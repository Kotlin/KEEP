# Kotlin Contracts

* Type: design proposal
* Author: Dmitry Savvinov
* Contributors: Stanislav Erokhin, Andrey Breslav, Mikhail Glukhih, Roman Elizarov, Ilya Ryzhenkov, Alexander Udalov
* Status: submitted
* Prototype: implemented
* Discussion: [KEEP-139](https://github.com/Kotlin/KEEP/issues/139)


## Summary

Support a way to **explicitly** express some aspects of function's behavior, thus allowing programmers to cooperate with Kotlin compiler by providing it with additional guarantees, getting more complete and intense analysis in return.


## Introduction

### Motivation / use cases

* Sometimes, compiler imposes overly conservative restrictions on some language constructs:
    * _Example:_ Usually, **val initialization in capture** is forbidden, because arbitrary lambda can be called more than once (leading to reassignment), or not called at all (leaving property uninitialized)
    *In some specific cases* (e.g. `run`, `synchronized`, etc.), function will call passed lambda exactly once, making initialization actually safe ([KT-6592](https://youtrack.jetbrains.com/issue/KT-6592))

      ```kotlin
      fun test() {
          val x: Int
          run {
              // Next line is reported as 'val reassignment', because compiler
              // can't guarantee that lambda won't be invoked more than once
              x = 42
          }
          // Next line is reported as 'uninitialized variable', because compiler
          // can't guarantee that lambda will be invoked at least once
          println(x)
      }
      ```

    * _Example:_ Usually, **smartcasts on captured vars** are forbidden, because arbitrary lambda can be called concurrently with some write, which will invalidate smartcast.  
    *In some specific cases*, function may guarantee that lambda won't be called after the whole call is finished (e.g. `run`, `forEach`, etc.), making smartcasts actually safe ([KT-7186](https://youtrack.jetbrains.com/issue/KT-7186))

      ```kotlin
      fun test() {
          var x: Int? = 42
          run {
              // Smartcast is forbidden here, because compiler can't guarantee
              // that this lambda won't be executed concurrently with 'x = null'
              // assignment
              if (x != null) println(x.inc)
          }
          x = null
      }
      ```

* Some functions may provide additional guarantees or restrictions, which could be used by compiler for the sake of analysis (e.g., additional smartcasts) if it were aware of them

  ```kotlin
  fun test(x: String?) {
      require(x != null)
      // Compiler doesn't know that if call to 'require' has finished successfully,
      // then its argument, namely, 'x != null', is true
      // Hence, there is no smartcast on the next line, while it is actually safe:
      println(x.length)
  }
  ```

    * See also [KT-14397](https://youtrack.jetbrains.com/issue/KT-14397), [KT-7566](https://youtrack.jetbrains.com/issue/KT-7566), [KT-19329](https://youtrack.jetbrains.com/issue/KT-19329), [KT-21481](https://youtrack.jetbrains.com/issue/KT-21481), [KT-8889](https://youtrack.jetbrains.com/issue/KT-8889)

* Currently, Kotlin compiler performs some particular analysis either always or never. Performing analysis over the whole project can be undesirable for various reasons:
    * If the analysis is too complex, it may significantly slow down compilation

    * Particular analysis can be relevant only in some contexts:
        * _Example:_ if the compiler knows for sure that method works in concurrent context, it may be sensible to perform more strict checks, e.g. about capturing a mutable state

          ```kotlin
          var x: Int = 42
          Thread {
              // Such code will work undeterministically: it will print either 42 or 43,
              // depending on what happened earlier: 'println' or assignment
              println(x)
          }.start()
          x = 43
          ```

        * _Example_: Users can decide to explicitly annotate some parts of their project with **checked exceptions,** getting benefits of safety and additional diagnostics, while avoiding burden of checking exceptions in the whole project (see [KT-18276](https://youtrack.jetbrains.com/issue/KT-18276))

### Short description

In the end, all issues above are about one and the same thing: *compiler doesn't know something that programmer does.*

To solve it, we propose to introduce a new language mechanism, called *Kotlin Contracts.* Its purpose is to allow programmer and compiler to *cooperate*:

* Programmer *explicitly* provides some *extra* information about code semantics (by writing down some additional language constructions)
* Compiler performs more extensive and precise analysis using that information

### Scope and restrictions

*Contracts* is a very broad model which imposes no specific limitations on its own. While this allows covering a wide range of use-cases and gives a lot of freedom for future extensions, at the same time it is very tempting to use contracts as a magical duct tape for any possible problems.

To avoid this, we want to pay special attention to the *scope* of this proposal. Here we will consider initial estimation, which is going to be revisited and refined in the future:

* Contracts primarily consider the **behavior of methods** rather than the **properties of values.** Properties of values should be handled by the type system rather than contracts.
    * It doesn't mean that if something *potentially* can be handled by the type system, then it is out of contracts' scope. Modern type systems are a very powerful tool and can be quite expressive regarding **methods' behavior**, but they are not necessarily the best tool for those use cases.
* Contracts shouldn't change fundamental language rules directly, such as overload resolution, inference, scoping, execution order, etc.
    * Note that contracts can influence type system **indirectly** through existing mechanisms (e.g. by providing more information for smartcasts)
* Currently, Kotlin Contracts is a completely compile-time mechanism. There are no plans on bringing it to the run-time in the near future
* Formal verification is a non-goal of Kotlin Contracts


## Model Description

### Effects

Function's behavior is expressed by its *effects*. Term *“effect”* is used here in a very broad sense: vaguely, this is a piece of knowledge about program context.  

If a function `f` has an effect `e`, then the invocation of `f` *produces* (or *fires*) effect `e`. Compiler tracks all produced effects and uses knowledge about them for its own needs (producing diagnostics, improving analysis, etc.).

An exact description of what compiler does with effects is deliberately missing: it depends on particular effect. However, for the sake of example, in this document (as well as in the basic prototype) we will use the following effects:

* `Returns(value)` — expresses that function returned successfully. Additionally, `value` may be provided to indicate which value function has returned
* `CallsInPlace(callable, kind)` — expresses the following guarantee:
    * `callable` will not be called after the call to owner-function is finished
    * `callable` will not be passed to another function without the similar contract

      > A careful reader will note that `CallsInPlace` is essentially an `inline`-lambda. Kotlin Compiler could automatically produce `CallsInPlace` for `inline`-lambdas, but in the prototype, we require to write effect explicitly.

      Additionally, `kind` may be provided to indicate that `callable` is guaranteed to be invoked some statically known amount of times. There are four possible kinds:  

      * `AT_MOST_ONCE`
      * `EXACTLY_ONCE`
      * `AT_LEAST_ONCE`
      * `UNKNOWN`

### **Conditional Effects**

Additionally, an effect can be enhanced with a *condition.* Condition can be thought of as a boolean expression in Kotlin, though this is a bit simplified view.

> Actually, in 1.3-prototype conditions are a subset of Kotlin boolean expressions, but we reserve the right to write some non-Kotlin constructions here.


We will write it as follows:

```
Effect -> Condition
```

The arrow in this notation is an **implication** in math-logic sense, so the whole statement should be read as **“If the effect is observed, then the condition is guaranteed to be true”**.

### Abstract Syntax

**Function's contract** is a collection of all its effects. For the sake of this proposal, we will use the following syntax for denoting that function has a contract:

```
[
effect_1
effect_2
...
effect_n
]
fun f() {
  ...
}
```

> We emphasize once more that this syntax is deliberately chosen to be unsuitable for real-world implementation because here we describe the *semantics *of contracts rather than actual syntax. See Prototype section for discussion of actual syntax.

### Examples

**Example #1**  
Consider following function:

```kotlin
fun isString(x: Any?): Boolean = x is String
```

We will use following abstract syntax for denoting its contract:

```kotlin
[Returns(true) -> x is String]
fun isString(x: Any?): Boolean = x is String
```

Then, later compiler may use this contract for improving analysis:

```kotlin
fun foo(y: Any?) {
    if (isString(y)) {
        // 1. Here, compiler has observed that 'isString(y)' returned 'true'
        // 2. Looking at the contract of 'isString', compiler concludes
        //    that 'y is String'
        // 3. This allows otherwise impossible smartcast on the next line:
        println(y.length)
    }
}
```


**Example #2**  
Consider following function:

```kotlin
fun require(value: Boolean) {
    if (!value) throw IllegalArgumentException("Failed requirement.")
}
```

We may annotate it with the following contract:

```kotlin
[Returns -> value == true]
fun require(value: Boolean)
```

Then, it can be used in the following way:

```kotlin
fun foo(y: Any?) {
    require(y is String)
    // 1. Here, compiler has observed that 'require' finished successfully
    // 2. Looking at the contract of 'require', compiler concludes
    //    that 'y is String == true', i.e. 'y is String'
    // 3. This allows otherwise impossible smartcast on the next line:
    println(y.length)
}
```


**Example #3**
Consider following function:

```kotlin
fun run(block: () -> Unit) {
    block()
}
```

We may annotate it with the following contract:

```kotlin
[CallsInPlace(block, EXACTLY_ONCE)]
fun run(block: () -> Unit)
```

Then, it can be used in the following way:

```kotlin
fun foo() {
    val x: Int
    run {
        // Here, the compiler knows that this lambda will be called exactly once,
        // so it's safe to make an assignment to 'val' on the next line
        x = 42
    }
    // 1. The compiler knows that lambda was already called at this point
    // 2. The compiler knows that lambda initializes 'x' properly
    // 3. Hence, it makes usage of 'x' on the next line safe:
    println(x)
}
```



## Prototype description

### Declaration Syntax

> Please note that syntax described in this section is experimental and may be changed in future (see "Compatibility Notice" for details)


`kotlin-stdlib` provides DSL for a fluent and flexible declaration of effects.
Any such declaration is started with call to `contract`**:**

```kotlin
inline fun contract(builder: ContractBuilder.() -> Unit)
```

**Convention:** `contract`-call should be the first statement in function's body

Each line of the lambda passed to `contract`, describes one *effect* of the function. To construct `Effect`, `ContractBuilder` exposes some helper methods:

```kotlin
class ContractBuilder {
    fun returns(): Effect
    fun returns(value: Any?): Effect 
    fun returnsNotNull(): Effect
    inline fun <R> callsInPlace(lambda: Function<R>, kind: InvocationKind = InvocationKind.UNKNOWN): Effect
}
```

**Note**. `returns(value: Any?)` accepts only `true`, `false` and `null` literals

Additionally, infix member function `implies` of `Effect` is provided, to denote conditional effect:

```kotlin
interface Effect {
    infix fun implies(booleanExpression: Boolean): Effect
}
```

Examples of DSL usage:

```kotlin
inline fun <R> run(block: () -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return block()
}

inline fun CharSequence?.isNullOrBlank(): Boolean {
    contract {
        returns(ConstantValue.FALSE) implies (this != null)
    }

    return this == null || this.isBlank()
}
```

**Notes:**

*  `contract`-call is never evaluated as a Kotlin expression (be it compile-time or runtime)!  
    Therefore, exact implementations of classes and methods of DSL in *stdlib* **do not matter** (in fact, they are implemented as empty declarations). Their sole purpose is to provide human-readable definition of contracts, type checking, and coding assistance. *Contract DSL* is processed by the compiler in a special way, by extracting semantics from contract declaration
* even though `implies` takes a `Boolean` argument, actually only a subset of valid Kotlin expressions is accepted: namely, null-checks (`== null`, `!= null`), instance-checks (`is`, `!is`), logic operators (`&&`, `||`, `!`)


_Pros:_

* Natural support in tooling: highlighting, completion, refactorings
* All references are naturally resolved in the scope of the function's body
* We have control over the binary format and can optimize it as we want
* Can be easily extended on compiler side by exposing new declarations in Contracts DSL
* Readability is acceptable

_Cons:_

* Need to implement and support binary format
* Awkward “first-statement” restriction
    * As a consequence, functions with expression body can't have a contract
* To check function's contract at least its first statement has to be resolved
    * This is a potential issue for performance in IDE

_Conclusion:_ this is a nice option to experiment with contracts, as it leaves enough room for future extensions and modifications and provides out-of-the-box tooling and compiler support. Declaration syntax is a bit awkward, but this is acceptable for purposes of the prototype.

**Alternatives considered:**

_1. Set of annotations which together form function's contract_

```kotlin
@CallsInPlace("block", EXACTLY_ONCE)
fun T.also(block: (T) -> Unit): T
```

_Pros:_

* easy to implement, no need to worry about runtime retention
* looks nice and clean for simple cases.

_Cons:_

* Quickly gets very verbose and unreadable when contract declaration consists of more than one part:

  ```kotlin
  @Returns(true)
  @receiver:ThenNotNull // How Returns(true) and ThenNotNull connected?
  fun CharSequence?.isNullOrEmpty(): Boolean
  ```

* Very inflexible: even simple extensions (composite conditions or function with several conditional effects) lead to an unusable mess of annotations, let alone more complex ones
* References to declarations have to be passed as strings, which complicates a bit support on the tooling side

_Conclusion:_ was implemented as the first iteration of the prototype, but was quickly rejected due to lack of flexibility and room for future extensions

_2. `@Contract`-annotation, which accepts one string parameter containing whole effect declaration_

```kotlin
@Contract("CallsInPlace(block, EXACTLY_ONCE)")
fun T.also(block: (T) -> Unit): T

@Contract("Returns true -> this != null")
fun CharSequence?.isNullOrEmpty(): Boolean
```

_Pros:_

* Possible to implement any syntax
* Possible to make any extensions

_Cons:_

* Need to support new language in Kotlin compiler: lexing, parsing, interaction with Kotlin on resolution side
* Need to support new language in IDE: highlighting, completion, refactorings
    * Because this is essentially language injection (language, passed in string literal), it complicates things further

_Conclusion:_ considered at some point, but required too much work without clear benefit over DSL-approach

### Compatibility notice

Kotlin Contracts is an experimental feature. However, it has several components, which have different stability and compatibility guarantees.

* *Syntax for declaring contracts* (i.e. Contracts DSL) is unstable at the moment, and it's possible that it will be completely changed in the future.
    * It means that writing your own contracts isn't suited for production usage yet
* *Binary representation* (in Kotlin Metadata) is stable enough and actually is a part of stdlib already. It won't be changed without a graceful migration cycle.
    * It means that you can depend on binary artifacts with contracts (e.g. stdlib) with all usual compatibility guarantees
* *Semantics*, as usual, may be changed only in exceptional circumstances and only after graceful migration cycle
    * It means that you can rely on the analysis made with the help of contracts, as it won't get broken suddenly

### Limitations and capabilities of the prototype

Here we summarize limitations of the prototype:

* Contracts are allowed only on functions, which are:
    * Final functions (members or top-level, not local) that override nothing (because inheritance not implemented yet)
    * Have block body (consequence of the first-statement convention)
* Compiler trusts contracts unconditionally (because verification not implemented yet); programmer is responsible for writing correct and sound contracts
* Only `Returns()`, `Returns() implies condition` and `CallsInPlace` are supported
    * `condition` must be an instance-checks (`is`, `!is`) or nullability-checks (`== null`, `!= null`), possibly joined with boolean operators (`&&`, `||`, `!`)
* Regular `assert` isn't annotated with contracts, because its semantics can be changed at runtime

Capabilities of the prototype:

* Following improvements in the analysis are implemented:
    * Smartcast based on conditional `returns`
    * Initialization in `CallsInPlace`-closure
* Following functions in stdlib are annotated with contracts:
    * `run`, `with`, `apply`, `also`, `let`, `takeIf`, `takeUnless`: lambda-argument is invoked in-place and exactly once
    * `repeat`: lambda-argument is invoked in-place unknown amount of times
    * `assertTrue`, `require`, `check`: finishes iff passed boolean argument is `true`
    * `assertFalse`: finishes iff passed boolean argument is `false`
    * `assertNotNull`, `checkNotNull`, `requireNotNull`: finishes iff passed argument is not null

### Similar API

* [Contracts API in C++](http://www.open-std.org/JTC1/SC22/WG21/docs/papers/2015/n4415.pdf)

### Open questions

* Inheritance of the contracts: how contracts should be inherited?
* Verification of the contracts: how compiler should check if written contract contradicts function's actual semantics?
  * This is crucial for release of contracts-annotations
* Inference of the contracts: how compiler could infer contracts for user?
* Pluggable contracts: how user can extend Kotlin Contracts with some new constructions? In particular, how semantics of new constructions should be communicated to the compiler?
* Interop with `org.jetbrains.annotations.Contract`
* How to express contract of  `filter` and other functions from collections-API to support type refinement in cases like following:

```
fun test(x: List<Any?>) {
    val strings = x.filter { it is String }
    println(strings.first().length) // Should compile
}
```


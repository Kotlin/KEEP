# Refinement Classes

* **Type**: Design proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, Daniil Berezun
* **Issue**: [KT-51416](https://youtrack.jetbrains.com/issue/KT-51417/Restricted-types)
* **Prototype**: [Kotlin 2.1.20 Compiler Plugin](https://github.com/InversionSpaces/kotlin-refinement-plugin)

# Abstract

We propose extending Kotlin inline value classes to support refinement types, similar to those
found in other languages or libraries. This feature aims to enhance safety by allowing developers 
to specify more precise constraints on values.

# Table of Contents

- [Abstract](#abstract)
- [Table of Contents](#table-of-contents)
- [Introduction](#introduction)
  - [Refinement Types](#refinement-types)
  - [Motivational Example](#motivational-example)
- [Proposed Extension](#proposed-extension)
  - [Refinement Classes](#refinement-classes-1)
  - [Refining a Value](#refining-a-value)
  - [Mutable Values](#mutable-values)
- [Implementation](#implementation)
- [Rationale](#rationale)
- [Challenges](#challenges)
  - [Subtyping of Refinements](#subtyping-of-refinements)
  - [Parametrized Refinements](#refinement-parameters)
  - [Dependent Refinements](#dependent-refinements)
- [Q&A](#qa)
- [Related Work](#related-work)
  - [Arrow Analysis](#arrow-analysis)
  - [Liquid Haskell](#liquid-haskell)
  - [Scala Refined Library](#scala-refined-library)
- [Alternative Design](#alternative-design)
- [Appendix A: Prototype Plugin Capabilities](#appendix-a-prototype-plugin-capabilities)

# Introduction

## Refinement types

A refinement type $RT(T, p)$ is a type that narrows values of type $T$ to those that satisfy
the predicate $p$. In other words, values of type $RT(T, p)$ are those and only those values $v$ of type $T$
for which $p(v)$ is $true$. For example, we can define a type of positive integers as 
$Pos = RT(Int, v \rightarrow v > 0)$.

Refinement types are well suited for expressing pre- and post-conditions for functions
by encoding constraints into the types of arguments and return values.

Note that for a value to be of a refinement type is a semantic property and thus might
depend on the context of an execution. For example, if we have `val v: Int`, then in the 
body of `if (v > 0) { ... }` it can be considered to have type $Pos$.

In theory, the predicate of a refinement type could be arbitrarily complex and depend on the context of a refinement type definition. 
However, this KEEP focuses primarily on simple predicates that depend only on the argument.

## Motivational Example

Suppose we have a function that makes an API call:

```kotlin
suspend fun getBrokerMessages(
    tag: String,
    batchSize: Int,
    offset: Int,
): MessageBatch 
```

There are probably some pre-conditions on parameters. For example, `tag` can not be empty,
`batchSize` should be positive and `offset` should be non-negative. Sending invalid data to a remote 
server will most probably result in an error response, so it is reasonable to validate parameters locally:

```kotlin
suspend fun getBrokerMessages(
    tag: String,
    batchSize: Int,
    offset: Int,
): MessageBatch {
    if (tag.isEmpty()) {
        throw IllegalArgumentException("Invalid empty tag")
    }
    // Same for other parameters
}
```

We can approach this from another side by constraining parameter types:

```kotlin
value class Tag(val value: String) {
    init { require(value.isNotEmpty()) }
}

value class BatchSize(val value: Int) {
    init { require(value > 0) }
}

value class Offset(val value: Int) {
    init { require(value >= 0) }
}

// ...

suspend fun getBrokerMessages(
    tag: Tag,
    batchSize: BatchSize,
    offset: Offset,
): MessageBatch
```

This gives us a few benefits. Firstly, now it is impossible to accidentally confuse `batchSize`
and `offset` on the call site as they have distinct types. But more importantly, pre-conditions for parameters of the function are expressed 
directly in the signature of the function, instead of, for example, some combination of documentation 
and checks inside the function.

Also, now the user is forced to perform validation on the call site by constructing a value 
of the demanded type. However, if a lot of values are given semantic constraints like this,
it will soon become as easy to overlook potential violations of contracts as previously without
this change. But note that constraints are now given in some uniform way, so an idea arises: 
maybe the compiler can help by verifying constraints statically and warning the user only on
possible violations? This KEEP develops this idea.

Let's also consider the following usage of the function:

```kotlin
val tag: Tag = ...
val batchSize: BatchSize = ...
var offset: Offset = ...
repeat(42) {
    val batch = getBrokerMessages(tag, batchSize, offset)
    // process batch
    offset = Offset(offset.value + batchSize.value)
}
```

In this code snippet constructor argument of the `Offset` class is checked each iteration.
But through static analysis it might be possible to verify that the check is redundant. 
The compiler can possibly erase it, thus maintaining semantic correctness
of a value between calls and, in the best case, subtly improve performance.

# Proposed extension

## Refinement Classes

We propose to add a `Refinement` annotation which can be applied to inline value classes.
Such classes are called *refinement classes*. Their single value parameter is called
*underlying value*, and its type is called *underlying type*.

Refinement classes could express the predicate of the refinement in terms of `require` call inside their `init` block.
The argument of the `require` call is denoted as *predicate expression*. Predicate expression should have type `Boolean`
and depend only on the underlying value.

Example definition of a refinement class:

```kotlin
@Refinement
@JvmInline
value class PosInt(val value: Int) {
    init { require(value > 0) }
}
```

Predicate expression could not be arbitrary. A set of supported predicate expressions depends 
on the underlying type. One underlying type could also potentially have different types of possible refinements
that cannot be used together. For example, only comparisons with constants and logical conjunction of them could
be allowed for underlying type `Int`, yielding refinements that correspond to integer intervals.

In case of an unsupported predicate expression, a compilation warning should be issued on it to notify the user.
The `Refinement` annotation then should have no further effect.

## Refining a Value

A value can be refined by calling a refinement class constructor. So each call to the primary constructor of the
refinement class should be analyzed statically to determine if the predicate holds for the constructor argument. There are
three possible outcomes of such analysis:
- It was deduced that predicate holds. If analysis is sound, the runtime check of the predicate might be erased
- It is unknown whether predicate holds or not. Then the runtime check should be left in place. A compilation warning might be issued to notify the user of a possible bug
- It was deduced that predicate does not hold. Then a compilation error should be issued

The analysis is not expected to be interprocedural, but it should account for the context of a constructor call
to support explicit constraint checks by the user and possibly more complicated cases.

For example, in the following code the constructor call should be verified:

```kotlin
val v1 = readLine().toInt()!!
var v2 = readLine().toInt()!!
if (v1 > 0) {
    while (v2 < 0) {
        val pos = Pos(v1 - v2)
        // ...
    }
}
```

## Mutable Values

Note that mutable underlying values pose a great challenge for the proposed functionality.
Mutability allows a value to stop satisfying the refinement predicate at some point, and this
can be hard to track. 

For example, one might try to introduce `NonEmptyList` like so:

```kotlin
@Refinement
@JvmInline
value class NonEmptyList<T>(val value: List<T>) {
    init { require(value.isNotEmpty()) }
}
```

But usage of this refinement class could lead to errors:

```kotlin
val list = mutableListOf(42)
val nel = NonEmptyList(list)
emptyListSomewhereDeepInside(list)
// Here nel.value is empty
```

Thus, usage of non-deeply-immutable types for underlying values should be prohibited.

# Implementation

Many existing implementations of refinement types are based on SMT solvers (however, there are other approaches,
for more info see [related work](#related-work)). But we propose to implement described functionality 
as intraprocedural control- and data-flow analysis, separate for each refinement kind. For example,
`Offset` and `BatchSize` refinement from [motivational example](#motivational-example) could be supported
by classical interval analysis for integers.

We also propose to use existing CDFA facilities already presented in the Kotlin compiler 
and deliver the solution as a compiler plugin.

We believe this approach to have several benefits:
- CDFA has better performance compared to SMT-solvers-based solutions. It is more important for practical applications than completeness offered by SMT solvers
- Compiler code reusage greatly simplifies development of this feature. No need to develop standalone tools
- Form of a compiler plugin makes this functionality an explicit opt-in and leaves the development of the solution relatively independent of the compiler development

However, it has disadvantages as well:
- CDFA does not have the generality of SMT-solvers. Each kind of refinement should be developed separately
- At the moment, the corresponding API of the Kotlin compiler is unstable, so maintenance of the solution might require a lot of rewrites

Elaborating on the first point above, to introduce a new refinement (e.g., intervals for integer values), one should define and implement:
- Corresponding lattice (e.g., interval lattice)
- Corresponding monotone transfer function, which usually requires:
- - Abstract evaluation of operations on the lattice (e.g., arithmetic operations)
- - Abstract interpretation of conditions (e.g., `if (v > 0) { ... }`)
- Appropriate widening and narrowing if the lattice is not of finite height

As a proof of concept, we developed a K2 compiler plugin which supports interval refinement for integer values.
Examples of supported refinements and verified code can be found in [Appendix A](#appendix-a-prototype-plugin-capabilities).

# Challenges

## Subtyping of Refinements

It is natural to incorporate refinement types into the subtyping relation in the following way:
$RT(T, p) <: RT(S, q)$ if $T <: S$ and $\forall v \in T: p(v) \Rightarrow q(v)$. However, checking 
implication on predicates is a hard task in practice, especially without SMT solvers. Also, this 
subtyping rule is more of a structural kind and feels off for Kotlin nominal subtyping.

User can still define explicit conversions between refinement classes. Analysis might verify
such conversion, but if it fails, the user takes responsibility.

For example:

```kotlin
@Refinement
@JvmInline
value class NonNeg(val value: Int) {
    init { require(value >= 0) }
}

@Refinement
@JvmInline
value class Pos(val value: Int) {
    init { require(value > 0) }
    
    // Deduced to be correct, runtime check might be eliminated during compilation
    fun toNonNeg(): NonNeg = NonNeg(value)
}
```

## Parametrized Refinements

Unfortunately, the proposed design does not provide the possibility to create general, parametrized refinements.
This can lead to low code reusage and a lot of boilerplate code for refinement classes.

For example, something similar to the following code is unachievable:

```kotlin
@Refinement
@JvmInline
value class IntInRange<a : Int, b : Int>( // pseudo-syntax
    val value: Int
) {
    init { require(value >= a && value <= b) }
}

typealias Pos = IntInRage<1, Integer.MAX_VALUE>
typealias NonNeg = IntInRage<0, Integer.MAX_VALUE>
```

To support this, we need the type system to allow manipulating values at the type level.
We believe there are at least two ways to make it happen:
- Introduce literal types to the type system so they can be used as generic type parameters. Note that they are tricky to support in the presence of JVM type erasure. For example, Scala supports retrieving value from a literal type with `given` instances (similar to context parameters in Kotlin) which constraints their usage
- Introduce type operators (constructors) that support value parameters directly. This approach is similar to Liquid Haskell type aliases and Ada generic packages. However, this is usually implemented by generic instantiation (like C++ templates), which again does not fit well with JVM

Those features are broad and not obvious to implement. Also, they are beneficial in many applications other than parametrized refinements.
So we decided not to develop them here.

For more context on technologies mentioned in this section, refer to [Related Work](#related-work).

## Dependent Refinements

After discussing parametrization of refinements by static values, we might imagine parametrizing them by runtime values.
In other words, support dependent types, only limited to the form of refinement types.

Note that if there was static parametrization, it would be already possible to "link" together refinements in one definition
by imposing constraints with the same parameter on them.
For example (here `N` is statically known integer):

```kotlin
fun <T, N : Int> find(list: ListOfSize<T, N>, value: T): IntInRange<-1, N> = ...
```

However, this would be of little use as it is possible to apply this function only
to a list with statically known size. Otherwise, we could not "lift" list size to static level:

```kotlin
val list: List<Int> = ...
find<Int, ???>(list) // no way to apply find
```

But refinement types are already associated with a predicate. We could allow this predicate to capture value from
context as lambdas do. For demonstration, it is convenient to adopt notation from [Alternative Design](#alternative-design).
For example:

```kotlin
fun <T> find(list: List<T>, value: T): Int satisfies { -1 <= it && it < list.size() } {
    var index: Int = -1
    // find the value
    return index
}
```

It is unclear what notation to use for return if explicit refining is adopted.
If the return type is repeated - `return index as Int satisfies { -1 <= it && it < list.size() }` - it might denote 
another refinement because `list.size()` might be changed during execution of the body.

Such anonymous refinement definition should not actually capture `list`. It should be used only for
static analysis and runtime check of the return value. However, implementation of the latter is unclear:
again, `list` might change during execution of the body, so the compiler might be required to 
evaluate and store the result of `list.size()` on entering the function.

This functionality is similar to pre- and post-conditions of Ada (see [Related Work](#ada-language)).

# Rationale

We admit that the proposed above extension might feel verbose and inconvenient while offering no strict guarantees.
For example, the user has to explicitly wrap values to refinement classes and unwrap them back.
Below we provide our reasoning behind such design.

It is hard to have a static analysis that is complete and performant enough for practical applications
in non-trivial refinement domains. So our best hope is for it to be sound. That is why we did not want 
to introduce new (especially implicit) execution semantics based on the analysis.

On the other hand, we strived for a simple design that would not introduce changes to syntax, type system, and 
other aspects of the language.

Those considerations lead us to the design that is a combination of other Kotlin features 
(see also [Why extend inline value classes specifically?](#why-extend-inline-value-classes-specifically)).
The proposed implementation is an enhancement to what already could be expressed:
it offers additional support for writing safe code and possible optimizations.
At the same time, programs remain valid without it, relying on runtime checks.

Somewhat opposite approach is discussed below in [Alternative Design](#alternative-design).

# Q&A

### Why extend inline value classes specifically?

Inline value classes were chosen as a base for refinement classes because:
- They impose the restriction of a single value parameter that fits well with desired refinement classes behavior
- They might be represented in runtime as just the underlying value in some cases when compiler optimization is applicable
- They provide a way to express refinement predicate without introducing any special syntax or logic. If a check is not decided to be eliminated, it will be executed in runtime following standard semantics of `init` block

### Why not integrate with smartcasts?

For any non-null type $T$ the following equality can be considered: $T = RT(T?, v \rightarrow v \neq null)$. 
Similarly, for `interface I` and `class S : I`, $S = RT(T, v \rightarrow v \text{ is } S)$. 

Thus, smartcasts like this could be regarded as a limited refinement type deduction from context:

```kotlin
val v: Int? = ...
if (v == null) return
// here v is Int

val v: Base = ...
if (v !is Derived) return
// here v is Derived
```

However, smartcasts do not change the runtime representation of the value. But the proposed design introduces a distinct 
class for a refinement type, thus making it incompatible with smartcasts. Another approach which fits better with
smartcasts is discussed in [Alternative Design](#alternative-design).

# Related work

## Arrow analysis

[Arrow analysis Kotlin compiler plugin](https://arrow-kt.io/ecosystem/analysis/) also implements static analysis for value constraints
in Kotlin code. Our proposal bears great resemblance to class invariants found in arrow analysis.
However, arrow analysis is based on SMT solvers and thus lacks performance for practical applications.
Also, it was not ported to the K2 compiler for the moment.

## Liquid Haskell

[Liquid Haskell](https://ucsd-progsys.github.io/liquidhaskell/) is arguably the most prominent implementation of refinement types for a general-purpose 
programming language. However, it is based on SMT solvers too. It also requires a lot of annotations
from the user written in a specific sublanguage.

## Scala Refined Library

[Scala refined library](https://github.com/fthomas/refined) is an interesting implementation of refinement types as it does not
require any external tools or even compiler plugins. It heavily relies on the following features of Scala, which are mostly 
unavailable in Kotlin: intersection types, literal types, inductive typeclass instance deduction, powerful macro system.

The library actually supports any (possibly user-defined) representation for refinement types. Out of the box it 
provides representations with intersection types and value classes (similar to those in Kotlin). For example, 
`Int` refined with a lower bound can be expressed as type `Int && Greater[100]` (Here `100` is a literal type, 
argument to generic class `Greater`). Alternatively, the same refinement can be represented as instance of
`Refined[Int, Greater[100]]`, where `Refined` is `final class Refined[T, P] private (val value: T) extends AnyVal`.
Both approaches share the following properties:
- Refinements are lightweight: they exist only on the level of types in compile time. In the runtime only the underlying value is left
- Refined value can be used where value of the underlying type is expected. For intersection types it follows from their semantics. In other cases, implicit conversion is inserted
- Implicit weakening is supported. For example, `Refined[Int, Greater[100]]` can be used in place of `Refined[Int, Greater[0]]` because `Greater[100]` is a strictly stronger refinement than `Greater[0]`. This is achieved through a combination of macros, typeclass deduction, and implicit conversions. User can define inference rules for custom refinements through typeclass instances

Compile-time validation of refinements is supported for constant expressions, but otherwise refining a value always involves
checking refinement predicate in the runtime. Macros and typeclass deduction are known to negatively affect scala compilation time. 
However, this approach is probably still more performant than the use of SMT solvers.

Below is an example of refinement definition:

```scala
// Define the refinement type itself
final case class Greater[N](n: N)

// Define actual refinement predicate by providing a ` Validate ` typeclass instance
implicit def greaterValidate[T, N](implicit
  wn: WitnessAs[N, T], // Require that `N` is subtype of `T`
  nt: Numeric[T] // Require that the type `T` is a numeric type
): Validate.Plain[T, Greater[N]] =
  Validate.fromPredicate(
    t => nt.gt(t, wn.snd), // Actual predicate implementation
    t => s"($t > ${wn.snd})", 
    Greater(wn.fst)
  )

// Define inference rule
implicit def greaterInference[C, A, B](implicit
  wa: WitnessAs[A, C],
  wb: WitnessAs[B, C],
  nc: Numeric[C]
): Greater[A] ==> Greater[B] =
  Inference(
    nc.gt(wa.snd, wb.snd), // Actual implication condition
    s"greaterInference(${wa.snd}, ${wb.snd})"
  )

// Now, to refine a value, we call a function that performs validation in the runtime
// It returns `Left` with the error description in case validation fails
val v: Int = ...
val refinementResult: Either[String, Refined[Int, Greater[100]]] = refineV(v)
val rv: Refined[Int, Greater[100]] = refinementResult.get()
// We can weaken refinement implicitly
val weakened: Refined[Int, Greater[0]] = rv
```

Note also that there is the [iron library](https://github.com/Iltotore/iron) with much similar functionality.
However, it supports scala 3 exclusively and uses its new features. 
Refinement types are represented as opaque type aliases to the underlying type with type bounds:

```scala
/**
 * An Iron type (refined).
 *
 * @tparam A the underlying type.
 * @tparam C the predicate/constraint guarding this type.
 */
opaque type IronType[A, C] <: A = A
```

## Ada Language

[Ada Programming Language](https://ada-lang.io/) is focused on developing reliable and correct software. It provides
a handful of features for contract-driven development. Some of them, namely range types, [subtype predicates](http://ada-auth.org/standards/12rat/html/Rat12-2-5.html) and
[type invariants](http://ada-auth.org/standards/12rat/html/Rat12-2-4.html), are quite similar to refinement types
in the sense that they allow constraining values of types with predicates:
- Range types, as the name suggests, allow constraining value to a specific range
- Subtype predicates are divided into two flavors:
- - Static predicates allow predicates only from a small sublanguage. In return, they get more support from the compiler
- - Dynamic predicates allow any boolean expression as a predicate
- Type invariants also allow any boolean expression

All the predicates are checked in the runtime at specific boundaries, mostly at variable initializations and 
procedure (or function) call and return. Such checks can be disabled completely with a compiler option.

Below are example definitions. Type invariants differ from dynamic subtype predicates mostly in application scope 
(they are intended for private types), so they are not examined here.

```ada
type Int7 is new Integer range 0..127;
  
subtype Positive is Integer
    with Static_Predicate => Positive > 0;

type Even is new Integer
    with Dynamic_Predicate => Even mod 2 = 0; -- mod is not allowed in Static_Predicate
```

Note that despite the name, subtype predicates can be applied not only to subtypes, but also to new types.
Difference between `subtype T is Integer` and `type T is new Integer` is that former is a subtype of `Integer` and
can be used as such, while later requires an explicit conversion to `Integer`.

Besides predicates for types, Ada supports [pre- and post-condition](http://ada-auth.org/standards/12rat/html/Rat12-2-3.html)
declarations for functions. They are also checked in runtime on function call and return, if not disabled by a compiler option.
For example:

```ada
function IntegerSquareRoot(X: Integer) return Integer is (...) -- implementation is omitted
    with Pre => X >= 0,
         Post => IntegerSquareRoot'Result * IntegerSquareRoot'Result <= X and 
                (IntegerSquareRoot'Result + 1) * (IntegerSquareRoot'Result + 1) > X;
```

As you can see, the core language supports described features mostly in the form of optional runtime checks. 
However, static analysis tools exist that take advantage of such specifications to actually prove the correctness of programs.
See [SPARK Language](https://docs.adacore.com/spark2014-docs/html/ug/en/introduction.html) based on Ada.

## Comparison

Here is a comparison table between the aforementioned solutions and our refinement classes proposal:

|                                    |                       Arrow Analysis                       |                             Liquid Haskell                              |                          Scala Refined Library                           |                        Ada Language                         |                                    Refinement Classes                                     |
|:-----------------------------------|:----------------------------------------------------------:|:-----------------------------------------------------------------------:|:------------------------------------------------------------------------:|:-----------------------------------------------------------:|:-----------------------------------------------------------------------------------------:|
| Underlying Technology              |                        SMT solvers                         |                               SMT solvers                               |        Scala type system, typeclass instance deduction and macros        |             Ada type system and other features              |                         Control and dataflow analysis, Kotlin FIR                         |
| Refining a Value                   |                         ✅ Implicit                         |                               ✅ Implicit                                |                                ❌ Explicit                                |       ✅ Implicit or explicit (controlled by the user)       |                                        ❌ Explicit                                         |
| Insuring Safety                    |                ✅ Complete static deduction                 |                       ✅ Complete static deduction                       |              ❌ Runtime checks (compile-time for constants)               |        ❌ Runtime checks (compile-time for constants)        |        :warning: Partial static deduction with possible runtime check elimination         |
| Compatibility with Underlying Type |                         ✅ Implicit                         |                               ✅ Implicit                                |                                ✅ Implicit                                |       ✅ Implicit or explicit (controlled by the user)       |                                   ❌ Explicit unpacking                                    |
| Supported Predicates               | Expressions including booleans, numbers, object properties | Expressions including booleans, numbers and lifted functions (measures) |                                Arbitrary                                 |                          Arbitrary                          | Refinement dependent, generally simple expressions depending only on the underlying value |
| Parametrized Refinements           |                       ❌ Unsupported                        |                               ✅ Supported                               |                      ✅ Supported with literal types                      |              ✅ Supported with generic packages              |                                       ❌ Unsupported                                       |
| Dependent Refinements              |   ✅ Supported (in the form of pre- and post-conditions)    |                               ✅ Supported                               |           :warning: Limited support with scala dependent types           |    ✅ Supported (in the form of pre- and post-conditions)    |                                       ❌ Unsupported                                       |
| Refinements Subtyping              |    ✅ Supported through predicates implication deduction    |          ✅ Supported through predicates implication deduction           | :warning: Limited support through inductive user-defined inference rules | :warning: Only explicit `subtype` definitions are supported |                       ❌ Unsupported, explicit conversions required                        |
| Compilation Performance Cost       |                            High                            |                                  High                                   |                                 Moderate                                 |                          Moderate                           |                                         Moderate                                          |
| Runtime Performance Cost           |                            Zero                            |                                  Zero                                   |                        Moderate (runtime checks)                         |                  Moderate (runtime checks)                  |                  Moderate (for boxing and not eliminated runtime checks)                  |

# Alternative Design

## Representation

Refinement classes introduce a new type different from the underlying type (compiler could
optimize inline value classes to the underlying type in runtime, but it is not guaranteed). A different approach was
discussed where a refinement type is a subtype of the underlying type and has the same runtime representation.
For comparison purposes, we will denote refinement types expressed in this alternative way as *refinement subtypes*.

Some new syntax for defining a refinement type has to be introduced. For example:

```kotlin
// satisfies is a new keyword
typealias RefinedT = T satisfies { <predicate> }
// for example
typealias Pos = Int satisfies { it > 0 }
```

All considerations of supported underlying types and refinement predicates from the refinement classes apply here.
However, now we have no predefined execution semantics, so it has to be introduced.
After analysis all refinement subtypes should be erased to corresponding underlying types.
Instead of possibly eliminating predicate checks for correct conversions to refinement classes,
implementation now should insert them where correctness was not deduced for conversion to a refinement subtype,
even if a refinement is not supported at all.

## Refining a value

There is a choice whether refining a value should be explicit or implicit.
This choice might be even left for the user by introducing different syntax for two cases
(similar to Ada language, see [Related Work](#ada-language)).
For example:

```kotlin
subtype PosImplicit = Int satisfies { it > 0 }
newtype PosExplicit = Int satisfies { it > 0 }

val v: Int = ...
val pos: PosImplicit = v // OK
val pos: PosExplicit = v // FAIL
val pos = PosExplicit(v) // OK
// possible alternative syntax, as we do not construct anything
val pos = v as PosExplicit
```

For the case of implicit refining, usages of values with type `T`
(or some other refinement subtype with underlying type `T`) as values of the refinement subtype
should be analyzed statically instead of constructor calls for refinement classes.
For example:

```kotlin
fun usePos(p: PosImplicit): Int = ...

val v: Int = ...
if (v > 0) {
    usePos(v) // should be deduced to be correct
} 
```

Implicit refining makes code rather hard to manage. Subtle code change might make static analysis fail and suddenly
introduce a runtime check where there was none, and it is not indicated by code. For example, suppose an implementation
has deduced that a runtime check is not needed in the following code:

```kotlin
fun usePos(p: PosImplicit) = ...

var i = 0
while (i < 42) {
    usePos(i) // runtime check is not inserted
    // a lot of code
    i += 1
}
```

But after a code change, analysis failed, so a check had to be inserted:

```kotlin
var i = 0
while (i < 42) {
    usePos(i) // runtime check inserted 
    // a lot of code
    i = complicatedIndexCalculation(i)
}
```

Compare this to the same code written using refinement classes, where a constructor call indicates a possibility
of a runtime check, no matter the result of an analysis:

```kotlin
@Refinement
value class Pos(val value: Int) {
  init { require(value > 0) }
}

fun usePos(p: Pos) = ...

var i = 0
while (i < 42) {
    usePos(Pos(i)) // runtime check left in place 
    // a lot of code
    i = complicatedIndexCalculation(i)
}
```

Also, refining a function parameter type might introduce runtime checks on all callsites if refining is implicit.

In the case of explicit refining, explicit conversions to a refinement subtype should be analyzed statically,
much like for refinement classes.
For example:

```kotlin
fun usePos(p: PosExplicit): Int = ...

val v: Int = ...
if (v > 0) {
    usePos(PosExplicit(v)) // should be deduced to be correct
    // or
    usePos(v as PosExplicit)
} 
```

Note that in this case refinement subtypes with empty predicate (constant `true`) would satisfy user request for `typetag`
(see [KT-75066](https://youtrack.jetbrains.com/issue/KT-75066/Feature-Request-typetag-Keyword-for-Compile-Time-Type-Safety)).

## Subtyping

Subtyping on refinements is not trivial (see [Subtyping of refinements](#subtyping-of-refinements)),
and for refinement subtypes there are different (even non-orthogonal) ways to support it.

In the case of implicit refining, refinement subtypes are implicitly compatible (with runtime checks), so they already
act somewhat as subtypes of each other. However, the issue still arises at the level of types, see remark on
bounded quantification in the [Disadvantages](#disadvantages) section.

In the case of explicit refining, the situation is more interesting.
We could exclude subtyping of refinement subtypes and require explicit conversion between them
just as for refining a value of the underlying type.
Or else we could rely on predicate implication deduction capabilities of an implementation,
effectively integrating it into the type system.

In both cases, interpretation for refinement subtypes of refinement subtypes should be defined or explicitly prohibited.
If supported, it allows extending nominal subtyping to refinement subtypes
(similar to what Ada does, see [Related Work](#ada-language)).
Subtype should combine inherited predicate with its own.
For example:

```kotlin
typealias PosEven = Pos satisfies { it % 2 == 0 } // the actual predicate is `it > 0 && it % 2 == 0`
// `PosEven` might be considered as a subtype of `Pos` by nominal type system
```

## Smartcasts

We might support `is` for refinement subtypes. Making `v is RefinedT` equivalent to `v is T && <predicate>(v)`
would align refinement subtypes and smartcasts (see also [Why not integrate with smartcasts?](#why-not-integrate-with-smartcasts)).
For example:

```kotlin
fun usePos(p: Pos): Int = ...

if (v is Pos) { // same as `v is Int && v > 0`
    usePos(v)
}
```

## Operations

This representation is also much more convenient for the user as
a refinement subtype inherits all operations from the underlying type.
An implementation might even automatically refine some of them, but the user can always do it manually with overloading.
For example:

```kotlin
operator fun Pos.plus(other: Pos): Pos = (this as Int).plus(other) as Pos
```

## Disadvantages

We did not pursue this approach for the following reasons:
- While the refinement classes are built on existing features, refinement subtypes require new syntax and type system changes
- It seems impossible to achieve such functionality with just a compiler plugin
- If introduced, structural subtyping on refinement subtypes does not fit well with Kotlin nominal subtyping
- If introduced, implicit refining and conversions between refinement subtypes would make code harder to manage
- It is unclear how refinement subtypes should interact with existing type system features.
  For example, if structural subtyping is introduced, what types should be allowed for `T` in `fun <T : Pos> f(v: T): T`?
  Being a subtype of `Pos` is being at-least-as-strong refinement,
  it means that to type check an application of `f` typesystem has to be able to reason about refinement predicate implications.
  It might be decidable for some domains, but not all

# Appendix A: Prototype Plugin Capabilities

Implementation note: the prototype plugin implements just one `FirFunctionCallChecker` which looks for constructor calls
of classes that are annotated with specific annotation. The plugin resolves and analyzes the actual refinement class
declaration on the first encountered constructor call. But this means that if the constructor is never called, the refinement
class declaration is never analyzed or checked for correctness.

## Integer Interval Refinement

The prototype plugin supports interval refinements for integer values. Allowed refinement predicates are boolean expressions
built from comparisons of underlying value with constants, logical conjunction, and disjunction. For example:

```kotlin
@JvmInline
@Refinement
value class Positive(val value: Int) {
    init { require(0 < value) }
}

@JvmInline
@Refinement
value class From0To128(val value: Int) {
    init { require(value >= 0 && value <= 128) }
}
```

Complex expressions are supported, but they are required to yield a continuous interval:

```kotlin
@JvmInline
@Refinement
value class From0To64(val value: Int) {
    init { require((value >= 0 && value <= 20) || (value < 65 && value > 5)) }
}
```

If a predicate does not correspond to a continuous interval, a compilation error will be issued:

```kotlin
@JvmInline
@Refinement
value class Incorrect(val value: Int) {
    // e: Unsupported predicate
    init { require((value >= 0 && value <= 20) || (value <= 64 && value > 32)) }
}
```

The plugin also warns the user if a refinement class defines an empty set of values:

```kotlin
@JvmInline
@Refinement
value class Empty(val value: Int) {
    // w: Refinement predicate defines empty set of values
    init { require(value >= 0 && value <= -20) }
}
```

## Integer Intervals Analysis

The plugin performs integer interval analysis on control flow graph containing refinement class constructor call.
It tries to gather information from context: from `if`, `when` and `while` conditions, also from other refinement classes.
For research purposes, it issues a warning in all cases: constructor call is correct, incorrect, or it 
failed to deduce correctness. 
For example:

```kotlin
val v = readLine()?.toInt()!!
if (v > 80) { // Deduced interval: [81, +inf)
    // w: Constructor call is correct
    val t = Positive(v)
}
```

```kotlin
val v = readLine()?.toInt()!!
if (v > 80) { // Deduced interval: [81, +inf)
    when {
        v < 100 -> { //  // Deduced interval: [81, 99]
            // w: Constructor call is correct
            val t = From0To128(v)
        }
    }
}
```

```kotlin
val v = readLine()?.toInt()!!
if (v > 0 && v < 42) { // Deduced interval: [81, +inf)
    // w: Constructor call is correct
    val t1 = From0To64(v)
    // w: Constructor call is correct
    val t2 = From0To128(t1.value)
}
```

```kotlin
val v = readLine()?.toInt()!!
while (v >= 0) { // Deduced interval: [0, +inf) 
    // w: Failed to deduce correctness of constructor call 
    val t = From0To64(v)
}
```

```kotlin
val v = readLine()?.toInt()!!
while (v < 0) { // Deduced interval: (-inf, -1]
    // w: Constructor call is incorrect
    val t = From0To64(v)
}
```

Conditions in context can contain other expressions as well:

```kotlin
val v = readLine()?.toInt()!!
val s = "some string"
if (v > 0 && s.isNotEmpty()) { // Deduced interval: [1, +inf) 
    // w: Constructor call is correct
    val t = Positive(v)
}
```

At the moment `else` branch of `if` does not take into context negation of `if` condition. Similarly,
negation of previous `when` branches conditions is not accounted for during analysis of a branch.

Note that conditions are approximated to an interval. Thus, sometimes analysis could not deduce total incorrectness of
a constructor call. For example:

```kotlin
val v = readLine()?.toInt()!!
if ((v < 0 && v > -10) || (v > 64 && v < 100)) { // Deduced interval: [-9, 99]
    // w: Failed to deduce correctness of constructor call 
    val t1 = From0To64(v)
}
```

The analysis supports constants and abstract evaluation of the following arithmetic operations: `+`, `-`, `*`. 
For example:

```kotlin
val v1 = readLine()?.toInt()!!
val v2 = readLine()?.toInt()!!
val v3 = -42
if (v1 > 100 && v2 > 0) {
    // w: Constructor call is correct
    val t = Positive(v1 * v2 - v3 + v2)
}
```

Mutable variables are supported:

```kotlin
val v1 = readLine()?.toInt()!!
var v2 = 42
if (v1 > 0) {
    v2 = v1
}
// w: Constructor call is correct
val t = Positive(v2)
```

The analysis also implements simple widening, so it can deal with loops:

```kotlin
var v1 = readLine()?.toInt()!!
var v2 = 0
while (v1 > 1 && v1 < 100) {
    v1 = v1 * 2
    v2 = v2 + 1
    // w: Constructor call is correct
    val t = Positive(v2) // Deduced interval: [1, +inf)
}
```

Currently, one of the most annoying flaws of the analysis is that it does not play well with lambda capture and 
is not integrated with contracts. Thus, it often fails on idiomatic kotlin code. For example:

```kotlin
var v = 0
repeat(10) {
    v = v + 1
    // w: Failed to deduce correctness of constructor call
    val t = Positive(v)
}
```
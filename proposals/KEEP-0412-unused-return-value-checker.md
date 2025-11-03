# Unused return value checker (a. k. a. `@CheckReturnValue`)

* **Type**: Design proposal
* **Authors**: Leonid Startsev, Mikhail Zarechenskiy
* **Contributors**: Alejandro Serrano Mena, Denis Zharkov, Kurt Alfred Kluever, Marat Akhin, Nikita Bobko, Pavel Kunyavskiy, Vsevolod Tolstopyatov
* **Status**: Experimental in 2.3. How to enable: [link](https://kotlinlang.org/docs/whatsnew-eap.html#unused-return-value-checker)
* **Discussion**: [KEEP-412](https://github.com/Kotlin/KEEP/issues/412)
* **Tracker**: [KT-12719](https://youtrack.jetbrains.com/issue/KT-12719)

## Synopsis and motivation

Improve functionality of the existing 'unused expression' diagnostic in Kotlin.
New diagnostic should be able to report complex expressions whose return values are meaningful (e.g., non-Unit) but are not used.

Consider the following example:
```kotlin
fun formatGreeting(name: String): String {
    if (name.isBlank()) return "Hello, anonymous user!"
    if (!name.contains(' ')) {
        "Hello, " + name.replaceFirstChar(Char::titlecase) + "!"
    }
    val (firstName, lastName) = name.split(' ')
    return "Hello, $firstName! Or should I call you Dr. $lastName?"
}
```

Did you spot the bug already? Yes, you are right — if the `name` does not contain whitespace, the exception will still be thrown when trying to obtain `lastName` from the `split` function.
This is because the author forgot to actually `return` the result of the `"Hello, " + name.replaceFirstChar(Char::titlecase)` operation, and that line did nothing in the program's control flow.

Our new diagnostic aims to report such cases, saving you time when analyzing exceptions and debugging the code.

## Table of contents

- [Synopsis and motivation](#synopsis-and-motivation)
- [Overview](#overview)
- [Goals](#goals)
- [Proposal](#proposal)
  - [Expression return value usage](#expression-return-value-usage)
  - [Ignorable expressions](#ignorable-expressions)
  - [Propagating expressions](#propagating-expressions)
  - [Ignorability annotations](#ignorability-annotations)
  - [Explicitly ignoring values](#explicitly-ignoring-values)
  - [Overriding and inheritance](#overriding-and-inheritance)
  - [Expect/actual matching](#expectactual-matching)
  - [Higher-order functions and further extensions](#higher-order-functions-and-further-extensions)
- [Migration Plan](#migration-plan)
  - [Opting-in your library](#opting-in-your-library)
  - [Feature Modes](#feature-modes)
  - [Metadata compatibility](#metadata-compatibility)
  - [Interop with existing annotations](#interop-with-existing-annotations)
- [Appendix: formal specification](#appendix-formal-specification)


## Overview

The problem of accidentally not using the result of a function call is well-known, and many existing languages have built-in compiler diagnostics or external linters for them, for example, `#[must_use]` [in Rust](https://doc.rust-lang.org/reference/attributes/diagnostics.html#the-must_use-attribute) or `[[nodiscard]]` [in C++](https://en.cppreference.com/w/cpp/language/attributes/nodiscard).
Java's approach to this problem is vast, and most existing linters have similar warnings — either for a predefined set of methods ([Sonar](https://rules.sonarsource.com/java/RSPEC-2201/)), or for methods annotated with `@CheckReturnValue` or similar annotations ([ErrorProne](https://errorprone.info/bugpattern/CheckReturnValue)).
The one particular language we want to highlight here is Swift, whose [approach is inverted](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0047-nonvoid-warn.md) in this regard:
It reports every unused non-void function, and the `@discardableResult` attribute is required to stop issuing the warning.


Based on the experience of existing linters and languages, we see that if a user declares a non-Unit return type in the function, then, in most cases, the return value is intended to be used later.
Conversely, cases where a function returns something but it's acceptable to omit this value are limited to well-known and narrow scenarios, accounting for only about 15% of all functions.
Based on this observation, it is inconvenient to place some kind of `@CheckReturnValue` annotation per function, since the majority of non-unit-returning functions (even in the Kotlin standard library) will have to be annotated with it.
For an ergonomic and clean developer experience, the default in Kotlin has to be reversed.
All non-unit-returning functions must be treated as requiring the use of their return value by default, with the exceptions marked with some kind of 'ignorable' annotation.

## Goals

By implementing this proposal, we aim to:

1. Prevent more (non-)trivial errors.
2. For code that meaningfully ignores values, force authors to structure the code in a way that the intent is clear and the reasoning is local.
3. Highlight weak and/or error-prone APIs in the language or libraries -- one that everyone ignores but probably shouldn't -- such as `File.delete(): Boolean`.

## Proposal

This proposal consists of two parts: the first explains terminology and rules, and the [second](#migration-plan) draws up the migration plan for the whole Kotlin ecosystem.

We use the word **ignored** to express that the value is not used.
Therefore, expressions or values that do not have to be used are **ignorable**.
To implement an inspection that would check whether the non-ignorable value is used, we need to define what exactly *ignorable expression* and *using the value* are.

### Expression return value usage

The proposal suggests that the return value of the expression `A` is used if this expression:

* Is a property, parameter, or local variable initializer (incl. delegates): `val a = A`, `public val x by A`
* Is an argument to `return` or `throw` expressions: `return A`, `throw A`
* Is an argument to another function call (including operator conventions): `foo(A)`, `A == 42`, `A + "str"`, `"Hi ${A}"`
* Is a receiver in a function call or function safe call: `A.foo()`, `A?.bar()`
* Is a **condition** in control flow constructions, such as `if`, `while`, and `when`: `if (A) ...`, `when(A) ...`, `when(x) { A -> ...}`, `while(A) ...`
* Is the last statement in lambda: `list.map { A }`

If the expression's return value is not used according to the rules above, and the expression is not ignorable by itself (see the following paragraphs), then a warning about unused return value is reported.

### Ignorable expressions

Naturally, not every expression's return value is useful. Some of the expressions are ignorable on their own, namely:

1. Expressions whose return type is `Unit`, `Nothing`, or `Nothing?`.

As the `Nothing` type is uninhabited, and the `Unit` type is generally ignored by the compiler itself and doesn't convey any meaning, this rule doesn't require further explanation.
For generic functions, we use a **substituted** return type here.
This means that for functions like `fun <T> fetch(name: String): T`, their calls will be considered ignorable if
`T` is inferred to be `Unit` or `Nothing?`, and non-ignorable otherwise.
Note that we do not add the `Unit?` type to this list. This type is most likely encountered in generic substitutions of nullable functions, e.g., `fun <T> tryAcquire(): T?`.
Most such functions expect their result to be checked for nullability, even if this result is not usable directly.

> For Java interop purposes, platform `Unit!`/`Nothing!` types and `java.lang.Void(!)` type, which can be encountered in generic overrides of Java functions, are also treated as ignorable.

2. Pre- and post-increment expressions

In expressions like `++i`, the return value doesn't always have to be used because they have a side effect.
This fact can not be expressed using the standard `@IgnorableReturnValue` annotation: the `i.inc()` function only increments the value but does not perform the assignment.
`inc()` function shouldn't be ignorable on its own.
Therefore, this particular operator call has its own place in exceptions.

3. Boolean control flow shortcuts

Results of the boolean operators `&&` and `||`, just like other operators in Kotlin, have to be used.
They're also quite popular for performing early returns or throws, e.g., `someCondition() || return`.
To avoid warnings in these cases, a special rule has been added: if the right-hand side of a boolean operator
has `Nothing` type (which includes `return` and `throw`, among others), then the whole expression is ignorable.

For example:

```kotlin
fun test(boolExpr: Boolean) {
    foo() && boolExpr // warning: unused value

    validateState() || return // OK, no warnings
    validateExists() && throw ExistingResourceException() // OK, no warnings
}
```

4. Calls to ignorable declarations

While expressions discussed above are ignorable due to their shape or type, regular calls can be ignorable or non-ignorable depending on the status of the callable:

#### Ignorable callables

> 'Callables' in Kotlin denote functions, properties, and constructors.

1. Defined as ignorable

Some functions (like well-known `MutableList.add`) do not expect their return value to be always used.
Authors of such functions can express this fact with the special [`@IgnorableReturnValue` annotation](#ignorability-annotations).
We call these functions _explicitly ignorable_.

2. Unspecified ignorability

As it was said in the proposal overview, we strive to check every non-unit function.
However, there will be significant [cooperation efforts](#migration-plan) from the community before we achieve that goal.
For the sake of migration, only callables that are specially marked will be considered as non-ignorable.
The rest of the callables will be considered _implicitly ignorable_, or _unspecified_.
Notably, most JDK functions (but not their Kotlin counterparts) will be implicitly ignorable.

> The difference between implicit and explicit ignorability is important for [overrides](#overriding-and-inheritance), but the checker treats them the same way.

### Propagating expressions

Besides ignorable and non-ignorable expressions, there is a third, special, category.
It is for expressions that cannot be ignorable or non-ignorable on their own, and their ignorability is determined based on their sub-expressions (arguments).
Alternatively, you can think of them as expressions that propagate ignorability up the expressions tree.
Currently, this category consists of **control flow expressions** and **type operators**.

#### 1. Control flow expressions

If the expression `A` is the last statement of an `if` or `when` **branch**, and the whole `if`/`when` expression is not used, then `A` is also considered not used.
Examples:

```kotlin
fun ifExample(x: Int): Any {
  if (x > 0) A else B // A and B are not used here, a warning should be reported for both of them.

  return if (x > 0) A else "" // A is used, no warning.
}
```

Elvis operator `A ?: B` is equivalent to `val tmp = A; if (tmp != null) tmp else B`.
However, because no actual `val tmp` is introduced in the code, and one cannot access it later, we
consider **both sides** of an Elvis operator propagating as well:

```kotlin
fun elvisExample() {
  A ?: B // both A and B are not used.

  foo() ?: return // foo() is considered unused because its value is lost after comparison with null.

  val f = foo() ?: return // foo() is used correctly.
}
```

Note that according to rules in [above](#expression-return-value-usage), position as a *condition* is treated as usage:

```kotlin
fun whenExample(x: Int) {
  when(x) { // Both A and B are not used here.
    0 -> A
    else -> B
  }

  when(x) {
    A -> B // A is used, but B and C are not.
    else -> C
  }

  val y = when(x) { // A and B are used because the `when` expression is a variable initializer.
    0 -> A
    else -> B
  }
}
```

Since the `try/catch/finally` construction in Kotlin is an expression, too, and its value is equal to the last expression(s) in corresponding `try` and `catch` blocks (but *not* in `finally` blocks), these
positions are propagating:

```kotlin
fun tryExample() {
    try {
        A
    } catch(e: IOException) {
        B
    } catch(e: Exception) {
        C
    } // A, B, and C are not used here. A warning is reported for all three of them.

    val x = try {
        A
    } finally {
        doSomething(B)
        C
    } // A is used because `try` is used as the `x` initializer.
      // However, `doSomething(B)` and `C` are not used because the `finally` block has no result value.
}
```

#### 2. Type operators

Similar to control flow expressions, type operators propagate the ignorability of their operands.
There are currently three type operators in Kotlin:

* `as` cast and `as?` safe-cast
* `is`/`!is` instance checks
* `!!` non-null assertion operator

> There is a formal definition of control flow analysis for this checker and propagations [in the appendix](#appendix-formal-specification).

### Ignorability annotations

In the scope of this proposal, two annotations will be added to the Kotlin standard library.
The first of them is `@IgnorableReturnValue`:

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class IgnorableReturnValue
```

This annotation is intended to express that calls of the annotated function are ignorable.
The API's author is responsible for manually placing this annotation on appropriate functions.

> Note that it does not have CONSTRUCTOR and PROPERTY targets because we want to discourage writing constructors and properties with side effects.

The second is `@MustUseReturnValues`:

```kotlin
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MustUseReturnValues
```

This annotation marks the *scope* (file or class) in which all the callables are non-ignorable.

> @MustUseReturnValues intentionally has a plural name and does not have `AnnotationTarget.FUNCTION`. We want to encourage authors to think about and design non-ignorable APIs as a whole, not on a per-function basis.

`@MustUseReturnValues` serves two purposes:

1. Specifying scope as non-ignorable in case you are overriding methods from an ignorable scope (see [Overrides](#overriding-and-inheritance)).
2. Opting in for checks in a more granular way.

Ideally, as an API author, you should globally mark your library as [ready to be consumed with checker](#opting-in-your-library) by enabling [corresponding feature mode](#feature-modes).
In cases where the API surface is large and it is problematic to perform migration at once, it is possible to do this on a per-class or per-file basis using this annotation.

### Explicitly ignoring values

In the ideal world, every function call is either explicitly ignorable and not used, or non-ignorable and correctly used by the caller.
In the real world, sometimes we need to drop the value even if the API's author does not want us to.
For those cases, to suppress the checker warning, one can use [underscore as a local variable name](KEEP-0412-underscores-for-local-variables.md):

```kotlin
fun foo(x: Int) {
  val _ = nonIgnorableCall(x) // A warning is not reported.
}
```

### Overriding and inheritance

When we talk about inheritance, we consider the base class as 'origin' of the API whose author put thought of whether certain methods in this class should be ignorable or not.
The main goal of the feature design in this area is to prevent an accidental or incorrect change of ignorability on overrides.
To achieve this goal, there are two rules in place:

1. **Ignorability is copied from the base class/interface by default.**

In particular, this means that if you implement any interface from the Kotlin standard library or other [opted-in](#opting-in-your-library) libraries,
you will automatically have non-ignorable status on corresponding callables, giving you warnings in the [Checker](#feature-modes) mode.
This is handy because functions like `Any.hashCode()` are considered to be declared in the Kotlin standard library,
and therefore all their overrides will be checked automatically.

2. **It is not allowed to override explicitly ignorable functions with non-ignorable functions.**

If we allowed overriding ignorable functions with non-ignorable ones, we may report incorrect results.
For example, if `Base` has an ignorable `foo` method, and it's overridden in `Derived` by a non-ignorable version, then we report a warning if we know that a value has type `Derived`, but this warning is "lost" if we only know it's a `Base`.
This breaks the substitution principle.

On the other hand, the reverse situation is fine in this regard.
It is allowed to make a function ignorable on override, if you know this particular implementation should not be checked:

```kotlin
@MustUseReturnValues
interface Foo {
  fun getSmth(): String // non-ignorable due to annotation
}

class FooImpl: Foo {
  // non-ignorable because base function is non-ignorable
  override fun getSmth(): String = System.getProperty("something")!!
}

object EmptyFoo: Foo {
  @IgnorableReturnValue
  override fun getSmth() = ""
}

fun bar(f: Foo) {
  f.getSmth() // unused return value warning
  FooImpl().getSmth() // unused return value warning
  EmptyFoo.getSmth()  // no warning
}
```

It is also possible to make _unspecified_ functions non-ignorable on override:

```java
// Some Java interface that is not annotated and therefore has unspecified ignorability
public interface JavaInterface {
  String getFoo();
  boolean doSmth();
}
```
```kotlin
@MustUseReturnValues
interface MyKotlin: JavaInterface {
  override fun getFoo(): String
  @IgnorableReturnValue
  override fun doSmth(): Boolean
}

fun check(i: JavaInterface, k: MyKotlin) {
  i.getFoo() // no warning
  k.getFoo() // unused return value warning
  k.doSmth() // no warning
}
```

Adding annotations this way allows you to 'enhance' methods with ignorability information if it wasn't there.
Consider doing this if you want to re-expose your class in the API.

We used `@MustUseReturnValues` to add ignorability information.
This is possible because it has _priority_ over callables from the base class.
In other words, to determine whether a callable is ignorable or not, these properties
are checked in this exact order:

1. Presence of `@IgnorableReturnValue` annotation or `@MustUseReturnValues` annotation on outer scopes.
2. Immediate parent's callable status, if it exists.
3. Selected [feature mode](#feature-modes).

### Expect/actual matching

While it may look similar to overriding, actualization of `expect` declarations is a different compiler mechanism.
To achieve the same goal of consistency and preventing accidental ignorability change, we use a different rule here:
**Ignorability should be the same for expect and actual callables.**
This means that annotations on declarations and/or [feature mode](#feature-modes) in common and platform compilations should
not contradict each other.
Note: implicit and explicit ignorability are not considered contradictory, since the checker does not report a warning for both of them.
For example:

```kotlin
expect class Foo {
    fun x(): String
    fun ign(): String
}

@MustUseReturnValues
actual class Foo {
    actual fun x(): String = "" // compilation warning, actualizing implicitly ignorable function with non-ignorable
    @IgnorableReturnValue actual fun ign(): String = "" // no warning, actualizing implicitly ignorable with explicitly ignorable
}
```

The only exception allowed here is actualization of a non-ignorable callable with unspecified callable (or vice versa) in case this callable is declared _outside the class_.
This helps in cases when the actual callable is contributed to the scope by a supertype or via typealias, for example:

```kotlin
@file:MustUseReturnValues

expect class Foo {
    fun x(): String
    @IgnorableReturnValue fun ign(): String
}
```
```java
// FILE: JavaFoo.java

public class JavaFoo {
    public String x() {
        return "";
    }
    public String ign() {
        return "";
    }
}
```
```kotlin
actual class Foo: JavaFoo() // No warnings
```

In the case above, methods `x()` and `ign()` are contributed to the `Foo` scope by Java supertype and therefore have unspecified ignorability.
The compiler would not report a warning here, but you may get different 'unused return value' warnings on common and platform sources, so try to avoid such situations.

### Higher-order functions and further extensions

Kotlin is well-known for its helpful standard library, which contains a lot of higher-order functions
that you might use every day: `let`, `apply`, `use`, and many more.
One of the patterns you might often see is adding some nullable value to a collection or a string builder:
`packageName?.let { list.add(it) }`.
Because we know that `MutableList.add` is an ignorable function, we do not want to report the whole construction as unused, even though the inferred type of `let` is `Boolean` and not `Unit`.
However, for cases where the non-ignorable function is called inside, e.g., `packageName?.let { "kotlin." + it }`, we want to warn
users if the whole expression is unused.
This problem forced us to realize that many (but not all!) higher-order functions should behave as [propagating expressions](#propagating-expressions), just like control flow expressions.
Unfortunately, there are no means to infer or detect this automatically, and no special syntax exists.

Therefore, we do not plan to address this problem in the current design stage.
`let`, and some other functions will be marked as `@IgnorableReturnValue` to avoid a large number of false-positive errors.
In the future, we plan to improve the situation by introducing a special *contract type* to express that a higher-order function call should be treated as a propagating expression.
This plan heavily relies on the [contracts](KEEP-0139-kotlin-contracts.md) feature of Kotlin, which requires quite some time to finalize.

## Migration plan

As was mentioned in the [overview](#overview) section, our ultimate goal is to check the usage of every non-ignorable function.
However, we cannot do this right away because there are many libraries and code out there that are not annotated with `@IgnorableReturnValue` when appropriate — for example, `io.netty.buffer.ByteBuf.clear()` returns self and is definitely ignorable.
Still, Netty is unlikely to be annotated with Kotlin's annotations.
Even if the checker itself was enabled by an additional flag, it could not check the whole code in the world at once — the migration cost would be unreasonably high, and there would be too many false positives.

To solve this problem, we somehow need to mark APIs verified by their authors to have `@IgnorableReturnValue` in all the right places. We can call such libraries/APIs *RVC-approved*.
Migration then can look like this:

1. A library author inspects their API and annotates it with `@IgnorableReturnValue` accordingly.
2. A new library version is released, which is now RVC-approved.
3. Library clients can safely enable the unused return value checker to get warnings about misuses of RVC-approved declarations from this library.
4. This, in turn, allows the clients to properly annotate their code as well and make a new, RVC-approved, version of their code.
5. Go to step 1.

kotlin-stdlib and some kotlinx libraries will be RVC-approved from the start, allowing you to benefit from this checker immediately.
We hope this feature will gain traction, and more library authors will follow, allowing the community to write much safer Kotlin code.

### Opting in your library

To implement the plan above, we need a way to mark a library/API as RVC-approved.
The initial idea was to use annotations everywhere, but after assessing technical implications, we
decided to go with the Kotlin-specific metadata information.
The metadata flag will be automatically set on every callable by the compiler when the author feels that their code is ready for that — i.e., `@IgnorableReturnValue` is placed everywhere it is supposed to be.
This will be controlled by a special feature switch (see [Feature modes](#feature-modes) below).

It is still possible to place the `@MustUseReturnValues` annotation in case you need it or simply wish to migrate only part of your API.
In that case, select the 'Checker only' mode and the compiler will set the metadata flag only on annotated declarations.

> This flag can be read with [kotlin-metadata-jvm](https://kotlinlang.org/docs/metadata-jvm.html) library if you wish to analyze compiled Kotlin code — for example, if you're writing your own static analyzer and you want to have 'unused return value' inspection in it.

### Feature modes

The switch for this feature has three states:

1. Disabled

This state is the default while the feature is experimental.

2. Checker only

In this mode, the checker only reports warnings for callables compiled as non-ignorable.
For example, code from libraries whose authors opted in for a full mode, or any code annotated with `@MustUseReturnValues`.

When this feature becomes stable, this state will be the default.
Therefore, all Kotlin users would immediately benefit from every library that is opted in without additional configuration.

3. Full mode

The compiler performs checks as stated above and sets the metadata flag for all declarations, so they become non-ignorable.
Thus, warnings are issued for the code from the libraries and for all local code.
Use this mode to mark your library as ready to be consumed with the checker, or to treat all your application code as non-ignorable.

Corresponding CLI flag is `-Xreturn-value-checker=disable|check|full`. Stay tuned for Gradle DSL updates.

### Metadata compatibility

You can freely switch between compiler modes.
Despite the feature being experimental, Kotlin metadata for it is compatible in both ways,
and using Full mode does not force the compiler to emit pre-release binaries.
Compiling your library in Full mode does not cause any problems for users with the feature disabled.

Since [ignorability annotations](#ignorability-annotations) are considered an experimental API in kotlin-stdlib, they cannot be used in the Disabled state.
This limitation will be lifted once the feature is stable.

### Interop with existing annotations

There are well-known Java annotation libraries that serve similar purposes, one of the most popular being [ErrorProne](https://errorprone.info/api/latest/com/google/errorprone/annotations/CheckReturnValue.html) from Google.
Some Java libraries, such as Guava, are already annotated with them.
To be able to provide the same safety level when using these declarations from Kotlin, we plan to treat the selected number of annotations similarly to Kotlin's `MustUseReturnValues` and `IgnorableReturnValue`.

To be treated as `kotlin.MustUseReturnValues`:
* `com.google.errorprone.annotations.CheckReturnValue`
* `edu.umd.cs.findbugs.annotations.CheckReturnValue`
* `org.jetbrains.annotations.CheckReturnValue`
* `org.springframework.lang.CheckReturnValue`
* `org.jooq.CheckReturnValue`

To be treated as `kotlin.IgnorableReturnValue`:
* `com.google.errorprone.annotations.CanIgnoreReturnValue`

Despite a similar purpose, most libraries do not provide a separate 'explicitly ignorable' annotation, expecting users to place `@CheckReturnValue` on every callable instead of a scope.
That is why we ask you to use Kotlin's annotations or Full mode, and this list is provided purely for Java interop reasons.

There is also a [JSpecify proposal](https://github.com/jspecify/jspecify/issues/200) aimed at providing similar functionality to the Java ecosystem.
If it is adopted and spread further, and existing Java linters would be able to recognize both JSpecify and Kotlin's annotations, then it would be possible to achieve complete safety in the mixed Java/Kotlin projects.

## Appendix: formal specification

This specification is phrased in terms of the [data flow analysis framework in the specification](https://kotlinlang.org/spec/control--and-data-flow-analysis.html#performing-analyses-on-the-control-flow-graph).

**Lattice elements.** For every data flow variable, we keep a pair with:

1. The “consumption”, which is a member of the partially ordered set:  
   `not consumed` \< `type consumed` \< `value consumed`
2. A boolean stating whether the consumption value should be ignored. The merge operation in this case should be a conjunction (and).

It seems useful to define whether we have “fully” consumed a value (by passing it to another function, for example), or we have only used it to check its type (which also includes nullability in this case). 
This allows us to model the behavior of some operations, like Elvis, much better.

**Initial element.** Each data flow variable starts as (not consumed, ignored \= false), unless otherwise specified.

**Calls.** For nodes of the form `$result = f($1, …, $N)`:

* Mark each of the variables `$1`, …, `$N` as `value consumed`.
* Initialized the `$result` as `not consumed`, with its `ignored` flag set to `true` if the f is an ignorable callable, or its type is ignorable.

**Assignment.** Assignment to variables effectively marks the involved values as `value consumed`. 
Note that we could have more precision here by introducing a more complex lattice (for example, keeping implications of the form “if this variable is consumed, then this value is consumed”),
but our design strives for more simplicity.

**Aliasing.** For nodes of the form `$result = $e` when `$result` is a fresh data flow variable (that is, not a variable introduced by the user),
the lattice element for `$result` should be copied from that of `$e`.

**Type operators.** For nodes of the form `$result = $0 is T`, `$result = $0 == null`, or their negations:

* Mark the variable `$0` as type consumed.
* Initialize `$result` as stated in *initial element*.

**Conditionals.** Whenever we find an `assume $0` node, mark `$0` as `value consumed`.

* This means that the conditional consumes the condition.

**Merge nodes.** The behavior of merge nodes (describing the result of branching) follows from the definition of the lattice elements above.
However, here is a description of their behavior on each data flow variable.

* The consumption is set to the “maximum” of all branches. That means that if a variable is consumed in any of the branches, it counts as consumed.
* The ignored flag is only set if this flag is set on every branch.

**Behavior on Elvis.** The desired behavior on `e1 ?: e2` can be derived from its expansion as follows:

```kotlin
val $lhs = eval e1
if ($lhs is Any) $result = $lhs
else $result = eval e2
```

* The value resulting from `e1` is `type consumed`, but not `value consumed`, unless something else is done afterward to it.
* The result of Elvis is only `ignored` whenever both branches are marked as such.

**Check.** At the end of the analysis, we check whether any of the data flow variables is marked as `not consumed` and `ignored = false`.
Additionally, we may want to report those which are only marked as `type consumed`.

* To improve reporting, we can track every variable resulting from merging the original values that were joined and reporting those instead.

**Implementation: approximation over expressions.**
The unused return value checker is not defined over the control flow graph but directly on the [expression tree](#propagating-expressions).
We can approximate the behavior of the former using the latter: the points at which we need to check for consumption are simply those at which an expression appears as a statement in the tree.

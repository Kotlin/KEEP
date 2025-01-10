# Unused return value checker (a. k. a. `@CheckReturnValue`)

* **Type**: Design proposal
* **Authors**: Leonid Startsev, Mikhail Zarechenskiy
* **Contributors**: Alejandro Serrano Mena, Denis Zharkov, Marat Akhin, Nikita Bobko, Pavel Kunyavskiy
* **Status**: Experimental in Kotlin 2.2
* **Discussion**: [link to discussion thread or issue]
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

- [Synopsis and Motivation](#synopsis-and-motivation)
- [Overview](#overview)
- [Goals](#goals)
- [Proposal](#proposal)
   - [Expression Return Value Usage](#expression-return-value-usage)
   - [Ignorable Expressions](#ignorable-expressions)
   - [Propagating Expressions](#propagating-expressions)
     - [Control Flow Expressions](#1-control-flow-expressions)
     - [Type Operators](#2-type-operators)
   - [Ignorability Annotations](#ignorability-annotations)
   - [Explicitly Ignoring Values](#explicitly-ignoring-values)
   - [Higher-Order Functions and Further Extensions](#higher-order-functions-and-further-extensions)
- [Migration Plan](#migration-plan)
   - [Marking Libraries with `@MustUseReturnValue`](#marking-libraries-with-mustusereturnvalue)
   - [Feature Modes](#feature-modes)
   - [Interop with Java and Existing Annotations](#interop-with-java-and-existing-annotations)


## Overview

The problem of accidentally not using the result of a function call is well-known, and many existing languages have built-in compiler diagnostics or external linters for them, for example, `#[must_use]` [in Rust](https://doc.rust-lang.org/reference/attributes/diagnostics.html#the-must_use-attribute) or `[[nodiscard]]` [in C++](https://en.cppreference.com/w/cpp/language/attributes/nodiscard).
Java's approach to this problem is vast, and most existing linters have similar warnings — either for a predefined set of methods ([Sonar](https://rules.sonarsource.com/java/RSPEC-2201/)), or for methods annotated with `@CheckReturnValue` or similar annotations ([ErrorProne](https://errorprone.info/bugpattern/CheckReturnValue)).
The one particular language we want to highlight here is Swift, whose [approach is inverted](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0047-nonvoid-warn.md) in this regard:
It reports every unused non-void function, and the `@discardableResult` attribute is required to stop issuing the warning.


Based on the experience of existing linters and languages, we see that if a user declares a non-Unit return type in the function, then in most cases, the return value is intended to be used later.
Conversely, cases where a function returns something but it's acceptable to omit this value are limited to well-known and narrow scenarios, accounting for only about 15% of all functions.
Based on this observation, it is inconvenient to place some kind of `@CheckReturnValue` annotation per function, since the majority of non-unit-returning functions (even in the Kotlin standard library) will have to be annotated with it.
For an ergonomic and clean developer experience, the default in Kotlin has to be reversed.
All non-unit-returning functions must be treated as requiring to use their return value by default, with the exceptions marked with some kind of 'ignorable' annotation.

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

The proposal suggests that the return value of expression `A` is used if this expression:

* Is a property, parameter, or local variable initializer (incl. delegates): `val a = A`, `public val x by A`
* Is an argument to `return` or `throw` expressions: `return A`, `throw A`
* Is an argument to another function call (including operator conventions): `foo(A)`, `A == 42`, `A + "str"`, `"Hi ${A}"`
* Is a receiver in a function call or function safe call: `A.foo()`, `A?.bar()`
* Is a **condition** in control flow constructions, such as `if`, `while`, and `when`: `if (A) ...`, `when(A) ...`, `when(x) { A -> ...}`, `while(A) ...`
* Is the last statement in lambda: `list.map { A }`

If the expression's return value is not used according to the rules above, and the expression is not ignorable by itself (see next paragraph), then a warning about unused return value is reported.

### Ignorable expressions

Naturally, not every expression's return value is useful. Some of the expressions are ignorable on their own, namely:

1. Expressions which return type is `Unit`, `Nothing`, or `Nothing?`.

As the `Nothing` type is uninhabited, and the `Unit` type is generally ignored by the compiler itself and doesn't convey any meaning, this rule doesn't require further explanations.
For generic functions, we use a **substituted** return type here.
This means that for functions like `fun <T> fetch(name: String): T`, their calls will be considered ignorable if
`T` is inferred to be `Unit` or `Nothing?`, and non-ignorable otherwise.
Note that we do not add the `Unit?` type to this list. This type is most likely encountered in generic substitutions of nullable functions, e.g., `fun <T> tryAcquire(): T?`.
Most such functions expect their result to be checked for nullability, even if this result is not usable directly.

> For Java interop purposes, platform `Unit!`/`Nothing!` types and `java.lang.Void(!)` type, which can be encountered in generic overrides of Java functions, are also treated as ignorable.

2. Calls to functions annotated with `@IgnorableReturnValue`.

Our research shows that the overwhelming majority of functions that return a non-Unit value in Kotlin suppose that the result is somehow used.
However, there are exceptions to this rule, such as `MutableCollection.add`, whose `Boolean` result is auxiliary and does not have to be used.
For those rare exceptions, there is a way for the function's author to express the fact that the result can be ignored: `@IgnorableReturnValue` annotation ([see below](#ignorability-annotations)).

3. Pre- and post-increment expressions

In expressions like `++i`, the return value doesn't always have to be used because they have a side effect.
This fact can not be expressed using the standard `@IgnorableReturnValue` annotation: the `i.inc()` function only increments the value but does not perform the assignment.
`inc()` function shouldn't be ignorable on its own.
Therefore, this particular operator call has its own place in exceptions.

### Propagating expressions

Besides ignorable and non-ignorable expressions, there is a third, special, category.
It is for expressions that cannot be ignorable or non-ignorable on their own, and their ignorability is determined based on their sub-expressions (arguments).
Alternatively, you can think of them as expressions that propagate ignorability up the expressions tree.
Currently, this category consists of **control flow expressions** and **type operators**.

#### 1. Control flow expressions

If the expression `A` as a last statement of an `if` or `when` **branch**, and the whole `if`/`when` expression is not used, then `A` is also considered not used.
Examples:

```kotlin
fun ifExample(x: Int): Any {
  if (x > 0) A else B // A and B are not used here, a warning should be reported for both of them.

  return if (x > 0) A else "" // A is used, no warning.
}
```

Elvis operator `A ?: B` is equivalent to `val tmp = A; if (tmp != null) tmp else B`.
However, because no actual `val tmp` is introduced in the code, and one cannot access it later, we
consider **both sides** of an elvis operator propagating as well:

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


### Ignorability annotations

In the scope of this proposal, two annotations will be added to a Kotlin standard library.
The first of them is `@IgnorableReturnValue`:

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class IgnorableReturnValue
```

This annotation is intended to express that calls of annotated function are ignorable.
The author of the API is the one responsible for manually placing this annotation on appropriate functions.

> Note that it does not have CONSTRUCTOR and PROPERTY targets because we want to discourage writing constructors and properties with side effects.

The second is `@MustUseReturnValue`:

```kotlin
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MustUseReturnValue
```

This annotation marks the *scope* (file or class) in which all the functions are non-ignorable.
Ideally, we want to treat every non-unit declaration as non-ignorable. However, we cannot do it due to the [high migration costs](#migration-plan). Therefore, the checker would only report functions from the scope annotated as `@MustUseReturnValue` at this stage.
This annotation is expected to be [automatically inserted by the compiler](#marking-libraries-with-mustusereturnvalue). However, it is also possible to place it manually in case you wish to mark only a part of your API.

> @MustUseReturnValue intentionally does not have `AnnotationTarget.FUNCTION`. We want to encourage authors to think about and design non-ignorable APIs as a whole, not on a per-function basis.

### Explicitly ignoring values

In the ideal world, every function call is either explicitly ignorable and not used, or non-ignorable and correctly used by the caller.
In the real world, sometimes we need to drop the value even if the API's author does not want us to.
For those cases, to suppress the checker warning, one can use [underscore as a local variable name](underscores-for-local-variables.md):

```kotlin
fun foo(x: Int) {
  val _ = nonIgnorableCall(x) // A warning is not reported.
}
```

### Higher-order functions and further extensions

Kotlin is well-known for its helpful standard library, which contains a lot of higher-order functions
that you might use every day: `let`, `apply`, `use`, and many more.
One of the patterns you might often see is adding some nullable value to a collection or a string builder:
`packageName?.let { list.add(it) }`.
Because we know that `MutableList.add` is an ignorable function, we do not want to report the whole construction as unused, even though the inferred type of `let` is `Boolean` and not `Unit`.
However, for cases where the non-ignorable function is called inside, e.g., `packageName?.let { "kotlin." + it }`, we want to warn
users if the whole expression is unused.
This problem forced us to realize that many (but not all!) higher-order functions should behave as [propagating expressions](#propagating-expressions), just like control flow expressions.
Unfortunately, there are no means to infer or detect this automatically, and no special syntax exists for it.

Therefore, we do not plan to address this problem in the current design stage.
`let`, and some other functions will be marked as `@IgnorableReturnValue` to avoid a large number of false-positive errors.
In the future, we plan to improve the situation by introducing a special *contract type* to express that a higher-order function call should be treated as a propagating expression.
This plan heavily relies on the [contracts](kotlin-contracts.md) feature of Kotlin, which requires quite some time to finalize.

## Migration plan

As was mentioned in the [goals](#overview-and-goals) section, our ultimate goal is to check the usage of every non-ignorable function.
However, we cannot do this right away because there are many libraries and code out there that are not annotated with `@IgnorableReturnValue` when appropriate — for example, `io.netty.buffer.ByteBuf.clear()` returns self and is definitely ignorable. Still, Netty is unlikely to be annotated with Kotlin's annotations.
Even if the checker itself was enabled by an additional flag, it could not check the whole code in the world at once — the migration cost would be unreasonably high, and there would be too many false positives.

To solve this problem, we somehow need to mark APIs verified by their authors to have `@IgnorableReturnValue` in all the right places. We can call such libraries/APIs *RVC-approved*.
Migration then can look like this:

1. A library author inspects their API and annotates it with `@IgnorableReturnValue` accordingly.
2. A new library version is released, which is RVC-approved now.
3. Library clients can safely enable the unused return value checker to get warnings about misusages of RVC-approved declarations from this library
4. This, in turn, allows the clients to properly annotate their code as well and make a new, RVC-approved, version of their code.
5. Go to step 1.

kotlin-stdlib and some kotlinx libraries will be RVC-approved from the start, allowing you to benefit from this checker immediately.
We hope this feature will gain traction, and more library authors will follow, allowing the community to write much safer Kotlin code.

### Marking libraries with `@MustUseReturnValue`

To implement the plan above, we need a way to mark a library/API as RVC-approved.
After considering various approaches, we concluded that the most reasonable way is to use
an annotation.
Note that we do not expect it to be placed manually; it should be automatically put on every class by the compiler when the author feels that their code is ready for that — i.e., `@IgnorableReturnValue` is placed everywhere it is supposed to be.
This will be controlled by a special feature switch (see [Feature modes](#feature-modes) below).
However, it is still possible to place it by hand in case you need it or simply wish to migrate only part of your API.

### Feature modes

To sum up, we expect that the switch for this feature would have three states:

1. Disabled.
2. Checker only — report warnings for declarations from classes and files annotated with `@MustUseReturnValue`.
3. Full mode — Classes compiled in this mode are automatically annotated with `@MustUseReturnValue`. Thus, warnings would be issued for the code from the libraries annotated with `@MustUseReturnValue` and for local code (because it also becomes annotated).

When this feature becomes stable, state 2 will be the default.
Therefore, all Kotlin users would immediately benefit from every library that is correctly annotated without additional configuration.

### Interop with Java and existing annotations

There are well-known Java annotation libraries that serve similar purposes, one of the most popular being [ErrorProne](https://errorprone.info/api/latest/com/google/errorprone/annotations/CheckReturnValue.html) from Google.
Some Java libraries, such as Guava, are already annotated with them.
To be able to provide the same safety level when using these declarations from Kotlin, we plan to treat the selected number of annotations similarly to Kotlin's `MustUseReturnValue` and `IgnorableReturnValue`.
Namely:

* `com.google.errorprone.annotations.CheckReturnValue` as `kotlin.MustUseReturnValue`
* `com.google.errorprone.annotations.CanIgnoreReturnValue` as `kotlin.IgnorableReturnValue`

More annotations can be added to this list if necessary.

There is also a [JSpecify proposal](https://github.com/jspecify/jspecify/issues/200) aimed at providing similar functionality to the Java ecosystem.
If it is adopted and spread further, and existing Java linters would be able to recognize both JSpecify and Kotlin's annotations, then it would be possible to achieve complete safety in the mixed Java/Kotlin projects.

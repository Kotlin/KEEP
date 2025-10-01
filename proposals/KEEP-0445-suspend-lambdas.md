# Suspend Modifier for Lambdas and Anonymous Functions

* **Type**: History Essay / Design Proposal
* **Author**: Kirill Rakhman
* **Contributors**: Mikhail Zarechenskii, Alejandro Serrano Mena
* **Discussion**: [#458](https://github.com/Kotlin/KEEP/discussions/458)
* **Status**: Public discussion
* **Related YouTrack issues**: [KT-22765](https://youtrack.jetbrains.com/issue/KT-22765/Introduce-suspend-modifier-for-lambdas) [KT-23610](https://youtrack.jetbrains.com/issue/KT-23610/Overload-resolution-ambiguity-for-suspend-function-argument) [KT-23570](https://youtrack.jetbrains.com/issue/KT-23570/Anonymous-suspend-functions-are-not-supported-in-parser)

## Abstract

This proposal discusses the history and current state of declaring suspend lambdas and anonymous
functions, the situation around overload conflict resolution, and proposes a change to the parser
to add missing pieces and clean up the current implementation.

## Table of contents

* [Motivation](#motivation)
* [History and Current State](#history-and-current-state)
  * [The Standard Library Function](#the-standard-library-function)
  * [Deprecations in Preparation for the Parsing Change](#deprecations-in-preparation-for-the-parsing-change)
  * [Suspend Lambdas Without Changing the Parser](#suspend-lambdas-without-changing-the-parser)
  * [Solution to the Overload Resolution Ambiguity](#solution-to-the-overload-resolution-ambiguity)
  * [Anonymous Functions](#anonymous-functions)
* [Proposal](#proposal)

## Motivation

With the introduction of coroutines, the necessity appeared to declare lambdas/anonymous functions
with suspend **function kind**.
Initially, no dedicated syntax was provided, and the only way to create such an object was using a
lambda when the expected type had suspend function kind.

```kotlin
fun foo(f: suspend () -> Unit) {}

fun bar(): suspend () -> Unit {
    // Lambda types are inferred to `suspend () -> Unit` from the expected type.
    foo {}
    val f: suspend () -> Unit = {}
    return {}
}
```

This led to problems when the expected type is ambiguous, usually because of multiple overloads

```kotlin
fun foo(f: () -> Unit) {}
fun foo(f: suspend () -> Unit) {}

fun bar(): suspend () -> Unit {
    // Used to be an overload resolution ambiguity.
    foo {}
}
```

..., when it's not available because the lambda is in a receiver position

```kotlin
fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>) {}

fun test() {
    // Lambda type is inferred to `() -> Unit` because expected type is not propagated to receivers.
    // The outer call compiles because of suspend conversion, but ...
    {
        suspendCoroutine<Unit> { } // ... illegal suspension point is reported for suspend calls.
    }.startCoroutine(object : Continuation<Unit> { ... })
}
```

..., or simply additional boilerplate on the declaration side

```kotlin
fun test() {
    // Declaring the variable with explicit type is the only way to force the lambda function kind
    // to be `suspend`.
    val f: suspend () -> Unit = {}
}
```

For comparison, `@Composable`, another kind of function type, didn't have this problem.
Since putting annotations on lambdas and anonymous functions has always been possible,
no special syntax was necessary to create them with the `@Composable` function kind.

```kotlin
val lambda = @Composable {}
val anonymousFun = @Composable fun() {} 
```

The proposed solution was to introduce the `suspend` modifier for lambdas (`suspend { }`) and
anonymous functions (`suspend fun() {}`).

However, this would be a breaking change, because until this point, `suspend {}` would be treated as
a function call with a trailing lambda argument and `x suspend fun() {}` would be treated as an
infix function call with an anonymous function as argument.
Therefore, the old syntax needed to be deprecated first.

## History and Current State

### The Standard Library Function

To provide a workaround before the actual implementation, a standard library function

```kotlin
public inline fun <R> suspend(noinline block: suspend () -> R): suspend () -> R = block
```

was introduced in 1.2. It allowed creating a suspend lambda using a syntax `suspend {}` that would
later be parsed as a lambda with the `suspend` modifier so users could be migrated smoothly.

The IntelliJ IDEA Kotlin plugin would even highlight the call like a modifer.

A shortcoming of this solution is that it only works for function types without receiver, parameters
and context parameters.

In [KT-78056](https://youtrack.jetbrains.com/issue/KT-78056), it was investigated if adding more
overloads with different arity solves the problem.
The outcome was negative because of the following problems:

Declaring the two overloads

```kotlin
fun <R> suspend(noinline block: suspend () -> R): suspend () -> R
fun <A, Result> suspend(noinline block: suspend (A) -> Result): suspend (A) -> Result
```

leads to an overload resolution ambiguity when trying to call `suspend {}` because a lambda
without declared parameters is equally applicable to both candidates.

In addition, the solution with more overloads doesn't allow creating lambdas with receivers or
context parameters.

This made clear that the standard library function was not enough to solve the problem
comprehensibly.
However, because introducing the `suspend` modifier for lambdas and anonymous functions would be a
breaking change, a number of deprecations had to be introduced first.

### Deprecations in Preparation for the Parsing Change

To prepare for the eventual introduction of suspend lambdas and anonymous functions, several
deprecations were introduced.

In 1.6 ([KTLC-191](https://youtrack.jetbrains.com/issue/KTLC-191)), calling a function named
`suspend` when the only argument was a trailing lambda was forbidden unless the call resolved to the
standard library function to prepare for the future parsing change of `suspend {}`.

In 1.9 ([KTLC-171](https://youtrack.jetbrains.com/issue/KTLC-171)) it was forbidden to call an infix
function called `suspend` with the argument being an anonymous function `x suspend fun() {}` to
prepare for parsing suspend anonymous functions `suspend fun() {}`.

In 2.0 ([KTLC-51](https://youtrack.jetbrains.com/issue/KTLC-51)) a corner case was forbidden that
allowed declaring a suspend anonymous function as the last expression of a lambda.

While the deprecations were being introduced and turned from warnings into errors, the new
frontend a.k.a. K2 was released.
Meanwhile, the old frontend was still being supported.
It can be used in the compiler using language version 1.9.
Additionally, it is used in the IDE when K2 Mode is disabled.

The old and the new frontend share the same parser implementation.
And while the old frontend is still supported, it's not updated with new features.
This means that any feature that requires changing the parser would either need extra effort to
handle it in the old frontend, or it needed to be delayed until the old frontend is no longer
supported.

### Suspend Lambdas Without Changing the Parser

In 2.3 (or 2.2.20 using language version 2.3), creating suspend lambdas with arbitrary arity while
supporting receivers and context parameters was made possible in a _creative_ way without changing
the parser.

```kotlin
fun foo(f: suspend context(String) Int.(Boolean) -> Unit) {}

fun test() {
    foo(suspend {}) // Works as expected.
}
```

The implementation without a change to the parser relies on a couple of tricks (one could call them
hacks).
For instance, because the code is still parsed as a function call, the IDE needs to be able to
resolve the call to some function declaration.
In the given solution, any call (like the one above) will be shown to resolve to the standard
library function

```kotlin
public inline fun <R> suspend(noinline block: suspend () -> R): suspend () -> R = block
```

..., even though the argument might not actually be a subtype of `suspend () -> R`.
This is somewhat bearable because the function is `inline` so it's expected that there is no trace
of the function call in the compiled code.

### Solution to the Overload Resolution Ambiguity

Given that a syntactical solution now exists to create arbitrary suspend lambdas, the overload
resolution problem [KT-23610](https://youtrack.jetbrains.com/issue/KT-23610)
(available in 2.3 or 2.2.20 using language version 2.3) could finally be fixed.

Given two overloads

```kotlin
fun foo(f: () -> Unit) {} // (1)
fun foo(f: suspend () -> Unit) {} // (2)
```

a call `foo {}` will now resolve to the first overload with a regular function type parameter.

To resolve to the second overload, the syntax `foo(suspend {})` can be used.

### Anonymous Functions

For lambdas, parameters, receiver, context parameters as well as the function kind (regular,
suspend, `@Composable`) is inferred from the expected type.

```kotlin
// Possible types of the lambda (among many anothers)
// () -> X
// (T) -> X
// R.() -> X
// context(C) () -> X
// suspend () -> X
// suspend context(C) R.(T) -> X
foo {} 
```

The opposite is true for anonymous functions. The expression `fun() {}` always has the type
`() -> Unit`.

It follows that to declare a suspend anonymous function, a syntactical solution needs to be
provided.

Currently, the code `suspend fun() {}` produces a parsing error

> Syntax error: Unexpected tokens (use ';' to separate expressions on the same line).

As discussed above, it's not possible to modify the parser in a backward incompatible way until
support for the old frontend is removed.

## Proposal

It is proposed that once support for the old frontend is removed,
the parser is adapted in the following ways:

```kotlin
suspend {}
```

is parsed as a lambda expression with the suspend modifier.

The _creative_ solution from
[Suspend Lambdas Without Changing the Parser](#suspend-lambdas-without-changing-the-parser)
is replaced with a proper one while maintaining its semantics.

In addition, it is proposed to modify the parser so that `suspend fun() {}` is parsed as an anonymous
function expression with suspend modifier and handled as such during resolution.

Both changes are non-breaking as the proposed syntax already has the proposed semantics
or is reserved.
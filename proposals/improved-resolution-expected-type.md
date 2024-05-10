# Improve resolution using expected type

* **Type**: Design proposal
* **Author**: Alejandro Serrano Mena
* **Status**: In discussion
* **Prototype**: Implemented in [this branch](https://github.com/JetBrains/kotlin/compare/rr/serras/improved-resolution-expected-type)
* **Discussion**: ??
* **Related issues**: [KT-9493](https://youtrack.jetbrains.com/issue/KT-9493/Allow-short-enum-names-in-when-expressions), [KT-44729](https://youtrack.jetbrains.com/issue/KT-44729/Context-sensitive-resolution), [KT-16768](https://youtrack.jetbrains.com/issue/KT-16768/Context-sensitive-resolution-prototype-Resolve-unqualified-enum-constants-based-on-expected-type), [KT-58939](https://youtrack.jetbrains.com/issue/KT-58939/K2-Context-sensitive-resolution-of-Enum-leads-to-Unresolved-Reference-when-Enum-has-the-same-name-as-one-of-its-Entries)

## Abstract

We propose an improvement of the name resolution rules of Kotlin based on the expected type of the expression. The goal is to decrease the amount of qualifications when the type of the expression is known.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivating example](#motivating-example)
    * [The issue with overloading](#the-issue-with-overloading)
* [Technical details](#technical-details)
    * [Additional candidate resolution scope](#additional-candidate-resolution-scope)
    * [Additional type resolution scope](#additional-type-resolution-scope)
* [Potential additions](#potential-additions)
    * [Equality](#equality)
    * [Nested inheritors](#nested-inheritors)
* [Implementation note](#implementation-note)

## Motivating example

The current rules of the language sometimes require Kotliners to qualify some members, where it feels that such qualification could be inferred from the types already spelled out in the code. One [infamous example](https://youtrack.jetbrains.com/issue/KT-9493/Allow-short-enum-names-in-when-expressions) is enumeration entries, which always live inside their defining class. In the example below, we need to write `Problem.`, even though `Problem` is already explicit in the type of the `problem` argument or the return type of `problematic`.

```kotlin
enum class Problem {
    CONNECTION, AUTHENTICATION, DATABASE, UNKNOWN
}

fun message(problem: Problem): String = when (problem) {
    Problem.CONNECTION -> "connection"
    Problem.AUTHENTICATION -> "authentication"
    Problem.DATABASE -> "database"
    Problem.UNKNOWN -> "unknown"
}

fun problematic(x: String): Problem = when (x) {
    "connection" -> Problem.CONNECTION
    "authentication" -> Problem.AUTHENTICATION
    "database" -> Problem.DATABASE
    else -> Problem.UNKNOWN
}
```

This KEEP addresses many of these problems by considering explicit expected types when doing name resolution. We try to propagate the information from argument and return types, declarations, and similar constructs. As a result, the following constructs are usually improved:

* conditions on `when` expressions with subject,
* type checks and casts (`is`, `as`),
* `return`, both implicit and explicit,
* we propose an alternative to improve equality (`==`, `!=`).

### The issue with overloading

We leave out of this KEEP any extension to the propagation of information from function calls to their argument. One particularly visible implication of this choice is that you still need to qualify enumeration entries that appear as arguments as currently done in Kotlin. Following with the example above, you still need to qualify the argument to `message`.

```kotlin
val s = message(Problem.CONNECTION)
```

What sets function calls apart from the constructs mentioned before is overloading. In Kotlin, there's a certain sequence in which function calls are resolved and type checked, as spelled out in the [specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution).

1. Infer the types of non-lambda arguments.
2. Choose an overload for the function based on the gathered type.
3. Check the types of lambda arguments.

Improving the resolution of arguments based on the function call would amount to reversing the order of (1) and (2), at least partially. There are potential techniques to solve this problem, but another KEEP seems more appropriate.

As a consequence, operators in Kotlin that are desugared to function calls, like `in` or `thing[x]`, are also outside of the scope of this KEEP.

## Technical details

We introduce an additional scope, present both in type and candidate resolution, which always depends on a type `T` (we say we **propagate `T`**). This scope contains the static and companion object callables of the aforementioned type `T`, as defined by the [specification](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver).

This scope is added at the lowest priority level. This ensures that we do not change the current behavior, we only extend the amount of programs that are accepted.

This scope is **not** propagated to children of the node in question. For example, when resolving `val x: T = f(x, y)`, the additional scope is present when resolving `f`, but not when resolving `x` and `y`. After all, `x` and `y` no longer have an expected type `T`.

### Additional candidate resolution scope

We propagate `T` to an expression `e` whenever the specification states _"the type of `e` must be a subtype of `T`"_. 

For declarations, a type `T` is propagated to the body, which may be an expression or a block. Note that we only propagate types given _explicitly_ by the developer.

* _Default parameters of functions_: `x: T = e`;
* _Initializers of properties with explicit type_: `val x: T = e`;
* _Explicit return types of functions_: `fun f(...): T = e` or `fun f(...): T { ... }`,
* _Getters of properties with explicit type_: `val x: T get() = e` or `val x: T get() { ... }`.

If a type `T` is propagated to a block, then the type is propagated to every return point of the block.

* _Explicit `return`_: `return e`,
* _Implicit `return`_: the last statement.

For other statements and expressions, we have the following rules. Here "known type of `x`" includes any additional typing information derived from smart casting.

* _Assignments_: in `x = e`, the known type of `x` is propagated to `e`,
* _`when` expression with subject_: in `when (x) { e -> ... }`, then known type of `x` is propagated to `e`, when `e` is not of the form `is T` or `in e`,
* _Branching_: if type `T` is propagated to an expression with several branches, the type `T` is propagated to each of them,
    * _Conditionals_, either `if` or `when`,
    * _`try` expressions_, where the type `T` is propagated to the `try` block and each of the `catch` handlers,
* _Elvis operator_: if type `T` is propagated to `e1 ?: e2`, then we propagate `T?` to `e1` and `T` to `e2`.
* _Not-null assertion_: if type `T` is propagated to `e!!`, then we propagate `T?` to `e`,
* _Type cast_: in `e as T` and `e as? T`, the type `T` is propagated to `e`,
    * This rule follows from the similarity to doing `val x: T = e`.
  
All other operators and compound assignments (such as `x += e`) do not propagate information. The reason is that those operators may be _overloaded_, so we cannot guarantee their type.

### Additional type resolution scope

We introduce the additional scope during type solution in the following cases:

* _Type check_: in `e is T`, `e !is T`, the known type of `e` is propagated to `T`.
* _`when` expression with subject_: in `when (x) { is T -> ... }`, then known type of `x` is propagated to `T`.

## Potential additions

### Equality

It is possible (and implemented in the prototype) to add the additional propagation rule:

* _Equality_: in `a == b` and `a != b`, then known type of `a` is propagated to `b`.

This helps in common cases like `p == Problem.CONNECTION`.

However, there are two potential problems with this approach, which require further discussion.

1. It is not symmetric: it might be surprising that `p == CONNECTION` is accepted, but `CONNECTION == p` is rejected.
2. The [specification](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) mandates `a == b` to be equivalent to `(A as? Any)?.equals(B as Any?) ?: (B === null)` in the general case. So there is no actual type expected from `b` merely from participating in equality.

### Nested inheritors

The rules above handle enumeration, and it's also useful when defining a hierarchy of classes nested on the parent.

```kotlin
sealed interface Either<out E, out A> {
  data class Left<E>(error: E): Either<E, Nothing>
  data class Right<A>(value: A): Either<Nothing, E>
}
```

One way in which we can improve resolution even more is by considering the subclasses of the known type of an expression. Making every potential subclass available would be quite surprising, but sealed hierarchies form an interesting subset (and the information is directly accessible to the compiler). However, this means getting away from a more "syntactical" choice (nested elements of the type), which may be surprising.

## Implementation note

In the current K2 compiler, these rules amount to considering those places in which `WithExpectedType` is passed as the `ResolutionMode`, plus adding special rules for `as`, `is`, and `==`. Since `when` with subject is desugared as either `x == e` or `x is T`, we need no additional rules to cover them.
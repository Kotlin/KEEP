# Improve resolution using expected type

* **Type**: Design proposal
* **Author**: Alejandro Serrano Mena
* **Status**: In discussion
* **Prototype**: Implemented in [this branch](https://github.com/JetBrains/kotlin/compare/rr/serras/improved-resolution-expected-type-dot-syntax)
* **Discussion**: [KEEP-379](https://github.com/Kotlin/KEEP/issues/379)
* **Related issues**: [KT-9493](https://youtrack.jetbrains.com/issue/KT-9493/Allow-short-enum-names-in-when-expressions), [KT-44729](https://youtrack.jetbrains.com/issue/KT-44729/Context-sensitive-resolution), [KT-16768](https://youtrack.jetbrains.com/issue/KT-16768/Context-sensitive-resolution-prototype-Resolve-unqualified-enum-constants-based-on-expected-type), [KT-58939](https://youtrack.jetbrains.com/issue/KT-58939/K2-Context-sensitive-resolution-of-Enum-leads-to-Unresolved-Reference-when-Enum-has-the-same-name-as-one-of-its-Entries)

## Abstract

We propose an improvement of the name resolution rules of Kotlin based on the expected type of the expression. The goal is to decrease the amount of qualifications when the type of the expression is known.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivating example](#motivating-example)
  * [What is available after `_.`](#what-is-available-after-_)
  * [Function arguments](#function-arguments)
* [Technical details](#technical-details)
  * [Expected type propagation](#expected-type-propagation)
  * [Single definite expected type](#single-definite-expected-type)
  * [Contextually-scoped identifiers](#contextually-scoped-identifiers)
* [Design decisions](#design-decisions)
  * [Risks](#risks)

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

val databaseProblemMessage: String = message(Problem.DATABASE)
```

This KEEP addresses many of these problems by considering explicit expected types when doing name resolution. We try to propagate the information from argument and return types, declarations, and similar constructs. As a result, the following constructs are usually improved:

* conditions on `when` expressions with subject,
* type checks (`is`),
* `return`, both implicit and explicit,
* equality checks (`==`, `!=`),
* assignments and initializations,
* function calls.

As a very short summary of this proposal, many usages of `Problem.` above can be replaced with `_.`, which intuitively should be read as "resolve this name in the context of the expected type".

```kotlin
fun message(problem: Problem): String = when (problem) {
    _.CONNECTION -> "connection"
    _.AUTHENTICATION -> "authentication"
    // other cases
}

fun problematic(x: String): Problem = when (x) {
    "connection" -> _.CONNECTION
    // other cases
}

val databaseProblemMessage: String = message(_.DATABASE)
```

> [!IMPORTANT]
> Even though `_.CONNECTION` could be read as the usual `.` operator applied to a `_` receiver, this is **not** the case. On the contrary, `_.` is new syntax for a _contextually-scoped identifier_.

### What is available after `_.`

In order to describe what this proposal allows as contextually-scoped identifiers, we need to separate the two positions in which such an identifier may occur:

* _Type position_: as the right-hand side of `is`, both in standalone and branch conditions.
* _Expression position_: in any place where an expression is expected.

Note the restrictive nature of the type position. You are _not_ allowed to use `_.` to access types in anywhere else; not in type adscriptions for variables, not in the list of supertypes or generic constraints, and so on.

In **type position** only classifiers which are both _nested_ and _sealed inheritors_ of the expected type are available.

```kotlin
sealed interface Either<out E, out A> {
  data class  Left<out E>(val error: E): Either<E, Nothing>
  data class Right<out A>(val value: A): Either<Nothing, A>
}

fun <E, A> Either<E, A>.getOrElse(default: A) = when (this) {
  is _.Left  -> default
  is _.Right -> value
}
```

In **expression position** the _entire_ static and companion object scope of the expected type is available, including classifiers, properties, and functions. Whereas the first two are needed to cover common cases like enumeration entries, and comparison with objects, the usefulness of methods seems debatable. However, it allows some interesting constructions where we compare against a value coming from a factory:

```kotlin
class Color(...) {
  companion object {
    val WHITE: Color = ...
    val BLACK: Color = ...
    fun fromRGB(r: Int, g: Int, b: Int): Color = ...
  }
}

// now when we match on a color...
when (color) {
  _.WHITE -> ...
  _.fromRGB(10, 10, 10) -> ...
}
```

In addition, note that the rules to filter out which functions should or shouldn't be available are far from clear; potential generic substitutions are one such complex example.

We do **not** look in the static and companion object scopes of **supertypes** of the expected type. This is in accordance to the rules of [resolution with an explicit type receiver](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver). Technically, we perform some pre-processing of the expected type to simplify it, but the general rule is that only _one_ classifier is searched.

**Extension** callables defined in the static and companion object scopes are **not** available. In most cases those callables can be imported without requiring any additional qualification on the call site.

> [!NOTE]
> In previous iterations of this proposal extension callables were included. However, the interaction with `_.` syntax is not clear, so this feature has been dropped.

### Function arguments

What sets function calls apart from the constructs mentioned before is **overloading**, that is, the possibility to use the same name for different declarations. Operators in Kotlin that are desugared to function calls, like `in` or `thing[x]`, also fall in this category, with the exception of [value equalities](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) (`==`, `!=`).

In Kotlin, there's a certain sequence in which function calls are resolved and type checked, as spelled out in the [specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution).

1. Infer the types of non-lambda arguments.
2. Choose an overload for the function based on the gathered type.
3. Check the types of lambda arguments.

Each of these steps influence the following one: the types inferred in (1) drive the choice of overload in (2), which in turn give you the information to check the lambda arguments in (3).

In order to support contextually-scoped identifiers as arguments, the proposal is to treat them in the same way as lambda arguments. The main consequence is that an argument using `_.` may _not_ influence the choice of overload, which must be unique after step (2).

For example, consider the following declarations in addition to those at the beginning of this section.

```kotlin
enum class Result {
  OK, FAILURE
}

fun message(result: Result): String = ...
```

In this scenario, `message(_.DATABASE)` results in an _ambiguity error_. We need to choose the overload of `message`, but we cannot use the information from the argument, since it is a contextually-scoped identifier, so the decision cannot be taken. This is the case even though `_.DATABASE` may only work with the `(Problem) -> String` overload.

## Technical details

The goal of this proposal is to make some identifiers available using `_.` syntax, based on the expected type of that identifier. We start by defining how the expected type is actually propagated, and then describe changes to the syntax and resolution for the new contextually-scoped identifiers.

### Expected type propagation

In general, we say that the expected type of `e` must be `T` whenever the specification states _"the type of `e` must be a subtype of `T`"_. In this section we formally describe the propagation of the expected type, that is, how the expected type of an expression depends on the expected type of its parent.

For declarations, the expected type `T` is propagated to the body, which may be an expression or a block. Note that we only propagate types given _explicitly_ by the developer.

* _Default parameters of functions_: `x: T = e`;
* _Initializers of properties with explicit type_: `val x: T = e`;
* _Explicit return types of functions_: `fun f(...): T = e` or `fun f(...): T { ... }`,
  * This includes _accessors_ with explicit type: `val x get(): T = e`;
* _Getters of properties with explicit type_: `val x: T get() = e` or `val x: T get() { ... }`.

If a type `T` is expected for a block, then the type is propagated to every return point of the block.

* _Explicit `return`_: `return e`,
* _Implicit `return`_: the last statement.

If a functional type `(...) -> R` is expected for a lambda expression, then the return type `R` is propagated to the body of the lambda (alongside the parameter types being propagated to the formal parameters, if available).

```kotlin
val unknown: () -> Problem = {
                // ^ propagated as type of the lambda

  _.UNKNOWN       // implicit return: we use 'Problem' for resolution
}
```

For other statements and expressions, we have the following rules. Here "known type of `x`" includes any additional typing information derived from smart casting.

* _Assignments_: in `x = e`, the expected type of `x` is propagated to `e`;
* _Type check_: in `e is T`, `e !is T`, the known type of `e` is propagated to `T`;
* _Branching_: if type `T` is expected for an with several branches, the type `T` is propagated to each of them,
    * _Conditionals_, either `if` or `when`,
    * _`try` expressions_, where the type `T` is propagated to the `try` block and each of the `catch` handlers;
* _`when` expression with subject_: those cases should be handled as if the subject had been inlined on every condition, as described in the [specification](https://kotlinlang.org/spec/expressions.html#when-expressions);
* _Elvis operator_: if type `T` is expected for `e1 ?: e2`, then we propagate `T?` to `e1` and `T` to `e2`;
* _Not-null assertion_: if type `T` is expected for `e!!`, then we propagate `T?` to `e`;
* _Equality_: in `a == b` and `a != b`, the known type of `a` is propagated to `b`.
    * This helps in common cases like `p == _.CONNECTION`.
    * Note that in this case the expected type should only be propagated for the purposes of additional resolution. The [specification](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) mandates `a == b` to be equivalent to `(A as? Any)?.equals(B as Any?) ?: (B === null)` in the general case, so from a typing perspective there should be no constraint on the type of `b`.

For function calls, and [operator calls which desugar to function calls](https://kotlinlang.org/spec/operator-overloading.html#operator-overloading), we refer back to the as [specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution). Remember the three stages of overload resolution, with the additions for this proposal.

1. Infer the types of non-lambda _and non-contextually-scoped_ arguments.
2. Choose an overload for the function based on the gathered type.
3. Check the types of lambda _and contextually-scoped_ arguments.

The expected types are those propagated during step (3).

> [!NOTE]
> In the current K2 compiler, these rules amount to considering those places in which `WithExpectedType` is passed as the `ResolutionMode`, plus adding special rules for `is`, `==`, and updating overload resolution. Since `when` with subject is desugared as either `x == e` or `x is T`, we need no additional rules to cover them.

### Single definite expected type

There are some scenarios in which the expected type propagation may lead to complex types, like intersections or bounded type parameters. In order to define exactly which scope we should look into, we introduce the notion of the **single definite expected type**, `sdet(T)`, which is defined recursively, starting with the expected type propagated by the rules above. It is possible for this type to be undefined.

* Built-in and classifier types: `sdet(T) = T`.
  * Note that type arguments do not influence the scope.
* Type parameters:
  * If there is a single supertype, `<T : A>`, `sdet(T) = sdet(A)`,
  * Otherwise, `sdet(T)` is undefined.
* Nullable types: `sdet(T?) = sdet(T)`.
* Types with variance:
  * Covariance, `stde(out T) = stde(T)`,
  * For contravariant arguments, `stde(in T)` is undefined.
* Captured types: `stde` is undefined.
* Flexible types, `stde(A .. B)`
  * Compute `stde(A)` and `stde(B)`, and take it if they coincide; otherwise undefined.
  * This rule covers `A .. A?` as special case.
* Intersection types, `stde(A & B)`,
  * Definitely not-null, `stde(A & Any) = stde(A)`,
  * "Fake" intersection types in which `B` is a subtype of `A`, `stde(A & B) = stde(B)`; and vice versa.

### Contextually-scoped identifiers

We extend the [grammar](https://kotlinlang.org/spec/syntax-and-grammar.html#syntax-and-grammar) with the following rules.

```diff
+ CONTEXT_DOT:
+     '_.'

  infixOperation:
-     elvisExpression {(inOperator {NL} elvisExpression) | (isOperator {NL} type)}
+     elvisExpression {(inOperator {NL} elvisExpression) | (isOperator {NL} typeWithContext)}

+ typeWithContext:
+     contextuallyScopedType
+   | type

+ contextuallyScopedType:
+     CONTEXT_DOT simpleIdentifier {{NL} '.' {NL} simpleUserType} [{NL} typeArguments]

  primaryExpression:
      parenthesizedExpression
    | simpleIdentifier
+   | contextuallyScopedIdentifier
    | literalConstant
    | ...

+ contextuallyScopedIdentifier
+     CONTEXT_DOT simpleIdentifier
```

> [!NOTE]
> In the current K2 compiler, these rules amount to having two new types of references (one for types, one for declarations), which are obtained using `_.` syntax.

Resolution for `contexutallyScopeIdentifiers` follows the rules for [calls with an explicit type receiver](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver). However, the role of explicit receiver is now taken by the single definite expected type of the expression.

For `contextuallyScopedType`, the first `simpleIdentifier` is resolved against a special scope containing only the nested sealed inheritors of the single definite expected type of the expression.

## Design decisions

**Choice of syntax**: in previous iterations of this proposal no special syntax was introduced, and the additional scope was available without any qualification. On the other hand, those previous proposals did not include expected type propagation to arguments. To get the more powerful version we need to "mark" those arguments where the resolution should be done in step (3) instead of step (1), which lead us to consider special syntax.

The first choice was based on [Swift's implicit member expressions](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/expressions#Implicit-Member-Expression), that is, using `.value`. Alas, that syntax does not play very well with Kotlin's, and would require explicitly separating expressions using `;` or `{ }`.

```kotlin
fun message(problem: Problem): String = when (problem) {
    .CONNECTION -> "connection" ;  // otherwise parsed as "connection".AUTHENTICATION
    .AUTHENTICATION -> "authentication" ;
}
```

We did not want to introduce any heuristic around newlines, as the current grammar is quite liberal with its placement. As a result, we decided to use a slightly modified version, the current proposed `_.`.

**Propagation to arguments**: in previous iterations of this proposal we explicitly crossed out function calls for expected type propagation. However, the following example was constructed based on the propagation of expected type to lambdas.

```kotlin
fun message(problem: Problem) = ...
fun message(problem: () -> Problem) = ...

message(_.DATABASE)    // does not work
message { _.DATABASE } // does work
```

The current proposal is based on the intuition behind that example: we can obtain an expected type for an argument if we wait until the moment in which lambdas are type-checked.

**Equality**: using the rules above, equalities propagate type information from left to right. But there are other two options: propagating from right to left, not propagating any information at all, or propagating based on the shape of expressions.

The current choice is obviously not symmetric: it might be surprising that `p == _.CONNECTION` is accepted, but `_.CONNECTION == p` is rejected. A preliminary assessment shows that the pattern `_.CONSTANT == variable` is not as prevalent in the Kotlin community as in other programming languages.

On the other hand, the proposed flow of information is consistent with the ability to refactor `when (x) { _.A -> ...}` into `when { x == _.A -> ...}`, without any further qualification required. We think that this uniformity is important for developers.

**Type casts**: a previous iteration included the additional rule "in `e as T` and `e as? T`, the known type of `e` is propagated to `T`", but this has been dropped. There are two reasons for this change:

1. On a conceptual level, it is not immediately obvious what happens if `e as T` as a whole also has an expected type: should `T` be resolved using the known type of `e` or that expected type? It's possible to create an example where depending on the answer the resolution differs, and this could be quite surprising to users.
2. On a technical level, the compiler _sometimes_ uses the type `T` to guide generic instantiation of `e`. This conflicts with the rule above.

**Interaction with smart casting**: the main place in which the notion of "single definite expected type" may bring some problems is smart castings, as they are the main source of intersection types.

The following code does _not_ work under the current rules.

```kotlin
class Box<T>(val value: T)

fun <T> Box<T>.foo() = when (value) {
  is Problem if value == _.UNKNOWN -> ...
  ...
}
```

The problem is that at the expression `value == _.UNKNOWN`, the known type of `value` is `T & Problem`, a case for which the single definite expected type is undefined. There are two reasons for this choice:

- It is unclear in general how to treat intersection types, since other cases may not be as simple as dropping a type argument.
- If in the future Kotlin gets a feature similar to GADTs, we may piggy back on the knowledge that `T` is equal to `Problem`, and face no problem in computing the single definite expected type.

### Risks

One potential risk of this proposal is the difficulty of understanding _when_ exactly it is OK to drop the qualifier, which essentially corresponds to understanding the propagation of the expected type through the compiler. On the other hand, maybe this complete understanding is not required, as developers will be able to count on the main scenarios: the conditions on a `when` with subject, the immediate expression after a `return`, or the initializer of a property, given that the return type is known. We expect the more technical notion of "single definite expected type" to never surface in practice.

Another potential risk is that we add more coupling between type inference and candidate resolution. On the other hand, in Kotlin those two processes are inevitably linked together -- to resolve the candidates of a call you need the type of the receivers and arguments -- so the step taken by this proposal feels quite small in comparison.

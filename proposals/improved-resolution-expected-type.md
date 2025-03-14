# Improve resolution using expected type

* **Type**: Design proposal
* **Author**: Alejandro Serrano Mena
* **Status**: In discussion
* **Prototype**: Implemented in [this branch](https://github.com/JetBrains/kotlin/compare/rr/serras/improved-resolution-expected-type)
* **Discussion**: [KEEP-379](https://github.com/Kotlin/KEEP/issues/379)
* **Related issues**: [KT-9493](https://youtrack.jetbrains.com/issue/KT-9493/Allow-short-enum-names-in-when-expressions), [KT-44729](https://youtrack.jetbrains.com/issue/KT-44729/Context-sensitive-resolution), [KT-16768](https://youtrack.jetbrains.com/issue/KT-16768/Context-sensitive-resolution-prototype-Resolve-unqualified-enum-constants-based-on-expected-type), [KT-58939](https://youtrack.jetbrains.com/issue/KT-58939/K2-Context-sensitive-resolution-of-Enum-leads-to-Unresolved-Reference-when-Enum-has-the-same-name-as-one-of-its-Entries)

## Abstract

We propose an improvement of the name resolution rules of Kotlin based on the expected type of the expression. The goal is to decrease the amount of qualifications when the type of the expression is known.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivating example](#motivating-example)
  * [What is available in the contextual scope](#what-is-available-in-the-contextual-scope)
  * [No-argument callables](#no-argument-callables)
  * [Chained inference](#chained-inference)
* [Technical details](#technical-details)
  * [Expected type propagation](#expected-type-propagation)
  * [Single definite expected type](#single-definite-expected-type)
  * [Additional contextual scope](#additional-contextual-scope)
  * [Changes to overload resolution](#changes-to-overload-resolution)
  * [Interaction with inference](#interaction-with-inference)
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

As a very short summary of this proposal, all usages of `Problem.` can be removed. Intuitively, the compiler uses the known or expected type to resolve the enumeration entries.

```kotlin
fun message(problem: Problem): String = when (problem) {
    CONNECTION -> "connection"
    AUTHENTICATION -> "authentication"
    // other cases
}

fun problematic(x: String): Problem = when (x) {
    "connection" -> CONNECTION
    // other cases
}

val databaseProblemMessage: String = message(DATABASE)
```

### What is available in the contextual scope

The core of this proposal is the addition of a scope, the **contextual scope**, whose available members are defined by type information known to the compiler at that point. In order to describe what this proposal allows as contextually-scoped identifiers, we need to separate the two positions in which such an identifier may occur:

* _Type position_: as the right-hand side of `is`, both in standalone and branch conditions.
* _Expression position_: in any place where an expression is expected.

Note the restrictive nature of the type position. Improved resolution is not available anywhere else: not in type annotations for variables, not in the list of supertypes or generic constraints, and so on.

In **type position** only classifiers which are both _nested_ and _sealed inheritors_ of the expected type are available.

```kotlin
sealed interface Either<out E, out A> {

  data class  Left<out E>(val error: E): Either<E, Nothing>
  data class Right<out A>(val value: A): Either<Nothing, A>
}

fun <E, A> Either<E, A>.getOrElse(default: A) = when (this) {
  is Left  -> default
  is Right -> value
}
```

In **expression position** we extend the scope mention in the type position with _properties_ defined in or extending the static and companion object scoped of the expected type. For the purposes of this KEEP, _enumeration entries_ count as properties defined in the static scope of the enumeration class. We describe the reasons for this restricted scope in the [_no-argument callables_](#no-argument-callables) section below.

```kotlin
class Color(...) {
  companion object {
    val WHITE: Color = ...
    val BLACK: Color = ...
    fun fromRGB(r: Int, g: Int, b: Int): Color = ...
    fun background(): Color = ...
  }
}

val Color.Companion.BLUE: Color = ...

// now when we match on a `color: Color`...
when (color) {
  WHITE -> ...                // OK, member property of the companion object
  fromRGB(10, 10, 10) -> ...  // NO, not a property
  background() -> ...         // NO, not a property
  BLUE -> ...                 // OK, extension property over companion object
}
```

**Extension** properties defined in the static and companion object scopes are **not** available. The receiver in that case acts as an additional parameter to resolve, putting us in the same situation as "regular" parameters.

```kotlin
class Color(...) {
  companion object {
    val Int.grey get() = ...
  }
}

when (color) {
  10.grey -> ... // this is not allowed
}
```

As a workaround, in most cases those callables can be imported without requiring any additional qualification on the call site.

```kotlin
import Color.Companion.grey
```

There is no additional filtering of properties or functions based on their result type. For example, the following code _resolves_ correctly to `Color.NUMBER_OF_COLORS`, but then raises a "type mismatch" error between `Color` and `Long`.

```kotlin
class Color(...) {
  companion object {
    val NUMBER_OF_COLORS: Long = 255 * 255 * 255
  }
}

when (color) {
  NUMBER_OF_COLORS -> ...  // type mismatch
}
```

We do **not** look in the static and companion object scopes of **supertypes** of the expected type. This is in accordance to the rules of [resolution with an explicit type receiver](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver). Technically, we perform some pre-processing of the expected type to simplify it, but the general rule is that only _one_ classifier is searched.

### No-argument callables

The reason for restricting available members is avoiding _resolution explosion_ in the case of nested function calls. To understand the problem, we need to understand the sequence in which function calls are resolved and type checked, which is spelled out in the [Kotlin specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution).

1. Infer the types of non-lambda arguments.
2. Choose an overload for the function based on the gathered types.
3. Check the types of lambda arguments.

Let us forget for a moment about lambda arguments; in that case the procedure is completely bottom-up: we move from arguments to function calls, performing resolution at each stage of the process. However, to improve the resolution we sometimes need to perform the tree walk in the other direction: from resolving the function overload we know the expected type of the argument, which we can use to resolve that expression.

The problem is that if we allowed resolving to a function with some arguments, we could end up in a situation in which we do not resolve anything until we reach the top-level function call, which then gives us information to resolve the arguments. And if those arguments also had unresolved arguments themselves, this process could go arbitrarily deep. This is both costly for the compiler, and also quite brittle.

Take the following example, in which we extend the `Color` class and introduce a `Label` function (in the style of Jetpack Compose).

```kotlin
class Color(...) {
  companion object {
    fun withAlpha(color: Color, alpha: Double): Color = ...
  }
}

fun Label(text: String, color: Color) = ...

val hello: Text = Label("hello", withAlpha(BLUE, 0.5))
```

During the resolution of the body of `hello`, we proceed arguments-first. So we already fail resolution at `BLUE`, since we do not know the type of it (yet). Going upwards we fail again for `withAlpha`. It is only when we get to `Label` that we understand that the second argument refers to `Color.withAlpha`, perform potential overload resolution, and then push the expected type of `BLUE` to finally resolve it. This already duplicates the work.

Up to this point, nothing seems to be against allowing a function call without arguments, like `background()`. Alas, a function call without any explicit arguments may still have optional ones. Furthermore, forbiding _any_ function call leads to more uniformity.

We acknowledge, though, that this no-argument rule may lead to some surprising behavior. Consider the following sealed hierarchy:

```kotlin
sealed interface Tree {
  data object Leaf: Tree
  data class Node(val left: Tree, val value: Int, val right: Tree): Tree
}
```

In this case resolution is improved for constructing `Leaf` but not for `Node`, since the latter requires arguments.

```kotlin
fun create(n: Int): Tree = when (n) {
  0 -> Leaf
  else -> Tree.Node(Leaf, n, Leaf)
}
```

The no-argument rule ensures that this undesired behavior may not arise, as resolution does not need to go deeper in that case. This seems like a good balance, since the most common use cases like dropping the name of the enumeration are still possible. In a previous iteration of this proposal we went even further, forbidding any improved resolution inside function calls.

### Chained inference

Another limitation of this proposal is that some seemingly trivial refactorings require introducing qualification.

```kotlin
val WEIRD: Problem = UNKNOWN  // improved resolution kicks in
// <T> T.also(block: (T) -> Unit): T
val WEIRD: Problem = Problem.UNKNOWN.also { println("weird!") }
```

The problem arises because once we introduce `also`, the expected type from the function can no longer "flow" to the receiver position. If instead of `also` we were calling a function with type `User.(() -> Unit): Problem`, resolving `UNKNOWN` in the context of `Problem` would no longer be valid.

_Chained inference_ is a [known problem](https://youtrack.jetbrains.com/issue/KT-17115) in Kotlin. Another place were it surfaces is requiring more type information for generic calls,

```kotlin
fun foo(): List<String> = listOf()
// but if we call any function on it...
fun foo(): List<String> = listOf<String>().reversed()
```

We acknowledge this limitation of the current proposal. In this case tooling can provide additional help by, for example, qualifying a name like `UNKNOWN` above when a call is auto-completed.

## Technical details

We start by defining how the expected type is actually propagated, and then describe changes to the resolution for the new contextually-scoped identifiers.

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

  UNKNOWN       // implicit return: we use 'Problem' for resolution
}
```

For other statements and expressions, we have the following rules. Here "known type of `x`" includes any additional typing information derived from smart casting.

* _Assignments_: in `x = e`, the expected type of `x` is propagated to `e`;
* _Type check_: in `e is T`, `e !is T`, the known type of `e` is propagated to `T`;
* _Type cast_: in `e as T`, `e as? T`, the known type of `e` is propagated to `T`;
* _Branching_: if type `T` is expected for an with several branches, the type `T` is propagated to each of them,
    * _Conditionals_, either `if` or `when`,
    * _`try` expressions_, where the type `T` is propagated to the `try` block and each of the `catch` handlers;
* _`when` expression with subject_: those cases should be handled as if the subject had been inlined on every condition, as described in the [specification](https://kotlinlang.org/spec/expressions.html#when-expressions);
* _Elvis operator_: if type `T` is expected for `e1 ?: e2`, then we propagate `T?` to `e1` and `T` to `e2`;
* _Not-null assertion_: if type `T` is expected for `e!!`, then we propagate `T?` to `e`;
* _Equality_: in `a == b` and `a != b`, the known type of `a` is propagated to `b`.
    * This helps in common cases like `p == CONNECTION`.
    * Note that in this case the expected type should only be propagated for the purposes of additional resolution. The [specification](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) mandates `a == b` to be equivalent to `(A as? Any)?.equals(B as Any?) ?: (B === null)` in the general case, so from a typing perspective there should be no constraint on the type of `b`.

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
  * Covariance, `sdet(out T) = sdet(T)`,
  * For contravariant arguments, `sdet(in T)` is undefined.
* Captured types: `sdet(T)` is undefined.
* Flexible types, `sdet(A .. B)`
  * Compute `sdet(A)` and `sdet(B)`, and take it if they coincide; otherwise undefined.
  * This rule covers `A .. A?` as special case.
* Intersection types, `sdet(A & B)`,
  * Definitely not-null, `sdet(A & Any) = sdet(A)`,
  * "Fake" intersection types in which `B` is a subtype of `A`, `sdet(A & B) = sdet(B)`; and vice versa.
  * Otherwise, `sdet(T)` is undefined.

### Additional contextual scope

Whenever they is a single definite expected type for an expression, this is resolved with an additional **contextual** scope. This scope has the lowest priority (even lower than that of default and star imports) and _should keep_ that lowest priority even after further extensions to the language. The mental model is that the expected type is only use for resolution purposes after any other possibility has failed.

The contextual scope for a single definite type `T` is made from three different sources:

1. The static scope of the type `T`,
2. The companion object scope of the type `T`,
3. Imported extension functions over the companion object of `T`.

Furthermore, only two kinds of members are available:

1. Classifiers which are nested in and inherit from type `T`, if `T` is a `sealed` class.
2. No-argument callables, which must:
   - Be either properties or enumeration entries (including properties synthetized from interoperating with other languages, like Java),
   - Have no context receivers nor context parameters.
   - Have no extension receiver, except for extension properties defined over the companion object of `T`.

### Changes to [overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution)

In order to accomodate improved resolution for function arguments, the algorithm must be slightly modified. As a reminder, overload resolution for a function call `f(...)` takes the following steps:

1. Resolve all non-lambda arguments,
2. _Applicability_: gather all potential overloads of the function `f`, and for each of them generate a constraint system that specifies the requirements between argument and parameter types. Filter out those overloads for which the constraint system is unsatisfiable. If no applicable overloads remain after this step, an _unresolved error_ is issued.
3. _Choice of most specific overload_: if more than one applicable overload remains after the previous step, try to decide which is the "most specific" by applying [some rules](https://kotlinlang.org/spec/overload-resolution.html#choosing-the-most-specific-candidate-from-the-overload-candidate-set). After this step, only one overload should remain, otherwise an _ambiguity error_ is issued.
4. _Completion_: use the information from the chosen overload to resolve lambda arguments, callable references, and fix type variables.

The first change relates to _no-argument expressions_, that is, those made only from a [`simpleIdentifier`](https://kotlinlang.org/spec/syntax-and-grammar.html#grammar-rule-simpleIdentifier). In that case only the _applicability_ step of the previous list apply. If during that phase no potential overloads are found, and the expression appears as argument to a function, then do not issue an error, but rather mark the expression as **delayed**.

The second change relates to any function call, which now must handle delayed expressions as arguments. The resolution algorithm is modified as follows.

1. Resolve all non-lambda arguments, where some of those may become delayed.
2. During the _applicability_ step, do not introduce information about delayed arguments inside each of the constraint systems.
3. The choice of the most specific overload remains the same.
4. During _completion_ we perform resolution of delayed arguments using the expected type obtain from the chosen overload; in addition to any other tasks in this phase.

### Interaction with inference

As described in the [specification](https://kotlinlang.org/spec/overload-resolution.html#type-inference-and-overload-resolution), type inference is performed after overload resolution. As a result, the expected type may not be known at the moment in which improved resolution may kick in.

```kotlin
val brightColor: Color = WHITE

// <R> run(block: () -> R): R
val darkColor: Color = run { Color.BLACK }  // requires qualification

// runColor(block: () -> Color): Color
val skyColor: Color = runColor { BLUE }
```

## Design decisions

**Priority level**: the current proposal makes this new scope have the lowest priority. In practical terms, that means that even built-ins and automatically imported declarations have higher priority. In a previous iteration, we made it have the same level as `*`-imports; but added ambiguity where currently there is not.

```kotlin
sealed interface Test {
    object Any : Test { }
}

fun foo(x: Test){
    when(x) {
        Any  -> 1  // should still resolve to kotlin.Any
        else -> 2
    }
}
```

Note however that this KEEP not only states that the scope coming from the expected type has the lowest priority, but also that this should _keep being the case_ in the future. We foresee that further extensions to the language (like contexts) may add new scopes, but still the one from the type should be regarded as the "fallback mechanism".

**No `.value` syntax**: Swift has a very similar feature to the one proposed in this KEEP, namely [implicit member expressions](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/expressions#Implicit-Member-Expression). The main difference is that one has to prepend `.` to access this new type-resolution-guided scope.

The big drawback of this choice is that new syntax ambiguities may arise, and these are very difficult to predict upfront. Code that compiled perfectly may now have an ambiguous reading by the compiler. The reason is that `foo<newline>.bar`, now unambiguously a field access, would get an additional interpretation as part of an infix function call following with `.bar`. Note that using `<newline>.` is very common in Kotlin code, so we should be extremely careful on that regard.

One possibility would be to make the parsing dependent on some compiler flag. However, that means that now parsing the file depends on some external input (Gradle file), so tools need to be updated to consult this (which is not always trivial). This goes against the efforts in toolability from the Kotlin team.

One very important difference with Swift is that we take a restrictive route, in which we are very clear about when you can expect types to guide resolution. Swift, on the other hand, allows `.value` syntax everywhere, and tries its best to decide which is the correct way to resolve it. We explicitly do not want to take Swift's route, because it makes the complexity of type checking and resolution much worse; which again goes against giving great tooling.

**On equality**: using the rules above, equalities propagate type information from left to right. But there are other two options: propagating from right to left, or even not propagating any information at all.

The current choice is obviously not symmetric: it might be surprising that `p == CONNECTION` is accepted, but `CONNECTION == p` is rejected. A preliminary assessment shows that the pattern `CONSTANT == variable` is not as prevalent in the Kotlin community as in other programming languages.

On the other hand, the proposed flow of information is consistent with the ability to refactor `when (x) { A -> ...}` into `when { x == A -> ...}`, without any further qualification required. We think that this uniformity is important for developers.

**Interaction with smart casting**: the main place in which the notion of "single definite expected type" may bring some problems is smart castings, as they are the main source of intersection types.

The following code does _not_ work under the current rules.

```kotlin
class Box<T>(val value: T)

fun <T> Box<T>.foo() = when (value) {
  is Problem if value == UNKNOWN -> ...
  ...
}
```

The problem is that at the expression `value == UNKNOWN`, the known type of `value` is `T & Problem`, a case for which the single definite expected type is undefined. There are two reasons for this choice:

- It is unclear in general how to treat intersection types, since other cases may not be as simple as dropping a type argument.
- If in the future Kotlin gets a feature similar to GADTs, we may piggy back on the knowledge that `T` is equal to `Problem`, and face no problem in computing the single definite expected type.

**Additional filtering of candidates**: should we filter out those candidates for which the resolution of the delayed argument with the corresponding parameter expected type fails? At this point we have decided to go with a "no". This answer has the benefit that if we ever move to "yes", the change is backward compatible, as opposed to the other direction.

### Risks

One potential risk of this proposal is the difficulty of understanding _when_ exactly it is OK to drop the qualifier, which essentially corresponds to understanding the propagation of the expected type through the compiler. On the other hand, maybe this complete understanding is not required, as developers will be able to count on the main scenarios: the conditions on a `when` with subject, the immediate expression after a `return`, or the initializer of a property, given that the return type is known.

Another potential risk is that we add more coupling between type inference and candidate resolution. On the other hand, in Kotlin those two processes are inevitably linked together -- to resolve the candidates of a call you need the type of the receivers and arguments -- so the step taken by this proposal feels quite small in comparison.

The third potential risk is whether this additional scope may lead to surprises. In particular, whether programs are accepted which are not expected by the developer, or the resolution points to a different declaration than expected. We think that the very low priority of the new scope is enough to mitigate those problems. In any case, IDE implementors should be aware of this new feature of the language, providing their usual support for code navigation.
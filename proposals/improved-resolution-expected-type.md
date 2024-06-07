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
  * [Importing the entire scopes](#importing-the-entire-scopes)
* [Technical details](#technical-details)
  * [Additional candidate resolution scope](#additional-candidate-resolution-scope)
  * [Additional type resolution scope](#additional-type-resolution-scope)
* [Design decisions](#design-decisions)
  * [Risks](#risks)
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
* equality checks (`==`, `!=`).

### The issue with overloading

We leave out of this KEEP any extension to the propagation of information from function calls to their argument. One particularly visible implication of this choice is that you still need to qualify enumeration entries that appear as arguments as currently done in Kotlin. Following the example above, you still need to qualify the argument to `message`.

```kotlin
val s = message(Problem.CONNECTION)
```

What sets function calls apart from the constructs mentioned before is overloading. In Kotlin, there's a certain sequence in which function calls are resolved and type checked, as spelled out in the [specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution).

1. Infer the types of non-lambda arguments.
2. Choose an overload for the function based on the gathered type.
3. Check the types of lambda arguments.

Improving the resolution of arguments based on the function call would amount to reversing the order of (1) and (2), at least partially. There are potential techniques to solve this problem, but another KEEP seems more appropriate.

As a consequence, operators in Kotlin that are desugared to function calls which in turn get resolved, like `in` or `thing[x]`, are also outside of the scope of this KEEP. Note that [value equalities](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) (`==`, `!=`) are not part of that group, since they are always resolved to `kotlin.Any.equals`.

### Importing the entire scopes

The current proposal imports the _entire_ static and companion object scopes, which include classes, properties, and functions. Whereas the first two are needed to cover common cases like enumeration entries, and comparison with objects, the usefulness of methods seems debatable. However, it allows some interesting constructions where we compare against a value coming from a factory:

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
  WHITE -> ...
  fromRGB(10, 10, 10) -> ...
}
```

An additional advantage is that of uniformity. In particular, the code behaves as you had `*`-imported the `Color` type.

## Technical details

We introduce an additional scope, present both in type and candidate resolution, which always depends on a type `T` (we say we **propagate `T`**). This scope contains the static and companion object callables of the aforementioned type `T`, as defined by the [specification](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver).

This scope has the same priority as `*`-imports. That way, the user may have the mental model that using the name of an enumeration entry without qualification, for example, is the same as if the enumeration was `*`-imported into the file.

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
* _Equality_: in `a == b` and `a != b`, the known type of `a` is propagated to `b`.
    * This helps in common cases like `p == Problem.CONNECTION`.
    * Note that in this case the expected type should only be propagated for the purposes of name resolution. The [specification](https://kotlinlang.org/spec/expressions.html#value-equality-expressions) mandates `a == b` to be equivalent to `(A as? Any)?.equals(B as Any?) ?: (B === null)` in the general case, so from a typing perspective there should be no constraint on the type of `b`.
  
All other operators and compound assignments (such as `x += e`) do not propagate information. The reason is that those operators may be _overloaded_, so we cannot guarantee their type.

### Additional type resolution scope

We introduce the additional scope during type solution in the following cases:

* _Type check_: in `e is T`, `e !is T`, the known type of `e` is propagated to `T`.
* _Type cast_: in `e as T` and `e as? T`, the known type of `e` is propagated to `T`.
* _`when` expression with subject_: in `when (x) { is T -> ... }`, then known type of `x` is propagated to `T`.

## Design decisions

**Priority level**: the current proposal puts the additional scope to be searched when the expected type is known at the same level as `*`-imports. This means that this feature is _not_ 100% backward-compatible, as we have the risk of ambiguity between a declaration imported in such a way, and one available in the static or companion object scope of the expected type.

The most conservative option is for the new scope to have the lowest priority. In practical terms, that means that even built-ins and automatically imported declarations have higher priority, which seems like an odd choice too. As mentioned above, the mental model of these scopes working as `*`-imports seems like a useful tool for understanding the feature, so making them have the same priority level feels like a natural next step.

**No `.value` syntax**: Swift has a very similar feature to the one proposed in this KEEP, namely [implicit member expressions](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/expressions#Implicit-Member-Expression). The main difference is that one has to prepend `.` to access this new type-resolution-guided scope.

The big drawback of this choice is that new syntax ambiguities may arise, and these are very difficult to predict upfront. Code that compiled perfectly may now have an ambiguous reading by the compiler.

Furthermore, Kotliners _already_ use a pattern to avoid re-writing the name of an enumeration over and over, namely importing all the enumeration entries, which makes them available without any `.` at the front. It seems better to support the style that the community already uses than trying to make people change their habits.

**On equality**: using the rules above, equalities propagate type information from left to right. But there are other two options: propagating from right to left, or even not propagating any information at all.

The current choice is obviously not symmetric: it might be surprising that `p == CONNECTION` is accepted, but `CONNECTION == p` is rejected. A preliminary assessment shows that the pattern `CONSTANT == variable` is not as prevalent in the Kotlin community as in other programming languages.

On the other hand, the proposed flow of information is consistent with the ability to refactor `when (x) { A -> ...}` into `when { x == A -> ...}`, without any further qualification required. We think that this uniformity is important for developers.

**Sealed subclasses**: the rules above handle enumeration, and it's also useful when defining a hierarchy of classes nested on the parent.

```kotlin
sealed interface Either<out E, out A> {
  data class Left<E>(error: E): Either<E, Nothing>
  data class Right<A>(value: A): Either<Nothing, E>
}
```

One way in which we can improve resolution even more is by considering the subclasses of the known type of an expression. Making every potential subclass available would be quite surprising, but sealed hierarchies form an interesting subset (and the information is directly accessible to the compiler).

At this point, we have decided against it for practical reasons. If the subclasses are defined inside the parent class (like in `Either` above), this proposal already helps because the subclasses are in the static scope of the parent. If they are defined outside of the parent, then we are not making the particular piece of code any smaller, only avoiding one import. Since imports are usually disregarded by the developers anyway, it seems that adding all sealed subclasses to the scope brings no additional benefit.

### Risks

One potential risk of this proposal is the difficulty of understanding _when_ exactly it is OK to drop the qualifier, which essentially corresponds to understanding the propagation of the expected type through the compiler. On the other hand, maybe this complete understanding is not required, as developers will be able to count on the main scenarios: the conditions on a `when` with subject, the immediate expression after a `return`, or the initializer of a property, given that the return type is known.

Another potential risk is that we add more coupling between type inference and candidate resolution. On the other hand, in Kotlin those two processes are inevitably linked together -- to resolve the candidates of a call you need the type of the receivers and arguments -- so the step taken by this proposal feels quite small in comparison.

The third potential risk is whether this additional scope may lead to surprises. In particular, whether programs are accepted which are not expected by the developer, or the resolution points to a different declaration than expected. We think that the very low priority of the new scope is enough to mitigate those problems. In any case, IDE implementors should be aware of this new feature of the language, providing their usual support for code navigation.

## Implementation note

In the current K2 compiler, these rules amount to considering those places in which `WithExpectedType` is passed as the `ResolutionMode`, plus adding special rules for `as`, `is`, and `==`. Since `when` with subject is desugared as either `x == e` or `x is T`, we need no additional rules to cover them.
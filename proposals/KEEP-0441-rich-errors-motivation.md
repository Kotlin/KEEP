# Rich Errors, aka Error Union Types: Motivation and Rationale

* **Type**: Design proposal
* **Authors**: Michail Zarečenskij, Roman Venediktov
* **Contributors**: Alejandro Serrano Mena, Marat Akhin, Ross Tate
* **Discussion**: [#447](https://github.com/Kotlin/KEEP/discussions/447)
* **Related YouTarck issue**: [KT-68296](https://youtrack.jetbrains.com/issue/KT-68296)
* **Status**: Design review

## Abstract

This KEEP outlines the motivation and rationale for introducing rich errors in Kotlin: a language feature to
handle recoverable error cases. 
```kotlin
fun load(): User | NotFound

when (val user = load()) {
    is User -> println("Hello, ${user.name}")
    is Notfound -> println("Not found!")
}
```

Since error handling is a common concern across programming languages, we find it
important to explore and share what Kotlin currently offers, what we can learn from other ecosystems, and what makes
certain approaches more convenient and expressive. We also examine how Kotlin’s unique features, such as
flow typing, can be leveraged to work with errors.

While this document also discusses our design of rich errors, a separate KEEP covering the full semantics and typing
will be submitted later. Here, we focus primarily on the rationale behind the feature.

### Goals

- Clearly communicate errors to programmers through both syntax and semantics;
- Address the ambiguity when `null` is used both as an error and as a value;
- Ensure the type inference algorithm remains polynomial and unambiguous;
- Draw a clear distinction between "exceptions" and rich, predictable errors.

### Non-goals

- Introduce full-blown union types;
- Introduce "checked exceptions" for Kotlin.

## Table of Contents

- [Error model. Background](#error-model-background)
    - [Preconditions, input validation](#preconditions-input-validation)
    - [Non-local error handling](#non-local-error-handling)
    - [Errors-as-values, programmable errors](#errors-as-values-programmable-errors)
    - [Nullable types as errors](#nullable-types-as-errors)
        - [Shortcomings of using `null` for Errors](#shortcomings-of-using-null-for-errors)
            - [Collections of nullable types](#collections-of-nullable-types)
            - [Mixing nulls in chain-calls](#mixing-nulls-in-chain-calls)
    - [Sealed hierarchies](#sealed-hierarchies)
    - [kotlin.Result](#kotlinresult)
    - [Result, Either from 3rd party libraries](#result-either-from-3rd-party-libraries)
- [Other languages and patterns](#other-languages-and-patterns)
    - [Two-value returns](#two-value-returns)
    - [Wrapped value, typed errors](#wrapped-value-typed-errors)
    - [Effect systems](#effect-systems)
    - [Zig’s error unions](#zigs-error-unions)
- [Key takeaways](#key-takeaways)
    - [Avoiding the pitfalls of checked exceptions](#avoiding-the-pitfalls-of-checked-exceptions)
- [Proposal](#proposal)
    - [Core idea](#core-idea)
        - [Example](#example)
    - [Design](#design)
        - [Error types](#error-types)
        - [Error unions](#error-unions)
        - [Type hierarchy](#type-hierarchy)
        - [Operators and ergonomics](#operators-and-ergonomics)
        - [Smart-casts](#smart-casts)
    - [Usage patterns](#usage-patterns)
        - [Expressive signatures](#expressive-signatures)
        - [Local tags and in-place errors](#local-tags-and-in-place-errors)
    - [Generalization of Existing Approaches](#generalization-of-existing-approaches)
    - [Migration and compatibility](#migration-and-compatibility)
    - [Limitations](#limitations)
- [Appendix](#appendix)
  - [Future enhancements for preconditions](#future-enhancements-for-preconditions)

## Error model. Background

Everything begins with two questions:

- How can I, as a developer, express that a method might fail?
- If a method might fail, how can I handle errors?

Kotlin, being originally rooted in the JVM platform, inherited the concept of exceptions. However, while Kotlin has a
notion of exceptions, it doesn't have checked ones, which are widely considered problematic. One of the implications that
concerns us today is that there is no _dedicated_ language construct for errors in Kotlin, except for the intentionally limited
`kotlin.Result` class, which is discussed in [later sections](#kotlinresult).

> There are many resources outlining problems of checked exceptions; we suggest reading the post
["Kotlin and Exceptions"](https://elizarov.medium.com/kotlin-and-exceptions-8062f589d07) by Roman Elizarov.

The lack of language support for errors has led to various _organic_ approaches to error handling, many of which rely on
specific language features in certain scenarios. It's crucial to understand these scenarios to focus on improvements
in this area while keeping the language and the ecosystem consistent.

### Preconditions, input validation

One area where the current exception mechanism performs well is in handling preconditions.
Normally, preconditions close the gap of type system limitations in an ad-hoc manner.

```kotlin
fun process(userName: String) { // (1)
    ...
}
```

If we squint hard enough, we can say that the method `process` has the precondition that an argument has to be a
`String`.
Of course, it's a core type, and we have it right in our runtime. We may add more conditions:

```kotlin
fun process(userName: String) { // (2)
    require(userName.isNotBlank()) { "userName must not be blank" }
    require(userName.first().isLetter()) { "userName ($userName) must start with a letter" }
}
```

In this case, we could introduce a `NonBlankString` type to encapsulate the 'not blank' check or
have a dedicated type for users, which is often justified. However, it is also often much simpler
to perform an in-place check for the sake of overall simplicity.

This toy example shows that just as we can't _reasonably recover_ from case `(1)` when passing a
non-String to the process method, we also normally don't have an intention to recover in the situation
if we pass a blank string in case `(2)`.

That's the first case where it's natural to use exceptions for unrecoverable situations.

We also see a number of [improvements](#future-enhancements-for-preconditions) to preconditions that are outside the scope of this KEEP.

### Non-local error handling

A second natural use of exceptions is when errors must be handled non-locally, across different parts of the code, often
to delegate responsibility for error handling to a different component.

If we take the Spring framework or IntelliJ IDEA as examples, a common approach in frameworks is to first translate
low-level or user-defined exceptions into unchecked, framework-specific ones. These are then handled internally by the
framework. Typically, the top-level application shouldn’t fail due to an error in one of the lower-level components, so
it's often the framework’s responsibility to handle such exceptions.

Specifically, in Spring MVC, user-defined exceptions can be annotated with `@ResponseStatus`, which specifies the
intended
HTTP status code and message:

```java

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserNotFoundException extends RuntimeException { ...
}

@GetMapping("/{id}")
public UserDto getUser(@PathVariable Long id) {
    return userService.findById(id)
            .map(UserDto::fromEntity)
            .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
}
```

HTTP 404 response will be returned if the method throws `UserNotFoundException`.

A custom exception handler can also be added:
```java

@ExceptionHandler(UserNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public String handleUserNotFound(UserNotFoundException ex) {
  // some business-logic
  return ex.getMessage();
}
```

Exceptions are convenient in this context because developers don’t need to handle them explicitly at every call site.
Code can be changed without changing the callers. This scenario is crucial and was recognized as one of
the main ones for exceptions in Kotlin.

However, it’s important to note that exceptions here do not act as part of an error model: there are no signals
about potential throws, no try/catch/finally blocks in application code. Exceptions are used to bypass the
"normal" execution path.

### Errors-as-values, programmable errors

While unchecked exceptions in Spring are convenient and often exactly what we want for cross-cutting concerns, there are
many cases where no higher-level component will (or should) handle an error, or where failures must be handled precisely
at a particular API boundary.

In these scenarios, Kotlin supports an errors-as-values style: transforming recoverable failures (and, when appropriate,
mapping exceptions) into values using nullable types, sealed hierarchies, or generic containers such as `Result`/`Either`.

The errors-as-values style makes it possible to work with errors just like with any other
data. This approach provides several benefits over exceptions:

- **Composability**: Errors can be passed through functions and lambdas as regular values, making it possible, for instance,
  to implement patterns such as retries/backoffs and others without non-local control-flow jumps.
- **Predictability and locality**: Errors are visible in types, guiding call sites on how to handle specific errors.
- **Refactoring and safety**: Changing error cases forces callers to update accordingly.
  - We specifically worked on making `when` expressions exhaustive ([KT-12380](https://youtrack.jetbrains.com/issue/KT-12380/Support-sealed-exhaustive-whens)), 
    which worked out really well. The same principle applies here: if a new case is added, it should be clear how and where
    the code needs to be updated.

Of course, this approach also comes with trade-offs, which we describe in more detail below.

### Nullable types as errors

Let's consider a few examples presented in the Kotlin standard library:

```kotlin
fun String.toInt(): Int
fun String.toIntOrNull(): Int?

fun <T : Comparable<T>> Array<out T>.min(): T
fun <T : Comparable<T>> Array<out T>.minOrNull(): T?
```

Here, functions that return a non-nullable type throw an exception if the evaluation fails, while `minOrNull`/
`toIntOrNull`
return `null`. In Kotlin, it's idiomatic to use exceptions for logic errors (unrecoverable cases) and type-safe results
for everything else.

Using `null` to represent an error is common in Kotlin, and the convenient ergonomics of nullable operators contributes
to this in practice:

```kotlin
"0mb".toIntOrNull() ?: default
arrayOf(42).minOrNull()?.process()
```

However, one might argue that it would be safer to have only nullable versions and rely on explicit assertions or
checks. However, this straightforward approach can be problematic for many functions, especially those like `toInt`,
`min`,
or, in general, _functions without side effects_. Often, the argument has already been validated beforehand or is
clearly correct from
the _context_. Limiting to nullable versions would lead to redundant checks that don't make the code safer but make it
harder to read.

At the same time, we occasionally hear concerns that it's unclear whether a function throws, especially with core
functions from the standard library.

#### Shortcomings of using `null` for Errors

##### Collections of nullable types

Let's consider one more example with "orNull" pattern from the stdlib:

```kotlin
/**
 * Returns the first element, or `null` if the list is empty.
 */
public fun <T> List<T>.firstOrNull(): T? {
    return if (isEmpty()) null else this[0]
}
```

This function returns `null` if the list is empty, _but also_ if the first element is `null`.
This creates ambiguity and can lead to incorrect behavior if the caller forgets that the collection itself might contain
`null` elements.

It would've been clearer to have:

```kotlin
// New syntax for rich errors "T | Empty" 
fun <T> List<T>.first(): T | Empty
```

where `Empty` lives in a separate hierarchy from `T`, so it cannot appear in `List<T>`.

##### Mixing nulls in chaining calls

Imagine having two functions that use `null` as an error:

```kotlin
fun fetch(): User?
fun User.charge(): Transaction?
```

Today it's convenient to chain them like this: `fetch()?.charge()`. However, when an error occurs, it becomes difficult
to trace the result because we end up with just `null`  and we can't tell whether the error came from `fetch` or
`charge`.

At the same time, we value the convenience of chaining calls and recognize that checking for errors or `null` after each
call can be tedious and often disregarded as a result.

### Sealed hierarchies

When the approach with null for errors falls short, we have sealed hierarchies.
For instance, it might be the case because of the reasons above or simply because one needed to cover more error cases.

```kotlin
sealed interface ApiStatus {
    class Success<T>(val data: T) : ApiStatus

    data object NetworkError : ApiStatus
    data object Unauthorized : ApiStatus
    data class ValidationError(val reason: String) : ApiStatus
}
```

This approach has several major benefits:

- **Exhaustiveness**: The compiler ensures you handle all possible error cases in `when` statements.

- **Clarity**: Consumers of such API know exactly what to expect. And errors become regular values that can be logged,
  analyzed, or displayed—rather than just being thrown.

- **Extensibility**: It's relatively easy to add new error cases. Of course, it won’t be source-compatible with the old
  code,
  and a mechanism to make the hierarchy “incomplete” ([KT-38750](https://youtrack.jetbrains.com/issue/KT-38750)) is
  required
  to support this in libraries.

At the same time, this approach introduces a few drawbacks.

- A wrapper is used for “successful” cases, e.g. `Success<T>(val data: T)`
  - This add little actual value
- With a separate sealed hierarchy and wrappers
  - You’re forced to handle the error immediately, or
  - You need to introduce multiple operators that work over such a wrapper

In the end, sealed hierarchies are powerful, but also verbose and _really_ repetitive. The most common cases have generic
counterparts, such as `Result`, which relieve users from recreating the same hierarchies and utility functions. In our standard
library we have `kotlin.Result`, which, on the one hand, encapsulates this logic but, on the other hand, is quite
specialized and was designed mainly for coroutine machinery.

### kotlin.Result

kotlin.Result was introduced in Kotlin 1.3 with the primary goal of using it in `Continuation<T>` callback interface:

```kotlin
public interface Continuation<in T> {
    /**
     * Resumes the execution of the corresponding coroutine passing a successful or failed [result] as the
     * return value of the last suspension point.
     */
    public fun resumeWith(result: Result<T>)
}
```

Full KEEP on this: [KEEP-127](./stdlib/KEEP-0127-result.md).

For performance reasons, the `Result` class was introduced as a value class, which helped avoid boxing. However, this
also
came with some limitations. In particular, `Result` is not a sealed class, which led to a somewhat awkward API, for
instance, the `isSuccess` and `isFailure` properties, that cannot introduce smart casts and often require code
duplication.
Additionally, the `Result` was intentionally designed with a single type parameter, which limits its use cases.

One interesting attempt was to integrate Result more deeply into the language by making operators like `?.`, `?:`, and
`!!`
work with it. At one point, we even disallowed using nullable operators on `kotlin.Result`, intending to later redefine
their behavior in a backwards-compatible way. However, this idea was eventually dropped due to its design complexity.
The main complication came from trying to bind `Result` with _exceptions_, for example, by automatically mapping Java
methods with `throws` clauses to `Result<T, E>`. In hindsight, it seems wise we didn’t go down that path. Today, the
prevailing view is that errors are better represented as values rather than exceptions, and exceptions should be used
for
unrecoverable cases.

### Result, Either from 3rd party libraries

Due to the specifics of `kotlin.Result` and its limited use cases, several libraries provide their own implementations
of
Result-like or Either types. One of the most well-known examples
is [Either](https://apidocs.arrow-kt.io/arrow-core/arrow.core/-either/index.html) from the Arrow library.

Interestingly enough, we also often see custom result types in our own projects. For example, Kotlin has a few
([one](https://github.com/JetBrains/kotlin/blob/c811992b611b8a725b6b55dafa574a0b145b5da3/native/commonizer/src/org/jetbrains/kotlin/commonizer/metadata/utils/MetadataDeclarationsComparator.kt#L43),
[two](https://github.com/JetBrains/kotlin/blob/c811992b611b8a725b6b55dafa574a0b145b5da3/compiler/cli/cli-common/src/org/jetbrains/kotlin/utils/parametersMap.kt#L60)),
and IntelliJ
has more ([one](https://github.com/JetBrains/intellij-community/blob/d73a081b09fcb0f53308352a57ad54c0721f0443/platform/vcs-impl/src/com/intellij/openapi/vcs/impl/LineStatusTrackerManager.kt#L1406),
[two](https://github.com/JetBrains/intellij-community/blob/d73a081b09fcb0f53308352a57ad54c0721f0443/platform/collaboration-tools/src/com/intellij/collaboration/auth/ui/LazyLoadingAccountsDetailsProvider.kt#L92),
[three](https://github.com/JetBrains/intellij-community/blob/d73a081b09fcb0f53308352a57ad54c0721f0443/platform/vcs-impl/lang/src/com/intellij/codeInsight/hints/VcsCodeVisionProvider.kt#L287), ...).
There are dozens of modifications and more specific examples. One can try looking for types with the name "Result" in the
code.
We should also be cautious, as many specific solutions might suggest that there is no one-size-fits-all approach.

So, on the one hand, it signals the lack of a single proper `Result` type. On the other hand, there always seems to be a
need
to slightly adjust such a Result-like type. However, one thing we're sure of is that people want to signal the status of
a computation in a type-safe way and use familiar names and approaches for it.

Also, such Result-like types typically follow a common pattern: a generic, simple `Success` case, and one or
more richer, _domain-specific_ `Error` cases that carry more meaningful context and data.

A general problem with Result-like types is that they need operators, pattern matching to conveniently "extract" values
from wrappers.
For instance, `kotlin.Result` defines a bunch of operators like `fold`, `map`, `mapCatching`, `recover`,
`recoverCatching`, `onFailure`, `onSuccess`, and so on.
And a simple code that invokes methods and handles errors turns into:

```kotlin
fun getUserResult(): Result<User> {
    val user = fetchUserResult().getOrElse { return Result.failure(it) }
    val parsedUser = user.parseUserResult().getOrElse { return Result.failure(it) }
    return Result.success(parsedUser)
}

fun usageResult() {
    getUserResult()
        .onSuccess { user -> println(user.name) }
        .onFailure { error ->
            when (error) {
                is NetworkException -> println("Failed to fetch user")
                is ParsingException -> println("Failed to parse user")
                else -> println("Unhandled error")
            }
        }
}
```

From a business logic perspective we’re only invoking three functions: `fetchUserResult`, `parseUserResult`, and
`getUserResult`, but here, most of the code is about working with Result operators.

## Other languages and patterns

### Two-value returns

This approach is common in languages such as Go, Lua, Julia, and others.
Variations of this method also appear in JavaScript callbacks (e.g., `(err, result) => {}`) and even in C using `errno`
or out parameters with error codes as a function result. Recently, JavaScript introduced this pattern through the `try`
operator.

This approach is common in ecosystems and languages where dynamic or structural typing is at the heart of a language.

### Wrapped value, typed errors

We clearly see a prevalence of the approach with wrapping values and errors into a generic sealed hierarchy.

Some examples of these wrappers are `Either`, `Result`, and `Optional`.
This approach is used in Rust, languages with a strong bias towards functional programming (Haskell, OCaml, Scala, Erlang, Elixir ...)
and in lots of other languages (Kotlin, C++, Java, C#, ...).

Essentially, the approach is to have a wrapper value that encapsulates the result of a computation.

These wrappers cover three main cases:

- `Optional`/`Option`: represent a value that may or may not be present.
- `Result`, `Try`: wraps exception, often exist mostly for interop with `Throwable`-like classes. 
Not suited for domain-specific
  errors.
- `Either`: the most generic case for having a value or an error presented as a value.

Every such type defines a number of operations mostly for chaining and error propagation.
For instance, [scala.Option](https://www.scala-lang.org/api/2.13.3/scala/Option.html),
[java.util.Optional](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html),
[kotlin.Result](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-result/).

Functional programming languages often provide first-class support for algebraic “wrapper” types such as `Option`,
`Result`/`Either`, and `Try`, making these patterns feel natural and ergonomic. This support typically includes
algebraic
data types, exhaustive pattern matching, and monad comprehensions. Kotlin partially covers these features,
where monad comprehensions and pattern matching are replaced with extensions and flow-typing.

In practice, although one can certainly adopt `Result` or `Either` in Kotlin (as many projects have demonstrated), the
Kotlin standard library and most core libraries are designed around a more direct, value-centric style, encouraging
developers to work with data directly rather than through wrapper types. This enables smart casts and makes
extensibility easier, as it's enough to have extensions on the main type rather than on its wrappers.

### Effect systems

Experimental in Scala, OCaml and somewhat in TypeScript ([link](https://effect.website/docs/error-management/two-error-types/)). 
The idea of effects is that all side effects that a pure function may produce can be described as a set of functions
(e.g., write to a file, read from a file). And we are able to track for each function which effects it may have.

This technique is powerful and promising, but it requires advanced type machinery. Depending on how capable (or actually
limited) your effect system is, it can encode cases like I/O (read/write may fail), composable "checked" exceptions, 
and domain-specific errors as effects. Another challenge, beyond the complex analysis, is that all effects are encoded
uniformly. This forces programs to be written in a very specific style, requiring a paradigm shift across the
ecosystem, and even then, there may still be no one-size-fits-all solution. 

### Zig’s error unions:

The language feature most similar to the proposed one
is [error union types in Zig](https://ziglang.org/documentation/master/#Error-Union-Type): `Error!T`,
`FileNotFound!File`.

- There are both nullable (optionals) and error union types in the language, and they are orthogonal.
- Error types are just tags without data, represented as integers at runtime.
    - There is a high [demand](https://github.com/ziglang/zig/issues/2647) for allowing content in errors.
- Zig has special operators and built-in short-cuts to work with errors.

In general, community feedback on this feature seems highly positive.

## Key takeaways

Considering all the above, the following points summarize the state of error handling in Kotlin.

- **Exceptions for unrecoverable errors and non-local handling**: Kotlin encourages using unchecked exceptions for
  unrecoverable situations such as bugs, failed preconditions, or invariant violations.
  They are also used in frameworks to bypass normal control flow without requiring explicit handling.
  Such exceptions are usually not caught in regular code and instead bubble up to a global handler or crash reporter.
- **Type-safe results for expected failures**: For errors that a caller is expected to handle (invalid inputs, missing
  resources, domain-specific conditions), it's idiomatic to return `null` or an object indicating a failure rather than
  throwing
- **`null` is a quick but limited solution**: using `null` for errors is concise and works well with null-safety
  features and it's
  idiomatic for simple failure cases, for example, in the Kotlin standard library.
    - However, `null` cannot show which error occurred and can introduce ambiguity (a `null` value vs an error).
    - Over-reliance on `null` for errors can make error origins hard to track in complex call chains.
- **Sealed error hierarchies add clarity at a cost**: Defining a sealed hierarchy for results gives a
  clear contract (caller knows every possible error case) and ensures error handling is exhaustive. The cost is
  verbosity and the need for wrapper objects and custom operators to work with those wrappers.
- **Kotlin’s built-in `Result` isn’t sufficient for domain and recoverable errors**: While useful internally, `Result` is
  constrained (only one type parameter, no support in `when`) and it's bound to work with `Throwable` classes.
- **3rd party solution for Result, Either**: There are a few third-party Result/Either implementations and many ad-hoc
  Result-like types in various codebases, which can be viewed as a demand for a more powerful, standardized error handling
  mechanism.
- **Other ecosystems use errors as values**: in structurally-typed languages, two-value returns are common; in more
  type-heavy languages, it's typically `Either<E, T>`. Most of them treat errors as values rather than exceptions.

As a result, there's no first-class support for errors, i.e., no built-in way to declare in a function signature that it
can fail
in a recoverable way. You either rely on documentation (for exceptions) or use types like `T?` or `Result<T>` which are
not
uniformly understood by all APIs.

Speaking of unchecked exceptions, they play an important role in Kotlin and cover existing use cases. We don't follow
the
path of replacing them with a new feature or adjusting somehow in the language.

### Avoiding the pitfalls of checked exceptions

Any new error mechanism should be designed with lessons from checked exceptions in mind. We want compile-time awareness
of errors (so they’re not ignored), but we do not want to reintroduce Java’s problems where every call site is forced
into verbose try-catch or throws declarations, have non-trivial control-flow and poor composability with higher order
functions. The goal is to make error handling visible but not burdensome. Techniques from other languages
(like Rust’s `?` or Zig’s `try`) show that it’s possible to propagate errors with minimal noise, and Kotlin
could adopt similar ideas especially having good experience with nullability operators.

## Proposal

### Core idea

Any function that may fail in a recoverable way can declare this in its return type using
union types of the form `ValueType | ErrorType1 | ErrorType2 | ...`. In combination with the must-use return values
feature ([KEEP-412](./proposals/KEEP-0412-unused-return-value-checker.md)), the
compiler will report an error if such a result is ignored.

#### Example:

```kotlin
error object NotFound
error object PermissionDenied

fun loadUser(id: String): User | NotFound | PermissionDenied
```

Here, the function explicitly declares the possible error states (`NotFound`, `PermissionDenied`) it can return,
in addition to the success type.

### Design

#### Error types

* Errors are defined using a new soft keyword `error`:

  ```kotlin
  error class NetworkError(val code: Int)
  error object NotFound
  ```

Error types **cannot** have superclasses, superinterfaces, or generic parameters.
Essentially, error types present a new flat hierarchy in the Kotlin type system. It's needed to form only disjoint unions.

In order to compose errors, it's proposed to use typealiases:
```kotlin
typealias UserFetchError = NotFound | PermissionDenied
```

#### Error unions

* The `|` operator is used to unite a value type with one or more error types:

  ```kotlin
  fun foo(): String | NetworkError | DbError
  ```
* Only **one non-error type** may appear in the union, and if present, it must be written leftmost.
* Multiple error types can be freely combined in a union.
* An error union can also have *no* regular type, e.g. for functions that always fail or just report errors:

  ```kotlin
  fun logError(e: NetworkError | ValidationError)
  ```

Note that nullable error types are not allowed in a union, but non-error components can be nullable: 
```kotlin
fun foo(): String? | Error // OK!
fun bar(): String | Error? // compile-time error!
```

It's question whether we should allow nullable error types on their own though, 
since they could be viewed as `Nothing? | Error`.

#### Type hierarchy

* All error types implicitly extend a synthetic supertype `Error`.
* `Error` forms a parallel hierarchy to `Any`/`Any?`; it is **not** related to `Any`, and `null` is *not* an error.
* The new global supertype becomes `Any? | Error`.

> `kotlin.Error` is not related to `java.lang.Error` in any way here

#### Operators and ergonomics

Similar to how nullable operators work for `T? = T | null`, we extend them to errors:

* The **safe-call operator** `?.` is reused:
  If a value in a chain is an error, the entire chain short-circuits and propagates the error.

```kotlin
fun Int.giveString(): String = TODO()

fun bar(nonNullable: Int | Error, nullable: Int? | Error) {
    nonNullable?.giveString() // type is String | Error
    nullable?.giveString() // type is String? | Error
}
```

An example with errors accumulation:
```kotlin
error object FeatureDisabled
error object NoData

fun loadFromAGP(): Model | NoData
fun Model.computeTask(): Task | FeatureDisabled

fun accumulateErrors() {
    // resulting type is Model | NoData | FeatureDisabled
    when (val task = loadFromAGP()?.computeTask()) { 
        is Task -> println("OK")    
        is FeatureDisabled -> println("Feature is disabled")    
        is NoData -> println("No data")    
    } 
}

```

* The **bang-bang operator** `!!` throws an exception wrapping the error value for cases
  where users want to escalate an error to an exception.

```kotlin
fun bar(v: Int? | MyError) {
    v!! // throws NPE if v is null; throws KotlinErrorException(MyError) if v is MyError
}
```

* The **elvis operator** ?: **is not extended** to error unions. What stops us from doing this is that there's no _syntactic_ place where we
  can
  provide an error value, and without one, errors could be silently swallowed. Instead, a standard function
  `ifError { ... }` is provided for explicit handling:

```kotlin
inline fun <T : R, E : Error, R> (T | E).ifError(onError: (E) -> R): R {
    return if (this is Error) onError(this) else this
}

v.ifError { e ->
    when (e) {
            ...
    }
}
v.ifError { return it }
```

Can't help but notice how beautifully smart casts work with the `ifError` function. The `onError` parameter expects `E`, but
we check `this is Error`, where `Error` is a _supertype_ of `E`, and it still works, we can pass `this` to `E`. How?
Because in Kotlin, smart casts perform type intersection, not a direct cast to the `is` type. After the smart cast, the
type of `this` becomes `(T | E) & Error`, which then simplifies to `E` through the chain:
```
(T | E) & Error ~> T & Error | E & Error ~> Nothing | E ~> E
``` 

> [!NOTE]
> A few times `?` or `try` operator was proposed in our discussions. We're aware of the idea and are exploring options.

#### Smart-casts

As every smart-cast creates an intersection type, and because our error unions are always disjoint, smart-casts fit
naturally with error types  `(A | B) & A = A`:

```kotlin
val result: Int | ParseError = parseInt(input)
when (result) {
    is Int -> println("Success: $result") // result is Int
    is ParseError -> println("Failed to parse: ${result.errorCode}") // result is ParseError
}
```

#### Use negative information for smart-casts

However, with errors, it becomes more important to support negative smart casts for exhaustiveness ([KT-8781](https://youtrack.jetbrains.com/issue/KT-8781/Consider-making-smart-casts-smart-enough-to-handle-exhaustive-value-sets)):
```kotlin
fun foo(): String | Err1 | Err2

fun f() { 
    val x = foo()
    if (x is Err1) return
  
    // no need for `is Err1` or an `else` branch here
    when (x) {
        is String -> println("string")
        is Err2 -> println("Err2")
    }
}
```

### Usage patterns

#### Expressive signatures

The error union model enables functions to express their ability to fail in their type, eliminating ambiguity:

* Instead of returning `null` for “not present,” use:

  ```kotlin
  error object NotPresent

  operator fun <K, V> Map<K, V>.get(key: K): V | NotPresent
  ```
* Instead of throwing `NumberFormatException` from parsing:

  ```kotlin
  error object InvalidFormat

  fun String.toIntOrError(): Int | InvalidFormat
  ```

#### Local tags and in-place errors

For implementation-internal states (such as “not found” within an algorithm), error objects can be declared locally and
safely scoped:

```kotlin

inline fun <T> Sequence<T>.last(predicate: (T) -> Boolean): T {
    error object NotFound
  
    var last: T | NotFound = NotFound
    for (element in this) {
        if (predicate(element)) {
            last = element
        }
    }
    if (last == NotFound) throw NoSuchElementException()
    return last // smart-cast to T
}
```

This eliminates unsafe unchecked casts and enhances code clarity. 
Compared with the original version (`NotFound` will be declared outside the function):

<table>
<tr>
<td>

```kotlin
inline fun <T> Sequence<T>.last(predicate: (T) -> Boolean): T {
  var last: T | NotFound = NotFound
  for (element in this) {
    if (predicate(element)) {
      last = element
    }
  }
  if (last == NotFound) throw NoSuchElementException()
  return last
}
```

</td>
<td>

```kotlin
inline fun <T> Sequence<T>.last(predicate: (T) -> Boolean): T {
  var last: T? = null
  var found = false
  for (element in this) {
    if (predicate(element)) {
      last = element
      found = true
    }
  }
  if (!found) throw NoSuchElementException()
  @Suppress("UNCHECKED_CAST")
  return last as T
}
```

</td>
</tr>
</table>

### Generalization of existing approaches

This proposal unifies and generalizes Kotlin’s existing error-handling solutions:

* **Nullable types**:
  Functions that today return `T?` to signal recoverable failure can instead return `T | ErrorType`, making the failure
  reason explicit and type-safe.
* **kotlin.Result**:
  Result-like wrappers can be modeled as error unions. For example, `Result<T>` can be mapped to `T | ExceptionError`,
  where `ExceptionError` wraps a `Throwable`. However, note that Result-like wrappers (and `Either`) with two type parameters cannot be
  represented with rich errors, as we don’t allow errors to have generics for now.
* **Sealed hierarchies**:
  Common error types (e.g. `NotFound`, `InvalidInput`) can be declared once and reused across many APIs, eliminating the
  need for repetitive sealed hierarchies and increasing composability.

### Is it a replacement for exceptions?

Definitely not. Exceptions remain an important part of the language.

With rich errors, however, we want to draw a clear line for when exceptions should be used.
Today, in the absence of a better alternative, exceptions are often used even for recoverable errors that can be handled
locally.
By introducing a dedicated construct for such cases, we expect exceptions to be used less often, and mainly for
unrecoverable errors and non-local handling.

### Migration and compatibility

The migration strategy is always essential and far from trivial for an existing language. It should be worked out in
detail, especially given that error handling concerns almost everyone. We'll describe it in upcoming documents. Here,
we’d like to highlight that it is an important aspect for us and we have the following potentially affected APIs:

* Nullable-returning APIs that encode "optional" pattern.
* Standard library APIs will gain error-union alternatives (`XOrError`), coexisting with existing nullable and throwing
  versions for compatibility.
* Custom wrappers (`Result`, `Either`, etc.) can interop with error unions via straightforward conversion and
  be gradually replaced with unions.

### Limitations

* Error types may not be generic, to keep unions tractable and prevent exponential type inference.
* Only a single non-error type may be present in a union.

## Appendix

###  Future enhancements for preconditions

Preconditions in Kotlin have proven to be convenient and popular, especially with the introduction of contracts. Now,
require-like
functions can propagate smart casts from the argument position:

```kotlin
private fun KtElement.findPackageName(): Name {
  require(this is KtClsFile || this is KlibDecompiledFile) { "$this must be a file" }
  // this is smartcasted to KtDecompiledFIle, which is the common supertype of KtClsFile and KlibDecompiledFile 
  return this.packageFqName.shortName()
}
```

While it's not the purpose of this KEEP, we see a number of improvements that could be explored in the future:

- Since contracts are often used when the type system falls short, we could investigate the most popular ones and
  consider integrating them into the language in some form (such as .nonEmpty, .nonBlank, and so on).
- Utilize [power-assert](https://kotlinlang.org/docs/power-assert.html) mechanics to automatically generate descriptive
  assertion messages
- It's often justified to place preconditions closer to their signatures. We already have "contracts" that hold a
  special place in function bodies with a sort of similar meaning. We could consider abstracting these concepts so that
  tooling and the compiler are more aware of preconditions/contracts, enabling deeper analysis or simply rendering
  functions
  differently if needed.
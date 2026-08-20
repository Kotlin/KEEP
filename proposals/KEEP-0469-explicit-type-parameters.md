# Explicit Type Parameters

* **Type**: Design Proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, Alejandro Serrano Mena
* **Status**: Draft
* **Discussion**: TODO

# Abstract

Kotlin relies on type inference for most generic function calls, 
which works well when inferred types do not affect runtime behavior. 
However, in some cases types directly affect execution - for example, in casting operations, filtering by type, 
or DSLs where type arguments are a proper part of the embedded language.

This document proposes to introduce explicit type parameters that are never inferred 
and for which type arguments must be specified explicitly on each call site:

```kotlin
inline fun <explicit reified T> List<*>.castAll(): List<T>

val list: List<CharSequence> = listOf("a", "b", "c")
val ok = list.castAll<String>() // ok
val error: List<String> = list.castAll() // error: type argument required
```

# Table of Contents

- [Abstract](#abstract)
- [Introduction](#introduction)
- [Proposal](#proposal)
  - [Motivation](#motivation)
  - [Design](#design)
- [Alternatives](#alternatives)
  - [Deducing Type Parameter Explicitness](#deducing-type-parameter-explicitness)
  - [Type Parameter Annotation](#type-parameter-annotation)
- [Migration](#migration)

# Introduction

Kotlin has type erasure runtime semantics for non-reified type parameters,
and for most Kotlin code, types are only used as a safety check:
they statically reject unsafe code but do not affect runtime behavior.
Therefore, the complex, heuristics-based, and ever-evolving type inference system is acceptable:
what concrete types are inferred does not affect execution.
Ensuring types safely approximate the runtime behavior is a separate concern.
This is why it is okay for the same expression `emptyList()` to have
different types depending on the context:

```kotlin
fun takeInts(l: List<Int>) {}
fun takeStrings(l: List<String>) {}

takeInts(emptyList())
takeStrings(emptyList())
```

In both cases, runtime behavior of `emptyList()` is the same: it returns an empty list.

However, in some cases, types directly affect the runtime behavior,
and thus they are an important part of the source code.
In such situations, inferring types is rather undesirable.
For example, it would be strange to infer type argument of `as`:

```kotlin
fun takeString(s: String) {}

val cs: CharSequence = "42"
takeString(cs as _) // invalid code
```

For similar reasons, the standard library requires explicit type arguments
for functions like `List.castAll` and `Iterable.filterIsInstance`.

The idea is also applicable to DSLs, where type arguments
might be a proper part of the embedded language and should not be inferred.
For example, take a dependency injection DSL:

```kotlin
module {
    single<Service> { ServiceImpl(get()) }
}
```

Explicitly provided type argument `Service` is a part of the DSL here,
without it, the type of the dependency would be inferred to `ServiceImpl` instead of `Service`.

# Proposal

## Motivation

To require providing explicit type argument on all call-sites for some functions,
Kotlin standard library currently applies internal `@kotlin.internal.NoInfer` type annotation:

```kotlin
inline fun <reified R> Array<*>.filterIsInstance(): List<@kotlin.internal.NoInfer R>
inline fun <reified R> Iterable<*>.filterIsInstance(): List<@kotlin.internal.NoInfer R>
inline fun <reified R> Sequence<*>.filterIsInstance(): Sequence<@kotlin.internal.NoInfer R>

inline fun <reified T> List<*>.castAll(): List<@kotlin.internal.NoInfer T>

context(context: @kotlin.internal.NoInfer A)
inline fun <A> contextOf(): @kotlin.internal.NoInfer A
```

The annotation generally allows a fine-grained control over type-inference:
it prohibits using information from a particular type variable occurrence to infer the mentioned type variable.
However, in the standard library,
`@NoInfer` is applied to all type variable occurrences in function signatures,
so there is no source of information to infer the type variable.
Thus, each caller is forced to specify the type argument explicitly:

```kotlin
val list: List<CharSequence> = listOf("a", "b", "c")
val ok = list.filterIsInstance<String>()
val error: List<String> = list.filterIsInstance() // CANNOT_INFER_PARAMETER_TYPE
```

As `@NoInfer` is internal, there is currently no publicly available stable way
for Kotlin users to declare that a type parameter should always be provided with an explicit type argument
while defining their APIs and DSLs.
See related issues: [KT-54642](https://youtrack.jetbrains.com/issue/KT-54642/Expose-NoInfer-annotation-and-design-it-for-public-use),
[KT-54477](https://youtrack.jetbrains.com/issue/KT-54477/NoInfer-doesnt-work-for-builders).

## Design

We propose to introduce a new soft-keyword `explicit` type parameter modifier,
which disables type inference for the type parameter 
and forces callers to always specify the corresponding type argument explicitly:

```kotlin
inline fun <explicit reified T> List<*>.castAll(): List<T>

val l: List<CharSequence> = listOf("a", "b", "c")
val ok = l.castAll<String>() // ok
val error: List<String> = l.castAll() // error: explicit type argument is required
```

Passing underscore `_` for explicit type parameters in the type argument list is also prohibited:

```kotlin
val error: List<String> = l.castAll<_>() // error: explicit type argument is required
```

In Kotlin, once a type-argument list is written at the call site,
it must contain a position for every function type parameter.
If a function has both explicit and regular type parameters,
callers have to provide the whole type-argument list.
Underscore `_` can be used to infer some type arguments:

```kotlin
inline fun <T, explicit reified R> T.castTo(): R = this as R

val cs: CharSequence = "string"
val s = cs.castTo<_, String>()
```

Only type parameters of functions can be explicit, it is prohibited to mark
class type parameters with `explicit`:

```kotlin
class Generic<explicit T> // error: class type parameter cannot be explicit
```

# Alternatives

## Deducing Type Parameter Explicitness

Type parameters that should be explicit commonly also have the following properties:
- They are `reified`, usually because the function code relies on the actual call-site type argument value.
- They do not appear among value parameter types, only in the return type. 
  Thus, the only constraint on the type parameter is provided by the expected return type.

Standard library functions like `Iterable<*>.filterIsInstance` or `List<*>.castAll` fall under this rule.
However, `contextOf` does not.

We considered an idea to deduce explicitness of a type parameter from function signature
and make the compiler or IDE warn callers if the type argument is not provided explicitly.
We decided against it because:
- This might be unexpected for function author, as it would be a property of a type parameter
  implicitly deduced based on a combination of unobvious factors.
- An explicit opt-out would have to be provided anyway, as there are APIs that are intentionally
  designed to use type inference even for type parameters that might affect runtime execution.

Examples for the latter point are dependency injection and mocking frameworks:

```kotlin
// io.mockk.MockKMatcherScope.any
inline fun <reified T> any(): T

class Adder {
 fun addOne(num: Int) = num + 1
}

val adder = mockk<Adder>()

// any() means "match any argument"
every { adder.addOne(any()) } returns -1

// org.koin.core.scope.Scope.get
inline fun <reified T : Any> get(
  qualifier: Qualifier? = null,
  noinline parameters: ParametersDefinition? = null,
): T

class UserRepository(val db: Database)

val appModule = module {
    single { Database() }
    single { UserRepository(get()) }
}
```

## Type Parameter Annotation

We considered exposing the feature through type parameter annotation,
for example, `@ExplicitTypeArgumentOnly`:

```kotlin
inline fun <@ExplicitTypeArgumentOnly reified T> List<*>.castAll(): List<T>
```

We decided against it, because a new `explicit` soft-keyword is more concise
and due to its narrow applicability is unlikely to interfere with any features added in the future.

# Migration

As mentioned above, `@kotlin.internal.NoInfer` is currently used in the standard library
to express the semantics of proposed `explicit` type parameters.
Such usages of `@NoInfer` can be migrated to `explicit` type parameters:

```kotlin
// old
inline fun <reified T> List<*>.castAll(): List<@kotlin.internal.NoInfer T>
// new
inline fun <explicit reified T> List<*>.castAll(): List<T>

// old
context(context: @kotlin.internal.NoInfer A)
inline fun <A> contextOf(): @kotlin.internal.NoInfer A
// new
context(context: A)
inline fun <explicit A> contextOf(): A
```

However, the Kotlin compiler must remain compatible with standard library 
versions one release backward and one release forward. 
Therefore, to ensure a smooth migration, there should be at least one release during which:
- The standard library uses both `explicit` and `@NoInfer`.
- The compiler supports both `explicit` and `@NoInfer`.

Once support for `@NoInfer` is dropped, its other usages would have to either
drop it, which would be a red-to-green change, or use other features.
For example, this code in IntelliJ Platform can use `@kotlin.internal.Exact` instead:

```kotlin
package org.jetbrains.tools.model.updater

// currently
internal fun <T> KProperty<T>.modify(
  newValue: @kotlin.internal.NoInfer T
): PreferenceModification<T>

// after @NoInfer support is dropped
internal fun <T> KProperty<@kotlin.internal.Exact T>.modify(
  newValue: T
): PreferenceModification<T>
```
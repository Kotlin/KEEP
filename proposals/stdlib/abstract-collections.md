# Read-only and mutable abstract collections

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Under consideration
* **Prototype**: Implemented
* **Discussion**: [KEEP-53](https://github.com/Kotlin/KEEP/issues/53)


## Summary

Provide two distinct hierarchies of abstract collections: one for implementing read-only/immutable collections,
and other for implementing mutable collections.

## Description

Currently JDK provides a set of abstract classes to inherit collection implementations from: 
`AbstractCollection`, `AbstractList`, `AbstractSet`, `AbstractMap` located in `java.util` package.

These collections are suitable for implementing both read-only and mutable collections, 
but that is achieved with the help of the following compromises:

  - for convenience of implementing read-only collections they have their mutation methods not abstract, 
but rather implemented as throwing an exception. This poses a risk of forgetting to override a mutation method
when implementing a mutable collection, which would result in getting an exception in runtime.
  - abstract collection provide facility for concurrent modification tracking, but it's barely demanded by
 *read-only* collections.

Also a collection inherited from JDK abstract collection suffers from the following drawbacks:

  - it is always a subtype of `MutableCollection` in Kotlin, so it cannot be true read-only such that 
`coll is MutableCollection<*> == false`
  - it has platform types (i.e. `String!`) appearing here and there: as parameter and return types.


This proposal is to introduce the following classes in `kotlin.collections` package.

Abstract read-only collections:

  - `AbstractCollection<out E> : Collection<E>`
  - `AbstractList<out E> : List<E>`
  - `AbstractSet<out E> : Set<E>`
  - `AbstractMap<K, out V> : Map<K, V>`
 
Abstract mutable collections:

  - `AbstractMutableCollection<E> : MutableCollection<E>`
  - `AbstractMutableList<E> : MutableList<E>`
  - `AbstractMutableSet<E> : MutableSet<E>`
  - `AbstractMutableMap<K, out V> : MutableMap<K, V>`
 
The mutable abstract collections inherit all their implementation from JDK abstract collections, 
but having abstract overrides for those mutation methods, that throw `UnsupportedOperationException`, 
thus requiring an inheritor to override and implement them deliberately.

In JS standard library all these classes have their own implementations.

## Similar API review

  * `java.util.Abstract*`-classes in JDK

## Use cases

  * Implementing read-only collections, such as `PrimitiveArray.asList()`, `List.asReversed()`, `groups` and `groupValues` properties in regex `MatchResult`.
  * Implementing [immutable collections](https://github.com/Kotlin/kotlinx.collections.immutable/blob/master/proposal.md). 
  * Implementing mutable collections, such as `MutableList.asReversed()`.

## Alternatives

  * Just use JDK abstract collections
    * con: listed in the description.
    * con: doesn't unify with JS stdlib collection classes.
    * pro: less classes/methods in runtime.
  * Just implement collection interfaces and do not use base abstract classes.
    * con: have to write annoying boilerplate
    * less methods in runtime, but at a price of more methods in implementing classes.


## Dependencies

What are the dependencies of the proposed API:

  * a subset of Kotlin Standard Library available on all supported platforms.
  * on JVM: JDK abstract collections, available in JDK 1.6+

## Placement

  * Standard Library
  * `kotlin.collections` package

## Reference implementation

Implementations can be found at:
 
  - for JVM: https://github.com/JetBrains/kotlin/tree/master/libraries/stdlib/src/kotlin/collections 
  - for JS: https://github.com/JetBrains/kotlin/tree/master/js/js.libraries/src/core/collections

## Questions

  * Will the proposed change be source compatible (since classes with the same name as in JDK are introduced)?
    * JDK abstract collections are located in `java.util` package, thus it's mandatory to import them explicitly.
      These explicit imports take precedence over imported by default `kotlin.collections` package, 
      thus previously used JDK abstract collection will still refer to the same `java.util` classes.

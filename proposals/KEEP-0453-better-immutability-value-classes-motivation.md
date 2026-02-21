# Better Immutability in Kotlin aka "Value Classes 2.0": Motivation and Design Space

* **Type**: Design space exploration
* **Author**: Marat Akhin
* **Contributors**: Roman Elizarov, Nikita Bobko, Komi Golova, Pavel Kunyavskiy, Alejandro Serrano Mena, Evgeniy Moiseenko, Alexander Udalov, Wout Werkman, Mikhail Zarechenskiy, Evgeniy Zhelenskiy, Filipp Zhinkin
* **Status**: Public discussion
* **Discussion**: [GitHub](https://github.com/Kotlin/KEEP/discussions/472)
* **Related YouTrack issue**: [KT-77734](https://youtrack.jetbrains.com/issue/KT-77734)

## Abstract

This proposal describes the motivation and the design space for the introduction of "better value classes" for Kotlin: an evolution of the current inline value classes and an alternative to data classes, aimed at providing a sound, first-class immutability support rather than primarily chasing low-level performance optimizations.
It is based on three main ideas: lifting the single-property restriction of current inline value classes, adding ergonomic mechanisms for updating immutable data, and supporting both shallow and deep immutability.
These capabilities are designed to make value-based, identity-free programming a first-class citizen in Kotlin.

## Table of Contents

- [Introduction](#introduction)
  - [Value Classes](#value-classes)
  - [Goal: Better Value Classes](#goal-better-value-classes)
- [Motivation](#motivation)
  - [Immutability, Performance and Safety](#immutability-performance-and-safety)
  - [Immutability and Concurrency](#immutability-and-concurrency)
- [Design Space](#design-space)
  - [Value Classes with Multiple Primary Properties](#value-classes-with-multiple-primary-properties)
  - [Ergonomic Updates of Immutable Data](#ergonomic-updates-of-immutable-data)
    - [Lenses](#lenses)
    - [Withers](#withers)
    - [Mutable Value Semantics and Copy Vars](#mutable-value-semantics-and-copy-vars)
    - [Which Option Is Best for Kotlin?](#which-option-is-best-for-kotlin)
  - [Deep Immutability](#deep-immutability)
    - [Why Deep Immutability Matters](#why-deep-immutability-matters)
- [Summary](#summary)
- [Call to Action](#call-to-action)
- [References](#references)

## Introduction

Immutability is considered to be one of the "Holy Grails" of programming.
Most development guidelines across diverse programming languages still recommend using immutable data when possible, as it allows for fewer bugs, easier reasoning about the program behavior, better concurrency and data sharing.
As one of the examples, the "Effective Java" chapter 17 goes to great lengths to explain why you should prefer immutability, and Kotlin successfully implements many (but not all) of the recommendations.
Unfortunately, bringing immutability to one’s programming language is not as simple as "just make things immutable", which is why there is still space for Better Immutability in Kotlin.

### Value Classes

One of the central concepts in immutability is **immutable data**.

Kotlin, being originally rooted in the JVM platform, is reference-based through and through.
All user-defined types have identity, and this identity is observable through references.
While this is important for mutable data, as mutable data requires a stable container (its identity), for immutable data the concept of identity is almost alien.

Consider such immutable types as `String` or `Instant`: for them, the important thing is what values they represent (which sequence of characters or moment in time), no matter what the reference is.
In other words: the identity of the immutable data is irrelevant, and you should not in any shape or form rely on it.
If you do, it is a bad practice, opening your code to potential bugs.

Once you begin thinking about programming without identity, you can't help but encounter **value types** and *value semantics*.
A value type is a type whose instances *do not carry a stable object identity*: they are conceptually passed and compared by *value* rather than by reference.
For example, Kotlin primitive types (`Int`, `Long`, `Double`, etc.) are essentially value types: two `Int` instances with the same numerical value are indistinguishable, and the language discourages using reference equality (`===`) on them.

If we are talking about user-defined types, Kotlin supports restricted value types in the form of [*inline value classes*](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md).
They were created to solve two adjacent problems at the same time:

* Provide the users the ability to create *zero-cost wrappers* around other types, which implement operations and add type safety without additional allocations, by inlining the underlying (wrapped) value when possible.
* Add value types that do not have identity and follow value semantics.

However, they come with severe limitations.

First, they are restricted to a single property.
In practice, this means they are great at representing single-value-based types, but cannot easily model a composite value with multiple underlying values.

Second, if you do need to update a value object, you have no other way but to call the constructor with the updated property.
When you are restricted to a single property, this is not a significant problem, but for value classes with multiple properties this is not convenient.

Third, they are only shallow immutable.
While an object of a value class (aka value object) itself cannot be mutated after creation, as its single property is read-only, that single property might still refer to mutable data (e.g., `MutableList<...>`).
This means deep immutability is not guaranteed by the language.

Fourth, arrays and varargs of inline value classes are not well-supported.
If you want an array of inline value class instances with flattened (unboxed) storage, you need to write your own custom `Array` wrapper which handles the flattening manually.
Additionally, `vararg` parameters of inline value class types are currently not allowed, because the compilation strategy for them is not clear: should they be compiled as boxed or unboxed arrays?
Both these cases are made more complicated by the fact that `Array<Value>` (array of boxed instances) and `CustomValueArray` (custom array wrapper of unboxed instances) are not interchangeable aka are not subtypes of one another.
This limits the usability of inline value classes in APIs that rely on arrays and/or varargs.

### Goal: Better Value Classes

This proposal describes **"better value classes"** for Kotlin: an enhancement of the value type concept to address their current shortcomings.
The goal is to allow developers to create rich, immutable value types that:

* Allow the user to model arbitrary data with **multiple properties** instead of just one.
* Offer **ergonomic updates**, meaning one can very concisely create modified copies of immutable values, without the boilerplate of manually calling `copy()` or constructing new instances.
* Support **deep immutability** which is checked and enforced by the compiler, to give the user the ability to ensure their entire object graph is immutable.

> Important: the goal of this proposal is to provide a sound model of immutability in the language and not to chase raw performance through aggressive inlining or other means.
> Such optimization features, e.g., offered by Project Valhalla on the JVM, are nice-to-haves, but they are not the foundation of the design.
> In other words, developers should adopt better value classes for the clarity, safety, and correctness they provide via better immutability, and not for speculative performance benefits.

## Motivation

Immutability is one of the cornerstones of robust software design.
An immutable object, once constructed, never changes, which eliminates an entire class of bugs related to unintended side effects.
This makes code easier to reason about, simplifies debugging and improves composability.

Kotlin already supports immutability in several ways: `val` declarations create read-only references, data classes provide `copy` functions for creating modified instances, the standard library distinguishes between read-only collection interfaces (such as `List`, `Set`, `Map`) and their mutable counterparts.
These features guide developers toward writing safer code.

However, Kotlin’s current support for immutability remains partial: in many cases the compiler helps you to control immutability of your data, but in other situations you need to control it yourself.
For example, read-only interfaces provide only shallow guarantees, as an object behind a `List` reference might still be changed via a `MutableList` reference to it.
Data classes are not enforced to be shallow or deeply immutable, and `copy` functions quickly become verbose when deeply nested structures need to be updated.
Inline value classes are shallow immutable and enforce immutability of their single primary property but cannot express multi-property composite data.

### Immutability, Performance and Safety

Improving support for immutability is important not only because it is a generally useful feature for programming, but also because in some application domains it lies at the core.
One example of such domains is UI programming, where immutability is critical.

Reactive UI frameworks like Jetpack Compose or React rely on the principle that "UI is a pure function of its state".
To efficiently decide what needs to be re-rendered, they either compare the new state with the previous one (if state updates are implicit), or track changes to the UI state (if state updates are explicit).
If they can be sure the UI state is immutable, then all state updates are by definition explicit, and a simple structural comparison (or even reference equality in some cases) is enough to know whether anything changed.
This reduces the cost of rendering and avoids subtle bugs where the UI "misses" an update because parts of the state were mutated implicitly without the framework noticing.

> Note: Compose uses the term ["stability"](https://developer.android.com/develop/ui/compose/performance/stability) to describe (immutable) UI state, changes to which it is able to track.

This is conceptually very similar to *caching*: if the function inputs haven’t changed, the previously computed result can be reused.
But caching only works correctly when the inputs are immutable; otherwise, input data could have changed, invalidating the stored cached outputs.
Additionally, these cached outputs should also be immutable, or it is possible to invalidate the computed result by accidentally mutating it.

```kotlin
// A mutable data type
data class Book(var title: String, var isbn: ISBN)

class LibraryRepository {
  val books: ImmutableList<Book>
    get() = apiCache.get(Path.BOOKS)
}

fun egoisticClient() {
   val books = libraryRepo.books
  
   // ...
  
   // "Fix" the book capitalization
   books.forEach {
       it.title = capitalize(it.title)
   }
   // Cached values are now invalid
}
```

As caching is widely used, for example, in server-side development to avoid re-doing heavy computations several times, immutability is a key requirement there also.

This means that immutability of (UI) inputs isn’t just about safety, it’s a **performance enabler**.
By relying on immutability, frameworks and applications can implement efficient computation avoidance algorithms while preserving correctness.
Without immutability checked by the language, these frameworks have to either trust the user to not make a mistake, or to implement custom analyses (e.g., stability analysis in Jetpack Compose, `@Immutable` analysis in the Checker framework) to approximate this guarantee, with mixed results.

A first-class immutability support in Kotlin would directly benefit these application domains and reduce reliance on convention and tooling.

### Immutability and Concurrency

Another application domain where immutability plays a central role is concurrency.
At the moment, most applications are concurrent or even parallel; this means that multiple threads or coroutines often need to share the same data structures.
If those structures are mutable, additional effort is required to avoid data races, in the form of synchronization primitives or atomics.
Also, if data sharing is not checked at compile-time, one could introduce a data race by accidentally sharing an unprotected data structure.

Immutable data, in contrast, can be freely shared across concurrent executions without additional effort.
Since they cannot change after creation, it is impossible to observe them in a "torn" or inconsistent state, and data races are avoided.

A lot of more functional-oriented languages use immutable data for their concurrency.
For example, languages adopting message passing for data sharing usually use immutable messages, thus avoiding by construction the possibility of a traditional data race.
Other languages which support more direct style via shared memory are also beginning to move in this direction by embracing and tracking immutability w.r.t. concurrency.

For example, Swift 6.0 introduced [*Data Race Safety*](https://www.swift.org/migration/documentation/swift-6-concurrency-migration-guide/dataracesafety/) by default: the compiler enforces that, if data is shared, then either it is immutable or only one concurrently executing actor can access it at a time.
This effectively guarantees at compile time that well-formed Swift programs are data-race-free.

The very flexible concurrency model we have in Kotlin, supporting both threads and coroutines, and also structured concurrency, would likewise benefit from compiler-enforced immutability.
If immutable types and immutable collections become first-class in Kotlin, and the compiler begins checking potentially concurrent data sharing, developers can write concurrent code with more confidence.

## Design Space

In the current document, we would like to discuss at a high level the design space, within which we are working.
Specifically, let's talk about the three main points we want to tackle to achieve our goal of getting better value classes in Kotlin.

### Value Classes with Multiple Primary Properties

The one-property requirement on current Kotlin value classes is a significant restriction.
The reason for such a restriction for *inline* value classes is simple: they were designed not only as shallow immutable value classes, but also as lightweight wrappers around other types.
Unfortunately, supporting performant wrappers around multiple values is significantly more difficult, as you need, for example, to do scalar replacement of multiple values.
On the JVM you would need to do this purely in user space, without the virtual machine support, which is practically infeasible.

At the same time, most everyday data cannot be fit into a single property.
Even for something as simple as a data type for a complex number, we would like to have a lightweight `Complex` number type, but it must contain two underlying values (e.g., `Double` values).
Right now, developers are forced to use a regular class or a data class, which means `Complex` objects carry an identity and are heap-allocated, or to manually pack two values into an array or similar hack.

We believe that supporting better immutability is more important than having definite inlining optimizations, because many more users will (in)directly benefit from it.
If that's the case, we can lift the one-property restriction from value classes and allow them to have one or more read-only `val` primary properties.

```kotlin
value class Complex(val re: Double, val im: Double)
```

For the moment, they will be compiled as regular classes, without any optimizations.
In the future, when *project Valhalla* comes to fruition, Kotlin value class `Complex` could be compiled as JVM value classes, to enable advanced JIT optimizations unlocked by Valhalla.
To allow for this transition, we would need to additionally restrict what is possible in Kotlin value classes, so that they are aligned with the planned restrictions of project Valhalla.

It is important to note that the [current version](https://cr.openjdk.org/~dlsmith/jep401/jep401-20250926/specs/value-objects-jls.html) of the project Valhalla's design for value classes is very reasonable and does not have any fundamental restrictions which are a blocker for introducing value classes in Kotlin.
This means that the alignment of the two designs (Kotlin and project Valhalla) should be easy to achieve.

> Side-note: for other target platforms (JS, Native, Wasm), we will be working separately to determine when and how we can introduce optimizations for value classes.

### Ergonomic Updates of Immutable Data

Embracing immutability is great, but it does not come completely free.
Immutability --- not surprising at all --- makes changing the data more difficult.
And the unfortunate reality is that programs usually need to change their data.

There are, of course, ways to do this in programming languages.
Kotlin `data class`es provide a `copy()` function, which is helpful for working with immutable data.
However, when data structures are deeply nested, updating even a single deeply nested property becomes verbose.
You have to construct a *ladder* of nested `copy()` calls, one for each level.

```kotlin
val updatedUser = user.copy(
    address = user.address.copy(
        zipCode = user.address.zipCode.copy(
            code = "1079MZ"
        )
    )
)
```

This "copy ladder" quickly becomes hard to read, introduces visual noise that hides the logical intent ("update the zip code"), and is a large source of boilerplate in applications.

The easiest way to solve this would be to switch to mutability and do updates in-place.
If you want to keep immutability, you have to use some alternatives.
The pain of deeply nested updates of immutable data has been around for decades, so it is not surprising that different programming communities have developed their own answers to it.

#### Lenses

One of the more functional solutions is **lenses**, an abstraction popularized in Haskell and also available in other languages (e.g., OCaml and Scala).
A lens focuses on a piece of a data structure: it knows both how to extract that piece and how to rebuild the whole structure when that piece changes.
In practice this means you can write something like the following.

```kotlin
// An example with Arrow Optics

val zipCodeLens = User.address.zipCode.code
val updatedUser = zipCodeLens.modify(user) { "1079MZ" }
```

Besides solving the immediate problem of deeply nested updates, another advantage of lenses is that they are composable, so you can zoom deeper into structures by reusing smaller lenses, which makes them powerful once you invest in learning their mental model.
On the other hand, syntax usually feels more like a library trick than a native language feature, and lenses are somewhat external to the data, which makes them relatively harder to adopt.

#### Withers

Object-oriented ecosystems moved in a slightly different direction and came up with **withers**, which are essentially single-property versions of `copy()`.
A wither method follows the pattern `withX(value)` and produces a new instance with only that property changed.
Lombok in Java can generate them automatically, and Java records are planned to support a similar mechanism via [JEP 468](https://openjdk.org/jeps/468).

```kotlin
val updatedUser = user.withAddress(
    user.address.withZipCode(
        user.address.zipCode.withCode("1079MZ")
    )
)

// or

val updatedUser = user.with {
    address = address.with {
        zipCode = zipCode.with {
            code = "1079MZ"
        }
    }
}
```

Withers tend to be easier to understand for developers coming from an object-oriented mindset, and they work well with Java interop.
They also work better w.r.t. binary compatibility compared to `copy` functions; the addition of a new property does not break withers, but does create problems for the `copy` function.

The downside, however, is that deeply nested updates are still verbose, meaning that we still get the "wither ladder".

#### Mutable Value Semantics and Copy Vars

A third option is to look for inspiration at a more recent idea, called [**mutable value semantics**](https://research.google/pubs/mutable-value-semantics/) (MVS).
It is an extension to regular *value semantics*: a discipline where data is defined only by its value, and never by its identity.
Another definition is the one popularized by Rust: an object respects value semantics if it's *"mutable xor shared"*, meaning it's either shared, but then no one can change it, or it's uniquely owned by someone who can change it.

Mutable value semantics allows mutating *all* values by *copying* a value when it needs to be updated.
If we know at compile-time that a value is uniquely mutable, we could elide the copy by mutating in-place, to avoid additional memory allocations.

> Note: an alternative view of MVS is "a reference to a value is always unique, and this is preserved by *copying* the value when it is potentially shared".

> Note: the way structures work in Swift can be considered a form of MVS, achieved by doing "copy-on-write".

The core property of MVS is: when part of a value is updated, the whole value is copied with the new data.
If we were to adapt this property to Kotlin, it would read as: when you update a property of a value object, you copy the whole value object with the new value of this property.

```kotlin
// Kotlin + MVS

a.b = c
// actually becomes
a = <update a with the value of property b equal to c>

// for example
a = A(..., b = c, ...)
// or
a = a.withB(c)
// or
a = a.copy(b = c)
// or
a = a.mutated { b = c }
```

Implementing this means we are introducing a new kind of property, in addition to already existing read-only `val` properties and mutable `var` properties, a *copying property* or `copy var`.

> Note: this design was first proposed by Roman Elizarov et al. in their [notes on value classes](https://github.com/Kotlin/KEEP/blob/main/notes/0001-value-classes.md).

When you are assigning to a `copy var`, under the hood it is expanded into a form of *copy-and-update*.
This allows us to surface MVS even when it is implemented on top of purely reference-based runtime (such as the JVM).

This approach is powerful because it handles two seemingly contradictory needs at the same time.
On the one hand, developers get a familiar, compact way to write "mutable looking" code when evolving immutable state, instead of constructing verbose copy ladders.
On the other hand, the language guarantees that every update keeps the data structure immutable, so reasoning about correctness and concurrency remains straightforward.
We are not introducing true mutable structures as other languages do, but rather emulating them through syntactic sugar and compiler transformations.

The trade-off is that this model introduces a third category beyond `val` and `var`.
Developers already know that `val` means "never reassigned" and `var` means "reassigned in-place".
Adding `copy var` means introducing additional cognitive load at their use-sites, as some assignments *look* like ordinary mutation but, due to mutable value semantics, actually create new values under the hood.

```kotlin
val res = ...

var tmp = res
tmp.state = newState // What does this actually do?
```

#### Which Option Is Best for Kotlin?

At this moment, we believe that `copy var`s are the best option for introducing ergonomic updates of immutable data in Kotlin.
Many of the most successful features of Kotlin, such as `suspend` functions or smart casts, are --- at their core --- about removing verbosity and boilerplate, when it is clear what the developer intent is.
And the introduction of mutable value semantics falls into the same kind of language changes.

We are aware of cases when `copy var`s might be ambiguous or hard to understand, and have potential solutions for them.
Also, in some cases alternative approaches might be strictly better than `copy var`s.
We will be taking a look at the feedback and considering all options before finalizing the design.

### Deep Immutability

The third and final part which is currently missing from Kotlin is support for deep immutability.
First, let's briefly describe what it means.

When we say something is *shallow immutable*, we mean its immediate properties cannot be reassigned once the object is constructed, but the objects these properties point to may still be mutable and can still be changed.
For example, the following type is shallow immutable.

```kotlin
data class ArrayWrapper<T>(val arr: Array<T>)
```

The `arr` property cannot be changed to reference another array, yet the content of the array can be freely updated.
Kotlin already supports shallow immutability through `val` properties, but it enforces it only for the current (restricted) inline value classes.

In contrast, *deep immutability* extends this guarantee transitively across the entire object graph.
A deeply immutable object cannot be changed "all the way down"; in other words, any part of it is itself immutable.
Deep immutability is currently *not* supported in Kotlin in any shape or form besides manual user control.

#### Why Deep Immutability Matters

Deep immutability is important because it is actually the kind of immutability people usually talk about when they talk about immutability in general.
All of the advantages of immutability we discussed before are actually about *deep immutability*.
For example, in concurrency, deeply immutable types can be freely shared across threads or coroutines, since no concurrent updates can occur for any part of the data.

Similarly, the immutability needed for reliable computation avoidance (such as with reactive UI frameworks or caching) is deep immutability.
If the data is only shallow immutable, parts of it could be changed, invalidating the attempts to avoid recomputations.

In short, shallow immutability helps avoid a number of mistakes, but deep immutability guarantees consistency.
Without it, developers must enforce strict discipline manually, which is prone to errors.
Language-enforced deep immutability replaces convention with certainty, allowing the compiler, frameworks and libraries to build stronger optimizations and correctness guarantees.

## Summary

To summarize the motivation:

* **We need multi-field value classes** to conveniently model composite values across all platforms, instead of (ab)using single-field wrappers or falling back to identity-based classes.
* **We seek ergonomic ways to update immutable data**, making code with immutable objects as natural and concise as code with mutability.
* **We want deep immutability** of objects to be a controllable, enforceable property, improving safety in many contexts such as concurrent and UI applications.

We will be working on bringing improvements to these areas incrementally, as each one builds on top of and is an extension of previous ones.
In the beginning, we will primarily focus on the first two aspects of the overall immutability story: supporting **multi-field value classes** and providing **ergonomic updates** of immutable data.
These are the most immediate building blocks for making immutability more natural in Kotlin and directly address well-known pain points such as the one-property restriction and the "copy ladder" problem.

While **deep immutability** is just as important in the long run, it is a "second-order" **extension** of this work.
Guaranteeing deep immutability requires not only additions to the language (such as deeply immutable value classes), but also changes to already existing features, to form a bridge between the new deeply immutable (and mostly value-based) world and the old mutable identity-based reference world.
That means dealing with standard collections, arrays, and general interoperability with existing reference types in the type system.
Because of that, we will cover deep immutability at a later time, building on the foundations provided.

## Call to Action

If you disagree with our general directions and motivation for what we want to achieve from immutability, we would very much like to hear your thoughts.
Some of the questions we have for you are:

* Is getting rid of object identity something important to you? Would you prefer to always keep the identity instead?
* Do you value immutability with value semantics (pun intended) or should we stick to immutability within reference semantics?
* How important is performance to you w.r.t. immutability?
* Which style of ergonomic updates (if any) would you prefer and enjoy using the most? Or is any option something you fundamentally disagree with?
* Anything and everything else you care about.

If you have the time to share, please go to the [discussion](https://github.com/Kotlin/KEEP/discussions/472) and leave your feedback.

## References

* [Design Notes on Kotlin Value Classes](https://github.com/Kotlin/KEEP/blob/main/notes/0001-value-classes.md)
* [Multi-Field Value Classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0340-multi-field-value-classes.md)
* [JEP 401: Value Classes and Objects (Preview)](https://openjdk.org/jeps/401)
* [Swift 6.0 Data Race Safety](https://www.swift.org/migration/documentation/swift-6-concurrency-migration-guide/dataracesafety/)
* [Arrow Optics](https://arrow-kt.io/learn/immutable-data/intro/)
* [JEP 468: Derived Record Creation (Preview)](https://openjdk.org/jeps/468)
* [Mutable Value Semantics](https://research.google/pubs/mutable-value-semantics/)
* [Jetpack Compose Stability](https://developer.android.com/develop/ui/compose/performance/stability)

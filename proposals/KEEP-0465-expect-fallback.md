# Expect with fallback

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: 
* **Discussion**: 
* **Status**: In progress
* **Related YouTrack issues**: [KT-20427](https://youtrack.jetbrains.com/issue/KT-20427/Allow-expect-declarations-with-a-default-implementation)

## Abstract

This proposal addresses the most common case of lack of reusability in
Kotlin Multiplatform projects, by providing a way for expect declarations
to define **fallback** implementations to be used whenever a platform does
not actualize such expect implementation.

### TL;DR

* Expect declarations may define a **fallback** implementation.
* The fallback implementation implementation is used when there's no
  corresponding actual declaration in a platform for an expect.
* Fallback implementation must define the declaration completely
  ("all-or-nothing" rule); leaving some parts unspecified is not allowed.

## Table of contents

* [Abstract](#abstract)
    * [TL;DR](#tldr)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
    * [Design goals](#design-goals)
    * [Competing approaches](#competing-approaches)
    * [Design decisions](#design-decisions)
* [Proposal](#proposal)
    * [Potential extensions](#potential-extensions)

## Motivation

The [expect/actual mechanism](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html#rules-for-expected-and-actual-declarations)
is a core piece of the Multiplatform capabilities in Kotlin. In short, this
mechanism allows the "skeleton" of a callable or classifier to be defined in a 
common source set,

```kotlin
// commonMain/foo.kt
expect class Foo {
  fun bar()
}
```

with the actual implementation differing for each platform,

```kotlin
// iosMain/foo.kt
actual class Foo {
  fun bar() { println("hello") }
}
```

The main benefit of this mechanism is that code that only depends on the
"skeleton" can be written once and for all for every platform, isolating the
platform-dependent bits.

As larger libraries have been developed using the expect/actual mechanism, some
shortcomings have become apparent. A common scenario is having a function or
class which could be implemented in pure Kotlin code, but for which some
platforms may already have an implementation with benefits in terms of
interoperability or performance.

### Design goals

**Aligned with the current Kotlin Multiplatform approach.**
We don't want a full re-design of how Kotlin Multiplatform works now.
This includes integrating on how the compiler currently does things.
The goals here are to minimize (subtle) breakage, and ensure that the Kotlin
Team can devote enough resources for this implementation.

**Simple de-duplication.** As far as possible, we want to design to support
getting rid of duplicate code among platforms in a (somehow) simple way.

**Compatibility guarantees.** We aim for a design that is completely backwards
compatible at the source level. That is, no change should be required in either
the expect or the actual classes that already work. 

> [!NOTE]
> We do not guarantee that any code which does not compile now would start
> working with this new proposal. In particular, our compatibility guarantees
> apply only to expect declarations that have been correctly actualized in
> all platforms.

### Competing approaches

Some projects already use other approaches to this problem.
We need to ensure that our design is more convenient than those other approaches.

The first one is creating a `nonP` (usually `nonJvm`) source set that includes
an actualization of the class for every platform except `P`, and make all source
sets for those platforms depend on `nonP`. There are quite some examples
of this pattern in open-source projects, including 
[Kotest](https://github.com/kotest/kotest/tree/master/kotest-property/src),
[kotlin-poet](https://github.com/square/kotlinpoet/tree/main/kotlinpoet/src),
[Kamel](https://github.com/Kamel-Media/Kamel/tree/main/kamel-image/src),
Ktor ([`ZstdEncoder`](https://github.com/search?q=repo%3Aktorio%2Fktor+%22class+ZstdEncoder%22&type=code),
[`TLSConfig`](https://github.com/search?q=repo%3Aktorio%2Fktor+%22class+TLSConfig%22&type=code)).

The second one is defining the "default" implementation in a common source set,
usually with a `commonX` or `defaultX` name, and simply refer to that in the
actualization. One project where this pattern is used is Compose:
[`createPlatformRippleNode`](https://github.com/JetBrains/compose-multiplatform-core/blob/bf16b821cc012c8770ac33c54241b42157c19253/compose/material3/material3/src/skikoMain/kotlin/androidx/compose/material3/internal/ripple/Ripple.skiko.kt#L24-L32),
[`PlatformVelocityTracker`](https://github.com/JetBrains/compose-multiplatform-core/blob/bf16b821cc012c8770ac33c54241b42157c19253/compose/ui/ui/src/macosMain/kotlin/androidx/compose/ui/input/pointer/util/PlatformVelocityTracker.macos.kt#L19),
[`Modifier.textFieldPointer`](https://github.com/JetBrains/compose-multiplatform-core/blob/08d534aa4db79030b5acd915583ffb87e038f0d9/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/TextFieldPointerModifier.common.kt#L39),
[`TextFieldSelectionState.detectTextFieldTapGestures`](https://github.com/JetBrains/compose-multiplatform-core/blob/ca0996fa6bec345236eebe924f43ceebf19202f0/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/internal/selection/TextFieldSelectionState.kt#L1785),
[`TextFieldCoreModifierNode.drawSelectionHighlight`](https://github.com/JetBrains/compose-multiplatform-core/blob/ca0996fa6bec345236eebe924f43ceebf19202f0/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/internal/TextFieldCoreModifier.kt#L680),
[`Modifier.textFieldDraw`](https://github.com/JetBrains/compose-multiplatform-core/blob/ca0996fa6bec345236eebe924f43ceebf19202f0/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/CoreTextField.kt#L1155).

Some projects like Okio use a combination. For example,
[`Buffer`](https://github.com/search?q=repo%3Asquare%2Fokio+%22class+Buffer+%22&type=code)
uses a `nonJvm` source set, and
[`ByteString`](https://github.com/search?q=repo%3Asquare%2Fokio+%22class+ByteString%22&type=code)
defines some `commonX` functions to be used in different actualizations.


### Design decisions

**No actual declaration needed to use fallback.** In order for this feature to
provide an advantage against other potential ways to solve this problem, it has
to minimize the amount of code. And the minimal amount is none! In other words,
we want a design in which there's no need to opt into the fallback
implementation for a platform – otherwise you still need to define that source
set in your build file.

**Fallback body in expect declaration.** The fallback or default implementation
for an expect declaration could be given as a separate declaration, or directly
in the expect declaration itself. We have taken the latter road, for a couple of
reasons:
1. It aligns with similar constructions in the language. For example, a default
   implementation for an interface method is given directly on the method itself,
   not separately.
2. It avoids complications related to where the expect and fallback declarations
   may or may not live (same source set? same file?).

**No modifier.** In most cases the fact that there's a body in the expect
declaration is enough to infer that such declaration defines a fallback.
However, abstract methods do not define a body, so this means some code is
ambiguous. For example, does the following code:

```kotlin
expect interface Bar {
  fun bar(): Int
}
```

... define a fallback implementation for `Bar`, to be used whenever a platform
doesn't actualize it further? Or does define an interface to be actualized in
every platform? Note that the syntax is only ambiguous for classifiers with only
abstract methods, though, a rather uncommon case for expect/actual

On the other hand, having to write a modifier interrupts the "happy path" of 
adding fallback implementations, since you need to remember this modifier.
It also makes diagnostics a bit harder, since whether there's a fallback is 
"inferred", rather than explicit. However, we think that the trade off should go
in the direction of no modifier.

Note that this design decision is **backward compatible** with all existing code,
since in those cases we have an actualization of the interface for every
platform. Only new code may potentially end up in this ambiguity.

**All-or-nothing for classifiers.** Although at first glance it seems possible
to define partial defaults (that is, giving only the body of some of the members
inside the class), there are two main blockers design-wise.

First, the combination of the expect syntax – in which expect members are not
marked in any way – and other implicit syntax in the language – like
constructors – makes it very hard to understand what the developer means.

```kotlin
expect class Thing {
  // do we expect a no-argument constructor in the actual class?
  // or are we defining a no-argument constructor to be used by default?
}
```

Second, when inheritance meets actualization it may be difficult to understand
which implementation to use when both define a potential body.

In our preliminary investigation the case of partial actualization is quite
uncommon, so this seems like a good trade off. On top of that, the current
design does not block a laxer design in the future. In conclusion, the current
design requires either all members in a classifier to have a fallback, or none.

## Proposal

**Declaration with body**. We say that a callable declaration (function,
property, property accessor) defines or has a body whenever:
* There's an implementation provided in the source code,
* The callable inherits an implementation in a non-ambiguous way,
* It is marked as `abstract` or `external`.

**Declaration with fallback.** We say that an expect declaration defines a
fallback whenever:
* In the case of functions, it defines a body.
* In the case of properties, it defines a body for one of the accessors,
  or defines a body for the initializer.
* In the case of classifiers, at least one of the members defines a fallback.

```kotlin
expect fun quux(x: String): String = x.drop(1)  // defines a fallback

expect val OneLuckyNumber: Int         // actualization required
expect val OtherLuckyNumber: Int = 7   // defines a fallback

expect abstract class Foo {
  abstract fun b(): Int       // abstract member => defines a fallback
  fun c(): Int                // error: concrete member without body (see later)
}
```

**All-or-nothing.** If an expect declaration defines a fallback, then its body
follows the usual Kotlin rules for that declaration. In particular:
* In the case of properties, they must either define a body for accessors,
  or define a body for the initializer;
* In the case of classifiers, all members must define a body
  (using the definition given above).

```kotlin
expect var ImportantThing: Int
  get() = 3
  set(value)             // error: concrete accessor without body

expect abstract class Foo {
  fun a(): Int { ... }        // concrete — fine
  abstract fun b(): Int       // abstract — explicit, no ambiguity
  fun c(): Int                // error: concrete member without body
                              // (exactly the error you'd get in any normal class)
}
```

**Actualization of declarations with fallback.** Any actualization takes
priority over the fallback implementation, regardless of the way it was done
(for example, for classifiers we have actual class, actual typealias, 
and actualization by Java class).

**Shorthand syntax.** It is allowed to use combined primary constructor and 
primary properties syntax in an expect class. Doing so implicitly defines a body
for the properties, which in turns means that a fallback is defined for such a 
classifier.

```kotlin
// this defines a fallback
expect class Point(val x: Float, val y: Float)

// this defines no fallback
expect class Point constructor(x: Float, y: Float) {
  val x: Float
  val y: Float
}
```

On the other hand, using the `data` modifier is not allowed. The syntax makes it
unclear whether the automatically-generated methods should be taken as expect
functions (so every actualization should define copy and components), or this
only matters for the fallback.

**Interactions with expect-related annotations.**
* It is not allowed to define a fallback for an expect class marked as
  [`@OptionalExpectation`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-optional-expectation/).
* It is not allowed to use 
  [`@ExpectRefinement`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.experimental/-expect-refinement/)
  alongside an expect class with a fallback.

### Potential extensions

**Additions during actualization.** A somehow common use case is adding
something to the fallback implementation of a class. For example, adding new
constructors for better interoperability with a platform type.

We say that a classifier defines additions whenever:
* The corresponding expect implementation has a fallback,
* There are no members marked with actual in the body of the actual classifier.
* None of the members defined match a member in the expect declaration.

In that case, the compiler should consider the actual implementation to be the
combination of the fallback from the expect declaration, and all the additional 
declarations.

```kotlin
actual class Foo {
  fun otherMember() { ... }       // addition

  companion {
    val CONSTANT_NUMBER: Int = 3  // addition
  }
}
```

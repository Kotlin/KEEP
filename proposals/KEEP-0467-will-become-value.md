# `@WillBecomeValue` annotation

* **Type**: Design proposal
* **Author**: Evgeniy Zhelenskiy
* **Status**: Proposed
* **Discussion and feedback**: TODO

# Abstract

This KEEP proposes the `@WillBecomeValue` annotation to mark existing reference-based classes
whose identity semantics should not be relied upon. That prepares them for migration
to [full value classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md).

In particular, by using this annotation a developer helps their downstream users to not break their code when the class is changed to a value class, as those users will get migration warnings about usages which will stop being valid.
Without this mechanism, the adoption of full value classes would be a slower and harder process.

# Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Background: value classes in Kotlin](#background-value-classes-in-kotlin)
  * [The migration problem](#the-migration-problem)
  * [Java's solution: `@jdk.internal.ValueBased`](#javas-solution-jdkinternalvaluebased)
  * [Kotlin's need for an equivalent](#kotlins-need-for-an-equivalent)
  * [Candidate classes in the standard library](#candidate-classes-in-the-standard-library)
  * [Naming](#naming)
* [Design](#design)
  * [Annotation declaration](#annotation-declaration)
  * [Semantics](#semantics)
  * [Compiler warnings](#compiler-warnings)
  * [Applicability](#applicability)
* [References](#references)

# Motivation

## Background: value classes in Kotlin

Kotlin supports `value class` — a class modifier that strips object identity, enabling more safety and efficiency. Value classes guarantee that two
instances with the same state are indistinguishable: there is no reliable way to distinguish
them by reference equality (`===`), `System.identityHashCode`, or synchronization. Thus, such usages are forbidden for them.

A large part of the data programs manipulate is value-like by nature: points, dates, money amounts, ranges, complex numbers, wrappers, and similar types are defined entirely by their contents, and their identity is accidental rather than meaningful.
Representing such data as `value class`es rather than ordinary reference classes brings several benefits:

- It stops incidental identity operations: accidental `===`, a lock taken on a shared instance, or a cache keyed on identity can no longer silently behave differently from what the value semantics suggest.
- It enables potential optimizations: value classes can be flattened into their fields and passed without boxing, instead of being heap-allocated and accessed through a pointer.
- It allows smart-casts to cross module boundaries, since the absence of identity means the value cannot be mutated concurrently between the check and the use.
- It ensures better concurrency guarantees: without shared mutable identity, instances are safe to publish and share across threads.
- It provides support for the upcoming features [name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md) and [copy vars](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md#mutable-value-semantics-and-copy-vars).

This is why it is desirable to migrate as many such classes as possible to `value class`es.

The standard library already contains classes that behave like values — they are immutable,
have no meaningful identity, and are candidates for eventually becoming `value class`. Examples
include `Pair`, `Triple`, and various result/wrapper types.

Kotlin currently supports a very limited subset of `value class`es: inline `value class`es with a single underlying field.
Their main purpose is to create type-safe wrappers around existing types being transparent in the runtime.
Many libraries and frameworks (`kotlinx.serialization`, `Spring`) adopted the usage and embed the underlying field, keeping safe wrapper only on the source code level.
Such a behavior is not acceptable for general purpose value classes.
As a result, many classes that should have been `value` cannot be marked as such and remain ordinary reference classes.

There are plans to support [full `value class`es](https://github.com/Kotlin/KEEP/blob/69d675e8a15f66ff6b3dace70b2d45bc3d6ad26a/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md) in the future.
Unlike the currently supported inline `value class`es, they lift exactly the restrictions that blocked the data use-cases:
* May have multiple fields.
* Are not embedded into the underlying single field by libraries and frameworks, e.g. `kotlinx.serialization`, `Spring`.
* Do not change the ABI and do not spoil Java interoperability.

In other respects they behave like `data class`es without identity, component functions, or a `copy` function,
but with name-based destructuring and copy vars.

They also may be `abstract`/`sealed`.

This makes full `value class`es the natural target for the value-like classes that could not be expressed before, and people would want to migrate their existing classes to them. Read more about them in the dedicated KEEP.

However, the process of migrating existing classes to `value class` is a breaking change for several reasons:
1. The mentioned restrictions regarding identity operations might break existing code.
2. The `open` classes cannot be migrated because of the unclear semantics of the generated `equals`/`hashCode`/`toString` methods similar to `data class`es.
3. The `var`-based data classes cannot be migrated because of the mutability restrictions.


The problems 2 and 3 are the valid reasons for the class not to become a `value class`.
On the other hand, the first one can and should be mitigated.

## The migration problem

Turning an existing reference class into a `value class` is a **breaking change**:

- Code that relies on reference equality (`===`) will silently change behavior.
- Code that synchronizes on instances (`synchronized(pair) { }`) will break or become
  unreliable.
- Code that relies on identity hash codes will produce different results.

Without any prior warning, library authors cannot migrate widely used classes to full value classes
without risking undetected breakage in downstream code.

## Java's solution: `@jdk.internal.ValueBased`

[JEP 390](https://openjdk.org/jeps/390) addressed this problem for the JDK by introducing a `@ValueBased` annotation. When applied to
a class, it signals:

1. The class is intended to have value semantics (no meaningful identity).
2. Code that relies on its identity (reference equality, locking, serialization of identity)
   may break in a future JVM release.
3. The JVM may report warnings or errors when identity-sensitive operations are performed on such instances.

This allows the JDK to gradually evolve classes like `Integer`, `Optional`, and `LocalDate`
toward value types under [Project Valhalla](https://openjdk.org/projects/valhalla/), while giving users time to fix problematic usage.

Kotlin already supports the annotation: as tracked in [KT-70722](https://youtrack.jetbrains.com/issue/KT-70722),
the compiler recognizes `@jdk.internal.ValueBased` on JDK classes and issues warnings for
identity-sensitive operations on their instances. `@WillBecomeValue` extends this mechanism
to Kotlin-defined classes.

## Kotlin's need for an equivalent

Kotlin needs the same mechanism for its own migration path, and the JDK's annotation cannot serve it:

- `@jdk.internal.ValueBased` is JVM-only, while Kotlin is not.
- `@jdk.internal.ValueBased` is internal to the JDK.
- Kotlin standard library has its own value-like classes awaiting migration.
- Kotlin libraries authors need to start the migration beforehand.

## Candidate classes in the standard library

This section lists standard-library classes that are value-like (immutable, with no meaningful identity) and are therefore natural candidates for `@WillBecomeValue` now and for `value class` once [full value classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md) are available.

For contrast, some standard-library types are **already** inline `value class`es and need no migration: `UByte`, `UShort`, `UInt`, `ULong`, [`kotlin.time.Duration`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-duration/), and `Result`. Each of them wraps a single field, which is exactly the case the current inline `value class`es already cover.

Not every candidate can carry `@WillBecomeValue` right away.
Everything in the first two groups below can be annotated **today**:
each is `final`, shallow-immutable, and has no `open` supertype, so it already passes the [applicability](#applicability) checks.
The types in the last group are value-like too,
but **cannot be annotated yet** because a `value class` may be neither `open` nor a subtype of an `open` class —
they must first shed `open` from their own declaration or from the class they inherit.

**Multi-field values, blocked only by the single-field restriction.** These are ordinary immutable `final` classes that cannot be inline `value class`es today solely because they hold more than one field, so they become expressible only with full (multi-field) value classes:

- [`kotlin.uuid.Uuid`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/-uuid/) — two `Long`s (`mostSignificantBits`, `leastSignificantBits`). Its own KDoc already states that it "has value semantics" and "may become a value class in the future".
- [`kotlin.time.Instant`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-instant/) — `epochSeconds: Long` and `nanosecondsOfSecond: Int`.
- [`KotlinVersion`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-kotlin-version/) — `major`, `minor`, `patch` (all `Int`).
- [`kotlin.text.HexFormat`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-hex-format/) — an immutable formatting configuration (`upperCase: Boolean`, plus the nested `bytes` and `number` holders, which are themselves immutable multi-field configuration classes). Its KDoc explicitly states that the class "is immutable".

**Tuples and small carriers.** Immutable `data class`es that already exhibit value semantics. Beyond identity, migrating a `data class` to a `value class` also replaces positional `componentN()`/`copy()` with name-based destructuring and copy vars, so estimating their impact must account for that as well:

- [`Pair`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-pair/) (`first`, `second`) and [`Triple`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-triple/) (`first`, `second`, `third`) — the KDoc of both explicitly states that they exhibit value semantics.
- [`kotlin.collections.IndexedValue`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-indexed-value/) — `index: Int`, `value: T`.
- [`kotlin.text.MatchGroup`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-match-group/) — `value: String` (and `range: IntRange` on supporting platforms).
- [`kotlin.time.TimedValue`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-timed-value/) — `value: T`, `duration: Duration`.
- [`kotlin.reflect.KTypeProjection`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.reflect/-k-type-projection/) — `variance: KVariance?`, `type: KType?`.

**Value-like, but not markable yet (blocked by `open`).** These types have value semantics, but currently fail the applicability checks because a `value class` may be neither `open` nor a subtype of an `open` class. None of them can carry `@WillBecomeValue` until that is resolved:

- **Ranges and progressions.** The progression base classes `IntProgression`, `LongProgression`, `CharProgression` (and the unsigned `UIntProgression`, `ULongProgression`) are `open`, and the ranges inherit from them (`IntRange : IntProgression`, and so on). So *neither* the progressions *nor* the ranges [`IntRange`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.ranges/-int-range/), `LongRange`, `CharRange`, `UIntRange`, `ULongRange` can be marked — they would first have to be redesigned as a `sealed`/`abstract` value-class hierarchy, precisely the abstract/sealed support that full value classes add.
- [`kotlin.io.encoding.Base64`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io.encoding/-base64/) — an immutable configuration (`isUrlSafe`, `isMimeScheme`, `mimeLineLength`, `paddingOption`), but declared `open` (its `Default` companion object extends it), so it is blocked until it is made non-`open`.

- `String` — the class whose content is immutable and fully defined by value. However, identity might be, for example, useful for an equality check hot path.

# Design

## Naming

The annotation is named **`@WillBecomeValue`**. The name was chosen over three other candidates:

| Name            | Why rejected                                                                                                                         |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `@ValueBased`   | Ambiguous alongside the existing `value class` keyword — "value-based" vs. "value" is confusing.                                     |
| `@IdentityFree` | A synonym for `value`, effectively introducing a second name for the same concept.                                                   |
| `@TreatAsValue` | Does not explain *why* the class is not already a `value class`; also does not hint that usages produce warnings rather than errors. |

`@WillBecomeValue` was selected because it:
- Makes the migration intent explicit: the class *will become* a `value class`, but cannot be one yet due to temporary language restrictions.
- Explains why identity-sensitive usages produce warnings rather than errors — the class is not a value class *yet*, so the compiler warns rather than forbids.
- Is unambiguous: it cannot be confused with `value class` itself, unlike `@ValueBased` or `@IdentityFree`.

## Annotation declaration

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class WillBecomeValue
```

## Semantics

A class annotated with `@WillBecomeValue`:

1. **Must not be relied upon for identity.** Reference equality (`===`), `identityHashCode`,
   and synchronization on instances are considered undefined behavior and may be flagged by
   the compiler or runtime.
2. **Must be shallow-immutable.** Mutating state through shared references
   undermines the value semantics the annotation promises.
3. **Is a candidate for future migration to `value class`.**
   Library authors are expected to complete the migration to prevent libraries from keeping the annotation forever.

All the checks eligible for full value class declaration itself ***(not usages)*** are also run on classes annotated with `@WillBecomeValue`.
The only observable difference is that the compiler will issue a warning instead of an error for wrong class **usages**.

Here is a sample:
```kotlin
@WillBecomeValue
class A( // CE: Identity-based equals and/or hashCode will change (or super<Any>.equals/hashCode call)
    var x: Int, // CE: `var`s are forbidden for value classes
    val y: A, // CE: Recursive vqlue class type
    z: Int, // CE: Non-property primary constructor parameters
):
    Base(), // CE: if Base is not abstract/sealed value or ValueBased, report
    I by (x + 1) // CE: Delegation in value class

val a: A
a === a // Warning
synchronized(a) // Warning
System.identityHashCode(a) // Warning
```

### Data classes

`data class`es guarantee absence of primary constructors problems.
If not overridden explicitly, their `equals` and `hashCode` are also structural and considered safe by the annotation.

[Read more](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md#migration-between-data-and-value-classes)
to learn about the migration process of `data class`es.

## Compiler warnings

The compiler should issue a warning when the following
identity-sensitive operations are applied to a type annotated with `@WillBecomeValue`:

- Reference equality: `a === b`
- Locking: `synchronized(a) { }`
- `System.identityHashCode(a)`

## Applicability

The annotation can be applied to:
- Regular (reference) final classes.
- Abstract classes and sealed classes, intended as base types.

It **cannot** be applied to:
- Classes already declared as `value class`.
- Interfaces (interfaces are common for identity and value classes).
- `object` declarations (singletons intentionally have identity).
- Enums (enumerations cannot become value types).
- Open classes (value classes are not going to be supported to be `open`).

# References

- [JEP 390: Warnings for Value-Based Classes](https://openjdk.org/jeps/390)
- [Project Valhalla: Value Types](https://openjdk.org/projects/valhalla/)
- [KEEP: Inline (value) classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md)
- [KEEP-0453: Better Immutability in Kotlin ("Value Classes 2.0")](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md)
- [KT-70722: Support `@jdk.internal.ValueBased` / JEP 390 warnings in Kotlin](https://youtrack.jetbrains.com/issue/KT-70722)

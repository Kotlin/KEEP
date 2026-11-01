# `@WillBecomeValue` annotation

* **Type**: Design proposal
* **Author**: Evgeniy Zhelenskiy
* **Status**: Proposed
* **Discussion and feedback**: [GitHub](https://github.com/Kotlin/KEEP/discussions/504)

# Abstract

This KEEP proposes the `@WillBecomeValue` annotation to mark existing reference-based classes
whose identity semantics should not be relied upon. That prepares them for migration
to [full value classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md).

In particular, by using this annotation a developer helps their downstream users to not break their code when the class is changed to a value class, as those users will get migration warnings about usages which will stop being valid.
Without this mechanism, the adoption of full value classes would be a slower and harder process.

The annotation is initially experimental and requires an opt-in to `ExperimentalValueClassesApi`.

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
  * [Experimental status and opt-in](#experimental-status-and-opt-in)
  * [Semantics](#semantics)
  * [Compiler warnings](#compiler-warnings)
  * [Examples](#examples)
  * [Applicability](#applicability)
* [IDE support](#ide-support)
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
- It provides a convenient copying mechanism: [copy vars](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md#mutable-value-semantics-and-copy-vars).
- It uses safer [name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md) out of the box.

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

Similarly to data classes, value classes can extend interfaces; additionally, they also may be `abstract` / `sealed`.

This makes full `value class`es the natural target for the value-like classes that could not be expressed before,
and people would want to migrate their existing classes to them.
Read more about them in the dedicated [KEEP](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md#migration-between-data-and-value-classes).

## The migration problem

Turning an existing reference class into a `value class` is a **breaking change** for several reasons:

1. The identity restrictions of value classes might break existing use-sites:
   - Code that relies on reference equality (`===`) will silently change behavior.
   - Code that synchronizes on instances (`synchronized(pair) { }`) will break or become unreliable.
   - Code that relies on identity hash codes will produce different results.
2. The `open` classes cannot be migrated because of the unclear semantics of the generated `equals`/`hashCode`/`toString` methods similar to `data class`es.
3. The `var`-based data classes cannot be migrated because of the mutability restrictions.

The problems 2 and 3 are valid reasons for the class not to become a `value class`.
On the other hand, the first one can and should be mitigated.
To avoid having an immediate breaking change on use-sites, we would like to introduce a mitigation strategy via a dedicated migration process.

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
- `@jdk.internal.ValueBased` is internal to the JDK, while Kotlin needs to support a proper ecosystem-level mechanism.

## Candidate classes in the standard library

To show this "reference-to-value-based" class migration is actually relevant, let's take a look at Kotlin standard library.

This section lists standard-library classes that are value-like (immutable, with no meaningful identity) and are therefore natural candidates for `@WillBecomeValue` now and for `value class` once [full value classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md) are available.

For contrast, some standard-library types are **already** inline `value class`es and need no migration: `UByte`, `UShort`, `UInt`, `ULong`, [`kotlin.time.Duration`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-duration/), and `Result`. Each of them wraps a single field, which is exactly the case the current inline `value class`es already cover.


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
package kotlin

@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(
    CLASS, ANNOTATION_CLASS, PROPERTY, FIELD, LOCAL_VARIABLE, VALUE_PARAMETER,
    CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, TYPEALIAS,
)
@MustBeDocumented
@SinceKotlin("2.5")
annotation class ExperimentalValueClassesApi

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@SinceKotlin("2.5")
@ExperimentalValueClassesApi
annotation class WillBecomeValue
```

## Experimental status and opt-in

`@WillBecomeValue` is introduced in Kotlin 2.5.0 as an experimental API under the opt-in marker `kotlin.ExperimentalValueClassesApi`,
which marks all the experimental API related to value classes.
The compiler reports the diagnostics described below since Kotlin 2.5.20; before that, it reports none of them.

Authors applying `@WillBecomeValue` must opt in with `@OptIn(ExperimentalValueClassesApi::class)`, at the declaration or file level, or with the module-wide compiler option `-opt-in=kotlin.ExperimentalValueClassesApi`.
Using the annotation without an opt-in is a compilation error.

The opt-in requirement applies to using the experimental annotation.
A class does not itself become experimental solely because it carries `@WillBecomeValue`, so downstream users do not need this opt-in to use the class.

The experimental phase is used to stabilize and finalize the behavior of the related `@WillBecomeValue` diagnostics.

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
class A( // CE: Identity-based equals, hashCode and toString inherited from Any
    var x: Int, // CE: `var`s are forbidden for value classes
    val y: A, // CE: Recursive value class type
    z: Int, // CE: Non-property primary constructor parameters
):
    Base(), // CE: if Base is not an allowed supertype, see below
    I by (x + 1) // CE: Delegation to anything but a primary constructor property

val a: A
a === a // Warning
synchronized(a) // Warning
System.identityHashCode(a) // Warning
```

A final `@WillBecomeValue` class must override `equals`, `hashCode` and `toString`, or inherit structural overrides of them,
as the identity-based implementations inherited from `Any` would silently change once the class becomes a value class.
A `@WillBecomeValue` object only has to override `toString`, as it has a single instance.
Abstract and sealed classes are not checked.

Interface delegation is allowed only to a primary constructor property of a final class,
so abstract and sealed `@WillBecomeValue` classes cannot use it. Delegated properties are not allowed.

### Supertypes

A `@WillBecomeValue` class may extend:
- the classes a value class may extend: abstract and sealed value classes declared in Kotlin or Java;
- `@WillBecomeValue` classes, including those declared in Java;
- JDK value-based classes (`@jdk.internal.ValueBased`).

The converse is not allowed: a `value class` cannot extend a `@WillBecomeValue` class or a JDK value-based class,
as they are still identity classes.

### Declaration checks

The full list of the declaration checks reported as errors for a class annotated with `@WillBecomeValue`:
- it must not be local or inner;
- a final class must have a primary constructor with at least one parameter,
  and all its parameters must be `val` properties;
- the primary constructor of an abstract or sealed class must not have property parameters;
- it must not have properties with backing fields outside the primary constructor, nor delegated properties;
- it must not implement an interface by delegation, except to a primary constructor property of a final class;
- it must not extend classes other than the [allowed supertypes](#supertypes);
- it must not be recursive, directly or via type parameters;
- it must not be `Cloneable`;
- a final class must not inherit the identity-based `equals`, `hashCode` and `toString` from `Any`,
  an object must not inherit the identity-based `toString`;
- on the JVM, its members must not be annotated with `@Synchronized`.

These checks do not require full value classes to be enabled.

### Data classes

All primary constructor parameters of a `data class` are properties, so it only has to make them `val`s.
If not overridden explicitly, its `equals`, `hashCode` and `toString` are structural and considered safe by the annotation.
The same applies to `toString` of a `data object`.

[Read more](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md#migration-between-data-and-value-classes)
to learn about the migration process of `data class`es.

### Expect and actual declarations

An `expect` class declares no implementations of `equals`, `hashCode` and `toString`, as they come from its `actual` class.
So the requirement to override them is checked on the platform side:
for the `actual` class, and for the classes extending an `expect` class, with the members of its `actual` class.
An `actual typealias` to a Java class annotated with `@kotlin.WillBecomeValue` is checked the same way.

## Compiler warnings

The compiler reports a warning when one of the following identity-sensitive operations is applied to a value
whose type is a class annotated with `@WillBecomeValue`, or a type parameter, intersection or flexible type bounded by such a class:

- on all platforms:
  - reference equality: `a === b` and `a !== b`, except comparisons with `null`;
  - `compareAndSet` and `compareAndExchange` of `kotlin.concurrent.atomics.AtomicReference`;
- on the JVM:
  - locking: `synchronized(a) { }`;
  - `System.identityHashCode(a)`;
  - `WeakReference(a)`, `SoftReference(a)`, `PhantomReference(a, queue)`, `Reference.refersTo(a)` and `Cleaner.register(a, action)`;
  - `IdentityHashMap` and `WeakHashMap` with such a key type;
  - the compare-and-set operations of `java.util.concurrent.atomic.AtomicReference`, `AtomicReferenceFieldUpdater`,
    `VarHandle`, `AtomicStampedReference` and `AtomicMarkableReference`;
- on Kotlin/Native:
  - `identityHashCode()`;
  - `WeakReference(a)`.

The warning is reported both outside and inside the annotated class, and also for Java classes annotated with `@kotlin.WillBecomeValue`:

> Identity-sensitive operation on 'Uuid', which is annotated with '@WillBecomeValue'. It will be forbidden once the class becomes a value class.

## Examples

The examples below are illustrative: no standard library class is annotated with `@WillBecomeValue` yet
(see [the candidates](#candidate-classes-in-the-standard-library) and [KT-90055](https://youtrack.jetbrains.com/issue/KT-90055)).

For example, the standard library could mark `Uuid` as a value-like class that is expected to become a full `value class`:

```kotlin
@WillBecomeValue
// Simplified declaration.
class Uuid(
    val mostSignificantBits: Long,
    val leastSignificantBits: Long,
)
```

After that, users get migration warnings on identity-sensitive operations:

```kotlin
fun checkUuid(first: Uuid, second: Uuid) {
    first === second
    // Warning: identity-sensitive operation on 'Uuid', which is annotated with '@WillBecomeValue'

    synchronized(first) {
        // Warning: identity-sensitive operation on 'Uuid', which is annotated with '@WillBecomeValue'
    }

    System.identityHashCode(first)
    // Warning: identity-sensitive operation on 'Uuid', which is annotated with '@WillBecomeValue'
}
```

The same applies to data classes that already have value semantics, such as `Pair`:

```kotlin
@WillBecomeValue
data class Pair<out A, out B>(
    val first: A,
    val second: B,
)

fun checkPair(pair: Pair<String, String>) {
    pair === pair
    // Warning: identity-sensitive operation on 'Pair<String, String>', which is annotated with '@WillBecomeValue'
}
```

## Applicability

The annotation can be applied to top-level and nested (not inner) declarations:
- Regular (reference) final classes, including `data class`es.
- Abstract classes and sealed classes, intended as base types.
- Object declarations, including `data object`s.

It **cannot** be applied to:
- Classes already declared as `value class` or `value object`.
- Interfaces (interfaces are common for identity and value classes).
- Annotation classes.
- Enums (enumerations cannot become value types).
- Open classes (value classes are not going to be supported to be `open`).
- Companion objects and anonymous objects.

Local and inner classes and local objects are rejected by the [declaration checks](#declaration-checks).
When the annotation is not applicable, no other `@WillBecomeValue` checks are performed for the declaration.

# IDE support

In scope of this feature, we also propose the following IDE quick-fixes and intentions.

* When a `@WillBecomeValue` class inherits the identity-based `equals`, `hashCode` or `toString` from `Any`,
  a quick-fix suggests adding the `data` modifier or generating the missing members.
  For a `@WillBecomeValue` object, which only has to override `toString`,
  a quick-fix suggests turning it into a `data object` or generating `toString`.
* The superclass of a `@WillBecomeValue` class must be a value class, a `@WillBecomeValue` class,
  or a JDK value-based class (`@jdk.internal.ValueBased`).
  When it extends any other abstract or sealed class, a quick-fix suggests marking with `@WillBecomeValue`
  not only that superclass but the whole hierarchy of its superclasses that are not yet allowed as supertypes.
  Subclasses are not affected: a class extending a `@WillBecomeValue` class does not need to be marked.
* An intention converts a `@WillBecomeValue` class to a `value class`.
  It converts the whole hierarchy of the potential value classes at once:
  all `@WillBecomeValue` superclasses and subclasses of the class are converted together with it,
  so that no value class extends a class that is still an identity class.

# References

- [JEP 390: Warnings for Value-Based Classes](https://openjdk.org/jeps/390)
- [Project Valhalla: Value Types](https://openjdk.org/projects/valhalla/)
- [KEEP: Inline (value) classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md)
- [KEEP-0454: Better Immutability in Kotlin ("Value Classes 2.0")](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md#migration-between-data-and-value-classes)
- [KT-70722: Support `@jdk.internal.ValueBased` / JEP 390 warnings in Kotlin](https://youtrack.jetbrains.com/issue/KT-70722)

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
  * [Naming](#naming)
* [Design](#design)
  * [Annotation declaration](#annotation-declaration)
  * [Semantics](#semantics)
  * [Compiler warnings](#compiler-warnings)
  * [Applicability](#applicability)
  * [Interaction with value classes](#interaction-with-value-classes)
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

The standard library already contains classes that behave like values — they are immutable,
have no meaningful identity, and are candidates for eventually becoming `value class`. Examples
include `Pair`, `Triple`, and various result/wrapper types.

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

Kotlin needs the same mechanism for its own migration path:

- Kotlin needs the annotation to be available on all target platforms.
- Library authors (including the Kotlin standard library team) want to signal that a class is
  should be marked `value`, but it cannot be done because of the compatibility constraint, usage restrictions,
  field number restriction, and others.
- The Kotlin compiler and tooling can then warn at use sites where identity-sensitive operations
  are applied to annotated instances, giving developers time to fix their code.
- When the class is eventually migrated to `value class`, the change will be much less likely
  to cause silent regressions.

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

# Design

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

## Interaction with value classes

Once a class annotated with `@WillBecomeValue` is migrated to `value class`, the annotation
should be removed. The compiler will report an error if `@WillBecomeValue` is present on a `value class`.

# References

- [JEP 390: Warnings for Value-Based Classes](https://openjdk.org/jeps/390)
- [Project Valhalla: Value Types](https://openjdk.org/projects/valhalla/)
- [KEEP: Inline (value) classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md)
- [KEEP-0453: Better Immutability in Kotlin ("Value Classes 2.0")](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md)
- [KT-70722: Support `@jdk.internal.ValueBased` / JEP 390 warnings in Kotlin](https://youtrack.jetbrains.com/issue/KT-70722)

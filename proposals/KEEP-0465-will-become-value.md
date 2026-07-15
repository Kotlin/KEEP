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

Kotlin currently supports a very limited subset of `value class`es: inline `value class`es with a single underlying field.
Their main purpose is to create type-safe wrappers around existing types being transparent in the runtime.
Many libraries and frameworks (`kotlinx.serialization`, `Spring`) adopted the usage and embed the underlying field, keeping safe wrapper only on the source code level.
Such a behavior is not acceptable for general purpose value classes.
Due to this and other restrictions, far not all existing classes can be marked as `value class` while only inline `value class`es are supported.

There are plans to support [full `value class`es](https://github.com/Kotlin/KEEP/blob/69d675e8a15f66ff6b3dace70b2d45bc3d6ad26a/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md) in the future.
These value classes behave similarly to `data class`es, but have no identity, component functions, copy function.
They support multiple fields, abstract/sealed classes. Read more about them in the dedicated KEEP.
They are the main target to migrate the existing immutable classes to.

However, the process of migrating existing classes to `value class` is a breaking change for several reasons:
1. The mentioned restrictions regarding identity operations might break existing code.
2. Currently existing `@JvmInline` value classes support only a single field.
3. Currently existing `@JvmInline` value classes cannot be abstract/sealed.

When migration to the existing inline `value class` is theoretically possible (the mentinioned problems do not apply),
the following problems are still present:
1. Change in the behavior of `kotlinx.serialization`, `Spring` and other frameworks which have special handling for inline `value class`es. 
2. ABI change on JVM.
3. Worse interoperability with Java.

It stops users from migrating to inline `value class`es.

Since only problem 3 can be mitigated (with `@JvmExposeBoxed`), the scope of the rest of the proposal is the migration to the full `value class`es.

The only problem that still remains for full `value class`es is identity-related operation restrictions because:
* ABI is kept the same (enabling support of [Project Valhalla](https://openjdk.org/projects/valhalla/) on JVM is going to change only the attributes, not descriptors or signatures).
* The full `value class`es allow multiple fields and can be `abstract`/`sealed`.

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

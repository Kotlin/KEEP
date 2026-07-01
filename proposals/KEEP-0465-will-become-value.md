# `@WillBecomeValue` annotation

* **Type**: Design proposal
* **Author**: Evgeniy Zhelenskiy
* **Status**: Proposed
* **Discussion and feedback**: TODO

# Summary

This KEEP proposes the `@WillBecomeValue` annotation — a Kotlin equivalent of Java's
`@jdk.internal.ValueBased` from [JEP 390](https://openjdk.org/jeps/390). The original Java annotation marks existing
reference-based classes whose identity semantics should not be relied upon, because they are
intended to become value classes in after the [Valhalla](https://openjdk.org/projects/valhalla/) project release.

The annotation is necessary for the gradual migration process. However, the original Java
annotation is internal in JDK.

# Motivation

## Background: value classes in Kotlin

Kotlin supports `value class` — a class modifier that strips object identity, enabling more safety and effeciency. Value classes guarantee that two
instances with the same state are indistinguishable: there is no reliable way to distinguish
them by reference equality (`===`), `System.identityHashCode`, or synchronization. Thus, such usages are forbidden for them.

However, the process of migrating existing classes to `value class` is a breaking change for two reasons:
1. The mentioned restrictions
2. ABI change on JVM (in case of the existing `@JvmInline` value classes)
3. Currently existing `@JvmInline` value classes support only a single field.
4. Currently existing `@JvmInline` value classes cannot be abstract/sealed.

The second problem is not a problem for full value classes,
since the ABI is kept the same and switching to Valhalla is going to change only the attributes.

The third and the fourth problems are not problem for full value classes as well,
since the full `value class`es allow multiple fields and can be abstract/sealed.

The standard library already contains classes that behave like values — they are immutable,
have no meaningful identity, and are candidates for eventually becoming `value class`. Examples
include `Pair`, `Triple`, and various result/wrapper types.

## The migration problem

Turning an existing reference class into a `value class` is a **breaking change**:

- Code that relies on reference equality (`===`) will silently change behavior.
- Code that synchronizes on instances (`synchronized(pair) { }`) will break or become
  unreliable.
- Reflection-based code that caches identity hash codes will produce different results.

Without any prior warning, library authors cannot migrate widely-used classes to value classes
without risking undetected breakage in downstream code.

## Java's solution: `@jdk.internal.ValueBased`

JEP 390 addressed this for the JDK by introducing a `@ValueBased` annotation. When applied to
a class, it signals:

1. The class is intended to have value semantics (no meaningful identity).
2. Code that relies on its identity (reference equality, locking, serialization of identity)
   may break in a future JVM release.
3. The JVM may warn or err when identity-sensitive operations are performed on such instances.

This allows the JDK to gradually evolve classes like `Integer`, `Optional`, and `LocalDate`
toward value types under Project Valhalla, while giving users time to fix problematic usage.

Kotlin already supports the annotation: as tracked in [KT-70722](https://youtrack.jetbrains.com/issue/KT-70722),
the compiler recognizes `@jdk.internal.ValueBased` on JDK classes and issues warnings for
identity-sensitive operations on their instances. `@WillBecomeValue` extends this mechanism
to Kotlin-defined classes.

## Kotlin's need for an equivalent

Kotlin needs the same mechanism for its own migration path:

- Library authors (including the Kotlin standard library team) want to signal that a class is
  should be marked `value`, but it cannot be done because of the compatibility constraint, usage restrictions,
  field number restriction, and others.
- The Kotlin compiler and tooling can then warn at use sites where identity-sensitive operations
  are applied to annotated instances, giving developers time to fix their code.
- When the class is eventually migrated to `value class`, the change will be much less likely
  to cause silent regressions.

## Naming

The annotation is named **`@WillBecomeValue`**. The name was chosen over three other candidates:

| Name            | Why rejected                                                                                      |
|-----------------|---------------------------------------------------------------------------------------------------|
| `@ValueBased`   | Ambiguous alongside the existing `value class` keyword — "value-based" vs. "value" is confusing  |
| `@IdentityFree` | A synonym for `value`, effectively introducing a second name for the same concept                 |
| `@TreatAsValue` | Does not explain *why* the class is not already a `value class`; also does not hint that usages produce warnings rather than errors |

`@WillBecomeValue` was selected because it:
- Makes the migration intent explicit: the class *will become* a `value class`, but cannot be one yet due to compatibility or language constraints.
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
2. **Must be immutable (or effectively immutable).** Mutating state through shared references
   undermines the value semantics the annotation promises.
3. **Is a candidate for future migration to `value class`.** Library authors are not obligated
   to complete the migration, but the annotation sets that expectation.

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
- Interfaces (identity is not a concept that applies directly).
- `object` declarations (singletons intentionally have identity).
- Enums (enumerations cannot become value types).
- Open classes (value classes are not going to be ever supported to be open).

## Interaction with value classes

Once a class annotated with `@WillBecomeValue` is migrated to `value class`, the annotation
should be removed. The compiler will report an error if `@WillBecomeValue` is present on a `value class`.

# References

- [JEP 390: Warnings for Value-Based Classes](https://openjdk.org/jeps/390)
- [Project Valhalla: Value Types](https://openjdk.org/projects/valhalla/)
- [KEEP: Inline (value) classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md)
- [KEEP-0453: Better Immutability in Kotlin ("Value Classes 2.0")](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0453-better-immutability-value-classes-motivation.md)
- [KT-70722: Support `@jdk.internal.ValueBased` / JEP 390 warnings in Kotlin](https://youtrack.jetbrains.com/issue/KT-70722)

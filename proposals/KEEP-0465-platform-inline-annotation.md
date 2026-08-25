# Explicit Annotations for Inline Single-Field Value Classes on All Platforms

* **Type**: Design proposal
* **Author**: Marat Akhin
* **Contributors**: Pavel Kunyavskiy, Alejandro Serrano Mena, Alexander Udalov, Mikhail Zarechenskiy, Evgeniy Zhelenskiy
* **Status**: Public discussion
* **Discussion**: [GitHub](https://github.com/Kotlin/KEEP/discussions/501)
* **Related proposals**:
  * [KEEP-0104: Inline Classes](./KEEP-0104-inline-classes.md)
  * [KEEP-0454: Full Value Classes (aka Multi-Field Value Classes)](./KEEP-0454-better-immutability-value-classes-MFVC.md)

## Abstract

Kotlin is moving toward two source-visible kinds of value classes.
**Inline value classes** are the existing single-field value classes whose inline behavior is externally observable.
**Full value classes** are the new value classes whose inlining (if done) is not observable for users.

This proposal introduces `@PlatformInline` as the marker annotation on non-JVM for the first kind.
The `value` modifier describes identity-less value semantics, while `@PlatformInline` selects the inline single-field value class kind.
The existing `@JvmInline` annotation remains a supported spelling for the same kind, including when it is already used in common source.

```kotlin
//// Before full value classes are enabled

// Existing explicit JVM and common spelling
@JvmInline
value class RgbColor(val rgb: Int)

// Implicit inline value class on non-JVM platforms
value class HsvColor(val hsv: Int)

// New explicit spelling
@PlatformInline
value class Color(val rgb: Int)

//// After full value classes are enabled

// `@PlatformInline value class` means "inline"
@PlatformInline
value class Color(val rgb: Int)

// Existing `@JvmInline value class` remains valid and also means "inline"
@JvmInline
value class RgbColor(val rgb: Int)

// An unmarked `value class` means "full value class"
value class UserId(val raw: String)
value class Complex(val re: Double, val im: Double)
```

Migration is staged.

* Existing JVM and common code written with `@JvmInline value class` remains valid without a mandatory source migration.
* Existing non-JVM code that currently implicitly uses the inline kind and **would like to keep it** receives diagnostics and quick fixes to add `@PlatformInline`.
* Existing non-JVM and common code that does not rely on being inline and would like to get the new full value class behavior when they are released can silence the migration diagnostics via [compiler options](#compiler-options).

After the migration period is over, plain single-field `value class` declarations switch their meaning and use the full value class kind; code that wants the old inline kind must use `@PlatformInline` or `@JvmInline` spelling.

## Table of Contents

- [Abstract](#abstract)
- [Motivation](#motivation)
  - [The Current State of Things](#the-current-state-of-things)
  - [The Ambiguity Introduced by Full Value Classes](#the-ambiguity-introduced-by-full-value-classes)
  - [The Inline Kind Is Observable](#the-inline-kind-is-observable)
  - [`@JvmInline` Was Too Optimistic](#jvminline-was-too-optimistic)
  - [When Ambiguity Creates Problems](#when-ambiguity-creates-problems)
- [Proposal](#proposal)
  - [New `@PlatformInline`](#new-platforminline)
  - [Existing `@JvmInline`](#existing-jvminline)
    - [`@PlatformInline` or `@JvmInline`?](#platforminline-or-jvminline)
    - [`@JvmInline` in Common Source](#jvminline-in-common-source)
    - [`@JvmExposeBoxed`](#jvmexposeboxed)
  - [Historical `inline class`](#historical-inline-class)
  - [Expect/Actual Matching](#expectactual-matching)
  - [Compiler Options](#compiler-options)
- [Migration](#migration)
  - [Existing JVM Code](#existing-jvm-code)
  - [Existing Non-JVM Code](#existing-non-jvm-code)
  - [Existing Multiplatform Code](#existing-multiplatform-code)
- [IDE Support](#ide-support)
- [Reflection Support](#reflection-support)
- [Interaction with Serialization and Exports](#interaction-with-serialization-and-exports)
- [Alternatives](#alternatives)
  - [Use an `inline` Modifier](#use-an-inline-modifier)
  - [Use a Negative Marker](#use-a-negative-marker)
  - [Add a Non-JVM Annotation](#add-a-non-jvm-annotation)
  - [Use `@JvmInline` as the Universal Spelling](#use-jvminline-as-the-universal-spelling)
  - [Reuse `inline class`](#reuse-inline-class)
  - [Keep Shape-Based Inlining Forever](#keep-shape-based-inlining-forever)
  - [Change Behavior With Full Value Classes](#change-behavior-with-full-value-classes)

## Motivation

### The Current State of Things

Today Kotlin has single-field value classes.

They were introduced for a narrow but important use case: type-safe wrappers over existing values without the allocation overhead of ordinary wrapper classes.
As they are wrappers, they follow value semantics and do not have well-defined identity; the value being wrapped defines them.
As they want to ensure no allocation overhead, they are **inlined** by the compiler (when possible) to avoid creating the wrapper.

On the JVM they are explicitly marked with `@JvmInline`.
On non-JVM platforms, a value class with one primary property is treated as inline **by its shape**.

```kotlin
// JVM
@JvmInline
value class Color(val rgb: Int) // inline

// Non-JVM
value class Color(val rgb: Int) // inline
```

Unfortunately, this shape-based rule is no longer merely an implementation detail of the Kotlin compiler.
Libraries and tools have learned to treat a single-field value class in common or non-JVM code as a declaration of the inline value class kind.

### The Ambiguity Introduced by Full Value Classes

With [full value classes](./KEEP-0454-better-immutability-value-classes-MFVC.md), this shape-based rule becomes ambiguous.

```kotlin
value class Color(val rgb: Int) // inline? full?
```

This declaration could mean either of the following.

1. An inline single-field value class, keeping the current inline value class kind.
2. A full single-field value class, following the general rules of value classes.

> Note: we will sometimes call the design of full value classes MFVCs, as their ability to declare multiple stored fields is their main user-visible difference with respect to inline value classes.

These two meanings are source-similar but observably different.
On the JVM this difference is representation- and ABI-visible, because signatures and generated methods can use either the underlying type (for inline value classes) or the full value class wrapper.
On non-JVM platforms, the Kotlin compilation model is closer to closed-world compilation, so the difference is not really observable while you stay within Kotlin.
However, this closed world is not actually closed once a value class crosses boundaries such as serialization, export to another language, reflection-like tooling, or code generation.

The problem is therefore broader than only JVM ABI.

### The Inline Kind Is Observable

The inline single-field value class kind should be explicit in source because it is **externally observable**.
Its exact effects are platform-specific, but it is visible at several important boundaries.

* On JVM, binary signatures by default use the underlying type instead of the wrapper type.
* Kotlin/JS, Kotlin/Native, and Kotlin/Wasm exports may need to distinguish inline single-field value classes from full value classes.
* Serialization plugins encode inline value classes as their established inline shape rather than as full value class structures.
* Reflection-like tooling, compiler plugins, API validators, and code generation may need to know whether a declaration uses the inline single-field value class kind.

To name a few concrete examples:

* Compose uses common value classes as lightweight type-safe wrappers, and it needs them to stay that way for performance purposes.
* Serialization tools, such as kotlinx.serialization and Ktor OpenAPI, use the inline shape to conventionally represent value classes as their underlying values rather than a full class-like structure.
* Export tools such as SKIE expose single-field value classes as their underlying properties.

In other words, "single-field value class" has become a cross-tool convention for the inline value class kind.
With full value classes this is no longer automatically the case, and whether a value class is inline should not be inferred solely from the number of primary properties.

By giving this kind an annotation for every platform, we make it an explicit source code declaration rather than an implicit controlled-by-convention behavior.
All consumers which care about a single-field value class being inline can rely on the same source-level fact: this declaration belongs to the inline single-field value class kind.
Without a marker, every boundary where this difference is important and potentially observable needs its own way of distinguishing inline and full single-field value classes.

### `@JvmInline` Was Too Optimistic

The current `@JvmInline` spelling reflects [the original assumption](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md#description) that the observable part of inline value classes was primarily a JVM problem.
On the JVM, representation affects method descriptors, mangling, Java interop, and binary compatibility, so the representation needed an explicit JVM marker.
On non-JVM platforms, the closed-world model made it tempting to infer inlining from the single-field shape.
This is why the original design required `@JvmInline` on JVM while allowing single-field value classes on non-JVM platforms to be inline by shape.
At that point the important compatibility boundary seemed to be the open-world JVM ABI, while klib-based platforms were treated as "closed-world enough" that the distinction did not need a common source marker.

That assumption turned out to be too narrow.
Even when a backend can internally choose a representation later, other tools and boundaries still need to know whether a value class belongs to the inline single-field kind.

### When Ambiguity Creates Problems

To illustrate the problem, consider a serializable value class in source where today's rules infer the inline kind from the single-field shape:

```kotlin
@Serializable
value class UserId(val raw: String)
```

Under the current rules this is a single-field inline value class on non-JVM platforms, and serializers encode it using the established inline shape, e.g. as an underlying string value.
When full value classes are released, the same declaration defines a full value class, and evolving the type by adding a property with a default value also silently changes the kind:

```kotlin
@Serializable
value class UserId(val raw: String, val tag: String = "")
```

Now serializers naturally treat it as a full value class, e.g. as an object with two properties, which changes its serialization shape **silently** and in a **backwards-incompatible manner**.

> Note: the same problem will also happen in a KMP project between JVM and non-JVM platforms, causing silent serialization format discrepancies between platforms.

With the proposed spelling, the old declaration is explicit:

```kotlin
@Serializable
@PlatformInline
value class UserId(val raw: String)
```

Adding another stored property then necessarily requires removing `@PlatformInline`, making the declaration-kind change visible during code review and in tooling.

## Proposal

### New `@PlatformInline`

Introduce `@PlatformInline` as a dual-to-`@JvmInline` annotation recognized intrinsically by the compiler.
Its declaration is equivalent to the following:

```kotlin
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
@SinceKotlin("2.5")
public expect annotation class PlatformInline

// JVM
actual typealias PlatformInline = JvmInline

// non-JVM
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@SinceKotlin("2.5")
public actual annotation class PlatformInline
```

This way, on JVM compiling a `@PlatformInline value class` emits the `@JvmInline` compatibility annotation.
This keeps consumers which currently use its presence to detect inline value classes (e.g. Spring) working without any migration.

`@PlatformInline` may be applied only to a `value class` with one primary property.
Using it on another declaration, or on a value class whose shape cannot use the inline single-field representation, is a compile-time error.

```kotlin
@PlatformInline
value class Color(val rgb: Int)

@PlatformInline
data class RegularClass(val rgb: Int) // Error: `@PlatformInline` requires a value class

@PlatformInline
value class Complex(val re: Double, val im: Double) // Error: `@PlatformInline` value class requires one primary property
```

A `@PlatformInline value class` uses the same inline single-field value class kind and follows the same rules as current inline value classes described in [KEEP-0104](./KEEP-0104-inline-classes.md).

Once [full value classes](./KEEP-0454-better-immutability-value-classes-MFVC.md) are released, a value class without `@PlatformInline` or `@JvmInline` spelling uses the full value class kind by default.
This includes value classes with one primary property.

```kotlin
value class UserId(val raw: String)
```

### Existing `@JvmInline`

The existing `@JvmInline` annotation remains a supported spelling for source compatibility.
It is normalized to the same inline value class kind as `@PlatformInline`; it is not scheduled for deprecation and existing valid code does not receive a mandatory migration diagnostic.

This is achieved by adding actualizations of `@JvmInline` to `@PlatformInline` on non-JVM platforms.

```kotlin
// non-JVM
actual typealias JvmInline = PlatformInline
```

#### `@PlatformInline` or `@JvmInline`?

One can choose between both `@PlatformInline` and `@JvmInline` spellings, as they are equivalent.
Thanks to how they are declared, the end result is the same: `@JvmInline` annotation is materialized on the JVM platform, `@PlatformInline` annotation is materialized on non-JVM platforms.

```kotlin
// Both declarations are inline value classes

@PlatformInline
value class Color(val rgb: Int)

@JvmInline
value class RgbColor(val rgb: Int)
```

The suggested rule to which one should be used in the source code is as follows:

* If the source set includes the JVM platform, one should prefer `@JvmInline` spelling.
* In other cases, one should prefer `@PlatformInline` spelling.

Trying to combine both spellings on the same declaration is a compile-time error.

```kotlin
@PlatformInline
@JvmInline
value class RgbColor(val rgb: Int) // Error: repeated annotation
```

#### `@JvmInline` in Common Source

`@JvmInline` remains legal in both JVM source and common source.
In common source, it stops meaning "inline only on the JVM" and begins meaning "inline on all platforms."
This allows existing common declarations to remain unchanged, and existing expect declarations to automatically require adding `@PlatformInline` on non-JVM platforms.

```kotlin
// Existing common source: no migration required
@JvmInline
value class Color(val rgb: Int)

// Requires matching @PlatformInline annotation on non-JVM platforms
@JvmInline
expect value class Color(val rgb: Int)

// New spelling on non-JVM
@PlatformInline
actual value class Color(val rgb: Int)
```

#### `@JvmExposeBoxed`

[`@JvmExposeBoxed`](./KEEP-0394-jvm-expose-boxed.md) remains compatible with inline value classes on the JVM.
The compiler applies it after normalizing the source spelling to materialized `@JvmInline` annotation, so both `@PlatformInline value class` and `@JvmInline value class` participate in the same JVM boxed-exposure rules.

```kotlin
@JvmExposeBoxed
@PlatformInline // Supported but not suggested spelling
value class Color(val rgb: Int)
```

> Note: this proposal does not introduce a common `PlatformExposeBoxed` facility.
> `@JvmExposeBoxed` addresses a JVM-specific problem: exposing boxed JVM entry points for declarations whose primary JVM ABI uses the inline representation, for interoperability purposes.
> If other platforms need analogous control for their exports, it should be designed separately based on the inline/full value class kind separation.

### Historical `inline class`

The older `inline class` syntax is a historical spelling for inline value classes.
This proposal **does not** revive `inline class` as a recommended syntax.

For migration purposes, `inline class` does not need a separate trajectory.
On the JVM and in common source, it is treated as a synonym for the `@JvmInline value class` spelling.
On non-JVM platforms, it is treated as a synonym for the `@PlatformInline value class` spelling.
In all cases diagnostics and quick fixes should migrate it to the new annotation-based spellings.

After the migration period, a future language version may deprecate or reject `inline class` as a source spelling.
This does not affect the continued support for `@JvmInline value class`.

### Expect/Actual Matching

For multiplatform declarations, the inline kind is part of the expect/actual contract; the exact annotation spelling is not.

```kotlin
@PlatformInline
expect value class Color(val rgb: Int)
```

All actual declarations for such an expect class must use the inline value class kind.
They may spell this with `@PlatformInline`, or with `@JvmInline`.

```kotlin
@PlatformInline
actual value class Color(val rgb: Int)

@JvmInline
actual value class Color(val rgb: Int)
```

An existing common expect carrying `@JvmInline` establishes the same contract and does not need to migrate.
It only requires adding `@PlatformInline` on currently unmarked non-JVM platforms.

```kotlin
@JvmInline
expect value class LegacyColor(val rgb: Int)

@JvmInline
actual value class LegacyColor(val rgb: Int) // JVM

actual value class LegacyColor(val rgb: Int) // non-JVM before
// =>
@PlatformInline
actual value class LegacyColor(val rgb: Int) // non-JVM after
```

If the expected declaration uses the full value class kind, an inline actual is not allowed.

```kotlin
// When full value classes are enabled, this is a full value class
expect value class UserId(val raw: String)

@PlatformInline
actual value class UserId(val raw: String) // Error: full / inline kind mismatch
```

This prevents the same common API from using the inline value class kind on one target and the full value class kind on another target, which would lead to different externally observed behavior between platforms.

### Compiler Options

For migration, the Kotlin compiler will provide a `-Xplatform-inline-value-class-migration` option with the following modes:

```text
-Xplatform-inline-value-class-migration=warning
-Xplatform-inline-value-class-migration=strict
```

`warning` is the soft migration mode.
Unmarked single-field value classes keep their legacy inline behavior, and the compiler reports diagnostics with quick fixes to add `@PlatformInline`.
Declarations already carrying `@JvmInline` are explicitly inline and require no change.

`strict` is the hard validation mode.
Unmarked single-field value classes become errors.
One needs to annotate those value classes which rely on inline behavior with `@PlatformInline`.
This mode is useful to guarantee that the code no longer depends on implicit inlining.

When full value classes are enabled (either by default or with an explicit compiler flag), this serves as an opt-in to "migration is done, all inline value classes are marked, it is OK to change unmarked single-field value classes to full value classes."

Through the migration period, one would go through some or all of the following combinations of flags.

* `-Xplatform-inline-value-class-migration=warning` + `-Xfull-value-classes=off` to get migration warnings for all implicitly inline value classes, and keep the inline behavior for some or all of them by adding `@PlatformInline`.
  * This will be the default in Kotlin 2.5.
* `-Xplatform-inline-value-class-migration=strict` + `-Xfull-value-classes=off` to get errors for all implicitly inline value classes, and keep the inline behavior for some or all of them by adding `@PlatformInline`.
  * This will be the default in Kotlin 2.6.
* `-Xfull-value-classes=on` to switch the behavior to full value classes and stop reporting migration diagnostics for unmarked single-field value classes.
  * This will be the default in Kotlin 2.7+.

The `-Xplatform-inline-value-class-migration` and `-Xfull-value-classes` flags are dependent; only the following combinations are allowed.

| `-Xfull-value-classes` / `-Xplatform-inline-value-class-migration` | `warning` | `strict` |
|--------------------------------------------------------------------|-----------|----------|
| `off`                                                              | OK        | OK       |
| `on`                                                               | not OK    | not OK   |

This means that, if one sets `-Xfull-value-classes=on`, it effectively disables the migration diagnostics (as it must have been completed before).

## Migration

The migration starts from a language where Kotlin does not support full value classes and where a single-field value class is either explicitly inline on the JVM or implicitly inline on non-JVM platforms.
The intended end state is a language where Kotlin supports full value classes and a single-field `value class` is a full value class by default.

During the transition, users must be able to make either of the following decisions for each existing single-field value class.

* Preserve the current inline value class kind by adding `@PlatformInline`, or by keeping an existing `@JvmInline` marker.
* Accept the behavior change and let the declaration become a full single-field value class.

### Existing JVM Code

Existing JVM code already spells the inline value class kind explicitly:

```kotlin
@JvmInline
value class Color(val rgb: Int)
```

This code continues to compile with the same meaning and requires no source migration.

### Existing Non-JVM Code

Existing non-JVM code currently relies on implicit shape-based inlining:

```kotlin
value class Color(val rgb: Int)
```

A single-field value class without an explicit inline marker is interpreted as inline until full value classes are enabled.
For this code, there are two possible migration trajectories.

The first trajectory is to preserve the inline kind by adding the `@PlatformInline` annotation:

```kotlin
@PlatformInline
value class Color(val rgb: Int)
```

This keeps the existing inline intent for externally observable consumers of value classes.

The other trajectory is to accept the future full value class behavior.
In that case the declaration intentionally remains plain:

```kotlin
value class Color(val rgb: Int) // full single-field value class when enabled
```

This is a behavior change for an existing declaration.
To opt in to accepting it, a source set can use one of the following options.

* Enable `-Xfull-value-classes=on` to do the behavior switch.
* Enable `-Xplatform-inline-value-class-migration=warning` and do `-Xwarning-level=IMPLICIT_INLINE_VALUE_CLASS:disabled` to disable the warning module-wide.
* (Not recommended but possible) Use `@Suppress`.

### Existing Multiplatform Code

The most important migration case is common code.

Common declarations which already carry `@JvmInline` require no source migration.
In the new model the annotation denotes the inline kind for the whole common declaration, not an inline kind limited to its JVM compilation.

```kotlin
// Existing common source remains valid
@JvmInline
value class UserId(val id: String)

@JvmInline
expect value class Color(val rgb: Int)
```

New common declarations should use [either of the new spellings](#platforminline-or-jvminline):

```kotlin
@PlatformInline
value class NewUserId(val id: String)

@JvmInline
expect value class NewColor(val rgb: Int)
```

An expect declaration marked by either annotation establishes a common inline-kind contract.
Actual declarations must match that kind:

```kotlin
// common
@PlatformInline
expect value class Color(val rgb: Int)

// JVM: either supported spelling is valid, @JvmInline is suggested
@JvmInline
actual value class Color(val rgb: Int)

// non-JVM: either supported spelling is valid, @PlatformInline is suggested
@PlatformInline
actual value class Color(val rgb: Int)
```

The IDE should update unmarked non-JVM actual declarations when migration diagnostics require the kind to become explicit.

If the author chooses the future full value class behavior, the expect declaration remains a plain `expect value class`.
Once full value classes are enabled, one would need to **remove** `@JvmInline` from an inline JVM actual, changing the ABI in a backwards-incompatible manner.

> Note: this is an instance of the general problem of migrating from inline to full value classes.
> [`@JvmExposeBoxed`](./KEEP-0394-jvm-expose-boxed.md) could provide a better migration path by generating boxed entry points in addition to inline entry points, and then also [changing](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0454-better-immutability-value-classes-MFVC.md#migration-from-stage-0-to-stage-1-via-jvmexposeboxed) the compiler code generation strategy.
> This possibility will be explored in the scope of that design and implementation.

## IDE Support

IDE support is essential because much of the migration is mechanical but must be applied carefully.

The IDE should provide the following quick fixes when `-Xplatform-inline-value-class-migration` is set to `warning` or `strict`.

**Implicit inline kind.**
Detect a single-field value class without an explicit inline marker.

Offer "Add `@PlatformInline`" for source sets not targeting the JVM.

```kotlin
value class Color(val rgb: Int)
```

becomes:

```kotlin
@PlatformInline
value class Color(val rgb: Int)
```

Offer "Add `@JvmInline`" for source sets targeting the JVM.

```kotlin
value class Color(val rgb: Int)
```

becomes:

```kotlin
@JvmInline
value class Color(val rgb: Int)
```

**Existing `@JvmInline` spelling.**
Do not issue a migration diagnostic for an existing valid `@JvmInline value class`; it already selects the inline kind explicitly.

**Switching between `@PlatformInline` and `@JvmInline`.**

An intention should offer "Replace with `@PlatformInline`" / "Replace with `@JvmInline`" for cases when the annotation used is not the annotation [suggested for the source set](#platforminline-or-jvminline).

```kotlin
// non-JVM
@JvmInline
value class Color(val rgb: Int)
```

becomes:

```kotlin
// non-JVM
@PlatformInline
value class Color(val rgb: Int)
```

and vice versa.

**Expect/actual inline mismatch.**

Detect mismatches between expected and actual inline kinds and offer fixes on the actual side.
The expect declaration is the source of truth for the common API declaration kind.
If the expect declaration is inline, add `@PlatformInline` / `@JvmInline` to unmarked actual declarations.

For compatibility purposes, when a JVM actual is already marked with `@JvmInline` but its expect declaration is unmarked, the suggested fix is to add `@JvmInline` to the expect declaration.

**Bulk migration.**
The IDE should provide a module- and project-level migration action for declarations that still depend on implicit shape-based inlining.
Existing explicit `@JvmInline` declarations are not part of the required bulk rewrite.

## Reflection Support

To simplify working with the inline value class kind, reflection and metadata should expose it directly instead of reconstructing it from annotations or primary-constructor arity.

We plan to add the following property to `KClass`:

```kotlin
public val isInline: Boolean
// returns true if the class is of inline kind
// * it is explicitly inline, i.e. it was compiled with `@PlatformInline` or `@JvmInline`
// * it is implicitly inline, i.e. it was compiled without either marker and without full value classes enabled
```

We also plan to add the following extension properties to `kotlin.reflect`:

```kotlin
public val KClass<*>.isFullValue: Boolean
    get() = isValue && !isInline

public val KClass<*>.isInlineValue: Boolean
    get() = isValue && isInline
```

Tools are advised to use this normalized kind rather than make behavior depend on which equivalent source annotation was used.

## Interaction with Serialization and Exports

Serialization and export tools should prefer to use the inline-kind information recorded by the compiler instead of inferring it from the number of primary properties or from one particular annotation spelling.

For inline value classes, existing inline-specific behavior is preserved unless the tool has an explicit customization mechanism.

```kotlin
@Serializable
@PlatformInline
value class Color(val rgb: Int) // inline kind

@Serializable
@JvmInline
value class LegacyColor(val rgb: Int) // same inline kind

@Serializable
value class UserId(val raw: String) // depends on how this was compiled
```

Before full value classes are enabled in a source set, this distinction is mostly a migration concern: existing unmarked single-field value classes continue to use the inline kind, and tools should preserve their current behavior.
Once full value classes are enabled, and especially once they are stable by default, tools must stop inferring inline behavior from arity alone.
They should handle three cases:

* `@PlatformInline value class` or `@JvmInline value class`: use inline value class behavior.
* Full single-field `value class`: use full value class behavior.
* Legacy unmarked single-field value class compiled before full value classes are enabled: preserve inline value class behavior and potentially report migration diagnostics specific to the current tooling.

## Alternatives

### Use an `inline` Modifier

We could introduce `inline value class` as the common spelling.

```kotlin
inline value class Color(val rgb: Int)
```

This makes the declaration read as one coherent class kind, but it requires a boilerplate-y migration: most existing JVM and common declarations are already marked with `@JvmInline`, and they must eventually be rewritten even though its semantic kind does not change.
It also marks `inline value class` as a full part of the Kotlin language, whereas we hope to reduce or even eliminate the need to think about inline value class kind once project Valhalla and other activities around value classes are complete.

The `@PlatformInline` annotation we propose instead follows the existing annotation-based model and allows `@JvmInline` to remain a direct fully backwards-compatible analogue.

### Use a Negative Marker

Another option is to keep one-field value classes inline by default and add a marker for the full value class kind.

```kotlin
@NonInlined
value class UserId(val raw: String)
```

This minimizes migration churn but preserves the wrong long-term default.
The uncommon-looking declaration would be the ordinary case with the full value class kind, while the unmarked declaration would keep a special inline-kind meaning.

### Add a Non-JVM Annotation

We could add a separate annotation such as `@KlibInline` or `@NonJvmInline` to cover platforms where inlining was previously implicit.

```kotlin
@JvmInline
@KlibInline
value class Color(val rgb: Int)
```

This follows the current implementation split, but it is not the right user-facing abstraction.
The user intent is not "also inline on non-JVM"; the user intent is "this value class uses the inline single-field kind."
Common code should express that once in common terms with one annotation, and not twice with two.

### Use `@JvmInline` as the Universal Spelling

We could make `@JvmInline` the recommended explicit marker on every platform.
This would avoid introducing a second annotation, but its name would incorrectly present a platform-independent class kind as JVM-specific.
It could also wrongly imply that non-JVM backends may ignore the annotation.

The proposed model keeps `@JvmInline` as a valid spelling, recommended to use in JVM source sets (where Kotlin already accepts it) and adds `@PlatformInline` as the new spelling for non-JVM source.

### Reuse `inline class`

We could make the old spelling the recommended spelling again:

```kotlin
inline class Color(val rgb: Int)
```

This is short, but it obscures that the declaration is still a value class.
It also reopens the historical migration from `inline class` to `value class`.
The proposed `@PlatformInline value class` spelling keeps continuity with current value classes while making the inline kind explicit.

### Keep Shape-Based Inlining Forever

We could keep the rule that every single-field value class on non-JVM platforms is of inline kind.
This is maximally compatible, but it prevents users from declaring a full one-field value class.
It also keeps field count as an observable value class property, which is problematic, as the [motivating example](#when-ambiguity-creates-problems) shows.

### Change Behavior With Full Value Classes

We could make `value class Foo(val x: T)` mean inline in old language versions and a full value class in new language versions.
This avoids a new marker, but it means the same source has different observable shape depending on compiler settings.
That is too dangerous and opens up the problem we've discussed in the [motivation](#when-ambiguity-creates-problems).

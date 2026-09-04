# Signature discriminators for Kotlin callables

* **Type**: Design proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, TBD
* **Discussion**: TBD
* **Status**: Internal discussion
* **Related proposal**: [Signature discriminators in platform import and export](KEEP-xxxx-signature-discriminator-interop.md)
* **Related YouTrack issues**: [KT-52009](https://youtrack.jetbrains.com/issue/KT-52009)

## Abstract

This proposal introduces `@SignatureDiscriminator`, a multiplatform mechanism for assigning an additional identity component to a Kotlin callable.
It is intended for API evolution when a library must retain an old declaration alongside a new declaration that would otherwise be a conflicting overload or produce a platform declaration clash.

A signature discriminator participates in declaration-conflict checking, override matching, expect/actual matching, and binary linkage.
It does not participate in call resolution, so a call site cannot select an overload by its signature discriminator.

It also does not define how the declaration is exported or imported on specific platforms.
In particular, the discriminator is not a Java, JavaScript, Objective-C, Swift, or WebAssembly name.
Extensions to the `@SignatureDiscriminator` design that cover these cases are specified separately in [Signature discriminators in platform import and export](KEEP-xxxx-signature-discriminator-interop.md).

## Table of contents

* [Motivation](#motivation)
  * [API evolution](#api-evolution)
  * [Why existing mechanisms are not enough](#why-existing-mechanisms-are-not-enough)
* [Non-goals](#non-goals)
* [Detailed design](#detailed-design)
  * [The annotation](#the-annotation)
  * [The signatures of a Kotlin callable](#the-signatures-of-a-kotlin-callable)
  * [Conflicting overloads](#conflicting-overloads)
  * [Override matching](#override-matching)
  * [Expect/actual matching](#expectactual-matching)
  * [Binary identity](#binary-identity)
  * [Also: properties](#also-properties)
* [Additional tooling](#additional-tooling)
  * [Reflection](#reflection)
  * [ABI tooling](#abi-tooling)
  * [IDE tooling](#ide-tooling)
* [Alternatives](#alternatives)
  * [Unconditionally use the discriminator as the platform name](#unconditionally-use-the-discriminator-as-the-platform-name)
  * [Use compiler-generated value for the discriminator](#use-compiler-generated-value-for-the-discriminator)
* [Open issues](#open-issues)

## Motivation

### API evolution

Libraries sometimes need to change the shape of an API while remaining binary compatible with previously compiled consumers.
Consider changing the return type of an open function:

```kotlin
// library v1
interface API {
    fun value(): CharSequence
}
class APIImpl : API {
    override fun value(): CharSequence = "old"
}

// library v2
interface API {
    fun value(): String
}
class APIImpl : API {
    override fun value(): String = "new"
}
```

Replacing the old declaration is binary incompatible: previously compiled consumers still link against the old binary signature.
The usual migration recipe is to keep the old declaration hidden and add the new one, which usually works until it does not:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    fun value(): String
}
```

This does not compile because Kotlin does not allow overloads that differ only in return type.
Similar problems arise also for the following cases:

* changing a property type;
* changing type parameters and arguments when they are erased on the platform;
* changing suspendness of a Kotlin callable.

`@SignatureDiscriminator` makes the new declaration a distinct callable for conflicting overloads, override matching and binary linkage while keeping its Kotlin source name unchanged:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String
}
```

### Why existing mechanisms are not enough

Existing options do not solve the core problem: distinguishing two declarations that are identical according to Kotlin signature rules.
They are only partial remedies for individual symptoms.

Using `@JvmName` can solve some JVM-only clashes for top-level and final declarations, but it is not generally applicable to open or abstract members, and does not work for other platforms.

Suppressing `CONFLICTING_OVERLOADS` or a platform-declaration-clash diagnostic does not guarantee distinct binary identities and can produce broken artifacts.

## Non-goals

This proposal specifically defines `@SignatureDiscriminator` as a solution to the problem of making two callables distinct on their declaration sites.
It does not change call resolution on their use sites and does not define platform import or export naming.

The companion interop proposal covers the story about feature interaction between `@SignatureDiscriminator` annotation and different Kotlin export/import mechanisms.

## Detailed design

### The annotation

```kotlin
package kotlin

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class SignatureDiscriminator(val value: String)
```

The `value` is an opaque component of a declaration signature.
It is not a Kotlin source name and is not, by itself, a platform export name.

The following rules apply:

* `value` must be a non-empty string literal.
  Constant expressions, concatenations, and references to `const val` declarations are not accepted.
  The compiler must be able to read the discriminator before ordinary expression resolution.
* The annotation cannot be applied through a typealias.
* The annotation cannot be applied to a local declaration or a constructor.
* Two equal string values denote the same discriminator.
  The value has no additional structure and is compared exactly.

### The signatures of a Kotlin callable

Kotlin uses different notions of a callable's signature for different purposes.
The proposal changes some of them and intentionally leaves others unchanged.

**Call-resolution signature.**
This is used to decide which declaration a source call resolves to.
It includes the Kotlin source name, value parameter types and type parameters, and may use value parameter names for calls with named arguments.
It excludes the return type.

`@SignatureDiscriminator` does not participate in call resolution.
There is no syntax for supplying a discriminator at a call site.
Call sites must therefore be disambiguated by ordinary Kotlin mechanisms, such as hiding the compatibility overload with `@Deprecated(level = HIDDEN)` or using named arguments when parameter names distinguish the overloads.

**Overload signature.**
This is used for the conflicting overloads checking.
It includes the Kotlin source name, value parameter types and type parameters.
It excludes the return type and the value parameter names.
The proposal adds the presence and exact value of `@SignatureDiscriminator` to this signature.

**Override-matching signature.**
This is used to match an `override` with inherited members.
It includes the Kotlin source name, value parameter types and type parameters.
It excludes the return type and the value parameter names.
Return-type covariance, property mutability, suspendness, and other overridability conditions are checked separately after a signature match.
The proposal adds the presence and exact value of `@SignatureDiscriminator` to this signature.

**Expect/actual-matching signature.**
For all intents and purposes, it works the same way as override-matching signature.

**Binary signature.**
This identifies a declaration in a compiled artifact.
On klib-based platforms the discriminator is stored in the linkage signature.
On other platforms the backend encodes it in the declaration's binary identity.

For example, on the JVM, the method descriptor has no field for a discriminator, so the compiler encodes it in the JVM method name.
Conceptually, the emitted name consists of the Kotlin source name followed by a stable encoding of the discriminator, while Kotlin metadata continues to expose the original source name.
Kotlin call sites and overrides use the encoded JVM name in bytecode.

This encoding must be deterministic, collision-free, stable across recompilations and compiler versions.
The exact encoding will be specified before stabilization.

### Conflicting overloads

The conflicting-overloads check is performed within groups of declarations with the same source name and signature discriminator.
An absent annotation means a distinguished "no discriminator" value, and two equal discriminator strings place declarations in the same group.
Declarations in different discriminator groups are distinct for this check, although ordinary call resolution can still report ambiguity because it ignores the discriminator.

Consequently, the intended migration from the [motivation](#motivation) section compiles:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String
}
```

### Override matching

The override matching is changed to also consider the discriminator between an override and the overridden declaration:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String
}

class APIImpl : API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = value()

    @SignatureDiscriminator("string-return-type")
    override fun value(): String = "new"
}
```

As this annotation is an important part of the declaration signature, it does not propagate automatically; one needs to write it explicitly on an override of a discriminated declaration.

This is intentionally verbose:

* it keeps declaration identity visible at every source declaration;
* it avoids deriving the effective signature of an override from the supertypes (which could potentially come from other modules or dependencies);
* it makes conflicts in multiple inheritance explicit.

If two inherited declarations have the same "source" override signature but different discriminators, they remain distinct members and require distinct overrides.

```kotlin
interface LegacyValue {
    @SignatureDiscriminator("legacy-value")
    fun value(): CharSequence
}

interface StringValue {
    @SignatureDiscriminator("string-value")
    fun value(): String
}

class CombinedValue : LegacyValue, StringValue {
    @SignatureDiscriminator("legacy-value")
    override fun value(): CharSequence = "legacy"

    @SignatureDiscriminator("string-value")
    override fun value(): String = "current"
}
```

The two implementations do not override or merge with one another.
A call through `LegacyValue` or `StringValue` selects the corresponding declaration; a call to `value()` through `CombinedValue` is ambiguous because call resolution does not use discriminators.

### Expect/actual matching

Kotlin-to-Kotlin expect/actual matching includes the discriminator:

```kotlin
// commonMain
expect class APIImpl {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String
}

// jvmMain
actual class APIImpl {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    actual fun value(): CharSequence = value()

    @SignatureDiscriminator("string-return-type")
    actual fun value(): String = "new"
}
```

An `actual` declaration must repeat the discriminator exactly.
The annotation is not copied from `expect` to `actual`.

Actualization to an imported platform declaration, including `actual typealias`, requires a mapping between Kotlin and platform identity.
That mapping for declarations having a Kotlin signature discriminator is outside this proposal.

### Binary identity

The discriminator is recorded as an independent part of Kotlin binary signature.
It is not substituted for the Kotlin source name.

On klib-based platforms, the linkage signature contains the source name, the ordinary Kotlin signature components, and the discriminator.
This prevents otherwise identical declarations with different discriminators from receiving the same linkage identity.

On platforms whose executable format cannot directly represent this extra component, the backend uses an ABI-stable encoding of *(source name, discriminator)* in the binary symbol identity, analogous to other Kotlin name manglings; it is not a user-selected binary name.

For a set of declarations with the otherwise same signatures, the compiler guarantees that no platform declaration clash is produced if:

* all declarations except at most one carry `@SignatureDiscriminator`;
* all discriminator values in the set are pairwise distinct; and
* no platform interop annotation or external declaration overrides the core binary encoding.

The exact encoding used by each executable backend will be fixed and documented before the stabilization of `@SignatureDiscriminator`.

### Also: properties

The annotation targets a property as a whole:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    var item: CharSequence

    @SignatureDiscriminator("string-item")
    var item: String
}
```

but is applied to all derived entities: property itself, backing field, getter and setter, etc.
This means it has the same effect as described in the previous sections for all these entities w.r.t. override matching, binary identity, etc.

## Additional tooling

### Reflection

`KCallable.name` continues to report the Kotlin source name.
The discriminator does not silently replace it.

Whether Kotlin reflection exposes the discriminator through a new API is an open issue.

### ABI tooling

Kotlin ABI tools and binary-compatibility validators must render the discriminator explicitly in dumps.

### IDE tooling

IDE support should include completion and quick-fixes that copy the inherited discriminator into generated override and actual stubs.

## Alternatives

### Unconditionally use the discriminator as the platform name

The discriminator value could become the JVM name, JavaScript export name, Objective-C selector base, and WebAssembly export name.

> Note: in essence, this is the previously proposed `@BinarySignatureName` design.

This tightly couples Kotlin declaration identity to several unrelated platform-specific binary conventions and makes it impossible to reason about the core language feature without settling every import/export interaction.
It is rejected for this proposal.

The companion interop proposal instead defines explicit convenience interactions between `@SignatureDiscriminator` and platform names.

### Use compiler-generated value for the discriminator

A zero-argument annotation could ask the compiler to generate a discriminator from the full signature, source position, or a pseudo-random token; see [KT-86190](https://youtrack.jetbrains.com/issue/KT-86190).
This idea is rejected because it makes the discriminator value potentially fragile under source changes.
The proposal therefore requires an explicit discriminator value.

## Open issues

Before stabilization, we need to answer the following questions.

* **Platform-specific encoding.**
  Each backend needs a well-defined and ABI-stable encoding specification for the discriminator value.
* **Reflection API.**
  We need to decide whether the discriminator should be observable through Kotlin reflection.
* **Override and expect/actual ergonomics.**
  Repetition is intentional, but good IDE support is necessary for non-trivial hierarchies.
  Also, there is a question of whether we want to support signature-discriminator-aware actualization to platform declarations.
* **ABI tool format.**
  A common rendering is needed so klib and JVM dumps describe the same discriminator consistently.

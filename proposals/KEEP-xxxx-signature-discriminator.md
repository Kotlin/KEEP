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
* [Migration of open members](#migration-of-open-members)
  * [Updating a library-owned hierarchy](#updating-a-library-owned-hierarchy)
  * [Supporting external implementations](#supporting-external-implementations)
    * [Moving the implementation to the new function](#moving-the-implementation-to-the-new-function)
    * [Keeping the old override contract](#keeping-the-old-override-contract)
  * [Migrating an inheritance hierarchy](#migrating-an-inheritance-hierarchy)
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
Consider changing the return type of a function in a library.
In this first example, `API` is sealed, `APIImpl` is final, and the library ships all implementations together with `API`.
Previously compiled callers must continue working.

```kotlin
// library v1
sealed interface API {
    fun value(): CharSequence
}
class APIImpl : API {
    override fun value(): CharSequence = "old"
}

// library v2
sealed interface API {
    fun value(): String
}
class APIImpl : API {
    override fun value(): String = "new"
}
```

Replacing the old declaration is binary incompatible: previously compiled consumers still link against the old binary signature.
It is sometimes possible to keep the old declaration hidden from source calls while retaining its binary signature, but this is not a silver bullet.

```kotlin
sealed interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    fun value(): String
}
```

This still does not compile because Kotlin does not allow overloads that differ only in return type.
Similar problems arise also for the following cases:

* changing a property type;
* changing type parameters and arguments when they are erased on the platform;
* adding `suspend` to or removing it from a Kotlin function.

`@SignatureDiscriminator` gives the new declaration an additional identity:

```kotlin
sealed interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String
}
```

The additional identity has several related goals:

* **Allow both declarations to coexist.**
    The discriminator makes the declarations distinct for conflicting-overload checks, even though they have the same Kotlin name and parameter list.
    This lets the library retain the old declaration while adding the new one.
* **Keep their override relationships separate.**
     Merely allowing the declarations to coexist would not be enough for open types: an implementation must be able to override each one independently.
     Repeating the discriminator identifies which declaration an override implements:

  ```kotlin
  class APIImpl : API {
      @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
      override fun value(): CharSequence = value()

      @SignatureDiscriminator("string-return-type")
      override fun value(): String = "new"
  }
  ```

* **Preserve binary linkage.**
    The old declaration keeps its existing binary identity, so previously compiled consumers can still link to it.
    The discriminator gives the new declaration a different, stable binary identity instead of reusing the old symbol.
* **Match the same declaration across source sets.**
    The discriminator participates in `expect`/`actual` matching, allowing otherwise identical declarations to remain distinct in a multiplatform API.
* **Remain independent of platform names.**
    A discriminator identifies a Kotlin declaration; it is not a Java, JavaScript, Objective-C, Swift, or WebAssembly name.
    The related [platform import and export proposal](KEEP-xxxx-signature-discriminator-interop.md) builds on discriminators to support explicit platform names without conflating the two concepts.

The [migration discussion](#migration-of-open-members) follows this example through old and new callers and explains the additional requirements when implementations can be created externally.

### Why existing mechanisms are not enough

Existing options do not solve the core problem: distinguishing two declarations that are identical according to Kotlin signature rules.
They are only partial remedies for individual goals, but none can solve all of these goals at the same time.

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

Every signature has a **signature context** which determines which declarations are considered together for a particular operation.
It accounts for declaration scopes and relationships such as inheritance or `expect`/`actual` matching, as appropriate for that operation.

Signature contexts do not have to match by literal equality.
In particular, an override and the member it overrides have different containing classes; inheritance establishes the relationship needed for matching.
Each operation uses its signature context according to the existing Kotlin rules described below.

The following table summarizes which declaration components participate in each source-level signature:

| Purpose                     | Signature context                  | Source name | Type parameters | Value parameter types | Value parameter names | Return type        | Other compatibility conditions | Signature discriminator |
|-----------------------------|------------------------------------|-------------|-----------------|-----------------------|-----------------------|--------------------|--------------------------------|-------------------------|
| Call resolution             | Imports, scopes, and receivers     | Yes         | Yes             | Yes                   | For named arguments   | No                 | No                             | No                      |
| Conflicting-overload checks | Exact scopes                       | Yes         | Yes             | Yes                   | No                    | No                 | No                             | Yes                     |
| Override matching           | Inheritance scopes                 | Yes         | Yes             | Yes                   | No                    | Checked separately | Checked separately             | Yes                     |
| `expect`/`actual` matching  | Exact scopes                       | Yes         | Yes             | Yes                   | No                    | Checked separately | Checked separately             | Yes                     |

> Note: “checked separately” means that a component must still be compatible, but is validated only after declarations have been matched by signature.

**Call-resolution signature.**
This is used to decide which declaration a callable invocation (a function call or a property access) resolves to.

Its signature context accounts for the call's explicit or implicit receivers, scopes, and imports when selecting candidates.
The call-resolution signature includes the Kotlin callable name, value parameter types and type parameters, and may use value parameter names for calls with named arguments.
It excludes the return type.

`@SignatureDiscriminator` does not participate in call resolution.
There is no syntax for supplying a discriminator at a call site.
Call sites must therefore be disambiguated by ordinary Kotlin mechanisms, such as hiding the compatibility overload with `@Deprecated(level = HIDDEN)` or using named arguments when parameter names distinguish the overloads.

**Overload signature.**
This is used for the conflicting overloads checking.

Its signature context determines the declarations compared within the same scope.
The overload signature includes the Kotlin source name, value parameter types and type parameters.
It excludes the return type and the value parameter names.

The proposal adds the presence and exact value of `@SignatureDiscriminator` to this signature.

**Override-matching signature.**
This is used to match an `override` with inherited members.

Its signature context uses the inheritance hierarchy to identify inherited members and scopes.
The override-matching signature includes the Kotlin source name, value parameter types and type parameters.
It excludes the return type and the value parameter names.

Return-type covariance, property mutability, suspendness, and other overridability conditions are checked separately after a signature match.

The proposal adds the presence and exact value of `@SignatureDiscriminator` to this signature.

**Expect/actual-matching signature.**
Its signature context determines the declarations compared within the same scope.
Within those corresponding contexts, callable matching works the same way as override matching.

**Binary signature.**
This identifies a declaration in a compiled artifact and is platform-specific.
On klib-based platforms the discriminator is stored in the linkage signature.
On other platforms the backend encodes it in the declaration's binary identity.

For example, the JVM descriptor has no field for a discriminator, so the compiler encodes it in the emitted JVM name.
Conceptually, the emitted name consists of the Kotlin source name followed by a stable encoding of the discriminator, while Kotlin metadata continues to expose the original source name.
Kotlin call sites and overrides use the encoded JVM name in bytecode.

This encoding must be deterministic, collision-free, stable across recompilations and compiler versions.
The exact encoding will be specified before stabilization.

### Conflicting overloads

The conflicting-overloads check is performed within groups of declarations with the same source name and signature discriminator.
An absent annotation means a distinguished "no discriminator" value, and two equal discriminator strings place declarations in the same group.
Declarations in different discriminator groups are distinct for this check, although ordinary call resolution can still report ambiguity because it ignores the discriminator.

Consequently, the old and new declarations from the [motivation](#motivation) can coexist:

```kotlin
sealed interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String
}
```

The [migration discussion](#migration-of-open-members) describes how to preserve old callers and what is additionally required to support old implementations.

### Override matching

The override matching is changed to also consider the discriminator between an override and the overridden declaration:

```kotlin
sealed interface API {
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

## Migration of open members

`@SignatureDiscriminator` enables several strategies for backwards-compatible migration of open members:

1. [Update all implementations together](#updating-a-library-owned-hierarchy).
2. [Move the implementation to the new function](#moving-the-implementation-to-the-new-function), and make the old function delegate to it.
3. [Keep the old override contract](#keeping-the-old-override-contract), and make the new function use the old implementation.

### Updating a library-owned hierarchy

The [motivation](#api-evolution) and [override example](#override-matching) describe successive versions of a hierarchy whose implementations are all upgraded together:

1. Version 1 publishes `API::value(): CharSequence` and the final `APIImpl` implementing it.
    Callers compiled against this version refer to the old binary signature.
2. Version 2 retains that declaration without a discriminator, marks it `HIDDEN`, and adds `value(): String` with the discriminator `"string-return-type"`.
3. Version 2 of `APIImpl` implements both declarations.
    Its old member function forwards to its new member function, but it could potentially have an independent implementation.
4. The library publishes the updated API and all its implementations together.
    Old caller binaries continue to invoke the retained function on the updated implementation.
    Newly compiled callers resolve to the new function because the old one is hidden.

The new interface member can remain abstract in this migration: all concrete implementations provide it in version 2.
This is possible, because all implementations are owned by the library which performs the migration.

### Supporting external implementations

For an interface or class that clients can implement or extend independently, we could get a mix of versions.
The following cases need to be considered:

| Caller | Implementation | Required behavior                                                                                                    | Covered by `@SignatureDiscriminator`                                                                   |
|--------|----------------|----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| Old    | Old            | Retain the old function via `@SignatureDiscriminator`.                                                            | Yes: the old binary identity can remain unchanged alongside the new declaration.                       |
| Old    | New            | Retain the old function via `@SignatureDiscriminator`, for example by forwarding it to the new one or implementing it separately. | Yes: both functions can be overridden independently, and the old function can call the new one. |
| New    | Old            | Provide an implementation of the new function that works with the old implementation.                        | No: requires a fallback usable by old binaries and/or a way to call the old function.                |
| New    | New            | Provide the new function directly or via inheritance.                                      | N/A: works as is.                                                                                      |

> Note: here, "covered" means that the discriminator enables the required declarations and overrides, not that it generates implementation bodies or guarantees compatible resolve throughout an inheritance hierarchy.

The examples below use the same Kotlin source name, `value`, for both declarations: the old one has no discriminator, and the new one carries `@SignatureDiscriminator("string-return-type")`.
To make call targets explicit, we use the illustrative notation `value@<discriminator>(...)`:

* `value@<none>()` selects the old declaration. Here, `none` denotes the absence of a discriminator.
* `value@<"string-return-type">()` selects the new declaration with a signature discriminator.
* `super.value@<none>()` selects the old superclass implementation; the same notation can select the new superclass implementation.

> Note: this is back-of-the-napkin notation, not call syntax introduced by this proposal.
> It assumes the ability to explicitly access the selected declarations, including hidden declarations, so we can discuss migration separately from the call resolution syntax.

#### Moving the implementation to the new function

An implementation can migrate by moving its body to the new function and making the old function delegate to it.
For example, a final implementation can change as follows, with version 2 of `API` declaring both:

```kotlin
// Before migration, implementing version 1 of API.
class Impl : API {
    override fun value(): CharSequence = "implementation"
}

// After migration, implementing version 2 of API.
class Impl : API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = value@<"string-return-type">()

    @SignatureDiscriminator("string-return-type")
    override fun value(): String = "implementation"
}
```

This provides both entry points on the migrated implementation.

However, an external implementation is not automatically migrated aka it is not backwards-compatible; it still overrides only the old undiscriminated `value`.
Calling the new `value` with such implementation would fail.

The need to update the implementations would need to be communicated to all downstream dependants.

#### Keeping the old override contract

If keeping all external implementation backwards-compatible is a hard requirement, one could keep the old contract, and the new function can remain an adapter around the old one.
Its default body calls the old implementation and adapts its result.
This preserves backwards compatibility, but requires all of the following:

* **A valid adaptation.**
    For the running example, converting a `CharSequence` with `toString()` can provide a `String` if that satisfies the intended API contract.
    For the general case, the conversion could be different, but it must be possible to express the new behavior via the old one.
* **A way to call the old declaration.**
    Once it is `HIDDEN`, an ordinary `value()` call cannot select it.
    The call will resolve to the visible new overload, and most probably fail.
    That is why we assume there is a way to call the old function, via a separate discriminator-based syntax or something else.
* **A new default available to old binaries.**
    The platform must support calling the new implementation on an already compiled binary.
    On the JVM for interfaces, this requires using Java 8 interface defaults; the alternative Kotlin-specific `DefaultImpls` based compilation does not work.
    On other platforms, this is supported during linkage phase.

Both existing implementations and implementations updated under this strategy keep overriding the old function and inherit the new default:

```kotlin
// Version 2 of the API
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-return-type")
    fun value(): String = value@<none>().toString()
}

// External implementation
class UpdatedImpl : API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = "old one"
}
```

The tradeoff is that the old function remains the implementation contract, including its parameter and return types.
The new default adapts that contract; it does not make the new function a valid override point for implementations.

### Migrating an inheritance hierarchy

The two strategies can coexist *next to each other*: a migrated implementation provides its new body and old-to-new forwarder, while an unchanged implementation in a sibling inheritance branch receives new calls through the new-to-old forwarder.
When these strategies are consistently used throughout the affected hierarchies, the combinations work as follows:

| Caller | Implementation | Call path                                                                                               |
|--------|----------------|---------------------------------------------------------------------------------------------------------|
| Old    | Old            | `value@<none>()` implementation                                                                                        |
| Old    | New            | `value@<none>()` implementation or `value@<none>()` default => `value@<"string-return-type">()`                 |
| New    | Old            | `value@<"string-return-type">()` default => `value@<none>()`                                            |
| New    | New            | `value@<"string-return-type">()` implementation or `value@<"string-return-type">()` default => `value@<none>()` |

As seen from the table, if we combine both a new-to-old default with an old-to-new one within the same hierarchy, it can create a cycle:

```kotlin
// Base library keeps the old override contract via new-to-old delegation
open class Base {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    open fun value(): CharSequence = "base"

    @SignatureDiscriminator("string-return-type")
    open fun value(): String = value@<none>().toString()
}

// Downstream library forwards old calls to the new function,
// but the new body still uses the base class's new-to-old adapter.
open class Derived : Base() {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = value@<"string-return-type">()

    @SignatureDiscriminator("string-return-type")
    override fun value(): String = super.value@<"string-return-type">()
}
```

Calling either function on a `Derived` instance leads to this cycle:

```text
Derived::value@<"string-return-type">()
  -> Base::value@<"string-return-type">()
  -> Derived::value@<none>()
  -> Derived::value@<"string-return-type">()
  -> ...
```

To preserve external implementations, the migration trajectory needs to be more complicated:

1. **Keep the old override contract in classes with external implementors.**
    This preserves backwards compatibility for all pre-existing external implementations.
2. **Migrate implementations to the new function only when all descendants can be updated together.**
    A final class can provide a new implementation and an old-to-new forwarder, as in [the earlier example](#moving-the-implementation-to-the-new-function).
    An open external hierarchy can do the same only when all affected overrides and `super` calls are migrated together; otherwise, there is a possibility to create a forwarding cycle.
3. **Keep the legacy contract until the migration is done.**
    Once all supported downstream implementations and their `super` calls have been updated to the new contract, the library can switch to the new override contract while retaining the old entry point for callers.

#### Putting it all together

The following sequence has two independently released components:

* The **library** owns the extensible class `Base`.
* The **external implementor** owns `Derived` and all its descendants, represented here by the final class `Leaf`.
  It can update this entire subtree together.

Callers are compiled separately from these components.
Each stage below replaces only the declarations shown for that component; the other component keeps its previous version.

**Stage 1: publish the original library and external hierarchy.**

```kotlin
// Library v1
open class Base {
    open fun value(): CharSequence = "base"
}

// External implementor v1, compiled against library v1
open class Derived : Base() {
    override fun value(): CharSequence = "derived"
}

class Leaf : Derived() {
    override fun value(): CharSequence = super.value().toString() + " leaf"
}

// Old caller, compiled against library v1 and retained without recompilation
fun oldCall(api: Base): CharSequence = api.value()
```

**Stage 2: the library adds the new function while keeping the old override contract.**

```kotlin
// Library v2
open class Base {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    open fun value(): CharSequence = "base"

    @SignatureDiscriminator("string-return-type")
    open fun value(): String = value@<none>().toString()
}

// New caller, compiled against library v2
fun newCall(api: Base): String = api.value() // resolves to the new function
```

The external implementor can keep shipping its v1 binaries.
For a `Leaf` instance, `newCall` enters `Base`'s new-to-old adapter and dispatches to `Leaf`'s old override.
Its existing `super.value()` call still reaches `Derived`'s original body.
Both `oldCall` and `newCall` therefore produce `"derived leaf"`.

**Stage 3: the external implementor migrates its whole subtree in one release.**

```kotlin
// External implementor v2, compiled against library v2
open class Derived : Base() {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = value@<"string-return-type">() // forwards to the new function

    @SignatureDiscriminator("string-return-type")
    override fun value(): String = "derived"
}

class Leaf : Derived() {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = value@<"string-return-type">() // forwards to the new function

    @SignatureDiscriminator("string-return-type")
    override fun value(): String = super.value@<"string-return-type">() + " leaf"
}
```

The library remains at v2.
The external implementor moves both bodies and changes the code to use the new function instead of the old one.

If the external implementor had descendants it could not update in the same release, it would have to retain its old override contract until those descendants could be migrated too.
Other external implementors in sibling hierarchies can migrate independently, because library v2's new-to-old adapter continues to support their unchanged binaries.

**Stage 4: the library switches its own implementation to the new override contract.**

This step is possible once every external implementation that the library continues to support has migrated, including its affected `super` calls.
For this example, that means requiring at least v2 of the external implementor.

```kotlin
// Library v3, supporting external implementor v2
open class Base {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    open fun value(): CharSequence = value@<"string-return-type">() // forwards to the new function

    @SignatureDiscriminator("string-return-type")
    open fun value(): String = "base"
}
```

The external implementor's v2 binaries work with this library without recompilation: neither `Derived` nor `Leaf` depends on `Base`'s former new-to-old adapter or calls its old body through `super`.
For new implementations, overriding the new function is now sufficient because they can inherit the old-to-new forwarder.

This final step ends support for implementations that override only the old function.
If the library must continue supporting those implementations, it must stay at stage 2's override contract, even after some external implementors have migrated.

## Additional tooling

### Reflection

`KCallable::name` continues to report the Kotlin source name.
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
* **Call resolution by signature discriminator.**
  The [migration discussion](#migration-of-open-members) describes retaining the old implementation body and adapting and delegating from the new function.
  This case is dependent on being able to call the implementation with a specific signature discriminator.
* **Reflection API.**
  We need to decide whether the discriminator should be observable through Kotlin reflection.
* **Override and expect/actual ergonomics.**
  Repetition is intentional, but good IDE support is necessary for non-trivial hierarchies.
  Also, there is a question of whether we want to support signature-discriminator-aware actualization to platform declarations.
* **ABI tool format.**
  A common rendering is needed so klib and JVM dumps describe the same discriminator consistently.

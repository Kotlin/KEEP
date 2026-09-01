# Signature discriminators in platform import and export

* **Type**: Design proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, TBD
* **Discussion**: TBD
* **Status**: Internal discussion
* **Depends on**: [Signature discriminators for Kotlin callables](KEEP-xxxx-signature-discriminator.md)
* **Related YouTrack issues**: [KT-31420](https://youtrack.jetbrains.com/issue/KT-31420), [KT-20068](https://youtrack.jetbrains.com/issue/KT-20068), [KT-61323](https://youtrack.jetbrains.com/issue/KT-61323)

## Abstract

This proposal extends signature discriminators to platform import and export.
The core proposal gives a Kotlin callable an additional declaration identity without treating the discriminator as a foreign-language name.
This proposal defines the convenience mapping layer between that Kotlin identity and Java, JavaScript, Objective-C, Swift, and WebAssembly identities.

For export, existing platform naming annotations (`@JvmName`, `@ObjCName`, etc.) may be now applied to all members, including open and abstract ones.
The applied platform name does not participate in Kotlin override or expect/actual matching; the signature discriminator continues to do that job.
The convenience layer here is that the discriminator value can become the platform name, if the user so chooses.

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("stringValue", useAsExportName = true)
    fun value(): String
}
```

The mental model is that such a declaration has the corresponding platform-specific name annotation applied with the discriminator value as the selected name.

For import, a platform importer does not by default use signature discriminators, and the signatures work according to the default Kotlin rules.
The convenience layer here is that we can begin to synthesize a discriminator when the platform identity contains information that ordinary Kotlin signatures lose.
The main concrete application is Objective-C selectors.

For example, these Objective-C selectors could be imported with distinct synthesized discriminators even though their Kotlin parameter types are identical:

```kotlin
@SignatureDiscriminator("objc:tableView:titleForHeaderInSection:") // synthesized
fun tableView(tableView: UITableView, titleForHeaderInSection: NSInteger): String?

@SignatureDiscriminator("objc:tableView:titleForFooterInSection:") // synthesized
fun tableView(tableView: UITableView, titleForFooterInSection: NSInteger): String?
```

The existing `@ObjCSignatureOverride` annotation is retained as source-compatible syntax for adopting an inherited selector-derived discriminator.

## Table of contents

* [Motivation](#motivation)
  * [Readable platform APIs](#readable-platform-apis)
  * [Imported platform identity](#imported-platform-identity)
* [Relationship to the core proposal](#relationship-to-the-core-proposal)
* [Export model](#export-model)
  * [Using the discriminator as a platform name](#using-the-discriminator-as-a-platform-name)
  * [Callable features](#callable-features)
  * [Platform names on open members](#platform-names-on-open-members)
  * [Platform names and inheritance](#platform-names-and-inheritance)
  * [Multiple inherited platform names](#multiple-inherited-platform-names)
  * [Platform clashes](#platform-clashes)
  * [Platform-specific behavior](#platform-specific-behavior)
    * [Kotlin/JVM](#kotlinjvm)
    * [Kotlin/JS](#kotlinjs)
    * [Kotlin/Native: Objective-C and Swift export](#kotlinnative-objective-c-and-swift-export)
    * [Kotlin/Wasm](#kotlinwasm)
* [Import model](#import-model)
  * [Synthesized discriminators](#synthesized-discriminators)
  * [Objective-C import](#objective-c-import)
  * [Overriding imported Objective-C members](#overriding-imported-objective-c-members)
  * [Commonization and klib representation](#commonization-and-klib-representation)
* [Compatibility and migration](#compatibility-and-migration)
  * [Existing Kotlin declarations](#existing-kotlin-declarations)
  * [`@ObjCSignatureOverride`](#objcsignatureoverride)
  * [Export-name inheritance](#export-name-inheritance)
* [Alternatives](#alternatives)
  * [Use the discriminator value as the default export name](#use-the-discriminator-value-as-the-default-export-name)
  * [Add a bare `@SignatureDiscriminator`](#add-a-bare-signaturediscriminator)
  * [Repeat platform names on every override](#repeat-platform-names-on-every-override)
  * [Reject multiple inherited platform names](#reject-multiple-inherited-platform-names)
  * [Immediately replace `@ObjCSignatureOverride`](#immediately-replace-objcsignatureoverride)
  * [One combined signature and interop proposal](#one-combined-signature-and-interop-proposal)
* [Open issues](#open-issues)

## Motivation

### Readable platform APIs

The core `@SignatureDiscriminator` proposal deliberately treats the discriminator as opaque Kotlin identity.
Where an executable format needs a distinct binary symbol, the backend uses a stable Kotlin encoding.
That is sufficient for Kotlin callers but does not necessarily produce a readable or even source-callable name for another language.

On the JVM, for example, the new declaration in a migration may be emitted under a Kotlin-mangled method name:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-result")
    fun value(): String // compiled to a mangled `fun value-<c0ffee>` on the JVM
}
```

To be usable, Java callers need a valid Java name such as `stringValue()`.
Today `@JvmName` is rejected on open and abstract members because it is unclear how it should work with conflicting Kotlin overrides.

For example, the following hierarchy has one Kotlin override signature but two JVM names:

```kotlin
interface A {
    @JvmName("foo")
    fun value(): String
}

interface B {
    @JvmName("bar")
    fun value(): String
}

class C : A, B {
    override fun value(): String = "value"
}
```

Without additional rules, it is unclear whether `C::value` should be emitted as `value`, `foo`, or `bar`, which methods should implement `A` and `B`, and what should happen when a Java subclass overrides only one of those names.
The model below answers these questions by distinguishing a primary platform method from bridges for inherited platform names.

Once Kotlin override matching is enhanced with signature discriminators, a platform name can be allowed on open and abstract members.
It becomes safe to write:

```kotlin
interface API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    fun value(): CharSequence

    @SignatureDiscriminator("string-result")
    @JvmName("stringValue")
    fun value(): String
}

class APIImpl : API {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    override fun value(): CharSequence = value()

    @SignatureDiscriminator("string-result")
    @JvmName("stringValue")
    override fun value(): String = "new"
}
```

The same separation works for other export-related annotations.

### Imported platform identity

In the other direction, some platform declaration signatures contain information that an ordinary Kotlin signature does not preserve.

The main example is Objective-C, which dispatches methods by selector, including argument labels:

```objective-c
- (NSString *)tableView:(UITableView *)tableView
    titleForHeaderInSection:(NSInteger)section;
- (NSString *)tableView:(UITableView *)tableView
    titleForFooterInSection:(NSInteger)section;
```

The imported Kotlin declarations have the same callable name and parameter types and differ only in parameter names:

```kotlin
fun tableView(tableView: UITableView, titleForHeaderInSection: NSInteger): String?
fun tableView(tableView: UITableView, titleForFooterInSection: NSInteger): String?
```

Kotlin/Native currently carries Objective-C-specific conflict and override rules, with `@ObjCSignatureOverride` as an opt-in workaround for these cases.
A synthesized signature discriminator can represent the selector as an ordinary declaration signature, removing the need for special-casing.

## Relationship to the core proposal

This proposal does not change these core rules:

* the discriminator is part of override, Kotlin expect/actual, and binary signature;
* the discriminator does not participate in call resolution;
* ordinary Kotlin overrides and actual declarations repeat an explicit discriminator;
* changing a discriminator is binary incompatible;
* the core backend encoding remains the default Kotlin binary identity.

This proposal adds two controlled extensions:

1. for export, an explicit platform naming annotation may replace the platform binary name; and
2. for import, we may synthesize or adopt a discriminator from a platform declaration's binary identity.

The discriminator and platform name remain separate values.

## Export model

In this section `@ExportName("name")` denotes the platform's existing naming mechanism, such as:

* `@JvmName("name")` for JVM functions and accessors;
* `@JsName("name")` for JavaScript names;
* `@ObjCName(name = "name")` and `@ObjCName(swiftName = "name")` for Objective-C and Swift names; or
* the `name` argument of `@WasmExport` or `@WasmImport` for Wasm.

> Important: `@ExportName` is notation used by this proposal, not a new annotation.

### Using the discriminator as a platform name

This proposal extends the core annotation with an export convenience parameter:

```kotlin
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class SignatureDiscriminator(
    val value: String,
    val useAsExportName: Boolean = false,
)
```

Only `value` participates in Kotlin declaration identity.
`useAsExportName` selects the primary platform name of this declaration and is not considered by conflicting-overload, override, or expect/actual matching.

When `useAsExportName` is `true`, each platform maps `value` to its binary callable name as described in [Platform-specific behavior](#platform-specific-behavior).
The parameter does not export a declaration that would not otherwise be exported.
If `value` is not legal on an affected platform, that platform reports the same diagnostic as its explicit naming annotation.

> Note: alternatively, you could consider
> 
> ```
> @SignatureDiscriminator("foo", useAsExportName = true)
> fun bar(): String = ...
> ```
> 
> as a shorter spelling for
> 
> ```
> @SignatureDiscriminator("foo")
> @ExportName("foo")
> fun bar(): String = ...
> ```

An explicit platform-specific name takes precedence over `useAsExportName` on that platform.
This permits a common default with a platform-specific override.

### Callable features

Each callable has two main features which are important w.r.t. how it's exported.

* Its signature together with the signature discriminator;
* Its platform name, selected by an explicit `@ExportName`, by `useAsExportName`, or by the platform's default encoding.

For each callable `C`, we also have a set of callables `S` which are overridden by it (which have the same signature).

### Platform names on open members

As `@SignatureDiscriminator` gives us a way to disambiguate callables in complicated cases (e.g., with inheritance), we allow changing platform name via `@ExportName` for open or abstract members.

```kotlin
interface API {
    @JvmName("stringValue")
    fun value(): String
}

// or

interface API {
    @SignatureDiscriminator("string-result")
    @JvmName("stringValue")
    fun value(): String
}
```

### Platform names and inheritance

When a callable `C` with platform name `N_C` overrides another callable `Q` with platform name `N_Q`, it creates *two* platform entities: the `N_C`-named primary callable and the `N_Q`-named secondary bridge.
To avoid this, one should prefer to also set the platform name via `@ExportName` to `N_Q` when inheriting from such callables.

```kotlin
interface API {
    @SignatureDiscriminator("string-result")
    @JvmName("stringValue")
    fun value(): String
}

class APIImpl : API {
    @SignatureDiscriminator("string-result")
    override fun value(): String = "new" // generates both a Kotlin-mangled `value-<...>` and a `stringValue` bridge on the JVM
}

// or

interface API {
    @SignatureDiscriminator("string-result")
    @JvmName("stringValue")
    fun value(): String
}

class APIImpl : API {
    @SignatureDiscriminator("string-result")
    @JvmName("stringValue")
    override fun value(): String = "new" // generates only the `stringValue` function on the JVM
}
```

Where the target supports final methods, bridges should be final to discourage platform subtypes from overriding only one view of the Kotlin member.

### Multiple inherited platform names

When one Kotlin callable inherits different platform names for the same signature, it is recommended to select one of them as primary via `@ExportName`.

```kotlin
interface A {
    @SignatureDiscriminator("bar-member")
    @JvmName("foo")
    fun bar()
}

interface B {
    @SignatureDiscriminator("bar-member")
    @JvmName("baz")
    fun bar()
}

class Impl : A, B {
    @SignatureDiscriminator("bar-member")
    @JvmName("baz")
    override fun bar() {} // generates both primary `baz` and bridge `foo` functions on the JVM 
}
```

Omitting `@JvmName` on `Impl::bar` is allowed, but would create *three* platform entities: a primary Kotlin-mangled `bar-<...>` function and two bridges, `baz` and `foo`.

### Platform clashes

An explicit platform name can make declarations with different discriminators clash, and this is expected.

```kotlin
@SignatureDiscriminator("first")
fun <T> execute(x: () -> T) {}

@SignatureDiscriminator("second")
fun execute(x: () -> Any?) {}

// vs

@SignatureDiscriminator("first")
@JvmName("run")
fun <T> execute(x: () -> T) {}

@SignatureDiscriminator("second")
@JvmName("run")
fun execute(x: () -> Any?) {}
```

This is expected: the guarantee that different discriminators are free from platform clashes holds only for compiler-generated platform names.
Once one uses `@ExportName`, they are responsible for platform clashes.

### Platform-specific behavior

#### Kotlin/JVM

For a function with `useAsExportName = true`, the discriminator value is used verbatim as its JVM method name, as if `@JvmName(value)` were present.
The value must satisfy the existing `@JvmName` validity rules.

```kotlin
@SignatureDiscriminator("stringValue", useAsExportName = true)
fun value(): String = "value" // JVM: stringValue()Ljava/lang/String;
```

With `useAsExportName = false`, no implicit `@JvmName` is applied.
The JVM backend instead uses the core proposal's ABI-stable Kotlin encoding of the source name and discriminator for the binary method name, analogous to value-class or internal-name mangling:

```kotlin
@SignatureDiscriminator("string-result")
fun value(): String = "value" // JVM: compiler-generated `value-<...>()Ljava/lang/String;`
```

The exact encoding will be defined by the core backend specification before stabilization.

For a property, with `useAsExportName = true`, the value is treated as the exported property base name.
The usual JVM conventions produce `getX` for a getter and `setX` for a mutable property's setter; a directly exposed backing field uses the value itself.
For example, `@SignatureDiscriminator("stringItem", useAsExportName = true) var item: String` produces `getStringItem` / `setStringItem` accessors and `stringItem` field.

With `useAsExportName = false`, the core proposal's encoding applies independently to each generated accessor or field.

#### Kotlin/JS

For Kotlin/JS, `useAsExportName = true` has the same naming effect as `@JsName(value)`.
The discriminator value becomes the generated JavaScript property or function name and, when the declaration is exported, its JavaScript API name.
It must satisfy the existing `@JsName` identifier restrictions.

This option does not imply `@JsExport`; it only selects the name if JavaScript code is generated or exported for the declaration.
An explicit `@JsName` takes precedence.
Klib linkage continues to use the discriminator as Kotlin identity independently of the JavaScript name.

With `useAsExportName = false`, no implicit `@JsName` is applied.
The core proposal scheme is used then.

#### Kotlin/Native: Objective-C and Swift export

For Objective-C export, the discriminator value becomes the selector base.
Parameter labels continue to follow the existing exporter rules and parameter-level `@ObjCName` annotations:

```kotlin
@SignatureDiscriminator("stringValue", useAsExportName = true)
fun value(index: Int): String = "value"
// Objective-C selector: stringValueIndex:
```

The Swift-facing name remains derived from the Kotlin source name, so the same declaration is presented as `value(index:)` through `NS_SWIFT_NAME`.
This keeps the discriminator responsible for the Objective-C runtime identity while retaining an idiomatic Swift source API.
An explicit `@ObjCName(name = ...)` overrides the selector base, and `@ObjCName(swiftName = ...)` independently overrides the Swift-facing name.

For a property, the value is the Objective-C property/getter base and the setter follows the normal `setX:` convention.
Direct Swift export likewise retains the Kotlin source name.
If two declarations map to the same Swift signature, the exporter reports a clash and requires an explicit Swift name.

With `useAsExportName = false`, no implicit `@ObjCName` is applied.
The Objective-C selector and Swift-facing name are derived using the core proposal's encoding.

#### Kotlin/Wasm

For all intents and purposes, Kotlin/Wasm works the same way as [Kotlin/JS](#kotlinjs).

## Import model

### Synthesized discriminators

A platform import may attach a synthesized discriminator when two native declarations would otherwise merge to the same Kotlin declaration signature.

The synthesized value must be:

* deterministic across compilations;
* preserved as if `@SignatureDiscriminator` was applied to the corresponding declaration; and
* namespaced so it cannot accidentally equal a user-authored discriminator.

Ordinary imported declarations that are already distinguishable by Kotlin rules do not acquire a separate discriminator value merely because they came from another language.
However, if a platform import decides to also generate a discriminator for such cases, it could do so.

### Objective-C import

This is most useful for Objective-C import, where parameter names (selector) are an important part of a callable signature.
The importer records a selector-derived discriminator for methods whose signature requires it:

```kotlin
// Imported declarations
@SignatureDiscriminator("objc:tableView:titleForHeaderInSection:")
fun tableView(tableView: UITableView, titleForHeaderInSection: NSInteger): String?

@SignatureDiscriminator("objc:tableView:titleForFooterInSection:")
fun tableView(tableView: UITableView, titleForFooterInSection: NSInteger): String?
```

These declarations are handled under the general discriminator-aware rules.

### Overriding imported Objective-C members

Existing source compatibility is preserved as follows.

When an override has exactly one compatible imported Objective-C member, the compiler adopts the inherited selector-derived discriminator implicitly.
No annotation is required, matching existing Kotlin/Native source behavior.

When a class implements multiple imported members that collapse to a conflicting Kotlin overload set, each override uses the existing `@ObjCSignatureOverride` annotation:

```kotlin
class DataSource : NSObject(), UITableViewDataSourceProtocol {
    @ObjCSignatureOverride
    override fun tableView(
        tableView: UITableView,
        titleForHeaderInSection: NSInteger,
    ): String? = "Header"

    @ObjCSignatureOverride
    override fun tableView(
        tableView: UITableView,
        titleForFooterInSection: NSInteger,
    ): String? = "Footer"
}
```

Under this proposal, `@ObjCSignatureOverride` has a precise meaning: use the selector-derived discriminator of the inherited Objective-C member.
It does not create a new discriminator and remains inapplicable when no Objective-C member is overridden.

One could use an explicit form if needed:

```kotlin
@SignatureDiscriminator("objc:tableView:titleForFooterInSection:")
override fun tableView(
    tableView: UITableView,
    titleForFooterInSection: NSInteger,
): String? = "Footer"
```

## Compatibility and migration

### `@ObjCSignatureOverride`

`@ObjCSignatureOverride` has shipped in Kotlin 2.0 with source retention and deliberately narrow applicability.
This proposal does not deprecate it.
Existing annotated overrides continue to compile and acquire the selector-derived identity described above.

The compiler may offer a quick-fix to replace it with an explicit inherited discriminator only in rare ambiguity cases; the `@ObjCSignatureOverride` marker annotation remains the preferred spelling otherwise.

### Platform round trips

The Kotlin-platform-Kotlin inheritance round trip is not always reversible.
If a Java or JavaScript subclass overrides two bridges independently, a later Kotlin subclass sees distinct platform members and cannot automatically merge them back into one Kotlin declaration.
This follows from exposing one Kotlin member under multiple platform identities.

In this case, Kotlin subclass should explicitly override such callable.

## Alternatives

### Use the discriminator value as the export name by default

This would make `@SignatureDiscriminator("string-result")` automatically behave like every platform naming annotation.

It is rejected because:

* a string that is good opaque identity is not necessarily a good or legal Java, JavaScript, Objective-C, Swift, or WebAssembly name;
* changing a foreign-language name should not change Kotlin-to-Kotlin linkage;
* one value cannot express Objective-C selector and Swift source-name distinctions cleanly; and
* the behavior would re-couple the two layers separated by the core proposal, even when this is not needed by the end user.

Explicit platform annotations or `useAsExportName = true` make the ABI commitment visible on the API surface.

### One combined signature and interop proposal

[KEEP-0302](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0302-multiplatform-binary-signature.md) `@BinarySignatureName` combined declaration identity, platform naming, and Objective-C selectors.

These concerns have different compatibility risks and implementation owners.
The split allows the core binary-evolution mechanism to be reviewed independently while this proposal handles platform-specific rollout, bridging, and metadata migration.

Together, the two proposals refine and replace the combined KEEP-0302 design.

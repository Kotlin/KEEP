# Explicit API mode

* **Type**: Design proposal
* **Authors**: Ilya Gorbunov, Leonid Startsev
* **Contributors**: Roman Elizarov, Vsevolod Tolstopyatov
* **Status**: Implemented in 1.4
* **Original proposal and discussion**: [KEEP-45](https://github.com/Kotlin/KEEP/issues/45)

## Synopsis

Provide an 'Explicit API' mode in the compiler which helps library authoring.
Such mode should prevent delivery of unintended public API/ABI to the clients by requiring explicit visibility modifier and explicit return types for public declarations.

## Motivation

There were a couple of hot discussions about the default visibility before release:

* https://discuss.kotlinlang.org/t/public-by-default-for-classes/110
* https://discuss.kotlinlang.org/t/kotlins-default-visibility-should-be-internal/1400

While such a decision is convenient for application development, the main concern against public-by-default visibility was that it becomes too easy for library authors to expose something accidentally, release it, and then have to make breaking changes to hide it back.

## Proposal

Introduce 'Explicit API' compiler mode. Compilation in such mode differs from the default mode in the following aspects:

* Compiler requires you to specify explicit visibility for a declaration when leaving default visibility would result in exposing that declaration to the public API.

* Compiler requires you to specify the explicit type of property/function when it is exposed to the public/published API.

* Compiler requires you to explicitly [propagate experimental status](https://kotlinlang.org/docs/reference/experimental.html#propagating-use) for functions which contain experimental types in the signature.

* Compiler warns you when declaration exposed to public API does not have a KDoc.

### Public API definition

#### Classes

A class is considered to be effectively public if all of the following conditions are met:

 - it has one of the following visibilities in Kotlin:
    - no visibility
    - *public*
    - *protected*
 - it isn't a local class
 - in case if the class is a member in another class, it is contained in the *effectively public* class
 - in case if the class is a protected member in another class, it is contained in the *non-final* class

#### Members

A member of the class (i.e. a field or a method) is considered to be effectively public
if all of the following conditions are met:

 - it has one of the following visibilities in Kotlin:
    - no visibility
    - *public*
    - *protected*

    > Note that Kotlin visibility of a field exposed by `lateinit` property is the visibility of its setter.

 - in case if the member is protected, it is contained in *non-final* class

### Published API

If a declaration has *internal* visibility modifier, and the declaration itself or its containing class is annotated with `PublishedApi`, and all other conditions from previous sections are met, it is considered **published** API.

As with public API, you should avoid making [binary incompatible changes](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/binary-compatibility-validator/ReadMe.md#what-makes-an-incompatible-change-to-the-public-binary-api) to published API.

However, published API is usually not visible in the sources from the point of view of a library client. Therefore, the compiler in 'Explicit API' mode will not complain about missing KDoc or missing visibility modifier (because it is `internal` anyway). Explicit return type is still required for published API to prevent implementation details exposure.

### Experimental API

When one is writing a library, they should use `@Experimental(...)` annotation to propagate the experimental status of types they use.
If experimental types are used as implementation details across all library, it might be convenient to mark the whole module with `-Xuse-experimental`.
In that case, it would be easy to forget to mark the corresponding public API as propagative.
Therefore, in Explicit API mode, the compiler would still require explicit `@Experimental` or `@UseExperimental` annotation on a declaration with experimental types in the signature, even if the whole module accepts experimental status via `-Xuse-experimental`.

### Inspection exclusions

After careful review, we decided that some declarations should not require explicit visibility modifiers even in 'Explicit API' mode for the sake of readability, simplicity, and common sense. These declarations are:

1. Primary constructors

    Because explicit visibility also requires you to insert keyword `constructor`.

2. Properties of data classes and annotations

    Because such classes usually fit in one line of code and do not profit much from information hiding.

3. Methods marked with `override`

    Because default visibility for such methods is the visibility of an overridden method.

4. Property getters and setters

    Because getters can't change visibility and setter-only explicit visibility looks ugly.

However, if you still want to insert an explicit visibility modifier for such declarations, it would not be marked as redundant by IDE.

## Implementation

This mode is enabled by the compiler flag `-Xexplicit-api={strict|warning}`. `strict` state of the flag means that compiler will issue errors when public API declaration does not have explicit visibility or explicit return type.
`warning` means that the compiler will issue warnings (this will help migration).
Note: missing KDoc is always a warning, regardless of the state of the flag.

To ease setting up this mode, a DSL would be provided in Kotlin Gradle plugin:

```gradle
kotlinOptions {
    explicitApi()
    // or
    explicitApi = ExplicitApiMode.Strict
    // or
    explicitApiWarning()
    // or
    explicitApi = ExplicitApiMode.Warning
}
```
Explicit mode enabled here would not affect test sources.

The exact place of these methods (top-level `kotlinOptions`, or compilation, or particular source set) is TBA. Maven plugin option is TBD.

Kotlin plugin in IntelliJ IDEA would recognize that explicit mode is enabled and would offer corresponding intentions and quick-fixes.

The following **IDE** inspections would be disabled either because they're redundant or duplicated in the compiler:

* 'Redundant visibility modifier' for public API declarations
* 'Public API declaration has implicit return type' (would be replaced with compiler diagnostic)
* 'Missing KDoc comment for public declaration' (would be replaced with compiler diagnostic)

Effectively, 'disabled in IDE/replaced with compiler diagnostic' means that you would not be able to control these inspections via 'Preferences - Editor - Inspections'.

## Alternatives

Besides embedding such mode as a compiler flag, the following alternatives were considered and discarded:

1. Compiler plugin

    While it is possible to make this functionality as a separate plugin, the lack of stability and consistency in the compiler plugin API (because there is no official public definition of plugin API/compiler surface, actually) would require a lot of efforts to support this relatively small feature.

2. External tool

    An external tool like a style checker may look like a proper solution for this problem, but it requires much more additional setup than one DSL parameter, which pushes adoption back. Another big problem is that such tools re-analyze whole code one more time, which leads to increased build times, big size of the tool, or incorrect results.

## Compatibility

The explicit mode doesn't change the semantics of the correct code and does not affect bytecode generation, so it's safe from the standpoint of compatibility.
For example, if some code was compiled once in the explicit mode and later without explicit mode, the result should be the same.

However, this mode could make previously correct code compile with errors (the code that had the default visibility declarations and inferred types in public API).
That effect is similar to the "Treat warning as errors" option; but the separate compiler flag allows fine-grained control (since we can't turn _particular_ warnings into errors).
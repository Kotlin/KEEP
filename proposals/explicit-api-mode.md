# Explicit API mode

* **Type**: Design proposal
* **Author**: Ilya Gorbunov
* **Contributors**: Leonid Startsev, Roman Elizarov
* **Status**: Prototype
* **Original proposal and discussion**: [KEEP-45](https://github.com/Kotlin/KEEP/issues/45)

## Synopsis

Provide an 'Explicit API' mode in the compiler which helps library authoring.
Such mode should prevent delivery of unintended pubic API/ABI to the clients by requiring explicit visibility and return types on declarations.

## Motivation

There were a couple of hot discussions about the default visibility before release:

* https://discuss.kotlinlang.org/t/public-by-default-for-classes/110
* https://discuss.kotlinlang.org/t/kotlins-default-visibility-should-be-internal/1400

While such a decision is convenient for application programming, the main concern against public-by-default visibility was that it becomes too easy for library authors to expose something accidentally, release it, and then have to make breaking changes to hide it back.

## Proposal

Introduce 'Explicit API' compiler mode. Compilation in such mode differs from the default mode in the following aspects:

* Compiler requires you to specify explicit visibility for a declaration when leaving default visibility would result in exposing that declaration to the public API surface.

* Compiler requires you to specify the explicit type of property/function when it is exposed to the public API surface.

* Compiler warns you when exposed to public API surface declaration does not have a KDoc.

See the definition of public API [here](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/binary-compatibility-validator/ReadMe.md#what-constitutes-the-public-api).

### Inspection exclusions

After careful review, we decided that some declarations should not require explicit visibility modifiers even in 'Explicit API' mode for the sake of readability, simplicity, and common sense. These declarations are:

1. Primary constructors

    Because explicit visibility also requires you to insert keyword `constructor`.

2. Properties of data classes

    Because such classes usually fit in one line of code and do not profit much from information hiding.

3. Methods marked with `override`

    Because default visibility for such methods is the visibility of an overridden method.

4. Property getters and setters

    Because getters can't change visibility and setter-only explicit visibility looks ugly.

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

* Redundant visibility modifier
* Public API declaration has implicit return type (would be replaced with compiler diagnostic)
* Missing KDoc comment for public declaration (would be replaced with compiler diagnostic)

Effectively, 'disabled in IDE/replaced with compiler diagnostic' means that you would not be able to control these inspections via 'Preferences - Editor - Inspections'.

## Alternatives

Besides embedding such mode as a compiler flag, the following alternatives were considered and discarded:

1. Compiler plugin

    While it is possible to make this functionality as a separate plugin, the lack of stability and consistency in the compiler plugin API (because there is no official public definition of plugin API/compiler surface, actually) would require a lot of efforts to support this relatively small feature.

2. External tool

    An external tool like a style checker may look like a proper solution for this problem, but it requires much more additional setup than one DSL parameter, which pushes adoption back. Another big problem is that such tools re-analyze whole code one more time, which leads to increased build times, big size of the tool, or incorrect results.

## Compatibility

The explicit mode doesn't change the semantics of the correct code and does not affect bytecode generation, so it's safe from the standpoint of compatibility.
For example, if some code was compiled once in explicit mode and later without explicit mode, the result should be the same.

However, this mode could make previously correct code to compile with errors (the code that had default visibility declarations and inferred types in public API).
That effect is similar to the "Treat warning as errors" option; but the separate compiler flag allows fine-grained control (since we can't turn _particular_ warnings into errors).
# Locale-agnostic case conversions by default

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Contributors**: Ilya Gorbunov
* **Status**: Implemented in Kotlin 1.5
* **Prototype**: Implemented
* **Discussion**: [KEEP-223](https://github.com/Kotlin/KEEP/issues/223)
* **Relates issues**: [KT-40292](https://youtrack.jetbrains.com/issue/KT-40292), [KT-43023](https://youtrack.jetbrains.com/issue/KT-43023)

## Summary

Make case conversion functions rely on the invariant locale by default.

## Current API review

Currently, the standard library provides the following common extension functions:

* `fun String.toLowerCase(): String`
* `fun String.toUpperCase(): String`
* `fun String.capitalize(): String`
* `fun String.decapitalize(): String`

The functions above convert letters of the receiver `String` using the rules of:

- the default system locale in Kotlin/JVM;
- the invariant locale in other Kotlin platforms.

Kotlin/JVM additionally provides overloads that allow specifying the locale explicitly:

* `fun String.toLowerCase(locale: Locale): String`
* `fun String.toUpperCase(locale: Locale): String`
* `fun String.capitalize(locale: Locale): String`
* `fun String.decapitalize(locale: Locale): String`

`Char` has the following locale-agnostic case conversion functions:

* `fun Char.toLowerCase(): Char` - Common
* `fun Char.toUpperCase(): Char` - Common
* `fun Char.toTitleCase(): Char` - Kotlin/JVM

## Motivation

Our researches show that people often use locale-sensitive functions mentioned above without realizing the fact that their code behaves differently
in different geographies/platform locale settings.

[The caution from Java team](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/String.html#toUpperCase()) highlights significance of the issue:
>Note: This method is locale sensitive, and may produce unexpected results if used for strings that are intended to be interpreted locale independently.
>Examples are programming language identifiers, protocol keys, and HTML tags. For instance, `"title".toUpperCase()` in a Turkish locale returns `"TİTLE"`,
>where `'İ'` (`'\u0130'`) is the `LATIN CAPITAL LETTER I WITH DOT ABOVE` character. To obtain correct results for locale insensitive strings, use `toUpperCase(Locale.ROOT)`.

Bug report related to the caution: https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6208680

However, documentation doesn't always prevent misuse. See bug reports related to this issue in big projects:
* https://issues.apache.org/jira/browse/SPARK-20156
* https://github.com/gradle/gradle/issues/1506

Related blog posts:
* https://lotusnotus.com/lotusnotus_en.nsf/dx/dotless-i-tolowercase-and-touppercase-functions-use-responsibly.htm
* https://javapapers.com/core-java/javas-tolowercase-has-got-a-surprise-for-you/

To overcome the issue we would like to deprecate the current API and introduce new locale-agnostic functions.

## Description

First, we introduce new case conversion functions that do not depend implicitly on the default locale:

```kotlin
// using the invariant locale
fun String.uppercase(): String
fun String.lowercase(): String

// using the invariant locale, possible multi-char result
fun Char.lowercase(): String
fun Char.uppercase(): String
fun Char.titlecase(): String

// using the invariant locale, char to single char conversion
fun Char.lowercaseChar(): Char
fun Char.uppercaseChar(): Char
fun Char.titlecaseChar(): Char
```

In Kotlin/JVM we additionally introduce overloads with an explicit `Locale` parameter.

```kotlin
// using the specified locale
fun String.uppercase(Locale): String
fun String.lowercase(Locale): String

// using the specified locale, possible multi-char result
fun Char.lowercase(Locale): String
fun Char.uppercase(Locale): String
fun Char.titlecase(Locale): String
```

Then we deprecate the existing `toUpperCase/toLowerCase` functions with the following replacements:
```kotlin
Char.toUpperCase() -> Char.uppercaseChar()
Char.toLowerCase() -> Char.lowercaseChar()
Char.toTitleCase() -> Char.titlecaseChar()

// in Kotlin/JVM
String.toUpperCase() -> String.uppercase(Locale.getDefault())
String.toLowerCase() -> String.lowercase(Locale.getDefault())
String.toUpperCase(locale) -> String.uppercase(locale)
String.toLowerCase(locale) -> String.lowercase(locale)
// in other targets
String.toUpperCase() -> String.uppercase()
String.toLowerCase() -> String.lowercase()
```

For the functions `capitalize` and `decapitalize`, we're going to provide a more orthogonal replacement.
First, we introduce a function that allows to transform the first char of a string with the given function
that returns either new `Char`, or new `CharSequence`:
```kotlin
fun String.replaceFirstChar(transform: (Char) -> Char): String
fun String.replaceFirstChar(transform: (Char) -> CharSequence): String
```

Then we can combine the function above with the char case transforming functions to achieve the following replacements:
```kotlin
// in all targets
String.capitalize()   -> String.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it }
String.decapitalize() -> String.replaceFirstChar { it.lowercase() }

// in JVM
String.capitalize()         -> String.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it }
String.capitalize(locale)   -> String.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it }
String.decapitalize()       -> String.replaceFirstChar { it.lowercase(Locale.getDefault()) }
String.decapitalize(locale) -> String.replaceFirstChar { it.lowercase(locale) }
```

The replacements are verbose, but preserve the behavior as close as possible. The resulting code can be simplified further
if a more simple behavior is desired, for example to `String.replaceFirstChar { it.uppercaseChar() }`.

An additional benefit we get by renaming the functions `toUpperCase/toLowerCase` is that they become more consistent
with the existing Kotlin naming conventions.
In Kotlin, functions starting with the preposition `to` typically convert the receiver to an instance of another type,
which is not the case with the case conversion functions.

## Dependencies

Implementing `Char.titlecase` function in platforms other than Kotlin/JVM would require Unicode title case mapping tables.

## Placement

- module `kotlin-stdlib`
- package `kotlin.text`

## Reference implementation

The reference implementation is provided in the pull request [PR #3780](https://github.com/JetBrains/kotlin/pull/3780).

## Naming

Alternative naming suggestions are discussable.

## Compatibility impact

The introduced functions will be marked with `@ExperimentalStdlibApi` until the next major release of Kotlin, 1.5.
With release of Kotlin 1.5 the new functions will become stable, and the old functions will become deprecated.

Previously compiled programs and libraries that used the deprecated functions will still be able to run with Kotlin 1.5 and further.

### Receiver types

The operations could be introduced for `StringBuilder` too, but that is out of scope of this proposal.

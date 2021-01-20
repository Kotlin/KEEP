# Locale-agnostic text processing by default

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Status**: Under consideration
* **Prototype**: Implemented
* **Discussion**: [KEEP-223](https://github.com/Kotlin/KEEP/issues/223)
* **Related issues**: [KT-43023](https://youtrack.jetbrains.com/issue/KT-43023)

## Summary

Make text processing API locale-agnostic by default.

## Current API review

Currently, the standard library provides the following сommon extension functions: 
* `fun String.toLowerCase(): String`
* `fun String.toUpperCase(): String`
* `fun String.capitalize(): String`
* `fun String.decapitalize(): String`

The functions above convert letters of the receiver `String` using the rules of the default locale.

Kotlin/JVM additionally provides overloads to specify the locale to be used:
* `fun String.toLowerCase(locale: Locale): String`
* `fun String.toUpperCase(locale: Locale): String`
* `fun String.capitalize(locale: Locale): String`
* `fun String.decapitalize(locale: Locale): String`

`Char` has following locale-agnostic extension functions:
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

To combat the issue we would like to deprecate current API and introduce new locale-agnostic functions.

## Description

* Deprecate existing locale-dependent function `String.toUpperCase(): String` and introduce locale-agnostic function `String.uppercase(): String` as a replacement 
* Deprecate existing locale-dependent function `String.toLowerCase(): String` and introduce locale-agnostic function `String.lowercase(): String` as a replacement 
* Deprecate existing locale-dependent function `String.capitalize(): String` and introduce locale-agnostic function `String.capitalizeFirst(): String` as a replacement 
* Deprecate existing locale-dependent function `String.decapitalize(): String` and introduce locale-agnostic function `String.decapitalizeFirst(): String` as a replacement 

While the replacements are not equivalent to the existing functions, most likely they are ones that were originally needed.

The new namings will also affect following functions:

* Deprecate existing function `String.toLowerCase(locale: Locale): String` and introduce `String.lowercase(locale: Locale): String` as a replacement
* Deprecate existing function `String.toUpperCase(locale: Locale): String` and introduce `String.uppercase(locale: Locale): String` as a replacement
* Deprecate existing function `String.capitalize(locale: Locale): String` and introduce `String.capitalizeFirst(locale: Locale): String` as a replacement
* Deprecate existing function `String.decapitalize(locale: Locale): String` and introduce `String.decapitalizeFirst(locale: Locale): String` as a replacement
* Deprecate existing function `Char.toLowerCase(): Char` and introduce `Char.lowercase(): Char` as a replacement
* Deprecate existing function `Char.toUpperCase(): Char` and introduce `Char.uppercase(): Char` as a replacement
* Deprecate existing function `Char.toTitleCase(): Char` and introduce `Char.titlecase(): Char` as a replacement

By renaming the functions we get rid of Java legacy names. 
In Kotlin functions starting with preposition `to` typically convert the receiver to an instance of another type.

## Dependencies

No additional dependencies are needed.

## Placement

- module `kotlin-stdlib`
- package `kotlin.text`

## Reference implementation

The reference implementation is provided in the pull request [PR #3780](https://github.com/JetBrains/kotlin/pull/3780).

## Naming

Alternative naming suggestions are welcome.

## Compatibility impact

The introduced functions will be marked with `@ExperimentalStdlibApi` until the next major release of Kotlin, 1.5.
With release of Kotlin 1.5 the new functions will cease to be experimental, and the old functions will become deprecated.

Previously compiled programs that use deprecated functions will still run with Kotlin 1.5.

### Receiver types

The operations could be introduced for `StringBuilder` too, but that is out of this proposal.

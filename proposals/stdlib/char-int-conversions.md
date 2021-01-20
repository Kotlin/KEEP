# Straighten Char-to-code and Char-to-digit conversions out

* **Type**: Standard Library API proposal
* **Authors**: Ilya Gorbunov, Abduqodiri Qurbonzoda
* **Status**: Under consideration
* **Prototype**: Implemented
* **Discussion**: [KEEP-227](https://github.com/Kotlin/KEEP/issues/227)
* **Related issues**: [KT-23451](https://youtrack.jetbrains.com/issue/KT-23451)


## Summary

Deprecate the existing `Char`<=>`Number` conversion functions and introduce new ones to avoid incorrect usages caused
by confusion from the conversion function names.

## Existing API review

Currently, `Char` type has the following conversion functions in the standard library: 

* `fun Char.toByte(): Byte`
* `fun Char.toShort(): Short`
* `fun Char.toInt(): Int`
* `fun Char.toLong(): Long`
* `fun Char.toFloat(): Float`
* `fun Char.toDouble(): Double`

The functions above return the integer value of the `Char` code (the UTF-16 code unit) converted to the specified 
numeric type with either widening, or narrowing conversion.
For the particular numeric type `N`, the value returned from `Char.toN()` is essentially equal to `Char.toInt().toN()`.

Also, there are inverse operations to get the `Char` corresponding to the specified UTF-16 code unit:
* `abstract fun Number.toChar(): Char`
* `fun Byte.toChar(): Char`
* `fun Short.toChar(): Char`
* `fun Int.toChar(): Char`
* `fun Long.toChar(): Char`
* `fun Float.toChar(): Char`
* `fun Double.toChar(): Char`

The value returned from `N.toChar()` is essentially equal to `N.toInt().toChar()` for the particular numeric type `N`.
Note that `Int.toChar()` uses the least significant 16 bits of the receiver `Int` value to represent the resulting `Char`. 

## Motivation

The conversions above are often found confusing. People calling `Char.toInt()` usually expect to get the digit value of char, 
similar to how `String.toInt()` works. Reverse conversions like `Double.toChar()` usually make no sense, 
and it would be more clear if the intent was expressed explicitly by converting the number to `Int` first 
and then getting the character corresponding to that `Int` code.

Some examples of the confusion:

* https://discuss.kotlinlang.org/t/convert-char-to-byte-gives-wrong-result/11548
* https://stackoverflow.com/questions/47592167/how-do-i-convert-a-char-to-int
* https://stackoverflow.com/questions/51961220/what-happens-with-toint
* https://stackoverflow.com/questions/52393028/how-to-convert-character-to-its-integer-value-in-kotlin
* https://stackoverflow.com/questions/57420203/how-to-convert-digit-to-character-in-kotlin
* https://stackoverflow.com/questions/57515225/why-it-kotlin-giving-me-the-wrong-int-value-when-converting-from-a-string
* https://stackoverflow.com/questions/61712411/converting-big-number-into-string-and-then-splitting-into-single-digits-results
* https://blog.jdriven.com/2019/10/converting-char-to-int-in-kotlin/

To alleviate the confusion, we would like to deprecate the conversion functions above 
and introduce new ones with the names that make it clear what the function purpose is. 

## Description

For each `N` type where `N` is `Int`, `Short`, `Long`, `Byte`, `Double`, `Float`
we are going to:

* Deprecate `Char.toN()` functions
* Deprecate `N.toChar()` functions
  
We also need to deprecate `Number.toChar()` function, change its modality from `abstract` to `open`, and provide 
the default implementation of this function, `toInt().toChar()`.

* Introduce functions to get the integer code of a `Char` and to construct a `Char` from the given code.

```kotlin
/**
 * Creates a Char with the specified [code], or throws an exception if the [code] is out of `Char.MIN_VALUE.code..Char.MAX_VALUE.code`.
 * 
 * If the program that calls this function is written in a way that only valid [code] is passed as the argument,
 * using the overload that takes a [UShort] argument is preferable (`Char(intValue.toUShort())`).
 * That overload doesn't check validity of the argument, and may improve program performance when the function is called routinely inside a loop.
 */
fun Char(code: Int): Char

/**
 * Creates a Char with the specified [code].
 */
fun Char(code: UShort): Char

/**
 * Returns the code of this Char.
 *
 * Code of a Char is the value it was constructed with, and the UTF-16 code unit corresponding to this Char.
 */
val Char.code: Int
```
These functions will be proposed as replacements for the deprecated conversions above.
For example: 
```kotlin
char.toInt() -> char.code
char.toShort() -> char.code.toShort()

int.toChar() -> Char(int.toUShort())
short.toChar() -> Char(short.toUShort()) 
```
Currently experimental `UShort` will also become stable in Kotlin 1.5, making the proposed replacements safe.

- Introduce functions to convert a `Char` to the numeric value of the digit it represents:

```kotlin
/**
 * Returns the numeric value of the digit that this Char represents in the specified [radix].
 * Throws an exception if the [radix] is not in the range `2..36` or if this Char is not a valid digit in the specified [radix].
 *
 * A Char is considered to represent a digit in the specified [radix] if at least one of the following is true:
 *  - [isDigit] is `true` for the Char and the Unicode decimal digit value of the character is less than the specified [radix]. In this case the decimal digit value is returned.
 *  - The Char is one of the uppercase Latin letters 'A' through 'Z' and its [code] is less than `radix + 'A'.code - 10`. In this case, `this.code - 'A'.code + 10` is returned.
 *  - The Char is one of the lowercase Latin letters 'a' through 'z' and its [code] is less than `radix + 'a'.code - 10`. In this case, `this.code - 'a'.code + 10` is returned.
 */
fun Char.digitToInt(radix: Int): Int

/**
 * Returns the numeric value of the digit that this Char represents in the specified [radix], or `null` if this Char is not a valid digit in the specified [radix].
 * Throws an exception if the [radix] is not in the range `2..36`.
 *
 * A Char is considered to represent a digit in the specified [radix] if at least one of the following is true:
 *  - [isDigit] is `true` for the Char and the Unicode decimal digit value of the character is less than the specified [radix]. In this case the decimal digit value is returned.
 *  - The Char is one of the uppercase Latin letters 'A' through 'Z' and its [code] is less than `radix + 'A'.code - 10`. In this case, `this.code - 'A'.code + 10` is returned.
 *  - The Char is one of the lowercase Latin letters 'a' through 'z' and its [code] is less than `radix + 'a'.code - 10`. In this case, `this.code - 'a'.code + 10` is returned.
 */
fun Char.digitToIntOrNull(radix: Int): Int?
```

`isDigit` is considered to be `true` for a `Char` if the Unicode general category of the `Char` is "Nd" (`CharCategory.DECIMAL_DIGIT_NUMBER`).

- Introduce an extension function for `Int` to covert the non-negative single digit it represents
to the corresponding `Char` representation.

```kotlin

/**
 * Returns the Char that represents this numeric digit value in the specified [radix].
 * Throws an exception if the [radix] is not in the range `2..36` or if this value is not less than the specified [radix].
 *
 * If this value is less than `10`, the decimal digit Char with code `'0'.code + this` is returned.
 * Otherwise, the uppercase Latin letter with code `'A'.code + this - 10` is returned.
 */
fun Int.digitToChar(radix: Int): Char
```

Similarly to `String.toInt/toIntOrNull()` functions, we will introduce overloads with no arguments as well defaulting `radix` to 10:
- `fun Char.digitToInt(): Int`
- `fun Char.digitToIntOrNull(): Int?`
- `fun Int.digitToChar(): Char`

## Dependencies

- The correct implementation of `Char.digitToInt` relies on knowing all digit ranges among the supported
`Char` values, see the issues [KT-30652](https://youtrack.jetbrains.com/issue/KT-30652) and [KT-39177](https://youtrack.jetbrains.com/issue/KT-39177).

- Deprecating `Number.toChar()` and changing its modality will require a special support in the compiler.

## Placement

- module `kotlin-stdlib`
- packages
    - `kotlin` for `fun Char(code: Int): Char`, `fun Char(code: UShort): Char` and `val Char.code: Int`
    - `kotlin.text` for digit-to-int and digit-to-char conversion functions

## Reference implementation

The reference implementation is provided in the pull request [PR #3969](https://github.com/JetBrains/kotlin/pull/3969).

## Naming

Alternative naming suggestions are welcome.

## Compatibility impact

The introduced functions will be marked with `@ExperimentalStdlibApi` until the next major release of Kotlin, 1.5.
With the release of Kotlin 1.5 the new functions will become stable, and the old Number <-> Char functions will become deprecated.

Previously compiled programs and libraries that used deprecated functions will still be able to run with Kotlin 1.5 and further.

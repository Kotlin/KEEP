# Unambiguous number to/from char conversions

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Status**: Submitted
* **Prototype**: Implemented
* **Discussion**: https://github.com/Kotlin/KEEP/issues/227


## Summary

Deprecate existing number to/from char conversion functions and introduce new ones to make conversions unambiguous.

## Similar API review

Currently, `Char` provides the following member functions in standard library: 
* `fun Char.toByte(): Byte`
* `fun Char.toShort(): Short`
* `fun Char.toInt(): Int`
* `fun Char.toLong(): Long`
* `fun Char.toFloat(): Float`
* `fun Char.toDouble(): Double`

The functions above return the UTF-16 code unit of the receiver `Char` converted to the return number type. 
The returned value from `Char.toN()` is essentially equal to `Char.toInt().toN()` for a particular number type `N`.

Also, there are inverse operations to get the `Char` corresponding to the specified UTF-16 code unit:
* `abstract fun Number.toChar(): Char`
* `fun Byte.toChar(): Char`
* `fun Short.toChar(): Char`
* `fun Int.toChar(): Char`
* `fun Long.toChar(): Char`
* `fun Float.toChar(): Char`
* `fun Double.toChar(): Char`

The returned value from `N.toChar()` is essentially equal to `N.toInt().toChar()` for a particular number type `N`.
Note that `Int.toChar()` uses the least significant 16 bits of the receiver `Int` value to represent the resulting `Char`. 

## Motivation

The conversions above are often found confusing. People calling `Char.toInt()` usually expect to get the digit value of char, 
similar to how `String.toInt()` works. Reverse conversions like `Double.toChar()` usually make no sense, 
and it would be more clear if the intent was expressed explicitly by converting the number to `Int` first 
and then getting the character corresponding to that `Int` code.

Some examples of confusion:
* https://discuss.kotlinlang.org/t/convert-char-to-byte-gives-wrong-result/11548
* https://stackoverflow.com/questions/47592167/how-do-i-convert-a-char-to-int
* https://stackoverflow.com/questions/51961220/what-happens-with-toint
* https://stackoverflow.com/questions/52393028/how-to-convert-character-to-its-integer-value-in-kotlin
* https://stackoverflow.com/questions/57420203/how-to-convert-digit-to-character-in-kotlin
* https://stackoverflow.com/questions/57515225/why-it-kotlin-giving-me-the-wrong-int-value-when-converting-from-a-string
* https://stackoverflow.com/questions/61712411/converting-big-number-into-string-and-then-splitting-into-single-digits-results
* https://blog.jdriven.com/2019/10/converting-char-to-int-in-kotlin/

To combat the issue we would like to make the changes described in the chapter below.

## Description

For each `N` type where `N` is `Int`, `Short`, `Long`, `Byte`, `Double`, `Float`

* Deprecate `Char.toN()` functions
* Deprecate `N.toChar()` functions
* Deprecate `Number.toChar()` function, change its modality from `abstract` to `open`, provide a default implementation = `toInt().toChar()`

Introduce functions to convert `Char` to `Int` based on its code:
```kotlin
/**
 * Creates a Char with the specified [code], or throws an exception if the [code] is out of `Char.MIN_VALUE.code..Char.MAX_VALUE.code`.
 */
fun Char(code: Int): Char

/**
 * Returns the code of this Char.
 *
 * Code of a Char is the value it was constructed with, and the UTF-16 code unit corresponding to this Char.
 */
val Char.code: Int
```

Introduce functions to convert `Char` to the numeric value of the digit it represents:
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
fun Char.digitToInt(radix: Int = 10): Int

/**
 * Returns the numeric value of the digit that this Char represents in the specified [radix], or `null` if this Char is not a valid digit in the specified [radix].
 * Throws an exception if the [radix] is not in the range `2..36`.
 *
 * A Char is considered to represent a digit in the specified [radix] if at least one of the following is true:
 *  - [isDigit] is `true` for the Char and the Unicode decimal digit value of the character is less than the specified [radix]. In this case the decimal digit value is returned.
 *  - The Char is one of the uppercase Latin letters 'A' through 'Z' and its [code] is less than `radix + 'A'.code - 10`. In this case, `this.code - 'A'.code + 10` is returned.
 *  - The Char is one of the lowercase Latin letters 'a' through 'z' and its [code] is less than `radix + 'a'.code - 10`. In this case, `this.code - 'a'.code + 10` is returned.
 */
fun Char.digitToIntOrNull(radix: Int = 10): Int?

/**
 * Returns the Char that represents this numeric digit value in the specified [radix].
 * Throws an exception if the [radix] is not in the range `2..36` or if this value is not less than the specified [radix].
 *
 * If this value is less than `10`, the decimal digit Char with code `'0'.code + this` is returned.
 * Otherwise, the uppercase Latin letter with code `'A'.code + this - 10` is returned.
 */
fun Int.digitToChar(radix: Int = 10): Char
```

`isDigit` is considered to be `true` for a `Char` if the Unicode general category of the `Char` is "Nd" (`CharCategory.DECIMAL_DIGIT_NUMBER`).

## Dependencies

No additional dependencies are needed.

## Placement

- module `kotlin-stdlib`
- package `kotlin.text`

## Reference implementation

The reference implementation is provided in the pull request [PR #3969](https://github.com/JetBrains/kotlin/pull/3969).

## Naming

Alternative naming suggestions are welcome.

## Compatibility impact

The introduced functions will be marked with `@ExperimentalStdlibApi` until the next major release of Kotlin, 1.5.
With release of Kotlin 1.5 the new functions will cease to be experimental, and the old Number <-> Char functions will become deprecated.

Previously compiled programs that use deprecated functions will still run with Kotlin 1.5.

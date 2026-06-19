# Unicode Code Point Support

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Contributors**: Filipp Zhinkin
* **Status**: Public discussion / Experimental in 2.5.0
* **Target issue**: [KT-23251](https://youtrack.jetbrains.com/issue/KT-23251)
* **Discussion**: [#508](https://github.com/Kotlin/KEEP/discussions/508)

## Summary

This proposal introduces support for Unicode code points in Kotlin's standard library, 
allowing for more precise and efficient handling of text data.

## Motivation

Historically, Kotlin inherited the string representation in UTF-16 encoding from JVM, 
where a string is stored as a sequence of 16-bit code units. Each such code unit is represented with the `Char` class.
It allowed modeling a string as a collection of UTF-16 chars with the random access by index.
This encoding has limitations for handling certain Unicode characters, such as those outside the Basic Multilingual Plane (BMP), 
requiring surrogate pairs to represent them. 

The existing `Char`-based API cannot accurately handle tasks requiring character transformation and filtering,
when an input string contains characters represented by surrogate pairs.
For example, the following naive function will not recognize mathematical bold letters in a string like `"𝐀𝐁12"`:

```kotlin
fun String.filterLetters(text: String): String = buildString {
    text.forEach { c: Char -> if (c.isLetter()) append(c) }
}
```

The proposal aims to introduce a new type that can represent individual Unicode code points directly, 
providing a more straightforward and type-safe way to work with Unicode characters in the standard library:

```kotlin
fun String.filterLetters(text: String): String = buildString {
    text.forEachCodePoint { c: CodePoint -> if (c.isLetter()) appendCodePoint(c) }
}
```

## Proposal

### CodePoint class

Unicode code point is basically an integer that fits in 21 bits (in the range `U+0000..U+10FFFF`)
uniquely identifying a character in the Unicode character set.

We propose to introduce a new `CodePoint` value class that wraps an integer code of a Unicode code point and
provides operations to get information about the code point, such as its size in UTF-16 chars, category, name, and other properties.

```kotlin
value class CodePoint(val code: Int) : Comparable<CodePoint> {
    // basic properties
    val size: Int // 1 or 2
    val isBasic: Boolean // size == 1
    val isSupplementary: Boolean // size == 2
    
    // conversions
    fun toSingleChar(): Char
    fun toCharArray(): CharArray
    fun <T> toSurrogatePair(action: (high: Char, low: Char) -> T): T
    fun toSurrogatePair(): Pair<Char, Char>

    // arithmetics
    operator fun plus(other: Int): CodePoint
    operator fun minus(other: Int): CodePoint
    operator fun minus(other: CodePoint): Int
    operator fun inc(): CodePoint
    operator fun dec(): CodePoint

    // ranges
    operator fun rangeTo(other: CodePoint): CodePointRange
    operator fun rangeUntil(other: CodePoint): CodePointRange
    
    companion object {
        fun fromChar(char: Char): CodePoint

        fun fromSurrogatePair(high: Char, low: Char): CodePoint
        fun isSurrogatePair(high: Char, low: Char): Boolean
        
        val MIN_VALUE: CodePoint = CodePoint(0)
        val MAX_VALUE: CodePoint = CodePoint(MAX_CODE_POINT_VALUE)
    }
}

// conversion extensions
fun Int.toCodePoint(): CodePoint
fun Char.toCodePoint(): CodePoint

// Unicode properties
val CodePoint.category: CharCategory
val CodePoint.directionality: CharDirectionality // Kotlin/JVM
fun CodePoint.isDefined(): Boolean
fun CodePoint.isDigit(): Boolean
fun CodePoint.isLetter(): Boolean
fun CodePoint.isLetterOrDigit(): Boolean
fun CodePoint.isISOControl(): Boolean
fun CodePoint.isLowerCase(): Boolean
fun CodePoint.isTitleCase(): Boolean
fun CodePoint.isUpperCase(): Boolean
fun CodePoint.isWhitespace(): Boolean

fun CodePoint.lowercase(): String
fun CodePoint.lowercaseCodePoint(): CodePoint
fun CodePoint.uppercase(): String
fun CodePoint.uppercaseCodePoint(): CodePoint
fun CodePoint.titlecase(): String
fun CodePoint.titlecaseCodePoint(): CodePoint
```

The value of `CodePoint` class can be obtained by:

  - constructing from an integer code in a validating (`CodePoint(code)`) and non-validating way (`code.toCodePoint()`)
  - converting a single BMP character `Char` to a code point: `char.toCodePoint()`, `CodePoint.fromChar(char)`
  - converting a pair of surrogate characters that encodes a code point in supplementary planes: `CodePoint.fromSurrogatePair(highSurrogateChar, lowSurrogateChar)`
  - getting a code point from a char sequence or a char array at or before the specified index: `"👍".codePointAt(0)`

#### Code range validation

The code point code value passed to its constructor is validated to be within the range of valid code point codes, thus ensuring 
that a value of `CodePoint` class always represents a valid code point. 

There are several options to decide which range of code point codes should be considered valid for the `CodePoint` class. 

1. Use the Unicode definition of [Unicode scalar value](https://www.unicode.org/glossary/#unicode_scalar_value), i.e. consider valid
   all code points in range U+0000 to U+10FFFF excluding surrogate code points (U+D800 to U+DFFF). \
   This option has a benefit that a `CodePoint` value can always be encoded as a valid UTF-8 byte sequence. 
   However, strings in Kotlin allow ill-formed UTF-16 sequences where surrogate code points are not properly paired, 
   and obtaining a code point from such a string may thus result in an exception. 
   This will also make the range of codes incontiguous, thus making the code point arithmetics and range operations much more intricate. 

2. Use the Unicode standard's definition of valid [code points](https://www.unicode.org/glossary/#code_point), 
   which includes all code points up to U+10FFFF. \
   This option provides good compatibility with Kotlin and JVM strings, however, code points that are reserved 
   for surrogate code units will be unencodable in UTF-8. The latter problem already exists in Kotlin and JVM strings
   and has its mitigation approach such as using replacement byte sequences.

3. Consider `CodePoint` as a typed wrapper of an `Int` code, thus allowing the entire range of 32-bit Int values. \
   This makes it possible to avoid validation in the `CodePoint` constructor entirely, which can be beneficial in performance-critical scenarios,
   but makes certain operations ill-defined or exception-throwing for invalid code points.

In some use cases the cost of validating the code of a code point constructed may be excessive, 
especially when dealing with large amounts of text data and the code is known to be valid in advance. 
For these use cases, we provide `Int.toCodePoint()` conversion
which takes the lowest 21 bits of the integer and circumvents the validation in the `CodePoint` constructor.

### CodePoint arithmetic

Similar to `Char` type, `CodePoint` type provides arithmetic operations namely `inc()`, `dec()`, `plus()`, `minus()` operators.
These operators take the code point code, add or subtract the operand, and convert the result back to a `CodePoint`.

The second overload of `minus` operator accepts another `CodePoint` and returns the difference between the code point codes.

The arithmetic on code point codes is implemented modulo `MAX_CODEPOINT_VALUE (U+10FFFF) + 1`. 
This allows wrapping around the range of valid code points, so that incrementing the code point with the maximum value 
results in the minimum code point value, and vice versa. This is also consistent with the behavior of `Char` arithmetic.

### Conversions from/to other types

#### `Int`

An integer value with a code point code can be converted to a `CodePoint` in two ways:

- Using the `CodePoint` class constructor accepting integer code value. The constructor validates that the code is in the range of valid code points.
- Using `Int.toCodePoint()` extension function. This function, similar to `Int.toChar()`, takes the value modulo `MAX_CODEPOINT_VALUE (U+10FFFF) + 1` 
  and constructs a code point from the result. Its primary use case is either when the value is validated beforehand to be in the range of valid codes, 
  or when the value is obtained from arithmetic operations on codes, so that the resulting code point is consistent 
  with the arithmetic result on code points themselves.

`CodePoint` can be converted to an integer code value by reading its `code` property.

#### `Char`

A `Char` value can be always converted to a `CodePoint` (using `Char.toCodePoint()` extension function or `CodePoint.fromChar(c)` companion function)
because there is a code point for any code in the Unicode Basic Multilingual Plane.

A surrogate pair of chars encoding a code point from supplementary Unicode planes where the first char is a high surrogate 
and the second char is a low surrogate can be converted to a `CodePoint` using the `CodePoint.fromSurrogatePair(high, low)` companion function.
The function checks if the provided pair of chars is a valid surrogate pair and fails otherwise.

A `CodePoint` can be converted to a single char using `CodePoint.toSingleChar()` function, but only when it represents 
the code in the Unicode Basic Multilingual Plane, otherwise the function fails.

A `CodePoint` from supplementary Unicode planes can be converted to a surrogate pair of chars using `CodePoint.toSurrogatePair()` function.
The function has two overloads: one that returns a pair of `Char` values and the other that takes a function that accepts
two `Char` arguments.

```kotlin
fun toSurrogatePair(): Pair<Char, Char>
fun <T> toSurrogatePair(action: (high: Char, low: Char) -> T): T
```

Finally, `toCharArray()` function returns an array of either one or two characters depending on 
whether the code point is in the basic multilingual Unicode plane or in a supplementary one.

#### `String`

A `CodePoint` can be converted to string representation using `CodePoint.toString()` function.

There are different options what `toString` could return when called on a `CodePoint`:

1. The literal string representation of the code point, so that `codePoint.toString().codePointAt(0) == codePoint`
2. The string describing the code point in the Unicode notation, e.g. `U+1F600`

We believe that the first option would be more convenient for string interpolation and string building and also consistent 
with the `Char.toString()` function.

The second string representation can be convenient for debugging and logging purposes because it does not result in
non-printable and otherwise visually indistinguishable characters. For these cases we could introduce another 
utility function, for example, `CodePoint.toUnicodeString()` or a property `CodePoint.unicodeNotation`. 
The same conversion would also be useful for `Char` type and can be introduced there as well.

### Getting code points from char sequences

#### Obtaining by index

The following extension functions can be used to get a code point at the specified *char* position in a `CharSequence`, a `String`, 
or a `CharArray` or its subarray.

```kotlin
fun String.codePointAt(index: Int): CodePoint
fun String.codePointBefore(index: Int): CodePoint
fun CharSequence.codePointAt(index: Int): CodePoint
fun CharSequence.codePointBefore(index: Int): CodePoint
fun CharArray.codePointAt(index: Int): CodePoint
fun CharArray.codePointBefore(index: Int): CodePoint
```

`codePointAt` returns a code point at the specified position, and `codePointBefore` - a code point directly preceding
the specified position, which may be useful in use cases of iterating over code points in the backward direction.
When using these functions, it's important to provide the position that is not in the middle of a pair of surrogate characters, 
otherwise they will return a code point of one of the surrogate characters in the pair rather than a code point 
that is represented by the surrogate pair itself.

For `CharArray` there are additional overloads that take the end bound of position when looking for a code point in the forward direction 
and the start bound when looking for a code point in the backward direction.
```kotlin
fun CharArray.codePointAt(index: Int, endIndex: Int): CodePoint
fun CharArray.codePointBefore(index: Int, startIndex: Int): CodePoint
```

These functions conflict with already existing extensions with the same name in the `kotlin.text` package which return `Int` code instead, 
so it's required to place them in a separate package which must then be imported explicitly (see [Placement](#Placement) section).
When the `CodePoint` API graduates to stable, these existing functions can be soft-deprecated and the IDE can propose a migration to the new API.

#### Counting code points and advancing position by number of code points

The extension `codePointCount()` provides the way to count the number of code points in a `CharSequence`, `String`, or `CharArray` 
in the specified subrange:

```kotlin
fun String.codePointCount(startIndex: Int = 0, endIndex: Int = this.length): Int
fun CharSequence.codePointCount(startIndex: Int = 0, endIndex: Int = this.length): Int
fun CharArray.codePointCount(startIndex: Int = 0, endIndex: Int = this.size): Int
```

Characters of the incomplete or invalid surrogate pairs, for example, appearing when `startIndex` or `endIndex` fall into 
the middle of a surrogate pair, are counted as one code point each.

`codePointCount` can be used to determine the number of code points available before using the function `offsetByCodePoints` 
which iterativelty finds the position of a code point in a `CharSequence`, a `String`, or a `CharArray` 
relative to the specified position and offset by the specified number of code points 
to the end (for positive `codePointOffset`) or to the start (for negative `codePointOffset`) of the container:

```kotlin
fun String.offsetByCodePoints(index: Int, codePointOffset: Int): Int
fun CharSequence.offsetByCodePoints(index: Int, codePointOffset: Int): Int
fun CharArray.offsetByCodePoints(index: Int, codePointOffset: Int, startIndex: Int = 0, endIndex: Int = this.size): Int
```

#### Iterating over code points sequentially

For a common pattern of iterating over code points in a char sequence, 
we provide `forEachCodePoint` extension function and the `CodePointSequence` class,
which allow iterating over code points in a char sequence one by one.

Without the proposed functions:

```kotlin
var position = 0
while (position < string.length) {
    val codepoint = string.codePointAt(position)
    // do something with codepoint
    position += codepoint.size
}
```

The proposed extensions simplify this pattern as:

```kotlin
string.forEachCodePoint { codepoint ->
    // do something with codepoint
}

string.asCodePointSequence().map { codepoint -> /* do something with code point */ }.forEach { /* process the results of mapping */ }
```

`asCodePointSequence()` is an extension function provided for `CharSequence`, `String`, and `CharArray` receivers:

```kotlin
fun String.asCodePointSequence(): CodePointSequence
fun CharSequence.asCodePointSequence(): CodePointSequence
fun CharArray.asCodePointSequence(startIndex = 0, endIndex = size): CodePointSequence
```

While `codePoints` seems to be a shorter name for this function, it conflicts 
with the existing `codePoints()` member function returning an `IntStream` exposed by `String` on Kotlin/JVM. 
Therefore, an alternative name is proposed.

`CodePointSequence` class is a specialized inheritor of `Sequence<CodePoint>` that returns a specialized iterator 
`CodePointIterator` which is in turn a specialized inheritor of `Iterator<CodePoint>`. 
This allows using all the extension operations on a `Sequence` to transform the results, 
however, iterating would likely cause boxing each `CodePoint` into an object.
For the trivial cases like `for (codePoint in "abc".asCodePointSequence())` the compiler can provide an optimization in future
when it spots the iteration throw statically known `CodePointIterator` class, similar to the existing optimizations
like for `CharIterator` or `IntIterator`.

#### Iterating over code points in both directions

`CodePointSequence` and `CodePointIterator` allow iterating code points only in the forward direction. 
A more advanced iteration interface `CodePointIndexedIterator` covers use cases when it is required to iterate code points
backwards or to operate on code point positions while iterating.

```kotlin
interface CodePointIndexedIterator : CodePointIterator {
    override fun hasNext(): Boolean
    fun hasPrevious(): Boolean
    override fun next(): CodePoint
    fun previous(): CodePoint

    // nextIndex and previousIndex return the char offset of
    // the next/previous code point
    fun nextIndex(): Int
    fun previousIndex(): Int

    fun advanceByCodePoints(codePointOffset: Int)
}
```

The interface is designed similar to [kotlin.collections.ListIterator](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-list-iterator/)
with the difference that its `nextIndex` and `previousIndex` functions return not the ordinal index of the code point being iterated, 
but its char offset from the beginning of the container.

A `CodePointIndexedIterator` can be obtained from `String`, `CharSequence`, or a `CharArray` using the `codePointIterator()` 
extension function. The function accepts a char position index in the container where to begin the iteration 
and also subarray bounds in the `CharArray` overload.

```kotlin
fun String.codePointIterator(index: Int = 0): CodePointIndexedIterator
fun CharSequence.codePointIterator(index: Int = 0): CodePointIndexedIterator
fun CharArray.codePointIterator(index: Int = 0, startIndex: Int = 0, endIndex: Int = size): CodePointIndexedIterator
```

### CodePoint manipulation in string builders

While a `CodePoint` can be appended to a `StringBuilder` or other `Appendable` using its default `append(Any)` function
thanks to `CodePoint.toString()` returning its literal representation, it causes an allocation of a `String`. 
Unfortunately, we cannot provide `append` overload specialized for `CodePoint` argument because we don't control 
the API surface of `StringBuilder` class in JVM and can only provide an extension function 
which the compiler would find less suitable than the `append(Any)` member function.

So instead we provide an extension with a different name which appends a code point as either one or two UTF-16 chars 
without allocating a `String`:

```kotlin
fun <T : Appendable> T.appendCodePoint(value: CodePoint): T
```

An IDE can spot places where `append()` is called with `CodePoint` argument and suggest replacing it with `appendCodePoint()`.

For string builders, we provide other in-place code point manipulation functions, similar to the existing functions
`insert()`, `set()` and `deleteAt()` working with `Char` arguments:

```kotlin
fun StringBuilder.insertCodePointAt(index: Int, value: CodePoint): StringBuilder
fun StringBuilder.setCodePointAt(index: Int, value: CodePoint): StringBuilder
fun StringBuilder.deleteCodePointAt(index: Int): StringBuilder
```

## Dependencies

The proposed API is mostly implementable without additional dependencies, but on Kotlin/JVM it delegates 
to the `java.lang.Character` to obtain Unicode information about code points. 
In other targets supported by Kotlin, getting such information will require bundling Unicode data tables 
into the Standard Library for these targets like it is currently done for BMP characters, but extended to all Unicode
characters.

## Placement

The proposed code point API will be placed in the new `kotlin.text.unicode` package of the Kotlin Standard Library.
While `CodePoint` class and related API has the same significance as the existing `Char` class and can benefit 
from being placed in the existing `kotlin` and `kotlin.text` packages beside that `Char`-related API, 
a dedicated package that is not imported by default allows better avoiding name clashes with existing APIs 
including those already in the Standard Library.

## Future advancements

- Accompany more existing API accepting `Char` arguments with `CodePoint` overloads
- Consider introducing behavior changes to the existing functions that currently iterate over strings `Char`-by-`Char`
  and thus do not handle supplementary characters correctly
- Introduce literal syntax for `CodePoint` values
- Add `CodePoint` support across existing JVM and Kotlin ecosystem libraries, such as `kotlinx.serialization`

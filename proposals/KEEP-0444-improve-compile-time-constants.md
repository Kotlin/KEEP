# Improve compile-time constants

* **Type**: Design proposal
* **Author**: Florian Freitag
* **Contributors**: Ivan Kylchik, Pavel Kunyavskiy, Mikhail Zarechenskii, Alejandro Serrano Mena, Marat Akhin
* **Discussion**: [#453](https://github.com/Kotlin/KEEP/discussions/453)
* **Status**: Public discussion
* **Related YouTrack issues**:  [KT-22505](https://youtrack.jetbrains.com/issue/KT-22505) , [KT-51065](https://youtrack.jetbrains.com/issue/KT-51065) , [KT-7774](https://youtrack.jetbrains.com/issue/KT-7774), [KT-80562](https://youtrack.jetbrains.com/issue/KT-80562/Investigate-that-compile-time-functions-have-a-different-result-if-they-were-called-on-the-runtime)

## Abstract

This proposal describes the current state of Kotlin's compile-time constants, outlines some inconsistencies within the feature and proposes changes to fix them.

## Table of contents

- [Motivation](#motivation)
- [Proposal](#proposal)
  - [Documentation](#documentation)
  - [Unsigned Integers](#unsigned-integers)
  - [Functions from stdlib](#functions-from-stdlib)
  - [Improved Error Messages](#improved-error-messages)
  - [Out of scope](#out-of-scope)
- [Technical Details](#technical-details)
- [Concerns](#concerns)
- [Future Evolutions](#future-evolutions)
- [Appendix](#appendix)
  - [Currently supported operations on primitives](#currently-supported-operations-on-primitives)
  - [Currently supported operations on String](#currently-supported-operations-on-primitives)
  - [Additional operations on unsigned integers](#additional-operations-on-unsigned-integers)
  - [Additions from the standard library](#additions-from-the-standard-library)

## Motivation

Kotlin has the concept of compile-time constants (`const val`) which are properties that are read-only and known at compile-time. As such they can be used inside a *constant context*, places in the languages where the compiler needs to be able resolve a value, like arguments of annotations or the initializer of other compile-time constants.

Inside such a constant context Kotlin currently only allows primitive types, unsigned integers and strings.

On primitive types most operations and methods that result in one of the mentioned types are allowed, there is a [full list in the Appendix](#currently-supported-operations-on-primitives).

For unsigned types, no operations are currently supported, resulting in the following [unexpected behavior](https://youtrack.jetbrains.com/issue/KT-51065):

```kotlin
// Works
const val three = 1 + 2
const val four = 4u

// Works: It's an operation on a string
const val text = "age: " + four

// Error: Const 'val' initializer must be a constant value.
const val secondsPerDay = 60u * 60u * 24u
```

In between the two extremes, strings do support some of the most common operations ([list in Appendix](#currently-supported-operations-on-primitives)), though there are some that would also produce compile-time known results and aren't allowed.

```kotlin
// Works
const val name = "apfel" + "strudel"

// Error: Const 'val' initializer must be a constant value.
const val receipt = """
        1) apple
        2) dough
        """.trimIndent()

```

The last example with `trimIndent` is especially unusual because the compiler already evaluates that expression during compilation as part of an optimization, but the checker validating the constant context doesn't know that.

These examples show that the operations that can be performed in a constant context are not unified across numeric types, and are chosen in an ad-hoc manner for Strings. The lack of operations makes it harder to use descriptive expressions in constants and it’s simply difficult to understand which operations are available. Adding a new type capable of constant operations (like new numeric types) makes the situation even harder and more convoluted.

## Proposal

To address the issues outlined, this KEEP proposes to extend the functions allowed inside a constant context with the goal to unify the behavior. At this stage, we also have the non-goal of introducing constant expressions to users. First, our plan is to clean up the story around the standard library.

### Documentation

```kotlin
/**
 * When applied to a function or property, it can be used inside a constant
 * context like compile-time constants initializer and annotation arguments.
 * Additionally, enables a compiler optimization that evaluates it at
 * compile-time and replaces calls to it with the computed result.
 */
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@SinceKotlin("1.7")
internal annotation class IntrinsicConstEvaluation
```

In the standard library we already have all compiler evaluable functions marked with the `@kotlin.internal.IntrinsicConstEvaluation` Annotation. Though, as mentioned in the [Motivation](#motivation) there are some cases like `trimIndent` where the checker in the frontend isn't aware of them and doesn't allow them inside a constant context.

From now on all such marked functions will be allowed inside a constant context. This improves usability and documentation of Kotlin as users will only need to look up if a function is marked with the annotation and don't need to remember all the special cases.

Additionally we will add special handling in Dokka to produce a note on those functions that they can be used inside a constant kontext.

### Unsigned Integers

Just as operations on primitives are already supported, the same operations will be supported on unsigned types, unifying the behavior between signed and unsigned integers. A full list can be found in the Appendix section [Additional operations on unsigned integers](#additional-operations-on-unsigned-integers).

### Functions from stdlib

Additionally to all functions already marked with `IntrinsicConstEvaluation` some functions from the [kotlin.text](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/) package will be added, notably the `trim` family of functions. A full list of all new functions from the standard library that will be supported can be found in  the Appendix section [Additions from the standard library](#additions-from-the-standard-library).

Many more functions from the standard library *could*  be implemented on account of being functionally pure, especially from the [kotlin.math](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/) and [kotlin.text](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/) packages, but lack sufficient motivation, to justify the additional work and testing this would require.

### Compile Time name resolution of Enum and KCallable

During compilation we have a lot of information about symbols and can easily evaluate `.name` calls. This feature doesn't require that we support Enum or KCallable values in the interpreter.

```kotlin
fun timesTwo(a: Int) = a * 2

// Works
const val name = ::timesTwo.name

// Error, would require KCallable values to be allowed
const val func = ::timesTwo
const val name2 = func.name
```

This initial set will provide us with useful information of how feasible and useful more complex compile-time reflection will be to implement.

### Improved Error Messages

```kotlin
const val tag = (if (true) "A" else "B") + C
//              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//				Error: Const 'val' initializer must be a constant value.
```

From the provided error message, it's not clear which part of the expression isn't a constant value as the whole expression gets highlighted and the description isn't specific.

To improve upon this design, error messages will track the source location of the violating expression instead of highlighting the whole expression and provide a more detailed description to explain themself.

```kotlin
const val tag = (if (true) "A" else "B") + C
//               ^^^^^^^^^^^^^^^^^^^^^^
//				Error: Const 'val' initializer cannot contain controlflow.
```

### Out of scope

This KEEP doesn't aim to support more types inside constant expression, which means that assigning annotations to constant `val` as suggested in [KT-60889](https://youtrack.jetbrains.com/issue/KT-60889) won't be added here, nor will higher order functions work.

All added functions from the standard library that will be callable from a constant context must be functionally pure and therefore loading files from disk (compile time injection) during compile time, as discussed in [this comment](https://youtrack.jetbrains.com/issue/KT-14652/Introduce-constexpr-const-modifier-annotation-for-functions-that-can-be-computed-in-compile-time#focus=Comments-27-6339535.0-0), or like [Go's embed](https://pkg.go.dev/embed) won't be allowed.

Similarly this KEEP doesn't propose any new language concepts, like constant functions and instead leaves this work to be done for future proposals.

## Technical details

Currently there are two interpreters that evaluate constants `FirExpressionEvaluator` in the frontend and `IrInterpreter` in the backend. While the former is quite limited, enforcing the constraints mentioned in [Motivation](#motivation), the later already can evaluate some functions and control flow constructs. Originally the design only planned for an interpreter in the backend where evaluation is easier to implement but even before `Ir` is generated we need to serialize annotations with arguments to metadata and provide evaluated arguments to the frontend compiler plugins.

At the moment both interpreters have a different decision process to decide which functions can be evaluated, with the frontend looking up a set of hard-coded lists while the backend looks up if the function is annotated with `@kotlin.internal.IntrinsicConstEvaluation`.

Even though this proposal only focuses on functions from the standard library, it should be noted that the current design of FIR places some limitations on interpreting user defined functions. For example interpreting functions from different modules would currently be impossible in the frontend interpreter as the body from such foreign functions isn't available.

Inside the IDE we can add support for the evaluated result as an inline hint, similar to how Android Studio shows the evaluated strings for `R` resources.

## Concerns

Right now constant evaluation adheres to JVM rules as described in [KT-80562](https://youtrack.jetbrains.com/issue/KT-80562). This is not completely correct because on a different platform the same code might produce different values at runtime.


```kotlin
// If executed on JS
const val a = 1.0.toString()
fun main() {
val b = 1.0.toString()
	println(a)			// 1.0
	println(b)			// 1
   	println(a == b)		// false
}
```

While this is already the current behavior, expanding compile time constants might lead to greater adoption of this feature and more users running into edge cases like this. We cannot change this behavior without breaking existing code but, pitfalls like this should be outlined in the documentation.

A step even further would be to issue warnings, like suggested in [KT-80563](https://youtrack.jetbrains.com/issue/KT-80563), when such functions are used inside a constant context for non-JVM backends.

## Future Evolutions

This KEEP is necessary groundwork for further extending the language and adding or discussing more features for constant evaluation in the future.

- Full Compile Time reflection
- [KT-14652](https://youtrack.jetbrains.com/issue/KT-14652/Introduce-constexpr-const-modifier-annotation-for-functions-that-can-be-computed-in-compile-time) Const functions (like C++ `constexpr` or Rust `const fn`)
- [KT-25915](https://youtrack.jetbrains.com/issue/KT-25915) Const classes
- [KT-44719](https://youtrack.jetbrains.com/issue/KT-44719) Allow const val in enum instances
- [KT-52318](https://youtrack.jetbrains.com/issue/KT-52318) Provide support for const arrays
- [KT-19230](https://youtrack.jetbrains.com/issue/KT-19230) Allowing Enums in constant context
- [KT-55882](https://youtrack.jetbrains.com/issue/KT-55882) Constant (or JVM-static) value classes
- [KT-57012](https://youtrack.jetbrains.com/issue/KT-57012), [KT-60889](https://youtrack.jetbrains.com/issue/KT-60889) Allow annotations as values for `const val`
- [KT-60845](https://youtrack.jetbrains.com/issue/KT-60845) Making annotations const classes
- [KT-16304](https://youtrack.jetbrains.com/issue/KT-16304) Compile-time intrinsic names shall be properly treated as constants


## Appendix

### Currently supported operations on primitives

**Boolean**

- fun not(): Boolean
- fun toString(): String
- fun and(other: Boolean): Boolean
- fun compareTo(Boolean): Int
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun or(Boolean): Boolean
- fun xor(Boolean): Boolean

**Byte**

- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun unaryMinus(): Int
- fun unaryPlus(): Int
- fun compareTo(Byte | Double | Float | Int | Long | Short): Int | Int | Int | Int | Int | Int
- fun div(Byte | Double | Float | Int | Long | Short): Int | Double | Float | Int | Long | Int
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Byte | Double | Float | Int | Long | Short): Int | Double | Float | Int | Long | Int
- fun plus(Byte | Double | Float | Int | Long | Short): Int | Double | Float | Int | Long | Int
- fun rem(Byte | Double | Float | Int | Long | Short): Int | Double | Float | Int | Long | Int
- fun times(Byte | Double | Float | Int | Long | Short): Int | Double | Float | Int | Long | Int

**Byte Extensions**

- fun Byte.floorDiv(Byte | Int | Short | Long): Int | Int | Int | Long
- fun Byte.mod(Byte | Int | Long | Short): Byte | Int | Long | Short

**Char**

- val Char.code: Int
- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun compareTo(Char): Int
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Char | Int): Int | Char
- fun plus(Int): Char

**Double**

- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun unaryMinus(): Double
- fun unaryPlus(): Double
- fun compareTo(Byte | Double | Float | Int | Long | Short): Int | Int | Int | Int | Int | Int
- fun div(Byte | Double | Float | Int | Long | Short): Double | Double | Double | Double | Double | Double
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Byte | Double | Float | Int | Long | Short): Double | Double | Double | Double | Double | Double
- fun mod(Float | Double): Double | Double
- fun plus(Byte | Double | Float | Int | Long | Short): Double | Double | Double | Double | Double | Double
- fun rem(Byte | Double | Float | Int | Long | Short): Double | Double | Double | Double | Double | Double
- fun times(Byte | Double | Float | Int | Long | Short | Short): Double | Double | Double | Double | Double | Double | Double

**Float**

- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun unaryMinus(): Float
- fun unaryPlus(): Float
- fun compareTo(Byte | Double | Float | Int | Long | Short): Int | Int | Int | Int | Int | Int
- fun div(Byte | Float | Int | Long | Short | Double): Float | Float | Float | Float | Float | Double
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Byte | Float | Int | Long | Short | Double): Float | Float | Float | Float | Float | Double
- fun mod(Float): Float
- fun mod(Double): Double
- fun plus(Byte | Float | Int | Long | Short | Double): Float | Float | Float | Float | Float | Double
- fun rem(Byte | Float | Int | Long | Short | Double): Float | Float | Float | Float | Float | Double
- fun times(Byte | Float | Int | Long | Short | Double): Float | Float | Float | Float | Float | Double

**Int**

- fun inv(): Int
- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun unaryMinus(): Int
- fun unaryPlus(): Int
- fun and(Int): Int
- fun compareTo(Byte | Double | Float | Int | Long | Short): Int | Int | Int | Int | Int | Int
- fun div(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun or(Int): Int
- fun plus(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun rem(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun shl(Int): Int
- fun shr(Int): Int
- fun times(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun ushr(Int): Int
- fun xor(Int): Int

**Int Extensions**

- fun Int.floorDiv(Byte | Int | Long | Short): Int | Int | Long | Int
- fun Int.mod(Byte | Int | Long | Short): Byte | Int | Long | Short

**Long**

- fun inv(): Long
- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun unaryMinus(): Long
- fun unaryPlus(): Long
- fun and(Long): Long
- fun compareTo(Byte | Double | Float | Int | Long | Short): Int | Int | Int | Int | Int | Int
- fun div(Byte | Int | Long | Short | Double | Float): Long | Long | Long | Long | Double | Float
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Byte | Int | Long | Short | Double | Float): Long | Long | Long | Long | Double | Float
- fun or(Long): Long
- fun plus(Byte | Int | Long | Short | Double | Float): Long | Long | Long | Long | Double | Float
- fun rem(Byte | Int | Long | Short | Double | Float): Long | Long | Long | Long | Double | Float
- fun shl(Int): Long
- fun shr(Int): Long
- fun times(Byte | Int | Long | Short | Double | Float): Long | Long | Long | Long | Double | Float
- fun ushr(Int): Long
- fun xor(Long): Long

**Long Extensions**

- fun Long.floorDiv(Byte | Int| Long | Short): Long
- fun Long.mod(Byte | Int | Long | Short): Byte | Int | Long | Short

**Short**

- fun toByte(): Byte
- fun toChar(): Char
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun unaryMinus(): Int
- fun unaryPlus(): Int
- fun compareTo(Byte | Double | Float | Int | Long | Short): Int | Int | Int | Int | Int | Int
- fun div(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun equals(Any?): Boolean (Only as infix equality operator)
- fun minus(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun plus(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun rem(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long
- fun times(Byte | Int | Short | Double | Float | Long): Int | Int | Int | Double | Float | Long

**Short Extension**

- fun Short.floorDiv(Byte | Int | Long | Short): Int | Int | Long | Int
- fun Short.mod(Byte | Int | Long | Short): Byte | Int | Long | Short

###

### Currently supported operations on String

- val length: Int
- fun toString(): String
- fun compareTo(String): Int
- fun equals(Any?) (Only as infix equality operator)
- fun get(Int): Char
- fun plus(Any?): String

### Additional operations on unsigned integers

This also includes some extension functions for other Types:

**UByte**

- fun and(UByte): UByte
- fun compareTo(UByte | UInt | ULong | UShort): Int | Int | Int | Int
- fun dec(): UByte
- fun div(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun equals(Any?): Boolean
- fun floorDiv(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun hashCode(): Int
- fun inc(): UByte
- fun inv(): UByte
- fun minus(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun mod(UByte | UInt | ULong | UShort): UByte | UInt | ULong | UShort
- fun or(UByte): UByte
- fun plus(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun rem(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun times(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun toByte(): Byte
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun toUByte(): UByte
- fun toUInt(): UInt
- fun toULong(): ULong
- fun toUShort(): UShort
- fun xor(UByte): UByte

**UShort**

- fun and(UShort): UShort
- fun compareTo(UByte | UInt | ULong | UShort): Int | Int | Int | Int
- fun dec(): UShort
- fun div(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun equals(Any?): Boolean
- fun floorDiv(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun hashCode(): Int
- fun inc(): UShort
- fun inv(): UShort
- fun minus(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun mod(UByte | UInt | ULong | UShort): UByte | UInt | ULong | UShort
- fun or(UShort): UShort
- fun plus(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun rem(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun times(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun toByte(): Byte
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun toUByte(): UByte
- fun toUInt(): UInt
- fun toULong(): ULong
- fun toUShort(): UShort
- fun xor(UShort): UShort

**UInt**

- fun and(UInt): UInt
- fun compareTo(UByte | UInt | ULong | UShort): Int | Int | Int | Int
- fun dec(): UInt
- fun div(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun equals(Any?): Boolean
- fun floorDiv(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun hashCode(): Int
- fun inc(): UInt
- fun inv(): UInt
- fun minus(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun mod(UByte | UInt | ULong | UShort): UByte | UInt | ULong | UShort
- fun or(UInt): UInt
- fun plus(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun rem(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun shl(Int): UInt
- fun shr(Int): UInt
- fun times(UByte | UInt | ULong | UShort): UInt | UInt | ULong | UInt
- fun toByte(): Byte
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun toUByte(): UByte
- fun toUInt(): UInt
- fun toULong(): ULong
- fun toUShort(): UShort
- fun xor(UInt): UInt

**ULong**

- fun and(ULong): ULong
- fun compareTo(UByte | UInt | ULong | UShort): Int | Int | Int | Int
- fun dec(): ULong
- fun div(UByte | UInt | ULong | UShort): ULong | ULong | ULong | ULong
- fun equals(Any?): Boolean
- fun floorDiv(UByte | UInt | ULong | UShort): ULong | ULong | ULong | ULong
- fun hashCode(): Int
- fun inc(): ULong
- fun inv(): ULong
- fun minus(UByte | UInt | ULong | UShort): ULong | ULong | ULong | ULong
- fun mod(UByte | UInt | ULong | UShort): UByte | UInt | ULong | UShort
- fun or(ULong): ULong
- fun plus(UByte | UInt | ULong | UShort): ULong | ULong | ULong | ULong
- fun rem(UByte | UInt | ULong | UShort): ULong | ULong | ULong | ULong
- fun shl(Int): ULong
- fun shr(Int): ULong
- fun times(UByte | UInt | ULong | UShort): ULong | ULong | ULong | ULong
- fun toByte(): Byte
- fun toDouble(): Double
- fun toFloat(): Float
- fun toInt(): Int
- fun toLong(): Long
- fun toShort(): Short
- fun toString(): String
- fun toUByte(): UByte
- fun toUInt(): UInt
- fun toULong(): ULong
- fun toUShort(): UShort
- fun xor(ULong): ULong

**Byte Extensions**

- fun Byte.toUByte(): UByte
- fun Byte.toUInt(): UInt
- fun Byte.toULong(): ULong
- fun Byte.toUShort(): UShort

**Short Extensions**

- fun Short.toUByte(): UByte
- fun Short.toUInt(): UInt
- fun Short.toULong(): ULong
- fun Short.toUShort(): UShort

**Int Extensions**

- fun Int.toUByte(): UByte
- fun Int.toUInt(): UInt
- fun Int.toULong(): ULong
- fun Int.toUShort(): UShort

**Long Extensions**

- fun Long.toUByte(): UByte
- fun Long.toUInt(): UInt
- fun Long.toULong(): ULong
- fun Long.toUShort(): UShort

**Float Extensions**

- fun Float.toUInt(): UInt
- fun Float.toULong(): ULong

**Double Extensions**

- fun Double.toUInt(): UInt
- fun Double.toULong(): ULong

###

### Additions from the standard library

**String**

- fun String.lowercase(): String
- fun String.trim(): String
- fun String.trimEnd(): String
- fun String.trimIndent(): String
- fun String.trimMargin(marginPrefix: String \= "|"): String
- fun String.trimStart(): String
- fun String.uppercase(): String
- fun equals(other: Any?): Boolean

**Char**

- fun Char(Int): Char
- fun equals(other: Any?): Boolean

**Byte**

- fun dec(): Byte
- fun inc(): Byte
- fun equals(other: Any?): Boolean

**Int**

- fun dec(): Int
- fun inc(): Int
- fun equals(other: Any?): Boolean

**Long**

- fun dec(): Long
- fun inc(): Long
- fun equals(other: Any?): Boolean

**Short**

- fun dec(): Short
- fun inc(): Short
- fun equals(other: Any?): Boolean

**KCallable**

- val name: String

**Enum**

- val name: String
# Unsigned types

* **Type**: Design proposal
* **Authors**: Ilya Gorbunov, Mikhail Zarechenskiy
* **Contributors**: Andrey Breslav, Roman Elizarov, Nikolay Igotti
* **Status**: Partially stable in 1.5, see details in the [Status](#status) section.
* **Prototype**: Implemented
* **Related issues**: [KT-191](https://youtrack.jetbrains.com/issue/KT-191)
* **Discussion**: [KEEP-135](https://github.com/Kotlin/KEEP/issues/135)

## Summary

Provide support in the compiler and the standard library in order to introduce types for unsigned integers and make them first-class citizen in the language.

## Use cases

### Hexadecimal constants that do not fit in signed types

Currently, it's hard or even impossible to use hexadecimal literal constants that result in overflow of the corresponding 
signed types. That overflow causes the constant to become wider than expected (e.g. `0xFFFF_FFFE` is `Long`) 
or even impossible to express in Kotlin: `0x8000_0000_0000_0000`

**Colors**

An example is specifying color as 32-bit AARRGGBB value:

```kotlin
fun takesColor(color: Int)  

takesColor(0xFFCC00CC) // doesn't compile, requires explicit .toInt() conversion
```

This proposal doesn't ease the usage of such API, but at least it becomes possible to author Kotlin API with unsigned types
as following:

```kotlin
fun takesColor(color: UInt) = takesColor(color.toInt()) 

takesColor(0xFFCC00CCu)
```

**Byte arrays initialized in code**

Currently, creating a byte array with content specified in code looks extremely verbose in Kotlin:

```kotlin
val byteOrderMarkUtf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
```

With the introduction of unsigned bytes and byte arrays this can be rewritten more compact:

```kotlin
val byteOrderMarkUtf8 = ubyteArrayOf(0xEFu, 0xBBu, 0xBFu)
```

### Algorithms involving unsigned integers

There are tricks that make it possible to implement algorithms based on 
unsigned arithmetic with signed integers (see [Unsigned int considered harmful for Java](https://www.nayuki.io/page/unsigned-int-considered-harmful-for-java)), 
but one has to be extremely careful when dealing with such tricks and remember which variable represents which type actually in code.

For an unsigned value represented by a signed integer special functions like 
`divideUnsigned`, `remainderUnsigned`, `toUnsignedString` have to be called instead of the standard ones. 
It is very fragile and error prone especially when signed and unsigned values both are used.


### Interoperability with native API

When one provides an external declaration for some native platform API (either C API or JS IDL declarations [KT-13541](https://youtrack.jetbrains.com/issue/KT-13541))
that declaration can contain unsigned types natively. Unsigned types in Kotlin would allow to represent such declarations
without unwittingly altering their semantics by substituting unsigned integers with signed ones.

## Non-goals

### Non-negative integers

While unsigned integers can only represent positive numbers and zero, it's not a goal to use them 
where non-negative integers are required by application domain, for example, as a type of collection size or collection index value.

First, even in these cases using signed integers helps to detect accidental overflows and signal error conditions, 
such as `List.lastIndex` being -1 for an empty list or `List.indexOf` returning -1 when an element is not found in the list.

Second, unsigned integers cannot be treated as a range-limited version of signed ones because their range of values 
is not a subset of the signed integers range, i.e. neither signed, nor unsigned integers are subtypes of each other.

To reiterate, the main use case of unsigned numbers is when you need to utilize the full bit range of an integer to
represent positive values.

That is also the reason why conversion from signed values to unsigned and vice-versa works by reinterpreting 
the bit pattern of a number as unsigned, or as 2-complement signed respectively.

## Description

We propose to introduce 4 types to represent unsigned integers:
- `kotlin.UByte`: an unsigned 8-bit integer, ranges from 0 to 255
- `kotlin.UShort`: an unsigned 16-bit integer, ranges from 0 to 65535
- `kotlin.UInt`: an unsigned 32-bit integer, ranges from 0 to 2^32 - 1
- `kotlin.ULong`: an unsigned 64-bit integer, ranges from 0 to 2^64 - 1

Same as for primitives, each of unsigned type will have corresponding type that represents array:
- `kotlin.UByteArray`: an array of unsigned bytes
- `kotlin.UShortArray`: an array of unsigned shorts
- `kotlin.UIntArray`: an array of unsigned ints
- `kotlin.ULongArray`: an array of unsigned longs

To iterate through a range of unsigned values there will be range and progression types of unsigned ints and longs:
- `kotlin.ranges.UIntRange`: an iterable range of unsigned ints
- `kotlin.ranges.UIntProgression`: an iterable progression of unsigned ints
- `kotlin.ranges.ULongRange`: an iterable range of unsigned longs
- `kotlin.ranges.ULongProgression`: an iterable progression of unsigned longs

## Experimental status 

The unsigned types are to be released in Kotlin 1.3 as an [experimental feature](opt-in.md).
This means we do not give compatibility guarantees for the API and language features related to unsigned types.

Their usage without an opt-in will produce a compiler warning about their experimentality.

The opt-in can be given in two ways:
- either annotate the code element that uses unsigned types with the `@UseExperimental(ExperimentalUnsignedTypes::class)` annotation
- or specify `-Xuse-experimental=kotlin.ExperimentalUnsignedTypes` compiler option

If you develop a library and want to use unsigned types, we recommend to propagate the experimentality to the parts of your API
that depend on the unsigned types with the `@ExperimentalUnsignedTypes` annotation:

```kotlin
// a function that exposes unsigned types in signature
@ExperimentalUnsignedTypes
fun upTo(limit: UInt): UInt {
}

// a function that uses unsigned types in its body
@ExperimentalUnsignedTypes
fun usesUnsignedUnderTheCover(): Boolean {
    return upTo(10u) < 5u
}
```


## API Details

Unsigned types support operations similar to their signed counterparts. These are:

- equality: `equals` and `hashCode`
- comparison: `compareTo`
- arithmetic operators: `plus`, `minus`, `div`, `rem`
- increment and decrement operators: `inc`, `dec`
- bitwise operations: `and`, `or`, `xor`, `inv`
- bit shifts: `shl`, `shr`
- range creation: `rangeTo` operator
- narrowing and widening conversions: `toUByte`, `toUShort`, `toUInt`, `toULong`
- unsigned->signed conversions: `toByte`, `toShort`, `toInt`, `toLong`
- signed->unsigned conversions: `toUByte`, `toUShort`, `toUInt`, `toULong` extensions on `Byte`/`Short`/`Int`/`Long`
- number->string conversions: `toString` member function and `toString(radix)` extensions
- string->number conversions: `String.toUInt/ULong/UByte/UShort()` extensions with optional `radix` and non throwing `OrNull`-variant

### `UByte` and `UShort` arithmetic

Arithmetic operations on `UByte` and `UShort` work similar to the signed ones: first they widen their operands to unsigned ints
then perform the operation and return `UInt` as a result.

This leads to the same compound assignment problem [KT-7907](https://youtrack.jetbrains.com/issue/KT-7907) as with `Byte` and `Short` types:

```kotlin
val bytes: UByteArray = ...

bytes[i] += 0x80u  // doesn't compile because the return type `UInt` doesn't fit back into `UByte`
```

We've decided to favor the consistency with the signed types and not try to solve this problem individually for the unsigned types.
We hope to approach the problem later uniformly both for the signed and unsigned types.

### Mixed width operations

Comparison and arithmetic operations are overloaded for each combination of unsigned type operands.
The narrower operand is extended to the width of the other one and that type is the type of the result.

### Mixed signedness operations

Arithmetic and comparison operations that mix signed and unsigned operands are not provided. The semantics of such operators is unclear.

### Unary plus and minus operators

In some languages there is unary minus operator on unsigned types which extends them to a wider signed type and then negates.
We're going to omit this operator, requiring an explicit conversion to a signed type before negating.

Unary plus operator is not provided for the lack of use cases.

### Bitwise operations

Bitwise operations are provided for operands of the same width only, same as in the signed counterparts.

Bitwise operators for `UByte` and `UShort` are provided, but they are in question, because for the signed counterparts they exist
as experimental extensions as of Kotlin 1.2.

### Bit shifts

Bit shifts are provided only for `UInt` and `ULong`, for the more narrow types, both for signed and unsigned, they are under consideration.

There's an open question how to call the operation that shifts an unsigned integer right.

### Narrowing and widening conversions between unsigned types

**Widening conversion** of one unsigned type to a wider one, for example `UInt.toULong()` is done by extending it with zero bits.

**Narrowing conversion** to a narrower unsigned type like `UInt.toUByte()` is just a bit pattern truncation.

### Signed/unsigned reinterpretation

A conversion between signed and unsigned types of the same with to each other is done by reinterpreting the bit pattern of
a number as signed or unsigned. Therefore `UInt.MAX_VALUE.toInt()` will turn into `-1`.

### Signed/unsigned narrowing conversion

**Narrowing conversion** between signed and unsigned types is done by reinterpreting
the bit pattern of a number truncated to the given width as signed or unsigned.
For example `511u.toByte()` will turn into `-1` signed byte value.

### Signed/unsigned widening conversion

**Widening conversion** of unsigned type to a wider signed type, for example `UInt.toLong()` is done by extending it with zero bits,
so the resulting signed value is always non-negative.

**Widening conversion** of signed type to a wider unsigned type is done by signed extension to a wider signed type first 
and then reinterpreting it as unsigned type of the same width.

### Floating point/unsigned conversion

**Conversion from floating point numbers to unsigned integers** follows the same principles as for the signed types:

- If the floating point number is outside of the target unsigned type range, it is coerced into that range, e.g. 
all negative numbers are clamped to zero.
- The floating point number is rounded towards zero, i.e. its fractional part is truncated.

**Conversion from unsigned integers to floating point numbers**

- When converting `UByte`, `UShort` to `Float` and `Double` or `UInt` to `Double`, 
the resulting floating point number represents the same integer numeric value as the original unsigned number.
- When converting `UInt` to `Float` or `ULong` to `Float` and `Double`,
the resulting floating point number has the integer numeric value that is the closest to the original unsigned number, 
as close as the accuracy of the floating point type at this magnitude affords.

### Arrays

For each unsigned integer type there will be a specialized array: `UIntArray`, `ULongArray` etc.

**Storage**

These arrays must provide the compactness of storage same as their signed counterparts.

**Equality contract**

The arrays have the identity equality contact like the signed arrays rather than the structural equality like lists.
Therefore they do not override `equals` and `hashCode` implementations, defaulting to the ones inherited from `Any`.

`toString` implementation is in question, whether it should or shouldn't render array element values.

**Implemented interfaces**

Unlike the signed counterparts these arrays can implement interfaces. We find it advantageous to implement
the `Collection<T>` interface and have a variety of collection extension functions available on arrays of unsigned integers
without having to provide specializations from them.

We also have considered if the arrays should implement `List` interface, because they do support indexed access, but found that
infeasible due to conflicting equality contract of the `List`.

**Conversions between signed and unsigned arrays**

It should be possible to convert an array of signed integers to an array of unsigned ones of the same width and vice versa
by copying values to a new array and reinterpreting them as the desired type:
```
fun UByteArray.toByteArray(): ByteArray
fun ByteArray.toUByteArray(): UByteArray
```

Also it might be advantageous to provide functions that reinterpret an entire array as signed or unsigned one:
```
fun UByteArray.asByteArray(): ByteArray
fun ByteArray.asUByteArray(): UByteArray
```
so that the returned array is a view on the original array with a different signedness,
but this is possible only if the specific implementation of unsigned arrays is chosen.

### Progressions and Ranges

A progression (`UIntProgression` or `ULongProgression`) of unsigned values has unsigned `start` and `endInclusive` properties and a signed `step` of the same width.

The sign of the step is used to represent the direction of a progression: either it's ascending or descending.
Therefore it isn't possible to create a progression that iterates from `0` to `UInt.MAX_VALUE` in one step.

A range is a special case of a progression with the step 1.

## Implementation

Implementation of unsigned types heavily depends on [inline classes](inline-classes.md) feature.
Namely, each unsigned class is actually an inline class, which uses the signed counterpart value as a storage.

### Inheritance from `Number`

`Number` is an abstract class in Kotlin, and inline classes are not allowed to extend other classes, therefore unsigned types 
can't be inherited from `Number` class.

We haven't found compelling use cases to circumvent this limitation specially for the unsigned types.

### Boxing on JVM

Each unsigned class has its own wrapper class, 
which is used for autoboxing operations, see [this section](inline-classes.md#java-interoperability) for more details.
Basically, rules for boxing are the same as for primitives. Example:
```kotlin
val a: UInt? = 3.toUInt() // Boxing
val b: Comparable<*> = 0.toULong() // Boxing
val c: List<UInt> = listOf(1.toUInt(), 2.toUInt()) // Boxing
```

### Intrinsics

_TODO_: List operations intrinsified by the compiler

## Language changes

Here we list changes in the language that should be supported in the compiler specifically for the unsigned types.

### Unsigned literals

In order to simplify the usage of unsigned integers we introduce unsigned literals.
An unsigned literal is an expression of the following form:
- `{decimal literal}` `{unsigned integer suffix}`
- `{hex literal}` `{unsigned integer suffix}`
- `{binary literal}` `{unsigned integer suffix}`

Where `{unsigned integer suffix}` can be one the following values: `u`, `U`, `uL` and `UL`.
Note that the order of `[uU]` and `L` characters matters, one cannot use unsigned literal `42LU`.

Semantically, type of an unsigned literal `42u` depend on an expected unsigned type or its supertype.
If there is no applicable expected type for an unsigned literal, then expression of such type will be approximated to `UInt` if possible, or to `ULong`,
depending on a value of the unsigned literal. Unsigned literal with suffix `uL` or `UL` always represents value of `ULong` type. 

Example:
```kotlin
val a1 = 42u // UInt
val a2 = 0xFFFF_FFFF_FFFFu // ULong, it's 281474976710655
val b: UByte = 1u // OK
val c: UShort = 1u // OK
val d: ULong = 1u // OK

val l1 = 1UL // ULong
val l2 = 2uL // ULong

val e1: Int = 42u // ERROR, type mismatch
```

Unsigned integer literals can represent numbers that are larger than any number representable with signed integer literals,
therefore the compiler should support parsing of unsigned number literals that are larger than `Long.MAX_VALUE`:

```kotlin
val a1 = 9223372036854775807 // OK, it's Long
val a2 = 9223372036854775808 // Error, out of range

val u1 = 9223372036854775808u // OK, it's ULong
```

### Constant evaluation

It might be useful to use values of unsigned types inside `const vals` and annotations:
```kotlin
const val MAX: UByte = 0xFFu
const val OTHER = 40u + 2u

const val ERROR = -1
const val U_ERROR = ERROR.toUInt()

annotation class ExpectedErrorCode(val error: UInt)
```

To make it possible, expression of unsigned type should be evaluated at compile-time to a concrete value. Thus, we are going to tune
constant evaluator for basic operations on unsigned types in order to support `const vals` and annotations with unsigned parameters.

### Arrays and varargs

Note that `vararg` parameters of inline class types are forbidden, because it's not clear how to associate the type from `vararg` parameter
with the underlying array type, see [this section](https://github.com/zarechenskiy/KEEP/blob/master/proposals/inline-classes.md#arrays-of-inline-class-values) for the details.

However, since we provide a specialized array for each unsigned integer type, we can associate
types from `vararg` with the corresponding array types:
```kotlin
fun uints(vararg u: UInt): UIntArray = u
fun ubytes(vararg u: UByte): UByteArray = u
fun ushorts(vararg u: UShort): UShortArray = u
fun ulongs(vararg u: ULong): ULongArray = u
```  


## Unresolved questions

#### Signed literals assignable to unsigned types
    
Should we allow assignment of usual signed literals to unsigned types?

```kotlin
fun takeUByte(b: UByte) {}

takeUByte(0xFF)
```

If yes, then how signed and unsigned types are related to each other?

#### Enhancing Java integer types as seen in Kotlin 

Should we support some kind of `@Unsigned` annotation to load types from Java as unsigned ones?

```java
public class Foo {
    void test(@Unsigned int x) {} // takes UInt from Kotlin point of view
}
```

#### Shift right: `shr` or `ushr`

How to call the operation that shifts an unsigned integer right:
- `shr`, because it's clear that the shift is always unsigned and we don't need `u` prefix to distinguish it.
- `ushr`, because the shift is always unsigned and we want to emphasize that.
- both of above, because they do the same and having both of them will ease the migration from signed types.

#### Bitwise operations for `UByte` and `UShort`

These operations are experimental for `Byte` and `Short`: we haven't decided yet on their contract.


## Status

- Unsigned types were introduced as experimental in Kotlin 1.3 and graduated to Beta in Kotlin 1.4.
- Since Kotlin 1.5, the `UInt`, `ULong`, `UByte`, `UShort` unsigned integer types are stable. 
  The same goes for operations on these types, ranges, and progressions of them. 
  Unsigned arrays, varargs, and operations on them remain in Beta.
  


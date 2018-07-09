# Unsigned types

* **Type**: Design proposal
* **Authors**: Ilya Gorbunov, Mikhail Zarechenskiy
* **Contributors**: Andrey Breslav, Roman Elizarov
* **Status**: Under consideration
* **Prototype**: Implemented in Kotlin 1.3-M1

Discussion of this proposal is held in [this issue](TODO).

## Summary

Provide support in the compiler and the standard library in order to introduce types for unsigned integers and make them first-class citizen in the language.

## Use cases

TODO: interop, algorithms, safety

## Description

We propose to introduce 4 types to represent unsigned integers:
- `kotlin.UByte`: an unsigned 8-bit integer
- `kotlin.UShort`: an unsigned 16-bit integer
- `kotlin.UInt`: an unsigned 32-bit integer
- `kotlin.ULong`: an unsigned 64-bit integer

Same as for primitives, each of unsigned type will have corresponding type that represents array:
- `kotlin.UByteArray`: an array of unsigned bytes
- `kotlin.UShortArray`: an array of unsigned shorts
- `kotlin.UIntArray`: an array of unsigned ints
- `kotlin.ULongArray`: an array of unsigned longs

### Representation on JVM



## API Details

TODO: API, mixed arithmetic, shifts, inheritance from Number

## Implementation

Implementation of unsigned types is heavily depend on [inline classes](https://github.com/zarechenskiy/KEEP/blob/master/proposals/inline-classes.md) feature.
Namely, each unsigned class is actually an inline class, which has value of a signed counterpart type as an underlying value.

### Boxing on JVM

Each unsigned class has its own wrapper class, 
which is used for autoboxing operations, see [this section](https://github.com/zarechenskiy/KEEP/blob/master/proposals/inline-classes.md#java-interoperability) for more details.
Basically, rules for boxing are the same as for primitives. Example:
```kotlin
val a: UInt? = 3.toUInt() // Boxing
val b: Comparable<*> = 0.toULong() // Boxing
val c: List<UInt> = listOf(1.toUInt(), 2.toUInt()) // Boxing
```   

### Constant evaluation

It might be useful to use values of unsigned types inside `const vals` and annotations:
```kotlin
const val MAX: UByte = 0xFFu
const val OTHER = 40u + 2u

annotation class Anno(val s: UByte)
```
 To make it possible, expression of unsigned type should be evaluated at compile-time to a concrete value. Thus, we are going to tune
 constant evaluator for basic operations on unsigned types in order to support `const vals` and annotations with unsigned types. 

### Arrays and varargs

Note that `vararg` parameters of inline class types are forbidden, because it's not clear how to associate type from `vararg` parameter 
with the array type, see [this section](https://github.com/zarechenskiy/KEEP/blob/master/proposals/inline-classes.md#arrays-of-inline-class-values) for the details.

However, for the unsigned integers it is definitely known which array type denotes an array of corresponding unsigned values. Therefore, we can associate
types from `vararg` with array types:
```kotlin
fun uints(vararg u: UInt): UIntArray = u
fun ubytes(vararg u: UByte): UByteArray = u
fun ushorts(vararg u: UShort): UShortArray = u
fun ulongs(vararg u: ULong): ULongArray = u
```  

## Unsigned literals

In order to simplify usage of unsigned integers we introduce unsigned literals. 
An unsigned literal is an expression of the following form:
- `{decimal literal}` `{unsigned integer suffix}`
- `{hex literal}` `{unsigned integer suffix}`
- `{binary literal}` `{unsigned integer suffix}`

Where `{unsigned integer suffix}` can be one the following values: `u`, `U`, `uL` and `UL`.
Note that the order of `[uU]` and `L` characters matters, one cannot use unsigned literal `42LU`.

Semantically, type of an unsigned literal `42u` depend on an expected unsigned type or its supertype.
If there is no applicable expected type for an unsigned literal, then expression of such type will be approximated to `UInt` if possible, or to `ULong`,
depending on a value of the unsigned literal. Unsigned literal with suffix `uL` or `UL` always represents value of `ULong` type. Example:
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

### Overlow of signed counterpart values

Since there is no need in keeping sign for an unsigned integers, they can hold larger positive values than their signed counterpart values.
Therefore, two changes in the compiler are going to be introduced:
- Support parsing of unsigned number literals that are larger than `Long.MAX_VALUE`  
    ```kotlin
    val a1 = 9223372036854775807 // OK, it's Long
    val a2 = 9223372036854775808 // Error, out of range
    
    val u1 = 9223372036854775808u // OK, it's ULong
    ```
- Conversion to signed value when overflow is happened.
    ```kotlin
    fun takeUByte(b: UByte) {} // internally it's just byte
    
    takeUByte(0xFFu) // convert 0xFFu (255) to -1
    ```

## Open question:    
- Should we allow assignment of usual signed literals to unsigned types?
    ```kotlin
    fun takeUByte(b: UByte) {}
    
    takeUByte(0xFF)
    ```
    If yes, then how singed and unsigned types are related to each other?
    
- Should we support some kind of `@Unsigned` annotation to load types from Java as unsigned ones?
    ```java
    public class Foo {
        void test(@Unsigned int x) {} // takes UInt from Kotlin point of view
    }
    ```


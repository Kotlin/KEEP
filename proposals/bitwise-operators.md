# Bitwise Operators

* **Type**: Design proposal
* **Author**: Rhys Kenwell
* **Contributor**: Solomon Greenberg
* **Status**: Submitted
* **Prototype**: Implemented (https://github.com/Redrield/Kotlin/tree/bitwise-operators)
* **Discussion**: [KEEP-142](https://github.com/Kotlin/KEEP/issues/142)

## Summary
Provide new overloadable operators corresponding to bitwise AND (&), bitwise OR (|), bitwise XOR (^), left and right shifts (<<, >>, >>>), and corresponding assignment operators

## Motivation
Bitwise operatations are currently supported on numeric types in Kotlin through several `infix` functions.
* `and`
* `or`
* `xor`
* `shl`
* `shr`
* `ushr`

Due to several factors, this method of providing this functionality is severely limited
1. Confusing to users new to Kotlin
	The operators that provide bitwise functionality are standard across multiple languages. It is confusing to programmers new to Kotlin if they attempt to use these operators, and they are confronted with a compiler error.<sup>1</sup>
2. Lack of precedence
	Kotlin function calls are purely left associative. This lack of proper precedence for bitwise operations leads to buggy code because patterns that would work with operators are broken with infix functions.
	* `num | bytes & 0x80` parses to `num | (bytes & 0x80)`
	* `num or bytes and 0x80` parses to `(num or bytes) and 0x80`
3. No assignment operators
	In providing infix functions rather than operators, Kotlin also does not have a way to cleanly represent the following action: `num |= 3`


## Implementation Details

This KEEP proposes to add 13 new operators to Kotlin.

* or (|), orAssign (|=)
* xor (^), xorAssign (^=)
* and (&), andAssign (&=)
* shl (<<), shlAssign (<<=)
* shr (>>), shrAssign (>>=)
* ushr (>>>), ushrAssign (>>>=)
* inv (~)

These functions will have implementations on primitive numeric types that currently have the infix functions.<sup>2</sup>
* Int
* Long
* Byte
* Short

## Use Cases
* Serialization code
  * Code to serialize values to ByteArrays makes use of bitwise operators extensively. Examples include LEB128 variable-length integers, where serialization and deserialization have to make use of bitwise operators to interpret the correct value from the byte sequence.
* Flags
  * Android has many operations involving bitwise flags (e.g. Intent, Gravity flags). Operations regarding them would be simplified when using bitwise operators, rather than infix functions.

```kotlin
  someView.setForegroundGravity(Gravity.BOTTOM | Gravity.LEFT)
```
```kotlin
someView.setForegroundGravity(Gravity.BOTTOM or Gravity.LEFT)
```

```kotlin
flags = (flags & ~FLAG_A) | FLAG_B | FLAG_C
...
```

```kotlin
flags = (flags and FLAG_A.inv()) or FLAG_B or FLAG_C
```

The examples with bitwise operators are universally recognized as manipulating bitflags. Kotlin's approach is nonstandard among programming languages and [leads to confusion](https://stackoverflow.com/questions/37631397/complex-gravity-in-anko/37631466).

## Unresolved Questions
* Should the functions be marked non-`infix` in the future?
  * How would a deprecation notice be shown to notify users of the deprecated status of calling the functions in `infix` notation

<sup>1</sup>This confusion is demonstrated by the [number of StackOverflow users](https://stackoverflow.com/search?q=%5Bkotlin%5D+bitwise) trying to use traditional bitwise operators, and who are confused when they don't work.

<sup>2</sup>In cases where there are name conflicts, the function will be marked as both `infix` and `operator`

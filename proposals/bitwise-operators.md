# Bitwise Operators

* **Type**: Design proposal
* **Author**: Rhys Kenwell
* **Contributor**: Solomon Greenberg
* **Status**: Submitted
* **Prototype**: Implemented
* **Discussion**: [KEEP-142](https://github.com/Kotlin/KEEP/issues/142)

## Summary
Provide new overloadable operators corresponding to bitwise AND (&), bitwise OR (|), bitwise XOR (^), left and right shifts (<<, >>, >>>), and corresponding assignment operators)

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
	The operators that provide bitwise functionality are standard across multiple languages. It is confusing to programmers new to Kotlin if they attempt to use these operators, and they are confronted with a compiler error.
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

These functions will have implementations on primitive numeric types that currently have the infix functions.<sup>1</sup>
* Int
* Long
* Byte
* Short


## Unresolved Questions
* Should the functions be marked non-`infix` in the future?
  * How would a deprecation notice be shown to notify users of the deprecated status of calling the functions in `infix` notation

<sup>1</sup>In cases where there are name conflicts, the function will be marked as both `infix` and `operator`

# Bitwise Operators

* **Type**: Design proposal
* **Author**: Rhys Kenwell
* **Contributor**: Solomon Greenberg

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

This KEEP proposes to add 12 new operators to Kotlin.

* bitOr (|), orAssign (|=)
* bitXor (^), xorAssign (^=)
* bitAnd (&), andAssign (&=)
* shiftLeft (<<), shlAssign (<<=)
* shiftRight (>>), shrAssign (>>=)
* ushiftRight (>>>), ushrAssign (>>>=)

These functions will have implementations on primitive numeric types that currently have the infix functions.
* Int
* Long
* Byte
* Short

The infix functions that are currently used for bitwise operations will be deprecated with a notice to switch to the new operators. They can be phased out of use and removed from the standard library in a future release.

## Language Impact

**Non-breaking**: The infix functions will be preserved with a deprecation notice, giving users time to adopt the operators. 

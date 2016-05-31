# String to nullable number conversions

* **Type**: Standard Library API proposal
* **Author**: Daniil Vodopian
* **Status**: Submitted
* **Prototype**: Implemented
* **Related issues**: [KT-7930](https://youtrack.jetbrains.com/issue/KT-7930)


## Summary

* Extension functions for `String` to convert it to various numeric types, such as `Int`, `Long`, `Double`, etc,
without throwing an exception when the string doesn't represent a correct number.

## Similar API review

* Standard Library: `String.toInt`, `String.toLong` etc — throw exception when a string is not a number.
* C#: [`int.TryParse`](https://msdn.microsoft.com/en-us/library/f02979c7(v=vs.110).aspx),
[`Double.TryParse`](https://msdn.microsoft.com/en-us/library/994c0zb1(v=vs.110).aspx), etc

## Use cases

* _TODO_: Provide several *real-life* use cases (either links to public repositories or ad-hoc examples).

## Description

It is proposed to provide following functions, one for each primitive numeric type:

* `String.toByteOrNull(): Byte?`
* `String.toShortOrNull(): Short?`
* `String.toIntOrNull(): Int?`
* `String.toLongOrNull(): Long?`
* `String.toFloatOrNull(): Float?`
* `String.toDoubleOrNull(): Double?`

Integer parsing is reimplemented in order not to throw exception on incorrect number, but to return `null` instead.

Floating number are parsed with JDK functions `java.lang.Float.parseFloat` and `java.lang.Double.parseDouble`,
but an additional regex validation is done beforehand, which hopefully does not pass incorrect floating point numbers,
and does not reject correct ones.

## Alternatives

* It is possible to use existing `String.to_Number_` functions inside a try-catch block,
but it could hurt performance on a hot path:

```
val result = try { string.toInt() } catch (e: NumberFormatException) { null }
```

## Dependencies

JDK-specific dependencies, available in JDK6:

* `java.lang.Character`

## Placement

- module `kotlin-stdlib`
- package `kotlin.text`

## Reference implementation

The reference implementation is provided in the pull request [PR #839](https://github.com/JetBrains/kotlin/pull/839).


## Unresolved questions

* Consider naming alternatives:
    * `String.tryToInt()`
    * `String.tryParseInt()`
* Difference between JDK6 and JDK8 in allowing the leading `+` sign.

## Future advancements

* Provide the same API in Kotlin JS Standard Library


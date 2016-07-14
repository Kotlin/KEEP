# Exceptionless string to number conversions

* **Type**: Standard Library API proposal
* **Author**: Daniil Vodopian
* **Contributors**: Ilya Gorbunov
* **Status**: Submitted
* **Prototype**: Implemented
* **Related issues**: [KT-7930](https://youtrack.jetbrains.com/issue/KT-7930)
* **Indirectly related issues**: [KT-8286](https://youtrack.jetbrains.com/issue/KT-8286), [KT-9374](https://youtrack.jetbrains.com/issue/KT-9374)
* **Discussion**: [KEEP-19](https://github.com/Kotlin/KEEP/issues/19)

## Summary

* Extension functions for `String` to convert it to various numeric types, such as `Int`, `Long`, `Double`, etc,
without throwing an exception when the string doesn't represent a correct number.

## Similar API review

* Standard Library: `String.toInt`, `String.toLong` etc — throw exception when a string is not a number.
* C#: [`int.TryParse`](https://msdn.microsoft.com/en-us/library/f02979c7(v=vs.110).aspx),
[`Double.TryParse`](https://msdn.microsoft.com/en-us/library/994c0zb1(v=vs.110).aspx), etc

## Use cases and motivation

The goal is to have a way to convert a string to a number without throwing exceptions in situations
where incorrectly formatted number is an expected, rather than an exceptional thing.
Throwing and catching exceptions just to implement control flow could affect performance badly.

- default value
```kotlin
val x = str.toIntOrNull() ?: 42
```

- custom exception
```kotlin
val x = str.toIntOrNull() ?:
    throw IllegalArgumentException("str is expected to be a valid number, but was '$str'")
```

- parsing a stream of formatted data
```kotlin
val numbers = lines.map { it.toIntOrNull() }
```

## Description

It is proposed to provide following functions, one for each primitive numeric type:

* `String.toByteOrNull(): Byte?`
* `String.toShortOrNull(): Short?`
* `String.toIntOrNull(): Int?`
* `String.toLongOrNull(): Long?`
* `String.toFloatOrNull(): Float?`
* `String.toDoubleOrNull(): Double?`

Integer parsing is reimplemented in order not to throw an exception on incorrect number, but to return `null` instead.

Floating point numbers are parsed with JDK functions `java.lang.Float.parseFloat` and `java.lang.Double.parseDouble`,
but an additional regex validation is done beforehand, which hopefully does not pass incorrect floating point numbers,
and does not reject correct ones.

## Alternatives

* It is possible to use the existing `String.to_Number_` functions inside a try-catch block,
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
* Returning nullable number introduces boxing which may be wasteful.
* Investigate how to introduce `base`/`radix` parameter for string-to-integral type conversion functions [KT-8286](https://youtrack.jetbrains.com/issue/KT-8286):
    * Should it be another parameter or an overload with a different name (like `toIntBase(16)`)?
    * Should it be a single overload with an optional parameter or two overloads?

## Future advancements

* Provide string-to-number conversions in the specified base.
* Provide the same API in Kotlin JS Standard Library.


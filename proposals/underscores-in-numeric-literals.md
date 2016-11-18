# Underscores in Numeric Literals

* **Type**: Design proposal
* **Author**: Kirill Rakhman
* **Status**: Implemented

## Synopsis

Support underscores in numeric literals for separating digit groups.

## Motivation

Numeric values with a high number of digits are hard to read which is why it is proposed to support underscores as digit group separators. This results in easier to read code and doesn't affect the semantics at all.

### Comparison

Current:
```kotlin
val ONE_MILLION = 1000000
```

With underscores:
```kotlin
val ONE_MILLION = 1_000_000
```

Other examples:
```kotlin
val creditCardNumber = 1234_5678_9012_3456L
val socialSecurityNumber = 999_99_9999L
val pi = 3.14_15F
val hexBytes = 0xFF_EC_DE_5E
val hexWords = 0xCAFE_BABE
val maxval = 0x7fff_ffff_ffff_ffffL
val bytes = 0b11010010_01101001_10010100_10010010;
```

## Implementation

The grammar needs to be adapted to allow underscores in `Int`, `Long`, `Float` and `Double` literals. The following rules are copied from the [Java implementation](http://docs.oracle.com/javase/7/docs/technotes/guides/language/underscores-literals.html) since Java 7:

You can place underscores only between digits; you cannot place underscores in the following places:

- At the beginning or end of a number
- Adjacent to a decimal point in a floating point literal
- Prior to an F or L suffix
- In positions where a string of digits is expected

Examples:

```kotlin
val pi1 = 3_.1415F        // Invalid cannot put underscores adjacent to a decimal poval
val pi2 = 3._1415F        // Invalid cannot put underscores adjacent to a decimal poval
val socialSecurityNumber1
  = 999_99_9999_L         // Invalid cannot put underscores prior to an L suffix

val x1 = _52              // This is an identifier, not a numeric literal
val x2 = 5_2              // OK (decimal literal)
val x3 = 52_              // Invalid cannot put underscores at the end of a literal
val x4 = 5_______2        // OK (decimal literal)

val x5 = 0_x52            // Invalid cannot put underscores in the 0x radix prefix
val x6 = 0x_52            // Invalid cannot put underscores at the beginning of a number
val x7 = 0x5_2            // OK (hexadecimal literal)
val x8 = 0x52_            // Invalid cannot put underscores at the end of a number
```

## Comparison to other languages

- Java is the role model for this proposal.
- For C#, [a proposal](https://github.com/dotnet/roslyn/issues/216) exists and according to the [C# 7 Work List of Features](https://github.com/dotnet/roslyn/issues/2136) it is in the group "Some Interest"
- [Ruby](https://docs.ruby-lang.org/en/2.0.0/syntax/literals_rdoc.html) has the same(?) rules as Java
- For Python, [PEP 515](https://www.python.org/dev/peps/pep-0515/) exists and is accepted. Unlike Java, underscores are allowed to the right of the `b` in a binary literal.

## References

[KT-2964](https://youtrack.jetbrains.com/issue/KT-2964) Underscores in integer literals 

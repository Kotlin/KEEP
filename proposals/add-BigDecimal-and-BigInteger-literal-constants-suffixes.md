# Add BigDecimal and BigInteger literal constants suffixes

* **Type**: Design proposal
* **Author**: Edinson E. Padrón Urdaneta
* **Status**: Submitted
* **Prototype**: Not started


## Summary

Support the `g` and `G` suffixes in literal constants for `BigDecimal`s and
`BigInteger`s.

## Similar API review

* Groovy: [Number Type suffixes](http://docs.groovy-lang.org/latest/html/documentation/#_number_type_suffixes)

## Use cases

* Obtain a BigDecimal/BigInteger with ease

```kotlin
val myBigDecimal = 0.1G
val myBigInteger = 1G
```

* Avoid the caveats and inconsistencies produced by the use of floating point
  numbers where high precision is important

```kotlin
0.1 * 3 == 0.3  // False
0.1 * 5 == 0.5  // True

0.1G * 3G == 0.3G  // True
0.1G * 5G == 0.5G  // True
```

* Avoid obtaining the wrong result due to arithmetic overflow and underflow

```kotlin
2147483647 + 1               // -2147483648
-2147483648 - 1              // 2147483647
9223372036854775807 + 1      // -9223372036854775808
-9223372036854775808 - 1     // 9223372036854775807

2147483647G + 1G             // 2147483648G
-2147483648G - 1G            // -2147483649G
9223372036854775807G + 1G    // 9223372036854775808G
-9223372036854775808G - 1G   // -9223372036854775809G
```

## Alternatives

* Transform the number to `BigDecimal`/`BigInteger` just like in Java:

```kotlin
val myBigDecimal = BigDecimal("0.1")
val myBigInteger = BigInteger("1")

BigDecimal("0.1") * BigDecimal("3") == BigDecimal("0.3")  // True
```

* Create the `toBigDecimal` and `toBigInteger` extension functions for the `String` class:

```kotlin
fun String.toBigDecimal(): BigDecimal {
 return BigDecimal(this)
}

fun String.toBigInteger(): BigInteger {
  return BigInteger(this)
}
```

## Parsing

    BigDecimalLiteral
      : FloatLiteral ["g", "G"]

    BigIntegerLiteral
      : IntegerLiteral ["g", "G"]


## Benefits over the alternatives

* "Possible speed improvements vs. Strings. The compiler can parse the number
  and store the data more efficiently (maybe a byte array or serialized) so
  that the actual object can be constructed faster at runtime. Literals used
  multiple times maybe could also all be the same object." `Jonathan Haas`

* "The compiler can check if the number is valid instead of getting a runtime
  error." `Jonathan Haas`

* "Make BigInteger/BigDecimal first class citizens. Operator overloading
  already has helped a lot vs. normal Java when using these classes. Literals
  would make them perfect." `Jonathan Haas`

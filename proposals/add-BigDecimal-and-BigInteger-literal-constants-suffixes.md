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

    0.1G * 3 == 0.3  // True
    0.1G * 5 == 0.5  // True
```


* Avoid obtaining the wrong result due to arithmetic overflow and underflow

```kotlin
  Int.MAX_VALUE + 1    // -2147483648
  Int.MIN_VALUE - 1    // 2147483647
  Long.MAX_VALUE + 1   // -9223372036854775808
  Long.MIN_VALUE - 1   // 9223372036854775807

  Int.MAX_VALUE + 1G   // 2147483648
  Int.MIN_VALUE - 1G   // -2147483649
  Long.MAX_VALUE + 1G  // 9223372036854775808
  Long.MIN_VALUE - 1G  // -9223372036854775809
```

## Alternatives

* Transform the number to `BigDecimal`/`BigInteger` just like in Java:

```kotlin
    val myBigDecimal = BigDecimal("0.1")
    val myBigInteger = BigInteger(1)

    BigDecimal("0.1") * BigDecimal(3) == BigDecimal("0.3")  // True
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


## Additional considerations

The standard library should be enlarged in order to ease arithmetic operations
where one and only one of the operands is a BigDecimal/BigInteger. For instance:

```kotlin
    fun Number.toBigDecimal(): BigDecimal {
      return BigDecimal(this.toString())
    }

    fun Number.toBigInteger(): BigInteger {
      return BigInteger(this.toString())
    }

    operator fun Number.minus(other: BigDecimal): BigDecimal {
      return this.toBigDecimal().subtract(other)
    }

    operator fun Number.plus(other: BigDecimal): BigDecimal {
      return this.toBigDecimal().add(other)
    }

    operator fun BigDecimal.minus(other: Number): BigDecimal {
      return this.subtract(other.toBigDecimal())
    }

    operator fun BigDecimal.plus(other: Number): BigDecimal {
      return this.add(other.toBigDecimal())
    }
```

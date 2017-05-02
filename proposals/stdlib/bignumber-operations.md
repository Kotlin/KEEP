
# Extending Kotlin API for BigInteger and BigDecimal

* **Type**: Standard Library API proposal
* **Author**: Daniil Vodopian
* **Shepherd**: Ilya Gorbunov
* **Status**: Submitted
* **Prototype**: Not started
* **Discussion**: [KEEP-49](https://github.com/Kotlin/KEEP/issues/49)


## Summary

Overload mathematical operations and infix functions to work with BigInteger and BigDecimal.

## Similar API review

* Python's `long` type: https://docs.python.org/2/library/stdtypes.html#numeric-types-int-float-long-complex

## Use cases and motivation

The motivation is to provide a complete and symmetrical set of operators for long arithmetics provided by JDK, namely `BigInteger` for integer computations and `BigDecimal` for floating point computations. 

A "complete" set of operations in this context means that all operators on a basic type are available on the corresponding big type. Operations for `BigInteger` are modelled after `Long`, and operations on `BigDecimal` are modelled after `Double`. The goal is to improve the Java API and not to introduce any additional semantics.

Kotlin stdlib already [contains](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/util/BigNumbers.kt) 12 of 35 proposed functions, but that set is neither complete nor symmetrical. That determines the placement of the additional functions.  

The proposal does not include mixed operations between `BigInteger` and `BigDecimal` since we did not find any evidence of their use.

## Alternatives

* The JDK API for operations on `BigInteger` and `BigDecimal`
* Converting basic numeric types to and from "big" types like `1.toBigInteger()` or `2.toBig()` or possibly `3.big`

## Dependencies

* JDK6 `BigInteger` and `BigDecimal`

## Placement

* Standard Library, `kotlin` package, since there is already a part of the prosed API for [BigInteger](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/java.math.-big-integer/) and [BigDecimal](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/java.math.-big-decimal/)

## Reference implementation

Some of the following functions are already implemented in the stdlib, but listed here to provide the whole picture. Those functions are marked with `/* implemented */`.

>Implementation notes:
>
> - Boxing of primitives should not be a concern
> - Conversion between `BigInteger` and `BigDouble` has no cost except for the obvious creating of a new object
> - `Number` is an open class, so generic functions accepting `Number` may clash with something else
> - default rounding mode is `HALF_EVEN` ([KT-10462] (https://youtrack.jetbrains.com/issue/KT-10462))
> - `toInt`, `toLong`, etc are already implemented on `Number`
> (JDK) Note: For values other than float and double NaN and ±Infinity, this constructor is compatible with the values returned by Float.toString(float) and Double.toString(double). This is generally the preferred way to convert a float or double into a BigDecimal, as it doesn't suffer from the unpredictability of the BigDecimal(double) constructor.
> (JDK) The unsigned right shift operator (>>>) [on BigIntegeer] is omitted, as this operation makes little sense in combination with the "infinite word size" abstraction provided by this class.

#### BigInteger:

    BigInteger.plus(BigInteger)   /* implemented */
    BigInteger.minus(BigInteger)  /* implemented */
    BigInteger.times(BigInteger)  /* implemented */
    BigInteger.div(BigInteger)    /* implemented */
    BigInteger.rem(BigInteger) 
    BigInteger.unaryMinus()       /* implemented */
    BigInteger.unaryPlus()
    
    BigInteger.inv()                                 //use `BigInteger#not`
    BigInteger.and(BigInteger)
    BigInteger.or(BigInteger)
    BigInteger.xor(BigInteger)
    BigInteger.shl(Int)
    BigInteger.shr(Int)
    
    String.toBigInteger()
    BigInteger.toBigDecimal()
    
    Int.toBigInteger()
    Long.toBigInteger()
    // `Float` and `Double` would lose information
    
####BigDecimal:

    BigDecimal.plus(BigDecimal)   /* implemented */
    BigDecimal.minus(BigDecimal)  /* implemented */
    BigDecimal.times(BigDecimal)  /* implemented */
    BigDecimal.div(BigDecimal)    /* implemented */  //use  BigDecimal#divide(divisor, RoundingMode.HALF_EVEN)
    BigDecimal.rem(BigDecimal)    /* implemented */ 
    BigDecimal.unaryMinus()       /* implemented */
    BigDecimal.unaryPlus()

    Int.toBigDecimal() 
    Long.toBigDecimal() 
    Float.toBigDecimal() 
    Double.toBigDecimal()
     
    String.toBigDecimal()
    // BigDecimal.toBigInteger()  /* implemented in JDK */
    
#### Do not have direct analogy in JDK:

    BigInteger.inc()
    BigInteger.dec()

    BigDecimal.inc()
    BigDecimal.dec()
	
## Future advancements

### Universal comparison

Implementing `compareTo` between `BigInteger` and `BigDecimal`. That will not affect interface  `Comparable<>`. Requires only 2 additional function.

### Mixed one-sided operations

Implement mixed one-sided productive operations between "big" types and the basic ones (same logic as with `String.plus(Int)`). Encourages the usage of the "big" types, may be useful for scientific applications. The goal is to write 99% of formulas without explicit conversions. This may be a part of  `kotlinx`.

    val a = BigInteger.valueOf(100)
    val b: BigInteger = a + 30  // Convenient!
    30 + a //Error

Reference implementation requires (at least) 40 methods:

    BigInteger.plus(<number>)
    BigInteger.minus(<number>)
    BigInteger.times(<number>)
    BigInteger.div(<number>)
    BigInteger.rem(<number>)
	
    BigDecimal.plus(Number)
    BigDecimal.minus(Number)
    BigDecimal.times(Number)
    BigDecimal.div(Number)
    BigDecimal.rem(Number)
    
    where `<number>` is one of the 6 basic types. 

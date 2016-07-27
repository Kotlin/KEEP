
# Extending Kotlin API for BigInteger and BigDecimal

* **Type**: Standard Library API proposal
* **Author**: Daniil Vodopian
* **Contributors**: Ilya Gorbunov
* **Status**: Submitted
* **Prototype**: Not started


## Summary

Overload mathematical operations and infix functions to work with BigInteger and BigDecimal.

## Similar API review

* Python's `long` type: https://docs.python.org/2/library/stdtypes.html#numeric-types-int-float-long-complex

## Use cases and motivation

This proposal contains 4 "levels" which can be implemented. Every next level include all operations from previous ones:

**level 1:** operations for `BigInteger`; operations for `BigDecimal`; extension functions for converting to and from basic types. 

This implementation only improves the Java API and does not introduce any additional semantics. The goal here is to have a **complete** set of operators and standard functions comparable to those for basic numeric types. Kotlin stdlib already [contains](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/util/BigNumbers.kt) 12 of 39 proposed functions, but that set is not complete or even semetrical.

    val a = BigInteger.valueOf(239)
    val b: BigDecimal = a.toBigDecimal() + 30.toBigDecimal()

**level 2:** mixed operations for `BigInteger` and `BigDecimal`. 

This implementation carries Kotlin semantic for `Int` and `Double` onto `BigInteger` and `BigDecimal`, but the "big" types are still not easily mixed with the basic ones. This helps to draw a line for less-than-usulal-performant-code. The goal for this level is create a comfortable environment for long-ariphmetic computations. The usecases might be in the finantial computations, where the formulas requare `BigDecimal` but the results are easier storred in `BigInteger`.

    val a = BigDecimal.valueOf(4.0)
    val b = BigInteger.valueOf(4)
    val c = BigInteger.valueOf(2)
    val ab: BigDecimal = a + b   // 4.0 + 4 == 8.0
    val ac: BigDecimal = a / c   // 4.0 / 2 == 2.0
    val bc: BigInteger = b / c   //   4 / 2 == 2
    
**level 3:** mixed one-sided productive operations between "big" types and the basic ones (same logic as with `String.plus(Int)`). 

Encourages the usage of the "big" types, may be useful for scientific applications. The goal is to write 99% of formulas in applications without explicit conversions. This part may be extracted into a separate module or library `kotlinx.science`.

    val a = BigInteger.valueOf(239)
    val b: BigInteger = a + 30
    30 + a //Error

**level 4:** unifies  `BigInteger` and `BigDecimal` with the basic types.

The goal is "extend" the notion of basic types on `BigInteger` and `BigDecimal` (except for literals and some compiler inference). May be in a separate library (see level-3)

## Alternatives

* The JDK API for operations on `BigInteger` and `BigDecimal` (level 1+)
* Converting  `BigInteger` and `BigDecimal` back and forth with `BigDecimal(bigInt)` and `bigDec.toBigInteger()` (level 2+)
* Converting basic numeric types to and from "big" types like `1.toBigInteger()` or `2.toBig()` or possibly `3.big`

## Dependencies

* JDK6 `BigInteger` and `BigDecimal`

## Placement

* Standard Library, `java.math`, since there is already a part of the prosed API for [BigInteger](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/java.math.-big-integer/), [BigDecimal](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/java.math.-big-decimal/)

* `kotlinx.science` for level-3 and level-4


## Reference implementation

Every next level implies functions from all the previous ones. 

Some of the following functions already implemented in the stdlib, but listed here to provide the whole picture. Those functions are marked with `/* implemented */`.


####Implementation notes:
    -Boxing of primitives should not be a concern
    -Conversion between `BigInteger` and `BigDouble` has no cost except for the obvious creating of new objects
    -`BigInteger is Number`, so generic functions accepting `Number` may clash with something else

###level 1
39 methods, 12 already implemented

#### BigInteger (20, 5)

    BigInteger.plus(BigInteger)   /* implemented */
    BigInteger.minus(BigInteger)  /* implemented */
    BigInteger.times(BigInteger)  /* implemented */  // optimize for TEN
    BigInteger.div(BigInteger)    /* implemented */  // optimize for TEN
    BigInteger.mod(BigInteger)                       // optimize for TEN, also use `BigInteger#mod`
    BigInteger.unaryMinus()       /* implemented */
    BigInteger.unaryPlus()
    
    BigInteger.inv()  // uses `BigInteger#not`
    BigInteger.and(BigInteger)
    BigInteger.or(BigInteger)
    BigInteger.xor(BigInteger)
    BigInteger.shl(Int)
    BigInteger.shr(Int)
    
    BigInteger.toInt()
    BigInteger.toLong()
    BigInteger.toFloat()
    BigInteger.toDouble()

    Number.toBigInteger()  // BigInteger.valueOf(this.toLong())
    String.toBigInteger()
    BigInteger.toBigDecimal()
    
####BigDecimal (14, 7)

    BigDecimal.plus(BigDecimal)   /* implemented */
    BigDecimal.minus(BigDecimal)  /* implemented */
    BigDecimal.times(BigDecimal)  /* implemented */  // optimize for TEN
    BigDecimal.div(BigDecimal)    /* implemented */  // optimize for TEN
    BigDecimal.mod(BigDecimal)    /* implemented */  // optimize for TEN, use `BigInteger#remainder`
    BigDecimal.unaryMinus()       /* implemented */
    BigDecimal.unaryPlus()
    
    BigDecimal.toInt()
    BigDecimal.toLong()
    BigDecimal.toFloat()
    BigDecimal.toDouble()

    Number.toBigDecimal()  // if(this is Double || this is Float) ... else ...
    String.toBigDecimal()
    // BigDecimal.toBigInteger()  /* implemented in JDK */
    
#### Do not have direct analogy in JDK: (5, 0)

    BigInteger.ushr(Int)
    BigInteger.inc()
    BigInteger.dec()

    BigDecimal.inc()
    BigDecimal.dec()

###level 2
12 methods

    BigInteger.compareTo(BigDecimal)
    BigInteger.plus(BigDecimal)
    BigInteger.minus(BigDecimal)
    BigInteger.times(BigDecimal)
    BigInteger.div(BigDecimal)
    BigInteger.mod(BigDecimal)
	
    BigDecimal.compareTo(BigInteger)
    BigDecimal.plus(BigInteger)
    BigDecimal.minus(BigInteger)
    BigDecimal.times(BigInteger)
    BigDecimal.div(BigInteger)
    BigDecimal.mod(BigInteger)
	
###level 3
40 methods

    BigInteger.plus(<number>)
    BigInteger.minus(<number>)
    BigInteger.times(<number>)
    BigInteger.div(<number>)
    BigInteger.mod(<number>)
	
    BigDecimal.plus(Number)
    BigDecimal.minus(Number)
    BigDecimal.times(Number)
    BigDecimal.div(Number)
    BigDecimal.mod(Number)
    
    `<number>` is one of the 6 basic types: `Int`, `Long`, `Short`, `Byte`, `Double`, `Float`
    
    We cannot use a generic approach like `BigInteger.plus(Number)` because if the operand is an integer type (`Int`, `Long`, `Short` or `Byte`), we should return `BigInteger`, not `BigDecimal` like in other cases.
    
###level 4
47 methods

    `<number>`.plus(BigInteger)
    `<number>`.minus(BigInteger)
    `<number>`.times(BigInteger)
    `<number>`.div(BigInteger)
    `<number>`.mod(BigInteger)
    
    Number.plus(BigDecimal)
    Number.minus(BigDecimal)
    Number.times(BigDecimal)
    Number.div(BigDecimal)
    Number.mod(BigDecimal)
    
    Number.compareTo(BigInteger)
    Number.compareTo(BigDecimal)

    BigInteger.rangeTo(BigInteger)
    Int.rangeTo(BigInteger)
    Long.rangeTo(BigInteger)
    Short.rangeTo(BigInteger)
    Byte.rangeTo(BigInteger)
	
    BigInteger.downTo(BigInteger)
    BigInteger.downTo(Int)
    BigInteger.downTo(Long)
    BigInteger.downTo(Short)
    BigInteger.downTo(Byte)
    
    `<number>` is one of the 6 basic types: `Int`, `Long`, `Short`, `Byte`, `Double`, `Float`. See level-3 for why we cannot rely on `Number` here. 

## Unresolved questions

* The JDK implementation of `equals` compares only values of the same class. In the cases of `BigDecimal` also the same precision is required, which is inconsistent with `compareTo` and [causes bugs](http://stackoverflow.com/questions/6787142/bigdecimal-equals-versus-compareto/6787166#6787166).
	* Leave the JDK `equals` as is
	* Provide a special infix function (`eq`) with a sensible implementation

* `compareTo` is only implemented for the same type. We can extend that. That will not affect interface  `Comparable<>`.


## Future advancements

* We are free to start with implementing level-1 or level-2 and add a level up later

-------

# Appendix: Questions to consider

## Contracts

* The contracts are the same as in JDK (except for maybe `compareTo`)
* Behaviour for mixed typed operations should mimic the one for basic Kotlin types

## Compatibility impact

* Using (trivial) JDK methods instead of operators may or may not be considered a style warning

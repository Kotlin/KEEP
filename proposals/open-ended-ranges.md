# Open-ended ranges and rangeUntil operator

* **Type**: Design proposal / Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Contributors**: Roman Elizarov, Vsevolod Tolstopyatov, Abduqodiri Qurbonzoda, Leonid Startsev, Egor Tolstoy
* **Status**: Experimental
* **Prototype**: Implemented in 1.7.20-Beta
* **Related issues**: [KT-15613](https://youtrack.jetbrains.com/issue/KT-15613)
* **Discussion**: [KEEP-314](https://github.com/Kotlin/KEEP/issues/314)

## Summary

Kotlin since its beginning has the `..` operator to express a range of values.
Similar to natural languages, the expression `a..b` means the range that includes both of its bounds.

However, in programming it's often the case that a typical data structure has its indices starting at 0 
and ending one before the number of elements in it, so to iterate these indices, the range `0..(size - 1)` has to be used.
For such a common use case, Kotlin standard library provides various shortcut functions, such as 
the `indices` extension property available on many data structures returning that `0..(size - 1)` range,
the `lastIndex` extension property returning the last index value, namely `size - 1`, and finally the `until` infix function
allowing to instantiate a range of integral values like `0 until size` which is equivalent to `0..(size - 1)`.

Despite all of this, due to asymmetry between `..` and `until`, the former is used more often, even in cases where the latter would be more clear.

We have conducted a UX research that showed that Kotlin users may experience troubles identifying whether the ranges created with 
the `..` operator and `until` function have their upper bound excluded or included. We also evaluated how introducing new operators
for range expressions may improve this situation, and it turned out that the effect of that can be significant: 
the misinterpretation rate was less by ~20-30% with them.

For the above reason, we propose to introduce the operator `..<` in Kotlin that would be 
on par with the `..` operator and make it very clear that the upper bound is not included.

## Use cases

Currently, the use cases of the new operator are mostly covered by the `until` function, that corrects the upper bound
returning a closed range of integral values that would be equivalent to an open-ended range. 
However, the `until` function is available only for the types where finding the successor to a value is done by adding 1 to that value, 
such as `Int`, `Long`, `Char`, 
and having the new operator gives a chance for introducing open-ended ranges for those type that didn't have it before.

### Iterating indices of a data structure

Typically, data structures start indexing at zero and thus most indexed loops on these data structures
have the form of `for (index in 0 until size)`. Such a common use case deserves introducing a designated operator with
a clear meaning.

### Discretization and bucketing of continuous values

Discretization and binning require splitting the domain of a continuous value to a number of non-overlapping intervals.
Such intervals are usually chosen as ranges that include their lower bound and exclude the upper bound, so that two 
adjacent ranges neither have a point where they overlap, nor a point between them that is not contained in these ranges.  

Even sometimes when the value is already discrete, for example, when it is expressed as a `Double` number, 
and it is possible to emulate a half-open range with a closed one by adjusting one of its bounds, 
in practice it is not convenient to work with such ranges:

```kotlin
val equivalent = 1.0..2.0.nextDown() // contains the same values as 1.0..<2.0 range
println(eqivalent)  // 1..1.9999999999999998
```

## Similar API review

### Languages that distinguish end-inclusive and end-exclusive ranges

- Swift: `...` end-inclusive range, `..<` end-exclusive, supports one-sided ranges 
- Ruby: `..` end-inclusive range, `...` end-exclusive, supports one-sided ranges
- Groovy: `..` end-inclusive range, `..<` end-exclusive, `<..` start-exclusive, `<..<` both bounds-exclusive range
- Rust: `..=` end-inclusive range, `..` end-exclusive, supports one-sided ranges

### Libraries for representing ranges

- Guava library provides the single `Range` class capable of representing full variety of mathematical range types:
  closed, open, unbounded. The range is defined by the properties `hasLower/UpperBound` which indicate whether the range is bounded or not,
  and then with `lower/upperEndPoint` and `lower/upperBoundType` properties which can be obtained only if the range has that bound.
  See https://github.com/google/guava/wiki/RangesExplained for details.
  
- Groovy supports [_number_ ranges](https://docs.groovy-lang.org/latest/html/api/groovy/lang/NumberRange.html) (including `IntRange`) 
  with bounds being individually excluded or included. This is indicated by `inclusiveLeft` and `inclusiveRight` properties. 
  However, the base [Range](https://docs.groovy-lang.org/latest/html/api/groovy/lang/Range.html) interface doesn't indicate inclusiveness and 
  has somewhat contradictory contract of `containsWithinBounds` function.

- Swift has the base protocol [`RangeExpression`](https://developer.apple.com/documentation/swift/rangeexpression/) which is implemented
  by the open-ended [`Range`](https://developer.apple.com/documentation/swift/range),
  the closed [`ClosedRange`](https://developer.apple.com/documentation/swift/closedrange),
  and also the one-sided `PartialRangeFrom` (start-inclusive), `PartialRangeThrough` (end-inclusive), `PartialRangeUpTo` (end-exclusive) ranges.
  The base protocol provides operations of checking whether the range contains a value and slicing a collection 
  (i.e. producing the closed range of indices of the collection with the matching index type that are contained in the range).

- Rust provides 6 structs in the standard library to represent ranges, varying by their boundness and inclusiveness of the end bound:
  [`Range`](https://doc.rust-lang.org/std/ops/struct.Range.html), [`RangeInclusive`](https://doc.rust-lang.org/std/ops/struct.RangeInclusive.html), 
  [`RangeFrom`](https://doc.rust-lang.org/std/ops/struct.RangeFrom.html), 
  [`RangeTo`](https://doc.rust-lang.org/std/ops/struct.RangeTo.html), [`RangeToInclusive`](https://doc.rust-lang.org/std/ops/struct.RangeTo.html), 
  [`RangeFull`](https://doc.rust-lang.org/std/ops/struct.RangeTo.html).
  
  There's also a crate that provides a more generic [`GenericRange`](https://docs.rs/ranges/latest/ranges/struct.GenericRange.html) implementation
  that covers all of the above range variants and allows to express other type of ranges, like one with an excluded start bound.
  

- [kotlin-statistics](https://github.com/thomasnield/kotlin-statistics/blob/master/src/main/kotlin/org/nield/kotlinstatistics/range) 
  library provides the base `Range` type and individual types for each combination of included/excluded bounds:
  `OpenRange`, `OpenClosedRange`, `ClosedOpenRange`, `XClosedRange`.
  
- [kotlinx-interval](https://github.com/Whathecode/kotlinx.interval) library uses approach similar to Groovy, but in a more generic fashion:
  the base `Interval` type indicates whether bounds are inclusive or exclusive with the boolean properties
  `isStartIncluded`/`isEndIncluded`


## Language changes

In order to use the new `..<` operator in code and be able to overload it for user types, 
we provide the following operator convention:

```kotlin
operator fun FromType.rangeUntil(to: ToType): RangeType
```

Similar to `rangeTo` operator, this operator convention can be satisfied either with a member
or an extension function taking `FromType`, the type of the first operand, as the receiver, and `ToType`, the type of the second operand, as the parameter. 
Usually `FromType` and `ToType` refer to the same type.

## API Details

When introducing `rangeUntil` operator support in the standard library, we pursue the following goals:

- for consistency, `rangeUntil` operator should be provided for the same types that currently have `rangeTo` operator;
- `rangeUntil` should return an instance of type representing open-ended ranges;
- it should be an easy and compatible change to replace the existing `until` function with the `..<` operator. 
  Therefore, the type returned by `rangeUntil` should be the same type or a subtype of the type that is currently
  returned by `until` for the given argument types.
  
The following new types and operations will be introduced in the `kotlin.ranges` packages in the common Kotlin standard library.
  
### OpenEndRange interface

The new interface to represent open-ended ranges is very similar to the existing `ClosedRange<T>` interface:

```kotlin
interface OpenEndRange<T : Comparable<T>> {
    // lower bound
    val start: T
    // upper bound, not included in the range
    val endExclusive: T
    
    operator fun contains(value: T): Boolean = value >= start && value < endExclusive
    
    fun isEmpty(): Boolean = start >= endExclusive
}
```

The difference is that it has the property `endExclusive` for the upper bound instead of `endInclusive` and uses different comparison
operators when comparing with the upper bound.

### Implementing OpenEndRange in the existing iterable ranges

Currently, in a situation when a user needs to get a range with excluded upper bound, they use `until` function producing
a closed iterable range effectively with the same values. In order to make these ranges acceptable in the new API that takes
`OpenEndRange<T>`, we want to implement that interface in the existing iterable ranges: `IntRange`, `LongRange`, `CharRange`,
`UIntRange`, `ULongRange`. So they will be implementing both `ClosedRange<T>` and `OpenEndRange<T>` interfaces simultaneously.

```kotlin
class IntRange : IntProgression(...), ClosedRange<Int>, OpenEndRange<Int> {
    override val start: Int
    override val endInclusive: Int
    override val endExclusive: Int
}
```

There's a subtlety in implementing `endExclusive` property in such ranges: usually it returns `endInclusive + 1`, but 
there can be such ranges where `endInclusive` is already the maximum value of the range type, and so adding one to it
would overflow.

We decided that in such cases the reasonable behavior would be to throw an exception from the `endExclusive` property 
getter. The possibility of that will be documented in the base interface, `OpenEndRange`, and additionally the implementation
of that property will be deprecated in the existing concrete range classes.

### rangeUntil operators for the standard types

`rangeUntil` operators will be provided for the same types and their combinations that currently have `rangeTo` operator defined.
For the purposes of prototype, we provide them as extension functions, but for consistency we plan to make them members 
later, before stabilizing the open-ended ranges API.

### Generic open-ended ranges of comparable values

Similar to closed ranges, there will be a function instantiating an open-ended range from any two values of a comparable type:

```kotlin
operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
```

### Specialized open-ended ranges of floating point numbers

There also will be two static specializations of `rangeUntil` operator for `Double` and `Float` types of arguments.
They are special in how they compare values of their bounds with the value passed to `contains` and between themselves,
so that a range where either bound is NaN is empty, and the `NaN` value is not contained in any range.  

```kotlin
operator fun Double.rangeUntil(that: Double): OpenEndRange<Double>
operator fun Float.rangeUntil(that: Float): OpenEndRange<Float>
```

### Equality of open-ended ranges

Similar to closed ranges, the `OpenEndRange` interface does not specify contract for `equals`/`hashCode` implementations,
however its concrete implementations can do that. For example, an open-ended range of double values equal to another such range
when bounds are respectively equal to each other, or to any empty range of doubles, when it is empty itself.

Also, as a consequence of implementing both `OpenEndRange` and `ClosedRange` 
in concrete range types for the standard integral types and `Char` type,
in these range types an open-ended range is equal to the closed range with the same `start` value and `endExclusive` equal to `endInclusive + 1`:
```kotlin
0..<10 == 0..9 // true
```

## Experimental status

Both the language feature of `rangeUntil` operator and its supporting standard library API
are to be released in Kotlin 1.7.20 in [Experimental](https://kotlinlang.org/docs/components-stability.html#stability-levels-explained) status.

In order to use `..<` operator or to implement that operator convention for own types,
the corresponding language feature should be enabled with the `-XXLanguage:+RangeUntilOperator` compiler argument.

The new API elements introduced to support the open-ended ranges of the standard types
require an opt-in as usual experimental stdlib API:
`@OptIn(ExperimentalStdlibApi::class)`. Alternatively, a compiler argument `-opt-in=kotlin.ExperimentalStdlibApi`
can be specified.

We recommend library developers to [propagate](https://kotlinlang.org/docs/opt-in-requirements.html#propagating-opt-in) the opt-in requirement,
if they use experimental API in their code.

## Alternatives

### Introduce the operator `..=` as an alias to `..`

When conducting the UX research, we have also evaluated what effect the operator `..=`
which basically means the same as `..` could bring.

While it has shown that `..=` operator similarly to `..<` reduces the number of errors in interpretation of range expressions,
the effect of that doesn't overweight negative effects of redundancy brought to the language by having both `..` and `..=` doing the same
and migration effort required to change `..` to `..=` in Kotlin code bases.


### Adapt the existing range interface for open-ended ranges

Instead of introducing a new interface for open-ended ranges, reuse the existing range interface and 
add a boolean parameter indicating whether the bound is included or excluded.

- pro: such approach is similar to one used in Groovy and Guava and allows to support ranges with an open lower bound later
without introducing a new type
- con: the existing users of `ClosedRange` type would be very unprepared and surprised if the range begins to exclude its bound
  indicating that with a new boolean property.
- con: it would be impossible to represent both closed and equivalent open-ended integral range with the same instance,
  so changing `until` to `..<` would be a more painful change.


## Open questions and considerations

### Search friendliness

Usually internet search engines disregard punctuation characters, so it might be
hard to find what `..<` means in Kotlin. For example, currently the search https://www.google.com/search?q=swift+operator+..%3C
doesn't show any relevant results about that Swift range operator.

### Common supertype of ClosedRange and OpenEndRange

If the range of a value is closed, for example `0.0..1.0`, then splitting it into a number of ranges 
will require a combination of open-ended ranges and one closed range in the end, e.g.:
`0.0..<0.1`, `0.1..<0.2`, ..., `0.9..1.0`.
Putting these ranges into a list would make its element type inferred to `Any`, and that would make working with elements inconvenient.

Introducing a more dedicated common supertype of the `ClosedRange` and `OpenEndRange` could help in this situation,
however, it's unclear what useful operations such supertype would provide.

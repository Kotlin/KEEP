# Exclusive Ranges and Range Interface


* **Type**: Standard Library API proposal
* **Author**: Thomas Nield
* **Contributors**: Thomas Nield, Burkhard Mittelbach
* **Status**: Submitted
* **Prototype**: Not started


## Summary

After doing some substantial exploration using Kotlin for [statistics](https://github.com/thomasnield/kotlin-statistics) and [stochastic optimization](https://github.com/thomasnield/traveling_salesman_demo), I think there are opportunties to take advantage of a better implementation for ranges, and be able to support an `until` infix operator implementation for `Double` and `Float`. 

Kotlin's stdlib has an implementation for `ClosedRange`, but not `OpenRange`, `OpenStartRange`, and `OpenEndRange`. I believe the latter items need to be implemented at least for continuous `Double` and `Float` ranges, where the exclusive start/end point cannot be achieved discretely with a `ClosedRange`.

The `ClosedRange`, `OpenRange`, `OpenStartRange`, and `OpenEndRange` should also share a common `Range` parent, so they all can be mixed together in a `List<Range>` (i.e. [histograms](https://en.wikipedia.org/wiki/Histogram) or [probability density functions](https://en.wikipedia.org/wiki/Probability_density_function)).

Also proposed is deprecating the `start` property in `ClosedRange` for `startInclusive`. The `Range` interface would also benefit from having `upperBound` and `lowerBound` properties that do not define a specific inclusivity/exclusivity behavior. 


## Similar API review


Kotlin's stdlib already contains `ClosedRange` implementations that can be invoked with a `..` as in `0..10`. 

Kotlin also indirectly supports an end-exclusive discrete range using a `ClosedRange`, and can be invoked with `until`, such as `0 until 10`. 

I believe that having an end-exclusive `until` implemented for `Double` and `Float` makes sense. However,  `OpenRange`, `OpenStartRange`, and `OpenEndRange` will be needed to support the continuous nature of `Double` and `Float`. 

## Use cases


### Binning and Bucketing Continuous Ranges

[Discretization of continuous features](https://en.wikipedia.org/wiki/Discretization_of_continuous_features) is a common mathematical operation. This task comes up in mathematical modeling, basic statistics, probability, and machine learning. 


For instance, I may bin `Sale` objects on their `price` into interval buckets of size `20.0`. Putting an `until` between two `Double` or `Float` values will return an `OpenEndRange`. 

```kotlin 
import java.time.LocalDate

fun main(args: Array<String>) {

    data class Sale(val accountId: Int, val date: LocalDate, val price: Double)

    val sales = listOf(
            Sale(1, LocalDate.of(2016,12,3), 180.0),
            Sale(2, LocalDate.of(2016, 7, 4), 140.2),
            Sale(3, LocalDate.of(2016, 6, 3), 111.4),
            Sale(4, LocalDate.of(2016, 1, 5), 192.7),
            Sale(5, LocalDate.of(2016, 5, 4), 137.9),
            Sale(6, LocalDate.of(2016, 3, 6), 125.6),
            Sale(7, LocalDate.of(2016, 12,4), 164.3),
            Sale(8, LocalDate.of(2016, 7,11), 144.2)
            )

    //bin by double ranges
    val binned = sales.binByDouble(
            valueSelector = { it.price },
            binSize = 20.0,
            rangeStart = 100.0
    )
	
val ranges = binned.ranges // should return endExclusive ranges

    ranges.forEach(::println) 
	
	/*
	OUTPUT:
	100..<120
	120..<140
	140..<160
	160..<180
	180..<200
	*/
}
```

I can also define my own ranges for a continuous histogram of values. I should have the option of putting in a `ClosedRange` so the last bin can capture the final end boundary. 

```kotlin 
val histogramBins = listOf(
		0.0 until 0.2,
		0.2 until 0.4,
		0.4 until 0.6,
		0.6 until 0.9,
		0.9..1.0 
)
```

To support a collection having both `ClosedRange` and `OpenEndRange` types, extracting a common `Range` interface might be necessary (with `contains()`, `isEmpty()`, `lowerBound`, and `upperBound` functions and properties). 


### Probability and Weighted Sampling

Another use case is random sampling with a probability density function in some form. While it is unlikely the end/start of each continuous range will be selected in a random sampling, it is still not kosher for those points to be inclusive and overlap on each other. 

Below is an implementation of a `WeightedDice` that takes `T` sides with an associated probability. It uses an `OpenEndDoubleRange` with an `endExclusive`. While it might be probabilistically negligible, there is no chance a random `Double` will belong to two ranges because it falls on a border. Also, even if a `ClosedRange` is not doing damage to fair sampling, it is still misleading especially if those ranges are exposed via the API. 

```kotlin 
/**
 *  Assigns a probabilty to each distinct `T` item, and randomly selects `T` values given those probabilities.
 *  
 *  In other words, this is a Probability Density Function (PDF) for discrete `T` values
 */
class WeightedDice<T>(val probabilities: Map<T,Double>) {

    constructor(vararg values: Pair<T, Double>): this(
            values.toMap()
    )

    private val sum = probabilities.values.sum()

    val rangedDistribution = probabilities.let {

        var binStart = 0.0

        it.asSequence().sortedBy { it.value }
                .map { it.key to OpenDoubleRange(binStart, it.value + binStart) }
                .onEach { binStart = it.second.endExclusive }
                .toMap()
    }

    /**
     * Randomly selects a `T` value with probability
     */
    fun roll() = ThreadLocalRandom.current().nextDouble(0.0, sum).let {
        rangedDistribution.asIterable().first { rng -> it in rng.value }.key
    }
}
```



## Alternatives

The alternative would be for the [Kotlin-Statistics](https://github.com/thomasnield/kotlin-statistics) library to continue making [its own `Range` implementations](https://github.com/thomasnield/kotlin-statistics/blob/master/src/main/kotlin/org/nield/kotlinstatistics/Ranges.kt). This is not desirable because this would make its ranges incompatible with stdlib's, and it does not get the language support with operators like `..` and `until`. 


## Dependencies

What are the dependencies of the proposed API:

_None_

## Placement

package kotlin.ranges

## Reference implementation

You can find an `OpenEndRange` implementation [here in Kotlin-Statistics](https://github.com/thomasnield/kotlin-statistics/blob/master/src/main/kotlin/org/nield/kotlinstatistics/Ranges.kt), although there it is called an `OpenRange`. There is no `Range` parent either. 


## Unresolved questions

If we were to extract a `Range` parent for `ClosedRange`, `OpenRange`, `OpenStartRange`, and `OpenEndRange`, what should it contain? 

Here is one proposed implementation: a `lowerBound` and `upperBound` should be defined to generalize the start and end, but not indicate whether they are inclusive or exclusive. This allows a `List<Range>` to still have access to the start and end values, regardless if they are inclusive or exclusive. 

```kotlin 
/**
 * A `Range` is an abstract interface common to all ranges, regardless of their inclusive or exclusive nature
 */
public interface Range<T: Comparable<T>> {

    /**
     * The minimum value in the range, regardless if it is inclusive or exclusive
     */
    public val lowerBound: T
    /**
     * The maximum value in the range, regardless if it is inclusive or exclusive
     */
    public val upperBound: T

    /**
     * Checks whether the specified [value] belongs to the range.
     */
    public operator fun contains(value: T): Boolean

    /**
     * Checks whether the range is empty.
     */
    public fun isEmpty(): Boolean
}
```


The child implementations can still have their own properties such as `startInclusive` `endExclusive`, `endInclusive`, `endExclusive`, and `startExclusive`, but they should have the same values as their respective `lowerBound` and `upperBound` counterparts. This also begs the question if `start` should be deprecated and explicitly be labeled `startInclusive` or `startExclusive`. 


```kotlin 
public interface OpenEndRange<T: Comparable<T>>: Range<T> {
    /**
     * The minimum value in the range.
     */
    public val startInclusive: T get() = lowerBound

    /**
     * The maximum value in the range (inclusive).
     */
    public val endExclusive: T get() = upperBound

    /**
     * Checks whether the specified [value] belongs to the range.
     */
    override operator fun contains(value: T): Boolean = value >= start && value < endExclusive

    /**
     * Checks whether the range is empty.
     */
    override fun isEmpty(): Boolean = start > endExclusive
}



public interface ClosedRange<T: Comparable<T>>: Range<T> {
    /**
     * The minimum value in the range.
     */
    public val startInclusive: T get() = lowerBound

    /**
     * The maximum value in the range (inclusive).
     */
    public val endInclusive: T get() = upperBound

    /**
     * Checks whether the specified [value] belongs to the range.
     */
    override operator fun contains(value: T): Boolean = value >= start && value <= endInclusive

    /**
     * Checks whether the range is empty.
     */
    override fun isEmpty(): Boolean = start > endInclusive
}


```

## Future advancements

The `Range` interface maybe can have additional properties describing inclusivity/exclusivity, such as `isStartInclusive`. This may add clutter so we should consider this augmentation carefully. 

It might also be beneficial to explore progressions with continuous numeric types, so expressions like `10.0 until 2.0 step 0.5` can be used. This can have a broad range of use cases, including [temperature schedules for simulated annealing](https://github.com/thomasnield/traveling_salesman_demo/blob/master/src/main/kotlin/Model.kt#L290-L292). 


-------


## Naming

Considering there is a `ClosedRange`, the additional types `OpenRange`, `OpenEndRange`, and `OpenStartRange` follow a stdlib-like convention. 

## Contracts

For the `OpenRange` and `OpenEndRange`  implementations, the `lowerBound` must be *less than* the `upperBound`. Otherwise the range should be empty. 

For the `OpenStartRange`  implementations, the `lowerBound` must be *less than or equal to* the `upperBound`. Otherwise the range should be empty. 


## Compatibility impact

The usage of `start` in `ClosedRange` may need to be deprecated in favor of explicit `startInclusive` and `startExclusive`. 

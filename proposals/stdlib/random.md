# Multiplatform random number generators

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Contributors**: Roman Elizarov, Vsevolod Tolstopyatov, Pavel Punegov
* **Status**: Implemented in Kotlin 1.3
* **Prototype**: Implemented
* **Related issues**: [KT-17261](https://youtrack.jetbrains.com/issue/KT-17261)
* **Discussion**: [KEEP-131](https://github.com/Kotlin/KEEP/issues/131)


## Summary

Introduce an API in the Standard Library to:

 - generate pseudo-random numbers conveniently and efficiently without much ceremony, 
 - generate reproducible pseudo-random number sequences,
 - allow using custom pseudo-random number algorithms or even true sources of randomness
 with the same API.

## Similar API review

* Java: [`java.util.Random`](https://docs.oracle.com/javase/6/docs/api/java/util/Random.html), 
[`java.util.concurrent.ThreadLocalRandom`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadLocalRandom.html)
* Scala: [`scala.util.Random`](https://www.scala-lang.org/api/current/scala/util/Random.html)
* Apache commons: [RandomGenerator](http://commons.apache.org/proper/commons-math/apidocs/org/apache/commons/math4/random/RandomGenerator.html)
* [Apache RNG](https://commons.apache.org/proper/commons-rng/userguide/rng.html): 
[UniformRandomProvider](https://commons.apache.org/proper/commons-rng/commons-rng-client-api/apidocs/org/apache/commons/rng/UniformRandomProvider.html)

## Motivation and use cases

While there's no problem with generating random numbers in Kotlin/JVM (aside that `ThreadLocalRandom`
isn't available prior to JDK 7), there's no API yet to do that in Kotlin for other targets, 
and more importantly there's no way to do that consistently in multiplatform code.

The use cases of random API are established, but here we name a few:

* Sampling a random element from a collection
* Collection shuffling
* Random game content and level generation
* Simulations requiring a source of random numbers
* Using randomness in tests to cover more code execution paths

## Alternatives

The current alternatives require finding some platform libraries and providing common multiplatform API
for them or implementing random generators by hand. 

## Placement

This API shall be placed into the Kotlin Standard Library. Regarding the package the following options were considered:

- Use the existing `kotlin` package which is imported by default.
- Use the existing `kotlin.math` package
- Create new `kotlin.random` package and place API there.

We decided to stick with the latter as it would be more explorable in docs: all the related API will be shown close to each other.
Also it's more future proof in regard to possible API additions.

A downside of a new package is that it will require new imports, especially if there will be extensions.
A class named `Random` from that package can be confused with `java.util.Random` in the completion list.
It should be investigated whether we could make that package imported by default in Kotlin 1.3.

## Reference implementation

The initial implementation is [available](https://github.com/JetBrains/kotlin/compare/4d51d13~1...f7337cc) in 1.3-M1 branch.

## Dependencies

What are the dependencies of the proposed API:

* JDK: `java.util.Random` in JDK 6,7, `ThreadLocalRandom` in JDK 8+
* JS: `Math.random` function as the source of randomness seed

## API details

The following code snippet summarizes the public API introduced by this proposal.

```kotlin
abstract class Random {

    abstract fun nextBits(bitCount: Int): Int

    open fun nextInt(): Int 
    open fun nextInt(bound: Int): Int 
    open fun nextInt(origin: Int, bound: Int): Int
    open fun nextInt(range: IntRange): Int

    open fun nextLong(): Long
    open fun nextLong(bound: Long): Long
    open fun nextLong(origin: Long, bound: Long): Long
    open fun nextLong(range: LongRange): Long
    
    open fun nextBoolean(): Boolean
   
    open fun nextDouble(): Double
    open fun nextDouble(bound: Double): Double
    open fun nextDouble(origin: Double, bound: Double): Double
    open fun nextFloat(): Float
    
    open fun nextBytes(array: ByteArray, fromIndex: Int = 0, toIndex: Int = array.size): ByteArray
    open fun nextBytes(array: ByteArray): ByteArray
    open fun nextBytes(size: Int): ByteArray 

    // The default random number generator
    // Safe to use concurrently in JVM, and to transfer between workers in Native    
    companion object : Random() {
        // overrides all methods delegating them to some platform-specific RNG implementation
    }
}

// constructor-like functions to get a reproducible RNG implementation with the specified seed
fun Random(seed: Int): Random
fun Random(seed: Long): Random

// JVM-only: functions to wrap java.util.Random in kotlin.random.Random and vice-versa
fun java.util.Random.asKotlinRandom(): Random
fun Random.asJavaRandom(): java.util.Random
```

### Default random number generator

The default RNG implementation can be obtained from the `Random` class companion object.
In fact that object is just an implementation of `Random`, so one can call its methods like

```kotlin
Random.nextInt(10)
``` 

There were two alternative options of getting the default RNG considered:

- Having a top-level property like `random` or `defaultRandom`
    - That was rejected because of high possibility of shadowing that property with a more local member

- Having a property in `Random` companion object, so that its methods are called like
    ```kotlin
    Random.default.nextInt(10)
    ```
    - That was rejected as too verbose, similar to `ThreadlocalRandom.current().nextInt()` calls

The default implementation can be used when it doesn't matter what particular RNG implementation is required
and what the state of that implementation is.

**Platform specifics**

The default implementation may be different in different platforms:

- In JVM it's a wrapper around `ThreadLocal<Random>` or `ThreadLocalRandom.current()` 
- In JS it's a repeatable implementation (see below) seeded with a random number

**Serialization**

In JVM the default implementation is serialized like a singleton, so after the deserialization it should point 
to the same companion object instance.


### Repeatable pseudo-random number generator

Two constructor-like functions are provided to obtain a repeatable sequence generator, 
seeded with the specified number:

```kotlin
val random = Random(seed)
...
random.nextInt(10)
```

The `seed` can be either `Int` or `Long` number.

Two random number generators obtained from the same seed produce the same sequence of numbers.

**Platform specifics**

- All platforms use the same implementation, so a sequence produced from the same seed is same on all platforms.
- In JVM the generator obtained this way is not thread-safe and should not be used concurrently.
- In Native the generator is not freezable (it becomes not functional when freezed) and should not be shared between workers. 

**Serialization**

In JVM the repeatable generator is serialized by saving its state, so after the deserialization it will produce numbers
from the state it was before the serialization.

**Implementation requirements**

There are a plenty of pseudo-random generator implementations to choose from. 
We decided to provide one that is good enough given the following constraints:

- an implementation should be simple to be easily verifiable
- it shouldn't require 64-bit `Long` type as it may have performance implications in Kotlin/JS, where longs are not supported natively
- it shouldn't require many operations or complex operations to generate the next number
- shouldn't have a lot of state to maintain
- should perform well in randomness tests
- the period of the generator should be long

Basing on these constraints we have chosen XORWOW algorithm. (George Marsaglia. Xorshift RNGs. Journal of Statistical Software, 8(14), 2003. Available at http://www.jstatsoft.org/v08/i14/paper.)

### Custom implementations

All methods in the `Random` abstract class have some reasonable implementations except `nextBits`, 
which is the abstract one, so to implement a custom random number generator only that one has to be overridden.

However if a custom generator can provide a more effective implementation for the other methods,
it can override them too, since they all are open.

### Bridging JVM Random API

On JVM there's an extension function `asKotlinRandom()` to wrap any `java.util.Random` implementation into `kotlin.random.Random`
and `asJavaRandom` to wrap Kotlin `Random` into `java.util.Random`.

This can be helpful when you have some JVM `Random` implementation, e.g. `SecureRandom`, and want to
pass it in a function taking `kotlin.random.Random`.  

### Collection shuffling

It becomes possible to provide extensions for collection shuffling with the specified source of randomness in the common standard library:

```kotlin
fun <T> MutableList<T>.shuffle(random: Random): Unit
fun <T> Iterable<T>.shuffled(random: Random): List<T>
```

The existing `shuffle()` and `shuffled()` can be reimplemented by delegating to `shuffle(Random)` and `shuffled(Random)` respectively.

## What has to be done

- [ ] Make the implementations serializable
- [x] Provide unsigned counterparts like `nextUInt`, `nextULong`, `nextUBytes` as extensions
- [x] Extension on `List`/`Array`/`CharSequence` to select a random element from the collection.

## Unresolved questions

#### Fixed implementation of seeded generator

What are the guarantees about the implementation of the seeded generator?
Should we fix its implementation and never change it in the future?

- An option here is to state that `Random(seed)` returns an unspecified repeatable RNG implementation
that can be changed later, for example in some 1.M Kotlin release, and one can obtain a fixed repeatable RNG with
an additional enum parameter:
    
        Random(seed, RandomImplementation.XORWOW)
        
**Resolution:** Avoid giving strict reproducibility guarantees for a while, document it in the `Random()` function docs.

#### `Random` identifier overloading

In the current naming scheme `Random` name is used to denote the abstract class, its companion, and two constructor-like functions. 
This makes it problematic to refer a correct overload of the name in the documentation. 

#### Do we need `nextBits` method?

Instead of `nextBits(n)` one can use `nextInt()` shifting the result right by `32 - n` bits.

#### Unclear names of `origin` and `bound` parameters

It was noted that it's unclear that these parameters specify an half-open range, 
where `origin` is inclusive start and `bound` is exclusive end.

**Resolution:** rename parameters to `from` and `until`. This way it is clear that the end is not included.

## Future advancements

* Extensions or members to generate sequences, like `ints()`, `ints(bound)`, `ints(range)` etc.
    
    We haven't found the use cases justifying introduction of these functions yet. 
    
    
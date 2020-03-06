# Successive results of accumulating functions

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Contributors**: Ilya Gorbunov
* **Status**: Implemented in Kotlin 1.3.70
* **Prototype**: Implemented
* **Related issues**: [KT-7657](https://youtrack.jetbrains.com/issue/KT-7657)
* **Discussion**: [KEEP-207](https://github.com/Kotlin/KEEP/issues/207)


## Summary

The goal is to introduce extension functions that `fold` or `reduce` collection and 
return all intermediate accumulator values. Indexed variants are also introduced.

## Motivation / Use cases

* https://discuss.kotlinlang.org/t/is-there-a-way-to-accumulate-and-map-in-the-same-operation/1492
* https://discuss.kotlinlang.org/t/reductions-cumulative-sum/8364
* https://discuss.kotlinlang.org/t/does-kotlin-have-something-similar-to-rxjava-scan/275

* Running total (cumulative sum)
    ```
    val runningTotal = numbers.scan(0) { acc, element -> acc + element }
    ```
* Running minimum
    ```
    val runningMinimum = numbers.reduce { acc, element -> minOf(acc, element) }
    ```
* Snapshots after every operation on data
    ```
    val initialText = "text in an editor"
    val textSnapshots = editOperations.scan(initialText) { text, edit -> edit(text) }
    ```

## Similar API review

* Stdlib currently implements `fold` and `reduce` extension function for `Iterable`, `Sequence`, `Grouping`, 
`CharSequence`, `Array`, `(Primitive)Array` and `(Unsigned)Array`.
    - `public inline fun <T, R> Iterable<T>.fold(initial: R, operation: (accumulator: R, element: T) -> R): R`
    - `public inline fun <S, T : S> Iterable<T>.reduce(operation: (accumulator: S, element: T) -> S): S`
* Coroutines Flow has `scan` ([docs](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/scan.html)) and `scanReduce` ([docs](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/scan-reduce.html)) methods
* RxJava contains method named `scan` ([docs](http://reactivex.io/documentation/operators/scan.html))

## Description

```kotlin
// function that accumulates collection starting from left to right using a given initial accumulator, and returns all successive accumulation values

public fun <T, R> Sequence<T>.scan(initial: R, operation: (acc: R, T) -> R): Sequence<R>

public inline fun <T, R> Iterable<T>.scan(initial: R, operation: (acc: R, T) -> R): List<R>

public inline fun <T, R> Array<out T>.scan(initial: R, operation: (acc: R, T) -> R): List<R>

public inline fun <R> CharSequence.scan(initial: R, operation: (acc: R, Char) -> R): List<R>

public inline fun <R> ByteArray.scan(initial: R, operation: (acc: R, Byte) -> R): List<R>
public inline fun <R> ShortArray.scan(initial: R, operation: (acc: R, Short) -> R): List<R>
public inline fun <R> IntArray.scan(initial: R, operation: (acc: R, Int) -> R): List<R>
public inline fun <R> LongArray.scan(initial: R, operation: (acc: R, Long) -> R): List<R>
public inline fun <R> FloatArray.scan(initial: R, operation: (acc: R, Float) -> R): List<R>
public inline fun <R> DoubleArray.scan(initial: R, operation: (acc: R, Double) -> R): List<R>
public inline fun <R> BooleanArray.scan(initial: R, operation: (acc: R, Boolean) -> R): List<R>
public inline fun <R> CharArray.scan(initial: R, operation: (acc: R, Char) -> R): List<R>

public inline fun <R> UIntArray.scan(initial: R, operation: (acc: R, UInt) -> R): List<R>
public inline fun <R> ULongArray.scan(initial: R, operation: (acc: R, ULong) -> R): List<R>
public inline fun <R> UByteArray.scan(initial: R, operation: (acc: R, UByte) -> R): List<R>
public inline fun <R> UShortArray.scan(initial: R, operation: (acc: R, UShort) -> R): List<R>

// indexed variants

public fun <T, R> Sequence<T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): Sequence<R>

public inline fun <T, R> Iterable<T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R>

public inline fun <T, R> Array<out T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R>

public inline fun <R> CharSequence.scanIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): List<R>

public inline fun <R> ByteArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Byte) -> R): List<R>
public inline fun <R> ShortArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Short) -> R): List<R>
public inline fun <R> IntArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Int) -> R): List<R>
public inline fun <R> LongArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Long) -> R): List<R>
public inline fun <R> FloatArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Float) -> R): List<R>
public inline fun <R> DoubleArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Double) -> R): List<R>
public inline fun <R> BooleanArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Boolean) -> R): List<R>
public inline fun <R> CharArray.scanIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): List<R>

public inline fun <R> UIntArray.scanIndexed(initial: R, operation: (index: Int, acc: R, UInt) -> R): List<R>
public inline fun <R> ULongArray.scanIndexed(initial: R, operation: (index: Int, acc: R, ULong) -> R): List<R>
public inline fun <R> UByteArray.scanIndexed(initial: R, operation: (index: Int, acc: R, UByte) -> R): List<R>
public inline fun <R> UShortArray.scanIndexed(initial: R, operation: (index: Int, acc: R, UShort) -> R): List<R>

// function that accumulates collection starting from left to right using the first element as the initial accumulator, and returns all successive accumulation values

public fun <S, T : S> Sequence<T>.scanReduce(operation: (acc: S, T) -> S): Sequence<S>

public inline fun <S, T : S> Iterable<T>.scanReduce(operation: (acc: S, T) -> S): List<S>

public inline fun <S, T : S> Array<out T>.scanReduce(operation: (acc: S, T) -> S): List<S>

public inline fun CharSequence.scanReduce(operation: (acc: Char, Char) -> Char): List<Char>

public inline fun ByteArray.scanReduce(operation: (acc: Byte, Byte) -> Byte): List<Byte>
public inline fun ShortArray.scanReduce(operation: (acc: Short, Short) -> Short): List<Short>
public inline fun IntArray.scanReduce(operation: (acc: Int, Int) -> Int): List<Int>
public inline fun LongArray.scanReduce(operation: (acc: Long, Long) -> Long): List<Long>
public inline fun FloatArray.scanReduce(operation: (acc: Float, Float) -> Float): List<Float>
public inline fun DoubleArray.scanReduce(operation: (acc: Double, Double) -> Double): List<Double>
public inline fun BooleanArray.scanReduce(operation: (acc: Boolean, Boolean) -> Boolean): List<Boolean>
public inline fun CharArray.scanReduce(operation: (acc: Char, Char) -> Char): List<Char>

// indexed variants

public fun <S, T : S> Sequence<T>.scanReduceIndexed(operation: (index: Int, acc: S, T) -> S): Sequence<S>

public inline fun <S, T : S> Iterable<T>.scanReduceIndexed(operation: (index: Int, acc: S, T) -> S): List<S>

public inline fun <S, T : S> Array<out T>.scanReduceIndexed(operation: (index: Int, acc: S, T) -> S): List<S>

public inline fun CharSequence.scanReduceIndexed(operation: (index: Int, acc: Char, Char) -> Char): List<Char>

public inline fun ByteArray.scanReduceIndexed(operation: (index: Int, acc: Byte, Byte) -> Byte): List<Byte>
public inline fun ShortArray.scanReduceIndexed(operation: (index: Int, acc: Short, Short) -> Short): List<Short>
public inline fun IntArray.scanReduceIndexed(operation: (index: Int, acc: Int, Int) -> Int): List<Int>
public inline fun LongArray.scanReduceIndexed(operation: (index: Int, acc: Long, Long) -> Long): List<Long>
public inline fun FloatArray.scanReduceIndexed(operation: (index: Int, acc: Float, Float) -> Float): List<Float>
public inline fun DoubleArray.scanReduceIndexed(operation: (index: Int, acc: Double, Double) -> Double): List<Double>
public inline fun BooleanArray.scanReduceIndexed(operation: (index: Int, acc: Boolean, Boolean) -> Boolean): List<Boolean>
public inline fun CharArray.scanReduceIndexed(operation: (index: Int, acc: Char, Char) -> Char): List<Char>

public inline fun UIntArray.scanReduceIndexed(operation: (index: Int, acc: UInt, UInt) -> UInt): List<UInt>
public inline fun ULongArray.scanReduceIndexed(operation: (index: Int, acc: ULong, ULong) -> ULong): List<ULong>
public inline fun UByteArray.scanReduceIndexed(operation: (index: Int, acc: UByte, UByte) -> UByte): List<UByte>
public inline fun UShortArray.scanReduceIndexed(operation: (index: Int, acc: UShort, UShort) -> UShort): List<UShort>
```

## Alternatives

* Use `fold`
    ```
    val runningTotal = mutableListOf(0)
    val total = numbers.fold(0) { acc, element ->
        val nextAcc = acc + element
        runningTotal.add(nextAcc)
        nextAcc
    }
    ```

* Use `for` loop
    ```
    var text = "text in an editor"
    val textSnapshots = mutableListOf(text)
    for ((index, edit) in editOperations.withIndex()) {
        text = edit(text)
        textSnapshots.add(text)

        Logger.logEdit(edit, index, text)
    }
    ```

## Dependencies

A subset of Kotlin Standard Library available on all supported platforms.

## Placement

* module: `kotlin-stdlib`
* packages: 
    - `kotlin.sequences` for `Sequence` extension functions
    - `kotlin.text` for `CharSequence` extension functions
    - `kotlin.collections` for other receivers
    -  all packages are imported by default

## Reference implementation

Open review: https://upsource.jetbrains.com/kotlin/review/KOTLIN-CR-3763

## Future advancements

* shorthand for cumulative sum (`scanSum`)
* accumulate from right to left (`scanRight`/`scanReduceRight`)
* append intermediate results to the given collection (`scanTo`/`scanReduceTo`)

## Naming

* Function that reduces collection and returns intermediate reductions is called `scan` in most frameworks, 
regardless of using initial accumulator. In Kotlin to be consistent with existing `fold` and `reduce` functions 
we need to name variants of `scan` with and without initial value differently.
* `scan...` is used as a prefix for the aggregate functions to indicate that variant returns all intermediate results. 
The `fold` variant will be called just `scan` due to its use cases being more widespread.

## Contracts

* `scan` returns a list containing the `initial` argument when receiver is empty
* `scanReduce` returns an empty list when receiver is empty
* `last()` value of the returned list/sequence is equivalent to calling non-scanning counterpart, `fold`/`reduce`

### Receiver types

The operations are provided for collections with `fold` and `reduce` operations, except for `Grouping`, 
i.e. `Iterable`, `Sequence`, `CharSequence`, `Array`, `(Primitive)Array` and `(Unsigned)Array`.

### Return type

 - the operation is lazy for sequence and thus returns a sequence
 - the operation is eager for iterables and returns a list
 - the return type can be changed by wrapping the receiver with `asSequence()`/`asIterable()` functions

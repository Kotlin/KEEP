# Successive results of accumulating functions

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Contributors**: Ilya Gorbunov
* **Status**: Implemented in Kotlin 1.4.0
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
    val runningTotal = numbers.runningFold(0) { acc, element -> acc + element }
    ```
* Running minimum
    ```
    val runningMinimum = numbers.runningReduce { acc, element -> minOf(acc, element) }
    ```
* Snapshots after every operation on data
    ```
    val initialText = "text in an editor"
    val textSnapshots = editOperations.runningFold(initialText) { text, edit -> edit(text) }
    ```

## Similar API review

* Stdlib currently implements `fold` and `reduce` extension function for `Iterable`, `Sequence`, `Grouping`, 
`CharSequence`, `Array`, `(Primitive)Array` and `(Unsigned)Array`.
    - `public inline fun <T, R> Iterable<T>.fold(initial: R, operation: (acc: R, element: T) -> R): R`
    - `public inline fun <S, T : S> Iterable<T>.reduce(operation: (acc: S, element: T) -> S): S`
* Coroutines Flow has `scan` ([docs](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/scan.html)) and `scanReduce` ([docs](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/scan-reduce.html)) methods
* RxJava contains method named `scan` ([docs](http://reactivex.io/documentation/operators/scan.html))

## Description

It is proposed to:
* Introduce `runningFold` and `runningReduce` functions for all the collections that have `fold` and `reduce` functions, except `Grouping`:
    - `public inline fun <T, R> Iterable<T>.runningFold(initial: R, operation: (acc: R, T) -> R): List<R>`
    - `public inline fun <S, T : S> Iterable<T>.runningReduce(operation: (acc: S, T) -> S): List<S>`

* Introduce indexed variants of `runningFold` and `runningReduce`:
    - `public inline fun <T, R> Iterable<T>.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R>`
    - `public inline fun <S, T : S> Iterable<T>.runningReduceIndexed(operation: (index: Int, acc: S, T) -> S): List<S>`

Most of the frameworks (e.g. RxJava) and programming languages (e.g. Scala) that provide a function 
to fold collection and return intermediate reductions have named it `scan`. 
To make the `runningFold` discoverable for users coming from that frameworks 
we will also introduce `scan` and `scanIndexed` functions, equivalent to `runningFold` and `runningFoldIndexed` respectively.
It will be helpful for users who got used to `scan` naming as well:
    - `public inline fun <T, R> Iterable<T>.scan(initial: R, operation: (acc: R, T) -> R): List<R>`
    - `public inline fun <T, R> Iterable<T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R>`

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

* shorthand for cumulative sum (`runningSum`)
* accumulate from right to left (`runningFoldRight`/`runningReduceRight`)
* append intermediate results to the given collection (`runningFoldTo`/`runningReduceTo`)

## Naming

* Function that reduces collection and returns intermediate reductions is called `scan` in most frameworks, 
regardless of using initial accumulator. In Kotlin to be consistent with existing `fold` and `reduce` functions 
we need to name variants of `scan` with and without initial value differently.

## Contracts

* `runningFold`/`scan` return a list containing the `initial` argument when receiver is empty
* `runningReduce` returns an empty list when receiver is empty
* `last()` value of the returned list/sequence is equivalent to calling non-scanning counterpart, `fold`/`reduce`

### Receiver types

The operations are provided for collections with `fold` and `reduce` operations, except for `Grouping`, 
i.e. `Iterable`, `Sequence`, `CharSequence`, `Array`, `(Primitive)Array` and `(Unsigned)Array`.

### Return type

 - the operation is lazy for sequence and thus returns a sequence
 - the operation is eager for iterables and returns a list
 - the return type can be changed by wrapping the receiver with `asSequence()`/`asIterable()` functions

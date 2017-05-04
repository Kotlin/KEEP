# Sliding window extensions for collections

* **Type**: Standard Library API proposal
* **Authors**: Sergey Mashkov, Ilya Gorbunov
* **Status**: Under consideration
* **Prototype**: Implemented
* **Related issues**: [KT-10021](https://youtrack.jetbrains.com/issue/KT-10021), [KT-9151](https://youtrack.jetbrains.com/issue/KT-9151), [KT-11026](https://youtrack.jetbrains.com/issue/KT-11026)
* **Discussion**: [KEEP-11](https://github.com/Kotlin/KEEP/issues/11)

## Summary

Introduce extension functions to support:
- partitioning collections into blocks of the given size,
- taking a window of the given size and moving it along the collection with the given step.

## Similar API review

 - Ruby: [each_slice](http://ruby-doc.org/core-2.2.3/Enumerable.html#method-i-each_slice) (partitioning)
 - Scala: [sliding](http://www.scala-lang.org/api/2.11.8/index.html#scala.collection.IterableLike@sliding%28size:Int,step:Int%29:Iterator[Repr]) (partitioning, windowing)
 - RxJava: [buffer](http://reactivex.io/documentation/operators/buffer.html) (partitioning)
 - F#: [windowed](https://msdn.microsoft.com/visualfsharpdocs/conceptual/seq.windowed['t]-function-[fsharp]) (windowing)
 - Clojure: [partition](https://clojuredocs.org/clojure.core/partition) (partitioning, windowing)
 - Klutter: [batch and lazyBatch](https://github.com/kohesive/klutter/blob/master/core/src/main/kotlin/uy/klutter/core/collections/CollectionsBatching.kt) (partitioning)
 - StreamEx: [StreamEx.ofSubLists](https://github.com/amaembo/streamex/blob/f5bd4c3ba79aa0de87ea834e87ac1040a67fa5d8/src/main/java/one/util/streamex/StreamEx.java#L2677) (partitioning, windowing) 
 - ProtonPack: [windowed](https://github.com/poetix/protonpack/blob/master/src/main/java/com/codepoetics/protonpack/StreamUtils.java#L210) (partitioning, windowing)

## Description

```kotlin

// the operation that partitions source into blocks of the given size

fun <T> Iterable<T>.chunked(size: Int): List<List<T>>
fun <T> Sequence<T>.chunked(size: Int): Sequence<List<T>>
fun CharSequence.chunked(size: Int): List<String>
fun CharSequence.chunkedSequence(size: Int): Sequence<String>

// the operation that partitions source into blocks of the given size 
// and applies the immediate transform on an each block

fun <T, R> Iterable<T>.chunked(size: Int, transform: (List<T>) -> R): List<R>
fun <T, R> Sequence<T>.chunked(size: Int, transform: (List<T>) -> R): Sequence<R>
fun <R> CharSequence.chunked(size: Int, transform: (CharSequence) -> R): List<R>
fun <R> CharSequence.chunkedSequence(size: Int, transform: (CharSequence) -> R): Sequence<R>

// the operation that takes a window of the given size and moves it along  with the given step

fun <T> Iterable<T>.windowed(size: Int, step: Int): List<List<T>>
fun <T> Sequence<T>.windowed(size: Int, step: Int): Sequence<List<T>>
fun CharSequence.windowed(size: Int, step: Int): List<String>
fun CharSequence.windowedSequence(size: Int, step: Int): Sequence<String>

// the operation that takes a window of the given size and moves it along  with the given step
// and applies the immediate transform on an each window

fun <T, R> Iterable<T>.windowed(size: Int, step: Int, transform: (List<T>) -> R): List<R>
fun <T, R> Sequence<T>.windowed(size: Int, step: Int, transform: (List<T>) -> R): Sequence<R>
fun <R> CharSequence.windowed(size: Int, step: Int, transform: (CharSequence) -> R): List<R>
fun <R> CharSequence.windowedSequence(size: Int, step: Int, transform: (CharSequence) -> R): Sequence<R>

// pairwise operation that is a special case of window of size 2 and step 1
fun <T> Iterable<T>.pairwise(): List<Pair<T, T>> 
fun <T> Sequence<T>.pairwise(): Sequence<Pair<T, T>>
fun CharSequence.pairwise(): List<Pair<Char, Char>>

// pairwise operation which applies the immediate transform on an each pair

fun <T, R> Iterable<T>.pairwise(transform: (a: T, b: T) -> R): List<R> 
fun <T, R> Sequence<T>.pairwise(transform: (a: T, b: T) -> R): Sequence<R>
fun <R> CharSequence.pairwise(transform: (a: Char, b: Char) -> R): List<R>
```

## Use cases

  - buffering/batching
 
    ```kotlin
    fun MessageService.sendAll(messages: List<Message>) {
        for (batch in messages.asSequence().chunked(this.batchSize)) {
            this.sendBatch(batch)
        }
        // or equivalent (though smelling like `.map { side_effect }` operation)
        messages.chunked(this.batchSize) { this.sendBatch(it) }
    }
    ```

 - computing moving average (this may require dropping partial windows)

    ```kotlin
    val averaged = values.windowed(size = 5, step = 1) { it.average() }
    ```
 
 - sequence sampling
 
    ```kotlin
    // downsample by factor 2
    val sampled = values.windowed(size = 1, step = 2) { it.single() }
    
    // take every tenth value and average it with two neighbours
    val decimated = values.windowed(size = 3, step = 10) { it.average() }
    ```
 
 - find some product of each two adjacent elements
 
    ```kotlin
    fun List<Double>.deltas(): List<Double> = pairwise { a, b -> b - a }
    ```

## Alternatives

 - use imperative loops
    * cons: the implementation is not so simple so it may cause a lot of very similar but slightly different implementations.

 - keep it in a separate library
    * cons: too small functionality for a separate library, nobody would like to add it just because of one function

## Dependencies

A subset of Kotlin Standard Library available on all supported platforms.

## Placement

 - module: `kotlin-stdlib`
 - package: `kotlin.collections`

## Reference implementation

### Receiver types

The operations are provided for `Iterables`, `Sequences` and `CharSequences` only.

If there's a need to invoke operation on an array, the array can be wrapped either to sequence or to list and then 
the operation is invoked on that wrapped sequence or list.  

### Return type

 - the operation is lazy for sequence and thus returns a sequence of lists/pairs
 - the operation is eager for iterables and returns a list or lists/pairs
 - the return type can be changed by wrapping the receiver with `asSequence()`/`asIterable()` functions
 - for `CharSequence` both eager/lazy variants are provided, similar to `split`/`splitToSequence`

### Applying immediate transform

The `transform` function parameter passed to `windowed` or `chunked` is applied to each window or chunk immediately.
The `List`/`CharSequence` passed to `transform` is ephemeral, i.e. it's only valid inside that function and
should not escape from it. This allows not to materialize the window as a snapshot and provide a view instead under some
circumstances.

On the other hand the `List/Sequence` of `List<T>/String` returned from the overloads without `transform` are fully 
materialized, i.e. each `List/String` is a copy of some subsequence of the original sequence.

### Implementation

See reference implementation in branch [rr/stdlib/window](https://github.com/JetBrains/kotlin/compare/rr/stdlib/window)

## Unresolved questions

 - should we provide `windowedBackward` for indexed collections? What are the use cases?
    - **resolution**: do not provide until use cases are clear in demand
 - should we provide `windowIndices` and `windowBackwardIndices` as well?
    - **resolution**: do not provide until use cases are clear in demand
 - Whether or not to allow window size of 0
    * neither of analogs allows
    * (con) strange corner case — makes not much of sense to obtain a series of zero-sized windows.
    * (pro) valid operation and it's possible to implement.
    * (pro) reduce possible crash cases as crash generally is more dangerous
    - **resolution**: do not allow
 - what to do with the last window having less than the specified `size` elements:
    - keep partial window(s) — required for cases like batch processing
    - drop partial window(s) — for cases like moving average
        * Trailing batches could be filtered out with `filterNot { it.size < windowSize }` operation.
    - pad partial windows to required size 
        * Could be achieved with `map { it.padEnd(size, paddingElement) }`, but it requires
        introducing `padStart`/`padEnd` for collections.
    - fail when last window(s) is partial
    - **resolution**: 
        - default to keeping partial windows, other modes could be achieved on top of that.
        - evaluate usage feedback from eap releases.
 - do we need `slidingWindow2` and `slidingWindow3` as described in [KT-10021](https://youtrack.jetbrains.com/issue/KT-10021) with the following signatures:
     * `slidingWindow2([step], operation: (T, T) -> Unit)`
     * `slidingWindow3([step], operation: (T, T, T) -> Unit)`
     -  **resolution**: introduce `slidingWindow2` in form of `pairwise((T, T) -> R)` and `pairwise(): Sequence<Pair<T, T>>`
 - should operations taking a function be inlined?
    - for `windowed` and `chunked` this results in a big amount of bytecode inlined and prevents runtime specializations based on the type of the receiver
    - for `pairwise` this seems feasible


## Future advancements

 - function that provides floating window size and step so it could look like `windowSliding(initialSeekPredicate, takeWhilePredicate, dropPredicate)` but it is definitely too generic and would not be so frequently used.



# Sliding window extensions for collections

* **Type**: Standard Library API proposal
* **Author**: Sergey Mashkov
* **Status**: Submitted
* **Prototype**: Implemented
* **Target tickets**: [KT-10021](https://youtrack.jetbrains.com/issue/KT-10021), [KT-9151](https://youtrack.jetbrains.com/issue/KT-9151), [KT-11026](https://youtrack.jetbrains.com/issue/KT-11026)
* **Discussion**: [KEEP-11](https://github.com/Kotlin/KEEP/issues/11)

## Summary

Support for `slidingWindow` and `slidingWindowBackward` extension functions for arrays, strings, collections and sequences so they provide ability to peek elements grouped by window size and moves the window by the specified step (shift amount).

## Similar API review

 - Ruby: [each_slice](http://ruby-doc.org/core-2.2.3/Enumerable.html#method-i-each_slice)
 - Scala: [sliding](http://www.scala-lang.org/api/2.11.8/index.html#scala.collection.IterableLike@sliding%28size:Int,step:Int%29:Iterator[Repr])
 - RxJava: [buffer](http://reactivex.io/documentation/operators/buffer.html)
 - F#: [windowed](https://msdn.microsoft.com/visualfsharpdocs/conceptual/seq.windowed['t]-function-[fsharp])
 - Clojure: [partition](https://clojuredocs.org/clojure.core/partition)
 - Klutter: [batch and lazyBatch](https://github.com/kohesive/klutter/blob/master/core-jdk6/src/main/kotlin/uy/klutter/core/common/CollectionsBatching.kt)
 - StreamEx: [StreamEx.ofSubLists](https://github.com/amaembo/streamex/blob/f5bd4c3ba79aa0de87ea834e87ac1040a67fa5d8/src/main/java/one/util/streamex/StreamEx.java#L2677)
 - ProtonPack: [windowed](https://github.com/poetix/protonpack/blob/master/src/main/java/com/codepoetics/protonpack/StreamUtils.java#L210)
 
## Use cases

 - buffering/batching
 
    ```kotlin
    class EventProcessor<E : Event>(val batchSize: Int, val eventBus: Sequence<E>) {
        fun doBatchProcessing(operation: (List<E>) -> Unit) {
            for (batch in eventBus.slidingWindow(batchSize)) {
                operation(batch)
            }    
        }
    }
    ```

 - computing moving average (this may require dropping partial windows)

    ```kotlin
    val averaged = values.slidingWindow(size = 5, step = 1).map { it.average() }
    ```
 
 - sequence sampling
 
    ```kotlin
    // downsample by factor 2
    val sampled = values.slidingWindow(size = 1, step = 2).map { it.single() }
    
    // take every tenth value and average it with two neighbours
    val decimated = values.slidingWindow(size = 3, step = 10).map { it.average() }
    ```
 
 
 - permutations generation (unless there is more specialized `pairwise` operation)
 
    ```kotlin
    fun IntArray.pairs() = sorted().slidingWindow(2, step = 1)
    ```

## Alternatives

 - use imperative loops
    * cons: the implementation is not so simple so it may cause a lot of very similar but slightly different implementations.

 - keep it in a separate library
    * cons: too small functionality for a separate library, nobody would like to add it just because of one function

## Dependencies

 - kotlin-runtime
 - kotlin-stdlib
 - JDK 6

## Placement

 - module: `kotlin-stdlib`
 - package: `kotlin.collections`

## Reference implementation

### Proposed function signatures

| receiver | slidingWindow | slidingWindowBackward | return type |
| --- | --- | --- | --- |
| `Sequence<T>` | :white_check_mark: | | `Sequence<List<T>>` |
| `Iterable<T>` | :white_check_mark: | | `Sequence<List<T>>` |
| `List<T>` | :white_check_mark: | :white_check_mark: | `Sequence<List<T>>` |
| `Array<T>` | :white_check_mark: | :white_check_mark: | `Sequence<Array<T>>` |
| `PrimitiveArray` | :white_check_mark: | :white_check_mark: | `Sequence<PrimitiveArray>` |
| `String` | :white_check_mark: | :white_check_mark: | `Sequence<String>` |
| `CharSequence` | :white_check_mark: | :white_check_mark: | `Sequence<CharSequence>` |

 \* where `PrimitiveArray` is one of the following: `ByteArray`, `ShortArray`, `CharArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`

### Return type rationale

 - laziness
 - small footprint, less OOM risk, better for big receivers and/or small steps
 - same type for all receivers
 
###  Sequence type argument rationale

 - for `Sequence<T>` receiver there is no reason to keep `Sequence<Sequence<T>>` as we actually can provide `List` that have more capabilities 
 - for `Iterable<T>` the reason is the same
 - for `PrimitiveArray` it is better to keep return `Sequence<PrimitiveArray>` because of less boxing overhead
 - for `Array<T>` we can provide `Array` or `List` but I propose it to return `Array` for better consistency with `PrimitiveArray`
 - for `CharSequence` we return sequence of `CharSequence` to eliminate possible copying for `CharBuffer` and other possible implementations (that could be good or bad)

### Implementation

See reference implementation in branch [rr/cy/window-sliding](https://github.com/JetBrains/kotlin/compare/rr/cy/window-sliding)

## Unresolved questions

 - should we provide `slidingWindowBackward` for indexed collections? What are the use cases?
 - should we provide `slidingWindowIndices` and `slidingWindowBackwardIndices` as well?
 - Whether or not to allow window size of 0
    * neither of analogs allows
    * (con) strange corner case — makes not much of sense to obtain a series of zero-sized windows.
    * (pro) valid operation and it's possible to implement.
    * (pro) reduce possible crash cases as crash generally is more dangerous
 - what to do with the last window having less than the specified `size` elements:
    - keep partial window(s) — required for cases like batch processing
    - drop partial window(s) — for cases like moving average
        * Trailing batches could be filtered out with `filterNot { it.size < windowSize }` operation.
    - pad partial windows to required size 
        * Could be achieved with `map { it.padEnd(size, paddingElement) }`, but it requires
        introducing `padStart`/`padEnd` for collections.
 - do we need `slidingWindow2` and `slidingWindow3` as described in [KT-10021](https://youtrack.jetbrains.com/issue/KT-10021) with the following signatures:
     * `slidingWindow2([step], operation: (T, T) -> Unit)`
     * `slidingWindow3([step], operation: (T, T, T) -> Unit)`

## Future advancements

 - function that provides floating window size and step so it could look like `windowSliding(initialSeekPredicate, takeWhilePredicate, dropPredicate)` but it is definitely too generic and would not be so frequently used.



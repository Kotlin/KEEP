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

## Use cases

 - pagination
 ```kotlin
 fun view(page: Int) = source.slidingWindow(pageSize).drop(page).firstOrNull()
 ```
 
 - buffering/batching
 
 ```kotlin
 class EventProcessor<E : Event>(val batchSize: Int, val eventBus: Sequence<E>) {
     fun doBatchProcessing(operation: (List<E>) -> Unit) {
         eventBus.slidingWindow(batchSize).forEach(operation)
     }
 }
 ```
 
 - permutations generation
 
 ```kotlin
 fun IntArray.pairs() = sorted().slidingWindow(2, step = 1)
 ```
 
 - advanced signal processing
 
 ```kotlin
 class ChangeListener<E>(val source: Sequence<E>) {
     fun listen(handler: (E, E, E) -> Unit) {
         source.windowSliding(3, step = 1).forEach { p ->
             handler(p[0], p[1], p[2])
         }
     }
 }
 ```
 
 - small auxiliaries such as hex to byte array
 ```kotlin
 fun bytesFromHex(hex: String): ByteArray {
      val result = ByteArray(hex.length / 2)

      for ((idx, v) in hex.windowSliding(2).withIndex()) {
          result[idx] = Integer.parseInt(v, 16).toByte()
      }

      return result
 }
 ```

## Alternatives

 - copy-paste it everywhere
    * cons: obviously you need to copy and paste it often while other languages already have it. Also the implementation is not so simple so it may cause a lot of very similar but slightly different implementations.

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
 
###  Sequence type rationale

 - for `Sequence<T>` receiver there is no reason to keep `Sequence<Sequence<T>>` as we actually can provide `List` that have more capabilities 
 - for `Iterable<T>` the reason is the same
 - for `PrimitiveArray` it is better to keep return `Sequence<PrimitiveArray>` because of less boxing overhead
 - - for `Array<T>` we can provide `Array` or `List` but I propose it to return `Array` for beter consistency with `PrimitiveArray`
 - for `CharSequence` we return sequence of `CharSequence` to eliminate possible copying for `CharBuffer` and other possible implementations (that could be good or bad)

### Implementation

See reference implementation in branch [rr/cy/window-sliding](https://github.com/JetBrains/kotlin/compare/rr/cy/window-sliding)

## Unresolved questions

 - should we provide `slidingWindowIndices` and `slidingWindowBackwardIndices` as well?
 - Whether or not to allow window size of 0
    * neither of analogs allows
    * (con) strange corner case
    * (pro) valid operation and it's possible to implement.
    * (pro) reduce possible crash cases as crash generally is more dangerous
 - do we need `slidingWindow2` and `slidingWindow3` as described in [KT-10021](https://youtrack.jetbrains.com/issue/KT-10021) with the following signatures:
     * `slidingWindow2([step], operation: (T, T) -> Unit)`
     * `slidingWindow3([step], operation: (T, T, T) -> Unit)`

## Future advancements

 - function that provides floating window size and step so it could look like `windowSliding(initialSeekPredicate, takeWhilePredicate, dropPredicate)` but it is definitely too generic and would not be so frequently used.



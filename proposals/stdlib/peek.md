# Add function for peeking into Sequences and Iterables

* **Type**: Standard Library API proposal
* **Author**: Christian Brüggemann
* **Status**: Submitted
* **Prototype**: Not started
* **Discussion**: [KEEP-47](https://github.com/Kotlin/KEEP/issues/47)


## Summary

The goal is to introduce a function called `peek`, which allows the user to perform an action on each element of a `Sequence` or an `Iterable` without breaking the functional chain like `forEach` does.

## Description

When working with `Sequences` or `Iterables`, it can be useful to take a look at what items are passed through the stream. This is especially useful for debugging. Effectively, `peek` would work like this:

```
inline fun <T> Sequence<T>.peek(f: (T) -> Unit): Sequence<T> = map { f(it); it }
inline fun <T> Iterable<T>.peek(f: (T) -> Unit): Iterable<T> = apply { forEach(f) }
```

However, `peek` could also be implemented without relying on `map` by implementing a custom `Sequence`/`Iterator` which allows for peeking.

### Example usage

```
val sum = listOf(1, 2, 3).asSequence().peek { println("Adding $it") }.sum()
```

### Naming

As described in the related [YouTrack issue](https://youtrack.jetbrains.com/issue/KT-8220#tab=Comments), the name `peek` could be confused with `Queue.peek`. Thus, the following alternatives could be considered to mitigate this:

* onEach
* peekEach
* doOnEach

## Similar API review

* As outlined above, `map` can be used for implementing `peek`.
* Java `Streams` have `peek` ([docs](https://docs.oracle.com/javase/8/docs/api/java/util/stream/Stream.html#peek-java.util.function.Consumer-)) as well.
* RxJava contains a method called `doOnNext` ([docs](http://reactivex.io/documentation/operators/do.html))

## Use cases
For debugging (the strongest use case):
```
inputDir.walk()
    .filter { it.isFile && it.name.endsWith(".txt") }
    .peek { println("Moving $it to $outputDir") }
    .forEach { moveFile(it, File(outputDir, it.toRelativeString(inputDir))) }
```
For various intermediate operations, real-world example from a distributed hashtable:
```
internalMap.entrySet().stream()
		.filter(entryBelongsToNeighbor(newNeighbor))
		.peek(entry -> newNeighbor.send(new AckPutMessage<>(this, entry.getKey(), entry.getValue())))
		.map(Map.Entry::getKey)
		.forEach(key -> System.out.println("Moving key " + toProperKey(key) + " to neighbor " + newNeighbor.id()));
```

* http://www.leveluplunch.com/java/examples/stream-intermediate-operations-example/
* The lambda passed to `peek` is a good place to set breakpoints
* Perhaps more due to equivalent methods in Java 8 and RxJava, though it is hard to find examples where `peek` was not used for debugging (for instance `println` or using a logging method). Here are some from RxJava (`doOnNext`):
  * https://searchcode.com/file/116104738/main/src/cgeo/geocaching/sensors/Sensors.java#l-74
  * https://searchcode.com/file/115079675/src/test/java/rx/internal/operators/OnSubscribeRefCountTest.java#l-59
  * https://searchcode.com/file/115119425/hystrix-core/src/test/java/com/netflix/hystrix/HystrixObservableCommandTest.java#l-3661
  * https://searchcode.com/file/116021346/couchbase2/src/main/java/com/yahoo/ycsb/db/couchbase2/Couchbase2Client.java#l-621
  * https://searchcode.com/file/116104915/main/src/cgeo/geocaching/utils/RxUtils.java#l-36

## Alternatives

If `peek` is defined once as a function somewhere, verbosity is not increased apart from the function definition. That does not imply though that `peek` should not be a part of the standard library, since standardizing commonly used patterns helps avoiding fragmentation.

## Dependencies

Only the stdlib.

## Placement

* Standard Library
* package: kotlin.sequences in file [Sequences.kt](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/collections/Sequences.kt)

## Reference implementation

If no custom `Sequence`/`Iterator` is required, the function definition in the description can be considered the reference implementation. Since it is very simple, additional tests should not be necessary.

## Unresolved questions

* Which name should be picked?
* Should `peek` delegate to `map`/`forEach` or be implemented using a custom `Sequence`/`Iterator`?

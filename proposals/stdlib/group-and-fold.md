# Group by key and fold each group simultaneously

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Submitted
* **Prototype**: Implemented
* **Discussion**: [KEEP-23](https://github.com/Kotlin/KEEP/issues/23)

## Summary

Introduce a function similar to `groupBy`, but folding values of each group on the fly.

## Similar API review

* Ceylon: [`Iterable.summarize`](http://modules.ceylon-lang.org/repo/1/ceylon/language/1.2.0/module-doc/api/Iterable.type.html#summarize).

## Description

The operation with the following signature is proposed:

```kotlin
public inline fun <T, K> Iterable<T>.groupingBy(
    crossinline keySelector: (T) -> K
): Grouping<T, K>
```

where `Grouping<T, K>` is an interface defined as following:

```
interface Grouping<T, out K> {
    fun iterator(): Iterator<T>
    fun keySelector(element: T): K
}
```

It represents a wrapper around a source of iterator with the `keySelector`
function attached to it.

After that it becomes possible to provide various useful extensions for `Grouping<T, K>`.

### Generic aggregation (fold or reduce)

The most generic form of aggreration, that other overloads delegate their implementation to.

```
public inline fun <T, K, R> Grouping<T, K>.aggregate(
    operation: (key: K, value: R?, element: T, first: Boolean) -> R
): Map<K, R>
```

### Key-parametrized fold

Here the initial value and the operation depend on the key of a group.

```
public inline fun <T, K, R> Grouping<T, K>.fold(
    initialValueSelector: (K, T) -> R,
    operation: (K, R, T) -> R
): Map<K, R>
```

### Simplified fold

The `initialValue` is a constant and the operation do not depend on the group key.

```
public inline fun <T, K, R> Grouping<T, K>.fold(
    initialValue: R,
    operation: (R, T) -> R
): Map<K, R>
```

### Reduce

```
public inline fun <S, T : S, K> Grouping<T, K>.reduce(
    operation: (K, S, T) -> S
): Map<K, S>
```

### Count

No inlining is required.
```
public fun <T, K> Grouping<T, K>.count(): Map<K, Int>
```

### SumBy

```
public inline fun <T, K> Grouping<T, K>.sumBy(
    valueSelector: (T) -> Int
): Map<K, Int> =
```

## Use cases

The most common use case is doing some aggregation, broken down by some key:

 1. given a text, count frequencies of words/characters;

    ```kotlin
    val frequencies = words.groupingBy { it }.count()
    ```

 2. given orders in all stores, sum total value of orders by store;
    ```kotlin
    val storeTotals =
            orders.groupingBy { it.store }
                  .sumBy { order -> order.total }
    ```

 3. given orders of all clients, find an order with the maximum total for each client.
    ```kotlin
    val bestClientOrders =
            orders.groupingBy { it.client }
                  .reduce { k, maxValueOrder, order -> maxOfBy(order, maxValueOrder) { it.total } }
    ```

## Alternatives

* Just use `groupBy` and then `mapValues` on a resulting map.
    * Pro: many operations are supported on a group of values, not just `fold`.
    * Con: intermediate map of lists is created.

    Example:

    ```
    val frequencies: Map<String, Int> =
            words.groupBy { it }.mapValues { it.value.size }
    ```

* Use Rx.observables.
    * Pro: many operations supported
    * Con: observable transformations overhead,
    asymptotically less than that of `mapValues`.

    ```
    val frequencies: Map<String, Int> =
            Observable.from(values)
                    .groupBy { it }
                    .flatMap { g -> g.count().map { g.key to it } }
                    .toBlocking()
                    .toIterable()
                    .toMap()
    ```

* [Previous version of this proposal](https://github.com/Kotlin/KEEP/blob/f1cdce73b5c1983d9380d632d2fcdd73c6253c23/proposals/stdlib/group-and-fold.md)
intended to introduce fully inlined operations, such as `groupFoldBy(keySelector, initialValue, operation)`.
    * Pro: do not require a wrapper `Grouping` object to be allocated.
    * Pro: may require less intermediate boxing in case of receivers with primitive elements, such as primitive arrays and char sequences.
    * Con: several labmdas in the parameter list makes an invocation akward.
    * Con: duplicating all the operations for each receiver type implies high method count.

    A benchmark was conducted to study the performance impact of not inlining `keySelector` function.
    That impact was shown to be negligible for receivers with object elements,
    and on the other side noticeable for receivers with primitive elements.
    However, the latter is hardly to be the use case covered by this operation.

## Dependencies

Only a subset of Kotlin Standard Library available on all supported platforms is required.

## Placement

 - module: `kotlin-stdlib`
 - packages: `kotlin.collections`, `kotlin.sequences`, `kotlin.text`

## Reference implementation

See the reference implementation in the repository [kotlinx.collections.experimental](https://github.com/ilya-g/kotlinx.collections.experimental/tree/master/kotlinx-collections-experimental/src/main/kotlin/kotlinx.collections.experimental/grouping)

### Receivers

It is possible to provide `groupingBy` operation for each collection-like receiver, such as
`Iterable`, `Sequence`, `Array`, `CharSequence`, `(Primitive)Array`,
however for primitive arrays this operation does not make much sense.

### Return type

The operation returns `Map<K, R>`, where `K` is the type of the key and `R` is the type of the accumulator.

## Unresolved questions

1. Naming options:
    * `groupingBy` or just `grouping`
    * `count`/`sumBy` can be misinterpreted as operations on the whole collection, rather on each group.

2. Which general forms of `fold`/`reduce`/`aggregate` should we provide?
    * Method count is increased
    * Having them as overloads hurts completion during gradual typing.
3. Should we provide `To`-overloads (like `groupByTo`) with a mutable map as a target parameter?
4. Having primitive fold accumulators stored in a map introduces a lot of boxing.

## Future advancements

* If we do not provide some forms, evaluate whether they could be introduced later.
* Converting collection operations to folds can be error-prone, maybe we should provide
  some standard reducer functions, such as Count, Sum etc.

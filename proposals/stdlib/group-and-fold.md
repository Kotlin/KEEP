# An operation to group by key and fold each group simultaneously

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Submitted
* **Prototype**: In progress


## Summary

Introduce a function similar to `groupBy`, but folding values of each group on the fly.

## Similar API review

* Ceylon: [`Iterable.summarize`](http://modules.ceylon-lang.org/repo/1/ceylon/language/1.2.0/module-doc/api/Iterable.type.html#summarize).

## Description

The operation with the following signature is proposed:

```kotlin
public inline fun <T, K, R> Iterable<T>.groupFoldBy(
    keySelector: (T) -> K,
    initialValue: R,
    operation: (R, T) -> R
): Map<K, R>
```

Also it is possible to provide two more generic forms:

```kotlin
public inline fun <T, K, R> Iterable<T>.groupFoldBy(
    keySelector: (T) -> K,
    initialValueSelector: (K, T) -> R,
    operation: (K, R, T) -> R
): Map<K, R>
```
> Here initial value and operation depend on the key of a group.

and the most general:

```kotlin
public inline fun <T, K, R> Iterable<T>.groupFoldBy(
    keySelector: (T) -> K,
    operation: (key: K, value: R?, element: T, first: Boolean) -> R
): Map<K, R>
```

> This form may be used to implement `groupReduceBy`.


## Use cases

Most common use case is doing some aggregation, broken down by some key:

 1. given a text, count frequencies of words/characters;

    ```kotlin
    val frequencies = words.groupFoldBy({ it }, 0, { acc, w -> acc + 1 })
    ```

 2. given orders in all stores, sum total value of orders by store;
    ```kotlin
    val storeTotals =
            orders.groupFoldBy({ it.store }, 0, { acc, order -> acc + order.total })
    ```

 3. given orders of all clients, find an order with the maximum total for each client.
    ```kotlin
    val bestClientOrders =
            orders.groupReduceBy(
                { it.client },
                { maxValueOrder, order -> maxOfBy(order, maxValueOrder) { it.total } })
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

## Dependencies

Only a subset of Kotlin Standard Library available on all supported platforms is required.

## Placement

 - module: `kotlin-stdlib`
 - packages: `kotlin.collections`, `kotlin.sequences`, `kotlin.text`

## Reference implementation


### Receivers

It is possible to provide operation for each collection-like receiver.

### Return type

The operation returns `Map<K, R>`, where `K` is the type of key and `R` is the type of accumulator.

## Unresolved questions

1. Naming options:
    * `groupFoldBy`/`groupReduceBy`/`groupCountBy`
    * `groupByAndFold`/`groupByAndReduce`/`groupByAndCount`
    * `foldBy`/`reduceBy`/`countBy`
    * `summarizeBy` as in Ceylon
    * `aggregateBy` (aggregate is fold in .NET)

2. Which general forms of `groupFoldBy` should we provide?
    * Method cound is increased
    * Having them as overloads hurts completion during gradual typing.
3. Should we provide `groupReduceBy` or it would be enough if it's expressible with general form of `groupFold`?
4. Should we provide some specific forms, such as `groupCountBy`?
5. Should we provide `To`-overloads (like `groupByTo`) with target mutable map as a parameter?

## Future advancements

* If we do not provide some forms, evaluate whether they could be introduced later.

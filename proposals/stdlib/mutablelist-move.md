# Adding moveXXX() Functions to MutableList

It would be helpful to create some additional extension functions for `MutableList` to move items to new indexes, as well as move them up/down, and move one or all items that meet a predicate.


# Title

* **Type**: Standard Library API proposal
* **Author**: Thomas Nield
* **Contributors**: N/A
* **Status**: Submitted
* **Prototype**: Implemented


## Summary

It would be helpful to create some additional extension functions for `MutableList` to move items to new indexes, as well as move them up/down, and one or all items that meet a predicate.

These can be implemented using only extension functions on `MutableList`.

## Similar API review

Cannot think of an explicit precendent in another API.

## Use cases

* Common use cases often have to do with re-ordering items especially in a UI context, and any scenario dealing with mutable ordered collections may be candidate.

* In JavaFX and other UI technologieis, a user may want to move an item in a `MutableList` up or down to define some arbitrary order.

* Items can be searched in a `MutableList` by having the matching items moved to the top of the `MutableList`, rather than creating a new `List` or some filtering abstraction

* Observe this [demo application](https://github.com/thomasnield/rxkotlinfx-tornadofx-demo) to see several of these use cases in action.

## Alternatives

Developers could implement the extension functions themselves, as shown below. Moving items can be tricky and verbose to coordinate with local implementations.

```kotlin

import java.util.*

/**
 * Moves the given **T** item to the specified index
 */
fun <T> MutableList<T>.move(item: T, newIndex: Int)  {
    val currentIndex = indexOf(item)
    if (currentIndex < 0) return
    removeAt(currentIndex)
    add(newIndex, item)
}

/**
 * Moves the given item at the `oldIndex` to the `newIndex`
 */
fun <T> MutableList<T>.moveAt(oldIndex: Int, newIndex: Int)  {
    val item = this[oldIndex]
    removeAt(oldIndex)
    if (oldIndex > newIndex)
        add(newIndex, item)
    else
        add(newIndex - 1, item)
}

/**
 * Moves all items meeting a predicate to the given index
 */
fun <T> MutableList<T>.moveAll(newIndex: Int, predicate: (T) -> Boolean) {
    check(newIndex >= 0 && newIndex < size)
    val split = partition(predicate)
    clear()
    addAll(split.second)
    addAll(if (newIndex >= size) size else newIndex,split.first)
}

/**
 * Moves the given element at specified index up the **MutableList** by one increment
 * unless it is at the top already which will result in no movement
 */
fun <T> MutableList<T>.moveUpAt(index: Int) {
    if (index == 0) return
    if (index < 0 || index >= size) throw Exception("Invalid index $index for MutableList of size $size")
    val newIndex = index + 1
    val item = this[index]
    removeAt(index)
    add(newIndex, item)
}

/**
 * Moves the given element **T** up the **MutableList** by one increment
 * unless it is at the bottom already which will result in no movement
 */
fun <T> MutableList<T>.moveDownAt(index: Int) {
    if (index == size - 1) return
    if (index < 0 || index >= size) throw Exception("Invalid index $index for MutableList of size $size")
    val newIndex = index - 1
    val item = this[index]
    removeAt(index)
    add(newIndex, item)
}

/**
 * Moves the given element **T** up the **MutableList** by an index increment
 * unless it is at the top already which will result in no movement.
 * Returns a `Boolean` indicating if move was successful
 */
fun <T> MutableList<T>.moveUp(item: T): Boolean {
    val currentIndex = indexOf(item)
    if (currentIndex == -1) return false
    val newIndex = (currentIndex - 1)
    if (currentIndex <=0) return false
    remove(item)
    add(newIndex, item)
    return true
}

/**
 * Moves the given element **T** up the **MutableList** by an index increment
 * unless it is at the bottom already which will result in no movement.
 * Returns a `Boolean` indicating if move was successful
 */
fun <T> MutableList<T>.moveDown(item: T): Boolean {
    val currentIndex = indexOf(item)
    if (currentIndex == -1) return false
    val newIndex = (currentIndex + 1)
    if (newIndex >= size)  return false
    remove(item)
    add(newIndex, item)
    return true
}


/**
 * Moves first element **T** up an index that satisfies the given **predicate**, unless its already at the top
 */
inline fun <T> MutableList<T>.moveUp(crossinline predicate: (T) -> Boolean)  = find(predicate)?.let { moveUp(it) }

/**
 * Moves first element **T** down an index that satisfies the given **predicate**, unless its already at the bottom
 */
inline fun <T> MutableList<T>.moveDown(crossinline predicate: (T) -> Boolean)  = find(predicate)?.let { moveDown(it) }

/**
 * Moves all **T** elements up an index that satisfy the given **predicate**, unless they are already at the top
 */
inline fun <T> MutableList<T>.moveUpAll(crossinline predicate: (T) -> Boolean)  = asSequence().withIndex()
        .filter { predicate.invoke(it.value) }
        .forEach { moveUpAt(it.index) }

/**
 * Moves all **T** elements down an index that satisfy the given **predicate**, unless they are already at the bottom
 */
inline fun <T> MutableList<T>.moveDownAll(crossinline predicate: (T) -> Boolean)  = asSequence().withIndex()
        .filter { predicate.invoke(it.value) }
        .forEach { moveDownAt(it.index) }

/**
 * Swaps the position of two items at two respective indices
 */
fun <T> MutableList<T>.swap(indexOne: Int, indexTwo: Int) {
    Collections.swap(this, indexOne,indexTwo)
}

/**
 * Swaps the index position of two items
 */
fun <T> MutableList<T>.swap(itemOne: T, itemTwo: T) = swap(indexOf(itemOne),indexOf(itemTwo))

```

## Dependencies

* MutableList

## Placement

The proposal is to put this in Standard Library, and make it immediately available wherever `MutableList` is used.

## Reference implementation

A proposed implementation can be [found in this repository](https://github.com/thomasnield/kotlin-collection-extras/blob/master/src/main/kotlin/MutableListMoveExt.kt).


## Unresolved questions

* Is there a way to move items without removing and adding them? (Unlikely to my knowledge).
* Should some of these functions return a `Boolean` value to indicate some form of successful operation?

## Future advancements

No future advancements come to mind.

-------

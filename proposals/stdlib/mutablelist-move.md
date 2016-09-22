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
fun <T> MutableList<T>.move(oldIndex: Int, newIndex: Int)  {
    val item = this[oldIndex]
    removeAt(oldIndex)
    add(newIndex, item)
}

/**
 * Moves all items meeting a predicate to the given index
 */
fun <T> MutableList<T>.moveAll(newIndex: Int, predicate: (T) -> Boolean) {

    var newIndexIncrement = newIndex

    asSequence().toList().asSequence().withIndex()
            .filter { v -> predicate.invoke(v.value) }
            .forEach { move(it.index, newIndexIncrement++) }
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
}Provide options to solve them.

/**
 * Moves the given element **T** up the **MutableList** by an index increment
 * unless it is at the top already which will result in no movement
 */
fun <T> MutableList<T>.moveUp(item: T) {
    val currentIndex = indexOf(item)
    val newIndex = (currentIndex - 1)
    if (currentIndex <=0) return
    remove(item)
    add(newIndex, item)
}

/**
 * Moves the given element **T** up the **MutableList** by an index increment
 * unless it is at the bottom already which will result in no movement
 */
fun <T> MutableList<T>.moveDown(item: T) {
    val currentIndex = indexOf(item)
    val newIndex = (currentIndex + 1)
    if (newIndex >= size)  return
    remove(item)
    add(newIndex, item)
}

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

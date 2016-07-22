# Generic values() and valueOf() for enums

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Contributors**: Stanislav Erokhin, Alexander Udalov
* **Status**: Under consideration
* **Prototype**: Not started

## Feedback

Discussion of this proposal is held in [this issue](TODO).

## Synopsis

Generic operations on enums naturally involve retrieving all enum values or converting a string into a value. Example:

``` kotlin
// Print options on the screen
fun <reified T : Enum<T>> renderOptions(render: (T) -> String) {
    val values = /* get T.values() somehow */
    for (v in values) {
        println(" * " + render(v))
    }
}
```

Currently, there's no performant way of retrieving `values()`/`valueOf()` from an enum that is given as a generic parameter. This can be done through reflection, but it would be too slow for many use cases.

> The initial YouTrack issue: 
>- *[KT-10569](https://youtrack.jetbrains.com/issue/KT-10569) Cannot iterate over values of an enum class when it is used as a generic parameter*   
   
We propose to support `values()`/`valueOf()` for reified type parameters that extend `Enum<T>` through a compiler intrinsic that would generate appropriate static calls to the particular enum class.
   
## References
   
- [KT-10569](https://youtrack.jetbrains.com/issue/KT-10569) Cannot iterate over values of an enum class when it is used as a generic parameter   
- [KT-7358](https://youtrack.jetbrains.com/issue/KT-7358) Implicit enum companion object
- [KT-5191](https://youtrack.jetbrains.com/issue/KT-5191) Enum.valueOf couldn't be called   

## Syntax / Implementation options

There are several options of expressing this in the language. Note that this is not a major feature, but rather an annoying pain point that needs to be fixed, but is not necessarily too sensitive to the elegance or generality of the solution. 

### Option 1. Intrinsic `Enum.values<E>()`

We can define the following functions in the standard library:

``` kotlin
@InlineOnly
fun <reified E : Enum<E>> Enum.Companion.values(): Array<E> = null!!

@InlineOnly
fun <reified E : Enum<E>> Enum.Companion.valueOf(name: String): E = null!!
```

Both should be intrinsics and the back-end should emit static calls to the `values()`/`valueOf()` methods of the actual enum class passed for `E` instead of inlining the bodies of these functions.
 
This only requires changes to the back-end(s).

### Option 2. Magic `E.values()`

We could do some front-end magic and add synthetic `values()` and `valueOf()` to the static scope of each `<E : Enum<E>>`. The back-ends should still treat such calls specially.

### Option 3. Companion object constraints

If any enum had a companion object, we could make it implement a special interface:
 
``` kotlin
public interface EnumValues<E : Enum<E>> {
    fun values(): Array<E>
    fun valueOf(name: String): E
}
``` 

Then, by supporting constraints on companion objects ([KT-7358](https://youtrack.jetbrains.com/issue/KT-7358)) for reified parameters we could express the constraint of a type having `values()`/`valueOf()`:

``` kotlin
fun <reified T> renderOptions(render: (T) -> String) 
    where companion object T : EnumValues<T>
{
    val values = T.values() // calls a function on the companion object of T
    for (v in values) {
        println(" * " + render(v))
    }
}
```

This is the most involved of the three options:
- requires another language feature: companion object constraints,
- requires implicit companion objects on every enum (problematic for Java enums),
- requires a new interface in `kotlin-runtime`.
 
It is also not entirely clear how `values()` and `valueOf()` can be available on a synthetic companion object for a Java enum without using reflection or byte code spinning at runtime.   

## Arguments against this proposal

- Reflection may actually be good enough for such use cases 
- Java doesn't support anything like this, and people live with it
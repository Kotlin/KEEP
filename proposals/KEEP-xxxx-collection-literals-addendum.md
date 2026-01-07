# Collection Literals Addendum

* **Type**: Design proposal addendum
* **Author**: Nikita Bobko
* **Contributors**:
  Denis Zharkov,
  Grigorii Solnyshkin,
  Pavel Kunyavskiy
* **Issue:** [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871)
* **Original Discussion**: [KEEP-416](https://github.com/Kotlin/KEEP/issues/416)
* **Addendum Discussion**: [todo](todo)

[Original collection literals proposal](./KEEP-0416-collection-literals.md)

<!-- During the implementation and later exploration of the original collection literals proposal it became clear that some things either were not mentioned or need adjustments. -->
<!-- This document targets people who either need to know implementation details  -->

This document assumes that readers are familiar with the original collection literals proposal.

## Table of contents

- [Feature interaction with `@Deprecated(DeprecationLevel.HIDDEN)`/`@SinceKotlin`](#feature-interaction-with-deprecateddeprecationlevelhiddensincekotlin)
- [Fallback](#fallback)
- [Collection literals resolve against not-yet-fixed type variable](#collection-literals-resolve-against-not-yet-fixed-type-variable)
  - [Not-yet-fixed typed variables that don't have any bounds](#not-yet-fixed-typed-variables-that-dont-have-any-bounds)

## Feature interaction with `@Deprecated(DeprecationLevel.HIDDEN)`/`@SinceKotlin`

`@Deprecated(DeprecationLevel.HIDDEN)` and `@SinceKotlin` are two very special annotations that allow acting as if some declarations weren't even declared.

If either of these annotations is applied to a non-vararg `of` overload, the feature interaction is trivial.
The behavior is not different from annotating any other declaration with those annotations.

But given that vararg `of` overload is used to extract type information from it even before the collection literal itself could be resolved:
```kotlin
class MyList { companion object {
    operator fun of(vararg x: Int) = MyList() // (a)
    operator fun of(x: Int, y: Int) = MyList() // (b)
} }


fun outer(a: List<String>) = Unit // (1)
fun outer(a: MyList) = Unit // (2)

fun test() {
    outer([1, 2]) // `outer` resolves to (2).
                  // The collection literal resolves to (b),
                  // but type information was extracted from (a)
                  // before we could even resolve the collection literal.
}
```

we have to make sure that we prevent the type information extraction when the vararg `of` overload is annotated with `@Deprecated(DeprecationLevel.HIDDEN)`/`@SinceKotlin`.

**The following behavior is proposed:**
If the `@Deprecated(DeprecationLevel.HIDDEN)` annotation is applied to the vararg `of` overload,
*the resolution* should behave as if neither `of` overloads had the `operator` modifier.

```kotlin
class MyList { companion object {
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    operator fun of(vararg x: Int) = MyList()

    operator fun of(x: Int, y: Int) = MyList()
} }

fun outer(a: List<Int>) = Unit // (1)
fun outer(a: MyList) = Unit // (2)

fun test() {
    outer([1, 2]) // Resolves to (1)
    val x1: MyList = [1, 2] // Error: MyList doesn't declare `operator fun of(vararg)`
    val x2: MyList = MyList.of(1, 2) // Green
}
```

Please note that the proposed behavior only affects the resolution.
For example, all existing `operator fun of` [restrictions](./KEEP-0416-collection-literals.md#operator-function-of-restrictions) are still in place.

The same behavior is proposed for `@SinceKotlin` if its `version: String` argument is less than the current `-api-version`.

## Fallback

The original proposal [did a poor job](./KEEP-0416-collection-literals.md#fallback-rule-what-if-companionof-doesnt-exist) at defining when the fallback to `kotlin.List` should trigger:

> When the compiler can't find `.Companion.of`, it always falls back to `List.Companion.of`.

It's important to understand that the fallback is probably one of the most fragile details of the whole design, and it mustn't be taken lightly.

The general rule of thumb: the trigger of the fallback



The general design rule is that 

The fallback must only be applied when we are sure that its application won't 

## Collection literals resolve against not-yet-fixed type variable

The original proposal suggests aggressive fallback to `List` when we resolve collection literals against type variables.
The original proposal even lists and acknowledges the puzzling behavior of this design choice:

> **Non-issue 1.**
> Note that type parameters are not yet fixated during applicability checking of overload candidates.
> It means that unconditional fallback to `List` may prospectively dissatisfy type parameter bounds, which sometimes could be puzzling:
>
> ```kotlin
> class MyList { companion object { operator fun <T> of(vararg elements: T): MyList = TODO() } }
>
> fun <T : MyList> outer1(a: T) = Unit // (1)
> fun <T : List<Int>> outer1(a: T) = Unit // (2)
>
> fun outer2(a: MyList) = Unit // (3)
> fun outer2(a: List<Int>) = Unit // (4)
>
> fun <T : MyList> outer3(a: T) = Unit
> fun outer4(a: MyList) = Unit
>
> fun <T> id(t: T): T = t
>
> fun main() {
>     outer1([1]) // resolves to (2)
>     outer2([1]) // overload resolution ambiguity
>
>     outer3([1]) // red. Type mismatch error
>     outer4([1]) // green
>     outer4(id([1])) // red. Type mismatch error
> }
> ```
>
> In the example, we manage to pick the overload in the `outer1` case but not in the `outer2` case.
> We think that such examples are synthetic, and it's more important to have the simple fallback rule.

Unfortunately, the original proposal only considered the upper bounds of not-yet-fixed type variables.
Examples with lower bounds of not-yet-fixed type variables were not considered:

*Listring 1.*

```kotlin
class MyList {
    companion object {
        operator fun of(vararg elements: String): MyList = MyList()
    }
}

fun foo(b: Boolean) {
    // List<String> / Error?
    val x1 = when (b) {
        true -> ["a", "b"]
        false -> arrayListOf("") // NB: ArrayList doesn't have `of` static function
    }

    // Collection<String> / Set<String> / Error?
    val x2 = when (b) {
        true -> ["a", "b"]
        false -> setOf("")
    }

    // Collection<String> / Set<String> / Error?
    val x3 = when (b) {
        true -> ["a", "b"]
        false -> linkedSetOf("") // NB: LinkedHashSet doesn't have `of` static function
    }

    // MyList / Any / Error?
    val x4 = when (b) {
        true -> ["a", "b"]
        false -> MyList()
    }

    // Set / Collection / Error?
    val x5 = getUserSet() ?: ["default user"]

    // Set / Error?
    val x6: Set<String> = when (b) {
        true -> ["a", "b"]
        false -> linkedSetOf("") // NB: LinkedHashSet doesn't have `of` static function
    }
}

fun getUserSet(): Set<String>
```

> [!NOTE]
> One can replace `when` expressions with `fun <T> select(vararg x: T): T = x.first()` function invocations to make the type variable presence more obvious.

The original aggressive fallback to `List` behavior leads to `x1: List<String>`, `x2: Collection<String>`, `x3: Collection<String>`, `x4: Any`, `x5: Collection<String>`, `x6 // error List is not subtype of Set`.
The elvis and `x5` examples, in particular, look bad, and they hint that some kind of special treatment is required for these cases.

Just to quickly discard one another obvious alternative:
blindly resolving collection literal type to the lower bound (when the bound is presented) is a bad idea,
because users will get 'LinkedHashSet.Companion.of' error in `x6` case.
It's unacceptable since users have explicitly specified the type!

We suggest the following behavior:
1.  If only one of the bounds is presented, _leverage_ that bound to resolve the `of` function.
2.  If both bounds are presented, ignore the lower bound and _leverage_ only the upper bound to resolve the `of` function.
    The idea to prefer the upper bound is consistent with [another suggestion](./KEEP-0416-collection-literals.md#feature-interaction-with-flexible-types) of picking the upper bounds for flexible types in the original proposal.
    And it is also consistent with the behavior that we already have for lambda literals:
    ```kotlin
    val y: (Int) -> Unit = when {
        true -> { x -> println(x) } // x: Int
        else -> { a: Number -> println(a) }
    }
    ```

We intentionally avoid specifying what exactly it means to _leverage_ the bound to resolve the `of` function in the suggested behavior above.
It has become clear that specifying every small detail of the feature up front is not sustainable as new cases keep coming up later.
Even the most basic definition of leveraging - namely, attempting to find the `of` function or failing loudly otherwise - already gives a reasonable result for the *Listring 1.*
Instead of failing loudly we could consider todo

### Not-yet-fixed typed variables that don't have any bounds

As we showed in the previous section type variable bounds 

```kotlin
@JvmName("outer1") fun outer(a: List<Int>) = Unit // (1)
@JvmName("outer2") fun outer(a: List<String>) = Unit // (2)

fun <T> id(t: T): T = t

fun test() {
    outer([1]) // (1)
    outer(id([1])) // Overload resolution ambiguity
}
```

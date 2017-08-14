# View holder pattern support and caching options

* Type: Android Extensions Proposal
* Author: Yan Zhulanow

## Summary

Add the `LayoutContainer` interface and the `@ContainerOptions` annotation in order to configure Android Extensions container options.

## Current state

Android Extensions does not have any configurable options for now. Synthetic properties are available for `Activity`, `Fragment` (Android SDK / support-v4) and `View` classes.

## Use cases

* Change `View` cache implementation or disable caching for a specific class;
* Support the view holder pattern.

## Description

### `@ContainerOptions` annotation

Caching options are set using the `@ContainerOptions` annotation. Its declaration:

```kotlin
@Target(AnnotationTarget.CLASS)
annotation class ContainerOptions(
    val cacheImplementation: CacheImplementation = HASH_MAP
)
```

Any Kotlin `Activity`, `Fragment`, or `LayoutContainer` classes can be annotated. "Annotation is not applicable" is displayed on the annotation applied to any other Kotlin class. Annotation is not applicable to Java classes.

The `@ContainerOptions` annotation (as well as other classes/interfaces mentioned in this document) should be placed in the separate JAR artifact available in Maven. The alternative way is described below.

### Caching options

`cacheImplementation` parameter of `@ContainerOptions` has a type of `CacheImplementation`:

```kotlin
enum class CacheImplementation {
    SPARSE_ARRAY,
    HASH_MAP,
    NO_CACHE
}
```

`HASH_MAP` is the default implementation (and the only implementation for now). `HashMap` provides constant-time performance for `get()` and `put()`, though the caching involves `int` View identifier boxing.

As an alternative, `SparseArray` from the Android SDK can be used. `SparseArray` uses binary search to find elements, and it is good for relatively small number of items. Identifier boxing is not needed because keys are primitive integers.

Also, there might be useful to disable caching for some particular class. `NO_CACHE` value can be used in this case:

```kotlin
@ContainerOptions(NO_CACHE)
class MyActivity : Activity() { ... }
```

Cache implementation list is fixed for now.

### View holder pattern

The base idea of the view holder pattern is that you have some base `View`, and you want to get its children:

```kotlin
// Declaration site
class MyViewHolder(val baseView: View) {
	val firstName = baseView.findViewById(R.id.first_name)
	val secondName = baseView.findView(R.id.second_name)
}

...

// Use site
val v: MyViewHolder = MyViewHolder(baseView)
v.firstName.text = user.firstName
v.secondName.text = user.secondName
```

The main advantage of this pattern is that `findViewById()` is called once for each widget.

We can already use Android Extensions with the `MyViewHolder` class (extension properties for the `View` receiver are already available):

```kotlin
v.baseView.first_name.text = user.firstName
```

Though we lose the View caching feature. The solution is to add an `LayoutContainer` interface:

```kotlin
interface LayoutContainer {
    val entityView: View?
}
```

So the previous code fragment can be written like this:

```kotlin
// Declaration site
class MyViewHolder(override val containerView: View): LayoutContainer

...

// Use site
val v = MyViewHolder(baseView)
v.first_name.text = user.firstName
v.second_name.text = user.secondName
```

Extensions properties `first_name` and `second_name` are available also for `LayoutContainer` and placed inside the `kotlinx.android.synthetic.<flavor name>.<activity id>` package.

As mentioned earlier, `LayoutContainer` implementations can also be annotated with `@ContainerOptions`.

## Additional information

* There should be an extra annotation checker (`@ContainerOptions` is applicable only to `Activity`, `Fragment` or `LayoutContainer` descendants).

## Alternatives

For now there is no runtime dependency for Android Extensions, and the alternative way is to make all described classes synthetic. It requires some (dirty) hacks.

## Related issues

* [KT-9892](https://youtrack.jetbrains.com/issue/KT-9892) Android Extensions: Support view holder pattern and custom View classes.

* [KT-10542](https://youtrack.jetbrains.com/issue/KT-10542) Android Extensions: No cache for Views.

## Future advancements

New parameters can be added to the `@AndroidEntityOptions` annotation, providing the additional functionality.

## Open questions

* Do we need to change the default cache implementation to `SparseArray`?
    * Looks like no, because "It is generally slower than a traditional HashMap, since lookups require a binary search and adds and removes require inserting and deleting entries in the array" ([Android Documentation](https://developer.android.com/reference/android/util/SparseArray.html))

# Alternative behavior for Map.getOrElse

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda, Ilya Gorbunov
* **Contributors**: Vsevolod Tolstopyatov, Filipp Zhinkin
* **Status**: Proposed
* **Prototype**: TDB
* **Target issue**: [KT-67337](https://youtrack.jetbrains.com/issue/KT-67337)
* **Discussion**: TBD

## Summary

Introduce `Map.getOrElse` variants that handle `null` entry values differently.

## Motivation

Currently, the Standard Library provides the following function:
```kotlin
public inline fun <K, V> Map<K, V>.getOrElse(key: K, defaultValue: () -> V): V
```
This function treats a `null` value as an absent entry and returns the result of the `defaultValue()` function.
Similarly, the `MutableMap.getOrPut` function:
```kotlin
public inline fun <K, V> MutableMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V
```
also treats a `null` value as an absent entry, that is, it puts and returns the result of the `defaultValue()` function.
As evidenced by [KT-21392](https://youtrack.jetbrains.com/issue/KT-21392), this behavior is not the
most intuitive or expected one. Moreover, `getOrElse` on other collections,
such as [List.getOrElse](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/get-or-else.html),
does not differentiate `null` values from non-nulls.
Given that there are use cases for both treating a mapping to a `null` value as absent and treating it as present,
we propose to introduce functions that cater to both behaviors, with function names that explicitly disambiguate their behavior.

## Use cases

The use cases for `getOrElse` and `getOrPut` are straightforward. `getOrElse` retrieves the stored value or,
if no value exists for the key, computes a fallback value. For example:
```kotlin
inventory.getOrElse(itemId) { 0 }
```
`getOrPut` is typically used for caching key-related calculations. For instance:
```kotlin
nameToClass.getOrPut(className) { loadClassOrNull(className) }
```

Confusion arises when the map has a nullable value type and `null` is considered a valid value.
For example, if loading a class by name fails and `null` gets stored, it might be undesirable to attempt
reloading the class on subsequent accesses.

## Similar API review

Below are existing similar functions and their behavior regarding `null` values.
This includes functions from both Kotlin and Java that are available in Kotlin/JVM targets.

| Method                           | null == absent | null != absent |
|:---------------------------------|:---------------|:---------------|
| Map.getOrElse                    | ✔️             |                |
| Map.getOrElseNullable (internal) |                | ✔️             |
| Map.getValue                     |                | ✔️             |
| Map.getOrPut                     | ✔️             |                |
|                                  |                |                |
| MutableMap.compute               | ✔️             |                |
| MutableMap.computeIfAbsent       | ✔️             |                |
| MutableMap.computeIfPresent      | ✔️             |                |
| MutableMap.merge                 | ✔️             |                |
| MutableMap.replace               |                | ✔️             |
| MutableMap.putIfAbsent           | ✔️             |                |
| Map.getOrDefault                 |                | ✔️             |

Note that in Java, the suffixes `IfAbsent` and `IfPresent` signify if a value is "absent-or-null" and
"present-and-not-null", respectively.

## Proposal

It is proposed to introduce two variants of `Map.getOrElse`:
* `getOrElseIfNull` - Returns the mapped value if it exists and is not `null`; otherwise, returns the result of `defaultValue()`. 
  This function mirrors the behavior of the existing `getOrElse`.
* `getOrElseIfMissing` - Returns the mapped value if it exists, even if it is `null`; otherwise, returns the result of `defaultValue()`.

Similarly, for `MutableMap.getOrPut`:
* `getOrPutIfNull` - Returns the mapped value if it exists and is not `null`; otherwise, puts and returns the result of `defaultValue()`.
  This function mirrors the behavior of the existing `getOrPut`.
* `getOrPutIfMissing` - Returns the mapped value if it exists, even if it is `null`; otherwise, puts and returns the result of `defaultValue()`.

This `null`-handling differentiation is relevant only for maps with nullable value types.
However, due to the limitations of Kotlin type system in resolving functions only for nullable generic type parameters,
these variants will be available for all maps, including `Map<String, String>`.
To mitigate potential confusion, we propose utilizing IDE inspection and code completion features:
* For existing `getOrElse()` invocations on maps with potentially nullable entry values
  (e.g., `String?`, generic `V`, `V?`), IDE inspection will suggest replacing it with either `getOrElseIfNull` or `getOrElseIfMissing`.
* When typing `map.get...` on maps with potentially nullable entry values, IDE code completion will hide `getOrElse()`.
* Conversely, IDE code completion will hide `getOrElseIfNull` and `getOrElseIfMissing` for maps with non-nullable entry values.

For maps with non-nullable entry values, the behavior of `getOrElse` remains straightforward and unambiguous.
Hence, we do not plan to deprecate it.

The same IDE inspection and code completion changes are proposed for `getOrPut`.

The code completion changes will ensure that developers do not see `getOrElse`, `getOrElseIfNull`, and `getOrElseIfMissing`
all at the same time. Instead, only the relevant functions will be presented, based on the generic parameters of the receiver map.

### Considered names

Coming up with good names for the new functions proved to be challenging.
Below are the alternative names we considered:

For `getOrElseIfNull`:
* `getIfNotNullOrElse`        / `getIfNotNullOrPut`
* `getNotNullOrElse`          / `getNotNullOrPut`

For `getOrElseIfMissing`:
* `getOrElseIfMissingKey`     / `getOrPutIfMissingKey`
* `getOrElseIfNoMapping`      / `getOrPutIfNoMapping`
* `getOrElseIfUnmapped`       / `getOrPutIfUnmapped`
* `getOrElseIfNoKey`          / `getOrPutIfNoKey`
* `getOrElseIfNoEntry`        / `getOrPutIfNoEntry`
* `getOrElseIfNotContains`    / `getOrPutIfNotContains`
* `getIfContainsOrElse`       / `getIfContainsOrPut`
* `getIfPresentOrElse`        / `getIfPresentOrPut` -  In Java, `IfPresent` suffix means the value is "present-and-not-null"
* `getOrElseIfAbsent`         / `getOrPutIfAbsent` -  In Java, `IfAbsent` suffix means the value is "absent-or-null"

As always, any naming suggestions are welcome.

## Considered approaches

### 1. Phase out `getOrElse` and `getOrPut` with replacements and reintroduce them later

This approach deprecates `getOrElse` and `getOrPut` and replaces them with `getOrElseIfNull` and `getOrPutIfNull`.
The goal is to eventually phase them out completely and then reintroduce them with new behavior that
returns the mapped value even if it is `null`.

We did not select this approach because it would constitute a breaking change.
While we could preserve binary compatibility, there is no way to preserve source compatibility.
Users would have to migrate their code.

### 2. Introduce overloads for `getOrElse` and `getOrPut` with non-null value types

This approach separates `getOrElse` and `getOrPut` into overloads with nullable and non-nullable value type:
```kotlin
// Existing signatures
public inline fun <K, V> Map<K, V>.getOrElse(key: K, defaultValue: () -> V): V
public inline fun <K, V> MutableMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V

// Added signatures, possibly with different platform names
public inline fun <K, V : Any> Map<K, V>.getOrElse(key: K, defaultValue: () -> V): V
public inline fun <K, V : Any> MutableMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V
```

This approach allows deprecating only problematic usages of the functions.
Maps with non-null value types would resolve to the new overloads due to a
[more specific type](https://kotlinlang.org/spec/overload-resolution.html#choosing-the-most-specific-candidate-from-the-overload-candidate-set).
Problematic usages can then be replaced with the more explicit `getOrElseIfNull` or `getOrElseIfMissing`.

However, this approach introduces compilation errors in existing code when the receiver map has a non-null value type,
but the `defaultValue` function returns a nullable value:
```kotlin
firstChoice.getOrElse(key) { fallback.get(key) }
```

## Dependencies

Only a subset of Kotlin Standard Library available on all supported platforms is required.

## Placement

* Standard Library
* `kotlin.collections` package

# Extensions to copy map contents

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Discussed
* **Prototype**: Implemented
* **Target tickets**: [KT-9108](https://youtrack.jetbrains.com/issue/KT-9108)
* **Discussion**: [KEEP-13](https://github.com/Kotlin/KEEP/issues/13)

## Summary

Standard Library provides several ways to copy collections such as `List` or `Set`, but doesn't do so for `Maps`.

## Similar API review

The following table summarizes which operations are available for Lists, Sets and Maps in the Standard Library.

| Operation            | List    | Set      | Map     |
| -------------------- | ------- | -------- | ------- |
| create from elements | listOf |	setOf |	mapOf |
| create mutable from elements |	mutableListOf |	mutableSetOf |	mutableMapOf |
| create special from elements |	arrayListOf |	 hashSetOf linkedSetOf sortedSetOf | hashMapOf linkedMapOf sortedMapOf  |
| create from iterable of elements	| toList | toSet	| toMap |
| create mutable from iterable of elements | toMutableList | toMutableSet	| - |
| create special from iterable of elements | 	- |	toHashSet toSortedSet	| - |
| fill target from iterable of elements |	toCollection(MutableList) | toCollection(MutableSet) | toMap(MutableMap) |
| copy of self |	toList |	toSet	| - |
| mutable copy of self |	toMutableList	| toMutableSet | - |
| special copy of self |	- |	toHashSet toSortedSet |	toSortedMap |
| fill target from self |	toCollection(MutableList)	| toCollection(MutableSet) |	- |

The elements to create map from here are key-value pairs `Pair<K,V>`.

## Description

It is proposed to introduce extensions for map which make various copies of the map contents:
- `Map<out K, V>.toMap(): Map<K, V>` - read only copy of map
- `Map<out K, V>.toMutableMap(): MutableMap<K, V>` - mutable copy of map
- `Map<out K, V>.toMap(M <: MutableMap<in K, in V>): M` - copy map to specified mutable map and return it

## Use cases

1. Defensive read-only copy of map
  ```kotlin
  class ImmutablePropertyBag(map: Map<String, Any>) {
    private val mapCopy = map.toMap()
    
    val setting1: String by mapCopy
    val setting2: Int by mapCopy
  }
  ```
2. Making a copy of map to populate it with additional entries later
  ```kotlin
  fun updateMappings(map: MutableMap<String, String>): Unit { ... }
  
  val map: Map<String, String> = ...
  val remapped = map.toMutableMap().apply { updateMappings(this) }
  ```

3. Making specialized copy of a map
  ```kotlin
  val map: Map<String, Any> = ...
  val treeMap = map.toMap(TreeMap())
  ```

## Alternatives

1. Use concrete map implementation constructors
  ```kotlin
  val copy = HashMap(map)
  val treeMap = TreeMap(map)
  ```
  
  Disadvantages:
  
  - in case 1 produces more specific map than required;
  - requires to decide on concrete implementation in advance;
  - not chaining to the end of operation chain.

2. Convert map to list of pairs and then back to map
  ```kotlin
  val copy = map.toList().toMap()
  val mutableCopy = map.toList().toMap(mutableMapOf())
  val treeMap = map.toList().toMap(TreeMap())
  ```
  
  Disadvantages: 
  
  - requires to create intermediate list of pairs, can cause significant GC pressure.

3. To populate destination map, use its `destination.putAll(this)` method instead of `this.toMap(destination)`.

  Disadvantages:

  - `putAll` returns `Unit`, so it doesn't chain well.

## Dependencies

A subset of Kotlin Standard Library available on all supported platforms.

## Placement

- module `kotlin-stdlib`
- package `kotlin.collections`

## Reference implementation

See branch [rr/stdlib/map-copying](https://github.com/JetBrains/kotlin/compare/rr/stdlib/map-copying~2...rr/stdlib/map-copying).

## Future advancements

- Functions to create mutable or special map from iterable of elements. 
- Functions to create maps from iterable of `Map.Entry`.

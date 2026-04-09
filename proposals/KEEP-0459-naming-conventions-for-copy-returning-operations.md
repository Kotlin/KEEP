# Naming conventions for copy-returning and in-place-mutating operations

* **Type**: Naming Convention / API Guidelines
* **Author**: Dmitry Nekrasov
* **Status**: Draft
* **Applied in**: [`kotlinx-collections-immutable`](https://github.com/Kotlin/kotlinx.collections.immutable)
* **Discussion**: [#480](https://github.com/Kotlin/KEEP/discussions/480)

## Summary

When an API offers both a mutating and a copy-returning variant of the same logical operation, the names of those variants should clearly signal which one modifies the receiver in place and which one returns a modified copy. In Kotlin, the convention is:

- **In-place-mutating** operations use an imperative verb: `list.reverse()` reverses the receiver in place.
- **Copy-returning** operations use a participial (past or present participle) form: `list.reversed()` returns a new collection in reversed order, leaving the original unchanged.

The Kotlin standard library already follows this pattern in several places: `sort()` vs `sorted()`, `reverse()` vs `reversed()`, `shuffle()` vs `shuffled()`. This proposal formalizes the convention and extends it to operations with explicit arguments — such as `add`/`adding` and `put`/`putting`.

## Motivation

Encoding the mutation semantics directly in the function name provides several concrete benefits:

- **Cognitive load at call sites.** Without a naming distinction, a reader must resolve the receiver type to understand whether `collection.add(element)` modifies state or produces a new value. A participial name like `adding` immediately communicates that the receiver is not mutated.

- **Discarded return values.** A common bug pattern with persistent collections is writing `persistentList.add(x)` and silently discarding the result. While tooling such as a return-value checker is the primary defense against this class of bugs, a participial name like `adding(x)` provides an additional hint at the call site that a value is being produced and should be captured.

- **Code review clarity.** During review, participial names make it immediately visible whether a line of code mutates shared state or creates a local copy, without requiring the reviewer to look up types.

- **Consistency.** The Kotlin standard library already uses this pattern for `sort`/`sorted`, `reverse`/`reversed`, `shuffle`/`shuffled`. Formalizing the convention ensures new APIs follow the same principle rather than inventing ad hoc naming schemes.

## The convention

> Name functions and methods so that they clearly indicate whether they modify the receiver in place or return a modified copy.
>
> - Those that **modify the receiver in place** should read as imperative verb phrases: `list.add(element)`, `map.put(key, value)`, `collection.clear()`.
> - Those that **return a modified copy** should read as participial or noun phrases: `list.adding(element)`, `map.putting(key, value)`, `collection.cleared()`.

The choice between past participle (`-ed`) and present participle (`-ing`) depends on whether the function takes a direct-object argument; see [Suffix rules: when to use `-ed` vs `-ing`](#suffix-rules-when-to-use--ed-vs--ing) for the detailed rules.

See [Naming in other ecosystems](#naming-in-other-ecosystems) for how other languages approach this problem.

## Applicability and scope

This convention **applies when** an API provides both a mutating and a copy-returning variant of the same logical operation. The canonical example is mutable vs. persistent collections: `MutableList.add(element)` modifies in place, while `PersistentList.adding(element)` returns a new list.

**When the operation name could plausibly denote an in-place mutation.** Even if an API is immutable-only, use the participial form when the operation name could plausibly suggest in-place mutation. For example, prefer `Graph.adding(vertex)` over `Graph.add(vertex)`.

  *Rationale:* names like `add` or `remove` are ambiguous to a reader who does not know the type is immutable. The participial form communicates copy semantics at the call site regardless of the reader's knowledge of the type, and it avoids a naming collision if a mutating counterpart is introduced later.

The convention **does not apply when**:

- **The operation name inherently implies producing a new value.** Functions like `map`, `filter`, `flatMap`, and `zip` have no in-place-mutating equivalent in the standard library and their names do not suggest one. There is no ambiguity to resolve, and their established names are not subject to this convention. (Contrast with `add` or `remove`, whose names *could* plausibly denote in-place mutation — see above.)
- **The name is already unambiguous.** Conversion functions like `toList()`, `toSortedSet()`, and `toMap()` clearly indicate that they produce a new value through the `to-` prefix.
- **Established standard library names that will not be renamed.** This convention is forward-looking; it does not propose renaming existing standard library functions.

## Naming in other ecosystems

### Imperative verbs (C#, Immutable.js)

C# `System.Collections.Immutable` uses entirely imperative naming, matching mutable collection APIs exactly. Methods like `Add()`, `Remove()`, `Clear()` all return new immutable instances rather than mutating in place. The return type - not the method name - signals immutability. Immutable.js follows a similar pattern, using familiar `Array` methods (`push`, `pop`, `shift`) that return new instances.

### Grammatical -ed/-ing rule (Swift)

[Swift's official API Design Guidelines](https://www.swift.org/documentation/api-design-guidelines/#strive-for-fluent-usage) dictate that mutating methods use imperative verbs, while non-mutating methods use `-ed` or `-ing` suffixes: `sort()` vs `sorted()`, `append(y)` vs `appending(y)`. The `mutating` keyword provides compile-time enforcement. This is the closest analog to the Kotlin convention proposed here.

### Symbolic operators (Scala)

Scala uses symbolic operators (`+`, `:+`, `++`) for immutable collection operations and compound assignment operators (`+=`, `-=`) for mutable ones. While concise, symbolic operators have poor discoverability and can be difficult to search for.

### Builder pattern only (Guava)

Guava's immutable collections provide no methods for creating modified copies at all. To "modify" an immutable collection, you must create a new `Builder`, populate it, and call `build()`. This avoids the naming problem entirely but sacrifices ergonomics for simple modifications.

The grammatical approach maps most naturally onto Kotlin's existing conventions and provides the best balance of clarity, discoverability, and consistency with the standard library.

## Alternatives considered

1. **`with`/`without`** (e.g., `withAdded(element)`, `withoutElement(element)`). While more elegant variants are possible — `withMapping(key, value)` or simply `with(key, value)` instead of the awkward `withPut(key, value)` — the pattern still has significant drawbacks: `with(key, value)` collides even more directly with Kotlin's `with` scoping function; names like `withMapping` use a different verb from the mutating counterpart (`put`), breaking the correspondence between mutable and persistent APIs that this convention aims to preserve; and the pattern requires inventing a new name for each operation rather than providing a mechanical derivation rule (verb → participle). (The participial convention also has one exception — `set` → `replacingAt`, see [below](#suffix-rules-when-to-use--ed-vs--ing) — but is otherwise fully mechanical.)

2. **`copying<Verb>`** (e.g., `copyingAdd(element)`, `copyingRemove(element)`, `copyingPut(key, value)`, `copyingClear()`). This pattern was explored in a [prototype implementation](https://github.com/Kotlin/kotlinx.collections.immutable/pull/234). While explicit about copy semantics, it has several drawbacks: the `copying` prefix is verbose and becomes repetitive noise on persistent types where *every* operation returns a copy; it breaks the grammatical parallel with the standard library (`sorted()`, not `copyingSort()`); and it reads less naturally in English than the participial form (`list.adding(4)` vs. `list.copyingAdd(4)`).

3. **`plus`/`minus`** (e.g., `plus(element)`, `minus(element)`). These already exist as operator extension functions in the standard library and in `kotlinx-collections-immutable`. However, they do not generalize to operations like `put`, `clear`, `retainAll`, or `set`.

4. **`filter`/`filterNot`** instead of `removeAll`/`retainAll`. This only covers predicate-based removal and would be inconsistent across the full API surface where element-based and index-based variants are also needed.

## Suffix rules: when to use `-ed` vs `-ing`

The choice between past participle (`-ed`) and present participle (`-ing`) depends on whether the function takes an explicit argument that serves as a [direct object](https://en.wikipedia.org/wiki/Object_(grammar)#Direct_object) (the noun phrase receiving the action of the verb — for example, in "add *element*", *element* is the direct object).

### No explicit direct object → `-ed`

When the function operates solely on the receiver and takes no argument that serves as a direct object, append `-ed`:

| In-place-mutating | Copy-returning | Why `-ed` |
|----------|-------------|-----------|
| `sort()` | `sorted()` | No explicit direct object |
| `reverse()` | `reversed()` | No explicit direct object |
| `shuffle()` | `shuffled()` | No explicit direct object |
| `clear()` | `cleared()` | No explicit direct object |

These read naturally as adjectives describing the resulting state: *"a sorted list"*, *"a cleared map"*.

Note: verbs like `sort` are grammatically transitive (the receiver *is* the thing being sorted), but for the purpose of this convention what matters is whether the function signature includes an explicit direct-object parameter. Arguments that describe *how* the operation is performed — such as comparators (`sortWith(comparator)`), key selectors (`sortBy { ... }`), and predicates — are not direct objects; they are instruments or modifiers (sort **by** X, sort **with** Y) and do not trigger the `-ing` form.

### Explicit direct object → `-ing`

When the function takes an explicit argument that serves as a direct object - the element, key, or collection being operated on - append `-ing`:

| In-place-mutating | Copy-returning | Why `-ing` |
|----------|-------------|-----------|
| `add(element)` | `adding(element)` | `element` is the direct object |
| `remove(element)` | `removing(element)` | `element` is the direct object |
| `put(key, value)` | `putting(key, value)` | `key`/`value` are direct objects |
| `addAll(elements)` | `addingAll(elements)` | `elements` is the direct object |

### Overload consistency

The two rules above — "predicates and comparators are not direct objects" and "use `-ing` when a direct object is present" — can appear to conflict when a function has overloads with mixed parameter roles. For example, `removeAll` has both a collection overload (`removeAll(elements)`, where `elements` is a direct object) and a predicate overload (`removeAll(predicate)`, where the predicate is *not* a direct object).

In such cases, **overload consistency takes precedence**: all overloads of the same function use the same participial form. The predicate in `removeAll(predicate)` is not a direct object, but `removingAll(predicate)` matches `removingAll(elements)` to keep the overload set uniform. Without this rule, a single function would have both a participial and an imperative overload, creating exactly the kind of ambiguity this convention is designed to prevent.

To summarize the priority:

1. If *any* overload of a function takes a direct object, use `-ing` for *all* overloads.
2. If *no* overload takes a direct object (e.g., `sort()`, `sortBy { ... }`, `sortWith(comparator)`), use `-ed`.

### Indexed operations → `-At` suffix

When a copy-returning operation accepts an index, append `-At` to disambiguate from the non-indexed overload:

| In-place-mutating | Copy-returning | Rationale |
|----------|-------------|-----------|
| `add(index, element)` | `addingAt(index, element)` | Distinguishes from `adding(element)` |
| `addAll(index, c)` | `addingAllAt(index, c)` | Distinguishes from `addingAll(c)` |
| `set(index, element)` | `replacingAt(index, element)` | Avoids ambiguous `setting` |
| `removeAt(index)` | `removingAt(index)` | Distinguishes from `removing(element)` |

The `-At` suffix prevents ambiguity when the element type is `Int` - for example, `removing(1)` could mean "remove the element `1`" or "remove the element at index `1`". The suffix also aligns with the existing `MutableList.removeAt(index)` convention in the standard library.

### Generalizing preposition-based suffixes

The `-At` suffix is one instance of a broader pattern: preposition-based suffixes can disambiguate overloads by describing the parameter's role. For example, an API operating on ranges might use `-Until` or `-From` (e.g., `removingUntil(bound)`, `removingFrom(bound)`). The same principle applies: the suffix clarifies how the parameter relates to the operation. Specific suffix recommendations beyond `-At` are outside the scope of this proposal.

## Precedent in the Kotlin Standard Library

The Kotlin standard library already pairs in-place-mutating and copy-returning operations using exactly this naming pattern:

| In-place-mutating (`MutableList`) | Copy-returning (extension) | Suffix |
|--------------------------|--------------------------|--------|
| `sort()` | `sorted()` | `-ed` |
| `sortBy { ... }` | `sortedBy { ... }` | `-ed` |
| `sortWith(comparator)` | `sortedWith(comparator)` | `-ed` |
| `reverse()` | `reversed()` | `-ed` |
| `shuffle()` | `shuffled()` | `-ed` |

These standard library pairs demonstrate that Kotlin developers already expect participial suffixes to signal a copy-returning variant. The persistent-collection API simply applies the same principle to `add`/`remove`/`put`/`clear`.

## Application to kotlinx-collections-immutable

> **Note:** Adopting this convention in `kotlinx-collections-immutable` will involve deprecating the current imperative-named methods and introducing their participial replacements. The deprecation strategy, migration path, and backward-compatibility considerations are outside the scope of this KEEP and will be addressed in a separate migration document for the library.

The following tables show how the convention is applied to the persistent-collection API in `kotlinx-collections-immutable`, demonstrating the naming scheme across the full API surface.

### `PersistentCollection`

| In-place-mutating equivalent | Persistent method | Suffix |
|---------------------|-------------------|--------|
| `add(element)` | `adding(element)` | `-ing` |
| `addAll(elements)` | `addingAll(elements)` | `-ing` |
| `remove(element)` | `removing(element)` | `-ing` |
| `removeAll(elements)` | `removingAll(elements)` | `-ing` |
| `removeAll(predicate)` | `removingAll(predicate)` | `-ing` |
| `retainAll(elements)` | `retainingAll(elements)` | `-ing` |
| `clear()` | `cleared()` | `-ed` |

### `PersistentList` (additional indexed operations)

| In-place-mutating equivalent | Persistent method | Suffix |
|---------------------|-------------------|--------|
| `add(index, element)` | `addingAt(index, element)` | `-ing` |
| `addAll(index, elements)` | `addingAllAt(index, elements)` | `-ing` |
| `set(index, element)` | `replacingAt(index, element)` | `-ing` |
| `removeAt(index)` | `removingAt(index)` | `-ing` |

> **Note:** `set` is renamed to `replacingAt` rather than `setting` because `setting` reads ambiguously in English (it could suggest configuring a setting). `replacingAt` more precisely describes the operation — substituting the element at a given index — and avoids the ambiguity. The `-At` suffix is retained for consistency with the other indexed operations (`addingAt`, `removingAt`), even though there is no non-indexed `replacing` overload to disambiguate from.

### `PersistentSet`

Inherits all operations from `PersistentCollection` with return type narrowed to `PersistentSet<E>`. No additional methods.

### `PersistentMap`

| In-place-mutating equivalent | Persistent method | Suffix |
|---------------------|-------------------|--------|
| `put(key, value)` | `putting(key, value)` | `-ing` |
| `putAll(map)` | `puttingAll(map)` | `-ing` |
| `remove(key)` | `removing(key)` | `-ing` |
| `remove(key, value)` | `removing(key, value)` | `-ing` |
| `clear()` | `cleared()` | `-ed` |

## Usage examples

### Mutable vs Persistent

```kotlin
// Mutable: imperative verbs, modifies in place
val mutableList = mutableListOf(1, 2, 3)
mutableList.add(4)
mutableList.remove(2)
mutableList.clear()

// Persistent: participial forms, returns new instances
val list0 = persistentListOf(1, 2, 3)
val list1 = list0.adding(4)  // [1, 2, 3, 4]
val list2 = list1.removing(2)  // [1, 3, 4]
val list3 = list2.cleared()  // []
// list0 is still [1, 2, 3]
```

### Map operations

```kotlin
val map0 = persistentMapOf("a" to 1, "b" to 2)
val map1 = map0.putting("c", 3)  // {a=1, b=2, c=3}
val map2 = map1.removing("a")  // {b=2, c=3}
val map3 = map2.puttingAll(mapOf("d" to 4, "e" to 5))  // {b=2, c=3, d=4, e=5}
```

## Relationship with operator overloads

The `+` and `-` operators on persistent collections delegate directly to the participial named methods:

| Operator | Delegates to |
|----------|-------------|
| `collection + element` | `adding(element)` |
| `collection + elements` | `addingAll(elements)` |
| `collection - element` | `removing(element)` |
| `collection - elements` | `removingAll(elements)` |
| `map + pair` | `putting(key, value)` |
| `map + map` | `puttingAll(map)` |
| `map - key` | `removing(key)` |

The compound assignment operators `+=` and `-=` follow Kotlin's standard resolution rules, which naturally align with the mutating/copy-returning distinction:

- **Mutable collections** (`val mutableList: MutableList<Int>`): `+=` resolves to `plusAssign`, which calls the in-place `add`. The collection is mutated.
- **Persistent collections in a `var`** (`var list: PersistentList<Int>`): `+=` resolves to `list = list + element`, which calls `plus` and thus `adding`. A new instance is assigned.

```kotlin
// Mutable: += mutates in place
val mutableList = mutableListOf(1, 2, 3)
mutableList += 4  // calls plusAssign → add(4)

// Persistent: += reassigns via copy-returning operator
var list = persistentListOf(1, 2, 3)
list += 4  // compiles to: list = list + 4 → list = list.adding(4)
```

This means `+=`/`-=` behave consistently with the naming convention: they mutate when the receiver is mutable, and produce a new value when the receiver is persistent.

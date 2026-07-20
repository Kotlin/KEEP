# allEqual and allDistinct

* **Type**: Standard Library API proposal
* **Author**: Dmitry Nekrasov
* **Status**: Experimental in 2.4.20
* **Prototype**: Implemented
* **Target issues**: [KT-10380](https://youtrack.jetbrains.com/issue/KT-10380), [KT-30270](https://youtrack.jetbrains.com/issue/KT-30270)
* **Discussion**: [#495](https://github.com/Kotlin/KEEP/discussions/495)

## Summary

Two new families of extension functions that answer two recurring questions about a collection's elements:

* `allEqual()` / `allEqualBy { }`: "are all elements equal to each other?"
* `allDistinct()` / `allDistinctBy { }`: "are all elements different from each other?"

They cover `Iterable`, `Sequence`, object arrays, all primitive arrays, and the unsigned arrays. Both return
`true` for empty and single-element receivers. The two families were designed jointly, modeled on the earlier
`isSorted` family, and are duals: `allEqual` holds when the receiver has at most one distinct value,
`allDistinct` when it has exactly as many distinct values as it has elements.

Unlike `isSorted`, both ship as `@ExperimentalStdlibApi`, because of two decisions worth community feedback
before stabilization: the floating-point equality model and the absence of a custom-predicate overload. Both
are explained below under [Design decisions](#design-decisions), and the full signature surface is in
[The API](#the-api).

## Motivation

"Are all of these the same?" and "are these all different?" are everyday questions about a collection, but
neither has a concise spelling in the standard library today, so developers hand-roll them over and over. The
repetition is a cost in itself: it is tedious, and each copy is a fresh chance to make a small mistake that
no test catches, because snippets this simple rarely get their own tests. The hand-rolled forms also share
two performance defects:

* They always scan the whole input, even after the answer is settled by the first mismatch or first
  duplicate.
* They build a throwaway `HashSet` or `List` only to compare its size against the original.

A named, short-circuiting operation is written and tested once, and does better on both performance counts,
though not identically for the two functions. Both return at the first mismatch or duplicate instead of
scanning to the end. `allEqual` also needs no intermediate collection; `allDistinct` still builds a `HashSet`,
but stops filling it at the first repeat. The two questions are duals over the same expression, so they are
proposed together: `xs.distinct().size` reads as `allEqual` when compared against `1` and as `allDistinct`
when compared against `xs.size`.

## Use cases

Both checks are written by hand constantly, and a survey of a large number of open-source Kotlin repositories
found both shapes throughout.

For "all the same", at most one distinct value:

```kotlin
xs.distinct().size <= 1
xs.toSet().size <= 1
xs.all { it == xs.first() }
```

For example:

* gradle, [`KotlinSourceQueries.kt:115`](https://github.com/gradle/gradle/blob/36c532001503a369011b2dac4ab64236d0325554/build-logic/binary-compatibility/src/main/kotlin/gradlebuild/binarycompatibility/sources/KotlinSourceQueries.kt#L115):
  `sinceTags.all { it == sinceTags.first() }`
* sqldelight, [`TypeResolver.kt:83`](https://github.com/sqldelight/sqldelight/blob/94618ca7220511c25b1c375afae46c649a74d4e6/sqldelight-compiler/dialect/src/main/kotlin/app/cash/sqldelight/dialect/api/TypeResolver.kt#L83):
  `val isTypesHomogeneous = types.map { ... }.distinct().size == 1`
* lets-plot, [`DensityStatUtil.kt:201`](https://github.com/JetBrains/lets-plot/blob/4e1c2b179c59e5d1764d37a8cf0bfcf23a865c7c/plot-base/src/commonMain/kotlin/org/jetbrains/letsPlot/core/plot/base/stat/DensityStatUtil.kt#L201):
  `require(statData.values.map { it.size }.toSet().size == 1) { "All data series in stat data must have equal size" }`
* apollo-kotlin, [`IrOperationsBuilder.kt:510`](https://github.com/apollographql/apollo-kotlin/blob/d300e06f5f04a3a0dd0b874f93ccc7c164154152/libraries/apollo-compiler/src/main/kotlin/com/apollographql/apollo/compiler/ir/IrOperationsBuilder.kt#L510):
  `check(fieldsWithSameResponseName.map { it.type }.distinctBy { it.pretty() }.size == 1)` (the `allEqualBy` shape)

For "no duplicates", as many distinct values as elements:

```kotlin
xs.distinct().size == xs.size
xs.toSet().size == xs.size
xs.groupingBy { it }.eachCount().none { it.value > 1 }
```

These turn up across many of the surveyed repositories, a sizeable share in the exact `allDistinct()` /
`allDistinctBy {}` shape; roughly two-thirds are production code and about one-third tests, and close to half
sit inside `require` / `check` / `assert*` whose messages spell out the intent: *"... must be unique"*,
*"Duplicated key"*, *"was not distinct"*. For example:

* detekt, [`UnnecessaryPartOfBinaryExpression.kt:45`](https://github.com/detekt/detekt/blob/1847f822b4023ed30504a0cc47c2e0be76dbda7a/detekt-rules-performance/src/main/kotlin/dev/detekt/rules/performance/UnnecessaryPartOfBinaryExpression.kt#L45):
  `if (expressions.size != expressions.distinct().size) { ... }`
* Exposed, [`SchemaUtilityApi.kt:62`](https://github.com/JetBrains/Exposed/blob/39d63400b2a8e1a8915fbab7c3b14e717fb9b474/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/SchemaUtilityApi.kt#L62):
  `require(target.toHashSet().size == target.size) { "Not all referenced columns ... are unique" }`
* Anki-Android, [`PreferenceUpgradeServiceTest.kt:87`](https://github.com/ankidroid/Anki-Android/blob/8f5ee22214d6b243b501a02b2d6319d9e6730b40/AnkiDroid/src/test/java/com/ichi2/anki/servicemodel/PreferenceUpgradeServiceTest.kt#L87):
  `assertThat("all version IDs should be distinct", codes.size, equalTo(codes.distinct().size))` (a test)
* intellij-community, [`Markers.kt:200`](https://github.com/JetBrains/intellij-community/blob/0e0dc50641d40cdaa89bf539ac7fd8a8b9fa2c48/plugins/kotlin/code-insight/line-markers/src/org/jetbrains/kotlin/idea/codeInsight/lineMarkers/Markers.kt#L200):
  `fun Collection<KtDeclaration>.hasUniqueModuleNames() = distinctBy { ... }.size == size` (the `allDistinctBy` shape)

## Similar API review

### Within Kotlin

`allEqual` and `allDistinct` reduce a collection to a single `Boolean` by testing a property of its elements,
much like `all`, `any`, and `none`. Here that
property is mutual, the elements' equality or distinctness as a whole, rather than something each element has
on its own. The closest existing sibling is the `isSorted` / `isSortedBy` / `isSortedWith` family
([KT-78499](https://youtrack.jetbrains.com/issue/KT-78499)), built on the same template infrastructure; "are
these sorted?", "are these all equal?", and "are these all distinct?" are the same kind of whole-collection
question. They compare with `equals`, like the library's `distinct()` / `distinctBy()` and `toSet()`, and the
floating-point model below follows from that choice. Notably the library offers `distinct()` and `distinctBy()`
but no `distinctWith()`; `allEqual` / `allDistinct` follow that precedent (see
[No custom-predicate overload](#no-custom-predicate-overload)).

### Other languages and libraries

A dedicated function for either check is uncommon; where it exists, it is in iterator-utility libraries:

* Rust [`itertools`](https://docs.rs/itertools/latest/itertools/trait.Itertools.html): `all_equal()` and
  `all_unique()`, short-circuiting Booleans that take no argument, neither a selector nor a predicate
  (`itertools` also offers `all_equal_value()`, which returns the common element).
* Python [`more-itertools`](https://more-itertools.readthedocs.io/en/stable/api.html):
  `all_equal(iterable, key=None)` and `all_unique(iterable, key=None)`, which add an optional `key` selector
  but, again, no binary predicate.

Between them they cover both forms this proposal adopts, the no-argument check and the `By(selector)` check
(Python's `key`), and neither adds a custom-predicate overload. The name `allDistinct` (over `all_unique`) is a
Kotlin-consistency call, matching the existing `distinct` / `distinctBy`.

## Design decisions

Both families ship as `@ExperimentalStdlibApi` because of the two decisions below, which we most want
community feedback on before stabilization.

### Floating-point equality

`Double` and `Float` have two different equality models in Kotlin, and the two disagree on exactly the values
that matter here.

* IEEE 754, used by `==` between operands statically typed as `Double` / `Float`: `NaN == NaN` is `false`, and
  `0.0 == -0.0` is `true`.
* `equals` / collection equality, used by generic APIs and equality-based collection operations: `NaN` equals
  `NaN`, and `-0.0` does not equal `0.0`. This is the
  [documented Kotlin behavior](https://kotlinlang.org/docs/numbers.html#floating-point-numbers-comparison).

`Array<Double>` has no choice: as a generic container it compares through `equals`. `DoubleArray` could in
principle pick either model, and that is where the design question lives.

Decision: both families follow the `equals` / collection-equality model, uniformly across all overloads.

```kotlin
arrayOf(Double.NaN, Double.NaN).allEqual()        // true
doubleArrayOf(Double.NaN, Double.NaN).allEqual()  // true
arrayOf(0.0, -0.0).allEqual()                     // false
doubleArrayOf(0.0, -0.0).allEqual()               // false

arrayOf(Double.NaN, Double.NaN).allDistinct()     // false
arrayOf(0.0, -0.0).allDistinct()                  // true
```

Why this model:

1. No primitive-vs-boxed split. `DoubleArray`, `Array<Double>`, `Iterable<Double>`, and `Sequence<Double>`
   return the same answer for the same values. Picking IEEE for the primitive arrays alone would make the
   result depend on the representation.
2. It matches the equality-based APIs that already exist. `distinct()`, `toSet()`, `contentEquals`,
   `HashSet<Double>`, and `groupBy` all treat `NaN` as equal to `NaN` and `-0.0` as distinct from `0.0`.
   `allEqual` and `allDistinct` are content checks, closer to those than to a bare `a == b` between two
   numbers, so they reuse the same rules instead of inventing a new exception.
3. It matches the dominant hand-rolled idiom. The `toSet()` / `distinct()` forms above already carry equals
   semantics, so callers migrating to the new functions see no behavior change.

There is an internal precedent on the very same types.
[KTLC-192](https://youtrack.jetbrains.com/issue/KTLC-192) addressed the same `DoubleArray` / `FloatArray`
ambiguity for `contains`, `indexOf`, and `lastIndexOf`, and deprecated the IEEE behavior there because it
broke the `List` contract and the `x in array` invariant for `NaN`. Choosing IEEE for `DoubleArray.allEqual()`
would reintroduce the same primitive-vs-boxed split that KTLC-192 removed.

There is no single cross-ecosystem convention for floating-point equality in collection APIs, which is itself
part of why this ships experimental:

| Ecosystem | `NaN` equals `NaN` | `-0.0` equals `0.0` |
|---|:---:|:---:|
| Kotlin / Java (`Double.equals`, `Arrays.equals(double[])`, `Stream.distinct`) | yes | no |
| .NET / LINQ, JavaScript `Set` (SameValueZero) | yes | yes |
| Rust `itertools`, Python `more_itertools` (IEEE) | no | yes |

NumPy takes a fourth route and exposes the choice as a flag (`np.array_equal(a, b, equal_nan=...)`). Kotlin on
the JVM matches Java exactly, which is the strongest signal for the chosen model.

### No custom-predicate overload

The shipped surface is `allEqual()` / `allEqualBy { }` and `allDistinct()` / `allDistinctBy { }`, with no
custom-predicate overload such as `allEqualWith { a, b -> ... }` or `allDistinctWith { a, b -> ... }`. The case
against it rests on demand and design.

1. No demand in the field. The same survey looked for hand-rolled "all equal under a custom
   predicate" idioms like `zipWithNext().all { ... }`, `all { it === first }`, and manual loops. They turned out
   to be very rare: only a handful of genuine hits across the whole corpus, a couple of domain-specific
   structural-equality checks and a couple of referential-identity (`===`) checks, the latter both inside
   IntelliJ's Kotlin plugin. The categories one might expect, like IEEE comparison, epsilon-tolerance, or
   case-insensitive string equality, produced none. For distinctness the signal is even clearer: `distinctWith`
   does not occur at all, and not one uniqueness check uses a custom equivalence predicate. The few `allEqual`
   hits are already idiomatic without a wrapper (`xs.zipWithNext().all { ... }` / `xs.all { it === first }`).
2. The obvious custom predicate is a hazard. The most tempting predicate is an epsilon-neighborhood
   comparison, and epsilon-closeness is not an equivalence relation: $|a - b| < \varepsilon$ and
   $|b - c| < \varepsilon$ do not imply $|a - c| < \varepsilon$. Past two elements, "are all elements equal?"
   stops having a single meaning (every element close to the first? adjacent elements close? all pairs
   close?), and the answers can disagree. There is no canonical $\varepsilon$ for the standard library, and
   nothing in a `(T, T) -> Boolean` signature lets the API enforce that the predicate is actually an
   equivalence relation, so shipping the overload would invite exactly this misuse. (A caller who wants "all
   values within $\varepsilon$" should write a spread check, `(xs.max() - xs.min()) < eps`, where the
   non-transitivity is visible.)
3. Naming would fight the convention. The variant was first prototyped as `allEqualWith`. In the ordering
   APIs the `*With` suffix denotes a `Comparator`-based variant such as `sortedWith`, `maxWith`, or
   `isSortedWith`; `allEqualWith` would take an arbitrary `(T, T) -> Boolean` rather than a `Comparator`, and
   "all equal *with* X" reads almost like "all equal *to* X" instead of "all equal *under predicate* X". The
   suffix is misleading here. We did consider sidestepping the name by overloading `allEqual()` /
   `allDistinct()` themselves with a lambda, but rejected that too: the naming was only a symptom, while the
   lack of demand and the equivalence-relation hazard remain.
4. Consistency with the existing library. `distinct()` and `distinctBy()` exist; `distinctWith()` does
   not. Because the two families are designed jointly, neither gets a `*With` variant.
5. Prior art agrees. As noted above, the dedicated implementations in Rust's `itertools` and Python's
   `more-itertools` take at most a key selector, never a binary predicate.

A binary predicate would also be inefficient: with no matching `hashCode`, `allDistinctWith` could not hash
its inputs and would fall back to comparing every pair, $O(n^2)$, losing the $O(n)$ that `allDistinct` gets from a
`HashSet`.

## The API

The shape, shown for `Iterable`:

```kotlin
@SinceKotlin("2.4")
@ExperimentalStdlibApi
public fun <T> Iterable<T>.allEqual(): Boolean

@SinceKotlin("2.4")
@ExperimentalStdlibApi
public inline fun <T, K> Iterable<T>.allEqualBy(selector: (T) -> K): Boolean

@SinceKotlin("2.4")
@ExperimentalStdlibApi
public fun <T> Iterable<T>.allDistinct(): Boolean

@SinceKotlin("2.4")
@ExperimentalStdlibApi
public inline fun <T, K> Iterable<T>.allDistinctBy(selector: (T) -> K): Boolean
```

The same four functions exist for `Sequence<T>`, `Array<out T>`, the eight primitive arrays (`ByteArray`,
`ShortArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray`, `CharArray`), and the four
unsigned arrays (`UIntArray`, `ULongArray`, `UByteArray`, `UShortArray`, behind `@ExperimentalUnsignedTypes`).

Both compare with structural equality (`==`): `allEqual()` checks the first element against every other,
`allDistinct()` looks for any repeated element, following the floating-point model above. The `*By` variants
apply the same logic to the `selector` results instead of the elements.

## Reference implementation

* `allEqual` / `allEqualBy`: [PR #5941](https://github.com/JetBrains/kotlin/pull/5941).
* `allDistinct` / `allDistinctBy`: [PR #6187](https://github.com/JetBrains/kotlin/pull/6187).

## Stabilization

The decision to stabilize the API will be based on community feedback, but not earlier than Kotlin 2.6.0.

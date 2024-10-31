# Collection Literals

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**: Alejandro Serrano Mena, Denis Zharkov, Marat Akhin, Mikhail Zarechenskii, todo
* **Issue:** [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871)
* **Discussion**: todo

Collection literal is a well-known feature among modern programming languages.
It's a syntactic sugar built-in into the language that allows creating collection instances more concisely and effortlessly.
In simpliest form, if users want to create a collection, instead of writing `val x = listOf(1, 2, 3)` they could write `val x = [1, 2, 3]`.

## Table of contents

- [Motivation](#motivation)
- [Proposal](#proposal)
- [Overload resolution motivation](#overload-resolution-motivation)
  - [Operator function restrictions](#operator-function-restrictions)
  - [Overload resolution and type inference](#overload-resolution-and-type-inference)
  - [Theoretical possibility to support List vs Set overloads in the future](#theoretical-possibility-to-support-list-vs-set-overloads-in-the-future)
- [What happens if user forgets operator keyword](#what-happens-if-user-forgets-operator-keyword)
- [Similarities with @OverloadResolutionByLambdaReturnType](#similarities-with-overloadresolutionbylambdareturntype)
- [Interop with Java ecosystem](#interop-with-Java-ecosystem)
- [Similar features in other languages](#similar-features-in-other-languages)
- [IDE support](#ide-support)
- [listOf deprecation](#listof-deprecation)
- [Change to stdlib](#change-to-stdlib)
- [Future evolution](#future-evolution)
- [Rejected proposals and ideas](#rejected-proposals-and-ideas)
  - [Rejected proposal: more granular operators](#rejected-proposal-more-granular-operators)
  - [Rejected idea: built-in matrices](#rejected-idea:-built-in-matrices)
  - [Rejected idea: self-sufficient collection literals with defined type](#rejected-idea-self-sufficient-collection-literals-with-defined-type)
  - [Rejected proposal: use improved overload resolution algorithm only to refine non-empty overload candidates set on the fixated tower level](#rejected-proposal-use-improved-overload-resolution-algorithm-only-to-refine-non-empty-overload-candidates-set-on-the-fixated-tower-level)

## Motivation

1.  Marketing.
    A lot of modern languages have collection literals.
    The presence of this feature makes a good first impression on the language.
2.  Collections (not literals) are very widely used in programs.
    They deserve a separate literal.
    A special syntax for collection literal makes them instantly stand out from the rest of the program, making code easier to read.
3.  Easy migration from other languages.
    Collection literals is a widely understood concept with more or less the same syntax across different languages.
    And new users have the right to naively believe that Kotlin supports it.
4.  Avoid unnecessary intermediate vararg array allocation in `listOf`.
5.  Special syntax for collection literals helps to resolve the `emptyList`/`listOf` hussle.
    Whenever the argument list in `listOf` reduces down to zero, some might prefer to cleanup the code to change `listOf` to `emptyList`.
    And vice-versa, whenever the argument list in `emptyList` needs to grow above zero, the programmer needs to replace `emptyList` with `listOf`.
    It creates a small hussle of `listOf` to `emptyList` back and forth movement.
    It's by no means a big problem, but it is just a small annoyance, which is nice to see to be resolved by the introduction of collection literals.

The feature doesn't bring a lot of value to the existing users, and primarly targets newcomers.

## Proposal

**Definition.**
_Position with expected type_ is the position in function arguments, variable assignment, variable declaration with explict type, lambda return expression, etc.
From the implementation point of view, position with expected type is already represented in compiler sources as `ResolutionMode.WithExpectedType`.

**Definition.**
_`Type` static scope_ is the set that contains member callables (functions and properties) of `Type.Companion` type (`Type.Companion` is a companion object),
extensions of `Type.Companion`, or static members of the type if the type is declared in Java.

It's proposed to use square brackets because the syntax is already used in Kotlin for array literals inside annotation constructor arguments,
because it's the syntax a lot of programmers are already familiar with coming from other programming languages,
and because it honors mathematical notation [for matrices](#rejected-idea-built-in-matrices).

**Informally**, the proposal strives to make it possible for users to use collection literals syntax to express user-defined types `val foo: MyCustomList<Int> = [1, 2, 3]`.
And when the expected type is unspecified, expression must fallback to `kotlin.List` type: `val foo = [1, 2, 3] // List<Int>`.

**More formally**, before the collection literal could be used at the use-site, an appropriate type needs to declare `operator fun of` function in its _static scope_.
The `operator fun of` functions must adhere to [the restrictions](#todo).
[Alternative collection builder](#todo) conventions were rejected.

Once proper `operator fun of` is declared, the collection literal can be used at the use-site.
1.  When the collection literal is used in the arguments position, similarly to lambdas and callable references, collection literal affects the overload resolution of the "outer call".
    See the section dedicated to [overload resolution](#overload-resolution-motivation).
2.  When the collection literal is used in the position with definite expected type, the collection literal is literally desugared to `Type.of(expr1, expr2, expr3)`,
    where `Type` is the definite expected type.
3.  In all other cases, it's proposed to desugar collection literal to `List.of(expr1, expr2, expr3)`.

The basic example:
```kotlin
class MyCustomList<T> {
    companion object { operator fun <T> of(vararg t: T): MyCustomList<T> = TODO() }
}
fun main() {
    val foo: MyCustomList<T> = [1, 2, 3]
    val bar: MyCustomList<String> = ["foo", "bar"]
    val baz = [1, 2, 3] // List<Int>
}
```

Please note that it is not necessary for the type to extend any predifined type in the Kotlin stdlib (needed to support `kotlin.Array<T>` type),
nor it necessary for the user-defined type to declare mandatory generic type parameters (needed to support specialized arrays like `kotlin.IntArray`, `kotlin.LongArray`, etc.).

## Overload resolution motivation

The reasoning behind [the restrictions](#todo) on `operator fun of` are closely related to overload resolution and type inference.
Consider the following real-world example:
```kotlin
@JvmName("sumDouble") fun sum(set: List<Double>): Double = TODO("not implemented") // (1)
@JvmName("sumInt")    fun sum(set: List<Int>): Int = TODO("not implemented")       // (2)

fun main() {
    sum([1, 2, 3]) // Should resolve to (2)
}
```

We want to make the overload resolution work for such examples.
We have conducted an analysis to see what kinds of overloads are out there [KT-71574](https://youtrack.jetbrains.com/issue/KT-71574) (private link).
In short, there are all kinds of overloads.
The restrictions and the overload resolution algorithm suggested further will help us to make the resolution algorithm distinguish `List<Int>` vs `List<Double>` kinds of overloads.

> [!NOTE]
> When we say `List` vs `Set` kinds of overloads, we mean all sorts of overloads where the "outer" type is different.
> When we say `List<Int>` vs `List<Double>` kinds of overloads, we mean all sorts of overloads where the "inner" type is different.

-   `List<Int>` vs `Set<Int>` typically emerges when one of the overloads is the "main" overload, and another one is just a convenience overload that delegates to the "main" overload.
    Such overloads won't be supported because it's generally unknown which of the overloads is the "main" overload,
    and because collection literal syntax doesn't make it possible to syntactically distinguish `List` vs `Set`.
    But if we ever change our mind, [it's possible to support `List` vs `Set` kind of overloads](#theoretical-possibility-to-support-List-vs-Set-overloads-in-the-future) in the language in backwards compatible way in the future.
-   `List<Int>` vs `List<Double>` is self explanatory (for example, consider the `sum` example above).
    Such overloads should be and will be supported.
-   `List<Int>` vs `Set<Double>`. Both "inner" and "outer" types are different. 
    This pattern is less clear and generally much more rare.
    The pattern may emerge accidentially, when different overloads come from different packages.
    Or when users don't know about `@JvmName`, so they use different "outer" types to circumvent `CONFLICTING_JVM_DECLARATIONS`.
    This pattern will be supported just because of the general approach we are taking that distinguishes "inner" types.

### Operator function restrictions

**Definition.**
_Overloading group_ is a set of overloads located in one file or classifier.

The restrictions:
1. In the _overloading group_, one and only one `of` overload must have single `vararg` parameter.
2. All `of` overloads must have the return type equal by `ClassId` to the type in whom _static scope_ the overload is declared in.
3. All `of` overloads must be either top-level extension on `Companion`, be declared as companion object members and have zero extension receivers, or be a static function declared in Java.
4. All `of` overloads must have zero context parameters.
5. All `of` overloads must be more specific than the overload with single `vararg` parameter.

If the `of` function is marked with the `operator` keyword, then the compiler should check and enforce all the restrictions.
All `of` overloads must be marked with `operator` keyword,
except for Java, because Kotlin compiler cannot issue diagnostics in Java code.

```kotlin
// Examples of valid declarations
    class Good1<T> { companion object {
        operator fun <T> of(vararg t: T) = Good1<Int>()
        operator fun of(a: String, b: Int) = Good1<String>()
    }}

    class Good2<T> { companion object }
    operator fun <T> Good2.Companion.of(vararg t: T) = Good2<Int>()

    class Good3<T> { companion object {
        operator fun <T> of(vararg t: T) = Good3<Int>()
    }}
    operator fun Good3.Companion.of(vararg t: String) = Good3<String>()

    class Good4<K, V> { companion object {
        operator fun <K, V> of(vararg t: Pair<K, V>) = Good4<K, V>()
    }}

// Examples of invalid declarations
    class Bad1<T> { companion object {
        operator fun <T> of(vararg t: T): Int = 1 // Invalid return type
    }}

    class Bad2<T> { companion object {
        operator fun of(t: String, t: Int): Bad2<String> = Bad2() // vararg overload is not present
    }}

    class Bad3<T> { companion object {
        operator fun <T> of(vararg t: T): Int = 1 // Invalid return type
        operator fun of(t: String, t: Int): Bad3<String> = Bad3()
    }}

    class Bad4 { companion object {
        operator fun Bad4.Companion.of(vararg t: Int) = Bad4() // Rule 3 is violated
    }}

    class Bad5<T> { companion object {
        operator fun of(vararg t: Int): Bad5<Int> = Bad5() // too many vararg overloads in one overloading group
        operator fun of(vararg t: String): Bad5<String> = Bad5()
    }}

    class Bad6<T> { companion object {
        operator fun of(vararg t: Int): Bad6<T> = Bad6<T>()
        operator fun of(t: String): Bad6<T> = Bad6<T>() // Violation. This overload must be 
    }}
```

The motivation behind restriction 1 is to make // todo

The motivation behind restriction 5 is to avoid cases like this:
```kotlin
class Array { companion object {
    operator fun of(vararg t: Int) = Array()
    operator fun of(t: String) = Array()
}}

fun foo(a: Array) = Unit // (1)
fun foo(a: List<String>) = Unit // (2)

fun main() {
    val s: Array = [""] // green
    foo(s) // (1)
    foo([""]) // (2)
}
```

### Overload resolution and type inference

> Readers of this section are encouraged to familiarize themselves with how [type inference](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md) and
> [overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution) already work in Kotlin.

All the problems with overload resolution and collection literals come down to examples when collection literals are used at the argument position.
```kotlin
fun <K> materialize(): K = null!!
fun outerCall(a: List<Int>) = Unit
fun outerCall(a: Set<Double>) = Unit

fun test() {
    outerCall([1, 2, materialize()])
}
```

On the high-level, overload resolution algorithm is very simple and logical.
There are two stages:
1.  Filter out all the overload candidates that certainly don't fit based on types of the arguments.
    Only types of non-lambda and non-reference arguments are considered.
    (it's important to understand that we don't keep the candidates that fit, but we filter out those that don't)
    https://kotlinlang.org/spec/overload-resolution.html#determining-function-applicability-for-a-specific-call
2.  Of the remaining candidates, we keep the most specific ones by comparing every two distinct overload candidates.
    https://kotlinlang.org/spec/overload-resolution.html#choosing-the-most-specific-candidate-from-the-overload-candidate-set

For our case to distinguish `List<Int>` vs `List<Double>` kinds of overloads, the first stage of overload resolution is the most appropriate one.
That's exactly what we want to do - to filter out definitely inapplicable `outerCall` overloads.

Given the following example: `outerCall([expr1, [expr2], expr3, { a: Int -> }, ::x], expr4, expr5)`,
similar to lambdas and callable references, collection literal expression type inference is delayed.
In turn, elements of the collection literal are analyzed in the way similar to how other arguments of `outerCall` are analyzed, which means:
1.  If collection literal elements are lambdas, or callable references their analysis is delayed.
    Only number of lambda parameters and lambda parameter types (if specified) are taken into account of overload resolution of `outerCall`.
2.  If collection literal elements are collection literals themselves, then we descend into those literals and recursively apply the same rules.
3.  All other collection literal elements are "plain arguments", and they are analyzed in [so-called "dependent" mode](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md).

Once all "plain" arguments are analyzed (their types are infered in "dependent" mode), and all recursive "plain" elements of collection literals are analyzed,
we proceed to filtering overload candidates for `outerCall`.

For every overload candidate, when a collection literal maps to its appropriate `ParameterType`:
1.  We resolve `ParameterType.of(vararg)` according to regular Kotlin operator convention resolution rules.
    What that means is that, in the resolution tower, we find the "closest" operator `of` with single `vararg` parameter.
    Please note that according to operator convention resolution rules, declarations with `operator` keyword win over declarations without the keyword (operator extensions win over non-operator members).
    In all other cases, members win over extensions.
2.  We remember the parameter of the single `vararg` parameter.
    We will call it _CLET_ (collection literal element type).
    We also remember the return type of that `ParameterType.of(vararg)` function.
    We will call it _CLT_ (collection literal type).
3.  In the first stage of overload resolution of `outerCall`, we add the following constraints to the constraint system of `outerCall` candidate:
    1. For each collection literal element `e`, we add `type(e) <: CLET` constraint.
    2. We also add the following constraint: `CLT <: ParameterType`.

Please note that constraints described above are only added to the constraint system of `outerCall` and not to constraint system of `of` function themselves.
(overload resolution for which will be performed later)

Example:
```kotlin
operator fun <T> List.Companion.of(vararg t: T): List<T> = TODO("not implemented")
operator fun <T> Set.Companion.of(vararg t: T): Set<T> = TODO("not implemented")

fun <K> materialize(): K = null!!
fun outerCall(a: List<Int>) = Unit // (1)
fun outerCall(a: Set<Double>) = Unit // (2)

fun test() {
    // The initial constraint system for candidate (1) looks like:
    //                type(1) <: T
    //                type(2) <: T
    // type(materialize<K>()) <: T
    //                List<T> <: List<Int>
    // The constraint system is sound => the candidate is not filtered out

    // The initial constraint system for candidate (2) looks like:
    //                type(1) <: T
    //                type(2) <: T
    // type(materialize<K>()) <: T
    //                List<T> <: Set<Double>
    // The constraint system is unsound => the candidate is filtered out
    outerCall([1, 2, materialize()]) // We resolve to the single candidate (1)
}
```

It's important to understand that until we pick the `outerCall` overload, we don't start the full-blown overload resolution process for nested `of` calls.
If we did so, it'd lead to exponential complexity of `outerCall` overload resolution.
Consdier the `outerCall([[[1, 2, 3]]])` example.

Once the particular overload for `outerCall` is choosen, we know what definite expected type collection literal maps to.
We desugar collection literal to `DefiniteExpectedType.of(expr1, expr2, expr3)`, and we proceed resolving overloads of `DefiniteExpectedType.of` according to regular Kotlin overload resolution rules.
The `DefiniteExpectedType.of` function itself is resolved according regular Kotlin _operator convention_ rules (e.g. we only consider those functions that are marked with `operator` keyword).

Now the idea behind `of` functions restrictions should be clear.
The idea is to make it possible to treat the `vararg` overload as "leading"/"fallback" overload that we can always use for overload resolution for `outerCall` without paying the cost of performing overload resolution for `of` function.
And the cost for full-blown overload resolution for nested `of` calls is big, as it's been said, it's exponential.

### Theoretical possibility to support List vs Set overloads in the future

We don't plan to, but if we ever change our mind, it's possible to support `List` vs `Set` kinds of overloads in the way similar to how Kotlin prefers `Int` overload over `Long` overload:
```kotlin
fun foo(a: Int) = Unit // (1)
fun foo(a: Long) = Unit // (2)

fun test() {
    foo(1) // (1)
}
```

On the second stage of overload resolution, `Int` is considered more specific than `Long`, `Short`, `Byte`.
In the similar way, `List` can be theoretically made more specific than any other type that can represent collection literals.

But right now, we **don't plan** to do that, since both `List` and `Set` overloads can equally represent the "main" overload.

## Similarities with @OverloadResolutionByLambdaReturnType

todo

## Interop with Java ecosystem

## Similar features in other languages

## IDE support

The IDE should implement an inspection to replace `listOf`/`setOf`/etc. with collection literals where it doesn't lead to overload resolution ambiguity.
It's under the question if the inspection for for `listOf`/`setOf` should be enabled by default. (most probably not)

The IDE should implement an inspection to replace explict use of operator function `Type.of` with collection literals where it doesn't lead to overload resolution ambiguity.
The inspection should be enabled by default.

todo

## listOf deprecation

We **don't** plan to deprecate any of the `smthOf` functions in Kotlin stdlib.
They are too much widespread, even some 3rd party Kotlin libraries follow `smthOf` pattern.

Besides, this proposal doesn't allow to eliminate `smthOf` pattern completely.
`listOfNotNull` isn't going anywhere.

Unfortunatelly, introduction of `List.of` functions in stdlib makes the situation harder for newcomers.

For the reference, here is the full list of `smthOf` functions that we managed to find in stdlib:

List like
- `listOf()`
- `listOfNotNull()`
- `setOfNotNull()`
- `mutableListOf()`
- `sequenceOf()`
- `arrayListOf()`
- `arrayOf()`
- `intArrayOf()`, `shortArrayOf()`, `byteArrayOf()`, `longArrayOf()`, `ushortArrayOf()` // etc
- `setOf()`
- `mutableSetOf()`
- `hashSetOf()`
- `linkedSetOf()`
- `sortedSetOf()`

Map like
- `mapOf()`
- `mutableMapOf()`
- `hashMapOf()`
- `sortedMapOf()`

Other collections
- `arrayOfNulls()`
- `arrayOf().copyOf()` // vs spread in collection literals?
- `mutableListOf<String>().copyOf()` // red code. We don't have stdlib function to copy list in Kotlin

Others, not collections, but things that could be literals of their own
- `lazyOf()`
- `typeOf<String>()` // Defined in kotlin-reflect. returns KType
- `enumValueOf<E>(String)`
- `CharDirectionality.valueOf(Int)` // Int to Enum entry
- `java.lang.Boolean.valueOf(true)` // Java
- `java.lang.Byte.valueOf("")` // Java
- `java.math.BigInteger.valueOf(1l)` // Java

## Change to stdlib

The following API change is proposed to stdlib:
```diff
diff --git a/libraries/stdlib/src/kotlin/Collections.kt b/libraries/stdlib/src/kotlin/Collections.kt
index e692a8c05ede..7509cc55b9c6 100644
--- a/libraries/stdlib/src/kotlin/Collections.kt
+++ b/libraries/stdlib/src/kotlin/Collections.kt
@@ -175,6 +175,13 @@ public expect interface List<out E> : Collection<E> {
      * Structural changes in the base list make the behavior of the view undefined.
      */
     public fun subList(fromIndex: Int, toIndex: Int): List<E>
+
+    public companion object {
+        public operator fun <T> of(vararg t: T): List<T>
+        public operator fun <T> of(t1: T, t2: T): List<T>
+        public operator fun <T> of(t1: T, t2: T, t3: T): List<T>
+        // 10 overloads?
+    }
 }

 /**
```

The analogical API changes are proposed to the following classes:
- `kotlin.collections.ArrayList`
- `kotlin.collections.MutableList`
- `kotlin.collections.Set`
- `kotlin.collections.HashSet`
- `kotlin.collections.LinkedHashSet`
- `kotlin.collections.MutableSet`
- `kotlin.sequences.Sequence`
- `kotlin.Array`, `kotlin.IntArray`, `kotlin.LongArray`, `kotlin.ShortArray`, `kotlin.UIntArray` etc.

Please note that on JVM, `kotlin.collections.List` is a mapped type.
Which means that we cannot change the runtime `java.util.List`.
It's suggested to do mapped `companion object` in the way similar to `Int.Companion` (which maps to `kotlin.jvm.internal.IntCompanionObject`).

**Observations**

Kotlin's `listOf` always returns immutable implementations:
-   `kotlin.collections.EmptyList` when there are zero elements
-   `java.util.Collections$SingletonList` when there is single element
-   `java.util.Arrays$ArrayList` when there is more than 1 element.
    NB! don't confuse with `java.util.ArrayList`.
    Contrary to `java.util.ArrayList`, `Arrays$ArrayList` doesn't allow list modifications.

Java's `List.of` always returns immutable implementations:
-   `java.util.ImmutableCollections$ListN` when there are zero elements.
-   Specialized `java.util.ImmutableCollections$List12` when there is single element.
-   Specialized `java.util.ImmutableCollections$List12` when there are two element.
-   `java.util.ImmutableCollections$ListN` when there more than 1 element.

As for what particular implementation that should be returned.
Conceptually, both `listOf` implementations are immutable and `List.of` implementations are immutable, which is a good thing.
The big showstopper is that `List.of` doesn't accept `null` arguments.
We leave the question of particular implementation to the developers of Kotlin stdlib.

## Future evolution

In future, Kotlin may provide `@VarargOverloads` (similar to `@JvmOverloads`), or `inline vararg` to eliminate unnecessary array allocations even further.

## Rejected proposals and ideas

This section lists some common proposals and ideas that were rejected

### Rejected proposal: more granular operators

In [the proposal](#proposal), we give users possibility to add overloads for the `vararg` operator.
It's clear that we do so to avoid unnecessary array allocations at least for most popular cases when collection sizes are small.
What if instead of a single operator, collections had to declare 3 operators: `createCollectionBuilder`, `plusAssign`, `freeze` (we even already have the `plusAssign` operator!)?

Please note, that we need the third `freeze` operator to make it possible to create collection literals for immutable collections.

Then the use-site could be desugared by the compiler:
```kotlin
fun main() {
    val s = [1, 2, 3]
    // desugared to
    val s = run {
        val tmp = createCollectionBuilder<Int>()
        tmp.plusAssign(1)
        tmp.plusAssign(2)
        tmp.plusAssign(2)
        List.freeze(tmp)
    }
}
```

The declaration site looks like:
```kotlin
class List<T> {
    companion object {
        operator fun createCollectionBuilder<T>(capacity: Int): MutableList<T> = ArrayList(capacity)
        operator fun <T> freeze(a: MutableList<T>): List<T> = a.toList()
    }
}

class MutableList<T> {
    fun add(t: T): Boolean = TODO("")

    companion object {
        operator fun createCollectionBuilder<T>(capacity: Int): MutableList<T> = ArrayList(capacity)
        operator fun <T> freeze(a: MutableList<T>): MutableList<T> = this
    }
}

operator fun <T> MutableList<T>.plusAssign(t: T) { add(t) }
```

The main problem with this approach is that the type of the collection builder is exposed in public API.
The type of the builder is an implementation detail, and users might want to change it over time.
Users may also want to use different builder types depending on the number of elements in the collection literal.

### Rejected idea: built-in matrices

todo

### Rejected idea: self-sufficient collection literals with defined type

The type of collection literals is infered from the expected type.
Unfortunatelly, that may lead to overload resolution ambiguity when collection literal is used in argument position.

```kotlin
fun foo(a: Set<Int>) = Unit
fun foo(a: List<Int>) = Unit

fun test() {
    foo([1, 2]) // overload resolution ambiguity
    // but what if...
    foo(List [1, 2])
}
```

The rejected proposal was to allow writing `List [1, 2]`.
The proposal was rejected because users can anyway desugar `[1, 2]` to `List.of(1, 2)` or `listOf(1, 2)`.

### Rejected proposal: use improved overload resolution algorithm only to refine non-empty overload candidates set on the fixated tower level

**Fact.** `foo` in the following code resolves to (2). Although (1) is a more specific overload, (2) is prefered because it's more local.

```kotlin
fun foo(a: Int) {} // (1)
class Foo {
    fun foo(a: Any) {} // (2)
    fun test() {
        foo(1) // Resolves to (2)
    }
}
```

It was proposed to use improved overload resolution algorithm for collection literals to refine already non-empty overload candidates set to avoid resolving to functions which are "too far away".

```kotlin
fun foo(a: List<Int>) {} // (1)
class Foo {
    fun foo(a: List<String>) {} // (2)
    fun test() {
        foo([1]) // It's proposed to resolve to (2) and fail with TYPE_MISMATCH error
    }
}
```

The proposal was rejected because the analogical "improved overload resolution for references" already allows resolving to functions which are "too far away".

```kotlin
fun x(a: Int) = Unit
fun foo(a: (Int) -> Unit) {} // (1)
class Foo {
    fun foo(a: (String) -> Unit) {} // (2)
    fun test() {
        foo(::x) // Resolves to (1)
    }
}
```

We prefer to keep the language concistent rather than "fixing" the behavior, even if the suggested behavior would be generally better

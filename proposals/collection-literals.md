# Collection Literals

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**: Alejandro Serrano Mena, Denis Zharkov, Marat Akhin, Mikhail Zarechenskii, todo
* **Issue:** [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871)
* **Discussion**: todo

**Precondition:** the text below is written with the assumption that Kotlin already implemented ["Improved resolution by expected type" feature](./proposals/improved-resolution-expected-type.md).
And the feature uses `_.foo`/`_.foo(arg1, arg2)` syntax to access `foo` callables in the static scope.

Collection literal is a well-known feature among modern programming languages.
It's a syntactic sugar built-in into the language that allows creating collection instances more concisely and effortlessly.
In simpliest form, if users want to create a collection, instead of writing `val x = listOf(1, 2, 3)` they could write `val x = [1, 2, 3]`.

## Table of contents

todo

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
From the implementation point of view, position with expected type is already represented in compiler sources as `ResolutionMode.ContextIndependent`.

**Definition.**
_Type static scope_ is the set that contains callables (functions and properties) declared inside the `Type.Companion` nested `companion object`,
as an extensions for `Type.Companion`, or static members of the type in Java.
Once statics feature comes to Kotlin, it becomes part of the _type static scope_.

**Definition.**
_Overloading group_ is a set of overloads located in one package or classifier.

It's proposed to use square brackets because the syntax is already used in Kotlin for array literals inside annotation constructor arguments,
because it's the syntax a lot of programmers are already familiar with coming from other programming languages,
and because it honors mathematical notation [for matrices](#rejected-idea-built-in-matrices).

**Informally**, the proposal strives to make it possible for users to use collection literals syntax to express user-defined types `val foo: MyCustomList<Int> = [1, 2, 3]`.
And when the expected type is unspecified, expression must fallback to `kotlin.List` type: `val foo = [1, 2, 3] // List<Int>`.

**More formally**, before the collection literal could be used at the use-site, an appropriate type needs to declare `operator fun of` function in its _static scope_.
The `operator fun of` functions must adhere to [the restrictions](#todo).
[Alternative collection builder](#todo) conventions were rejected.

Once proper `operator fun of` is declared, the collection literal can be used at the use-site.
1. When the collection literal is used in the _position with expected type_, it's proposed to literally desugar collection literal `[expr1, expr2, expr3]` to `_.of(expr1, expr2, expr3)`.
2. In all other cases, it's proposed to desugar collection literal to `List.of(expr1, expr2, expr3)`.

The fact that collection literals are just merely a syntactic sugar for another Kotlin feature is a good thing, because it makes the language more transparent for users.
It makes the language easier to learn since the number of concepts reduces.
Kotlin already has features that are merely a syntactic sugar for other more basic building blocks.
Like `x++` is desugared to `x = x.inc()`, it's not a new thing.

The basic example:
```kotlin
class MyCustomList<T> {
    companion object { operator fun <T> of(vararg t: T): MyCustomList<T> = TODO() }
}
fun main() {
    val foo: MyCustomList<T> = [1, 2, 3]
    val foo: MyCustomList<String> = ["foo", "bar"]
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
We have conducted an analysis to see what kinds of overloads are out there [KT-71574](https://youtrack.jetbrains.com/issue/KT-71574). (private link)
In short, there are all kinds of overloads, there are `List<Int>` vs `Set<Int>` kinds of overloads, there are `List<String>` vs `List<Path>` kinds of overloads, and even `List<Int>` vs `Set<Double>`.

> [!NOTE]
> When we say `List` vs `Set` kinds of overloads we mean all sorts of overloads where the "outer" type is different.
> When we say `List<Int>` vs `List<Double>` kinds of overloads we mean all sorts of overloads where the "inner" type is different.

-   `List<Int>` vs `Set<Int>` typically emerges when when one of the overloads is the "main" overload, and another one is just a convenience overload that delegates to the "main" overload.
-   `List<Int>` vs `List<Double>` is self explanatory. (for example, consider the `sum` example above)
-   `List<Int>` vs `Set<Double>`. This pattern is less clear and generally much more rare.
    The pattern may emerge accidentially, when different overloads come from different packages.
    Or when users don't know about `@JvmName`, so they use different types to circumvent `CONFLICTING_JVM_DECLARATIONS`.
    We don't target to support it, but as you will see it will be supported just because of the general approach that we are taking.

The restrictions and the overload resolution algorithm suggested further will help us to make the resolution algorithm distinguish `List<Int>` vs `List<Double>` overloads.
`List<Int>` vs `Set<Int>` won't be supported because it's generally unknown which of the overloads is the "main" overload,
and because collection literal syntax doesn't make it possible to syntactically distinguish `List` vs `Set`.
But if we ever change our mind, [it's possible to support `List` vs `Set` kind of overloads](#todo) in the language in backwards compatible way in the future.

### Operator function restrictions

The restrictions:
1. In the _overloading group_, one and only one `of` overload must have single `vararg` parameter.
2. All `of` overloads must have the return type equal by `ClassId` to the type in whom _static scope_ the overload is declared in.
3. All `of` overloads must be either extension on `Companion`, be declared inside the companion and have zero extension receivers, or be a static function declared in Java.
4. All `of` overloads must have zero context parameters.

If the `of` function is marked with the `operator` keyword, then the compiler should check and enforce all the restrictions
(except for Java, because Kotlin compiler cannot issue diagnostics in Java code).

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
        operator fun Bad4.Companion.of() = Bad4() // Rule 3 is violated
    }}

    class Bad5<T> { companion object {
        operator fun of(vararg t: Int): Bad5<Int> = Bad5() // too many vararg overloads in one overloading group
        operator fun of(vararg t: String): Bad5<String> = Bad5()
    }}
```

### CLET and CLT

**Definition.**
For the given use-site `x`,
A _collection literal element type_ (_CLET_ in short) of type `A` is the type of the single `vararg` parameter of the `A.of`/`A.Companion.of` function (see the restriction 1) available at the use-site `x`.
If there are none or multiple `A.Companion.of` functions available with single `vararg` parameter, (those overloads must be coming from different _overloading groups_)
then we say that _CLET_ is undefined.

**Definition.**
For the given use-site `x`,
A _collection literal type_ (_CLT_ in short) of type `A` is the return type of of `A.of`/`A.Companion.of` overload with the single `vararg` parameter (see the restriction 1) available at the use-site `x`.
If there are none or multiple `A.Companion.of` functions available with single `vararg` parameter, (those overloads must be coming from different _overloading groups_)
then we say that _CLT_ is undefined.

**Informally**, CLET is the type of `vararg` parameter (NB! don't confuse parameters and arguments), and CLT is the return type of the appropriate `of` function.

Examples:
```kotlin
// File: a.kt
package a
class Array<T> {
    companion object { operator fun <T> of(vararg t: T) = Array<T>() }
}
fun test1() {
    val s: Array<Int> = [1, 2] // At this use-site, CLET of `Array` is generic parameter `T`. CLT of `Array` is `Array<T>`
}

// File: b.kt
package b
import a.*
operator fun Array.Companion.of(vararg t: Int) = Array<Int>()
fun test2() {
    val s: Array<Int> = [1, 2] // At this use-site, CLET and CLT of `Array` is undefined (because there are multiple `of` overloads with single `vararg` parameter.)
}

// File: c.kt
package c
class IntArray {
    companion object { operator fun of(vararg t: Int) = IntArray() }
}
fun test3() {
    val s: IntArray = [1, 2] // At this use-site, CLET of IntArray is Int, CLT of IntArray is IntArray
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
    outerCall(_.of(1, 2, materialize()))
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

Before performing any overload resolution, it's proposed to literally desugar the collection literal syntax with square brackets `[1, 2, 3]` to `_.of(1, 2, 3)`.
We do so to make sure that collection literal was really just a syntactic sugar for `_.of`.

For our case to distinguish `List<Int>` vs `List<Double>` kinds of overloads, the first stage of overload resolution is the most appropriate one.
That's exactly what we want to do - to filter out definitely inapplicable `outerCall` overloads.
Similar to how lambdas and callable references receive a special treatment on the first stage of overload resolution,
it's proposed to give a _special treatment_ for `_.of` syntactic construct in the arguments position.

During the first stage of overload resolution for the `outerCall`,
when `_.of` appears in the argument position, we will collect all `ParameterType.of`/`ParameterType.Companion.of` functions available at the use-site.
(`ParameterType` is the type of the parameter that maps to `_.of` argument)
1.  If any of the collected `of` functions are not marked with `operator` keyword and don't come from Java, then `_.of` doesn't receive any _special treatment_.
2.  If [_CLET_](#clet-and-clt) of type `ParameterType` is undefined, then `_.of` doesn't receive any _special treatment_.
3.  If the two conditions above are satisfied, then we will run the type inference for collection literal elements in so-called "dependent" mode.
    (see [type inference](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md))
    After infering types for collection literal elements, we add the following constraints to the constraint system of `outerCall` candidate:
    1. For each collection literal element `e`, we add `type(e) <: CLET(ParameterType)` constraint.
    2. We also add the following constraint: `CLT(ParameterType) <: ParameterType`.

Please note that constraints described above are only added to the constraint system of `outerCall` and not to constraint system of `_.of` functions themselves.
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
    outerCall([1, 2, materialize()])
}
```

It's important to understand that until we pick the `outerCall` overload, we don't start the full-blown overload resolution process for nested `_.of` calls.
If we did so, it'd lead to exponential complexity of `outerCall` overload resolution.
Consdier the `outerCall(_.of(_.of(_.of(1, 2, 3))))` example.

Once the particular overload for `outerCall` is choosen, we proceed with resolving `_.of` arguments according to regular resolution rules for `_.of` literals.
Thanks to already fixated `outerCall` overload, when, at argument position, we resolve `_.of` literal, we already know the exact expected type for the `_.of` literal.

> One may argue that it feels wrong to treat `_.of` literal in a special way just because of the name.
> This question is understandable.

### Theoretical possibility to support List vs Set overloads in the future

todo

## Similarities with @OverloadResolutionByLambdaReturnType

todo


## Rejected proposals and ideas

todo

### Rejected idea: built-in matrices

todo

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

It was proposed to use improved overload resolution algorithm for `_.of` literal to refine already non-empty overload candidates set to avoid resolving to functions which are "too far away".

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

We prefer to keep the language concistent rather than "fixing" the behavior, even if the suggested behavior would be better in general.

## Interop with Java ecosystem

## Similar features in other languages


## List.of implementation

Kotlin's `listOf` returns:
- `kotlin.collections.EmptyList` when there are zero elements
- `java.util.Collections$SingletonList` when there is single element
- `java.util.Arrays$ArrayList` (NB! don't confuse with `java.util.ArrayList`) when there is more than 1 element

Java's `List.of` returns:
- `java.util.ImmutableCollections$ListN` when there are zero elements

## IDE support

IDE should suggest to replace `_.of(expr1, expr2)` to `[expr1, expr2]`.







```kotlin
public interface Sequence<out T> {
    public operator fun iterator(): Iterator<T>
}

public interface List<out T> {}
public interface MutableList<T> : List<T> {}
```

## Overload resolution for collection literals








What if collection is only constructable with a builder

3 operators:
1. Create the builder
2. Append elements
3. Invoke ".build()"

Observation: nobody cares about `vararg` in `listOf`

Providing 3 functions seems like an overkill




My proposal restricts `.whatever` literals.

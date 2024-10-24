# Collection Literals

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**: Alejandro Serrano Mena, Marat Akhin, Mikhail Zarechenskii, todo
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
_Type static scope_ is the set that contains callables (functions and properties) declared inside the `Type.Companion` nested `companion object`, or declared as extensions for `Type.Companion`.
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
1. One and only one overload in the _overloading group_ must have single `vararg` parameter.
2. All overloads must have the return type equal by `ClassId` to the type in whom _static scope_ the overload is declared in.
3. All `of` overloads must be either extension on `Companion` of the target type, or be declared inside the companion and have zero extension receivers.
4. The overloads must have zero context parameters.

If the `of` function is marked with the `operator` keyword then the compiler should check and enforce all the restrictions.

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

**Definition.** 
For the given use-site `x`,
A _collection literal element type_ of type `A` is the type of the single `vararg` parameter of the `A.Companion.of` function (see the restriction 1) available at the use-site `x`.
If there are none or multiple `A.Companion.of` functions available with single `vararg` parameter (those overloads must be coming from different _overloading groups_)
then we say that _collection literal element type_ is undefined.

Examples:
```kotlin
// File: a.kt
package a
class Array<T> {
    companion object { operator fun <T> of(vararg t: T) = Array<T>() }
}
fun test1() {
    val s: Array<Int> = [1, 2] // At this use-site, collection literal element type of Array is generic parameter T
}

// File: b.kt
package b
import a.*
operator fun Array.Companion.of(vararg t: Int) = Array<Int>()
fun test2() {
    val s: Array<Int> = [1, 2] // At this use-site, collection literal element type of Array is undefined (because there are multiple `of` overloads with single `vararg` parameter.)
}

// File: c.kt
package c
class IntArray {
    companion object { operator fun of(vararg t: Int) = IntArray() }
}
fun test3() {
    val s: IntArray = [1, 2] // At this use-site, collection literal element type of IntArray is Int
}
```

### Overload resolution and type inference

> Readers of this section are encouraged to familiarize themselves with how [type inference](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md) and 
> [overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution) already work in Kotlin.

On the top-level, overload resolution algorithm is very simple and logical.
There are two stages:
1.  Filter out all the overload candidates that certainly don't fit based on types of the arguments.
    Only types of non-lambda and non-reference arguments are considered.
    (it's important to understand that we don't keep the candidates that fit, but we filter out those that don't)
    https://kotlinlang.org/spec/overload-resolution.html#determining-function-applicability-for-a-specific-call
2.  Of the remaining candidates, we keep the most specific ones by comparing every two distinct overload candidates.
    https://kotlinlang.org/spec/overload-resolution.html#choosing-the-most-specific-candidate-from-the-overload-candidate-set

All the problems with overload resolution and collection literals come down to examples when collection literals are used at the argument position.
```kotlin
fun <T> materialize(): T = null!!
fun outerCall(a: List<Int>) = Unit
fun outerCall(a: Set<Double>) = Unit

fun test() {
    outerCall([1, 2, materialize()])
}
```

Similar to how lambdas and callable references receive a special treatment on the first stage of overload resolution,
it's proposed to give a special treatment for `_.of` syntactic construct.











Since the overload needs to be placed in the _static scope_ of the target class, the overload can either have 


the following shape: `operator fun of(vararg t: <INPUT_TYPE>): <RETURN_TYPE>`.
The overload must have single `vararg` parameter.
The overload may have as many type parameters as needed.
The overload is allowed to be declared in 



1.  It's allowed to provide several `of` overloads.
2.  One of the `of` overloads must accept single `vararg` of arguments.
3.  All other overloads are allowed to differ from the `vararg` overload only by number of parameters, and parameter names.
    Everything else must be the same (type parameters, return types, parameter types)
4.  Types of all parameters across all `of` overloads must be the same.
    We will call this specific type `T` a **collection element type** of `MyCustomList`.


One and only one overload in the _overloading group_ must have the following shape: `operator fun of(vararg t: <INPUT_TYPE>): <RETURN_TYPE>`.
The overload must have single `vararg` parameter.
The overload may have as many type parameters as needed.

Examples:


```kotlin
class MyCustomList {
    companion object {
        operator fun <T> of(vararg t: T): MyCustomList = TODO("")
        operator fun of(a: Int) = ""
    }
}

fun foo(a: MyCustomList) = Unit
fun foo(a: String) = Unit

fun main() {
    foo(_.of(1)) // Argument type mismatch: expected 'MyCustomList' actual 
    foo([1]) // Argument type mismatch: expected ''
}
```


```kotlin
// Valid operator `of` declaration
class MyCustomList {
    companion object {
        operator fun <T> of(t: T): MyCustomList = TODO("")
        operator fun <T> of(t1: T, t2: T): MyCustomList = TODO("")
        operator fun <T> of(vararg t: T): MyCustomList = TODO("")
    }
}

// Invalid operator `of` declaration
class MyCustomList {
    companion object {
        operator fun <T> of(t: Int, t: String): MyCustomList = TODO("")
    }
}
```


## Similarities with @OverloadResolutionByLambdaReturnType

todo

## Theoretical possibility to support List vs Set overloads

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

We prefer to keep the language concistent in such small details rather than "fixing" the behavior, even if the suggested behavior would be better in general.





















The invariants






The complete example:

```kotlin
class MyCustomList {
    companion object {
        operator fun <T> of(vararg t: T): MyCustomList<T> = TODO()
    }
}
fun main() {
    val foo 
}
```

todo: dilemma. is it ok for `operator` keyword to affect overload resolution? I think yes, it's ok.

## Interop with Java ecosystem

## Similar features in other languages


## What implementation to use in List.of

todo
Should it return EmptyList when args are 0, should it return ArrayList or ImmutableList?

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

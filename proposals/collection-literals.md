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
    A lot of modern languages have this feature.
    The presence of this feature makes a good first impression on the language.
2.  Collections (not literals) are very widely used in programs.
    They deserve a separate literal.
    A special syntax for collection literal makes them instantly stand out from the rest of the program, making code easier to read.
3.  Easy migration from other languages.
    Collection literals is a widely understood concept with more or less the same syntax across different languages.
    And new users have the right to naively believe that Kotlin supports it.
4.  Avoid unnecessary intermediate vararg array allocation in `listOf`.
5.  Resolve the `emptyList`/`listOf` hussle.
    Whenever the argument list in `listOf` reduces down to zero, some might prefer to cleanup the code to change `listOf` to `emptyList`.
    And vice-versa, whenever the argument list in `emptyList` needs to grow above zero, the programmer needs to replace `emptyList` with `listOf`
    It creates a small hussle of `listOf` to `emptyList` back and forth movement.
    It's by no means a big problem, but it is just a small annoyance, which is nice to see to be resolved by introduction of collection literals.

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
and because it honors mathematical notation [for matrices](#rejected-proposal-built-in-matrices).

**Informally**, the proposal strives to make it possible for users to use collection literals syntax to express user-defined types `val foo: MyCustomList<Int> = [1, 2, 3]`.
And when the expected type is unspecified, expression must fallback to `kotlin.List` type: `val foo = [1, 2, 3] // List<Int>`.

**More formally**, before the collection literal could be used at the use-site, an appropriate type needs to declare `operator fun of` function in its _static scope_.
The `operator fun of` functions must adhere to the following restrictions:
1. One and only one overload in the _overloading group_ must have single `vararg` parameter.
2. All overloads must have the return type equal by `ClassId` to the type in whom static scope the overload is declared in.
3. All `of` overloads must be either extension on `Companion` of the target type, or be declared inside the companion and have zero extension receivers.
4. The overloads must have zero context parameters

The logic behind the restrictions and what happens if they are violated are described [further](#overload-resolution-and-operator-function-restrictions).

Once `operator fun of` is declared, the collection literal can be used at the use-site.
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

Please notice that it is not necessaty for the type to extend any predifined type in the Kotlin stdlib (necessary for `kotlin.Array<T>` type),
nor it necessaty for the user-defined type to declare mandatory generic type parameters (necessary for specialized arrays like `kotlin.IntArray`, `kotlin.LongArray`, etc.).

## Overload resolution and operator function restrictions

Readers of this section are encouraged to familiarize themselves with how [type inference](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md) and 
[overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution) work in Kotlin.

The reasoning behind the restrictions on `operator fun of`, introduced in the [proposal](#proposal) section, are closely related to overload resolution and type inference.
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
In short, there are all kinds of overloads, there are `List<Int>` vs `Set<Int>` kinds of overloads, there are `List<String>` vs `List<Path>` kinds of overloads, and even `List<Int>` vs `Set<Double>`.

-   `List<Int>` vs `Set<Int>` typically emerges when 
-   `List<Int>` vs `List<Double>` is self explanatory. (for example, consider the `sum` example above)
-   `List<Int>` vs `Set<Double>`. This pattern is less clear and generally much more rare. 
    The pattern may emerge accidentially, when different overloads come from different packages.
    Or when users don't know about `@JvmName`, so they use different types to circumvent `CONFLICTING_JVM_DECLARATIONS`.
    We don't target to support it, but as you will see it will be supported just because of the general approach that we are taking.

The restrictions and the overload resolution algorithm suggested further will help us to make the resolution algorithm distinguish `List<Int>` vs `List<Double>` overloads.

Let's recall the restrictions.
1. One and only one overload in the _overloading group_ must have single `vararg` parameter.
2. All overloads must have the return type equal by `ClassId` to the type in whom static scope the overload is declared in.
3. All `of` overloads must be either extension on `Companion` of the target type, or be declared inside the companion and have zero extension receivers.
4. The overloads must have zero context parameters

**Definition.** 
For the given use-site, A _collection literal element type_ of type `A` is the type of the single `vararg` parameter of the `A.Companion.of` function available at the use-site.
If there are none or multiple `A.Companion.of` functions available with single `vararg` parameter (those overloads must be coming from different _overloading groups_)
then we say that _collection literal element type_ is undefined.

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

### Overload resolution

One particular example








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

### Rejected proposal: built-in matrices

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

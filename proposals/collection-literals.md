# Collection Literals

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**: Alejandro Serrano Mena, Marat Akhin, Mikhail Zarechenskii, todo
* **Issue:** [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871)
* **Discussion**: todo

**Precondition:** the text below is written with the assumption that Kotlin already implemented "Improved resolution by expected type" feature.
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
_Type static scope_ is the set/scope that contains callables (functions and properties) declared in the `Type.Companion` nested `companion object`, declared as extensions for `Type.Companion`.
Once statics feature comes to Kotlin, it becomes part of the _type static scope_.

It's proposed to use square brackets because the syntax is already used in Kotlin for array literals inside annotation constructor arguments,
because it's the syntax a lot of programmers are already familiar with coming from other programming languages,
and because it honors mathematical notation [for matrices](#rejected-proposal-built-in-matrices).

**Informally**, the proposal strives to make it possible for users to use collection literals syntax to express user-defined types `val foo: MyCustomList<Int> = [1, 2, 3]`.
And when the expected type is unspecified, expression must fallback to `kotlin.List` type: `val foo = [1, 2, 3] // List<Int>`.

**More formally**, before the collection literal could be used on the use-site, an appropriate type needs to declare `operator fun of` function in its _static scope_.
The `operator fun of` functions must adhere to the following restrictions:
1.  It's allowed to provide several `of` overloads.
    But the overloads are allowed to differ only by number of parameters.
    Everything else must be the same (type parameters, return type)
2.  Types of all parameters across all `of` overloads must be the same.
    We will call this type an **input type** of collection literal.

The logic behind restrictions and what happens if they are violated are described [further](#overload-resolution-and-operator-function-restrictions).

Once `operator fun of` is declared, the collection literal can be used on the use-site.
1. When the collection literal is used in the _position with expected type_, it's proposed to literally desugar collection literal `[expr1, expr2, expr3]` to `_.of(expr1, expr2, expr3)`.
2. In all other cases, it's proposed to desugar collection literal to `List.of(expr1, expr2, expr3)`.

The fact that collection literals is just merely a syntactic sugar for another Kotlin feature is a good thing, because it makes the language more transparent for users.
It makes the language easier to learn since the number of concepts reduces.
Kotlin already has features that are merely a syntactic sugar for other more basic building blocks.
Like `x++` is desugared to `x = x.inc()`, it's not a new thing.

The basic example:
```kotlin
class MyCustomList<T> {
    companion object { operator fun <T> of(vararg t: T): MyList<T> = TODO() }
}
fun main() {
    val foo: MyCustomList<T> = [1, 2, 3]
    val foo: MyCustomList<String> = ["foo", "bar"]
}
```

Please notice that it is not necessaty for the type to extend any predifined type in the Kotlin stdlib (necessary for `kotlin.Array<T>` type),
nor it necessaty for the user-defined type to declare mandatory generic type parameters (necessary for specialized arrays like `kotlin.IntArray`, `kotlin.LongArray`, etc.).

## Overload resolution and operator function restriction

Readers of this section are encouraged to familiarize themselves with how [type inference](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md) and 
[overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution) works in Kotlin.

The reasoning behind restrictions on `operator fun of`, introduced in the [proposal](#proposal) section, are closely related to overload resolution and type inference.
Consider the following real-world example:
```kotlin
@JvmName("sumDouble") fun sum(set: List<Double>): Double = TODO("not implemented")
@JvmName("sumInt")    fun sum(set: List<Int>): Int = TODO("not implemented")

fun main() {
    sum([1, 2, 3])
}
```

We remind the restrictions:
1.  It's allowed to provide several `of` overloads.
    But the overloads are allowed to differ only by number of parameters.
    Everything else must be the same (type parameters, return type)
2.  Types of all parameters across all `of` overloads must be the same.
    We will call this type an **input type** of collection literal.







The invariants






The complete example:

```kotlin
class MyList {
    companion object {
        operator fun <T> of(vararg t: T): MyList<T> = TODO()
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

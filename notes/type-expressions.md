# Design Notes on Kotlin Type Expressions

* **Type**: Design notes
* **Author**: Werner Thumann
* **Contributors**: TODO
* **Status**: TODO
* **Discussion and feedback**: [KEEP-TODO](https://github.com/Kotlin/KEEP/issues/TODO)

This is not a design document, but a brief and unified overview of language features revolving around defining anonymous types.
Except for maybe [restriction types](#restriction-types) and [tagged types](#tagged-types), most of those features are not new, already have been proposed elsewhere, exist in other languages or even partially in Kotlin itself.
Considering them in tandem and under a common umbrella could reveal synergies which should be taken into account in a final design.

## Introduction

An informal definition of a *type expression* could be as follows:
A way to define a type without identity using other types.
Such a type expression can be used at places where a type is expected.

An example of a type expression already exists in Kotlin: *function types*.
Assuming that types are values of the universe type, type expressions are not very different from conventional expressions.
```kotlin
val number = 1 + 2
print(number)
print(1 + 2)

typealias StringProvider = () -> String
fun print(provider: StringProvider)
fun print(provider: () -> String)
```

Each kind of type expression must offer ways to construct values.
For the case of function types, we have lambdas and anonymous functions.
```kotlin
val myFunc: (String) -> Int = { it.length }
val myFunc = fun(it: String): Int { return it.length }
```

Other kinds of type expressions include
* product types
* union types
* intersection types

The names reflect operations on sets in the mathematical sense.
Motivated by this, another kind is apparent: Given a type `X`, we want to define another type with values forming a *subset* of the values of `X`.
We call the type expression associated to this construction a *restriction type* and discuss it [below](#restriction-types).
Another not so common kind is a *tagged type*.
Roughly speaking, it duplicates the values of the original type by adding a certain label to them.
Use-cases for this kind of type expression are presented [below](#tagged-types).

Why should we care about new type expressions besides function types?
The answer is simple: Exactly the same reasons for which function types have been introduced alongside SAM (single abstract method) interfaces.
New kinds of type expression will support and strengthen the functional side of Kotlin.

The type expressions discussed below also relate to other proposed features not yet present in Kotlin, like [value classes](value-classes.md) and `const` functions [KT-14652](https://youtrack.jetbrains.com/issue/KT-14652).
Furthermore, recursive `typealias`, which would enable to define recursive types using type expressions, is not yet supported in Kotlin.
Besides that, `typealias` allows to give identities to type expressions just like `val` or `var` does for regular expressions.
This way, type expressions could be used instead of classes in many (but not all) situations.
Part of a final design must define the interoperability between type expression and the class world.
The following table lists the mentioned type expressions and their equivalents in the class world.

| type expression   | class equivalent                       |
|-------------------|----------------------------------------|
| function type     | `fun interface`                        |
| product type      | `data class` or `value class`          |
| union type        | `sealed interface/class`, `enum class` |
| tagged type       | -                                      |
| intersection type | -                                      |
| restriction type  | -                                      |

## Product types

Also known as tuples, e.g. in Scala.
The basic syntax is `(A, B)` for types `A`, `B` and a value can be defined as `(a, b)` for `a: A`, `b: B`.

A tuple behaves like `data class` defined with `val` properties, maybe even a `value class` as soon as they are available in Kotlin.
In any case, `hashCode`, `equals`, `toString` and `copy` methods are generated according to their components.
Also, `operator component` functions are generated which provide a standard way to access the individual components as well as allow for destructuring declarations.
```kotlin
val pair: (String, Int) = ("foo", 8)
val first: String = pair.component1()
val second: Int = pair.component2()
val (first, second) = pair
```

A frequent criticism on product types is the anonymous nature of their components.
This can be alleviated by introducing an extended syntax where the components are named individually.
These components can then be accessed via property syntax.
```kotlin
val person: (name: String, age: Int) = ("Peter", 32)
val name = person.name
val age = person.age
```
Similarly, as for named parameters in function types, the names of the components are not part of the value data.
Consequently, `(String, Int)` and `(name: String, age: Int)` are assignment compatible in both directions.

Theoretically, an empty tuple expression `()` makes sense.
As it would just be equivalent to `Unit`, it could simply be forbidden.
A tuple expression wrapping a single type like `(A)` would also make sense, however, introduces ambiguities in syntax related to grouping parentheses in compound type expressions.
For example, `() -> (() -> Unit)` is valid Kotlin syntax and expresses a function producing a function.
With the introduction of `(A)` as tuple type, this might be interpreted as a function returning a box wrapping a function.
So also `(A)` could be forbidden as tuple type, interpreting such a construct as grouping expression, unless it contains `,` or is followed by `->`.
Although not very useful (except in combination with tagged types below), the ambiguity should not arise with `(a: A)`.
A different solution for avoiding ambiguities could consist of choosing an alternative syntax for product types, such as `A * B`.

A common use-case for product types is to return multiple values from functions without having to define a separate class.
```kotlin
fun parseNextInt(line: String): (value: Int, nextPosition: Int)
```

YouTrack issues: [KT-45587](https://youtrack.jetbrains.com/issue/KT-45587)

## Tagged types

This produces a copy of a given type with an equivalent set of values.
However, it can be distinguished from the original type via a tag or label.
The basic syntax is `Tag@A` where `Tag` is a free to choose symbol (same restrictions and conventions as for class names) and `A` is a type.
Value constructors have the form `Tag@a` where `a: A`.

Note that the symbol `Tag` does not give an identity to the type, contrary to the name of a class.
Values of `Tag@A` are automatically cast to `A` if needed.
Values of `A` or `Other@A` are *not* automatically cast to `Tag@A`.
```kotlin
var string = "any string whatsoever"
var name: Name@String = Name@"Peter"
string = name // ok
name = string // not allowed

fun Name@String.greet() = print("Hello, ${uppercase()}") // receiver cast to String
Name@"Peter".greet() // prints "Hello, PETER"
"something".greet() // does not compile
```

Of course, tagged types can be used in combination with any other type expression, including tuple types.
In particular, `Tag@(a: A)` would be a tagged unary tuple with named component.
If `x: Tag@(a: A)`, the underlying value of type `A` could be accessed via `x.a` because `x` is automatically cast to `(a: A)`.

A special use-case arises when tagging `Unit`.
This effectively produces a singleton type with a specific name for its value.
Similarly, as for function return types, we could drop the `Unit` and arrive at a more compact syntax.
```kotlin
val on: On@Unit = On@Unit
val off: Off@ = Off@
```
Together with [union types](#union-types), this makes it possible to model enumerations as type expressions.
More generally, tagged types help to realize tagged unions, see below.

## Union types

The basic syntax is `A | B` for types `A` and `B`.
Any value `a: A` or `b: B` is also a value in `A | B`.
An alternative syntax could be `A + B`.

By default, the union is non-disjoint, meaning that any value which is both in `A` and `B` also appears only once in `A | B`.
In particular, `A | A` is equivalent to `A`.
Disjoint unions, also called tagged unions, can be realized with [tagged types](#tagged-types).
For example `Success@String | Failure@String` is a disjoint union of two copies of `String`.
Applied to `Unit` as carrier type, this yields basic enumerations.
For example `One@ | Two@ | Three@` is a type with three values `One@`, `Two@` and `Three@` respectively.

The standard way to consume union types is via `when` blocks.
Example:
```kotlin
typealias Color = Red@ | Blue@ | Yellow@ | Green@
val vehicle: Car@(brand: String, color: Color) | Bike@
when (vehicle) {
    Car@("Ferrari", Red@) -> print("red sports car")
    is Car@(b: String, c: Color) -> print("${vehicle.c} ${vehicle.b}")
    Bike@ -> print("just a bike")
}
```

Union types are convenient as return types of functions when different outcomes need to be modelled.
```kotlin
fun parseData(s: String): Data | Failure
```
Furthermore, they could be used to realize the multi-catch feature available in Java but still missing in Kotlin.
```kotlin
try {
    // do something that could fail
} catch (e: IllegalStateException | IllegalArgumentException) {
    // handle exceptions
}
```

Theoretical examples requiring recursive `typealias`:
```kotlin
typealias N = Zero@ | Succ@N
typealias List<T> = (head: T, tail: List<T>) | Empty@
```

YouTrack issues: [KT-13108](https://youtrack.jetbrains.com/issue/KT-13108), [KT-7128](https://youtrack.jetbrains.com/issue/KT-7128)

## Restriction types

Subtyping with classes is realized via class extension.
As the word indicates, extending a base class modifies it by adding values to it.
Because of this, designing a class requires planning for extension in advance, or forbid extensibility.

Instead, it would be desirable to define a subset of a given type to be a new type, embedded in the old.
Doing so builds strongly on compile-time `const` predicates [KT-14652](https://youtrack.jetbrains.com/issue/KT-14652).
The basic syntax could be `A / p` where `A` is a type and `p` is a `const` predicate on `A`.
Example where `A` is `Int`:
```kotlin
const fun even(num: Int) = num % 2 == 0
typealias EvenInt = Int / ::even

// or alternatively
const val even: const (Int) -> Boolean = { it % 2 == 0 }
typealias EvenInt = Int / even
```

Of course, any value of `A / p` can be assigned to `A`.
The converse is not always true and the value needs to be down-cast.
This is where smart-casting known from nullable types `A?` comes into play.
In fact, restriction types is a mechanism to open up this feature to user-defined types.
```kotlin
fun halve(evenNumber: EvenInt)

halve(3) // does not compile
halve(4) // ok

val number: Int
halve(number) // does not compile
halve(number as EvenInt) // ok, but unsafe
if (even(number)) {
    halve(number) // ok
}
```

To what extent smart-casting can be implemented into the compiler and how 'clever' it will be is probably a matter of expertise, effort and a big topic of its own.
For example, is the compiler able to detect that two `const` predicates are equivalent?
```kotlin
val number: Int
if (number % 2 == 0) {
    halve(number) // can the compiler deduce that the check is equivalent to even(number)?
}
```

Independently of that, the idea of restriction types is strongly justified by a principle worth to pursue: Moving program validity checks from runtime to compile-time as much as possible.
```kotlin
// this is how we would do it today: not good
fun halve(number: Int): Int {
    require(number % 2 == 0)
    return number / 2
}

// better: exceptions at runtime are avoided
fun halve(evenNumber: EvenInt): Int = number / 2
```

A special case are predicates restricting to a single value, producing singleton types embedded in a given type.
Instead of
```kotlin
const val v = 0
const fun p(num: Int) = it == v
typealias Zero = Int / ::p
```
we could just write `Int / v` or even `Int / 0`.
Together with union types, this allows to define finite types embedded in a given type, e.g. a finite set of integers.
```kotlin
typealias SpecialNumber = Int / 0 | Int / 8 | Int / 42
```
This would be equivalent to the following, but the usage would be different:
```kotlin
const fun specialNumber(num: Int) = num == 0 || num == 8 || num == 42
typealias SpecialNumber = Int / ::specialNumber
```

Something which is noticeable here is the dependency on an external symbol (the predicate) in the definition of a restriction type.
In order to benefit from smart-casting (in the basic form), the predicate should be available when working with the derived restriction type.
An idea would be to make it accessible via familiar syntax like `EventInt::predicate`.
If the predicate is always retrievable from the type like this or similar, it would also be possible to inline it in the definition of the type:
```kotlin
typealias EvenInt = Int / { it % 2 == 0 }
val number: Int
if (EvenInt::predicate(number)) {
    val evenNumber: EvenInt = number // ok, number is smart-cast to EvenInt
}
```

## Intersection types

Given types `A`, `B`, the intersection type `A & B` represents the values which are of type `A` and `B` simultaneously.

Intersection types already exist as an internal representation in the Kotlin compiler.
Moreover, definitely non-nullable types already use the syntax.
```kotlin
fun <T> elvis(x: T, y: T & Any): T & Any = x ?: y
```

In the world of classes, intersection types are primarily useful with interfaces in order to require that a type has to implement multiple of these.
```kotlin
fun cloneAndSerialize(obj: Cloneable & Serializable)
```

The real potential, however, will arise in combination with [restriction types](#restriction-types).
```kotlin
const fun short(s: String) = s.length < 8
const fun upper(s: String) = s.firstOrNull()?.isUpperCase() ?: false
typealias ShortString = String / ::short
typealias UpperString = String / ::upper
typealias ShortUpperString = ShortString & UpperString
```
The type
```kotlin
const fun shortAndUpper(s: String) = short(s) && upper(s)
typealias ShortUpperString = String / ::shortAndUpper
```
would be equivalent but requires smart-casting based on `shortAndUpper` which the compiler might not understand to be a combination of `short` and `upper`.

YouTrack issues: [KT-13108](https://youtrack.jetbrains.com/issue/KT-13108)

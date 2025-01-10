# Underscore syntax for unused local variables

* **Type**: Design proposal
* **Authors**: Leonid Startsev, Mikhail Zarechenskiy
* **Contributors**: Alejandro Serrano Mena, Marat Akhin, Nikita Bobko, Pavel Kunyavskiy
* **Status**: Experimental in Kotlin 2.2
* **Discussion**: [link to discussion thread or issue]

## Synopsis

The gist of this proposal is allowing `_` (one underscore) as a local variable name.
The intention is for the compiler and other static analysis tools to consider that value
as ignored or unused (depending on the particular scenario):

```kotlin
fun writeTo(file: File): Boolean {
    val result = runCatching { file.writeText("Hello World!") }
    return result.isSuccess
}

fun foo(file: File) {
    val _ = writeTo(file) // We are not interested in whether the write operation was successful
}
```

Underscore can be used only in *local variable declaration*: `val _ = ...` and cannot be referenced or resolved otherwise.

## Motivation

1. Explicit expression of intent

When reading and reviewing code you are unfamiliar with, it may be hard to say whether unused function return value is an intention or a subtle bug.
Explicit syntax would help quickly differentiate between the two.

2. Readiness for static analysis

In the scope of [Unused return value checker](unused-return-value-checker.md) proposal, a checker for more complex unused expressions will be added to the Kotlin compiler.
This syntax will make its adoption much easier since there would be no need to `@Suppress` checker warnings in cases where the value has to be explicitly ignored.
Other static analysis tools will also report less false positives with this feature.

3. Uniformity in existing Kotlin features

Kotlin already supports underscores for unused things in several different positions:

* Underscore for unused lambda/anonymous function parameters ([KEEP](underscore-for-unused-parameters.md), [link](https://kotlinlang.org/docs/lambdas.html#underscore-for-unused-variables)).
* Underscore for unused components in positional-based destructuring ([link](https://kotlinlang.org/docs/destructuring-declarations.html)).
* Underscore for unused type arguments ([link](https://kotlinlang.org/docs/generics.html#underscore-operator-for-type-arguments))
* Underscore for unused exception instances ([link](https://youtrack.jetbrains.com/issue/KT-31567))

Adding an underscore as a name for unused local variables is a logical extension of this trend.

## Proposed syntax

It is allowed to declare a variable as `val _`. Such a variable is called **unnamed**.
Unnamed variable declarations should adhere to the following restrictions:

* Only local variables (not class or top-level properties) can be declared unnamed.
* Only the `val` keyword can be used; `var` is not allowed.
* You can have multiple unnamed variables in a single scope.
* Unnamed variables cannot be delegated.
* Referencing an unnamed variable is not possible. Because of that, it is required to have an initializer.
* Unnamed variables can have their type specified explicitly. If type is not specified, it is inferred using the same rules as regular variables.

### Discarded alternatives

Besides using `val _ = ...` syntax, a shorter `_ = ...` statement was considered as an alternative.
However, we've decided not to proceed with it for the following reasons:

1. `val` syntax is better aligned with already existing Kotlin features.

Kotlin already has positional-based destructuring with an option to use an underscore to omit certain components: `val (first, _) = getSomePair()`. Since positional-based destructuring declares new variables, it uses `val` syntax.

On the other hand, Kotlin has the 'underscore for unused lambda parameter' feature. Using underscore without `val` here may imply some connection with the lambda parameter, while in reality, there is no such connection:

```kotlin
listOf(1, 2, 3).mapIndexed { idx, _ ->
  _ = unusedCall(idx) // Is _ a new unnamed variable or an existing lambda parameter?
}
```

2. `val` syntax implies the creation of a new unnamed variable rather than referencing an existing one.

Normally, without the `val/var` keywords, you can encounter only existing declarations on the left-hand side of an assignment.
Using `_ = ...` syntax may create an impression that `_` is some existing global variable, while in reality, any expression that uses `_` — such as `println(_)` — would produce 'unresolved reference' compiler error.

## Further extensions

Besides this proposal and already existing underscore usages, one of the most requested places for underscore support is an unused function parameter:

```kotlin
fun foo(_: String) {
  println("Parameter is unused")
}
```

However, this use case is explicitly out of the scope of this proposal.
Its destiny will be decided later.
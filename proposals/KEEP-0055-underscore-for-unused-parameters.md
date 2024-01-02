# Underscore for unused parameters

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Contributors**: Andrey Breslav, Roman Elizarov, Stanislav Erokhin
* **Status**: Implemented
* **Discussion**: [KEEP-55](https://github.com/Kotlin/KEEP/issues/55)

## Goals

In some cases we are bound to declare all the parameters in function, lambda or
destructuring declaration while some of them remain unused.

We need a way to omit names for such parameters.

## Use cases

For example we have an observable delegated property:
``` kotlin
var name: String by Delegates.observable("no name") {
    kProperty, oldValue, newValue -> println("$oldValue")
}
```

Sometimes we just don't need the exact instance of `KProperty` or even the `oldValue`,
but we have to declare them because it's required by the given function's signature.

Another example is destructuring declarations - occasionally we want to skip some
of the components:
``` kotlin
val (w, x, y, z) = listOf(1, 2, 3, 4)
print(x + z) // 'w' and 'y' remain unused
```

## Solution

The idea is to allow using one underscore character (`_`) as a name for parameter
of a lambda or an expression function or as a name of destructuring entry.

It must be allowed to declare such parameters many times in the same place.

Examples:
``` kotlin
var name: String by Delegates.observable("no name") {
    _, oldValue, _ -> println("$oldValue")
}

val (_, x, _, z) = listOf(1, 2, 3, 4)
print(x + z)

val functionExpression: (Int) -> Int = fun(_: Int) = 1
```

Note that it's still possible to declare type of these variables:
``` kotlin
var name: String by Delegates.observable("no name") {
    _: KProperty<*>, oldValue, _ -> println("$oldValue")
}
```
These types must be treated as usual

## Scopes

Of course such declarations don't affects the declaring scope, e.g. they can't
be referenced in the next statements.

Note, that in Kotlin 1.0 it is forbidden to have a declaration with identifier token containing
only underscores (`_`), at the same time one can declare underscore-name using backticks:
``` kotlin
fun foo(`_`: Int) = _ // _ after the equal sign is resolved to the first parameter of 'foo'
```

## 'componentX' calls

In case of destructuring declarations skipped components must be checked as usual,
but relevant `componentX` calls mustn't be present in run-time.

## "Unused declaration" warning
For all declarations that may have their name skipped (replaced by `_`) warning
should be reported if they are effectively unused.

Also an IDE quickfix is expected that turns unused declaration's name to `_`.

## Other languages
- In Haskell underscore character has meaning of a wildcard in [pattern matching](https://en.wikibooks.org/wiki/Haskell/Pattern_matching)
- For C# `_` in lambdas there is just an [idiom](https://charlieflowers.wordpress.com/2009/04/02/nice-c-idiom-for-parameterless-lambdas/)
 without special treatment in the language
- It seems that the same semantic may be applied in future versions of Java.
See [related message](http://mail.openjdk.java.net/pipermail/lambda-dev/2013-July/010670.html).

## To be done in the future
The same trick could also be applied to the named functions.

One of the points why unused parameters are necessary for non-overrides is
that they may be useful for method references:
``` kotlin
var name: String by Delegates.observable("no name", ::onChange)

fun onChange(x: KProperty<*>, oldValue: String, newValue: String) {
    println(oldValue)
}
```

Of course it could be useful for overrides that are obligated to declare
parameters because they're present in the overridden declaration, while
they may be completely irrelevant to the specific implementation.

But there are some questions to answer about what names must be used for such
anonymous parameters both in Kotlin call sites and in JVM signature.

## References
* [KT-3824](https://youtrack.jetbrains.com/issue/KT-3824) Underscore in lambda for unused parameters
* [KT-2783](https://youtrack.jetbrains.com/issue/KT-2783) Allow to skip some components in a multi-declaration

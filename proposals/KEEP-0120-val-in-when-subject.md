# Local variable declaration in 'when' subject

* **Type**: Design proposal
* **Author**: Dmitry Petrov
* **Contributors**:
* **Status**:
* **Prototype**: In progress

Discussion: https://github.com/Kotlin/KEEP/issues/120

## Problem description

Local variable declaration in `when` subject allows to introduce a meaningful name with a proper scope
that can be used to reference the subject value.
Also, it enables smart casts on the subject value in corresponding branches.

In Kotlin 1.2, explicit value declaration for when subject would be:
```kotlin
    val x = foo()
    when (x) {
        is String -> println(x.length)
        is Number -> println(x.toInt())
    }
```
This, however, places `x` in the outer scope, and makes code somewhat harder to follow
(is `x` used just in `when`, or also somewhere else in the following code?).

## Design details

The following restrictions apply to local variable declarations in `when` subject:
* Must be a `val`
* Must have an initializer
* Custom accessors are not allowed
* Local delegated properties are not allowed

Examples:
```kotlin
    when (val x = foo()) { ... }            // Ok

    when (val x: Number = foo()) { ... }    // Ok, explicit type annotation can be provided

    when (var x = foo()) { ... }             // Error, must be a val

    when (val x: Number) { ... }            // Error, must have an initializer

    when (val x get() = ...) { ... }        // Error, custom accessors are not allowed
                                            //  (this code actually doesn't parse in Kotlin 1.2, which is ok)

    when (val x by bar()) { ... }           // Error, local delegated properties are not allowed in 'when' subject
```

## Open questions

### No 'val' keyword

Option suggested during discussions: omit `val` keyword for subject variable in `when` (which would be similar to loop
variable in `for`):
```kotlin
    when (x = foo()) { ... }
    when (y: String = bar()) { ... }
```

This might make code somewhat harder to read, because `x = foo()` in the example above looks like an expression (or even
like an assignment statement).

### Explicit type annotations & exhaustiveness

Consider the following code:
```kotlin
enum class Color { RED, GREEN, BLUE }

fun foo(x: Color) =
    when (val xx: Color? = x) {
        Color.RED -> "Roses are red"
        Color.GREEN -> "Grass is green"
        Color.BLUE -> "Violets are blue"
    }
```

Equivalent code without subject variable is currently considered ill-formed, even though we statically know that `xx` is
not null:
```kotlin
fun foo(x: Color): String {
    val xx: Color? = x
    return when (xx) {                      // Error: 'when' expression must be exhaustive
        Color.RED -> "Roses are red"
        Color.GREEN -> "Grass is green"
        Color.BLUE -> "Violets are blue"
    }
}
```

So, in the current prototype, for the sake of consistency, the code in example above is also considered ill-formed.


### Destructuring declarations

It's possible to introduce destructuring declarations in `when` subject as follows:

Destructuring declaration in `when` subject is well-formed if the aforementioned restrictions are satisfied,
and it's well-formed as a standalone destructuring declaration. E.g.:
```kotlin
    val p: Pair<Int, Int> = ...

    when (val (p1, p2) = p) { ... }         // Ok

    when (val (p1, p2, p3) = p) { ... }     // Error: missing 'component3()' function
}
```

Here `when (val (p1, p2) = p) { ... }` is equivalent to (modulo scopes):
```kotlin
    val (p1, p2) = p
    when (p) { ... }
```

However, this code looks somewhat confusing, and this feature can be introduced later without breaking changes, if it's
considered valuable.

Note that smart casts on destructured value currently have no effect on the corresponding components. E.g.:
```kotlin
interface NumPair {
    operator fun component1(): Number
    operator fun component2(): Number
}

interface IntPair : NumPair {
    override fun component1(): Int
    override fun component2(): Int
}

fun useInt(x: Int) {}

fun test(p: NumPair) {
    val (p1, _) = p
    if (p is IntPair) {
        useInt(p1) // Error
    }
}
```


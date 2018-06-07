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
```
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
* Should be a `val`
* Should have an initializer
* Custom accessors are not allowed
* Local delegated properties are not allowed

Examples:
```
    when (val x = foo()) { ... }            // Ok

    when (val x: Number = foo()) { ... }    // Ok, explicit type annotation can be provided

    when (val x: Number) { ... }            // Error, should have an initializer

    when (var x = foo() { ... }             // Error, should be a val

    when (val x get() = ...) { ... }        // Error, custom accessors are not allowed
                                            //  (this code actually doesn't parse in Kotlin 1.2, which is ok)

    when (val x by bar()) { ... }           // Error, local delegated properties are not allowed in 'when' subject
```

Destructuring declaration in `when` subject is well-formed if the aforementioned restrictions are satisfied,
and it's well-formed as a standalone destructuring declaration. E.g.:
```
    val p: Pair<Int, Int> = ...

    when (val (p1, p2) = p) { ... }         // Ok

    when (val (p1, p2, p3) = p) { ... }     // Error: missing 'component3()' function
}
```

Note that smart casts on destructured value currently have no effect on the corresponding components. E.g.:
```
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
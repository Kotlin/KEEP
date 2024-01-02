# Named arguments in their own position

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Contributors**: Mikhail Zarechenskiy
* **Status**: Submitted
* **Prototype**: Implemented in 1.3.60
* **Discussion**: [KEEP-193](https://github.com/Kotlin/KEEP/issues/193)
* **Tracker**: [KT-7745](https://youtrack.jetbrains.com/issue/KT-7745)

## Summary

Currently, [named arguments](https://kotlinlang.org/docs/reference/functions.html#named-arguments) have a specific limitation:
when a function is called with both positional and named arguments, all the positional arguments should be placed before
the first named one. For example, the call `f(1, y = 2)` is allowed, but `f(x = 1, 2)` is not.

But in some cases, such limitation might be inconvenient.

## Motivation

### Documenting literal arguments
The major known use-case when one may want to mix named and positioned arguments is documentation: sometimes it's useful
to specify in the source explicitly to what parameter exactly this argument matches. Especially, it's important when
the argument is appeared as some plain literal:
```kotlin
someFunction(true, someOtherArgumentThatAppearsObviousOnCallSite)
someFunction(runInTheSameThread=true, someOtherArgumentThatAppearsObviousOnCallSite)
```

The second call version seems to be much more clear than the first one: it becomes obvious on the call-site
that computation would be run in the same thread.

## Rules and semantics
- Proposed semantics is the following: if there was a successfully resolved call in 1.3 it's allowed to add a name
to any positioned non-vararg argument.
- In other words, if there were positioned non-vararg arguments `a_1, ..., a_k` in a successfully resolved call `f(a_1, .., a_k, ...)`
then it's allowed to specify names for any subset of those `a_1, ..., a_k` arguments.

## Examples

### Simple
```kotlin
fun foo(
    p1: Int,
    p2: String,
    p3: Double
) {}

fun main() {
    foo(p1 = 1, "2", 3.0) // OK
    foo(1, p2 = "2", 3.0) // OK
    foo(1, "2", p3 = 3.0) // OK

    foo(p1 = 1, p2 = "2", 3.0) // OK

    foo(p2 = "2", p1 = 1, 3.0) // Error: p1 and p2 are not on their position in the list
    foo(1, p3 = 2.0, "") // Error, p3 is not on its position
}
```

### Varargs
```kotlin
fun foo1(
    vararg p1: Int,
    p2: String,
    p3: Double
) {}

fun main() {
    foo1(1, 2, p2 = "3", 4.0) // Is not allowed because first arguments match to a vararg parameter
    foo1(p2 = "3", 4.0) // Is not allowed because there's implicit empty vararg argument in the beginning
}
```

## Remaining questions/issues
- None

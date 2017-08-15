# Error handling in the Kotlin language #

* **Type:** Design proposal
* **Author:** Ben Leggiero
* **Status:** Under consideration
* **Revision:** 2

# Summary #
The purpose of Kotlin, Swift, and other such modern languages, as I see them, are **to enhance the programmer's experience of writing code** (to bring delight and joy back into the programming experience); to do more with less and produce higher-quality output (that is, bytecode, assembly, JS, or whathaveyou). If that's true, then the languages themselves should make it obvious when you're writing something dangerous that is likely to crash your app (which makes programming very frustrating). This proposal attempts to incorporate exception handling into the Kotlin language in a way that achieves that goal.

## Revision History ##
1. Original proposal
2. Added `rethrows` for functions that take in a closure that might throw

# Motivation #
My program crashing without me knowing whether it will at compile-time is something I hate; it makes the programming experience worse, not better. Often, I have designed my end of the code without hese crashes, but because the person on the SDK side might have decided their code can throw (very common when using Java SDKs), and the people on the language side with Kotlin decided the compiler will ignore exceptions by default, my program will crash through no obvious fault of my own, because the compiler couldn't warn me.

**This is why we have optionals** that must be unwrapped before using them, as opposed to making `NullPointerExceptions` the default like Java. On that same note, this is why the old throw-catch pattern was invented in the first place: to avoid the mysterious C-style signal-based crashes. It feels like a huge step back to just let all exceptions through by default.

> This is inspired by [Swift's approach to error handling](https://developer.apple.com/library/content/documentation/Swift/Conceptual/Swift_Programming_Language/ErrorHandling.html).

# Description #
I propose Kotlin adopt a similar pattern to Swift's approach to exceptions, so that **_all_ the following** are a part of the language:

## Declarations ##
### Declaration Site A ###
A function's signature may include the `throws` keyword to indicate that it throws an exception. **The type of the exception is not indicated in the function signature.** Here, "``: Int`" still specifies that the function returns an `Int`, but has no bearing on the exception type.

```Kotlin
fun danger(i: Int) throws : Int {
    if (Random().nextBoolean()) {
        throw UnsupportedOperationException()
    } else {
        return i
    }
}
```

> If a function does throw an exception, or does not handle an exception thrown inside it, then it must be declared using the `throws` keyword; omitting this would be a compiler error.

### Declaration Site B ###
Similar to [Declaration Site A](#declaration-site-a), but for closures:

```Kotlin
typealias Danger = (Int) throws -> Int
```

> Note that a closure like this does not neccessarily have to throw. This is elaborated upon in [Call Sites G](#call-sites-g) and [H](#call-site-h).

### Declaration Site C ###
For a function that takes closure arguments which might throw an exception (for instance, [Declaration Site B](#declaration-site-b)'s `Danger`), the function may be declared using the `rethrows` keyword, meaning that if the closure throws, whatever calls the rethrowing function needs to handle that, but if the closure doesn't throw, the caller does not need to handle an exception:

```Kotlin
fun rethrower(danger: Danger) rethrows : Int {
    return try danger()
}
```

> Note that because `danger` is of type `Danger`, which is a closure that may or may not throw, `rethrower` is allowed to be declared as `rethrows` instead of `throws`. It may also be declared as `throws`, in which case it must be treated just like [Declaration Site A](#declaration-site-a), even when the given closure `danger` doesn't throw. It is a compile-time error for rethrower to be declared without `throws` or `rethrows`.

## Calls ##
Generally, when calling a function, it must be obvious whether it might throw an exception, so safe ones are called normally, but dangerous ones (which might throw an exception) _must_ be preceded by a form of  `try`; the compiler would emit an error if either of these is disobeyed (`try` preceding a safe function, or not preceding a dangerous one). This is elaborated upon below:

### Call Site A ###
Use a `do`-`catch` block-style paradigm to catch anything. The type and value of the exception are discarded. **Note that there still cannot be a naked `do` block**; it must be followed by `catch` to catch exceptions or `while` to create a loop.

```Kotlin
do {
    println("This line always runs")
    val a = try danger(1)
    println("This line is only run if `danger` does not throw")
} catch {
    println("I caught, but I don't care what I caught")
}
println("This line always runs")
```

### Call Site B ###
Use a `do`-`catch` block-style paradigm to catch anything. The type of the exception is not restricted, and the value is saved in a argument, whose type can be later checked.

```Kotlin
do {
    println("This line always runs")
    val b = try danger(2)
    println("This line is only run if `danger` does not throw")
} catch (x: Throwable) {
    println("I caught $x, but I don't care what type it is")
}
println("This line always runs")
```

### Call Site C ###
Use a `do`-`catch` block-style paradigm to catch only a specific type of exception, with the option to catch further specific types andor have a catch-all that ignores unexpected types. The first block whose type matches the thrown exception is the only one called. **This is the most like Java's approach.**

```Kotlin
do {
    println("This line always runs")
    val c = try danger(3)
    println("This line is only run if `danger` does not throw")
} catch (x: UnsupportedOperationException) {
    println("I caught $x, as an unsupported operation")
} catch (x: NumberFormatException) {
    println("I caught $x, as a number format exception")
} catch (x: Throwable) {     // Note: "} catch {" is also legal here
    println("I caught $x, but I don't care what type it is")
}
println("This line always runs")
```

> **Important:** If no `catch` block matches the type of thrown exception, then the program will halt/crash. It's recommended to ensure a `Throwable` is caught (or the [Call Site B](#call-site-b)-style catch-all is used) after all explicit exception types are caught.

### Call Site D ###
Use a single-line "optional-`try`" paradigm to catch any exception. The type and value of the exception are discarded, and the function's result is wrapped in an optional which is `null` if the function throws, but contains a value if the function does not throw.
This essentially behaves as if the function's return type was optional.

```Kotlin
println("This line always runs")
val d = try? danger(4) // d is Int?
println("This line always runs, even if danger throws")
```

> Note that even if the return type is `Unit`, `try?` can be used; its return value can be ignored just as always

### Call Site E ###
Use a single-line "force-`try`" paradigm to ignore all exceptions. The type and value of the exception are discarded. If the function throws an exception at run-time, the variable is never set and the application crashes/halts. Else, it is the output as a non-wrapped type.
This essentially behaves like Kotlin today would treat `val e = danger(5)` when used outside a `try`-`catch` block.

```Kotlin
println("This line always runs")
val e = try!! danger(5) // e is Int
println("This line is only run if danger does not throw")
```

### Call Site F ###
Place the call site inside a new function that is marked with `throws`. The possibly-throwing function(s) called inside it must still be marked with `try`, but exceptions don't need to be handled inside the new function, itself. This allows handling to be propagated up the chain to the caller of the new function.

```Kotlin
fun useDanger() throws {
    println("This line always runs")
    val f = try danger(6)
    println("This line is only run if `danger` does not throw")
}
```

> Note: It is a compiler error for a function like this to omit the `throws` keyword


### Call Sites G ###
Calling a function that is declared as `rethrows` inherits the throwing state of the closure passed to it. This means that any closure declared like this with an unhandled exception is implicitly declared as `throws`:

```Kotlin
val g1 = rethrower { 42 } // `try` is not allowed; given closure can not throw

val g2 = try rethrower { try danger(7) } // `try` is required in both contexts; given closure might throw, but it's not handled in the closure

val g3 = rethrower { // `try` not allowed on this line because the given closure catches all its own exceptions
    do {
        try danger(7) // `try` is required here as it always is
    } catch {
        print("An exception was caught, but I don't care what it was")
    }
}
```

### Call site H ###
Calling a function that is declared as `rethrows` by passing it a function that is declared with `throws`, rather than declaring a new closure at the call site like Call Sites G, means that **`try` is required**, since the safety of the given function cannot be determined at compile time. Conversely, passing a function that is declared _without_ `throws` is assumed safe and does not allow `try`:

```Kotlin
val h1 = try rethrower(::danger) // `try` is required

fun safe(i: Int) : Int { return i }

val h2 = rethrower(::safe) // `try` must be omitted
```


# In short, here are my proposals: #
1. I propose that all 8 of the above ways to handle exceptions ([Call Sites A-H](#calls)) should be built into the language, without the aid of annotations andor libraries.
2. I propose that a compile-time error should occur if a function or closure which marked with `throws` is called without using one of the 8 above ways of handling said exception.
3. I propose that a compile-time error should occur if a function or closure containing a `throw` or unhandled `try` statement does not have `throws` (or `rethrows` if that exception might be thrown by a closure) in its declaration.

These should allow for fully-featured exception handling, and should provide peace-of-mind to the programmer knowing all exceptions are handled, without introducing the unruly clutter that Java does.

# Impact on existing code #
## New Keywords ##
`throws` and `rethrows` would be introduced, so there is the potential for it to break existing source code; any programmer-defined symbols named `throws` or `rethrows` would then fail to be recognized as symbols. However, since these can only appear in a function or closure `typealias` declaration, **they could be treated as a regular token in all other contexts.**
If that is too difficult (or impossible) to do with the current compiler, then **all current code that uses the token `throws` or `rethrows` will fail to compile.**

## Repurposed Keywords ##
The usage of the try keyword would have to be changed so that it is placed immediately before calling a function that throws, rather than at the start of a block like Java and current Kotlin.

The usage of the `do` keyword would have to be changed so that its block can be followed by a `catch`. This should be done in such a way that it _does not_ affect `do { ... } while` loops.

# Alternatives Considered #
1. Cause the stdib `@Throws` parameter to emit compile-time errors (or warnings) when functions using it are called outside a `try`-`catch` block, possibly typechecking against its arguments if any are provided. This would be the least-invasive change and provide peace-of-mind that thrown exceptions are caught, but none of the callsite benefits of the above proposals, such as creating an optional with `try?`.
2. Implement the same solution currently used in Java. This would be a familiar syntax to anyone moving from Java to Kotlin, but comes with heavily-typed baggage. This also has the same callsite downsides as alternative #1.
3. Only implement the `throws` and `rethrows` keywords and leave the callsite handling the same, like alternative #1. This would be the least-invasive language change, but has all the disadvantages of alternative #1.

---


> Note that I use "exception" and "error" interchangeably here. I know that errors aren't technically exceptions, but I like this phrasing better than "Throwable". Assume I always mean "Throwable" wherever I say "exception" or "error".

# Infinite loop

* **Type**: Design proposal
* **Author**: Vadim Mishenev, Roman Elizarov
* **Status**: In Progress
* **Prototype**: In Progress
* **Issue**: [KT-27970](https://youtrack.jetbrains.com/issue/KT-27970/Support-an-infinite-for-loop)

## Summary

An [infinite loop](https://en.wikipedia.org/wiki/Infinite_loop) (also known as a “while true”) is an idiom that is widely used in Kotlin programming.

## Motivation / use cases


- Infinite loops are widely used to monitor user input or device activity. 
The idiomatic approach to reading all the lines from the input stream until it is over (until readLine functions returns null):

	```kotlin
	while (true) {
	    val line = input.readLine() ?: break
	    // process line
	}

	```
Or the while loop can be used for the main game frame which continues to get executed until the user or the game selects some other event. 
- Infinite loops also appear quite often in concurrent programming with coroutines, because various concurrent background processes are often conveniently represented as an infinite loop doing something until cancelled.






The proposal is to support infinite loop via `while { ... }` statement without parameters or conditions, which seems to read nicely for the case of infinite loop, changing the motivating example to:

```kotlin
while {
    val line = input.readLine() ?: break
    // process line
}

```
But it can look like that user forgot to write a condiftion.


In other languages:

* Go (Golang) has `for { ... }` - loop. Probably `for` stems from `repeat forever`.

* In Rust there is `loop { ... }`.

* In C# there was a [proposal](https://github.com/dotnet/csharplang/issues/2475), but the discussion was shut down.

and so on (Ada, Ruby, Fortran). 

Infinite loops can be implemented via a function (like `repeat` in StdLib), but it will not support `break`/`continue`. So this feature is shortest way to support it.


## Type inference

Currently, infinite loops cannot be properly used inside scope functions. The following code does not compile due to type mismatch, since while is not an expression and the resulting type of run coerces to Unit:

```kotlin
private fun foo(): Nothing = // ERROR: Type inference failed. Expected: Unit
    run {
        while (true) {
            doSomething()
        }
    }
```

Infinite loop, on the other hand, shall be an expression of `Nothing` type (similar to `throw` to mark code locations that can never be reached) so the following code should compile:

```kotlin
private fun foo(): Nothing = // ERROR: Type inference failed. Expected: Unit
    run {
        while {
            doSomething()
        }
    }
```
But an inifinite loop with `break` is an expression of `Unit` type.

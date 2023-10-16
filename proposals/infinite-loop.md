# Infinite loop

* **Type**: Design proposal
* **Author**: Vadim Mishenev
* **Contributors**: Roman Elizarov
* **Status**: In Progress
* **Prototype**: In Progress (inferring `Nothing` is implemented)
* **Issue**: [KT-27970](https://youtrack.jetbrains.com/issue/KT-27970/Support-an-infinite-for-loop)

## Summary

This KEEP introduces a new expression `for { ... }` to indicate an [infinite loop](https://en.wikipedia.org/wiki/Infinite_loop) (also known as a “while true”).

## Motivation

Kotlin leads to be laconic programming language, and an infinite loop `while(true) { .. }` (rare `do { .. } while(true)`) might be expressed more concisely. 
Besides, a dedicated expression makes an infinite loop immediately understandable.
This usually results in more readable code.

### Use-cases

Infinite loops is an idiom that is widely used in Kotlin programming. 
In Kotlin the percentage of `while(true)` among all written `while` loops is 19% (in 1.2M repositories from the BigCode tool).

- Infinite loops are widely used to monitor user input or device activity. 
The idiomatic approach to reading all the lines from the input stream until it is over (until `readLine` function returns null):

```kotlin
while(true) {
    val line = input.readLine() ?: break
    // process line
}

```
Or the while loop can be used for the main game frame which continues to get executed until the user or the game selects some other event. 

- Infinite loops also appear quite often in concurrent programming with coroutines, because various concurrent background processes are often conveniently represented as an infinite loop doing something until cancelled.

- It is often used along with exit conditions / jump expressions at the middle of a body loop.
with `when` condition:

```kotlin
while(true) {
    when(await().resultCode) {
        RESULT_OK -> break
        RESULT_CANCELED -> {
            finish()
            return false
        }
        else -> continue
    }
}
```
into a `try-catch` block:

```kotlin
while(true) {
    try {
        // repeated process
    } catch(e: InterruptedException) {
    	break;
    }
}
```


The list of use cases is not exhaustive.
In general, an infinite loop is a common form, and usual loops (`while`, `do-while`) with a condition can be rewritten with it and vice versa.


### Other languages

* Go (Golang) has `for { ... }` - loop. Probably `for` stems from `repeat forever`.

* In Rust there is `loop { ... }`.

* In C# there was a [proposal](https://github.com/dotnet/csharplang/issues/2475), but the discussion was shut down.
Summary: The community does not want to encourage the use of infinite loops. In their opinion, it is better to have loops with a condition or flag that can explain a reason of termination.
`while{ .. }` looks like a user accidentally forgot to type the condition or accidentally removes it (say cut/paste instead of copy/paste) and ends up with valid code that represents an infinite loop.

and so on (Ada, Ruby, Fortran). 

Remarkably, Rust, Go, Ruby do not have `do-while` loop. So infinite loop can be a way to write it in these languages.


## Design

The proposal is to support infinite loop via the concise `for { ... }` construction without parameters or conditions. The curly braces `{ ... }` are required.
It should be used as expression with the result type `Nothing`, but if a loop has a `break` expression, the result type of expression should be `Unit`.


## Statement or expression

### Other languages

In Golang infinite loop `for { ... }` is a statement. (see [The Go Language Specification: for clause](https://go.dev/ref/spec#ForClause)).

On the other hand, in Rust  `loop { ... }` is an expression. (see [The Rust Reference: infinite loops](https://doc.rust-lang.org/reference/expressions/loop-expr.html#infinite-loops))
A loop expression without an associated break expression has type `!`([Never type](https://doc.rust-lang.org/reference/types/never.html))
Meanwhile, `break` expressions of Rust can have a [loop value](https://doc.rust-lang.org/reference/expressions/loop-expr.html#break-and-loop-values) only for infinite loops, e.g.

```rust
let result = loop {
    if b > 10 {
        break b;
    }
    let c = a + b;
    a = b;
    b = c;
};
```

### Type inference in Kotlin

Currently, in Kotlin infinite loops cannot be properly used inside scope functions. The following code does not compile due to type mismatch, since `while` is not an expression and the resulting type of run coerces to Unit (see [KT-25023](https://youtrack.jetbrains.com/issue/KT-25023/Infinite-loops-in-lambdas-containing-returnlabel-dont-coerce-to-any-type)):

```kotlin
fun foo(): Nothing = // Should be compiled, but Error: Type mismatch: inferred type is Unit but Nothing was expected
    run {
        while (true) {
            doSomething()
        }
    }
```
Infinite loop shall be an expression of `Nothing` type (similar to `throw` to mark code locations that can never be reached) so this code compiles.
Meanwhile, the type should be `Unit` if a loop contains `break`. 
Despite `return` breaking a loop, it does not make code after an infinite loop reachable so the expression type can be `Nothing`. It allows to compile the following code:

```kotlin
val x = run<Int> {
        while (true) {
            return@run 1 // Error: Type mismatch: inferred type is Unit but Int was expected
        }
    }
```
Moreover, the porpose infinite loop `for { ... }` with `Nothing`/`Unit` types is backwards compatible with old code.

### Should existing loops in Kotlin be expresions?

IDE can have an intention to transform loops with `true` condition into proposed infinite loops. It could make sense to make existing loops with a condition (`do-while`, `while`) expressions.
But it breaks backward compatibility with already written code, for example:

```kotlin
fun foo() = run {
  while (true) {
    doSomething()
  }
}
```
After this changing, `foo` will have `Nothing` type instead of `Unit`. Also, it will cause the compiler error `'Nothing' return type needs to be specified explicitly`.


### Functions with expression body

 `return` is also frequently used to exit from an infinite loop, but `return`s are not allowed for functions with expression body in Kotlin, e.g.

```kotlin
fun test() = while (true) {
                return 42 // Error: Returns are not allowed for functions with expression body. Use block body in '{...}'
            }
```
This problem can be solved by `break` with a loop value like in Rust. But it deserves another proposal.

## Feature versus stdlib function

Infinite loops can be implemented via a function (like `repeat` in StdLib), but it will not support `break`/`continue`. So this feature is the shortest way to support it.
[KT-19748](https://youtrack.jetbrains.com/issue/KT-19748/Provide-some-sort-of-break-continue-like-mechanism-for-loop-like-functions) solves this problem for a such function and loop-like functions (`forEach`, `filter`, etc). But there are some disadvantages of this feature:

- It seems to be very specified only for stdlib's functions.
- Existed local `return` can become misleading with a labelled `break`/`continue` expression. A user can expect a local `return` should exit a loop-like function at all. It might need to change the behavior of local `return` for loop-like function.


```kotlin
    (0..9).forEach {
        if (it == 0) return@forEach // currently, it has the same behavior as `continue@forEach`
        println(it)
    }
```


- For a function of infinite loop inside a loop (`for`, `while`), it might require extra labels that make code verbose.
See [KEEP-326](https://github.com/Kotlin/KEEP/issues/326): 
an unlabeled `break`/`continue` goes to the innermost enclosing block that is clearly marked with `for`, `do`, or `while` hard keywords:

```
for (elem in 1..10) {
	(1..10).someInfiniteLoopFunction {
	    println(it)
	    if (it == 5) break@someInfiniteLoopFunction
	}
}
```

- The implementation of the feature is more complicated than infinite loops.


## Which keyword is used for infinite loops? 

Main candidates of the keyword:

- `while { ... } ` 
- `for { ... }` 
	The Golang (Go) uses it.  Probably `for` is shortened to `forever`. 
- `do { ... }` But `do {} while(...) {}` can be ambiguous so for a user. A user should check the end of a loop to differ `do-while` and `do` loops.
- `loop { ... }`
	This keyword is used Rust for infinite loops.
- `repeat { ... }`


The plus of using words `while`, `do`, `for` is that they are existing keywords. Users are already familiar with their semantics. 
Otherwise, introducing a new keyword increases syntax complexity of the language. 
Also, it can break backward compatibility since old code can have a user function with the same name as the keyword.

`while { ... } ` can look like that a user forgot to write a condition. At the same time, unlike othe languages, Kotlin does not have a free-form condition for `for` loop.


The existing keywords `for` and `while` require `{ ... }` for an infinite loop body.
Contrariwise, for example, the following code becomes ambiguous:

```kotlin
for (x in xs);
```
It can be treated either as a for loop with a loop variable `x` and an empty body or as an infinite loop which evaluates expression `(x in xs)`.


To sum up, `for` is the best choice for an infinite loop. It does not require intruduction a new keyword and do not have the problem with forgotten condition like `while`. 
It seems to read nicely for the case of an infinite loop, changing the motivating example from `Use-cases` section to:

```kotlin
for {
    val line = input.readLine() ?: break
    // process line
}

```
Also, factoring this form both away from and back to having a condition is natural.



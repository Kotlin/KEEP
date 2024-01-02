# Non-local break and continue

* **Type**: Design proposal
* **Authors**: Nikita Bobko, Pavel Mikhailovskii
* **Contributors**: Alexander Udalov, Roman Elizarov, Simon Ogorodnik
* **Status**: Experimental since 1.8.20, stable since 1.9.0
* **Discussion and feedback**: [KEEP-326](https://github.com/Kotlin/KEEP/issues/326)
* **YouTrack issues:** [KT-1436](https://youtrack.jetbrains.com/issue/KT-1436),
  [KT-19748](https://youtrack.jetbrains.com/issue/KT-19748), [KT-54906](https://youtrack.jetbrains.com/issue/KT-54906)

## Introduction

One of the key features of Kotlin inline functions is that lambdas passed as arguments to them can contain
[non-local return statements](https://kotlinlang.org/docs/inline-functions.html#non-local-returns) which jump over
the lambda's boundaries and exit from the enclosing function. However, a similar non-local behavior is not yet implemented
for `break` and `continue` statements.

```kotlin
var firstNumber: Int
for (file in files) {
    file.bufferedReader().use { reader ->
        firstNumber = reader.readLine()?.toInt() ?: continue
        //                                          ^^^ 'break' or 'continue' jumps across a function or a class boundary
        break
     // ^^^ 'break' or 'continue' jumps across a function or a class boundary
    }
}
```

## Proposed change

Make it possible to use non-local (applied to a loop belonging to the enclosing function) `break` and `continue`
statements within lambdas passed as arguments to an inline function.
Similarly to non-local returns, non-local `break` and `continue` could only be used in lambdas passed
to parameters without `noinline` or `crossinline` modifiers.

```kotlin
@outer for (department in departments) {
    for (employee in department.employees) {
        employee.apply { // We're inside an inline lambda now
            if (isRetired) continue // Unlabeled break/continue works with the closest enclosing for/while
            if (position == "Developer" && age > 70) {
                println("We have some senior developers in our organisation!")
                break@outer
            }
        }
    }
}
```

## Motivation

Inline functions with [trailing lambdas](https://kotlinlang.org/docs/lambdas.html#passing-trailing-lambdas) are designed in a way
that allows using them as if they were first-class language constructs (e.g. `synchronized` or `run`, `with`, `let` scope
functions from stdlib).

Since users can invoke `return`, `break` or `continue` inside of bodies of native language constructs (such as body of `if` or
body of `try-catch`), it would be only logical to allow the same for lambdas passed as parameters to inline functions. At the
moment, only non-local `return`s are supported. That's why we are proposing to add support for non-local `break`/`continue` as
well.

So the motivation is the following:

1. Make inline lambdas truly first-class language constructs that are seamless to use.
2. Fix consistency with non-local `return`. It's inconsistent that currently we allow non-local `return` statements but don't
   allow non-local `break` and `continue` statements.

## Design problems

This feature may interfere with another proposed [feature](https://youtrack.jetbrains.com/issue/KT-19748),
allowing to use `break`/`continue` not only inside loop statements, but also within lambda arguments
of loop-like functions like `forEach` or `takeWhile`
(the notion of a loop-like function would require a strict definition; we leave this question open for now).

```kotlin
for (i in 0 until 100) {
    (0 until 200).forEach {
        if (it == 42) break // Should it break from `for` or `forEach`?
        if (i == 10) continue // Where to continue?
    }
}
```

However, it seems that both features can be implemented without introducing ambiguities.

First of all, we should never allow unlabeled `break`/`continue` for loop-like functions because users
wouldn't be able to understand whether it is applied to a certain function without checking its implementation.
```kotlin
foo { bar { break } } // which function is loop-like?
```
It leaves us only with the labeled syntax option for loop-like functions:
```kotlin
for (i in 0 until 100) {
    (0 until 200).forEach {
        if (it == 42) break@forEach // Obviously, it breaks `forEach`
        if (i == 10) continue // No label, so it applies to the 'for' loop
    }
}
```

A similar rule applies to `return` statements: an unlabeled `return` within a lambda is always non-local, a labeled one returns to the label.
In other words, an unlabeled `return` goes to an innermost enclosing block that is clearly marked with `fun` hard keyword.
In a similar way, an unlabeled `break`/`continue` goes to the innermost enclosing block that is clearly marked with `for`, `do`, or `while` hard keywords.

## Tooling for puzzling code

Even if we don't implement support for `break`/`continue` for loop-like functions,
some users may find constructions containing `break` or `continue` within lambda arguments of loop-like inline functions puzzling:
```kotlin
fun printUntilZero(producer: () -> List<Int>) {
    while(true) {
        val list = producer()
        list.forEach {
            if (it == 0) break // Does it exit from while or from forEach?
            println(it)
        }
    }
}
```

To reduce the risk of confusion, it was proposed to introduce an IDE inspection and a quickfix recommending to use labeled `break`
and `continue` in lambdas passed to parameters without an `EXACTLY_ONCE` or `AT_MOST_ONCE` contract (potentially loop-like).
After the inspection is applied:
```kotlin
fun printUntilZero(producer: () -> List<Int>) {
    myLoop@ while(true) {
        val list = producer()
        list.forEach {
            if (it == 0) break@myLoop
            println(it)
        }
    }
}
```

It will typically be used only with stdlib, and we must be sure it is not triggered on the usual scope functions.
It is not bad if it is triggered on a user-defined function without a contract.
It is better to be safe (by suggesting to use labelled break/continue) than end up with a more ambiguous code on a user-defined loop-like function.

The inspection shouldn't be triggered for `let`, `run`, etc., as they all have the corresponding contract.
There seems to be no need for a similar inspection for non-local returns. We assume that they are less confusing.

### Why an IDE warning instead of a compilation error

Since by allowing non-local `break` and `continue`, we introduce this new puzzler, one may wonder why not make it a
compilation error instead of an IDE inspection. The problem is that the compiler can't reliably detect jumps over lambdas of loop-like
functions because Kotlin doesn't have a concept of loop-like functions.

First of all, while it's totally fine the proposed IDE inspection would use a heuristic that is based on the `AT_MOST_ONCE`/`EXACTLY_ONCE` contract, 
compiler errors can't be based on a heuristic. Another objection is that contracts remain an experimental feature, 
and we shouldn't force people to use an experimental feature if they decide to use non-local `break`/`continue` with user-defined inline functions.

Instead, we could be more restrictive and make labels mandatory for non-local `break` and `continue`. But we can't do that because
it would mean that we would need to forbid unlabeled non-local `return` for consistency. It would not only break a lot of existing
code (e.g. 1212 occurrences in IntelliJ monorepo), but it would also break inline functions' first-class citizenship as language
constructs. E.g. we value "return if not null" idiomatic Kotlin construct `nullable?.let { return it }`.

## Issues with data-flow and control-flow analyses

The proposed change breaks control-flow analysis in K1 (see [KT-54906](https://youtrack.jetbrains.com/issue/KT-54906)).
That issue in non-trivial, so that fixing in K1 doesn't look like a reasonable option at the moment.
This means that we have to postpone finalization of the feature until K2 is ready.

So far, testing of the prototype against K2 hasn't revealed any CFA/DFA-related issues.

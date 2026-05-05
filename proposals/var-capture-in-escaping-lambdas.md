# Warn on capture of mutable variables by escaping lambdas

- **Type**: Design proposal
- **Authors**: Yuliya Karalenka, Michail Zarečenskij
- **Contributors**: Marat Akhin, Alejandro Serrano Mena, Komi Golova, Roman Venediktov, Mikhail Vorobev
- **Status**: Internal review
- **Discussion**: TBD
- **Related YouTrack issue**: [KT-15514]([https://youtrack.jetbrains.com/issue/KT-15514](https://youtrack.jetbrains.com/issue/KT-15514))

## Abstract

In Kotlin, the default mechanics for argument passing and lambda variable capturing differ, which often breaks developer intuition. While function arguments are passed **by sharing**, lambdas capture local variables (`var`) by reference. The problem becomes visible if the lambda is escaping (not `inline` and not marked as `callsInPlace`) and the variable is reassigned in a different non-inplace scope. This leads to unexpected bugs and developer confusion.

We propose a compiler warning for mutable local variables (`var`), which is not stable at the moment, when it is captured by escaping lambdas.

## Table of contents

- [Abstract](#abstract)
- [Table of contents](#table-of-contents)
- [Motivation](#motivation)
  - [Motivational examples](#motivational-examples)
  - [Detailed description](#detailed-description)
  - [Comparison with other languages](#comparison-with-other-languages)
- [Problem statement](#problem-statement)
- [Non-goals](#non-goals)
- [Model](#model)
- [Specific rules](#specific-rules)
- [What should be highlighted by the warning?](#what-should-be-highlighted-by-the-warning)
  - [Chosen approach: highlight the captured variable](#chosen-approach-highlight-the-captured-variable)
  - [Considered options](#considered-options)
- [Migration](#migration)
- [How to react to a warning?](#how-to-react-to-a-warning)
  - [Add callsInPlace contract](#add-callsinplace-contract)
  - [Make snapshot of variable](#make-snapshot-of-variable)
- [What if capture-by-reference is intentional?](#what-if-capture-by-reference-is-intentional)
  - [Chosen approach: new annotation on variable declaration](#chosen-approach-new-annotation-on-variable-declaration)
  - [Considered options](#considered-options-1)
- [Alternative design options](#alternative-design-options)

## Motivation

### Motivational examples

**Problem:** If a mutable variable is changed **after** a lambda captures it, but **before** the lambda runs, the lambda will use the **new** value.

#### Example 1

It's possible to find questions about this behaviour on Stack Overflow: [example](https://stackoverflow.com/questions/44747175/in-kotlin-how-do-i-pass-a-parameter-so-that-the-async-scope-will-conserve-it/44796809).

Let's look more precisely at the following example.

The `seed` variable is captured by reference and the reassignment `seed = 4` executes before the `lazy` execution. The result is 8, not the value that might be expected at first glance (6).

```kotlin
var seed = 3

val deferredResult = lazy {
        seed * 2
}

seed = 4

val result = deferredResult.value
println("Result is $result")

// Output: Result is 8.
```

#### Example 2

The problem becomes even trickier in concurrent code. In some cases it can be impossible to determine which line will execute first, and it can change depending on the run. So seemingly safe reassignments lead to errors that are extremely difficult to find.

Let's look at the provided example with concurrency.

```kotlin
suspend fun forward(inp: ReceiveChannel<Int>, ch: SendChannel<Int>) {
    while(true) {
        val i = inp.receive()
        ch.send(i)
    }
}

fun main() = runBlocking {
    var ch = Channel<Int>() // Create a new channel
    launch { for (i in 0..9) ch.send(i) } 
    
    for (i in 0..9) {
        println(ch.receive())
        val ch1 = Channel<Int>()
        launch { forward(ch, ch1) }
        ch = ch1
    }
    coroutineContext.cancelChildren()
}
```

- The line `launch { filter(ch, ch1, prime) }` starts a new coroutine. The lambda captures `ch` by reference.
- Immediately after, the main thread reassigns the variable: `ch = ch1`

The program can hang indefinitely because `ch = ch1` happens before the lambda executes. Then the `forward` function tries to read and write at the same time to the channel `ch1` and suspends, waiting forever.

This tricky example can be fixed by making a new, local `val` to hold the channel's value. It will be passed in the `filter` function instead of `ch`.

```kotlin
val ch0 = ch
launch { forward(ch0, ch1, prime) }
ch = ch1
```

### Detailed description

Let's look at the problematic code in more detail. During **lambda creation** (1), the lambda captures a reference to `ch`. During **lambda execution** (2), the current value of `ch` is passed to `forward`, where it becomes an immutable parameter.

So the bug occurs if **reassignment** (3) runs before **lambda execution** (2). In that case, the lambda observes the reassigned value. Otherwise, the outer reassignment would not be visible to that execution.

```kotlin
launch {(reference to ch) // (1) lambda creation 
    forward(ch (copy of reference to ch), ch1, prime) // (2) lambda execution
}
ch = ch1 // (3) reassignment
```

### Comparison with other languages

To avoid ambiguity, the following definitions are used throughout:

- **Pass by reference** - passing reference to object
- **Pass by sharing** - passing copy of reference to object
- **Pass by value** - passing full copy of value

The direct translation of the second example into Go does not fail:

```go
go filter(ch, ch1, prime)
ch = ch1
```

The difference is that in Go, `go filter(ch, ch1, prime)` evaluates and copies reference of `ch` into the goroutine call (**pass by sharing**). So, reassigning `ch` will not be seen inside goroutine. That shows that deferred execution itself is not the whole problem.

To understand what causes this confusion, let's take a look at passing rules in other languages.

#### Go

The main way to pass variables into lambdas in Go is **by sharing**.

```go
go func(name string) {    
    fmt.Println("Hello, " + name) 
}(name) 
```

Note: It is possible to **capture** variables **by reference**. Then the lambda will observe changes to the variable in the outer scope. However, this is a less common approach.

```go
go func() { 
    fmt.Println("Hello, " + name) 
}()
name = "..."
```

#### Java

In Java, parameters **pass by sharing** in named functions. And the lambda capturing rules are a little bit stricter. The captured variable must be effectively final to prevent value inconsistency between the copy and the original. So variables cannot be reassigned.

Therefore, both lambda capturing and parameter passing behave like **pass by sharing**.

#### C++

In C++, the following rules apply for lambda capturing and parameter passing.

Explicit choice:

- `[=]` full copy
- `[&]` reference
- `[x = std::move(obj)]` **capture by move** (transfers ownership into the lambda's scope).

```cpp
// value - full copy    
auto lambda = [value, &reference]() {
        std::cout << value; 
        std::cout << reference;
 };

void printValues(int value, int& reference) {
    std::cout << value;
    std::cout << reference;
}
```

As shown in the example, in C++ same syntax provides the same behavior for capturing and parameter passing.

#### Kotlin

As we can see, in languages above default ways of passing variables in anonymous function and in named function are similar.

But in Kotlin, we encounter inconsistency. In a regular call in Kotlin, the callee receives the value computed at the call site (**pass by sharing**). Developers often carry that intuition into deferred execution and expect `launch { use(x) }` to mean "run use later with the value of x that existed here." But anonymous functions capture variables **by reference**, so later reassignments remain visible when the lambda eventually runs.

Also, there is only one way to pass variables into anonymous functions the same way they are passed to named functions: create a new `val` and copy the value into it.

This leads to the following conclusion. The mismatch in Kotlin and the absence of familiar syntax for capturing variables **by sharing** is a big part of confusion.

## Problem statement

Looking at the examples mentioned above, it becomes clear that confusion occurs when the following three conditions are met:

- **Escaping lambda:** Confusion arises when the execution order is non-linear. In particular, the problem appears when lambda creation and lambda execution do not happen at the same time, so it is unclear when a captured mutable variable will be read. This happens through escaping lambdas. Currently, all lambdas in Kotlin are considered escaping by default unless they have a `callsInPlace` contract.
- **Visible reassignment:** There must be a visible reassignment located in a non-in-place node (outside the immediate synchronous flow). This reassignment may change the value observed by the lambda. As a result, different outcomes are possible depending on whether the read is expected to happen at creation time or at execution time. The first motivational example demonstrates this clearly.
  ```kotlin
  var seed = 3
  val deferredResult = lazy { // lambda creation, seed = 3
          seed
  }
  seed = 4
  println("Result is ${deferredResult.value}") // lambda execution, seed = 4
  ```
- **Conflicting default:** The developer expects that default behaviour will be the same as in variable passing. In other words, they expect the read to happen at lambda creation time, not at lambda execution time.

Our goal is to report a warning on captured variables that meet the conditions described above. This pattern produces value inconsistency (expectation that lambda will use the old value) which leads to bugs that are hard to localize. Because the cause of the bug is split across two distant parts of the code: capture point (where variable is captured) and reassignment.

## Non-goals

### Data race detection

It's important to note that it is not a goal to detect all concurrency/race issues.

In the following example, there will be no warning, despite the fact that this code can potentially produce a data race. Even so, it does not produce the kind of confusion this analysis is designed to detect, because no read can observe a surprising value.

```kotlin
var r = 1
escapingFun {
	r = 2
}

escapingFun {
	r = 3
}
```

However, if a "read" is introduced alongside a visible reassignment, a warning would be reported.

```kotlin
var r = 1
escapingFun {
    println(<!WARNING!>r<!>)
    r = 2
}

escapingFun {
    r = 3
}
```

### Preventing mutability

Also, this proposal is not against usage of `**var**`. The goal is to ensure that if a `**var**` is captured in escaping lambda, the timing of reassignments is explicit and intentional.

### Breaking changes

We propose a **warning**, not a compilation error. We do not intend to break existing codebases or forbid this pattern entirely: some code produces tricky behavior, but remains simple enough not to be prohibited. The goal is to provide better analysis for a known source of bugs.

## Model

We propose introducing a compiler warning for mutable local variables (`var`) that are captured by escaping lambdas.

The core principle is that a read of the captured variable should observe visible reassignment from a different non-inplace node to trigger a warning.

This also can be reformulated as escaping lambda needs a unique right to reassign reference of captured mutable variables from the moment they are captured. So, before capture, the variable has a certain reference, if there is a possibility that the reference has changed before lambda execution will end, a warning should be shown. This model allows us to prevent the discussed trap.

```kotlin
var x: String? = "hello"
// x has a reference
escapingFun {
      println(x.length) // warning, visible reassignment [x = null]
}
x = null // visible reassignment from a non-inplace node
```

The current model is based on an existing analysis of variable lifetimes. The analysis proceeds in two phases. In the first phase, reassignments are propagated backward through the graph using BFS traversal until the variable declaration. The second phase collects visible reassignments from non-inplace nodes.

## Specific rules

- **Escaping Lambdas Only:** The warning is applied to escaping lambdas. In `callsInPlace` lambdas, creation and execution happen together, so the confusion is not observable. In addition, lambda arguments of inline functions are `callsInPlace` by default.
- **Lambdas Only:** The warning is limited to lambdas. Capturing in local classes or functions is allowed as there's no escaping and there's a natural way to pass variables as regular arguments.
In the example below, `i` is passed as an explicit argument, whereas `l` is captured **by reference**.
  ```kotlin
  fun namedFunction() {
      var l = 2
      class Local {
          constructor(i: Int) {
              val result = i + l
              println("Captured result: $result")
          }
      }
      Local(10) // Captured result: 12
      l = 5
      Local(10) // Captured result: 15
  }
  ```
- **Exclude effectively immutable variables:**
We consider a variable *[effectively immutable](https://kotlinlang.org/spec/type-inference.html#effectively-immutable-smart-cast-sinks)* if there are no nested reassignments and all direct reassignments precede the capture point in the Control Flow Graph.
To put it simply, variable reassigning before the lambda doesn't trigger the warning.
  ```kotlin
  var x = "Hello"
  x += ", " + name
  someFunction(true) { 
    println(x.length) 
  }
  ```
- **Exclude cases where captured variable is used only inside the lambda**
The warning is not reported when all reads and writes of the variable occur in the escaping lambda.
In this pattern, the variable acts as a private field of the lambda and doesn't bring the confusion:
  ```kotlin
  var i = 0
  val progressId = window.setInterval({
      output.textContent = progress[i]
      i = (i + 1) % progress.size
      null
  }, 100)
  ```
- **Exclude output-only captures**
The warning is not reported when the variable is reassigned inside the escaping lambda and is only read outside it.
The reason we consider it a valid behavior is that the lambda captures a variable which won't be unexpectedly changed before the execution of such a lambda.
A common case is "local lateinit val"-like pattern, which is used in tests. In these cases, a variable is declared with the intent of being initialized exactly once before the target function executes.
Example: [link](https://github.com/ktorio/ktor/blob/kotlin-community/dev/ktor-client/ktor-client-tests/common/test/io/ktor/client/tests/ResponseObserverTest.kt#L129), [link](https://github.com/Kotlin/kotlinx.coroutines/blob/kotlin-community/dev/test-utils/common/src/MainDispatcherTestBase.kt#L126)
  ```kotlin
  @Test
  fun testLaunchInMainScope() = runTestOrSkip {
      var executed = false
      withMainScope {
          launch {
              checkIsMainThread()
              executed = true
          }.join()
      }
      if (!executed) throw AssertionError("Should be executed")
  }
  ```

This exclusion keeps the warning focused on cases where a mutable local flows **into** an escaping lambda and later outer writes make the observed value depend on execution order.

## What should be highlighted by the warning?

### Chosen approach: highlight the captured variable

The rule of thumb for a good warning is to highlight a place that typically should be changed. In our case, it's the captured variable as the expected fix is to either introduce a new local `val`, or to make the lambda `callsInPlace`.

```kotlin
var seed = 3
val deferred = lazy { seed * 2 } // WARNING: captures 'seed' with a later reassignment
seed = 4       
```

Trade-off: If a lambda captures several unstable mutable variables, several variables may be highlighted. This is acceptable because each highlight corresponds to a separate source of risk. Also, there are no links to reassignment, which is ok, because we don't want to make this warning noise.

### Considered options

#### Highlight the captured variable + reassignment

**Pros**: Very explicit: shows both the capture point and the later reassignment.

**Cons**: Noisier: one capture may produce multiple warnings on later writes.

#### Highlight the lambda + nested reassignments

Message example: _"This escaping lambda captures mutable vars: seed, ch …"_

**Pros**: Scales well if the lambda captures several mutable variables.

**Cons**: Less precise: it is not immediately clear which captured vars are actually problematic.

## Migration

Although the problem seems quite narrow, our analysis of internal projects revealed near **4,5k** instances of this pattern. But the vast majority of warned variables (~45%) are captured by `callsInPlace` lambdas.

## How to react to a warning?

### Add callsInPlace contract

If lambda acts like non escaping, it should be declared with a `callsInPlace` contract. So the warning should not be reported.

Example: [link](https://code.jetbrains.team/p/kct/repositories/exposed_kct/files/kotlin-community/dev/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/Query.kt?tab=source&line=171&lines-count=1). It's clear that the `apply` function is `callsInPlace` `adjustWhere` can be `callsInPlace` too.

```kotlin
override suspend fun collect(collector: FlowCollector<Flow<ResultRow>>) {
    var lastOffset = if (fetchInAscendingOrder) 0L else null
    while (true) {
        val query = this@Query.copy().adjustWhere {
            lastOffset?.let { lastOffset ->
                ...
            } ?: whereOp
        }
        ...
        lastResult?.let {
            lastOffset = toLong(it[autoIncColumn]!!)
        }
    }
}

fun adjustWhere(body: Op<Boolean>?.() -> Op<Boolean>): Query {
    return apply { where = where.body() }
}
```

### Make snapshot of variable

If the lambda should observe the value from the capture point, introduce a new local `val` and capture that `val` instead of the original `var`. This makes the snapshot explicit and keeps later reassignments outside the lambda.

```kotlin
val currentCh = ch
launch { filter(currentCh, ch1, prime) }
ch = ch1
```

## What if capture-by-reference is intentional?

In some cases, code outside compiler analysis makes the execution order known, so capture-by-reference is intentional. In that case, developers need a clear way to say: **"Yes, I want this behavior."**

### Chosen approach: new annotation on variable declaration

The name `@AllowEscapingVarCapture` is only a placeholder; feel free to suggest a better name.

```kotlin
@AllowEscapingVarCapture
var seed = 3

val deferred = lazy { seed * 2 }
```

Note that the annotation can allow capturing variables even in potentially unsafe closures added later, after the annotation has been placed on the variable. However, we consider it OK, as the annotation helps bring attention to the fact that the code might be tricky and helps with debugging.

However, if variables participate in different escaping lambdas, it makes sense to mark the variable with an annotation to avoid mistakes in both places (as an example below, inspired by real code).

```kotlin
@AllowEscapingVarCapture
var startedAt: Long? = null

monitor.subscribe(ApplicationStarted) {
    val t0 = startedAt
    if (t0 != null) {
        val elapsed = getTimeMillis() - t0
        environment.log.info("Application started in $elapsed ms")
        startedAt = null
    }
}

monitor.subscribe(ApplicationStarting) {
    startedAt = getTimeMillis()
}
```

### Considered options

#### On lambda

```kotlin
val deferred = lazy(@AllowEscapingVarCapture { seed * 2 })
```

This is risky: the developer may believe the lambda always runs before a certain reassignment. Later, someone adds another captured variable, and a new "earlier" reassignment appears. The annotation on lambda stays and the bug becomes silent.

#### On visible nested reassignment

```kotlin
@AllowEscapingVarCapture 
seed = 4
```

**Pros**:

- Very local: the opt-in is attached to the exact write that should be reviewed carefully.
- It avoids silently approving future captures

**Cons**:

- Kotlin does not have annotation targets for assignments ([available annotation targets](https://kotlinlang.org/spec/annotations.html#annotation-targets)).
Note: A target for expressions exists, but Kotlin assignments are not expressions and cannot be used as such ([spec](https://kotlinlang.org/spec/statements.html#assignments)).
- Noisy: if there are many nested reassignments, the developer should mark all of them.

## Alternative design options

If Kotlin gets a capture list syntax (similar to in C++/Swift), it can make intent obvious and reduce the need for warnings:

```kotlin
// snapshot capture (swift-like) 
val deferred = lazy { [seed] in seed * 2 }
```

Another possible future direction is to support **compound `val` syntax** for lambdas. The idea is to use the same general style as a scoped `val` in `if`/`when`: introduce a new immutable name and make it visible only inside that block.

```kotlin
if (val foo = x.getFoo(); foo is String) {
    foo.length
}
```

Applied to lambdas, the same style could provide an explicit **snapshot capture** (`val` will be initialized at lambda creation time, not at lambda execution time):

```kotlin
lazy { val snapshot = seed -> // "captures" as a copy
	snapshot * 2
}
```


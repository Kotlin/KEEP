# Design Notes: Multishot continuations in Kotlin

- **Type**: Design notes
- **Author**: Nikita Bobko
- **Related YouTrack issues**:
  https://youtrack.jetbrains.com/issue/KT-79957,
  https://youtrack.jetbrains.com/issue/KT-68932

This is a small document that theoretizes on multishot continuations in Kotlin.

## Is there any practical use case for multishot continuations?

**Composable functions.**
Composable continuations are indeed a very restrictive version of multishot continuations.
But the Compose framework takes advantage of the domain and takes a few shortcuts.
See "Why Compose and Coroutines are not the same thing?"

**Backtracking / Error recovery.**
Multishot continuations can be used in algorithms for solving puzzles like Sudoku or backtracking in parsing problems,
where you may need to explore multiple possible solutions,
backtrack to previous states if a path proves to be incorrect, and rerun the continuation with a different input.

A particular example of parsus (https://github.com/alllex/parsus) was investigated.
Frankly speaking, I don't entirely understand what it does.
But whatever [continuation backtracking](https://github.com/alllex/parsus/blob/main/src/commonMain/kotlin/me/alllex/parsus/parser/ParsingContext.kt) it does,
it doesn't do multishot continuations.

**Some kind of undo/redo functionality.**
Coroutines can capture a unique state.

**Non-deterministic programming.**

**Flow comprehension.**
See the next section.

## Flow comprehension use case

Coroutines freed us from the callback hell of Java APIs.
Users no longer need to remember all these `thenCompose`, `thenAccept` operators but can program in a straight-line sequential style.
Unfortunately, `Flow` reintroduces this problem but on a one more higher level of abstraction
(also see https://elizarov.medium.com/callbacks-and-kotlin-flows-2b53aa2525cf)

That's how Coroutines saved us:

```kotlin
// Instead of this
val x: CompletableFuture<Int> = null!!
val y: CompletableFuture<Int> = null!!
val z: CompletableFuture<Int> = null!!

x.thenCompose { x1 -> // flatMap-like
    y.thenCompose { y1 -> // flatMap-like
        z.thenAccept { z1 -> // map-like
            println(x1 + y1 + z1)
        }
    }
}

// You can write this
val job1: Deferred<Int> = async { delay(100); 1 }
val job2: Deferred<Int> = async { delay(100); 2 }
val job3: Deferred<Int> = async { delay(100); 2 }
println(job1.await() + job2.await() + job3.await()) // [1]
```

\[1\] BTW, notice how uniform `await` calls are.
In the callback style, all the operators are `thenCompose` (aka "flatMap") except for the most inner `thenAccept` (aka "map").

This is how they can rescue us one more time to help us fight with callback hell and operator hell.

```kotlin
val a: Flow<Int> = null!!
val b: Flow<Int> = null!!
val c: Flow<Int> = null!!

//// Cartesian product

// Instead of this
a.flatMapConcat { x ->
    b.flatMapConcat { y ->
        b.map { z ->
            Triple(x, y, z)
        }
    }
}

// You can write this
flowComprehension {
    val x = a.multiCollect()
    val y = b.multiCollect()
    val z = c.multiCollect() // [2]
    emit(Triple(x, y, z))
}

//// "zip" 3 values

// Instead of this
a.zip(b).map { (x, y) -> x + y }.zip(c).map { (x, y) -> x + y } // Flow<Int>

// You can write this
flowComprehension {
    val x = a.multiCollect() // Subscribe to the Flow. Pull all elements one-by-one
                             // Multishot continuation
    val y = b.pull() // Don't subscribe to the Flow. Pull just one element from the flow,
                     // but keep the flow alive. Single-shot continuation
    val z = c.pull()
    emit(x + y + z)
}
```

\[2\] `multiCollect` is the suspend function that calls its continuation every time a new element is available (effectively, it's a way to subscribe and collect all the elements from the Flow).
Notice how uniform `multiCollect` are. Contrary, in the callback style, all the operators are `flatMapConcat` except for the innermost `map`.

Multishot continuations allow you to write code in an arguably more aesthetically pleasing style, but it's unclear whether it's desirable to write code in this style.
It was desirable to kill callbacks and write code sequentially when coroutines replaced `CompletableFuture` back at that time, because callbacks represented a sequential chain of single-shot callbacks.
It's not the case with `Flow`s.

Callbacks in Flow operators are invoked several times.
The code is not sequential but written sequentially.
This may be puzzling, especially combined with the fact that coroutines calls on the call site are indistinguishable from regular calls (e.g. in Swift forces you to always write `await`. In Kotlin, we rely on coroutine gutter).

**Idea:**
I think it's fine that we don't require any kind of `await` keyword on the call-site in Kotlin (One can even emulate Swift's await keyword by using RestrictsSuspension in Kotlin).
But to make the distinction between multishot and single-shot continuations clear we could force users to write some kind of keyword in front of `multiCollect` since it's a one-level-higher abstraction.
I'd call the keyword `reactive` or something.
Though this approach raises "a problem."
It would mean that `@Composable` function invocations have to be annotated with this keyword.

### Molecule

Interestingly, the community has invented multishot-collect specifically for StateFlow by abusing the Compose framework (https://github.com/cashapp/molecule).
It works due to the fact that StateFlow is a special kind of Flow which doesn't explore "different universes" but only replays the last element of the Flow.

The above cartesian product code translates to the Molecule framework in the following way.
Unfortunately, it works only with StateFlow (for understandable reasons), so technically it's not a cartesian product :(

```kotlin
scope.launchMolecule(mode = ContextClock) {
  ExtractedForClarity(a, b, c)
}

@Composable // Yes, it's Composable instead of suspend
fun ExtractedForClarity(
    a: StateFlow<Int>,
    b: StateFlow<Int>,
    c: StateFlow<Int>
): Triple<Int, Int, Int> {
    val x = a.multiCollect()
    val y = b.multiCollect()
    val z = c.multiCollect()
    return Triple(x, y, z)
}
```

Apparently, we can emit only one element at a time since we don't have `suspend fun emit()` function.

## Multishot continuation problems

Problematic state management.

Kotlin has reference semantics, and, in general, it's not possible to do a deep copy of the continuation.
Thus, different "universes" will mutate/operate-on the same state.
This model makes sense for some restricted domains (e.g., Compose),
but it's unclear whether it has applications besides "unpredictable UI redraw."
E.g., shared mutable world discards the whole class of "non-deterministic programming" applications.

- Operating on mutable state becomes a nightmare.
  In Compose, it's not as severe because Compose runs only a single "asynchronous branch" at a time.
  See `6c7a978f` below.
  In general-purpose multishot continuations, users won't even be able to use mutable objects that "cross" suspension points.
- If all the "universes" create the same big object, it will be created and destroyed several times (In Compose, it's possible to reuse the same object with `remember`). Far-fetched?
- Feature interaction with `try-catch-finally`? (Shall `finally` be executed only once, or as many times as continuation is invoked? BTW `try` is unsupported with `@Composable` functions)
- Feature interaction with loops? Hard to reason about suspend calls inside loops (but not impossible)

## Why Compose and Coroutines are not the same thing

The Compose framework takes advantage of the domain.
It is intended to draw a UI.
The UI is often redrawn.
Compose framework can make unpredictable recompositions,
execute recompositions of composables in different orders,
or recompositions can be discarded (https://developer.android.com/develop/ui/compose/side-effects) Users are aware of all of it (or at least they are supposed to be),
and write code accordingly.

### Technical difference. Simpler "suspension point" implementation

Compose avoids the complications of state-machine implementation and code transformations. It abuses the fact that it can rerun continuations several times.

```kotlin
@Composable fun Root() {
    println("recompose 1")
    Text("first")

    var state by remember { mutableStateOf(false) }
    println("recompose 2")
    Text("hi $state")
    Button(onClick = { state = !state }) { Text("hi") }
}
```

Clicking the button will lead to "rerunning the whole `Root` function:

```
--- recompose 1
--- recompose 2
```

But if a user extracts it to a separate function:

```kotlin
@Composable fun Root() {
    println("recompose 1")
    Text("first")
}

@Composable
fun Child() {
    var state by remember { mutableStateOf(false) }
    println("recompose 2")
    Text("hi $state")
    Button(onClick = { state = !state }) { Text("hi") }
}
```

Clicking the button will rerun only `Child` function.

Real, general-purpose multishot continuations can't afford this.
But technically nothing prevents implementing Composable functions "in the right way",
and calling them multishot continuations

### Technical difference. Memory management

```kotlin
@Composable fun Root() {
    var count by remember { mutableStateOf(0) }
    Text("$count")
    var visible by remember { mutableStateOf(true) }
    Button(onClick = { visible = !visible }) { Text("Toggle") }
    Button(onClick = { count++ }) { Text("Redraw") }
    if (visible) {
        val bigObject by remember { allocateBigObject() }
        Render(bigObject)
    }
}
```

Once users click "Toggle", after a few recompositions (which can be triggered by clicking "Redraw" button), Compose drops "visible" subtree from memory, and `bigObject` gets deallocated.
Clicking "Toggle" once again recreates the object. In other words, Compose automatically drops continuations that it thinks won't be used

General-purpose, multishot continuations can't afford this, they should keep all the references to the state,
until references to continuations are discarded, or references are explicitly "cancelled" with `resumeWithException(CancellationException())`.

I consider the two-mentioned differences to be technical.
Technically, they can be fixed to make Compose look more like real general-purpose continuations.
The next difference is a more fundamental difference

### The user can't reify compose continuations. Continuations are managed by strict rules of the Compose framework

Compose doesn't offer "suspendCoroutine" (`call_cc`) primitive.
That's why users can't suspend coroutine, save the continuation somewhere and call it later.
"suspendCoroutine" is a principle framework that makes coroutines "suspendable".

Mutating a `MutableState` could be mistakenly considered an equivalent to firing a "captured" continuation. But it's not.

1. Such continuations are not fired immediately as you mutate the state
2. ref: `6c7a978f`.
  "Firing" a continuation in such a way fires "the continuation" only once.
  Composable functions are written in asynchronous style, but Compose runs only a single "asynchronous branch" at a time.
  It makes it impossible to use Compose to explore different "universes"
3. The `onClick` lambda is not a Composable lambda.
  State mutation is not possible during the recomposition itself (such mutations don't cause recomposition)

```kotlin
@Composable
fun Root() {
    var state by remember { mutableStateOf(false) }
    println("recompose")
    Text("hi $state")
    Button(onClick = {
        // The state is mutated 2 times. In the general-purpose continuation model,
        // it would cause the recomposition two times. Unfortunately, it's not the case
        // with @Composable
        state = !state // setValue is neither suspend nor @Composable
        state = !state // setValue is neither suspend nor @Composable
        println("end of onClick")
    }) { Text("hi") }
}
```

Clicking the button prints the following:

```
end of onClick
recompose
```

Please note that "end of onClick" is printed before "recomposition"

## What if Kotlin had value types?

Multishot continuations could have independent states.

It would be easier to support real multishot continuations. Continuations themselves must be a value type to support this.

Interestingly, value types would help Compose from different angles:
1. Compose tries to avoid allocations as much as possible because heap allocations are heavy. Value types can be allocated on stack.
2. Value types guarantee value-uniqueness, which is exactly what we need to stop different "universes" from mutating the big shared state.

## Final thoughts

If we ever decide to introduce multishot continuations, I think that they are significantly different from regular continuations so that they should:
- (a) have a different color (a keyword different from `suspend`).
- (b) Prohibit the color in `try-catch-finally`.
- (c) An interface different from Continuation.
- (d) Potentially, have a keyword (`multi`/`reactive`/`repeated`) on the call site as an act of acknowledgement that the rest-of-the-function/continuation can be called multiple times.
- (e) Potentially, we should think about special treatment for StateFlow-kind-of-use-case (The problem that Molecule solves).

## Appendix

Delimited and multishot continuations, `call_cc` and `prompt`/`control` primitives

- https://www.youtube.com/watch?v=TE48LsgVlIU
- https://cs.ru.nl/~dfrumin/notes/delim.html
- shift/reset in Kotlin: https://gist.github.com/elizarov/5bbbe5a3b88985ae577d8ec3706e85ef

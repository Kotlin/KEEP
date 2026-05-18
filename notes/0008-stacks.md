# Design Notes: Stacks for Kotlin

* **Type**: Design Notes
* **Author**: Ross Tate
* **Contributors**: Komi Golova, Mikhail Zarechenskiy, Alejandro Serrano Mena, Marat Akhin
* **Discussion**: [#486](https://github.com/Kotlin/KEEP/discussions/486)
* **Prerequisite**: [Local Lifetimes for Kotlin](https://github.com/Kotlin/KEEP/blob/main/notes/0007-local-lifetimes.md)

# Abstract

This document summarizes ongoing research on a library providing primitives for creating and working with first-class call-stacks, a feature that provides safer and more compositional support for coroutines.
Note that this work heavily relies on [local lifetimes](https://github.com/Kotlin/KEEP/blob/main/notes/0007-local-lifetimes.md), so the reader is advised to first familiarize themselves with that research before proceeding with this document.

# Disclaimer

This work is still research in progress.
We are sharing it early because we want to collect feedback (hopes, concerns, suggestions, and so on) from the community before determining whether to develop it into a formal proposal.

# Table of Contents

<!-- TOC -->
- [Design Notes: Stacks for Kotlin](#design-notes-stacks-for-kotlin)
- [Abstract](#abstract)
- [Disclaimer](#disclaimer)
- [Table of Contents](#table-of-contents)
- [Background](#background)
   * [Existing Coroutine Primitives](#existing-coroutine-primitives)
   * [Missing Invariants](#missing-invariants)
   * [Broken Compositionality](#broken-compositionality)
- [Design](#design)
   * [Stack Lifetimes](#stack-lifetimes)
   * [Stack Suspensions](#stack-suspensions)
   * [Stack Continuations](#stack-continuations)
   * [Stack Mounts](#stack-mounts)
   * [Stack Restackers](#stack-restackers)
      + [`restack`](#restack)
      + [`StackRestacker`](#stackrestacker)
      + [`mount`](#mount)
      + [`dismount`](#dismount)
      + [`switchTo`](#switchto)
      + [`finish`](#finish)
   * [Statically Unchecked Requirements](#statically-unchecked-requirements)
- [Practice](#practice)
   * [Common Stack Continuations](#common-stack-continuations)
   * [Resuming Stack Continuations](#resuming-stack-continuations)
   * [Suspending Stack Continuations](#suspending-stack-continuations)
   * [Pausing Stacks](#pausing-stacks)
- [Applications](#applications)
   * [Deeply-Recursive Functions](#deeply-recursive-functions)
   * [Sequences](#sequences)
      + [The `sequence` Sequence Builder](#the-sequence-sequence-builder)
      + [Revising `SequenceScope`](#revising-sequencescope)
      + [The `iterator` Sequence Builder](#the-iterator-sequence-builder)
         - [The fields](#the-fields)
         - [The `computeNext` implementation](#the-computenext-implementation)
         - [The pausing stack](#the-pausing-stack)
      + [Nesting builders](#nesting-builders)
   * [Flows](#flows)
      + [Redesigning with Locality](#redesigning-with-locality)
<!-- TOC -->

# Background

The purpose of this is to provide safer and more compositional primitives for supporting coroutines.
It is perfectly possible that these primitives could be shipped as only `internal` to the standard library, using them to improve the existing coroutine libraries Kotlin provides, as well as possibly provide new coroutine libraries, without ever giving Kotlin users direct access to the primitives.

## Existing Coroutine Primitives

Kotlin's coroutine libraries currently use a collection of primitives (i.e. intrinsics) that are implemented differently by each platform depending on what primitives the platform itself has (e.g. using continuation-passing style on JavaScript).
These primitives have slight variations between them, but we will use the following two proxies to cleanly capture the key functionality for our purposes:
```
public fun <T> createCoroutineUnintercepted(suspendingBlock: suspend (T) -> Unit
): Continuation<T>
```

```
public suspend fun <T> suspendCoroutineUnintercepted(
    handlingBlock: (Continuation<T>) -> Unit
): T
```

When we call `val snapshot = createCoroutineUnintercepted(suspendingBlock)`, we effectively create a new suspended call stack that is primed to call `suspendingBlock` the first time it is resumed, i.e. the first time we invoke `snapshot.resume(value)` for some `value` of the input type `T` that `suspendingBlock` is waiting for.
Once we invoke `snapshot.resume(value)`, the computuation `suspendingBlock(value)` will be executed on this newly created separate call stack, and it will run until it either completes *or* it makes a call to `suspendCoroutineUnintercepted`.

If the call to `suspendingBlock` completes, then the call to `snapshot.resume(...)` that we had made to kick off that computation on the separate call stack returns.

On the other hand, suppose `suspendingBlock` calls `suspendCoroutineUnintercepted(handlingBlock)`.
Then a few steps happen:
1. The separate call stack executing `suspendingBlock` gets suspended.
2. We switch back to wherever the call to `snapshot.resume(...)` was made.
3. We call `handlingBlock`, giving it the *new* snapshot of that *same* separate call stack that is now suspended in the middle of running `suspendingBlock` and is waiting to be resumed with whatever type of value that that call to `suspendCoroutineUnintercepted` was expected to return.
4. Once `handlingBlock` returns, then we return from the call to `snapshot.resume(...)`.

When the snapshot given to `handlingBlock` is resumed, it will switch control to the suspendind stack and run it until it either completes or calls `suspendCoroutineUnintercepted`, at which point it will switch control back to whoever resumed the snapshot.
Thus these primitives essentially give us a way to switch control back and forth between two call stacks.

Note that, when one of these snapshots is resumed, it is used up and cannot be used again.
Thus, if the computation suspends, it is the responsibility of the `handlingBlock` given to `suspendCoroutineUnintercepted` to make the new snapshot available so that the computation can be resumed in the future.

## Missing Invariants

One detail is that, if an exception gets thrown from the `suspendingBlock` of one of these separate call stacks, then that exception is passed onto and thrown from the last invocation to `resume` that caused the separate call stack to execute.
So, there's a way to throw an exception to the resumption site, but interestingly there's no way to return a value to the resumption site.
That is, you would think that `suspendingBlock` could return a non-`Unit` value that would be returned from `resume` if the `suspendingBlock` completes.

The issue is that each time `suspendingBlock` calls `suspendCoroutineUnintercepted`, a *new* snapshot is created.
So to make such a pattern safe, we would need to maintain an invariant across these snapshots that their `resume` sites all expect that same type of return value.
This requires adding a new type parameter to `Continuation`; right now it just has one indicating the *input* that it is waiting for, but we would need a new one to indicate the *output* that it is waiting for.
However, that is not enough.
The call to `suspendCoroutineUnintercepted` would need to know what return type is expected in order to what type of `Continuation` to give to its `handlingBlock`.
So the `suspend` keyword would need to be parameterizerd, and our primitives would end up looking like the following:
```
public fun <T, R> createCoroutineUnintercepted(suspendingBlock: suspend<R> (T) -> R
): Continuation<T, R>
```

```
public suspend<R> fun <T, R> suspendCoroutineUnintercepted(
    handlingBlock: (Continuation<T, R>) -> R
): T
```

This obviously would complicate the design, but because of its absence, instead this pattern is supported by using mutable nullable references and unchecked downcasts.
More generally, the current design has no way of enforcing invariants across resumption sites, which forces the standard library to use unchecked operations and implicit invariants to get around this lack of expressiveness.

## Broken Compositionality

A more severe problem arises when we try to compose or nest coroutine libraries and operations.
For example, it's fairly obvious that the program
```
sequence {
    runBlocking {
        launch {
            yield("Hello")
        }
        launch {
            yield("World")
        }
    }
}.forEach { println(it) }
```
*should* print either `Hello\nWorld\n` or `World\nHello\n`.
But would actually happen if you were to run this program is it would just stall forever.

The issue arises due to what is known is accidental interference.
`sequence` is implemented using `createCoroutineUnintercepted`, and `yield` is implemented using `suspendCoroutineUnintercepted`.
These two operations are conceptually meant to match with each other; this is, `yield` is meant to suspend the call stack created specifically by `sequence`.
However, `launch` is also implemented using `createCoroutineUnintercepted` (and also has "matching" operations like `delay` implemented using `suspendCoroutineUnintercepeted`).
Because `yield` is called inside these coroutine operations, its call to `suspendCoroutineUnintercepeted` ends up suspending the call stacks that `launch` created rather than the one created by `sequence`.
Meanwhile, `launch` expects all of its "matching" operations to interact with the scheduler in their `handlingBlock`, so the program stalls because `yield` does not use the scheduler to pick a task to run next.

At present, Kotlin attempts to hide these issues with the `@RestrictsSuspension` annotation.
This annotation is directly supported by the compiler, and it effectively disallows composing libraries that use coroutines.
For example, it disallows `yield` from being called anywhere but directly inside the `sequence` block, and it disallows `delay` from being called directly inside the `sequence` block.
This is excessively restrictive, though, and in some cases the standard library drops the annotation because it is too prohibitive to be useful.
And in these cases, like the library for asynchronous flows, bugs like [KT-3480](https://github.com/Kotlin/kotlinx.coroutines/issues/3480) do arise due to incomplete attempts to mitigate these fundamental issues with compositionality.

# Design

With [localities](https://github.com/Kotlin/KEEP/blob/main/notes/0007-local-lifetimes.md), we can generalize `suspend` because `local` objects (and functions) are safely able to use control that's local to the caller, such as suspending computation.
Thus we can have two `local` objects that are each able to suspend the computation to different points (unlike `suspendCoroutineUnintercepeted`, which can only suspend up to the innermost point).
The interface of these objects each dictate what kind of interaction they support; unlike `suspend`, these `local` objects can be used by the callee to suspend the computation *without* giving the callee complete access to the continuation.

This means that localities provide us with an opportunity to develop a new, safer, and more compositional set of primitives for creating and working with call stacks.
Here I provide a tentantive such design.
It provides more flexibility in expressing invariants for coroutines so that coroutine libraries can avoid relying on implicit invariants and making unchecked downcasts, and it supports safely nesting coroutines even when composing functionality across independently developed coroutine libraries.

This section describes the primitives.
That is, it is essentially a technical manual for an API.
These primitives are very low level so that they can efficiently support very advanced libraries and patterns like Kotlin's structured concurrency.
As such, the [Practice](#practice) section provides a quick tutorial on how one can put these primitives together to build something higher level and more conveniently usable.
After that, the [Applications](#applications) section illustrates a few of the concrete ways we can use this to address current issues in the standard library (and eliminate the need for `@RestrictsSuspension` and many dynamic checks).

Note that this technical document assumes the reader is familiar with [local lifetimes](https://github.com/Kotlin/KEEP/blob/main/notes/0007-local-lifetimes.md), including advanced features such as [explicit locality polymorphism](https://github.com/Kotlin/KEEP/blob/main/notes/0007-local-lifetimes.md#Advanced-Locality-Polymorphism).

## Stack Lifetimes

For a stack, there are a few kinds of lifetimes to be aware of.
One is the "arena"; this is the lifetime that all of the stack's computation is expected to run within.
Current Kotlin coroutines are defined using primitives that assume the captured continuation has a global lifetime, but in reality we want a continuation to be able to safely reference things like local resources, which means its lifetime must be restricted to that of those resources.
Thus, the referenced resources are part of the "arena" of the stack.

Another important kind of lifetime is the "environment".
Each time a stack is executed, it is within some local environment.
At the least, a stack's code might throw an exception, and the current environment is responsible for propagating that exception.
Often, a stack's code might want to return an value (e.g. after finishing executing), and the local environment needs to specify where that value should be returned to locally.
A key challenge is that the environment of the stack can change each time a stack's code is suspended and resumed.
So we need some way for an executing stack to interact with its current environment without letting it interact with an old (and likely no longer valid) environment.

Lastly, there is the "execution" lifetime of the stack itself.
There are certain operations that only make sense to perform while the stack is executing.
Another challenge is that this lifetime is not contiguous; it has a gap between each suspension and resumption.
Thankfully, locality works great here, because it already ensures objects are only accessible during certain execution times.
We just need to make sure not to break that invariant.

Note that the "execution" lifetime and all of the "environment" lifetimes of a stack should always be contained within (i.e. sub-lifetimes of) the stack's "arena" lifetime.

## Stack Suspensions

A stack suspension represents a (potentially) suspended stack:
```
public expect local class StackSuspension<out local resumption>
    internal expect constructor
```

The lifetime of the `StackSuspension` indicates the locality requirements for using the suspended stack, such as its arena.
That is, the code of the suspended stack accesses certain objects/operations with restricted localities, so those localities need to be available for the suspended stack to be safely resumed. 

The `resumption` locality parameter indicates the locality at the point of suspension.
This locality includes, say, access to all objects/operations currently allocated on the suspended stack.
When the suspended stack is resumed, the resuming code will execute within the `resumption` locality.

A `StackSuspension` is in one of three states:
* __Pending:__ The stack suspension is currently awaiting completion of the `StackRestacker` session (see [below](#Stack-Restackers)) that created this reference.
* __Available:__ The stack suspension is available to be used.
* __Expired:__ The stack suspension has already been used—a stack suspension can be used at most once (though the same stack is captured by many stack suspensions throughout its execution—just only by one stack suspension at a time).

## Stack Continuations

Consider a simple case: cooperative yielding.
Coroutines take turns suspending themselves and switching to some other coroutine.
They don't need any value to be resumed, they simply need control to be transferred back to them.
In this pattern, a coroutine will suspend its stack and hand off a stack-allocated `() -> Nothing` function to be used to resume its code once we switch back to its stack.
In this pattern, we'll have stack suspensions that all have the same lifetime but which each have their own `resumption` locality and each have an associated `() ->_{resumption} Nothing` object with that restricted lifetime.

We use `StackContinuation` to represent such a common pattern.
A stack continuation is not a new primitive, just a convenient class that bundles a `StackSuspension` with an object to be used to transfer control back to the suspended code:
```
public local class StackContinuation<out R>(
    val suspension: StackSuspension<resumption>_{this},
    val resumer: R_{resumption}
) {
    local resumption = public
}
```
Here I introduce a *local member* `resumption`.
Whereas type members allow each object to have its own associated type (like an existentially quantified type), local members allow each object to have its own associated locality (besides its lifetime).
In this way, even though each cooperatively yielding coroutine's stack suspension has its own `resumption` lifetime, we can use `StackContinuation<() -> Nothing>_{arena}` to represent the common structure shared across these suspended coroutines.

Many designs go straight for `StackContinuation`, bypassing `StackSuspension`.
Because we have localities, we can operate a step lower.
This enables us to directly implement operations that other designs would need to make primitive.
For example, we can implement the following ourselves:
```
fun <T> ignoreInput(
    local continuation: StackContinuation<() -> Nothing>
): StackContinuation<(T) -> Nothing>_{continuation}
    = StackContinuation(continuation.suspension) { _ ->
        continuation.resumer()
    }
```

In `StackContinuation`, we have `local resumption = public`.
When a `StackContinuation` is constructed, the value of its locality member is automatically inferred (which is why it can be used in the types of the constructor parameters).
The `= public` means that the inferred value is part of the type after constructor.
That is, the type of any interface/class can optionally indicate lower and upper bounds on type members—using the syntax `StackContinuation<() -> Nothing, resumption_{lower}^{upper}>`—and `= public` indicates that the result type of the constructed object should include the inferred bounds on that member.
Alternatively, a class could say `= private[this]`, which can be useful for concealing internal implementation details.

## Stack Mounts

Because the environment of a stack changes each time it is suspended, every stack has a mutable reference to its environment.
This reference is a `StackMount`.
It is used
* to propagate control (e.g. if an exception is thrown from the root of the stack, the exception is thrown to whatever environment the stack mount currently references),
* to ensure invariants of the environment (e.g. the locality expectations of the stack's code that the environment must provide),
* and to provide means for interacting with the current environment (e.g. how to return a value to the current environment and/or give a stack suspension to the current environment).

```
public expect local class StackMount<out local arena, E>() {
    local^{arena} mounted = private[this]
    fun_{global} <R> new(
        resumer: R_{mounted}
    ): StackContinuation<R, resumption^{mounted}>_{mounted}
}
```
The `arena` locality parameter indicates the locality requirements of environments.
The `E` type parameter indicates the type of the object to be used to interact with environments.

A mount is either __Mounted__ (to some environment) or __Available__.
Every mount has its own locality, `mounted`, that indicates when it is mounted.
This locality is upper-bounded by `arena`, indicating that the mount can only be mounted within environments that are themselves within the `arena` lifetime.
Because the type system is not able to explicitly reason about such typestate, we use `= private[this]` to make `mounted` abstract; it is the implementation of the class and the design of the API that ensures that the code executes within the `mounted` locality only when the mount is mounted.

In order to create a new stack, one uses the `new` method.
The `fun_{global}` declaration indicates that this method is available within the `global` locality (i.e. always), rather than just the mount's lifetime.
This is because it does not actually use the stack mount; it simply gives the stack mount's reference to the newly allocated stack.
The `resumer` is the object the new stack will be resumed using once switched to.
The stack will only ever execute while the mount is mounted, so `resumer` is allowed to access anything needing the `mounted` locality (and, consequently, anything needing the `arena` locality).
This results in a `StackContinuation` whose `resumption` point is necessarily within the `mounted` lifetime, with `R` as the interface for kicking off execution, and which can be switched to when the mount is `mounted`.

## Stack Restackers

I have presented all the key values, but not any operations on those values (besides construction).
This is because all the operations are provided by a single class that is only available when in a special state for reconfiguring stacks.

### `restack`

```
fun restack(once block: local StackRestacker<local>.() -> R): R
```
`restack` enters into this special state.
The code outside a call to `restack` is the portion of the current stack that will remain should this stack be suspended, whereas the code inside the `block` will all be unwound by the time the restacking process completes (leaving the suspended stack in a nice clean state with no lingering stack frames below the resumption site).

### `StackRestacker`

This `block` is given access to a local `StackRestacker` used to manipulate the current stack and other suspended stacks.
The `StackRestacker` class provides the bulk of the functionality, with each of its methods providing a primitive for rearranging and transferring control between call stacks.
The signature of the `StackRestacker` class is as follows:
```
public expect local class StackRestacker<out local_{this} resumption> internal expect constructor {
    fun <local_{resumption} arena, E, local that> mount(
        environment: E_{resumption},
        mount: StackMount<arena, E>_{local},
        suspension: StackSuspension<that>_{mount.mounted},
        block: local StackRestacker<that>.(
        ) ->_{that} Nothing
    ): Nothing
    fun <local_{resumption} arena, E> dismount(
        mount: StackMount<arena, E, mounted_{resumption}>_{local},
        block: local^{environment} StackRestacker<environment>.(
            local^{arena} environment: E,
            StackSuspension<resumption>_{mount.mounted}
        ) ->_{arena} Nothing
    ): Nothing
    fun <local that> switchTo(
        local_{resumption} suspension: StackSuspension<that>,
        block: local StackRestacker<that>.(
            StackSuspension<resumption>_{suspension}
        ) ->_{that} Nothing
    ): Nothing
    fun finish(
        block: (
        ) ->_{resumption} Nothing
    ): Nothing
}
```
The `resumption` locality parameter indicates the lifetime of the (current) resumption site of the restacker; while the local frame will be unwound by the time restacking finishes, anything with the `resumption` lifetime will persist past that unwinding.

All of the operations provided by `StackRestacker` take a `block`.
None of these blocks are `local` and all of them return `Nothing`, which means that each `StackRestacker` will only ever be used once.
However, most of these blocks provide a new local `StackRestacker`.
In this way we essentially encode typestate; `restack` enters a restacking session, and each operation provides a `StackRestacker` whose `resumption` locality represents the next typestate of that session, namely the `resumption` lifetime of the (suspended) stack it reconfigures.

### `mount`

The first operation is `mount`:
```
fun <local_{resumption} arena, E, local that> mount(
    mount: StackMount<arena, E>_{local},
    environment: E_{resumption},
    suspension: StackSuspension<that>_{mount.mounted},
    block: local StackRestacker<that>.(
    ) ->_{that} Nothing
): Nothing
````
This operation is used to mount the given (dynamically checked) __Available__ `StackMount` onto the current resumption site, which (atomically) becomes __Mounted__; the given `StackSuspension` then becomes the new resumption site of the current session.
In order to do so, the current resumption site must be within the required `arena` locality of the given stack mount, and we must provide an `environment` object that is available at the resumption site.
The stack `suspension`'s lifetime must only require the stack mount to be mounted (which any stack suspension created by that stack mount will satisfy), and it must either be (dynamically checked) __Available__ *or* __Pending__ completion of specifically the current restacking session—in either case, it (atomically) becomes __Expired__.
Then we unwind the current local stack frame and execute `block` with the resumption site of the stack `suspension` as the new target resumption site of the restacking session.
Natively this can be performed without actually performing a context switch, though some restricted platforms might require one; the design is compatible with either implementation strategy.

### `dismount`

The second operation is `dismount`:
```
fun <local_{resumption} arena, E> dismount(
    mount: StackMount<arena, E, mounted_{resumption}>_{local},
    block: local^{environment} StackRestacker<environment>.(
        local^{arena} environment: E,
        StackSuspension<resumption>_{mount.mounted}
    ) ->_{environment} Nothing
): Nothing
```
This operation is used to detach a (necessarily) __Mounted__ or __Pending__ (the current session) `StackMount` from its current resumption site.
The type of `mount` ensures that it is currently mounted somewhere "above" the current resumption site.
It (atomically) becomes __Available__, and its (former) resumption site (whose locality is represented by the `environment` locality variable) becomes the target resumption site of the current restacking session.
In so doing, the former resumption site of the session becomes unreachable.
As such, `block` is given both the `environment` object that was provided when the stack `mount` was mounted *and* the `StackSuspension` of the abandoned stack.
Because it is possible that the restacking session is still executing on that stack, this `StackSuspension` is marked as __Pending__ completion of the current session.

### `switchTo`

The third operation is `switchTo`:
```
 fun <local that> switchTo(
    local_{resumption} suspension: StackSuspension<that>,
    block: local StackRestacker<that>.(
        StackSuspension<resumption>_{suspension}
    ) ->_{that} Nothing
): Nothing
```
This directly switches to another (dynamically checked) __Available__ or __Pending__ (the current session) stack `suspension` whose locality requirements are provided by the current resumption site (e.g. another stack suspension created by the same stack mount as that of the current resumption site), which (atomically) becomes __Expired__.
This conceptually hands off those locality requirements to that stack suspension, so the __Pending__ `StackSuspension` of the current stack given to `block` has those same locality requirements.

### `finish`

The final operation is `finish`:
```
fun finish(
    block: (
    ) ->_{resumption} Nothing
): Nothing
```
This finishes the session, changing all `StackSuspension`s that are __Pending__ its completion to instead be __Available__.
Like the other operations, this first unwinds the local stack frame and then executes the `block`.
Unlike the other operations, no `StackRestacker` is given to `block` because the session has finished.

Note that in all cases the block is allowed to operate within the locality of the respective resumption site.
This means that an implementation might need to perform a context switch should the block, say, throw an exception or perform a non-local return.
However, such implicit context switches are necessary when non-local control exits a suspensed stack anyways.
Whether __Pending__ stack suspensions should be made __Available__ even without an explicit call to `finish` is a design choice yet to be made.
Regardless, having this functionality provides a safe way to inspect the contents of a suspended stack without performing a context switch, which has a variety of applications (including making the "stack barrier" primitives that the old design required for interop with parallelism no longer necessary, though this relies on the ability to switch back to stack suspensions created earlier by the current restacking session).

## Statically Unchecked Requirements

While this design makes heavy use of lifetimes to statically ensure many operations are safe, there are still a few dynamic checks in this design.
It is the responsibility of the user to ensure the following:
* The `StackMount` given to `mount` must be __Available__.
* The `StackSuspension` given to `mount` or `switchTo` must be either __Available__ or __Pending__ completion of the current restacking session.

# Practice

Here we demonstrate a few examples of how to put the above primitives together into a simpler stack API.

## Common Stack Continuations

In many cases, when we create a new stack, it runs some code that may or may not suspend the stack multiple times, and afterwards it returns some computed result to whatever its final mounting site is.
We can support this common pattern with the following extension method:
```
fun <local arena, E, O> StackMount<arena, E>.new(
    after: (local E, O) ->_{arena} Nothing,
    block: () ->_{mounted} O
): StackContinuation<() -> Nothing>_{mounted} = new { input ->
    val output = block()
    restack {
        dismount(this@new) { (environment, _) ->
            finish {
                after(environment, output)
            }
        }
    }
}
```

The `after` function specifies how to use the environment object to transfer the final output to the final mounting site.
The `block` is the code to run on the stack to compute that output.

The implementation simply creates a `new` stack that first calls `block` and then, after `block` finishes, enters a restacking session.
Then it `dismount`s the given mount, transferring the session to the final mounting site.
Finally it `finish`es the session by calling `after` (at the mounting site), giving it that site's `environment` object and the computed output.

## Resuming Stack Continuations

The `StackContinuation` class has two fields tied together by its member lifetime.
Rather than requiring the programmer to regularly access these fields at this low level, we can provide the following extension method for its most common usage pattern:
```
fun <local_{local} arena, E, R, T> StackMount<arena, E>.resume(
    environment: E_{local},
    continuation: StackContinuation<R>_{mounted},
    block: (local R) ->_{mounted} Nothing
): Nothing {
    restack {
        mount(this@new, continuation.suspension) {
            finish {
                block(continuation.resumer)
            }
        }
    }
}
```

`this` is the stack mount to mount at the current call site so that its associated suspended stacks run within the current dynamic scope.
The `environment` is what the `continuation` should use to interact with the current call site to `resume`.
The `continuation`'s `suspension` is the suspended stack to (first) switch to, and it's `resumer` is the object to resume it with, which is given to the `block` that is called on the suspended stack.
Everything returns `Nothing` because `block` uses the `resumer` to transfer control to the code on the suspended stack, and that code uses the given `environment` to transfer control back to the call site to `resume`.

The implementation works by first entering a restacking session.
Then it `mount`s the given stack mount and transfers the session to the `continuation`'s `suspension`.
Then it `finish`es the session and calls `block` with the `continuation`'s `resumer` (on the `continuation`'s suspended stack).

## Suspending Stack Continuations

The `StackContinuation` class has two fields tied together by its member lifetime.
Rather than requiring the programmer to regularly access these fields at this low level, we can provide the following extension method for its most common usage pattern:
```
fun_{mounted} <local arena, E, R, T> StackMount<arena, E>.suspend(
    resumer: R_{local},
    block: (local E, StackContinuation<R>_{mounted}) ->_{arena} Nothing
): Nothing {
    restack {
        dismount(this@new) { (environment, suspension) ->
            finish {
                block(environment, StackContinuation(suspension, resumer))
            }
        }
    }
}
```

`this` is the stack mount whose current attachment site is where we want to `suspend` up to.
The `resumer` is what object to use to resume from the current call site to `suspend`.
The `block` is the code to run at the mount's attachment site, which is given the environment object for that site and the stack continuation for the stack we are suspending.
Everything returns `Nothing` because `block` uses the given environment to transfer control to the code at the attachment site, and later code will use the given `resumer` to transfer control back to the call site to `suspend`.

The implementation works by first entering a restacking session.
Then it `dismount`s the given stack mount and transfers the session to its (now former) attachment site.
Notice that `suspend` is declared as `fun_{mounted}`; this ensures it is only executed while the given mount is mounted, which makes this call to `dismount` valid.
Next the implementation `finish`es the session and calls `block` (at the attachment site) with that site's `environment` object along with the continuation build from the stack we just suspended and the given `resumer` object.

## Pausing Stacks

Now we build upon the above extension methods to implement a pausing stack class that, each time its `resume` method is invoked, executes its given `block` until that `block` calls its given `local pause: () -> Unit` function.

Its signature is the following:
```
local class PausingStack(
    block: (local pause: () -> Unit) ->_{this} Unit
) {
    fun progress(): Boolean // returns true if block (has) finished
}
```

Its implementation is the following (where `private[this]` indicates it is only accessible to this object):
```
local class PausingStack(
    private val block: (local pause: () -> Unit) ->_{this} Unit
) {
    private[this] val mount: StackMount<this, (Boolean) -> Nothing>
        = StackMount()
    private[this] var continuation: StackContinuation<() -> Nothing>_{mount.mounted}?
        = mount.new({ (exit, _) -> exit(true) }) {
            block pause@{
                mount.suspend({ return@pause }) { (exit, continuation) ->
                        this.continuation = continuation
                        exit(false)
                    }
                }
            }
        }
    fun progress(): Boolean {
        mount.resume(
            (continuation ?: return true).also { continuation = null }
        ) {
            it()
        }
    }
}
```

A pausing stack has its own stack mount it mounts each time it is resumed.
The arena of that mount is the lifetime of the pausing stack (which is restricted to include the lifetime of the given `block`).
The environment type of that mount is a function to use to transfer control and indicate whether or not `block` has finished.

A pausing stack has a mutable field storing the current suspension of its stack; `null` indicates either the stack is currently executing or it has completed.
We initialize this field by using our `new` extension method to create a continuation that will call `exit(true)` when its code finishes, with `true` indicating the completion.
That code calls the given `block`, handing it a `pause` function that uses our `suspend` extension to suspend the stack, storing the new continuation in the mutable field and calling `exit` with`false` to transfer control and indicate the incompletion.
The validity of this `pause` function relies on the fact that `block` accepts a `local` parameter, and as such we can give it a function that is only valid within the `mount.mounted` lifetime (so that it can call `mount.suspend`).
The `progress` method calls the our `resume` extension to resume the suspended stack, simply invoking the resumer function to transfer control back to the last call to `pause`.

# Applications

Now with the design technically covered, we illustrate it with examples of how it can be applied to Kotlin's existing coroutining libraries.
A common pattern is that we will replace `suspend` with `local` objects implemented using stacks.
This replacement more directly connects the components in these libraries, making them more flexible and reliable and removing the need for `@RestrictsSuspension`.

## Deeply-Recursive Functions

Deeply-recursive functions have the issue that their call stacks often exceed the permitted space.
The existing `util` library provides functionality for ensuring each deeply-recursive call is run on its own stack in order to avoid hitting this cap.
A deeply-recursive function uses this library by constructing the `DeepRecursiveFunction` with a function that invokes `callRecursive` each time it wants to make a deeply-recursive call.

The current design provides this functionality using `suspend`:
```
public class DeepRecursiveFunction<T, R>(
    internal val block: suspend DeepRecursiveScope<T, R>.(T) -> R
)
@RestrictsSuspension
public sealed class DeepRecursiveScope<T, R> {
    public abstract suspend fun callRecursive(value: T): R
    public abstract suspend fun <U, S> DeepRecursiveFunction<U, S>.callRecursive(value: U): S
}
```
The expectation is that `suspend` in `callRecursive` gets matched to the `suspend` in `block`.
However, this is phrased indirectly, and as such the basic type system permits `callRecursive` to be called within other suspending functions (like within a sequence builder), and permits other suspending functions (like `Flow.collect`) to be called within `block`, neither of which would go well.
This is why `DeepRecursiveScope` has the `@RestrictsSuspension` annotation; it further restricts the usage of `DeepRecursiveScope` and the suspending functions in `block` to ensure the intended matching.

With locality, we can make the connections more direct, eliminating the need for `@RestrictsSuspension` and its corresponding restrictions:
```
public local class DeepRecursiveFunction<T, R>(
    internal val block: local DeepRecursiveScope<T, R>.(T) ->_{this} R
)
public local sealed class DeepRecursiveScope<T, R> {
    public abstract fun callRecursive(value: T): R
    public abstract fun <U, S> local DeepRecursiveFunction<U, S>.callRecursive(value: U): S
}
```
The fact that the `DeepRecursiveScope` given to `block` is `local` simultaneously prevents it from escaping the block and enables it to use more advanced control with which we can implement the requested functionality.
Notice that we're also able to enable `block` to access local variables and control, so long as we similarly restrict the lifetime of the `DeepRecursiveFunction`.
(The effect of `->_{this}` in `block`'s type is to restrict the lifetime of this `DeepRecursiveFunction` to at most that of the `block`.)

The key function for initiating a deeply-recursive call is
```
public operator fun <T, R> local DeepRecursiveFunction<T, R>.invoke(
    value: T
): R
    = DeepRecursiveScopeImpl<T, R>(this).block(value)
```
Notice that the receiver is marked `local` so that it can be used on limited-lifetime `DeepRecursiveFunction` objects.
For this to type-check, we rely on the fact that our stacks design effectively reasons about lifetimes.

The above implementation of `invoke` pretty much entirely relies upon the following class implementing `DeepRecursiveScope`:
```
private local class DeepRecursiveScopeImpl<T, R>(
    private val function: DeepRecursiveFunction<T, R>_{this}
) : DeepRecursiveScope<T, R>() {
    override fun callRecursive(value: T): R = onFreshStack {
        function.block(value)
    }
    override fun <U, S> local DeepRecursiveFunction<U, S>.callRecursive(
        value: U
    ): S = DeepRecursiveScopeImpl(this).callRecursive(value)
}
```
This implementation is much simpler than before, reducing 90 lines of code to just 10.
Furthermore, it is fully typed, whereas the old implementation relied on unsafe casting to get around the fact that its use of `suspend` relied on things like `@RestrictsSuspension` to ensure suspensions only occurred where expected.

This new implementation relies on `onFreshStack`, which is a utility function that executes the given function on a fresh stack:
```
fun <R> onFreshStack(
    local block: () -> R
): R {
    val mount = StackMount()
        // : StackMount<local, (R) -> Nothing>
    val continuation = mount.new(Unit)
        // : StackContinuation<Unit>_{mount.mounted}
    restack {
        mount(mount, Unit, continuation.suspension) {
            finish {
                return block()
            }
        }
    }
}
```
Note that this relies on the fact that the arena of the stack mount can be within the `local` lifetime (of the call to `onFreshStack`), wherein both the call to `block` and the `return` are necessarily safe.
It also takes advantage of the fact that the code inside `finish` is executed on the stack of the target resumption site.

Semantically speaking, the `block` could safely be marked as `once`, rather than just `local`.
If we want the type system to support this, then we need to extend it with a notion of `once_{locality}` which only allows the given function to assume it runs within `locality`.
The blocks for the restacking operations could all be given such an annotation.

## Sequences

Deeply-recursive functions make use of stack resources, but no use of stack suspension.
To illustrate the latter, we look into the design of sequences, or really of the sequence-builder function `sequence` and its `SequenceScope` class.
For this, we assume the `Sequence` interface has been localized as follows:
```
public interface Sequence<out T> {
    public operator fun iterator(): Iterator<T>_{this}
}
```

### The `sequence` Sequence Builder

For the same reasons as with deeply-recursive functions, the `sequence` function is changed to use locality rather than `suspend`:
```
public fun <T, local sequence> sequence(
    block: local SequenceScope<T, sequence>.() ->_{sequence} Unit
): Sequence<T>_{sequence} = Sequence { iterator(lock, block) }
```
Its implementation is actually identical to before, at least syntactically.
That's because the real work is done in the corresponding `iterator` function.
But, before we visit that, we should consider an interesting aspect of lifetimes for `SequenceScope`.

### Revising `SequenceScope`

We update `SequenceScope` to have the following signature:
```
public local abstract class SequenceScope<in T, out local owner>
    internal constructor() {
    public abstract fun yield(value: T)
    public abstract fun yieldAll(iterator: Iterator<T>_{owner})
    public fun yieldAll(elements: Iterable<T>_{owner}) {
        if (elements is Collection && elements.isEmpty()) return
        return yieldAll(elements.iterator())
    }
    public fun yieldAll(sequence: Sequence<T>_{owner})
        = yieldAll(sequence.iterator())
}
```
The key detail to observe is that `SequenceScope` now has a lifetime parameter: `owner`.
The `yieldAll` function enables the builder to give the sequence scope an entire iterator of values.
Of course, the builder could just `yield` those values one by one, but that incurs a call and two stack switches per element, creating inefficient overhead.
The intent is for the iterator of the sequence to store this given iterator and use it directly until it runs out.
But that means the given iterator escapes the call to `yieldAll`, and as such it cannot be marked as `local`.
Thus the `owner` parameter is used to ensure the given iterator is at least valid within the lifetime of the built sequence; the type of `sequence` ensures the same lifetime is used for `block`, for the `SequenceScope` owner, and for the returned `Sequence`.

### The `iterator` Sequence Builder

This function uses a corresponding `iterator` function, which is where the real work is done.
In the following I split its definition across many code blocks so that I can explain each key portion one at a time.

```
public fun <T> iterator(
    local block: local SequenceScope<T, block>.() -> Unit
): Iterator<T>_{block}
    = object : AbstractIterator<T>() {
```
We are going to create a custom `Iterator` object using `AbstractIterator` for convenience.
This class takes care of handling the public `hasNext` and `next` methods and just requires you to implement a protected `computeNext` method.
It also provides protected `setNext` and `done` methods, exactly one of which your implementation of `computeNext` needs to call before it returns.

#### The fields

Our custom iterator will keep track of any potential queued-up elements provided by an earlier `yieldAll` call by the `block`.
```
    var queued: Iterator<T>_{block}? = null
```

Initially, though, `queued` will be `null`.
So the first time `computeNext` is called, we will execute `block` on a fresh stack.
For this stack we will use the `PausingStack` class we developed above:
```
    val stack: PausingStack_{block} = PausingStack { ... }
```
The stack's lifetime is `block` so that the stack we create with it can run `block`.
I defer discussing the code it executes until later.

#### The `computeNext` implementation

With the fields established, now we proceed to the our custom iterator's actual implementation of `computeNext`:
``` 
    override fun computeNext() {
        queued?.let {
            if (it.hasNext()) {
                setNext(it.next())
                return
            } else {
                queued = null
            }
        }
        stack.progress()
    }
```

The first part of `computeNext` is straightforward.
It simply checks whether there is already a queued value.
Notice that, as intended for `SuspendScope`'s design for `yieldAll`, there is no stack-switching in this case.

Otherwise, we resume the pausing stack to compute the next value.

#### The pausing stack

This pausing stack is running the given `block`, so how does it pause itself when it generates a value?
For that, let's examine the code executed by the stack (that I deferred presenting earlier).
This code has three parts: create the (suspending) `SequenceScope`, call `block` with that scope, and then exit the stack when there are no more values to generate.

The scope we run `block` with is the following:
```
val scope = object : SequenceScope<T, block>() {
    override fun yield(value: T) {
        setNext(value)
        pause()
    }
    override fun yieldAll(iterator: Iterator<T>_{block}) {
        if (iterator.hasNext()) {
            queued = iterator
            yield(iterator.next())
        }
    }
}
```
Whenever a value is yielded, the scope calls the `pause` function that was given to the pausing stack's block.
(If multiple values are yielded, it first sets `queued` to the iterator with the remaining values.)
This restricts the lifetime of this scope object to that of `pause`, which is a local parameter.
Nonetheless, because `block` takes a `local SequenceScope`, we can hand it such a limited-lifetime object.

After creating this local `SequenceScope` object, the pausing stack invokes `block`:
```
scope.block()
done()
```
And, once the `block` finishes (possibly after many suspensions and resumptions), it finally indicates that the iterator is `done` generating values.

### Nesting builders

Altogether, we're able to recreate roughly the same implementation currently done using `suspend`, but without relying on `@RestrictsSuspension` for correctness, and while furthermore being able to safely support sequences whose generating function references local resources.

Because of this composability, we can safely nest sequence builders with various other "suspending" computations.
The following is an example of nesting sequence builders within sequence builders.
Although a toy example, it is useful for understanding and comparing implementation and performance considerations.
```
fun leastPrimeFactorSequence(): Sequence<Pair<Int, Int>> = sequence {
    var primes = iterator {
        var i = 1
        while (i != Int.MAX_VALUE)
            yield(++i)
    }
    while (true) {
        val candidates = primes
        val prime = candidates.next()
        yield(Pair(prime, prime))
        primes = iterator {
            candidates.forEach { candidate ->
                if (candidate % prime == 0)
                    this@sequence.yield(Pair(candidate, prime))
                else
                    yield(candidate)
            }
        }
    }
}
```

This generates the sequence
```
(2, 2)
(3, 3)
(4, 2)
(5, 5)
(6, 2)
(7, 7)
(8, 2)
(9, 3)
(10, 2)
...
```
It does so by progressively building a stack of filtering iterating stacks within the sequence stack in order to implement the sieve of Eratosthenes.
Whenever a filtering iterating stack encounters a value that its prime filter divides, it yields the value and its prime filter to the overarching sequence, rather than yielding the value further up the stack.

This often happens very early in the stack.
Thus, how that yield to the overarching sequence is implemented can significantly affect performance.
In this design, that yield happens in constant time.
This is in contrast to dynamically-scoped designs like in OCaml and WebAssembly, where a context switch would be performed with every filterating iterator up the stack (only to discover it is not the sequence-generating iterator and propagate the suspension further up the stack).
So by using lexically-scoped semantics and statically-typed localities, we get all of safety, composability, and performance.

## Flows

In their simplest form, flows are created via `val f = flow { /* make calls to emit(value) /* }`, and one can invoke `f.collect { value -> /* do stuff with value */ }` to effectively run the body of the flow in the collector's context while "doing stuff" each time a call to `emit` was made.
The design attempts to achieve this through the following interfaces:
```
public interface Flow<out T> {
    public suspend fun collect(collector: FlowCollector<T>)
}
public fun interface FlowCollector<in T> {
    public suspend fun emit(value: T)
}
```
However, there are some gaps caused by the language-limitation of having only `suspend` to work with.

Consider, for example, the following "unsafe" flow builder:
```
inline fun <T> unsafeFlow(
    crossinline block: suspend FlowCollector<T>.() -> Unit
): Flow<T>
    = object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            collector.block()
        }
    }
```
This is the most obvious implementation of a flow builder, but it's unsafe because it fails to dynamically enforce "context preservation".
The following illustrates the oddities that context preservation prevents:
```
inline suspend fun currentContext(): CoroutineContext
    = suspendCoroutineUninterceptedOrReturn<CoroutineContext> { coroutine ->
        coroutine.context
    }
fun main() {
    val unsafe = unsafeFlow {
        runBlocking {
            launch {
                emit(103L)
                emit(117)
            }
            launch {
                emit(205)
                emit(211)
            }
            launch {
                emit(413)
                emit(437)
            }
        }
    }
    runBlocking {
        val context = currentContext()
        launch {
            for (i in 1..20) {
                delay(50)
                println("Timer: ${i*50}")
            }
            
        }
        unsafe.collect {
            delay(it)
            println("Delayed $it in collecting context? ${currentContext() === context}")
        }
    }
}
```
The intent is that the `delay(it)` in `unsafe.collect` executes in the collector's context, and as such it should delay the `runBlocking` coroutine each time.
Even though the flow launches concurrent emissions, this delay should create a common bottleneck, but what this actually prints is the following:
```
Timer: 50
Timer: 100
Delayed 103 in collecting context? false
Timer: 150
Timer: 200
Delayed 205 in collecting context? false
Delayed 117 in collecting context? false
Timer: 250
Timer: 300
Timer: 350
Timer: 400
Delayed 413 in collecting context? false
Delayed 211 in collecting context? false
Timer: 450
Timer: 500
Timer: 550
Timer: 600
Timer: 650
Timer: 700
Timer: 750
Timer: 800
Timer: 850
Delayed 437 in collecting context? false
Timer: 900
Timer: 950
Timer: 1000
```
That is, rather than the `runBlocking` coroutine being delayed, whichever launched coroutine is calling `emit` is what gets delayed.
We can see this mismatch with the expecation in the fact not the `currentContext` in the collection never matches the expected context.

Looking at the design, we can see that the collecting function is not called with the `suspend` capabality of the collector; rather, it is called with the `suspend` capability of the emitter.
So if the emitter fails to preserve the context, then that causes a mismatch.
The dynamic checks in the flow built by the standard `flow` catch this and throw an exception, but those dynamic checks are overly conservative, causing bugs like [KT-3480](https://github.com/Kotlin/kotlinx.coroutines/issues/3480), where an exception is thrown even though the usage is perfectly safe.

### Redesigning with Locality

We can use locality to fix this problem.
The first step is to revise the design of flows:
```
public interface Flow<out T> {
    public suspend fun collect(local collector: (T) -> Unit)
}
```
Then the standard builder can be implemented simply via:
```
inline fun <T> flow(
    local block: suspend (local emit: (T) -> Unit) -> Unit
): Flow<T>_{block}
    = object : Flow<T> {
        override suspend fun collect(local collector: (T) -> Unit) {
            block(collector)
        }
    }
```
Now any calls to `emit` do not pass anything besides the value to emit.
Yet, because `collector` is `local`, it is still able to use locally available control like `delay`, and that `delay` will use the locally available `suspend` capability rather than whatever capability is live at the `block`'s call to `emit`.

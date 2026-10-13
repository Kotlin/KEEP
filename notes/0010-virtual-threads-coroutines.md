# `kotlinx.coroutines` on top of Project Loom Virtual Threads

This document presents research on viability of integration Project Loom Virtual Threads
with `kotlinx.coroutines`, mostly from the performance perspective.

# Project Loom

[Project Loom](https://openjdk.org/projects/loom/) introduces to JVM:
* Virtual Threads - lightweight threads supported by JVM and scheduled by the JDK.
* Structured Concurrency - standard library facility for managing related concurrent tasks.
* Scoped Values - a way to implicitly share context values in a scope.

This document explores only Virtual Threads integration, leaving other features out of scope.

## Virtual Threads vs `kotlinx.coroutines`

A short comparison of Virtual Threads and `kotlinx.coroutines` is presented below.

Virtual Threads are designed to be a drop-in replacement for platform threads on JVM, so they expose mostly the same API.
Internally, Virtual Threads use stackful JVM continuations.
When a VT unmounts, its stack frames are saved in heap-allocated stack chunks (**freezing**); 
when it mounts again, frames are restored for execution (**thawing**).
This continuation machinery is not exposed through the public API.

Virtual Threads are executed by a `ForkJoinPool` backed by platform threads.
Platform thread executing a VT at a particular moment is called its **carrier**.
The proposed value of VTs is that supported blocking operations can unmount a virtual thread and free its carrier,
thus allowing applications to scale better by avoiding CPU starvation.
However, this is not always the case and blocking a VT may block its carrier, 
a situation called **carrier pinning**.

On the other hand, `kotlinx.coroutines` are **stackless**, i.e. on coroutine suspension,
the entire stack is unwinded and necessary state is saved on the heap (but not in the form of stack frames).
On continuation resumption, the call stack is restored using the saved state and compiler-generated state machines.
To make this possible, `suspend` functions are compiled in a specific way (with continuation passing style).
This is a practical reason for **code coloring** - the rule that non-`suspend` functions could not call `suspend` ones.
Such limitation is not imposed by thread-based concurrency, and by Virtual Threads in particular.

`kotlinx.coroutines` is also much more flexible than Virtual Threads.
Kotlin exposes public continuation APIs like `Continuation` and `ContinuationInterceptor`.
Execution of coroutines is also configurable through dispatchers, in contrast to 
Virtual Threads, whose carrier scheduler cannot be chosen through the public API.

# Previous Work

Nikita Bobko's [research](https://github.com/Kotlin/KEEP/blob/bobko/loom/notes/0007-project-loom.md) explores
Virtual Threads' integration with `suspend` functions, i.e. on the language level,
or `kotlinx.coroutines`, i.e. on the library level, from design perspective.
A short summary:
* **Language level:** Virtual Threads cannot replace Kotlin's implementation of `suspend` functions: 
  JVM continuations are not public, carrier threads cannot be chosen, 
  while Kotlin coroutines need precise control to support uses such as sequences and UI dispatch.
* **Library level:** Running `kotlinx.coroutines` on Virtual Threads is possible, 
  but Kotlin's continuation transformation would still be performed, so developers pay the price for it. 
  Also, blocking at unmarked points with VTs obscures suspension points and complicates the model.
* **Conclusion:** Use Virtual Threads directly where they fit.

# Research

## Problem

Why could it still be beneficial to run coroutines on top of Virtual Threads?

It can eliminate the need to distinguish `Dispatchers.Default` 
for CPU-bound work and `Dispatchers.IO` for blocking IO work.
If coroutines are backed by VTs, calling a blocking IO operation
can unmount the VT and release its carrier for other work.
This has the following benefits:
* The user does not need to carefully mark IO operations with `withContext(Dispatchers.IO)`.
* Many submitted operations use smaller amount of platform threads.
* Throughput of an application could potentially increase if it does a lot of blocking operations.

Virtual Threads can also help with **thread-pool starvation**.
All workers of `Dispatchers.Default` (or `IO`) can be waiting for some blocking operation, while a task that 
should produce the result for this operation is scheduled on the same dispatcher and thus can not be executed.
This situation causes a deadlock and prompted IJ Platform to implement [external compensation strategy](https://youtrack.jetbrains.com/issue/IJPL-214442).
Using Virtual Threads, this situation can be avoided: the task can execute on a carrier released by a waiting VT.

**Important to note**, that Virtual Threads could not solve the above problems completely,
as they do not release carriers on many file system operations, blocking native calls and spin-waiting.

## Question

The benefits of using Virtual Threads described above are of value for codebases
that do blocking operations, call potentially blocking Java libraries or even unknown third-party code (e.g. plugins).
If an application uses Kotlin-first libraries and APIs that appropriately
use async calls and adapt them for Kotlin coroutines,
then using Virtual Threads might provide no benefits at all.
Moreover, `Dispatchers.Default` and `Dispatchers.IO` use `CoroutineScheduler` which 
implements custom work-stealing algorithm and presumably is more performant for coroutine execution patterns.

In other words, overall performance difference of using Virtual Threads depends on two things:
* Potential throughput increase from reducing OS-thread blocking.
* Performance impact of switching from `kotlinx.coroutines` provided dispatchers to a custom one.

For the first point, the effect depends highly on the usage pattern of blocking operations.
So this research focuses mostly on the second point:
**what is the performance cost of switching to Virtual Threads backed dispatcher,
if the application uses suspending APIs without blocking worker threads?**.

# Integration

Kotlin coroutines may be executed on Virtual Threads in different ways.

## VT-per-task

The most simple approach is to spawn a Virtual Thread for each `dispatch`,
it can be achieved like this:

```kotlin
val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
// ...
withContext(virtualThreadDispatcher) {
    doStuff()
}
```

Note that people already use such integration, mainly as an alternative to `Dispatchers.IO`:
[termora](https://github.com/TermoraDev/termora/blob/f0acbb40845c4ab2ddeb588951204ee9a9f727a9/plugins/vnc/src/main/kotlin/app/termora/plugins/vnc/VNCViewer.kt#L45),
[ort-server](https://github.com/eclipse-apoapsis/ort-server/blob/ea2335c03d86a8b726a73ee53089b45308606afa/shared/coroutines/src/main/kotlin/Dispatchers.kt#L48).

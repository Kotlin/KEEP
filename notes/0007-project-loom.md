# What does Project Loom mean for Kotlin

* **Type**: Design notes
* **Author**: Nikita Bobko
* **Related issues**: https://github.com/Kotlin/kotlinx.coroutines/issues/3606,
  [KT-78467](https://youtrack.jetbrains.com/issue/KT-78467),
  [KT-79151](https://youtrack.jetbrains.com/issue/KT-79151),
  [KT-77632](https://youtrack.jetbrains.com/issue/KT-77632)

## Abstract

> Project Loom is intended to explore,
> incubate and deliver Java VM features and APIs built on top of them for the purpose of supporting easy-to-use,
> high-throughput lightweight concurrency and new programming models on the Java platform.
>
> Source: https://wiki.openjdk.org/display/loom/Main

So far, Project Loom delivers the following features:
1. [JEP 444](https://openjdk.org/jeps/444) Virtual Threads. VM feature. Delivered in Java 21
2. [JEP 505](https://openjdk.org/jeps/505) Structured concurrency. Java Stdlib feature. In Preview (as of 03 March 2026)
3. [JEP 506](https://openjdk.org/jeps/506) Scoped Values. Java Stdlib feature. Delivered in Java 25

This document reasons about the features in the context of Kotlin programming language.

## Table of contents

- [JEP 444. Virtual Threads](#jep-444-virtual-threads)
  - [Why suspension points matter](#why-suspension-points-matter)
  - [Integrating Virtual Threads and suspend functions together](#integrating-virtual-threads-and-suspend-functions-together)
  - [Integrating Virtual Threads and kotlinx.coroutines together](#integrating-virtual-threads-and-kotlinxcoroutines-together)
  - [What if Java opens up more API?](#what-if-java-opens-up-more-api)
- [JEP 505 Structured concurrency & JEP 506 Scoped Values](#jep-505-structured-concurrency--jep-506-scoped-values)

## JEP 444. Virtual Threads

**Glossary.** *carrier thread* is the thread that virtual threads are run on.

Virtual Threads is the biggest feature.
It's primarily a VM feature, and a very limited set of public APIs are exposed to developers.

The public API only allows two things:
1. To create a Virtual Thread: `Thread.ofVirtual().start {  }`/`Executors.newVirtualThreadPerTaskExecutor()`
2. To check if the current thread is Virtual: `Thread.currentThread().isVirtual`

Continuation is the basic primitive that Virtual Threads are implemented on top of.

```java
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

public class ContinuationsExample {
    public static void main(String[] args) {
        var scope = new ContinuationScope("generator");
        var cont = new Continuation(scope, () -> {
            System.out.println("Before yield");
            Continuation.yield(scope);
            System.out.println("After yield");
        });
        System.out.println("Before the first run");
        cont.run();
        System.out.println("Between runs");
        cont.run();
        System.out.println("After the second run");
    }
}
// java --add-exports java.base/jdk.internal.vm=ALL-UNNAMED ContinuationsExample.java
// Before the first run
// Before yield
// Between runs
// After yield
// After the second run
```

Unfortunately for Kotlin, `jdk.internal.vm.Continuation` API is not public.

The current Virtual threads design idea is that they are drop-in replacement for traditional physical threads,
because of that, currently, it's not possible to access the carrier thread,
and it's not possible to choose which carrier threads virtual threads are scheduled to run on
(which means that you can't use Virtual Threads for asynchronous programming on UI main thread).

As we can see Virtual Threads are not yet as powerful as Coroutines are.
Depending on your use case it can be either a good or a bad thing.

The good thing about Virtual threads is that they are simpler to use.
The good thing about Coroutines is that they give you more flexibility (and they are potentially safer in terms of compile time checks, as we will see it later)

Before jumping to discussing how Virtual Threads and coroutines could be integrated together,
it's worth understanding why suspension points matter.

### Why suspension points matter

`suspend` functions bring code coloring,
and there are two perspectives on that.

**The first perspective** says that the code coloring is the system flaw and a restriction.
It's a side effect of how `suspend` functions are implemented.
And now when we've got virtual threads, we all should migrate to the simpler framework that Java provides.

But there is **a second perspective** which says that code coloring is a feature that can help developers prevent concurrency bugs.
When the compiler knows at compile time where the suspension points are, it can analyze your code better.

For example, that's the thing in Swift.
In Swift, they even consider suspension points so important that they ask you to prefix all `async` function invocations with the `await` keyword,
which in itself doesn't have any semantical meaning, and merely is an act of acknowledgment that the operation is non-atomic
[\[1\]](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0296-async-await.md#await-expressions)
[\[2\]](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0296-async-await.md#suspension-points)
[\[3\]](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0306-actors.md#actor-reentrancy).
In Swift, code coloring and suspension points are the core part of their data-race-free, deadlock-free design.

Let us demonstrate how suspension points and code coloring can help you to prevent concurrency bugs in Kotlin.
Imagine modeling banking and fund transfers between accounts.

```kotlin
class BankAccount(initBalance: Int = 0) {
    private val threadConfinement = Dispatchers.Default.limitedParallelism(1)
    private var balance: Int = initBalance

    suspend fun changeBalanceBy(value: Int) = withContext(threadConfinement) { balance += value }
    suspend fun getBalance(): Int = withContext(threadConfinement) { balance }

    suspend fun transfer(to: BankAccount, amount: Int) = withContext(threadConfinement) {
        if (amount > balance) error("Insufficient funds")
        to.changeBalanceBy(amount)
        balance -= amount
    }
}
```

Can you already see the concurrency bug?
The bug is the classic double spending concurrency bug.
You can observe the bug by running the following stress test:

<details>

```kotlin
fun main() {
    runBlocking(Dispatchers.Default) {
        repeat(1000) { // 1000 repeats should be enough
            try {
                val acc1 = BankAccount(100)
                val acc2 = BankAccount(0)
                val acc3 = BankAccount(0)

                coroutineScope {
                    launch { acc1.transfer(acc2, 60) }
                    launch { acc1.transfer(acc3, 60) }
                }
                println("Double spending detected! ${acc1.getBalance()} ${acc2.getBalance()} ${acc3.getBalance()}")
                exitProcess(1)
            } catch (_: IllegalStateException) {}
        }
    }
}
```

</details>

The essence of the bug lies in how we decided to protect our internal mutable state `balance`.
Instead of using `kotlinx.coroutines.sync.Mutex`, we went with the _thread confinement_ approach via `Dispatchers.Default.limitedParallelism(1)`.

For one, thread confinement has an advantage over `Mutex` by being deadlock-free.

For another, it has less strong atomicity and transactionality guarantees.
In other words, thread confinement has the "problem" of _interleaving_.
Interleaving means that the code is not atomic,
it may be interrupted, and the dispatcher returned by `limitedParallelism` function may start doing some other job if the current job suspends.
To our advantage, the semantics of interleaving is precise and well-defined.
The interleaving can happen only at locations known at compile time - the suspension points.

`to.changeBalanceBy(amount)` is our suspension point that splits up the precondition check and the change of the current balance.
To fix the bug above we just need to swap the last two lines of the `transfer` function, like so:

```diff
     suspend fun transfer(to: BankAccount, amount: Int) = withContext(threadConfinement) {
         if (amount > balance) error("Insufficient funds")
-        to.changeBalanceBy(amount)
         balance -= amount
+        to.changeBalanceBy(amount)
     }
```

And we can even improve it,
to make sure that we won't reintroduce the concurrency bug in the future,
let's declare the following helper function:

```kotlin
// Related issue: https://youtrack.jetbrains.com/issue/KT-17260
inline fun <R> noSuspend(crossinline body: () -> R): R = body()
```

the sole purpose of which is to forbid `suspend` calls in the lambda.
So that the final version of the `transfer` function is as follows:

```kotlin
    suspend fun transfer(to: BankAccount, amount: Int) = withContext(threadConfinement) {
        noSuspend { // Prevent double-spending bug
            if (amount > balance) error("Insufficient funds")
            // ↑ ↓ No suspension points can now sneak between these two lines
            balance -= amount
        }
        to.changeBalanceBy(amount)
    }
```

Tada! You've got a deadlock-free, data-race-free implementation of `BankAccount`,
but you - the developer have to pay attention to suspension points.
Luckily, you know them at compile time.

### Integrating Virtual Threads and suspend functions together

> _CPS-transformation_ stands for Continuation Passing Style transformation. https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html#continuation-passing-style

Kotlin `suspend` functions are implemented via CPS code transformation.
Virtual threads are implemented natively by the JVM itself.
So the first idea that comes to mind: what if we could leverage the JVM feature to implement `suspend` functions?
It may decrease the bytecode footprint, simplify debugging, and significantly simplify the Kotlin compiler's code.

Unfortunately, with the limited APIs that Java/JVM exposes we find it hardly feasible.
`suspend` functions are much more powerful than Virtual Threads.
1. `suspend` functions allow you to choose which threads to schedule your asynchronous work on.
  It's very important for programming UIs where you submit asynchronous work to the main thread.
  Virtual Threads don't allow you to choose the carrier thread.
2. `suspend` functions are generally not about parallel computations.
  There are quite a few other features in Kotlin that are implemented using `suspend` functions.
  In case of `kotlin.sequences.sequence`, suspension means that the next element of the Sequence is produced (concurrent but not parallel computation).
  In case of `kotlin.DeepRecursiveFunction`, suspension means that the stack should be saved to the heap, but the computation should continue.

### Integrating Virtual Threads and kotlinx.coroutines together

Ok, in the previous section, we figured that we cannot integrate `suspend` functions and Virtual threads together.
What if we dampened our appetite and tried to leverage Virtual Threads only on the library level - in kotlinx.coroutines?

Every new `CoroutineScope.launch` could be identical to starting a virtual thread,
and if we see that we run on a virtual thread dispatcher, then we could block instead of suspending:

```kotlin
////////////////////////////////
// kotlinx.coroutines library //
////////////////////////////////

/**
 * Naive implementation of CoroutineDispatcher. Probably, it doesn't account for cancellation and
 * who-knows-what-else
 */
@OptIn(InternalCoroutinesApi::class)
object JvmVirtualThreadsCoroutineDispatcher : CoroutineDispatcher(), Delay {
    private val pool = ConcurrentHashMap<CoroutineVirtualThreadUniqueToken, ExecutorService>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val token = context[CoroutineVirtualThreadUniqueToken]
        if (token == null) { // dispatch must be exception free
            Exception("Every coroutine submitted to " +
                "${JvmVirtualThreadsCoroutineDispatcher::class.simpleName} " +
                "must have a token assigned to it")
                .printStackTrace()
            exitProcess(1)
        }
        // Keep every coroutine pinned to the assigned virtual thread
        pool.computeIfAbsent(token) { Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()) }
            .submit(block)
    }

    internal fun cleanupVirtualThread(token: CoroutineVirtualThreadUniqueToken) {
        pool.remove(token)?.close()
    }

    // For kotlinx.coroutines.delay implementation
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        check(continuation.context[CoroutineVirtualThreadUniqueToken] != null)
        // Yay! We can just sleep instead of suspending (todo: support cancellation)
        Thread.sleep(timeMillis)
        continuation.resume(Unit)
    }
}

/**
 * The token helps to pin coroutines to assigned virtual threads.
 * Every time a new virtual thread needs to be spawned,
 * the new token must be created manually by the coroutine builder functions (such as async, launch, runBlocking)
 */
internal class CoroutineVirtualThreadUniqueToken : AbstractCoroutineContextElement(Companion), AutoCloseable {
    companion object : CoroutineContext.Key<CoroutineVirtualThreadUniqueToken>
    override fun close(): Unit = JvmVirtualThreadsCoroutineDispatcher.cleanupVirtualThread(this)
}

// That's how kotlinx.coroutines.runBlocking new implementation should look like
@OptIn(ExperimentalStdlibApi::class)
public fun <T> myRunBlocking(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T
): T = when (context[CoroutineDispatcher] === JvmVirtualThreadsCoroutineDispatcher) {
    true -> CoroutineVirtualThreadUniqueToken().use { token -> runBlocking(context + token, block) }
    false -> runBlocking(context, block)
}

// That's how kotlinx.coroutines.launch new implementation should look like
@OptIn(ExperimentalStdlibApi::class)
fun CoroutineScope.myLaunch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit
): Job = when ((coroutineContext + context)[CoroutineDispatcher] === JvmVirtualThreadsCoroutineDispatcher) {
    true -> {
        val token = CoroutineVirtualThreadUniqueToken()
        launch(context + token, start, block).also { it.invokeOnCompletion { token.close() } }
    }
    false -> launch(context, start, block)
}

////////////
// stdlib //
////////////

// That's how kotlin.coroutines.suspendCoroutine new implementation should look like
@OptIn(ExperimentalStdlibApi::class)
public suspend inline fun <T> mySuspendCoroutine(crossinline block: (Continuation<T>) -> Unit): T {
    if (coroutineContext[CoroutineDispatcher] === JvmVirtualThreadsCoroutineDispatcher) {
        // Block instead of suspending
        val continuation = BlockingContinuation<T>(coroutineContext)
        block(continuation)
        return continuation.waitForResult().getOrThrow()
    } else {
        // The good-old suspending implementation
        return suspendCoroutine(block)
    }
}

@PublishedApi internal class BlockingContinuation<T>(override val context: CoroutineContext) : Continuation<T> {
    private var result: CompletableFuture<Result<T>> = CompletableFuture()
    @PublishedApi internal fun waitForResult(): Result<T> = result.get()
    override fun resumeWith(result: Result<T>): Unit =
        check(this.result.complete(result), fun() = "The continuation must be resumed only once")
}
```

While such integration is technically possible, practically, it raises a couple of concerns:

1. Developers have already paid the performance cost of CPS-transformation, and the cost of accurately coloring their functions, but then throw it all away.
2. Even worse, the effort they've spent on accurately coloring their functions is now completely irrelevant,
   because Virtual Threads can block at arbitrary points,
   you don't control it at compile time, and as we saw it in the [previous section](#why-suspension-points-matter),
   controlling your suspension points can give you a lot.
3. It simply complicates the mental model.
   It is already known that the coroutines framework in Kotlin is not the easiest thing to master.
   Playing the "it either suspends or blocks" kind of game complicates the model even further.

Ultimately, this kind of integration is already so close to using bare-bones virtual threads that it naturally raises the question:
why not just use the virtual threads API directly?

That's why instead of trying to make Coroutines _somehow_ work on Virtual Threads and combine two incompatible models,
we decide to acknowledge that there are things that Virtual Threads are simply better at,
and there are things that Coroutines are simply better at.
Those are different abstractions, use the one that fits your job.
In Kotlin, you have the power of two!
It's perfectly fine to call Virtual Threads JDK API from Kotlin.

> [!NOTE]  
> The attentive reader will notice that we reference `JvmVirtualThreadsCoroutineDispatcher` from inside `mySuspendCoroutine`.
> `mySuspendCoroutine` is located in stdlib, while `JvmVirtualThreadsCoroutineDispatcher` is located in kotlinx.coroutines library.
> This problem can be fixed with the common programming inversion of control technique or with the help of `ContinuationInterceptor`s (if the API is tweaked a bit).
> We left this part out to keep the code snippet as small as possible.

### What if Java opens up more API?

If Java opens up `jdk.internal.vm.Continuation` API, it's a totally different situation.
With native Continuations supported by JVM,
Kotlin no longer needs to do any CPS-transformations itself.
The whole `suspend` feature becomes expressible via Java Continuations.
The only problem is that since everyone can now "suspend",
it is no longer possible to guarantee that all suspension points are known at compile time,
which is again a [useful property](#why-suspension-points-matter).

If Java opens up a possibility to choose which carrier threads virtual threads can run on,
it's almost equivalent to opening up the Continuation API
because `Continuation.yield` (suspending) is equivalent to `LockSupport.park`,
`Continuation.run` (resuming) is equivalent to `LockSupport.unpark`.
Except, depending on the details, we might need to create an additional coordinator thread that does parking and unparking.

## JEP 505 Structured concurrency & JEP 506 Scoped Values

[JEP 505](https://openjdk.org/jeps/505) is about adding a structured concurrency API.
Similar to what the `launch` function from coroutines already does.

[JEP 506](https://openjdk.org/jeps/506) is about adding "bounded ThreadLocal values".

Neither of the proposals touches the JVM, and they only change the Java stdlib.
Neither of these JEPs provide primitives that Kotlin could take advantage of.

But kotlinx.coroutines could definitely provide better integrations with these APIs:
[KT-78467](https://youtrack.jetbrains.com/issue/KT-78467),
[KT-79151](https://youtrack.jetbrains.com/issue/KT-79151),
[KT-77632](https://youtrack.jetbrains.com/issue/KT-77632)

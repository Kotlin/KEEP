# What does Project Loom mean for Kotlin

* **Type**: Design notes
* **Author**: Nikita Bobko

## Abstract

> Project Loom is to intended to explore,
> incubate and deliver Java VM features and APIs built on top of them for the purpose of supporting easy-to-use,
> high-throughput lightweight concurrency and new programming models on the Java platform.
>
> Source: https://wiki.openjdk.org/display/loom/Main

So far, Project Loom is about the following features:
1. [JEP 444](https://openjdk.org/jeps/444) Virtual Threads. VM feature. Delivered in Java 21
2. [JEP 505](https://openjdk.org/jeps/505) Structured concurrency. Java Stdlib feature. Fifth Preview
3. [JEP 506](https://openjdk.org/jeps/506) Scoped Values. Java Stdlib feature. Planned for Java 25

## Table of contents

- [JEP 444. Virtual Threads](#jep-444-virtual-threads)
  - [Why suspension points matter](#why-suspension-points-matter)
  - [What if Java opens up more API?](#what-if-java-opens-up-more-api)
- [JEP 505. Structured concurrency](#jep-505-structured-concurrency)
- [JEP 506. Scoped Values](#jep-506-scoped-values)

## JEP 444. Virtual Threads

**Glossary.** *carrier thread* is the thread that virtual threads are run on.

Virtual Threads is the biggest feature.
It's primarly a VM feature, and a very limited set of public APIs are exposed to developers.

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
and it's not possible to choose which threads virtual threads are scheduled to run on
(which means that you can't use Virtual Threads for asynchronous programming on UI main thread).

As we can see Virtual Threads are not yet as powerful as Coroutines are.
Depending on your use case it can be either a good or a bad thing.

The good thing about Virtual threads is that they are simplier to use.
The good thing about Coroutines is that they give your more flexibility (and they are potentially safer in terms of compile time checks, as we will see it further)

Before jumping to discussing how Virtual Threads and coroutines could be integrated together,
it's worth to understand why suspension points matter.

### Why suspension points matter

`suspend` functions bring code coloring,
and there are two perspectives on that.

**The first perspective** says that the code coloring is the system flaw and a restriction.
It's a side-effect of how `suspend` functions are implemented.
And now when we've got virtual threads, we all should migrate to the simplier framework that Java now provides.

But there is **a second perspective** which says that code coloring is a feature that can help developers prevent concurrency bugs.
When the compiler knows at compile time where the suspension points are, it can analyze your code better.

For example, that's the thing in Swift.
In Swift, they even consider suspension points so important that they ask you to prefix all `async` function invocations with the `await` keyword,
which on itself doesn't have any semantical meaning, and merely is an act of acknowledgment that the operation is non-atomic
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

For another, it has less stronger atomicity and transactionality guarantees.
In other words, thread confinement has the "problem" of _interleaving_.
Interleaving means that the code is not atomic,
it may be interrupted, and the dispatcher returned by `limitedParallelism` function may start doing some other job if the current job suspends.
To our advantage, the semantics of interleaving is precise and well defined.
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
So that the final version of `transfer` function is as follows:

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

Tada! You've got deadlock-free, data-race-free implementation of `BankAccount`,
but you - the developer have to pay attention to suspension points.
Luckily, you know them at compile time.

### Potential integration \#1. Deprecate Kotlin compiler CPS-transformation machinary, create a Virtual Thread for every Coroutines

> _CPS-transformation_ stands for Continuation Passing Style transformation.

Coroutines are sometimes advertised as lightweight threads.
Virtual Threads are also advertised as lightweight threads.
So the first naive and the most radical idea is to stop doing CPS-transformations in Kotlin, and always use Virtual Threads that Java provides.
The complicated operation of suspending translates into a good old thread blocking.
It's a language-level integration.

Unfortunately, Coroutines are not always translatable to Virtual Threads.
Coroutines are more powerful than Virtual Threads.
1. Coroutines allow you to choose which threads to schedule your asynchronous work on.
  It's very important for programming UIs where you submit asynchronous work to the main thread.
  Virtual Threads don't allow you to choose the carrier thread.
2. Coroutines and `suspend` functions are generally not about concurrent computations.
  Suspension doesn't necessarily mean that the computation should be paused.
  There are quite a few features in Kotlin that are implemented using `suspend` functions.
  In case of `kotlin.sequences.sequence`, suspension means that the next element of the Sequence is produced.
  In case of `kotlin.DeepRecursiveFunction`, suspension means that the stack should be saved to the heap, but the computation should continue.

### Potential integration \#2. Block instead of suspending in kotlinx.coroutines

Since we rejected the previous language-level integration,
let's explore kotlinx.coroutines library-level integration.
In all "leaf-suspending" functions,
kotlinx.coroutines could try to check if we run on a Virtual Thread and block instead of suspending:

```kotlin
public suspend fun delay(duration: Duration) {
    if (Thread.currentThread().isVirtual) {
        Thread.sleep(duration.inWholeMilliseconds)
    } else {
        // The previous suspending delay logic
    }
}
```

This naive approach will lead to deadlocks in a simple case where the coroutines machinary runs on a single virtual thread:
```

```





As we saw, we can't run coroutines unconditionally on Virtual Threads.
But if the user runs their coroutine on a Virtual Thread explicitly themselves, we could theoretically

Ok, now when we saw that 




### Potential integration \#1. Let's run Coroutines on Virtual Threads

One of the suggested ideas was to run Coroutines on Virtual Threads.
Since blocking/parking should be cheap on Virtual Threads, instead of suspending,
we could just park the thread if we see that we run on a virtual thread.

The problem with this suggestion is that you've already paid the performance price of CPS-transformation,
and you've already paid the price of accurately coloring your functions,
and then you want to throw it all away.

Besides, the effort you've spent on accurately coloring your functions is now completely irrelevant,
because Virtual Threads can block at arbitrary points,
you don't control it at compile time, and as we saw it in the [previous section](#why-suspension-points-matter),
controlling your suspension points can give you a lot.

The only reasonable scenario that we found for which you may want to block instead of suspending is to simplify debugging.
By blocking, we establish 1-to-1 relation between Coroutines and Virtual Threads.
That way, you won't loose your stacktraces, and a lot of JVM tooling is thread-aware which for sure helps for debugging.
The problem here is that it's not always possible to ignore the current `CoroutineDispatcher` and use 

So instead of trying to make Coroutines _somehow_ work on Virtual Threads and combine uncombinable,
we decide to acknowledge that there are things that Virtual Threads are simply better at,
and there are things that Coroutines are simply better at.
Those are different abstractions, use the one that fits your job.
It's perfectly fine to call Virtual Threads JDK API from Kotlin.

### Potential integration \#2. Let's create a dispatcher that spawns Virtual Threads

The general advice is to never block your coroutines.
You should prefer suspending over blocking.
But in the cases when people do mistakes and block their coroutines by mistake or simply because there is no other API,
we could try to take advantage of Loom.

The idea is to allow people to explicitly run their coroutines on Virtual Threads with some sort of API.
We see 3 alternatives:

**Alternative 1.**
Add `Dispatchers.JvmVirtualThreads` dispatcher.
The implementation could be roufly `Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()`.
In fact, users can already do that themselves.
And some users already [do that](https://github.com/search?q=%22Executors.newVirtualThreadPerTaskExecutor%28%29.asCoroutineDispatcher%28%29%22+lang%3AKotlin&type=code).

**Alternative 2.**
Change the existing `Dispatchers.IO` to use Virtual Threads by default.
The consequences of slight behavior change are not entirely clear.
For example, `Dispatchers.IO` has limited thread parallelism, while `Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()` does not.

**Alternative 3.**
The dispatcher implementation should use `StructuredTaskScope`.
Given that `StructuredTaskScope` API is structured (one cannot create daemon dispatcher),
```
class VirtualThreadsScopedDispatcher private constructor(private val scope: StructuredTaskScope<Void, Void>) :
    CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        scope.fork<Void>(block)
    }

    companion object {
        fun <R> runBlocking(
            context: CoroutineContext = EmptyCoroutineContext,
            block: suspend CoroutineScope.() -> R
        ): R = StructuredTaskScope.open<Void>().use { scope ->
            try {
                runBlocking(context + VirtualThreadsScopedDispatcher(scope), block)
            } finally {
                scope.join()
            }
        }
    }
}
```
The upside of this approach is that 

Note: alternative 3 is not mutually exclusive with alternatives one and two, since  
introduce one more dispatcher alongside the existing dispatchers:


```diff
 public actual object Dispatchers {
     @JvmStatic
     public actual val Default: CoroutineDispatcher = DefaultScheduler

     @JvmStatic
     public actual val Main: MainCoroutineDispatcher get() = MainDispatcherLoader.dispatcher

     @JvmStatic
     public actual val Unconfined: CoroutineDispatcher = kotlinx.coroutines.Unconfined
+
+    @JvmStatic
+    public val JvmVirtualThreads: CoroutineDispatcher = TODO()
```



The open question here is what form this dispatcher should take.
Alternatives:

1. Change the existing `Dispatchers.IO` to use Virtual Threads by default.

2. Use `StructuredTaskScope` to implement `CoroutineDispatcher`.

3. foo bar


In fact, users can already do that themselves.
And some users already [do that](https://github.com/search?q=%22Executors.newVirtualThreadPerTaskExecutor%28%29.asCoroutineDispatcher%28%29%22+lang%3AKotlin&type=code).

### What if Java opens up more API?

If Java opens up `jdk.internal.vm.Continuation` API, it's a totally different situation.
With native Continuations supported by JVM,
Kotlin no longer needs to do any CPS-transformations itself.
The whole `suspend` feature becomes expressible via Java Continuations.
The only problem is that since everyone can now "suspend",
it is no longer possible to guarantee that all suspension points are known at compile time,
which is again a [useful property](#why-suspension-points-matter).

If Java opens up a possibility to choose which carrier threads virtual threads can run on,
It's almost equivalent to opening up the Continuation API
because `Continuation.yield` (suspending) is equivalent to `LockSupport.park`,
`Continuation.run` (resuming) is equivalent to `LockSupport.unpark`.
Except, depending on the details, we might need to create an additional coordinator thread that does parking and unparking.

## JEP 505. Structured concurrency & JEP 506. Scoped Values

It's easier to discuss these two proposals as a one thing.
Neither of the proposals touch the JVM, and they only change the stdlib.

JEP 505 is about adding a structured concurrency API.
Similar, to what `launch` function from coroutines already does.

JEP 506 is about adding "bounded ThreadLocal values".

On their own, neither of these JEPs provide anything useful to Kotlin.

It's an oversimplification, but JEP 505 is essentially about 

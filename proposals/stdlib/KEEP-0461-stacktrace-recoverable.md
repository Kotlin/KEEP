# Assistance for `kotlinx.coroutines` stacktrace recovery in the standard library

* **Type**: Standard Library API proposal
* **Prototype authors**: Vsevolod Tolstopyatov, Roman Elizarov
* **Proposal author**: Dmitry Khalanskiy
* **Proposal contributors**: Leonid Startsev, Ilya Gorbunov, Filipp Zhinkin
* **Status**: Prototype available in `kotlinx.coroutines` as `CopyableThrowable`

## Summary

Introduce an API to the Standard Library that allows library authors to make
their classes inheriting from `Throwable` recognized by the
`kotlinx-coroutines-debug` stacktrace recovery when it doesn't happen naturally.

## Motivation and use cases

### Overview of `CopyableThrowable`

#### Basics of stacktrace recovery

It is common that code using `kotlinx.coroutines` uses several communicating
coroutines. If one coroutine fails with an exception, other ones can receive
that exception and rethrow it.

Example:

```kotlin
supervisorScope {
    // Create one coroutine
    val computation = async<Int> {
        throw IllegalStateException("Some coroutine failed")
    }
    // Create another coroutine
    async {
        try {
            // Another coroutine an exception
            computation.await()
        } catch (e: IllegalStateException) {
            println("Caught $e")
        }
    }
}
```

(Runnable version: <https://pl.kotl.in/r-6VfBDtp>)

Note that the code where the exception is thrown is not being run *inside* the
`await` call!
Instead, the code in the first `async` runs concurrently with the second
`async`, possibly in another thread, and the exception is stored in the
`computation` object. `await` then only retreives the exception and rethrows it.

There is a usability problem with this: the stacktrace of the exception thrown
from `await` does not include the `await` or its caller.

Example:

```kotlin
supervisorScope {
    val computation = async<Int> {
        throw IllegalStateException("Some coroutine failed")
    }
    async {
        try {
            foo(computation)
        } catch (e: IllegalStateException) {
            println(e.stackTraceToString())
        }
    }
}

suspend fun foo(deferred: Deferred<Int>) {
    bar(deferred)
    yield() // prevent the compiler from optimizing the function out
}

suspend fun bar(deferred: Deferred<Int>) {
    deferred.await()
    yield() // prevent the compiler from optimizing the function out
}
```

(Runnable version: <https://pl.kotl.in/CiNZNHBqo>)

This code prints:

```
java.lang.IllegalStateException: Some coroutine failed
	at FileKt$main$2$1$computation$1.invokeSuspend(File.kt:8)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
	at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:793)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:697)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:684)
```

Notice, in particular, the absence of `foo` and `bar`.
It is natural that the exception created entirely in one coroutine and rethrown
in another one wouldn't know about the other coroutine--yet it's inconvenient
for debugging.

The `kotlinx-coroutines-debug` artifact for the JVM attempts to work around
this. It makes `await` and other similar functions that receive data from
other coroutines throw *modified* versions of the original exceptions, so that
the modified versions also include the stack trace of the receiving coroutine.

With stacktrace recovery enabled, the code above prints:

```
java.lang.IllegalStateException: Some coroutine failed
	at kotlinx.coroutines.exceptions.StackTraceRecoveryStdlibInterfaceTest$testForKeep$1$1$1$computation$1.invokeSuspend(StackTraceRecoveryStdlibInterfaceTest.kt:106)
	at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt:42)
	at kotlinx.coroutines.exceptions.StackTraceRecoveryStdlibInterfaceTestKt.bar(StackTraceRecoveryStdlibInterfaceTest.kt:127)
	at kotlinx.coroutines.exceptions.StackTraceRecoveryStdlibInterfaceTestKt.foo(StackTraceRecoveryStdlibInterfaceTest.kt:122)
	at kotlinx.coroutines.exceptions.StackTraceRecoveryStdlibInterfaceTest$testForKeep$1$1$1$1.invokeSuspend(StackTraceRecoveryStdlibInterfaceTest.kt:111)
Caused by: java.lang.IllegalStateException: Some coroutine failed
	at kotlinx.coroutines.exceptions.StackTraceRecoveryStdlibInterfaceTest$testForKeep$1$1$1$computation$1.invokeSuspend(StackTraceRecoveryStdlibInterfaceTest.kt:106)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
	at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:586)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:807)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:717)
	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:704)
```

`_COROUTINE._BOUNDARY._` separates the stacktrace of the failing coroutine
(the top) from the stacktrace of the coroutine receiving the failure
(the bottom).
`Caused by` lists the exception that was thrown originally,
with no modifications applied to it.

#### Copying exceptions for stacktrace recovery

Stacktrace recovery captures the moment a coroutine is about to rethrow an
exception received from some other coroutine--and injects extra logic there.

`kotlinx.coroutines` can't modify the original exception--that would easily
break if several coroutines were to receive the same exception at the same time.
Instead, stacktrace recovery analyzes the exception object using the JVM
reflection and attempts to create a *copy* that can then be modified.

Some exceptions are easier to copy that others.
If an exception is of a type that exposes constructors of a form used in
the JDK by convention,
making a copy means calling such a constructor.
The conventional forms are:

- `(String, Throwable?)`--probably `(message, cause)`.
- `(String)`--probably `(message)`.
- `(Throwable?)`--probably `(cause)`.
- `()`--a primary constructor.

For example, the `IllegalStateException` used in the snippet above works
"out of the box", since it exposes all of the constructor forms listed above--
though any one of them would also suffice.

#### Exceptions that `kotlinx.coroutines` can't copy

Consider this exception class:

```kotlin
class FileEditException(val line: Int, message: String): IllegalStateException(
    "When editing line $line: " + message
)
```

Stacktrace recovery does not understand how to handle the extra `line`
parameter!
There is no clear way of making a reasonable copy of a `FileEditException`.
To avoid making blind guesses and possibly ending up with a broken copy of an
exception, `kotlinx.coroutines` leaves the original exception untouched in
this scenario.

Example:

```kotlin
supervisorScope {
    val computation = async<Int> {
        throw FileEditException(15, "Some coroutine failed")
    }
    async {
        try {
            foo(computation)
        } catch (e: FileEditException) {
            println(e.stackTraceToString())
        }
    }
}

suspend fun foo(deferred: Deferred<Int>) {
    bar(deferred)
    yield()
}

suspend fun bar(deferred: Deferred<Int>) {
    deferred.await()
    yield()
}
```

When running this code, whether stacktrace recovery is enabled does not make
a difference: because stacktrace recovery does not understand how to copy
a `FileEditException`, it's left as is.

#### Opting into stacktrace recovery

`kotlinx.coroutines.CopyableThrowable`--or with this proposal,
`kotlin.coroutines.debug.StackTraceRecoverable`--
can be used to opt into stacktrace recovery by implementing the copying logic
explicitly:

```kotlin
import kotlinx.coroutines.CopyableThrowable

class FileEditException(val line: Int, message: String): IllegalStateException(
    "When editing line $line: " + message
), CopyableThrowable<FileEditException> {
    override fun createCopy(): FileEditException =
        FileEditException(line, message!!.substringAfter(": "))
}
```

Stacktrace recovery checks if an exception implements `CopyableThrowable`.
If so, `createCopy` is used to obtain a new copy, and even the
constructor discovering logic is not being used.
Because of this, `CopyableThrowable` can also be used for modifying the
behavior of stacktrace recovery when the default one is incorrect.

### Using `CopyableThrowable` in third-party libraries

`kotlinx.coroutines.CopyableThrowable` has a glaring problem:
for a library author implementing a `Throwable` subclass to opt into
stacktrace recovery, they have to introduce a `kotlinx.coroutines` dependency.
For many libraries, this is not acceptable.

On the JVM, using a `compileOnly` dependency might work (at the cost of
confusing bytecode processors like ProGuard and R8), but for an exception
class introduced in multiplatform code, avoiding a `kotlinx.coroutines`
runtime dependency requires writing `expect`/`actual` declarations.

This indicates the `CopyableThrowable` needs to be reworked.

## Proposed API

```kotlin
package kotlin.coroutines.debug // note: not kotlinX

/**
 * A [Throwable] that is aware of stacktrace recovery and explicitly supports
 * a procedure for copying it.
 *
 * Whenever an exception object is created in one concurrent computation,
 * stored in some shared memory, and then accessed by and rethrown in another
 * concurrent computation, the stacktrace of rethrowing computation is going
 * to be absent from the exception's stacktrace.
 *
 * To work around this, asynchronous frameworks may establish a convention where
 * an exception received from a different concurrent computation is getting
 * *copied* first, with the copy getting populated with the stacktrace of the
 * caller and only then rethrown.
 *
 * In `kotlinx.coroutines`, there is such a convention available on the JVM as
 * an opt-in, called "stacktrace recovery".
 * It is available by default to all exception classes that have one of the
 * following constructors:
 * - `(String, Throwable?)`
 * - `(String)`
 * - `(Throwable?)`
 * - a constructor with no parameters.
 *
 * Implementing [StackTraceRecoverable] in a [Throwable] subclass allows
 * its instances to be copied for stacktrace recovery when an asynchronous
 * framework can not determine a copying procedure on its own.
 *
 * Usage example:
 *
 * class BadResponseCodeException(
 *     val responseCode: Int
 * ) : Exception(), StackTraceRecoverable<BadResponseCodeException> {
 *
 *     override fun copyForStackTraceRecovery(): BadResponseCodeException {
 *         val result = BadResponseCodeException(responseCode)
 *         result.initCause(this)
 *         return result
 *     }
 *
 *
 * Alternatively, this interface can be used to opt out
 * from stacktrace recovery even in scenarios when an asynchronous framework
 * can heuristically find a copying procedure.
 * In that scenario, [copyForStackTraceRecovery] needs to return `null`.
 *
 * In `kotlinx.coroutines`, the copying mechanism is only available on the JVM,
 * but this interface is available on all targets so that exceptions
 * implemented in common code can also support stacktrace recovery on the JVM.
 */
interface StackTraceRecoverable<T>
where T: Throwable, T: StackTraceRecoverable<T> {
    /**
     * Creates a copy of `this` for stacktrace recovery.
     *
     * For better debuggability, it is recommended to use original exception as
     * the [cause][Throwable.cause] of the resulting one.
     * The stack trace of the copied exception will be overwritten by
     * stacktrace recovery machinery using the `Throwable.setStackTrace` call.
     *
     * An exception can opt-out of copying by returning `null` from this function.
     *
     * Suppressed exceptions of the original exception should be left uncopied,
     * to avoid circular exceptions.
     *
     * This function may create a copy with a modified [message][Throwable.message],
     * but note that the copy can be later recovered as well,
     * so the message modification code should handle this situation correctly
     * (e.g. by also storing the original message and checking it)
     * to produce a human-readable result.
     */
    fun copyForStackTraceRecovery(): T?
}
```

## Alternatives

We considered other options that could also serve the use case of implementing
`kotlinx.coroutines.CopyableThrowable` in a library that doesn't depend on
`kotlinx.coroutines`.

1. Establish a non-compiler-enforced convention, e.g. one where the existence
   of `fun copyForStackTraceRecovery(): Throwable` in a `Throwable`
   would be discovered by reflection.
   This is similar to the Java serialization API, which requires introducing
   `readObject`/`writeObject` methods that don't implement any interface.
   * Pro: even non-Kotlin libraries can follow this convention if necessary.
   * Pro: no extra API needs to be added, `kotlinx.coroutines` can simply
     start accepting this convention on its own.
   * Con: one typo, and stacktrace recovery will silently stop working,
     with no help from the compiler.
     This is a problem that statically typed languages are not supposed to
     suffer from.
   * Con: non-idiomatic.
2. Introduce a lightweight artifact, like `kotlinx-coroutines-api`, only
   containing APIs that even projects not using `kotlinx.coroutines` may wish to
   implement for compatiblity.
   * Pro: even non-Kotlin libraries can depend on this library if necessary,
     if we make it independent from the Kotlin standard library.
   * Pro: clear separation of concerns.
   * Con: an artifact for a single interface feels excessive.
     We did not manage to think of another interface we'd like to put in there.
   * Con: it may be difficult to convince third-party library authors to learn
     about stacktrace recovery and the interplay between
     `kotlinx-coroutines-core` and `kotlinx-coroutines-api`.
     The interface is just too cumbersome.

Putting this interface into the standard library seems like the best option,
as this creates a low-friction way of enhancing exception classes
with stacktrace recovery support.
The downside is that non-Kotlin projects can't realistically opt into
`kotlinx.coroutines` stacktrace recovery, but we haven't observed any demand
for that.

## Roadmap

- [x] Implement a reflection-based lookup for `copyForStackTraceRecovery`
  in `kotlinx.coroutines`, so that new implementations of
  `StackTraceRecoverable` immediately start working even with slightly older
  versions of `kotlinx.coroutines`.
- [ ] Introduce `StackTraceRecoverable` into the standard library.
- [ ] Remove the reflection-based lookup in `kotlinx.coroutines`, replacing it
  with an `is`-check.
- [ ] Deprecate `kotlinx.coroutines.CopyableThrowable` in favor of the new
  Standard Library interface.



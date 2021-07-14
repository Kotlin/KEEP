# Cancellable continuation API

* **Type**: Standard Library API proposal
* **Author**: Vsevolod Tolstopyatov
* **Status**: Polymorphic keys implemented in 1.3.70, cancellability is prototyped.
* **Prototype**: Implemented
* **Discussion**: https://github.com/Kotlin/KEEP/issues/214

## Key takeaways 

The goal of this proposal is to introduce API for cooperative cancellable computations that is seamlessly
interoperable with third-party libraries.

* Add `suspendCancellableCoroutine` that is a cancellable counterpart of `suspendCoroutine`
* Zero-dependency for library authors
* A mechanism for pluggable third-party cancellation mechanisms 
* Interoperability with `kotlinx.coroutines` cancellation

## Motivation and use cases

In a real-world application, it is often beneficial to have fine-grained control over asynchronous computations.
For example, if a connection is closed or a user cancels their request, it would be a good idea to immediately cancel
all computations, related to the closed session: outcoming database calls, supplementary connections and launched coroutines.

In Kotlin, `kotlinx.coroutines` library is successfully solving this task for more than two years, and the overall feedback was mostly positive.
C# have the first-class support of cancellation, and Java [is looking in that direction as well](https://cr.openjdk.java.net/~rpressler/loom/loom/sol1_part2.html#more-on-interruption-and-cancellation).
Concurrency primitives such as `Future` type is Swift and Java support direct cancellation as the only available mechanism in the language.

Having a library works for application development, but not for other libraries.
On the one hand, if a library does not depend on a `kotlinx.coroutines` and provides a `suspend`-based API that can be cancellable, adding 
`kotlinx.corotuines` is often undesirable and creates a maintenance burden related to compatibility with different Kotlin versions.
On the other hand, it is still beneficial to have interoperability with widespread libraries with cancellation capabilities.

## Similar API review
* [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), the original source of first-class cancellability
* C#: [CancellationTokenSource](https://docs.microsoft.com/en-us/dotnet/api/system.threading.cancellationtokensource) and [CancellationToken](https://docs.microsoft.com/en-us/dotnet/api/system.threading.cancellationtoken)
* Python: [Trio](https://trio.readthedocs.io/)
* [Arrow Fx](https://arrow-kt.io/docs/0.10/fx/)
 
## Alternatives

For application development, `kotlinx.coroutines` dependency can be used.
For library development, there is no dependency-free or lightweight-enough alternatives. 

## API details. Cancellation

### Cancellation consumers

For users that want to leverage potenrial cancellation in their suspend function, the following API is provided:
```kotlin
public interface CancellableContinuation<T> : Continuation<T> {
    fun invokeOnCancellation(handler: (cause: Throwable?) -> Unit)
    fun cancel(cause: Throwable?): Boolean
}

public suspend inline fun <T> suspendCancellableCoroutine(
    crossinline block: (CancellableContinuation<T>) -> Unit
): T

public open class CancellationException : IllegalStateException()
```

`CancellableContinuation` is a continuation with two additional methods:
  * `cancel` cancels the underlying computation, triggering a `CancellationException` if the computation is suspended. `cause` can be passed as an additional diagnostic information.
    `cancel` is asynchronous and cooperative by its nature, so it can be ignored if coroutine is already completed.
  * `invokeOnCancellation` receives a handler that is invoked synchronously when a continuation is cancelled. It provides a mechanism to cancel the underlying computation as soon as `cancel` is invoked.
  
### Integration example
 
Here is a demonstration of the cancellation capabilities for `CompletableFuture<T>.await` function that awaits an asynchronous computation in a non-blocking manner:

```kotlin
suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
      // Register callback in completable future
      whenComplete { result, error ->
          if (error != null) cont.resumeWithException(error)
          else cont.resume(result)
      }
      // Cancel future computation as soon as the caller is cancelled
      cont.invokeOnCancellation {
          cancel(/* mayInterruptIfRunning: */ false)
      }
}
```    

### Cancellation source

For users that want to provide a cancellation source, whether it is structured concurrency, a cancellation on shutdown or application-specific cancellation, the following interface is provided:

```kotlin
typealias CancellationDetachFunction = () -> Unit

public interface CancellationSource : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CancellationSource>
    override val key: CoroutineContext.Key<*> get() = Key

    fun attach(continuation: CancellableContinuation<*>): CancellationDetachFunction
}
```

`CancellationSource` is a [coroutine context element](/proposals/coroutines.md#coroutine-context) that is used by `suspendCancellableCoroutine` implementation.
Its only function, `attach` notifies the source that the given cancellable continuation is interested in the cancellation signal. 
Return value is used to signal the source that the continuation is completed and should no longer be tracked or signalled.

## API details. CoroutineContext key-based polymorphism

`CancellationSource` is a way to provide a cancellation mechanism, but not to interoperate with existing solutions that already have their specific context elements.
It all comes from the fact that an arbitrary context element is represented with two _different_ types: its own type and its key type 
and they do not have a [structured subtyping relationship](https://youtrack.jetbrains.com/issue/KT-36118).

To resolve this problem, a new `AbstractCoroutineContextKey` and supplementary extension functions were introduced in Kotlin 1.3.70.
Using them enables proper subtyping relationship:
```kotlin
interface CancellationSource : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CancellationSource>
    override val key: CoroutineContext.Key<*> get() = Key

    fun attach(continuation: CancellableContinuation<*>): CancellationDetachFunction
    // Delegate to newly introduced functions
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = getPolymorphicElement(key)
    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = minusPolymorphicKey(key)
}

class CancellationImplementation : CancellationSource {
    // Just declare AbstractCoroutineContextKey and do not override the original key
    companion object Key : AbstractCoroutineContextKey<CancellationSource, CancellationImplementation>(CancellationSource, { it as? CancellationImplementation })
}
```

Now `CancellationImplementation` is considered as `CancellationSource` context element, but is still retrievable by its actual type:
```kotlin
val context: CoroutineContext = CancellationImplementation()
context[CancellationSource] // Returns CancellationSource?
context[CancellationImplementation] // Returns CancellationImplementation?
context.minusKey[CancellationSource] // Properly removes element
context.minusKey[CancellationImplementation] // Properly removes element
context + AnotherCancellationSource // Properly overwrites the previous element
```

## API details. Packaging, IDE and migration experience

All new API will be added to new `kotlin.coroutines.cancellation` package, so no existing clients will be affected. Standard library wildcard imports have the lowest priority during symbol resolution and it is hard to import it accidentally.

`CancellationException` will be mapped to `java.util.concurrent.CancellationException` on JVM, so libraries relying on this type will be interoperable with its Kotlin counterpart.

In order for libraries to interoperate with Kotlin cancellation, minimally supported Kotlin version of the library should be raised to 1.4.   

## Experimental status and API evolution 

This API is experimental in Kotlin 1.4 and will remain so at least until the next major release (1.5).
Additionally, binary compatibility is not guaranteed for implementors of these interfaces, interfaces will be unstable for implementation as long as they are experimental.  

## Non-goals

It is not a goal to provide a one-fits-all API that is able to solve a widespread set of problems:
  * Leak-free resource passing with `resume`
  * Idempotent resumes required for lock-free data structures such as `select`
  * System-level cancellation mechanisms with global shutdown hooks, error handlers etc. 

## Open questions

* Whether more than one handler should be allowed in `invokeOnCancellation`
* How to report double `resume` that typically is a programmer error
* `Function0<Unit>` naming and type

# Kotlin Coroutines

* **Type**: Design proposal
* **Authors**: Andrey Breslav, Roman Elizarov
* **Contributors**: Vladimir Reshetnikov, Stanislav Erokhin, Ilya Ryzhenkov, Denis Zharkov
* **Status**: Stable since Kotlin 1.3 (Revision 3.3), experimental in Kotlin 1.1-1.2

## Abstract

This is a description of coroutines in Kotlin. This concept is also known as, or partly covers

- generators/yield
- async/await
- composable/delimited сontinuations

Goals:

- No dependency on a particular implementation of Futures or other such rich library;
- Cover equally the "async/await" use case and "generator blocks";
- Make it possible to utilize Kotlin coroutines as wrappers for different existing asynchronous APIs 
  (such as Java NIO, different implementations of Futures, etc).

## Table of Contents

* [Use cases](#use-cases)
  * [Asynchronous computations](#asynchronous-computations)
  * [Futures](#futures)
  * [Generators](#generators)
  * [Asynchronous UI](#asynchronous-ui)
  * [More use cases](#more-use-cases)
* [Coroutines overview](#coroutines-overview)
  * [Terminology](#terminology)
  * [Continuation interface](#continuation-interface)
  * [Suspending functions](#suspending-functions)
  * [Coroutine builders](#coroutine-builders)
  * [Coroutine context](#coroutine-context)
  * [Continuation interceptor](#continuation-interceptor)
  * [Restricted suspension](#restricted-suspension)
* [Implementation details](#implementation-details)
  * [Continuation passing style](#continuation-passing-style)
  * [State machines](#state-machines)
  * [Compiling suspending functions](#compiling-suspending-functions)
  * [Coroutine intrinsics](#coroutine-intrinsics)
* [Appendix](#appendix)
  * [Resource management and GC](#resource-management-and-gc)
  * [Concurrency and threads](#concurrency-and-threads)
  * [Asynchronous programming styles](#asynchronous-programming-styles)
  * [Wrapping callbacks](#wrapping-callbacks)
  * [Building futures](#building-futures)
  * [Non-blocking sleep](#non-blocking-sleep)
  * [Cooperative single-thread multitasking](#cooperative-single-thread-multitasking)
  * [Asynchronous sequences](#asynchronous-sequences)
  * [Channels](#channels)
  * [Mutexes](#mutexes)
  * [Migration from experimental coroutines](#migration-from-experimental-coroutines)
  * [References](#references)
  * [Feedback](#feedback)
* [Revision history](#revision-history)
  * [Changes in revision 3.3](#changes-in-revision-33)
  * [Changes in revision 3.2](#changes-in-revision-32)
  * [Changes in revision 3.1](#changes-in-revision-31)
  * [Changes in revision 3](#changes-in-revision-3)
  * [Changes in revision 2](#changes-in-revision-2)

## Use cases

A coroutine can be thought of as an instance of _suspendable computation_, i.e. the one that can suspend at some 
points and later resume execution possibly on another thread. Coroutines calling each other 
(and passing data back and forth) can form 
the machinery for cooperative multitasking.
 
### Asynchronous computations 
 
The first class of motivating use cases for coroutines are asynchronous computations 
(handled by async/await in C# and other languages). 
Let's take a look at how such computations are done with callbacks. As an inspiration, let's take 
asynchronous I/O (the APIs below are simplified):

```kotlin
// asynchronously read into `buf`, and when done run the lambda
inChannel.read(buf) {
    // this lambda is executed when the reading completes
    bytesRead ->
    ...
    ...
    process(buf, bytesRead)
    
    // asynchronously write from `buf`, and when done run the lambda
    outChannel.write(buf) {
        // this lambda is executed when the writing completes
        ...
        ...
        outFile.close()          
    }
}
```

Note that we have a callback inside a callback here, and while it saves us from a lot of boilerplate (e.g. there's no 
need to pass the `buf` parameter explicitly to callbacks, they just see it as a part of their closure), the indentation
levels are growing every time, and one can easily anticipate the problems that may come at nesting levels greater 
than one (google for "callback hell" to see how much people suffer from this in JavaScript).

This same computation can be expressed straightforwardly as a coroutine (provided that there's a library that adapts
the I/O APIs to coroutine requirements):
 
```kotlin
launch {
    // suspend while asynchronously reading
    val bytesRead = inChannel.aRead(buf) 
    // we only get to this line when reading completes
    ...
    ...
    process(buf, bytesRead)
    // suspend while asynchronously writing   
    outChannel.aWrite(buf)
    // we only get to this line when writing completes  
    ...
    ...
    outFile.close()
}
```

The `aRead()` and `aWrite()` here are special _suspending functions_ — they can _suspend_ execution 
(which does not mean blocking the thread it has been running on) and _resume_ when the call has completed. 
If we squint our eyes just enough to imagine that all the code after `aRead()` has been wrapped in a 
lambda and passed to `aRead()` as a callback, and the same has been done for `aWrite()`, 
we can see that this code is the same as above, only more readable. 

It is our explicit goal to support coroutines in a very generic way, so in this example,
 `launch{}`, `.aRead()`, and `.aWrite()` are just **library functions** geared for
working with coroutines: `launch` is the _coroutine builder_ — it builds and launches coroutine, 
while `aRead`/`aWrite` are special
_suspending functions_ which implicitly receive 
_continuations_ (continuations are just generic callbacks).  

> The example code for `launch{}` is shown in [coroutine builders](#coroutine-builders) section, and
the example code for `.aRead()` is shown in [wrapping callbacks](#wrapping-callbacks) section.

Note, that with explicitly passed callbacks having an asynchronous call in the middle of a loop can be tricky, 
but in a coroutine it is a perfectly normal thing to have:

```kotlin
launch {
    while (true) {
        // suspend while asynchronously reading
        val bytesRead = inFile.aRead(buf)
        // continue when the reading is done
        if (bytesRead == -1) break
        ...
        process(buf, bytesRead)
        // suspend while asynchronously writing
        outFile.aWrite(buf) 
        // continue when the writing is done
        ...
    }
}
```

One can imagine that handling exceptions is also a bit more convenient in a coroutine.

### Futures

There's another style of expressing asynchronous computations: through futures (also known as promises or deferreds).
We'll use an imaginary API here, to apply an overlay to an image:

```kotlin
val future = runAfterBoth(
    loadImageAsync("...original..."), // creates a Future 
    loadImageAsync("...overlay...")   // creates a Future
) {
    original, overlay ->
    ...
    applyOverlay(original, overlay)
}
```

With coroutines, this could be rewritten as

```kotlin
val future = future {
    val original = loadImageAsync("...original...") // creates a Future
    val overlay = loadImageAsync("...overlay...")   // creates a Future
    ...
    // suspend while awaiting the loading of the images
    // then run `applyOverlay(...)` when they are both loaded
    applyOverlay(original.await(), overlay.await())
}
```

> The example code for `future{}` is shown in [building futures](#building-futures) section, and
the example code for `.await()` is shown in [suspending functions](#suspending-functions) section.

Again, less indentation and more natural composition logic (and exception handling, not shown here), 
and no special keywords (like `async` and `await` in C#, JS and other languages)
to support futures: `future{}` and `.await()` are just functions in a library.

### Generators

Another typical use case for coroutines would be lazily computed sequences (handled by `yield` in C#, Python 
and many other languages). Such a sequence can be generated by seemingly sequential code, but at runtime only 
requested elements are computed:

```kotlin
// inferred type is Sequence<Int>
val fibonacci = sequence {
    yield(1) // first Fibonacci number
    var cur = 1
    var next = 1
    while (true) {
        yield(next) // next Fibonacci number
        val tmp = cur + next
        cur = next
        next = tmp
    }
}
```

This code creates a lazy `Sequence` of [Fibonacci numbers](https://en.wikipedia.org/wiki/Fibonacci_number), 
that is potentially infinite 
(exactly like [Haskell's infinite lists](http://www.techrepublic.com/article/infinite-list-tricks-in-haskell/)). 
We can request some of it, for example, through `take()`:
 
```kotlin
println(fibonacci.take(10).joinToString())
```

> This will print `1, 1, 2, 3, 5, 8, 13, 21, 34, 55`
  You can try this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/fibonacci.kt)
 
The strength of generators is in supporting arbitrary control flow, such as `while` (from the example above),
`if`, `try`/`catch`/`finally` and everything else: 
 
```kotlin
val seq = sequence {
    yield(firstItem) // suspension point

    for (item in input) {
        if (!item.isValid()) break // don't generate any more items
        val foo = item.toFoo()
        if (!foo.isGood()) continue
        yield(foo) // suspension point        
    }
    
    try {
        yield(lastItem()) // suspension point
    }
    finally {
        // some finalization code
    }
} 
```

> The example code for `sequence{}` and `yield()` is shown in
[restricted suspension](#restricted-suspension) section.

Note that this approach also allows to express `yieldAll(sequence)` as a library function 
(as well as `sequence{}` and `yield()` are), which simplifies joining lazy sequences and allows
for efficient implementation.

### Asynchronous UI

A typical UI application has a single event dispatch thread where all UI operations happen. 
Modification of UI state from other threads is usually not allowed. All UI libraries provide
some kind of primitive to move execution back to UI thread. Swing, for example, has 
[`SwingUtilities.invokeLater`](https://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-),
JavaFX has 
[`Platform.runLater`](https://docs.oracle.com/javase/8/javafx/api/javafx/application/Platform.html#runLater-java.lang.Runnable-), 
Android has
[`Activity.runOnUiThread`](https://developer.android.com/reference/android/app/Activity.html#runOnUiThread(java.lang.Runnable)),
etc.
Here is a snippet of code from a typical Swing application that does some asynchronous
operation and then displays its result in the UI:

```kotlin
makeAsyncRequest {
    // this lambda is executed when the async request completes
    result, exception ->
    
    if (exception == null) {
        // display result in UI
        SwingUtilities.invokeLater {
            display(result)   
        }
    } else {
       // process exception
    }
}
```

This is similar to callback hell that we've seen in [asynchronous computations](#asynchronous-computations) use case
and it is elegantly solved by coroutines, too:
 
```kotlin
launch(Swing) {
    try {
        // suspend while asynchronously making request
        val result = makeRequest()
        // display result in UI, here Swing context ensures that we always stay in event dispatch thread
        display(result)
    } catch (exception: Throwable) {
        // process exception
    }
}
```
 
> The example code for `Swing` context is shown in the [continuation interceptor](#continuation-interceptor) section.
 
All exception handling is performed using natural language constructs. 

### More use cases
 
Coroutines can cover many more use cases, including these:  
 
* Channel-based concurrency (aka goroutines and channels);
* Actor-based concurrency;
* Background processes occasionally requiring user interaction, e.g., show a modal dialog;
* Communication protocols: implement each actor as a sequence rather than a state machine;
* Web application workflows: register a user, validate email, log them in 
(a suspended coroutine may be serialized and stored in a DB).

## Coroutines overview

This section gives an overview of the language mechanisms that enable writing coroutines and 
the standard libraries that govern their semantics.

### Terminology

 *  A _coroutine_ — is an _instance_ of _suspendable computation_. It is conceptually similar to a thread, in the sense that
    it takes a block of code to run and has a similar life-cycle — it is _created_ and _started_, but it is not bound
    to any particular thread. It may _suspend_ its execution in one thread and _resume_ in another one. 
    Moreover, like a future or promise, it may _complete_ with some result (which is either a value or an exception).
 
 *  A _suspending function_ — a function that is marked with `suspend` modifier. It may _suspend_ execution of the code
    without blocking the current thread of execution by invoking other suspending functions. A suspending function 
    cannot be invoked from a regular code, but only from other suspending functions and from suspending lambdas (see below).
    For example, `.await()` and `yield()`, as shown in [use cases](#use-cases), are suspending functions that may
    be defined in a library. The standard library provides primitive suspending functions that are used to define 
    all other suspending functions.
  
 *  A _suspending lambda_ — a block of code that have to run in a coroutine.
    It looks exactly like an ordinary [lambda expression](https://kotlinlang.org/docs/reference/lambdas.html)
    but its functional type is marked with `suspend` modifier.
    Just like a regular lambda expression is a short syntactic form for an anonymous local function,
    a suspending lambda is a short syntactic form for an anonymous suspending function. It may _suspend_ execution of
    the code without blocking the current thread of execution by invoking suspending functions.
    For example, blocks of code in curly braces following `launch`, `future`, and `sequence` functions,
    as shown in [use cases](#use-cases), are suspending lambdas.

    > Note: Suspending lambdas may invoke suspending functions in all places of their code where a 
    [non-local](https://kotlinlang.org/docs/reference/returns.html) `return` statement
    from this lambda is allowed. That is, suspending function calls inside inline lambdas 
    like [`apply{}` block](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/apply.html) are allowed,
    but not in the `noinline` nor in `crossinline` inner lambda expressions. 
    A _suspension_ is treated as a special kind of non-local control transfer.

 *  A _suspending function type_ — is a function type for suspending functions and lambdas. It is just like 
    a regular [function type](https://kotlinlang.org/docs/reference/lambdas.html#function-types), 
    but with `suspend` modifier. For example, `suspend () -> Int` is a type of suspending
    function without arguments that returns `Int`. A suspending function that is declared like `suspend fun foo(): Int`
    conforms to this function type.

 *  A _coroutine builder_ — a function that takes some _suspending lambda_ as an argument, creates a coroutine,
    and, optionally, gives access to its result in some form. For example, `launch{}`, `future{}`,
    and `sequence{}` as shown in [use cases](#use-cases), are coroutine builders.
    The standard library provides primitive coroutine builders that are used to define all other coroutine builders.

    > Note: Some languages have hard-coded support for particular ways to create and start a coroutines that define
    how their execution and result are represented. For example, `generate` _keyword_ may define a coroutine that 
    returns a certain kind of iterable object, while `async` _keyword_ may define a coroutine that returns a
    certain kind of promise or task. Kotlin does not have keywords or modifiers to define and start a coroutine. 
    Coroutine builders are simply functions defined in a library. 
    In case where a coroutine definition takes the form of a method body in another language, 
    in Kotlin such method would typically be a regular method with an expression body, 
    consisting of an invocation of some library-defined coroutine builder whose last argument is a suspending lambda:
 
    ```kotlin
    fun doSomethingAsync() = async { ... }
    ```

 *  A _suspension point_ — is a point during coroutine execution where the execution of the coroutine _may be suspended_. 
    Syntactically, a suspension point is an invocation of suspending function, but the _actual_
    suspension happens when the suspending function invokes the standard library primitive to suspend the execution.

 *  A _continuation_ — is a state of the suspended coroutine at suspension point. It conceptually represents 
    the rest of its execution after the suspension point. For example:

    ```kotlin
    sequence {
        for (i in 1..10) yield(i * i)
        println("over")
    }  
    ```  

    Here, every time the coroutine is suspended at a call to suspending function `yield()`, 
    _the rest of its execution_ is represented as a continuation, so we have 10 continuations: 
    first runs the loop with `i = 2` and suspends, second runs the loop with `i = 3` and suspends, etc, 
    the last one prints "over" and completes the coroutine. The coroutine that is _created_, but is not 
    _started_ yet, is represented by its _initial continuation_ of type `Continuation<Unit>` that consists of
    its whole execution.

As mentioned above, one of the driving requirements for coroutines is flexibility:
we want to be able to support many existing asynchronous APIs and other use cases and minimize 
the parts hard-coded into the compiler. As a result, the compiler is only responsible for support
of suspending functions, suspending lambdas, and the corresponding suspending function types. 
There are few primitives in the standard library and the rest is left to application libraries. 

### Continuation interface

Here is the definition of the standard library interface 
[`Continuation`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-continuation/index.html)  
(defined in `kotlinx.coroutines` package), which represents a generic callback:

```kotlin
interface Continuation<in T> {
   val context: CoroutineContext
   fun resumeWith(result: Result<T>)
}
```

The context is covered in details in [coroutine context](#coroutine-context) section and represents an arbitrary
user-defined context that is associated with the coroutine. The `resumeWith` function is a _completion_
callback that is used to report either a success (with a value) or a failure (with an exception) on coroutine completion.

There are two extension functions defined in the same package for convenience:

```kotlin
fun <T> Continuation<T>.resume(value: T)
fun <T> Continuation<T>.resumeWithException(exception: Throwable)
```

### Suspending functions

An implementation of a typical _suspending function_ like `.await()` looks like this:
  
```kotlin
suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCoroutine<T> { cont: Continuation<T> ->
        whenComplete { result, exception ->
            if (exception == null) // the future has been completed normally
                cont.resume(result)
            else // the future has completed with an exception
                cont.resumeWithException(exception)
        }
    }
``` 

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/future/await.kt).
  Note: this simple implementation suspends coroutine forever if the future never completes.
  The actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines)
  supports cancellation.

The `suspend` modifier indicates that this is a function that can suspend execution of a coroutine.
This particular function is defined as an 
[extension function](https://kotlinlang.org/docs/reference/extensions.html)
on `CompletableFuture<T>` type so that its usage reads naturally in the left-to-right order
that corresponds to the actual order of execution:

```kotlin
doSomethingAsync(...).await()
```
 
A modifier `suspend` may be used on any function: top-level function, extension function, member function, 
local function, or operator function.

> Property getters and setters, constructors, and some operators functions 
  (namely `getValue`, `setValue`, `provideDelegate`, `get`, `set`, and `equals`) cannot have `suspend` modifier. 
  These restrictions may be lifted in the future.
 
Suspending functions may invoke any regular functions, but to actually suspend execution they must
invoke some other suspending function. In particular, this `await` implementation invokes a suspending function
[`suspendCoroutine`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/suspend-coroutine.html) 
that is defined in the standard library (in `kotlin.coroutines` package) 
as a top-level suspending function:

```kotlin
suspend fun <T> suspendCoroutine(block: (Continuation<T>) -> Unit): T
```

When `suspendCoroutine` is called inside a coroutine (and it can _only_ be called inside
a coroutine, because it is a suspending function) it captures the execution state of a coroutine 
in a _continuation_ instance and passes this continuation to the specified `block` as an argument.
To resume execution of the coroutine, the block invokes `continuation.resumeWith()`
(either directly or using `continuation.resume()` or `continuation.resumeWithException()` extensions)
in this thread or in some other thread at some later time. 
The _actual_ suspension of a coroutine happens when the `suspendCoroutine` block returns without invoking `resumeWith`.
If continuation was resumed before returning from inside of the block,
then the coroutine is not considered to have been suspended and continues to execute.

The result passed to `continuation.resumeWith()` becomes the result of `suspendCoroutine` call,
which, in turn, becomes the result of `.await()`.

Resuming the same continuation more than once is not allowed and produces `IllegalStateException`.

> Note: That is the key difference between coroutines in Kotlin and first-class delimited continuations in 
functional languages like Scheme or continuation monad in Haskell. The choice to support only resume-once 
continuations is purely pragmatic as none of the intended [use cases](#use-cases) need multi-shot continuations. 
However, multi-shot continuations can be implemented as a separate library by suspending coroutine
with low-level [coroutine intrinsics](#coroutine-intrinsics) and cloning the state of the coroutine that is
captured in continuation, so that its clone can be resumed again. 

### Coroutine builders

Suspending functions cannot be invoked from regular functions, so the standard library provides functions
to start coroutine execution from a regular non-suspending scope. Here is the implementation of a simple
`launch{}` _coroutine builder_:

```kotlin
fun launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> Unit) =
    block.startCoroutine(Continuation(context) { result ->
        result.onFailure { exception ->
            val currentThread = Thread.currentThread()
            currentThread.uncaughtExceptionHandler.uncaughtException(currentThread, exception)
        }
    })
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/run/launch.kt).

This implementation uses [`Continuation(context) { ... }`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-continuation.html) 
function (from `kotlin.coroutines` package) that provides a 
shortcut to implementing a `Continuation` interface with a given value for its `context` and the body of 
`resumeWith` function. This continuation is passed to 
[`block.startCoroutine(...)`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/start-coroutine.html) extension function 
(from `kotlin.coroutines` package) as a _completion continuation_.

The completion of coroutine invokes its completion continuation. Its `resumeWith`
function is invoked when coroutine _completes_ with success or failure.
Because `launch` does "fire-and-forget"
coroutine, it is defined for suspending functions with `Unit` return type and actually ignores
this result in its `resume` function. If coroutine execution completes with exception,
then the uncaught exception handler of the current thread is used to report it.

> Note: this simple implementation returns `Unit` and provides no access to the state of the coroutine at all. 
  The actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) is more
  complex, because it returns an instance of `Job` interface that represents a coroutine and can be cancelled.

The context is covered in details in [coroutine context](#coroutine-context) section.
The `startCoroutine` is defined in the standard library as an extension for no parameter and
single parameter suspending function types:

```kotlin
fun <T> (suspend  () -> T).startCoroutine(completion: Continuation<T>)
fun <R, T> (suspend  R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>)
```

The `startCoroutine` creates coroutine and starts its execution immediately, in the current thread (but see remark below),
until the first _suspension point_, then it returns.
Suspension point is an invocation of some [suspending function](#suspending-functions) in the body of the coroutine and
it is up to the code of the corresponding suspending function to define when and how the coroutine execution resumes.

> Note: continuation interceptor (from the context) that is covered [later](#continuation-interceptor), can dispatch
the execution of the coroutine, _including_ its initial continuation, into another thread.

### Coroutine context

Coroutine context is a persistent set of user-defined objects that can be attached to the coroutine. It
may include objects responsible for coroutine threading policy, logging, security and transaction aspects of the
coroutine execution, coroutine identity and name, etc. Here is the simple mental model of coroutines and their
contexts. Think of a coroutine as a light-weight thread. In this case, coroutine context is just like a collection 
of thread-local variables. The difference is that thread-local variables are mutable, while coroutine context is
immutable, which is not a serious limitation for coroutines, because they are so light-weight that it is easy to
launch a new coroutine when there is a need to change anything in the context.

The standard library does not contain any concrete implementations of the context elements, 
but has interfaces and abstract classes so that all these aspects
can be defined in libraries in a _composable_ way, so that aspects from different libraries can coexist
peacefully as elements of the same context.

Conceptually, coroutine context is an indexed set of elements, where each element has a unique key.
It is a mix between a set and a map. Its elements have keys like in a map, but its keys are directly associated
with elements, more like in a set. The standard library defines the minimal interface for 
[`CoroutineContext`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/index.html)
(in `kotlin.coroutines` package):

```kotlin
interface CoroutineContext {
    operator fun <E : Element> get(key: Key<E>): E?
    fun <R> fold(initial: R, operation: (R, Element) -> R): R
    operator fun plus(context: CoroutineContext): CoroutineContext
    fun minusKey(key: Key<*>): CoroutineContext

    interface Element : CoroutineContext {
        val key: Key<*>
    }

    interface Key<E : Element>
}
```

The `CoroutineContext` itself has four core operations available on it:

* Operator [`get`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/get.html)
  provides type-safe access to an element for a given key. It can be used with `[..]` notation
  as explained in [Kotlin operator overloading](https://kotlinlang.org/docs/reference/operator-overloading.html).
* Function [`fold`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/fold.html)
  works like [`Collection.fold`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/fold.html)
  extension in the standard library and provides means to iterate all elements in the context.
* Operator [`plus`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/plus.html)
  works like [`Set.plus`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/plus.html)
  extension in the standard library and returns a combination of two contexts with elements on the right-hand side
  of plus replacing elements with the same key on the left-hand side.
* Function [`minusKey`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/minus-key.html)
  returns a context that does not contain a specified key.

An [`Element`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/-element/index.html) 
of the coroutine context is a context itself. 
It is a singleton context with this element only.
This enables creation of composite contexts by taking library definitions of coroutine context elements and
joining them with `+`. For example, if one library defines `auth` element with user authorization information,
and some other library defines `threadPool` object with some execution context information,
then you can use a `launch{}` [coroutine builder](#coroutine-builders) with the combined context using
`launch(auth + threadPool) {...}` invocation.

> Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) provides several context elements, 
  including `Dispatchers.Default` object that dispatches execution of coroutine onto a shared pool of background threads.

Standard library provides
[`EmptyCoroutineContext`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-empty-coroutine-context/index.html)
&mdash; an instance of `CoroutineContext` without any elements (empty).

All third-party context elements shall extend 
[`AbstractCoroutineContextElement`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-abstract-coroutine-context-element/index.html)
class that is provided by the standard library (in `kotlin.coroutines` package). 
The following style is recommended for library defined context elements.
The example below shows a hypothetical authorization context element that stores current user name:

```kotlin
class AuthUser(val name: String) : AbstractCoroutineContextElement(AuthUser) {
    companion object Key : CoroutineContext.Key<AuthUser>
}
```

> This example can be found [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/auth.kt).

The definition of context `Key` as a companion object of the corresponding element class enables fluent access
to the corresponding element of the context. Here is a hypothetical implementation of suspending function that
needs to check the name of the current user:

```kotlin
suspend fun doSomething() {
    val currentUser = coroutineContext[AuthUser]?.name ?: throw SecurityException("unauthorized")
    // do something user-specific
}
```

It uses a top-level [`coroutineContext`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/coroutine-context.html)
property (from `kotlin.coroutines` package) that is available in
suspending functions to retrieve the context of the current coroutine.

### Continuation interceptor

Let's recap [asynchronous UI](#asynchronous-ui) use case. Asynchronous UI applications must ensure that the 
coroutine body itself is always executed in UI thread, despite the fact that various suspending functions 
resume coroutine execution in arbitrary threads. This is accomplished using a _continuation interceptor_.
First of all, we need to fully understand the life-cycle of a coroutine. Consider a snippet of code that uses 
[`launch{}`](#coroutine-builders) coroutine builder:

```kotlin
launch(Swing) {
    initialCode() // execution of initial code
    f1.await() // suspension point #1
    block1() // execution #1
    f2.await() // suspension point #2
    block2() // execution #2
}
```

Coroutine starts with execution of its `initialCode` until the first suspension point. At the suspension point it
_suspends_ and, after some time, as defined by the corresponding suspending function, it _resumes_ to execute 
`block1`, then it suspends again and resumes to execute `block2`, after which it _completes_.

Continuation interceptor has an option to intercept and wrap the continuation that corresponds to the
execution of `initialCode`, `block1`, and `block2` from their resumption to the subsequent suspension points.
The initial code of the coroutine is treated as a
resumption of its _initial continuation_. The standard library provides the 
[`ContinuationInterceptor`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-continuation-interceptor/index.html) 
interface (in `kotlin.coroutines` package):
 
```kotlin
interface ContinuationInterceptor : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ContinuationInterceptor>
    fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T>
    fun releaseInterceptedContinuation(continuation: Continuation<*>)
}
 ```
 
The `interceptContinuation` function wraps the continuation of the coroutine. Whenever coroutine is suspended,
coroutine framework uses the following line of code to wrap the actual `continuation` for the subsequent
resumption:

```kotlin
val intercepted = continuation.context[ContinuationInterceptor]?.interceptContinuation(continuation) ?: continuation
```
 
Coroutine framework caches the resulting intercepted continuation for each actual instance of continuation
and invokes `releaseInterceptedContinuation(intercepted)` when it is no longer needed. 
See [implementation details](#implementation-details) section for more details.

> Note, that suspending functions like `await` may or may not actually suspend execution of
a coroutine. For example, `await` implementation that was shown in [suspending functions](#suspending-functions) section
does not actually suspend coroutine when a future is already complete (in this case it invokes `resume` immediately and 
execution continues without the actual suspension). A continuation is intercepted only when the actual suspension 
happens during execution of a coroutine, that is when `suspendCoroutine` block returns without invoking
`resume`.

Let us take a look at a concrete example code for `Swing` interceptor that dispatches execution onto
Swing UI event dispatch thread. We start with a definition of a `SwingContinuation` wrapper class that
dispatches continuation onto the Swing event dispatch thread using `SwingUtilities.invokeLater`:

```kotlin
private class SwingContinuation<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context
    
    override fun resumeWith(result: Result<T>) {
        SwingUtilities.invokeLater { cont.resumeWith(result) }
    }
}
```

Then define `Swing` object that is going to serve as the corresponding context element and implement
`ContinuationInterceptor` interface:
  
```kotlin
object Swing : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        SwingContinuation(continuation)
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/swing.kt).
  Note: the actual implementation of `Swing` object in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 
  also supports coroutine debugging facilities that provide and display the identifier of the currently running 
  coroutine in the name of the thread that is currently running this coroutine.

Now, one can use `launch{}` [coroutine builder](#coroutine-builders) with `Swing` parameter to 
execute a coroutine that is running completely in Swing event dispatch thread:
 
 ```kotlin
launch(Swing) {
   // code in here can suspend, but will always resume in Swing EDT
}
```

> The actual implementation of `Swing` context in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 
  is more complex since it is integrated with time and debugging facilities of the library.

### Restricted suspension

A different kind of coroutine builder and suspension function is needed to implement `sequence{}` and `yield()`
from [generators](#generators) use case. Here is the example code for `sequence{}` coroutine builder:

```kotlin
fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence {
    SequenceCoroutine<T>().apply {
        nextStep = block.createCoroutine(receiver = this, completion = this)
    }
}
```

It uses a different primitive from the standard library called 
[`createCoroutine`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/create-coroutine.html) 
which is similar `startCoroutine` (that was explained in [coroutine builders](coroutine-builders) section). 
However it _creates_ a coroutine, but does _not_ start it. 
Instead, it returns its _initial continuation_ as a reference to `Continuation<Unit>`:

```kotlin
fun <T> (suspend () -> T).createCoroutine(completion: Continuation<T>): Continuation<Unit>
fun <R, T> (suspend R.() -> T).createCoroutine(receiver: R, completion: Continuation<T>): Continuation<Unit>
```

The other difference is that _suspending lambda_
`block` for this builder is an 
[_extension lambda_](https://kotlinlang.org/docs/reference/lambdas.html#function-literals-with-receiver) 
with `SequenceScope<T>` receiver.
The `SequenceScope` interface provides the _scope_ for the generator block and is defined in a library as:

```kotlin
interface SequenceScope<in T> {
    suspend fun yield(value: T)
}
```

To avoid creation of multiple objects, `sequence{}` implementation defines `SequenceCoroutine<T>` class that
implements `SequenceScope<T>` and also implements `Continuation<Unit>`, so it can serve both as
a `receiver` parameter for `createCoroutine` and as its `completion` continuation parameter. 
The simple implementation for `SequenceCoroutine<T>` is shown below:

```kotlin
private class SequenceCoroutine<T>: AbstractIterator<T>(), SequenceScope<T>, Continuation<Unit> {
    lateinit var nextStep: Continuation<Unit>

    // AbstractIterator implementation
    override fun computeNext() { nextStep.resume(Unit) }

    // Completion continuation implementation
    override val context: CoroutineContext get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        result.getOrThrow() // bail out on error
        done()
    }

    // Generator implementation
    override suspend fun yield(value: T) {
        setNext(value)
        return suspendCoroutine { cont -> nextStep = cont }
    }
}
```
 
> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequence.kt).
  Note, that the standard library provides out-of-the-box optimized implementation of this
  [`sequence`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/sequence.html)
  function (in `kotlin.sequences` package) with additional support for 
  [`yieldAll`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence-scope/yield-all.html) function.
  
> The actual code for `sequence` uses experimental `BuilderInference` feature that enables declaration 
  of `fibonacci` as shown in [generators](#generators) section without explicit specification of
  sequence type parameter `T`. Instead, it is inferred from the types passed to `yield` calls.

The implementation of `yield` uses `suspendCoroutine` [suspending function](#suspending-functions) to suspend
the coroutine and to capture its continuation. Continuation is stored as `nextStep` to be resumed when the 
`computeNext` is invoked.
 
However, `sequence{}` and `yield()`, as shown above, are not ready for an arbitrary suspending function
to capture the continuation in their scope. They work _synchronously_.
They need absolute control on how continuation is captured, 
where it is stored, and when it is resumed. They form _restricted suspension scope_. 
The ability to restrict suspensions is provided by `@RestrictsSuspension` annotation that is placed
on the scope class or interface, in the above example this scope interface is `SequenceScope`:

```kotlin
@RestrictsSuspension
interface SequenceScope<in T> {
    suspend fun yield(value: T)
}
```

This annotation enforces certain restrictions on suspending functions that can be used in the
scope of `sequence{}` or similar synchronous coroutine builder.
Any extension suspending lambda or function that has _restricted suspension scope_ class or interface 
(marked with `@RestrictsSuspension`) as its receiver is 
called a _restricted suspending function_.
Restricted suspending functions can only invoke member or
extension suspending functions on the same instance of their restricted suspension scope. 

In particular, it means that
no `SequenceScope` extension of lambda in its scope can invoke `suspendContinuation` or other
general suspending function. To suspend the execution of a `sequence` coroutine they must ultimately invoke
`SequenceScope.yield`. The implementation of `yield` itself is a member function of `SequenceScope`
implementation and it does not have any restrictions (only _extension_ suspending lambdas and functions are restricted).

It makes little sense to support arbitrary contexts for such a restricted coroutine builder as `sequence`,
because their scope class or interface (`SequenceScope` in this example) serves as a context, so restricted
coroutines must always use `EmptyCoroutineContext` as their context and that is what `context` property getter implementation
of `SequenceCoroutine` returns. An attempt to create a restricted coroutine with the context that is other than 
`EmptyCoroutinesContext` results in `IllegalArgumentException`.

## Implementation details

This section provides a glimpse into implementation details of coroutines. They are hidden
behind the building blocks explained in [coroutines overview](#coroutines-overview) section and
their internal classes and code generation strategies are subject to change at any time as
long as they don't break contracts of public APIs and ABIs.

### Continuation passing style

Suspending functions are implemented via Continuation-Passing-Style (CPS).
Every suspending function and suspending lambda has an additional `Continuation` 
parameter that is implicitly passed to it when it is invoked. Recall, that a declaration
of [`await` suspending function](#suspending-functions) looks like this:

```kotlin
suspend fun <T> CompletableFuture<T>.await(): T
```

However, its actual _implementation_ has the following signature after _CPS transformation_:

```kotlin
fun <T> CompletableFuture<T>.await(continuation: Continuation<T>): Any?
```

Its result type `T` has moved into a position of type argument in its additional continuation parameter.
The implementation result type of `Any?` is designed to represent the action of the suspending function.
When suspending function _suspends_ coroutine, it returns a special marker value of 
`COROUTINE_SUSPENDED` (see more in [coroutine intrinsics](#coroutine-intrinsics) section). 
When a suspending function does not suspend coroutine but
continues coroutine execution, it returns its result or throws an exception directly.
This way, the `Any?` return type of the `await` implementation is actually a union of
`COROUTINE_SUSPENDED` and `T` that cannot be expressed in Kotlin's type system.

The actual implementation of the suspending function is not allowed to invoke the continuation in its stack frame directly 
because that may lead to stack overflow on long-running coroutines. The `suspendCoroutine` function in
the standard library hides this complexity from an application developer by tracking invocations
of continuations and ensures conformance to the actual implementation contract of 
the suspending functions regardless of how and when the continuation is invoked.

### State machines

It is crucial to implement coroutines efficiently, i.e. create as few classes and objects as possible.
Many languages implement them through _state machines_ and Kotlin does the same. In the case of Kotlin 
this approach results in the compiler creating only one class per suspending lambda that may
have an arbitrary number of suspension points in its body.   
 
Main idea: a suspending function is compiled to a state machine, where states correspond to suspension points. 
Example: let's take a suspending block with two suspension points:
 
```kotlin
val a = a()
val y = foo(a).await() // suspension point #1
b()
val z = bar(a, y).await() // suspension point #2
c(z)
``` 

There are three states for this block of code:
 
 * initial (before any suspension point)
 * after the first suspension point
 * after the second suspension point
 
Every state is an entry point to one of the continuations of this block 
(the initial continuation continues from the very first line). 
 
The code is compiled to an anonymous class that has a method implementing the state machine, 
a field holding the current state of the state machine, and fields for local variables of 
the coroutine that are shared between states (there may also be fields for the closure of 
the coroutine, but in this case it is empty). Here's pseudo-Java code for the block above
that uses continuation passing style for invocation of suspending functions `await`:
  
``` java
class <anonymous_for_state_machine> extends SuspendLambda<...> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    A a = null
    Y y = null
    
    void resumeWith(Object result) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        if (label == 2) goto L2
        else throw IllegalStateException()
        
      L0:
        // result is expected to be `null` at this invocation
        a = a()
        label = 1
        result = foo(a).await(this) // 'this' is passed as a continuation 
        if (result == COROUTINE_SUSPENDED) return // return if await had suspended execution
      L1:
        // external code has resumed this coroutine passing the result of .await() 
        y = (Y) result
        b()
        label = 2
        result = bar(a, y).await(this) // 'this' is passed as a continuation
        if (result == COROUTINE_SUSPENDED) return // return if await had suspended execution
      L2:
        // external code has resumed this coroutine passing the result of .await()
        Z z = (Z) result
        c(z)
        label = -1 // No more steps are allowed
        return
    }          
}    
```  

Note that there is a `goto` operator and labels because the example depicts what happens in the 
byte code, not in the source code.

Now, when the coroutine is started, we call its `resumeWith()` — `label` is `0`, 
and we jump to `L0`, then we do some work, set the `label` to the next state — `1`, call `.await()`
and return if the execution of the coroutine was suspended. 
When we want to continue the execution, we call `resumeWith()` again, and now it proceeds right to 
`L1`, does some work, sets the state to `2`, calls `.await()` and again returns in case of suspension.
Next time it continues from `L3` setting the state to `-1` which means 
"over, no more work to do". 

A suspension point inside a loop generates only one state, 
because loops also work through (conditional) `goto`:
 
```kotlin
var x = 0
while (x < 10) {
    x += nextNumber().await()
}
```

is generated as

``` java
class <anonymous_for_state_machine> extends SuspendLambda<...> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    int x
    
    void resumeWith(Object result) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        else throw IllegalStateException()
        
      L0:
        x = 0
      LOOP:
        if (x > 10) goto END
        label = 1
        result = nextNumber().await(this) // 'this' is passed as a continuation 
        if (result == COROUTINE_SUSPENDED) return // return if await had suspended execution
      L1:
        // external code has resumed this coroutine passing the result of .await()
        x += ((Integer) result).intValue()
        label = -1
        goto LOOP
      END:
        label = -1 // No more steps are allowed
        return 
    }          
}    
```  

### Compiling suspending functions

The compiled code for suspending function depends on how and when it invokes other suspending functions.
In the simplest case, a suspending function invokes other suspending functions only at _tail positions_ 
making _tail calls_ to them. This is a typical case for suspending functions that implement low-level synchronization 
primitives or wrap callbacks, as shown in [suspending functions](#suspending-functions) and
[wrapping callbacks](#wrapping-callbacks) sections. These functions invoke some other suspending function
like `suspendCoroutine` at tail position. They are compiled just like regular non-suspending functions, with 
the only exception that the implicit continuation parameter they've got from [CPS transformation](#continuation-passing-style)
is passed to the next suspending function in tail call.

In a case when suspending invocations appear in non-tail positions, the compiler creates a 
[state machine](#state-machines) for the corresponding suspending function. An instance of the state machine
object in created when suspending function is invoked and is discarded when it completes.

> Note: in the future versions this compilation strategy may be optimized to create an instance of a state machine 
only at the first suspension point.

This state machine object, in turn, serves as the _completion continuation_ for the invocation of other
suspending functions in non-tail positions. This state machine object instance is updated and reused when 
the function makes multiple invocations to other suspending functions. 
Compare this to other [asynchronous programming styles](#asynchronous-programming-styles),
where each subsequent step of asynchronous processing is typically implemented with a separate, freshly allocated,
closure object.

### Coroutine intrinsics

Kotlin standard library provides `kotlin.coroutines.intrinsics` package that contains a number of
declarations that expose internal implementation details of coroutines machinery that are explained
in this section and should be used with care. These declarations should not be used in general code, so 
the `kotlin.coroutines.intrinsics` package is hidden from auto-completion in IDE. In order to use
those declarations you have to manually add the corresponding import statement to your source file:

```kotlin
import kotlin.coroutines.intrinsics.*
```

The actual implementation of `suspendCoroutine` suspending function in the standard library is written in Kotlin
itself and its source code is available as part of the standard library sources package. In order to provide for the
safe and problem-free use of coroutines, it wraps the actual continuation of the state machine 
into an additional object on each suspension of coroutine. This is perfectly fine for truly asynchronous use cases
like [asynchronous computations](#asynchronous-computations) and [futures](#futures), since the runtime costs of the 
corresponding asynchronous primitives far outweigh the cost of an additional allocated object. However, for
the [generators](#generators) use case this additional cost is prohibitive, so the intrinsics packages provides
primitives for performance-sensitive low-level code.

The `kotlin.coroutines.intrinsics` package in the standard library contains the function named 
[`suspendCoroutineUninterceptedOrReturn`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/suspend-coroutine-unintercepted-or-return.html)
with the following signature:

```kotlin
suspend fun <T> suspendCoroutineUninterceptedOrReturn(block: (Continuation<T>) -> Any?): T
```

It provides direct access to [continuation passing style](#continuation-passing-style) of suspending functions
and exposes _unintercepted_ reference to continuation. The later means that invocation of `Continuation.resumeWith` does
not go though [ContinuationInterceptor](#continuation-interceptor). It can be used when 
writing synchronous coroutines with [restricted suspension](#restricted-suspension) that cannot have installed
continuation interceptor (since their context is always empty) or 
when currently executing thread is already known to be in the desired context.
Otherwise, an intercepted continuation shall be acquired with the 
[`intercepted`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/intercepted.html)
extension function (from `kotlin.coroutines.intrinsics` package):

```kotlin
fun <T> Continuation<T>.intercepted(): Continuation<T>
```

and the `Continuation.resumeWith` shall be invoked on the resulting _intercepted_ continuation.

Now, The `block` passed to `suspendCoroutineUninterceptedOrReturn` function can return 
[`COROUTINE_SUSPENDED`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/-c-o-r-o-u-t-i-n-e_-s-u-s-p-e-n-d-e-d.html) 
marker if the coroutine did suspend (in which case `Continuation.resumeWith` shall be invoked exactly once later) or
return the result value `T` or throw an exception (in both last cases `Continuation.resumeWith` shall never be invoked).

A failure to follow this convention when using `suspendCoroutineUninterceptedOrReturn` results 
in hard to track bugs that defy attempts to find and reproduce them via tests.
This convention is usually easy to follow for `buildSequence`/`yield`-like coroutines,
but attempts to write asynchronous `await`-like suspending functions on top of `suspendCoroutineUninterceptedOrReturn` are
**discouraged** as they are **extremely tricky** to implement correctly without the help of `suspendCoroutine`.

There are also functions called 
[`createCoroutineUnintercepted`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/create-coroutine-unintercepted.html) 
(from `kotlin.coroutines.intrinsics` package)
with the following signatures:

```kotlin
fun <T> (suspend () -> T).createCoroutineUnintercepted(completion: Continuation<T>): Continuation<Unit>
fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(receiver: R, completion: Continuation<T>): Continuation<Unit>
```

They work similarly to `createCoroutine` but return unintercepted reference to the initial continuation.
Similarly to `suspendCoroutineUninterceptedOrReturn` it can be in synchronous coroutines for better performance.   
For example, optimization version of `sequence{}` builder via `createCoroutineUnintercepted` is shown below:

```kotlin
fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence {
    SequenceCoroutine<T>().apply {
        nextStep = block.createCoroutineUnintercepted(receiver = this, completion = this)
    }
}
```

Optimized version of `yield` via `suspendCoroutineUninterceptedOrReturn` is shown below.
Note, that because `yield` always suspends, the corresponding block always returns `COROUTINE_SUSPENDED`.

```kotlin
// Generator implementation
override suspend fun yield(value: T) {
    setNext(value)
    return suspendCoroutineUninterceptedOrReturn { cont ->
        nextStep = cont
        COROUTINE_SUSPENDED
    }
}
```

> You can get full code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/optimized/sequenceOptimized.kt)

Two additional intrinsics provide lower-level version of `startCoroutine` (see [coroutine builders](#coroutine-builders) section)
and are called
[`startCoroutineUninterceptedOrReturn`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/start-coroutine-unintercepted-or-return.html):

```kotlin
fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(completion: Continuation<T>): Any?
fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(receiver: R, completion: Continuation<T>): Any?
```

They are different from `startCoroutine` in two aspects. First of all, [ContinuationInterceptor](#continuation-interceptor)
is not automatically used when starting coroutine, so the caller has to ensure the proper execution context if needed.
Second, is that if the coroutine does not suspend, but returns a value or throws an exception, then the
invocation of `startCoroutineUninterceptedOrReturn` returns this value or throws this exception. If the coroutine
suspends, then it returns `COROUTINE_SUSPENDED`. 

The primary use-case for `startCoroutineUninterceptedOrReturn` is to combine it with `suspendCoroutineUninterceptedOrReturn`
to continue running suspended coroutine in the same context but with a different block of code:

```kotlin 
suspend fun doSomething() = suspendCoroutineUninterceptedOrReturn { cont ->
    // figure out or create a block of code that needs to be run
    startCoroutineUninterceptedOrReturn(completion = block) // return result to suspendCoroutineUninterceptedOrReturn 
}
```

## Appendix

This is a non-normative section that does not introduce any new language constructs or 
library functions, but covers some additional topics dealing with resource management, concurrency, 
and programming style, as well as provides more examples for a large variety of use-cases.

### Resource management and GC

Coroutines don't use any off-heap storage and do not consume any native resources by themselves, unless the code
that is running inside a coroutine does open a file or some other resource. While files opened in a coroutine must
be closed somehow, the coroutine itself does not need to be closed. When coroutine is suspended its whole state is 
available by the reference to its continuation. If you lose the reference to suspended coroutine's continuation,
then it will be ultimately collected by garbage collector.

Coroutines that open some closeable resources deserve a special attention. Consider the following coroutine
that uses the `sequence{}` builder from [restricted suspension](#restricted-suspension) section to produce
a sequence of lines from a file:

```kotlin
fun sequenceOfLines(fileName: String) = sequence<String> {
    BufferedReader(FileReader(fileName)).use {
        while (true) {
            yield(it.readLine() ?: break)
        }
    }
}
```

This function returns a `Sequence<String>` and you can use this function to print all lines from a file 
in a natural way:
 
```kotlin
sequenceOfLines("https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequenceOfLines.kt")
    .forEach(::println)
```

> You can get full code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequenceOfLines.kt)

It works as expected as long as you iterate the sequence returned by the `sequenceOfLines` function
completely. However, if you print just a few first lines from this file like here:

```kotlin
sequenceOfLines("https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequenceOfLines.kt")
        .take(3)
        .forEach(::println)
```

then the coroutine resumes a few times to yield the first three lines and becomes _abandoned_.
It is Ok for the coroutine itself to be abandoned but not for the open file. The 
[`use` function](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/use.html) 
will not have a chance to finish its execution and close the file. The file will be left open
until collected by GC, because Java files have a `finalizer` that closes the file. It is 
not a big problem for a small slide-ware or a short-running utility, but it may be a disaster for
a large backend system with multi-gigabyte heap, that can run out of open file handles 
faster than it runs out of memory to trigger GC.

This is a similar gotcha to Java's 
[`Files.lines`](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html#lines-java.nio.file.Path-)
method that produces a lazy stream of lines. It returns a closeable Java stream, but most stream operations do not
automatically invoke the corresponding 
`Stream.close` method and it is up to the user to remember about the need to close the corresponding stream. 
One can define closeable sequence generators 
in Kotlin, but they will suffer from a similar problem that no automatic mechanism in the language can
ensure that they are closed after use. It is explicitly out of the scope of Kotlin coroutines
to introduce a language mechanism for an automated resource management.

However, usually this problem does not affect asynchronous use-cases of coroutines. An asynchronous coroutine 
is never abandoned, but ultimately runs until its completion, so if the code inside a coroutine properly closes
its resources, then they will be ultimately closed.

### Concurrency and threads

Each individual coroutine, just like a thread, is executed sequentially. It means that the following kind 
of code is perfectly safe inside a coroutine:

```kotlin
launch { // starts a coroutine
    val m = mutableMapOf<String, String>()
    val v1 = someAsyncTask1() // start some async task
    val v2 = someAsyncTask2() // start some async task
    m["k1"] = v1.await() // map modification waiting on await
    m["k2"] = v2.await() // map modification waiting on await
}
```

You can use all the regular single-threaded mutable structures inside the scope of a particular coroutine.
However, sharing mutable state _between_ coroutines is potentially dangerous. If you use a coroutine builder
that installs a dispatcher to resume all coroutines JS-style in the single event-dispatch thread, 
like the `Swing` interceptor shown in [continuation interceptor](#continuation-interceptor) section,
then you can safely work with all shared
objects that are generally modified from this event-dispatch thread. 
However, if you work in multi-threaded environment or otherwise share mutable state between
coroutines running in different threads, then you have to use thread-safe (concurrent) data structures. 

Coroutines are like threads in this sense, albeit they are more lightweight. You can have millions of coroutines running on 
just a few threads. The running coroutine is always executed in some thread. However, a _suspended_ coroutine
does not consume a thread and it is not bound to a thread in any way. The suspending function that resumes this
coroutine decides which thread the coroutine is resumed on by invoking `Continuation.resumeWith` on this thread 
and coroutine's interceptor can override this decision and dispatch the coroutine's execution onto a different thread.

## Asynchronous programming styles

There are different styles of asynchronous programming.
 
Callbacks were discussed in [asynchronous computations](#asynchronous-computations) section and are generally
the least convenient style that coroutines are designed to replace. Any callback-style API can be
wrapped into the corresponding suspending function as shown [here](#wrapping-callbacks). 

Let us recap. For example, assume that you start with a hypothetical _blocking_ `sendEmail` function 
with the following signature:

```kotlin
fun sendEmail(emailArgs: EmailArgs): EmailResult
```

It blocks execution thread for potentially long time while it operates.

To make it non-blocking you can use, for example, error-first 
[node.js callback convention](https://www.tutorialspoint.com/nodejs/nodejs_callbacks_concept.htm)
to represent its non-blocking version in callback-style with the following signature:

```kotlin
fun sendEmail(emailArgs: EmailArgs, callback: (Throwable?, EmailResult?) -> Unit)
```

However, coroutines enable other styles of asynchronous non-blocking programming. One of them
is async/await style that is built into many popular languages.
In Kotlin this style can be replicated by introducing `future{}` and `.await()` library functions
that were shown as a part of [futures](#futures) use-case section.
 
This style is signified by the convention to return some kind of future object from the function instead 
of taking a callback as a parameter. In this async-style the signature of `sendEmail` is going to look like this:

```kotlin
fun sendEmailAsync(emailArgs: EmailArgs): Future<EmailResult>
```

As a matter of style, it is a good practice to add `Async` suffix to such method names, because their 
parameters are no different from a blocking version and it is quite easy to make a mistake of forgetting about
asynchronous nature of their operation. The function `sendEmailAsync` starts a _concurrent_ asynchronous operation 
and potentially brings with it all the pitfalls of concurrency. However, languages that promote this style of 
programming also typically have some kind of `await` primitive to bring the execution back into the sequence as needed. 

Kotlin's _native_ programming style is based on suspending functions. In this style, the signature of 
`sendEmail` looks naturally, without any mangling to its parameters or return type but with an additional
`suspend` modifier:

```kotlin
suspend fun sendEmail(emailArgs: EmailArgs): EmailResult
```

The async and suspending styles can be easily converted into one another using the primitives that we've 
already seen. For example, `sendEmailAsync` can be implemented via suspending `sendEmail` using
[`future` coroutine builder](#building-futures):

```kotlin
fun sendEmailAsync(emailArgs: EmailArgs): Future<EmailResult> = future {
    sendEmail(emailArgs)
}
```

while suspending function `sendEmail` can be implemented via `sendEmailAsync` using
[`.await()` suspending function](#suspending-functions)

```kotlin
suspend fun sendEmail(emailArgs: EmailArgs): EmailResult = 
    sendEmailAsync(emailArgs).await()
```

So, in some sense, these two styles are equivalent and are both definitely superior to callback style in their
convenience. However, let us look deeper at a difference between `sendEmailAsync` and suspending `sendEmail`.

Let us compare how they **compose** first. Suspending functions can be composed just like normal functions:

```kotlin
suspend fun largerBusinessProcess() {
    // a lot of code here, then somewhere inside
    sendEmail(emailArgs)
    // something else goes on after that
}
```

The corresponding async-style functions compose in this way:

```kotlin
fun largerBusinessProcessAsync() = future {
   // a lot of code here, then somewhere inside
   sendEmailAsync(emailArgs).await()
   // something else goes on after that
}
```

Observe, that async-style function composition is more verbose and _error prone_. 
If you omit `.await()` invocation in async-style
example,  the code still compiles and works, but it now does email sending process 
asynchronously or even _concurrently_ with the rest of a larger business process, 
thus potentially modifying some shared state and introducing some very hard to reproduce errors.
On the contrary, suspending functions are _sequential by default_.
With suspending functions, whenever you need any concurrency, you explicitly express it in the source code with 
some kind of `future{}` or a similar coroutine builder invocation.

Compare how these styles **scale** for a big project using many libraries. Suspending functions are
a light-weight language concept in Kotlin. All suspending functions are fully usable in any unrestricted Kotlin coroutine.
Async-style functions are framework-dependent. Every promises/futures framework must define its own `async`-like 
function that returns its own kind of promise/future class and its own `await`-like function, too.

Compare their **performance**. Suspending functions provide minimal overhead per invocation. 
You can checkout [implementation details](#implementation-details) section.
Async-style functions need to keep quite heavy promise/future abstraction in addition to all of that suspending machinery. 
Some future-like object instance must be always returned from async-style function invocation and it cannot be optimized away even 
if the function is very short and simple. Async-style is not well-suited for very fine-grained decomposition.

Compare their **interoperability** with JVM/JS code. Async-style functions are more interoperable with JVM/JS code that 
uses a matching type of future-like abstraction. In Java or JS they are just functions that return a corresponding
future-like object. Suspending functions look strange from any language that does not support 
[continuation-passing-style](#continuation-passing-style) natively.
However, you can see in the examples above how easy it is to convert any suspending function into an 
async-style function for any given promise/future framework. So, you can write suspending function in Kotlin just once, 
and then adapt it for interoperability with any style of promise/future with one line of code using an appropriate 
`future{}` coroutine builder function.

### Wrapping callbacks

Many asynchronous APIs have callback-style interfaces. The `suspendCoroutine` suspending function 
from the standard library (see [suspending functions](#suspending-functions) section)
provides for an easy way to wrap any callback into a Kotlin suspending function. 

There is a simple pattern. Assume that you have `someLongComputation` function with callback that 
receives some `Value` that is a result of this computation.

```kotlin
fun someLongComputation(params: Params, callback: (Value) -> Unit)
```

You can convert it into a suspending function with the following straightforward code:
 
```kotlin
suspend fun someLongComputation(params: Params): Value = suspendCoroutine { cont ->
    someLongComputation(params) { cont.resume(it) }
} 
```

Now the return type of this computation is explicit, but it is still asynchronous and does not block a thread.

> Note that [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) contains a framework for 
  cooperative cancellation of coroutines. It provides `suspendCancellableCoroutine` function that is 
  similar to `suspendCoroutine`, but with cancellation support. See the 
  [section on cancellation](http://kotlinlang.org/docs/reference/coroutines/cancellation-and-timeouts.html) 
  in its guide for more details.

For a more complex example let us take a look at
`aRead()` function from [asynchronous computations](#asynchronous-computations) use case. 
It can be implemented as a suspending extension function for Java NIO 
[`AsynchronousFileChannel`](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html)
and its 
[`CompletionHandler`](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/CompletionHandler.html)
callback interface with the following code:

```kotlin
suspend fun AsynchronousFileChannel.aRead(buf: ByteBuffer): Int =
    suspendCoroutine { cont ->
        read(buf, 0L, Unit, object : CompletionHandler<Int, Unit> {
            override fun completed(bytesRead: Int, attachment: Unit) {
                cont.resume(bytesRead)
            }

            override fun failed(exception: Throwable, attachment: Unit) {
                cont.resumeWithException(exception)
            }
        })
    }
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/io/io.kt).
  Note: the actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines)
  supports cancellation to abort long-running IO operations.

If you are dealing with lots of functions that all share the same type of callback, then you can define a common
wrapper function to easily convert all of them to suspending functions. For example, 
[vert.x](http://vertx.io/) uses a particular convention that all its asynchronous functions receive 
`Handler<AsyncResult<T>>` as a callback. To simplify the use of arbitrary vert.x functions from coroutines,
the following helper function can be defined:

```kotlin
inline suspend fun <T> vx(crossinline callback: (Handler<AsyncResult<T>>) -> Unit) = 
    suspendCoroutine<T> { cont ->
        callback(Handler { result: AsyncResult<T> ->
            if (result.succeeded()) {
                cont.resume(result.result())
            } else {
                cont.resumeWithException(result.cause())
            }
        })
    }
```

Using this helper function, an arbitrary asynchronous vert.x function `async.foo(params, handler)`
can be invoked from a coroutine with `vx { async.foo(params, it) }`.

### Building futures

The `future{}` builder from [futures](#futures) use-case can be defined for any future or promise primitive
similarly to the `launch{}` builder as explained in [coroutine builders](#coroutine-builders) section:

```kotlin
fun <T> future(context: CoroutineContext = CommonPool, block: suspend () -> T): CompletableFuture<T> =
        CompletableFutureCoroutine<T>(context).also { block.startCoroutine(completion = it) }
```

The first difference from `launch{}` is that it returns an implementation of
[`CompletableFuture`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html), 
and the other difference is that it is defined with a default `CommonPool` context, so that its default
execution behavior is similar to the 
[`CompletableFuture.supplyAsync`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html#supplyAsync-java.util.function.Supplier-)
method that by default runs its code in 
[`ForkJoinPool.commonPool`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html#commonPool--).
The basic implementation of `CompletableFutureCoroutine` is straightforward:

```kotlin
class CompletableFutureCoroutine<T>(override val context: CoroutineContext) : CompletableFuture<T>(), Continuation<T> {
    override fun resumeWith(result: Result<T>) {
        result
            .onSuccess { complete(it) }
            .onFailure { completeExceptionally(it) }
    }
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/future/future.kt).
  The actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) is more advanced,
  because it propagates the cancellation of the resulting future to cancel the coroutine.

The completion of this coroutine invokes the corresponding `complete` methods of the future to record the
result of this coroutine.

### Non-blocking sleep

Coroutines should not use [`Thread.sleep`](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html#sleep-long-),
because it blocks a thread. However, it is quite straightforward to implement a suspending non-blocking `delay` function by using
Java's [`ScheduledThreadPoolExecutor`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledThreadPoolExecutor.html)

```kotlin
private val executor = Executors.newSingleThreadScheduledExecutor {
    Thread(it, "scheduler").apply { isDaemon = true }
}

suspend fun delay(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Unit = suspendCoroutine { cont ->
    executor.schedule({ cont.resume(Unit) }, time, unit)
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/delay/delay.kt).
  Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) also provides `delay` function.

Note, that this kind of `delay` function resumes the coroutines that are using it in its single "scheduler" thread.
The coroutines that are using [interceptor](#continuation-interceptor) like `Swing` will not stay to execute in this thread,
as their interceptor dispatches them into an appropriate thread. Coroutines without interceptor will stay to execute
in this scheduler thread. So this solution is convenient for demo purposes, but it is not the most efficient one. It
is advisable to implement sleep natively in the corresponding interceptors.

For `Swing` interceptor that native implementation of non-blocking sleep shall use
[Swing Timer](https://docs.oracle.com/javase/8/docs/api/javax/swing/Timer.html)
that is specifically designed for this purpose:

```kotlin
suspend fun Swing.delay(millis: Int): Unit = suspendCoroutine { cont ->
    Timer(millis) { cont.resume(Unit) }.apply {
        isRepeats = false
        start()
    }
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/swing-delay.kt).
  Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) implementation of `delay` is aware of
  interceptor-specific sleep facilities and automatically uses the above approach where appropriate. 

### Cooperative single-thread multitasking

It is very convenient to write cooperative single-threaded applications, because you don't have to 
deal with concurrency and shared mutable state. JS, Python and many other languages do 
not have threads, but have cooperative multitasking primitives.

[Coroutine interceptor](#coroutine-interceptor) provides a straightforward tool to ensure that
all coroutines are confined to a single thread. The example code
[here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/threadContext.kt) defines `newSingleThreadContext()` function that
creates a single-threaded execution services and adapts it to the coroutine interceptor
requirements.

We will use it with `future{}` coroutine builder that was defined in [building futures](#building-futures) section
in the following example that works in a single thread, despite the
fact that it has two asynchronous tasks inside that are both active.

```kotlin
fun main(args: Array<String>) {
    log("Starting MyEventThread")
    val context = newSingleThreadContext("MyEventThread")
    val f = future(context) {
        log("Hello, world!")
        val f1 = future(context) {
            log("f1 is sleeping")
            delay(1000) // sleep 1s
            log("f1 returns 1")
            1
        }
        val f2 = future(context) {
            log("f2 is sleeping")
            delay(1000) // sleep 1s
            log("f2 returns 2")
            2
        }
        log("I'll wait for both f1 and f2. It should take just a second!")
        val sum = f1.await() + f2.await()
        log("And the sum is $sum")
    }
    f.get()
    log("Terminated")
}
```

> You can get fully working example [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/threadContext-example.kt).
  Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) has ready-to-use implementation of
  `newSingleThreadContext`. 

If your whole application is based on a single-threaded execution, you can define your own helper coroutine
builders with a hard-coded context for your single-threaded execution facilities.
  
## Asynchronous sequences

The `sequence{}` coroutine builder that is shown in [restricted suspension](#restricted-suspension)
section is an example of a _synchronous_ coroutine. Its producer code in the coroutine is invoked
synchronously in the same thread as soon as its consumer invokes `Iterator.next()`. 
The `sequence{}` coroutine block is restricted and it cannot suspend its execution using 3rd-party suspending
functions like asynchronous file IO as shown in [wrapping callbacks](#wrapping-callbacks) section.

An _asynchronous_ sequence builder is allowed to arbitrarily suspend and resume its execution. It means
that its consumer shall be ready to handle the case, when the data is not produced yet. This is
a natural use-case for suspending functions. Let us define `SuspendingIterator` interface that is
similar to a regular 
[`Iterator`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-iterator/) 
interface, but its `next()` and `hasNext()` functions are suspending:
 
```kotlin
interface SuspendingIterator<out T> {
    suspend operator fun hasNext(): Boolean
    suspend operator fun next(): T
}
```

The definition of `SuspendingSequence` is similar to the standard
[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)
but it returns `SuspendingIterator`:

```kotlin
interface SuspendingSequence<out T> {
    operator fun iterator(): SuspendingIterator<T>
}
```

We also define a scope interface for that is similar to a scope of a synchronous sequence,
but it is not restricted in its suspensions:

```kotlin
interface SuspendingSequenceScope<in T> {
    suspend fun yield(value: T)
}
```

The builder function `suspendingSequence{}` is similar to a synchronous `sequence{}`.
Their differences lie in implementation details of `SuspendingIteratorCoroutine` and
in the fact that it makes sense to accept an optional context in this case:

```kotlin
fun <T> suspendingSequence(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend SuspendingSequenceScope<T>.() -> Unit
): SuspendingSequence<T> = object : SuspendingSequence<T> {
    override fun iterator(): SuspendingIterator<T> = suspendingIterator(context, block)
}
```

> You can get full code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/suspendingSequence/suspendingSequence.kt).
  Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) has an implementation of
  `Channel` primitive with the corresponding `produce{}` coroutine builder that provides more 
  flexible implementation of the same concept.

Let us take `newSingleThreadContext{}` context from
[cooperative single-thread multitasking](#cooperative-single-thread-multitasking) section
and non-blocking `delay` function from [non-blocking sleep](#non-blocking-sleep) section.
This way we can write an implementation of a non-blocking sequence that yields
integers from 1 to 10, sleeping 500 ms between them:
 
```kotlin
val seq = suspendingSequence(context) {
    for (i in 1..10) {
        yield(i)
        delay(500L)
    }
}
```
   
Now the consumer coroutine can consume this sequence at its own pace, while also 
suspending with other arbitrary suspending functions. Note, that 
Kotlin [for loops](https://kotlinlang.org/docs/reference/control-flow.html#for-loops)
work by convention, so there is no need for a special `await for` loop construct in the language.
The regular `for` loop can be used to iterate over an asynchronous sequence that we've defined
above. It is suspended whenever producer does not have a value:


```kotlin
for (value in seq) { // suspend while waiting for producer
    // do something with value here, may suspend here, too
}
```

> You can find a worked out example with some logging that illustrates the execution
  [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/suspendingSequence/suspendingSequence-example.kt)

### Channels

Go-style type-safe channels can be implemented in Kotlin as a library. We can define an interface for 
send channel with suspending function `send`:

```kotlin
interface SendChannel<T> {
    suspend fun send(value: T)
    fun close()
}
```
  
and receiver channel with suspending function `receive` and an `operator iterator` in a similar style 
to [asynchronous sequences](#asynchronous-sequences):

```kotlin
interface ReceiveChannel<T> {
    suspend fun receive(): T
    suspend operator fun iterator(): ReceiveIterator<T>
}
```

The `Channel<T>` class implements both interfaces.
The `send` suspends when the channel buffer is full, while `receive` suspends when the buffer is empty.
It allows us to copy Go-style code into Kotlin almost verbatim.
The `fibonacci` function that sends `n` fibonacci numbers in to a channel from
[the 4th concurrency example of a tour of Go](https://tour.golang.org/concurrency/4)  would look 
like this in Kotlin:

```kotlin
suspend fun fibonacci(n: Int, c: SendChannel<Int>) {
    var x = 0
    var y = 1
    for (i in 0..n - 1) {
        c.send(x)
        val next = x + y
        x = y
        y = next
    }
    c.close()
}

```

We can also define Go-style `go {...}` block to start the new coroutine in some kind of
multi-threaded pool that dispatches an arbitrary number of light-weight coroutines onto a fixed number of 
actual heavy-weight threads.
The example implementation [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/go.kt) is trivially written on top of
Java's common [`ForkJoinPool`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html).

Using this `go` coroutine builder, the main function from the corresponding Go code would look like this,
where `mainBlocking` is shortcut helper function for `runBlocking` with the same pool as `go{}` uses:

```kotlin
fun main(args: Array<String>) = mainBlocking {
    val c = Channel<Int>(2)
    go { fibonacci(10, c) }
    for (i in c) {
        println(i)
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-4.kt)

You can freely play with the buffer size of the channel. 
For simplicity, only buffered channels are implemented in the example (with a minimal buffer size of 1), 
because unbuffered channels are conceptually similar to [asynchronous sequences](#asynchronous-sequences)
that were covered before.

Go-style `select` control block that suspends until one of the actions becomes available on 
one of the channels can be implemented as a Kotlin DSL, so that 
[the 5th concurrency example of a tour of Go](https://tour.golang.org/concurrency/5)  would look 
like this in Kotlin:
 
```kotlin
suspend fun fibonacci(c: SendChannel<Int>, quit: ReceiveChannel<Int>) {
    var x = 0
    var y = 1
    whileSelect {
        c.onSend(x) {
            val next = x + y
            x = y
            y = next
            true // continue while loop
        }
        quit.onReceive {
            println("quit")
            false // break while loop
        }
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-5.kt)
  
Example has an implementation of both `select {...}`, that returns the result of one of its cases like a Kotlin 
[`when` expression](https://kotlinlang.org/docs/reference/control-flow.html#when-expression), 
and a convenience `whileSelect { ... }` that is the same as `while(select<Boolean> { ... })` with fewer braces.
  
The default selection case from [the 6th concurrency example of a tour of Go](https://tour.golang.org/concurrency/6) 
just adds one more case into the `select {...}` DSL:

```kotlin
fun main(args: Array<String>) = mainBlocking {
    val tick = Time.tick(100)
    val boom = Time.after(500)
    whileSelect {
        tick.onReceive {
            println("tick.")
            true // continue loop
        }
        boom.onReceive {
            println("BOOM!")
            false // break loop
        }
        onDefault {
            println("    .")
            delay(50)
            true // continue loop
        }
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-6.kt)

The `Time.tick` and `Time.after` are trivially implemented 
[here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/time.kt) with non-blocking `delay` function.
  
Other examples can be found [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/) together with the links to 
the corresponding Go code in comments.

Note, that this sample implementation of channels is based on a single
lock to manage its internal wait lists. It makes it easier to understand and reason about. 
However, it never runs user code under this lock and thus it is fully concurrent. 
This lock only somewhat limits its scalability to a very large number of concurrent threads.

> The actual implementation of channels and `select` in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 
  is based on lock-free disjoint-access-parallel data structures.

This channel implementation is independent 
of the interceptor in the coroutine context. It can be used in UI applications
under an event-thread interceptor as shown in the
corresponding [continuation interceptor](#continuation-interceptor) section, or with any other one, or without
an interceptor at all (in the later case, the execution thread is determined solely by the code
of the other suspending functions used in a coroutine).
The channel implementation just provides thread-safe non-blocking suspending functions.
  
### Mutexes

Writing scalable asynchronous applications is a discipline that one follows, making sure that ones code 
never blocks, but suspends (using suspending functions), without actually blocking a thread.
The Java concurrency primitives like 
[`ReentrantLock`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/ReentrantLock.html)
are thread-blocking and they should not be used in a truly non-blocking code. To control access to shared
resources one can define `Mutex` class that suspends an execution of coroutine instead of blocking it.
The header of the corresponding class would like this:

```kotlin
class Mutex {
    suspend fun lock()
    fun unlock()
}
```

> You can get full implementation [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/mutex/mutex.kt).
  The actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 
  has a few additional functions.

Using this implementation of non-blocking mutex
[the 9th concurrency example of a tour of Go](https://tour.golang.org/concurrency/9)
can be translated into Kotlin using Kotlin's
[`try-finally`](https://kotlinlang.org/docs/reference/exceptions.html)
that serves the same purpose as Go's `defer`:

```kotlin
class SafeCounter {
    private val v = mutableMapOf<String, Int>()
    private val mux = Mutex()

    suspend fun inc(key: String) {
        mux.lock()
        try { v[key] = v.getOrDefault(key, 0) + 1 }
        finally { mux.unlock() }
    }

    suspend fun get(key: String): Int? {
        mux.lock()
        return try { v[key] }
        finally { mux.unlock() }
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-9.kt)

### Migration from experimental coroutines

Coroutines were an experimental feature in Kotlin 1.1-1.2. The corresponding APIs were exposed
in `kotlin.coroutines.experimental` package. The stable version of coroutines, available since Kotlin 1.3,
uses `kotlin.coroutines` package. The experimental package is still available in the standard library and the 
code that was compiled with experimental coroutines still works as before.

Kotlin 1.3 compiler provides support for invoking experimental suspending functions and passing suspending
lambdas to the libraries that were compiled with experimental coroutines. Behind the scenes, the 
adapters between the corresponding stable and experimental coroutine interfaces are created. 

### References

* Further reading:
   * [Coroutines Reference Guide](http://kotlinlang.org/docs/reference/coroutines/coroutines-guide.html) **READ IT FIRST!**.
* Presentations:
   * [Introduction to Coroutines](https://www.youtube.com/watch?v=_hfBv0a09Jc) (Roman Elizarov at KotlinConf 2017, [slides](https://www.slideshare.net/elizarov/introduction-to-coroutines-kotlinconf-2017))
   * [Deep dive into Coroutines](https://www.youtube.com/watch?v=YrrUCSi72E8) (Roman Elizarov at KotlinConf 2017, [slides](https://www.slideshare.net/elizarov/deep-dive-into-coroutines-on-jvm-kotlinconf-2017))
   * [Kotlin Coroutines in Practice](https://www.youtube.com/watch?v=a3agLJQ6vt8) (Roman Elizarov at KotlinConf 2018, [slides](https://www.slideshare.net/elizarov/kotlin-coroutines-in-practice-kotlinconf-2018))
* Language design overview:
  * Part 1 (prototype design): [Coroutines in Kotlin](https://www.youtube.com/watch?v=4W3ruTWUhpw) 
    (Andrey Breslav at JVMLS 2016)
  * Part 2 (current design): [Kotlin Coroutines Reloaded](https://www.youtube.com/watch?v=3xalVUY69Ok&feature=youtu.be) 
    (Roman Elizarov at JVMLS 2017, [slides](https://www.slideshare.net/elizarov/kotlin-coroutines-reloaded)) 

### Feedback

Please, submit feedback to:

* [Kotlin YouTrack](http://kotl.in/issue) on issues with implementation of coroutines in Kotlin compiler and feature requests.
* [`kotlinx.coroutines`](https://github.com/Kotlin/kotlinx.coroutines/issues) on issues in supporting libraries.

## Revision history

This section gives an overview of changes between various revisions of coroutines design.

### Changes in revision 3.3

* Coroutines are no longer experimental and had moved to `kotlin.coroutines` package.
* The whole section on experimental status is removed and migration section is added.
* Some non-normative stylistic changes to reflect evolution of naming style.
* Specifications are updated for new features implemented in Kotlin 1.3:
  * More operators and different types of functions are supports.
  * Changes in the list of intrinsic functions:
  * `suspendCoroutineOrReturn` is removed, `suspendCoroutineUninterceptedOrReturn` is provided instead.
  * `createCoroutineUnchecked` is removed, `createCoroutineUnintercepted` is provided instead.
  * `startCoroutineUninterceptedOrReturn` is provided.
  * `intercepted` extension function is added.
* Moved non-normative sections with advanced topics and more examples to the appendix at end of the document to simplify reading.

### Changes in revision 3.2

* Added description of `createCoroutineUnchecked` intrinsic.

### Changes in revision 3.1

This revision is implemented in Kotlin 1.1.0 release.

* `kotlin.coroutines` package is replaced with `kotlin.coroutines.experimental`.
* `SUSPENDED_MARKER` is renamed to `COROUTINE_SUSPENDED`.
* Clarification on experimental status of coroutines added.

### Changes in revision 3

This revision is implemented in Kotlin 1.1-Beta.

* Suspending functions can invoke other suspending function at arbitrary points.
* Coroutine dispatchers are generalized to coroutine contexts:
  * `CoroutineContext` interface is introduced.
  * `ContinuationDispatcher` interface is replaced with `ContinuationInterceptor`.
  * `createCoroutine`/`startCoroutine` parameter `dispatcher` is removed.
  * `Continuation` interface includes `val context: CoroutineContext`.
* `CoroutineIntrinsics` object is replaced with `kotlin.coroutines.intrinsics` package.

### Changes in revision 2

This revision is implemented in Kotlin 1.1-M04.

* The `coroutine` keyword is replaced by suspending functional type.
* `Continuation` for suspending functions is implicit both on call site and on declaration site.
* `suspendContinuation` is provided to capture continuation is suspending functions when needed.
* Continuation passing style transformation has provision to prevent stack growth on non-suspending invocations.
* `createCoroutine`/`startCoroutine` coroutine builders are introduced.
* The concept of coroutine controller is dropped:
  * Coroutine completion result is delivered via `Continuation` interface.
  * Coroutine scope is optionally available via coroutine `receiver`.
  * Suspending functions can be defined at top-level without receiver.
* `CoroutineIntrinsics` object contains low-level primitives for cases where performance is more important than safety.

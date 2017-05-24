# KEEP-COFUN. Coroutines for Kotlin

Document type: Language Design Proposal
Document authors: Andrey Breslav, Vladimir Reshetnikov
Other contributors: Stanislav Erokhin, Ilya Ryzhenkov
Document status: under review
Prototype implementation: not started  

## Abstract

We propose to add coroutines to Kotlin. This concept is also known as or partly covers

- generators/yield
- async/await
- (delimited or stackless) continuations 

## Use cases

Coroutine can be thought of as a _suspendable computation_, the one that can suspend at some points and later continue 
(possibly on another thread). Coroutines calling each other (and passing data back and forth) can form the machinery for 
cooperative multitasking, but this is not exactly the driving use case for us.
 
### Asynchronous computations 
 
First motivating use case for coroutines is asynchronous computations (handled by async/await in C# and other languages). 
Let's take a look at how such computations are done with callbacks. As an inspiration, let's take 
asynchronous I/O (the APIs below are simplified, to make examples shorter, we use named arguments to make the code more
self-explanatory):

```
inFile.read(into = buf, whenDone = {
    bytesRead ->
    ...
    ...
    val newData = process(buf, bytesRead)
    outFile.write(from = buf, whenDone = {
        ...
        ...
        outFile.close()          
    })
})
```

Note that we have a callback inside a callback here, and while it saves us from a lot of boilerplate (e.g. there's no 
need to pass the `buf` parameter explicitly to callbacks, they just see it as part of their closure), the indentation
levels are growing every time, and one can easily anticipate the problems that may come at nesting levels greater than one 
(google for "callback hell" to see how much people suffer from this in current JavaScript, where they have no choice 
other than use callback-based APIs).

This same computation can be expressed straightforwardly as a coroutine (provided that there's a library that adapts
 the I/O APIs to coroutine requirements):
 
```
asyncIO {
    val bytesRead = inFile.read(into = buf) // suspension point
    ...
    ...
    val newData = process(buf, bytesRead)
    outFile.write(from = buf) // suspension point
    ...
    ...
    outFile.close()
}
```

If we assume that every _suspension point_ (such points are to be statically determined at compile time) implicitly
receives as an argument a callback enclosing the entire _continuation_ of the `asyncIO` coroutine, we can see that this is 
the same code as above, but written in a more readable way. NOTE: passing continuation lambdas around is not exactly 
how we are proposing to implement coroutines, it's just a useful mental model. 

Note that in the callback-passing style having an asynchronous call in the middle of a loop can be tricky, and in a
coroutine a suspension point in a loop is a perfectly normal thing to have:

```
asyncIO {
    while (true) {
        val bytesRead = inFile.read(into = buf) // suspension point
        if (bytesRead == -1) break
        ...
        val newData = process(buf, bytesRead)
        outFile.write(from = buf) // suspension point
        ...
    }
}
```

One can imagine that handling exceptions is also a bit more convenient in a coroutine.

There's another style of expressing asynchronous computations: through futures (and their close relatives — promises).
We'll use an imaginary API here, to apply an overlay to an image:

```
val future = runAfterBoth(
    asyncLoadImage("...original..."), // creates a Future 
    asyncLoadImage("...overlay...")   // creates a Future
) {
    original, overlay ->
    ...
    applyOverlay(original, overlay)
}
return future.get()
```

This could be rewritten as

```
asyncFutures {
    val original = asyncLoadImage("...original...") // creates a Future
    val overlay = asyncLoadImage("...overlay...")   // creates a Future
    ...
    return applyOverlay(await(original), await(overlay))
}
```

Again, less indentation and more natural composition logic (and exception handling, not shown here). For more complex
logic the difference between the coroutine code and futures-based code becomes much more dramatic.

> With the help of _delegated properties_, the example above may be simplified even further:
```
asyncFutures {
    val original by asyncLoadImage("...original...")
    val overlay by asyncLoadImage("...overlay...")
    ...
    // access to the properties (i.e. the getValue() function) is a suspension point,
    // so there's no need for explicit await()
    return applyOverlay(original, overlay)
}
```

### Generators

Another typical use case for coroutines would be lazily computed sequences of values (handled by `yield` in C#, Python 
and many other languages):
 
```
val seq = input.filter { it.isValid() }.map { it.toFoo() }.filter { it.isGood() }
```

This style of expressing (lazy) collection filtering/mapping is often acceptable, but has its drawbacks:

 - `it` is not always fine for a name, and a meaningful name has to be repeated in each lambda
 - multiple intermediate objects created
 - non-trivial control flow and exception handling are a challenge
 
As a coroutine, this becomes close to a "comprehension":
 
```
val seq = input.transform {
    if (it.isValid()) {      // "filter"
        val foo = it.toFoo() // "map"
        if (foo.isGood()) {  // "filter"
            yield(foo) // suspension point        
        }                
    }
} 
```

This form may look more verbose in this case, but if we add some more code in between the operations, or some non-trivial
control flow, it has invaluable benefits:

```
val seq = transform {
    yield(firstItem)

    for (item in input) {
        if (!it.isValid()) break // don't generate any more items
        val foo = it.toFoo()
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

This approach also allows to express `yieldAll(sequence)`, which simplifies joining lazy sequences and allows for 
efficient implementation (a naïve one is quadratic in the depth of the joins).  

Some other use cases:
 
* UI logic involving off-loading long tasks from the event thread
* Background processes occasionally requiring user interaction, e.g., show a modal dialog
* Communication protocols: implement each actor as a sequence rather than a state machine
* Web application workflows: register a user, validate email, log them in (a suspended coroutine may be serialized and stored in a DB)
   




## Coroutines overview

This section gives a brid's-eye view of the proposed language mechanisms that enable writing coroutines and libraries that govern their semantics.  

### Terminology

* A _coroutine_ -- a block of code (possibly, parameterized) whose execution can be suspended and resumed potentially multiple times (possibly, at several different points), yielding the control to its caller. [Note: The wording "potentially multiple times" should be understood as "zero, one or more times". While it is rarely useful, a coroutine can we written in a way such that it is never suspended at all. End note]. 

Syntactically, a coroutine looks exactly as a function literal `{ x, y -> ... }`. A coroutine is distinguished by the compiler from a function literal based on the special type context in which it occurs. A coroutine is typechecked using different rules it in a different way than a regular function literal.
 
> Note: Some languages with coroutine support allow coroutines to take forms both of an anonymous function and of a method body. Kotlin supports only one syntactic flavor of coroutines, resembling function literals. In case where a coroutine in the form of a method body would be used in another language, in Kotlin such method would typically be a regular method with an expression body, consisting of an invocation expression whose last argument is a coroutine: 
 ```
 fun asyncTask() = async { ... }
 ```

* _Suspension point_ -- a special expression in a coroutine that designates a point where the execution of the coroutine is suspended. Syntactically, a suspension point looks as an invocation of a function that's marked with a special modifier on the declaration site (other syntactic options may be considered at some point). 

* Such a function is called a _suspending function_, it receives a _continuation_ object as an argument which is passed implicitly from the calling coroutine.

* _Continuation_ is like a function that begins right after one of the suspension points of a coroutine. For example:
```
generate {
    for (i in 1..10) yield(i * i)
    println("over")
}  
```  
Here, every time the coroutine is suspended at a call to `yield()`, _the rest of its execution_ is represented as a continuation, so we create 10 continuations: first runs the loop with `i = 2` and suspends, second runs the loop with `i = 3` and suspends, etc, the last one prints "over" and exits the coroutine. 

### Implementation through state machines

As mentioned above, implementing continuations in coroutines as lambdas makes certain scenarios (suspending in a loop, handling exceptions, etc) difficult. This is why many languages implement them through _state machines_.  
 
Main idea: a coroutine is compiled to a state machine, where states correspond to suspension points. Example: let's take a coroutine with two suspension points:
 
```
val a = a()
val y = await(foo(a)) // suspension point
b()
val z = await(bar(a, y)) // suspension point
c(z)
``` 
 
For this coroutine there are three states:
 
 * initial (before any suspension point)
 * after the first suspension point
 * after the second suspension point
 
Every state is an entry point to one of the continuations of this coroutine (the first continuation "continues" from the very first line). 
 
The code is compiled to an anonymous class that has a method implementing the state machine, a field holding the current
 state of the state machine, and fields for local variables of the coroutines that are shared between states. Here's pseudo-bytecode for the coroutine above: 
  
```
class <anonymous_for_state_machine> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    A a = null
    Y y = null
    
    void resume(Object data) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        if (label == 2) goto L2
        else throw IllegalStateException()
        
      L0:
        // data is expected to be `null` at this invocation
        a = a()
        label = 1
        await(foo(a), this) // 'this' is passed as a continuation 
        return
      L1:
        // external code has resumed this coroutine passing the result of await() as data 
        y = (Y) data
        b()
        label = 2
        await(bar(a, y), this) // 'this' is passed as a continuation
        return
      L3:
        // external code has resumed this coroutine passing the result of await() as data 
        Z z = (Z) data
        c(z)
        label = -1 // No more steps are allowed
        return
    }          
}    
```  

Note that:
 * exception handling and some other details are omitted here for brevity,
 * there's a `goto` operator and labels, because the example depicts what happens in the byte code, not the source code.

Now, when the coroutine is started, we call its `resume()`: `label` is `0`, and we jump to `L0`, then we do some work, 
set the `label` to the next state — `1` and return (which is — suspend the execution of the coroutine). 
When we want to continue the execution, we call `resume()` again, and now it proceeds right to `L1`, does some work, sets
the state to `2`, and suspends again. Next time it continues from `L3` setting the state to `-1` which means "over, 
no more work to do". The details about how the `data` parameter works are given below.

A suspension point inside a loop generates only one state, because loops also work through (conditional) `goto`:
 
```
var x = 0
while (x < 10) {
    x += await(nextNumber())
}
```

is generated as

```
class <anonymous_for_state_machine> : Coroutine<...> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    int x
    
    void resume(Object data) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        else throw IllegalStateException()
        
      L0:
        x = 0
      LOOP:
        if (x > 10) goto END
        label = 1
        await(nextNumber(), this) // 'this' is passed as a continuation 
        return
      L1:
        // external code has resumed this coroutine passing the result of await() as data 
        x += ((Integer) data).intValue()
        label = -1
        goto LOOP
      END:
        label = -1
        return
    }          
}    
```  

Note: boxing can be eliminated here, through having another parameter to `resume()`, but we are not getting into these details.

## The building blocks

One of the driving requirements for this proposal is flexibility: we want to be able to support many existing asynchronous APIs and other use cases (unlike, for example, C#, where async/await and generators are tied up to Task and IEnumerable) and minimize the parts hard-coded into the compiler.
  
As a result, the compiler is only responsible for transforming coroutine code into a state machine, and the rest is left to libraries. We provide more or less direct access to the state machine, and introduce building blocks that frameworks and libraries can use: _coroutine builders_, _suspending functions_ and _controllers_.

NOTE: all names, APIs and syntactic constructs described below are subject to discussion and possible change.

### A lifecycle of a coroutine

As mentioned above, the compiler doesn't do much more than creating a state machine, so the rest of the coroutine lifecycle is customizable. The coroutine object that encapsulates the state machine (or rather a factory capable of creating those objects) is passed to a function such as `async {}` or `generator {}` from above, we call these functions _coroutine builders_. The builder function's biggest responsibility is to define a _controller_ for the coroutine. Controller is an object that determines which suspension functions are available inside the coroutine, how the return value of the coroutine is process, and how exceptions are handled. 

Normally, a builder function creates a controller, passes it to a factory to obtain a working instance of the coroutine, and returns some useful object: Future, Sequence, AsyncTask or alike. The returned object is the public API for the coroutine whose inner workings are governed by the controller.
    
To summarize:
- coroutine builder is an entry point that takes a coroutine as a block of code and returns a useful object to the client,
- controller is an object used internally by the library to define and coordinate all aspects of the coroutine's behavior.
        
### Library interfaces

Here's the minimal version of the core library interfaces related to coroutines (there will likely be extra members to handle advanced use cases such as restrating the coroutine from the beginning or serializing its state):

``` kotlin
interface Coroutine<C> {
   fun entryPoint(controller: C): Continuation<Unit>
}

interface Continuation<P> {
   fun resume(data: P)
   fun resumeWithException(exception: Throwable)
}
```

So, a typical coroutine builder would look like this:
 
```
fun <T> async(coroutine c: () -> Coroutine<FutureController<T>>): Future<T> { 
    // get an instance of the coroutine
    val coroutine = c() 
    
    // controllers will be discussed below
    val controller = FutureController<T>()
     
    // to start the execution of the coroutine, obtain its fist continuation
    // it does not take any parameters, so we pass Unit there
    val firstContinuation = coroutine.entryPoint(controller)
    firstContinuation.resume(Unit)
    
    // return the Future object that is created internally by the controller
    return controller.future
}    
``` 

The `c` parameter normally receives a lambda, and its `coroutine` modifier indicates that this lambda is a coroutine, so its body has to be translated into a state machine. Note that such a lambda may have parameters which can be naturally expressed as `(Foo, Bar) -> Coroutine<...>`.  
  
The usual workflow of a builder is to first pass the user-defined parameters to the coroutine. In our example there're no parameters, and this amounts to calling `c()` which returns a `Coroutine` instance. Then, we create a controller and pass it to the `entryPoint()` method of a the `Coroutine`, to obtain the first `Continuation` object whose `resume()` starts the execution of the coroutine. (Passing `Unit` to `resume()` may look weird, but it will be explained below.)    

NOTE: Technically, one could implement the `Coroutine` interface and pass a lambda returning that custom implementation to `asyncExample`. 

NOTE: To allocate fewer objects, we can make the state machine itself implement `Continuation`, so that its `resume` is the main method of the state machine. In fact, the initial lambda passed to the coroutine builder, `() -> Coroutine<...>` can be also implemented by the same state machine object. Sometimes the lambda and the `entryPoint()` function may be called more than once and with different arguments yielding multiple instances of the same coroutine. To support this case, we can teach the sole lambda-coroutine-continuation object to clone itself.   

### Controller

The purpose of the controller is to govern the semantics of the coroutine. A controller can define 
- suspending functions,
- handlers for coroutine return values,
- exception handlers for exceptions thrown inside the coroutine.

Typically, all suspending functions and handlers will be members of the controller. We may need to allow extensions to the controller as suspending functions, but this should be through an opt-in mechanism, because many implementations would break should any unanticipated suspension points occur in a coroutine (for example, if an `async()` call happens unexpectedly among `yield()` calls in a basic generator, iteration will end up stuck leading to undesired behavior).

It is language rule that suspending functions (and probably other specially designated members of the controller) are available in the body of a coroutine without qualification. In this sense, a controller acts similarly to an [implicit receiver](https://kotlinlang.org/docs/reference/extensions.html#declaring-extensions-as-members), only it exposes only some rather than all of its members.      

### Suspending functions

To recap: a _suspension point_ is an expression in the body of a coroutine which cause the coroutine's execution to
suspend until it's explicitly resumed by someone. Suspension points are calls to specially marked functions called _suspending functions_. 

A suspending function looks something like this:
  
```
suspend fun <T> await(f: CompletableFuture<T>, c: Continuation<T>) {
    f.whenComplete { result, throwable ->
        if (throwable == null)
            // the future has been completed normally
            c.resume(result) 
        else          
            // the future has completed with an exception
            c.resumeWithException(throwable)
    }
}
``` 

The `suspend` modifier indicates that this function is special, and its calls are suspension points that correspond to states of a state machine. 

When `await(f)` is called in the body of the coroutine, the second parameter (a continuation) is not passed explicitly, but is injected by the compiler. All the user sees is a call, but in fact after the call to `await()` completes, the coroutine is suspended and the control is transferred to its caller. The execution of the coroutine is resumed only when the future `f` is completed, and `resume()` is called on the continuation `c`.
 
The value passed to `resume()` is the **return value** of the `await()`. Since this value will only be known when the future is completed, `await()` can not return it right away, and the return type in _any_ suspending function declaration is always `Unit`. When the coroutine is resumed, the result of the last suspending call is passed as a parameter to the `resume()` of the continuation. (This is why the `entryPoint()` gives a `Continuation<Unit>` — there's no call to return a result for, so we simply pass a placeholder object.)  

Consider this example of a coroutine:
 
```
async {
    val x = await(foo) // suspension point
    println(x)
}
```

Here's the state machine code generated for it:
 
```
void resume(Object data) { 
    if (label == 0) goto L0
    else if (label == 1) goto L1
    else throw IllegalStateException()
     
    L0:
      label = 1
      contoller.await(foo, this)
      return
    L1:
      Foo x = data as Foo  // the value of `await(foo)` is passed back as `data`
      println(x)
      label = -1
      return
}  
```

Note a detail that has been omitted before: `await()` is called as a member of the controller (which is a field in the state machine class). 

The first time (`label == 0`) the value of `data` is ignored, and the second time (`label == 1`) it is used as the result of `await(foo)`.  
 
So, a suspension point is a function call with an implicit continuation parameter. Such functions can be called in this
 form only inside coroutines. Elsewhere they may be called only with both parameters passed explicitly.
 
Note that the library has full control over which thread the continuation is resumed at and what parameter is passed to it. It may even continue the execution of the coroutine synchronously by immediately calling `resume()` on the same thread. 
    
NOTE: some may argue that it's better to make suspension points more visible at the call sites, e.g. prefix them with
  some keyword or symbol: `#await(foo)` or `suspend await(foo)`, or something. These options are all open for discussion.   
  
### Result handlers

A coroutine body looks like a regular lambda in the code and, like a lambda, it may have parameters and return a value. Handling parameters is covered above, and the returned values are passed to a designated function (or functions) in the controller:
 
```
class FutureController<T> {
    val future: CompletableFuture<T> = ...
    
    operator fun handleResult(t: T, c: Continuation<Nothing>) {
        future.complete(t)    
    }
} 
``` 
 
The `handleResult()` function is called on the last expression in the body of a coroutine as well as on each explicit `return` from it:
   
```
val r = async {
    if (...) 
        return default // this calls `handleResult(default)`
    val f = await(foo)
    f + 1 // this calls `handleResult(f + 1)`           
}   
```   
 
As any function, `handleResult()` may be overloaded, and if a suitable overload is not available for a returned expression, it is a compilation error. If no `handleResult()` is defined in the controller, the last expression in the body of the coroutine is ignored and `return` with an argument is forbidden (`return Unit` may be allowed).
 
Note: the continuation parameter in the result handler is provided for uniformity, and may be used for advanced operations such as resetting the state machine or serializing its state. 
 
### Exception handlers
 
Handling exceptions in coroutines may be tricky, and some details of it are to be refined later, but the basics are as follows.
 
A controller may define an exception handler:
 
```
operator fun handleException(e: Throwable, c: Continuation<Nothing>) {
    future.completeExceptionally(e) 
}
```

This handler is called when an unhandled exception occurs in the coroutine (the coroutine itself becomes invalid then and can not be continued). Technically, it is implemented by wrapping the whole body of the coroutine (the whole state machine) into a try/catch block whose catch calls the handler:
  
```
void resume(Object data) {
    try {
        if (label == 0) goto L0
        else if (label == 1) goto L1
        else throw IllegalStateException()
        
    L0:
        ...
        return
    L1:
        ...
        return
    } catch (Throwable e) {
        controller.handleException(e)     
    }          
}
```  

Exception handlers can not be overloaded.

TODO: finally blocks
 
### Continuing with exception

TODO
 
## Type-checking coroutines

TODO

## Code examples

TODO

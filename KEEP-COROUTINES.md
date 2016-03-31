# KEEP-COROUTINES. Coroutines for Kotlin

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
Let's take a look at how such computations are done in the callback-passing style. As an inspiration, let's take 
asynchronous I/O (the APIs below are simplified, to make examples shorter):

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
need to pass the `buf` parameter explicitly to callbacks, they just see it as part fo their closure), the indentation
is growing every time, and one can easily anticipate the problems that may come at nesting levels greater than one 
(google for "Callback hell" to see how much people suffer from this in JavaScript, where they have no choice other
than use asynchronous APIs).

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
receives as an argument a callback enclosing the entire _continuation_ of the asyncIO coroutine, we can see that this is 
the same code as above, but written in a more understandable way. NOTE: passing continuation lambas around is not exactly 
how we are proposing to implement coroutines, it's just a useful mental model. 

Note that in the callback-passing style having an asynchronous call in the middle of a loop can be tricky, and in a
coroutine a suspension point in a loop is a perfectly normal thing to have:

```
asyncIO {
    while (true) {
        val bytesRead = inFile.read(into = buf) // suspension point
        if (bytesRead) break
        ...
        val newData = process(buf, bytesRead)
        outFile.write(from = buf) // suspension point
        ...
    }
}
```

One can imagine that handling exceptions is also a bit more convenient in a coroutine.

There's another style of expressing asynchronous computations: through futures (and their close relatives — promises).
We'll use an imaginary API here, to apply an overlay to an image (explicit types are not essential, written down for
illustration purposes only):

```
  val original: Future<Image> = asyncLoadImage(...) 
  val overlay: Future<Image> = asyncLoadImage(...)
  val result: Future<Image> = runAfterBoth(original, overlay) {
      orig, over ->
      ...
      applyOverlay(orig, over)
  }
  return result.get()
```

This could be rewritten as

```
asyncImages {
  val original = asyncLoadImage(...)
  val overlay = asyncLoadImage(...)
  val (orig, over) = awaitBoth(original, overlay)
  ...
  return applyOverlay(orig, over)
}
```

Again, less indentation and more natural composition logic (and exception handling, not shown here).


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
    if (it.isValid()) { // "filter"
        val foo = it.toFoo() // "map"
        if (foo.isGood()) { // "filter"
            yield(foo) // suspension point        
        }                
    }
} 
```

This form looks is more verbose in this case, but if we add some more code in between the operations, or some non-standard
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
        yield(lastItem())
    }
    finally {
        // some finalization code
    }
} 
```

This approach also allows to express `yieldAll(sequence)`, which simplifies joining lazy sequences and allows for 
efficient implementation (a naïve one is quadratic in the depth of the joins).  

Other use cases:
 
 * "Maybe monad": computation aborted on a null intermediate result
 * Asynchronous protocol written out as sequential
 * Website registration steps implemented without an explicit state machine, but through a (serializable) coroutine
 * TODO
 
## Coroutines overview
 
We think of coroutines as computations having designated _suspension points_, a coroutine can be suspended only at one 
of its suspension points. All suspension points are known at compile time. While the syntax is not fixed yet, the options 
are:

* a suspension point is designated by a call to a specially annotated function,
* a suspension point is somehow marked in the code, e.g. with a special symbol or keyword.

A useful mental model is think that a suspension point receives the _continuation_ as an implicit parameter that it may
store or execute at some point.
 
## Implementation through state machines
 
Main idea: the block of code with suspension points inside (a "colambda") is compiled to a state machine, where states
 correspond to suspension points. For example, for a coroutine with two suspension points, there are three states:
 
 * initial (before any suspension point)
 * after the first suspension point
 * after the second suspension point
 
```
val a = a()
val y = await(foo(a)) // suspension point
b()
val z = await(bar(a, y)) // suspension point
c(z)
``` 
 
The code is compiled to an anonymous class that has a method implementing the state machine, a field holding the current
 state of the state machine, and fields for local variables of the coroutines that are shared between states:
  
```
class <anonymous_for_state_machine> : Coroutine {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    A a = null
    Y y = null
    
    void main() {
        if (label == 0) goto L0
        if (label == 1) goto L1
        if (label == 2) goto L2
        else throw IllegalStateException()
        
      L0:
        a = a()
        label = 1
        await(foo(a), this) // 'this' is passed as a continuation 
        return
      L1:
        // val y gets set by the await() when the asynchronous call is finished
        b()
        label = 2
        await(bar(a, y), this) // 'this' is passed as a continuation
        return
      L3:
        // val z gets set by the await() when the asynchronous call is finished
        c(z)
        label = -1
        return
    }          
}    
```  

Note that:
 * exception handling code is eliminated here for brevity,
 * there's a `goto` operator and labels, because the example shows the byte code, not the source code,

Now, when the coroutine is started, we call its `main()`: `label` is `0`, and we jump to `L0`, then we do some work, 
set the `label` to the next state — `1` and return (which is — suspend the execution of the coroutine). 
When we want to continue the execution, we call `main()` again, and now it proceeds right to `L1`, does some work, sets
the state to `2`, and suspends again. Next time it continues from `L3` setting the state to `-1` which means "over, 
no more work to do".

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
    int result1
    
    void main() {
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
        // result1 gets set by the await() when the aynchronous call is finished
        x += result1
        label = 2
        goto LOOP
      END:
        return
    }          
}    
```  

## The building blocks

One of the driving requirements for this proposal is flexibility: we want to be able to support many existing asynchronous
APIs and other use cases (unlike, for example, C#, where async/await and generators are tied up to Task and IEnumerable).

To achieve this, we provide more direct access to the state machine, and introduce building blocks that frameworks and
libraries can use: _coroutine builders_, _suspension point functions_ and _controllers_.

NOTE: all names, APIs and syntactic constructs described below are subject to discussion and possible change.

### Coroutine builders

_Coroutine builders_ are functions that take state machines and turn them into some useful objects like Futures, 
Observables, lazy Sequences etc:
   
```
val f: Future<T> = asyncExample { 
    // coroutine body
}
```

Here, `asyncExample` is a function that receives a block which is a bofy of a coroutine ("colambda") as an argument. 
Under the hoods this block is translated into a state machine, and the parameter type for `asyncExample` is not 
a function type (e.g. `() -> Unit`), but an interface `Coroutine` that exposes functions to initialize the coroutine, 
start its execution, etc:
 
```
fun <T> asyncExample(coroutine: Coroutine<...>): Future<T> { ... }
```

The job of `asyncExample` is to wrap the coroutine into a `Future` object (those who are into GoF design patterns may say
 that it's an "Adaptor Factory Method"): for example, it may create a promise (`CompletableFuture`) and set a completion
 handler on the coroutine to fulfill it (i.e. call `CompletableFuture.complete()`). 
 
This essentially proposes a change to the language syntax: previously, when we saw a lambda expression, we 
expected the type of it (e.g. a type of a parameter it is passed for or a property/variable it is assigned to) to be a
function type, e.g. `(Int) -> String`, but now it can also be a `Coroutine<...>`. So this interface is a special
type in the sense that the compiler knows about it and transforms the lambdas passed to such parameters to state machines.

* It is open to discussion whether we should have a special syntax for `Coroutine` (like we have for function types), or
  leave them is the generic form.
 
NOTE: Technically, one could implement the `Coroutine` interface and pass a custom implementation to `asyncExample`, in the 
same manner as it can be done with functions. And, of course, a function that takes a `Coroutine` doesn't have to really
build anything, so _coroutine builder_ is not a syntactical property, or kind of functions the language knows about, but
simply a coding pattern.
 
### Suspension points

To recap: a _suspension point_ is an expression in the body of a coroutine which cause teh coroutine's execution to
suspend until it's explicitly resumed by someone.
  
An example of a suspension point is `await()`, that takes an asynchronous computation (e.g. a `CompletableFuture`) and
suspends until it's completed. Technically, it could subscribe for completion of the Future with a handler that resumes
the coroutine when called. In fact, it needs to do a bit more, because there's the result computed by the Future, which
needs to be put back "into" the coroutine:

```
asyncExample {
    val x = await(foo) // suspension point
    println(x)
}
```

The state machine for this example will look something like this:

```
L0:
  label = 1
  await(foo, this)
  return
L1:
  println(x)
  return
```

The code after `L1` assumes that someone has already written a value computed by `foo` into `x` (which is a field in the
anonymous class of the state machine). The way it works is roughly as follows:
 
```
fun <T> await(f: CompletableFuture<T>, continuation c: Continuation<T, ...>) {
    f.whenComplete { t, throwable ->
        if (throwable != null) 
            c.runWithException(throwable)
        else          
            c.run(t)
    }
}
``` 

Here, we have another new piece of syntax: the `continuation` modifier on the second parameter `c` (that is not passed
explicitly to `await()` in the code); and a new interface: `Continuation` that can either run with a given value, or
run with exception (we'll not delve into the exception handling just yet, although it's a very important and interesting 
topic). The `T` type-argument of a continuation is the type that `await` "returns" in the coroutine. Since the result is 
received asynchronously, it can not be returned directly from await, and is passed to the continuation as an argument to
`run`.

So, a suspension point is a function call with an implicit continuation parameter. Such functions can be called in this
 form only inside coroutines. Elsewhere they may be called only with both parameters passed explicitly.
  
NOTE: some may argue that it's better to make suspension points more visible at the call sites, e.g. prefix them with
  some keyword or symbol: `#await(foo)` or `suspend await(foo)`, or something. These options are all open for discussion.

But where does the implementation of `Continuation` come from, and what is its `run` function actually doing? 
The `run` function does two things: writes a value to a field that represents a local variable `x` in the state machine 
object, and runs the state machine's `main()` so that it proceeds with the execution of the coroutine.
 
NOTE: even if the result of `await()` is not assigned to a variable in the code of the coroutine, such a variable may be
 created by the compiler, or some other mechanism of putting a value into the coroutine through a field may be employed 
 (e.g. we could use one field for all suspension point results, or have one field for reference-typed results and another
 for primitives to avoid boxing, because any primitive can be encoded as a `Long`).
 
To achieve this, the compiler needs to either generate a new class that implements `Continuation` and has the appropriate
 `run` function implementation, or make the state machine itself implement `Continuation`, which would mean fewer classes
 and allocations, and thus is the option we'd prefer.   

### Controllers

The model presented above is rather flexible: anyone can declare a coroutine builder or a suspension point independently.
This flexibility may need some governance to avoid chaos, plus for many use cases there's a need for some kind of
"execution context": a party that knows, for example, what thread pool to schedule computaions on, what time-outs to set,
how to handle errors and so on. Plus, there are some type-checking issues, we'll cover below. To address all these concerns
we introduce _controller objects_.

One can think of a _controller object_ as an [implicit receiver](https://kotlinlang.org/docs/reference/extensions.html#declaring-extensions-as-members) 
available inside a coroutine, because it's members are available in the body of coroutine without explicit qualification. 
In fact, most suspension points are either members of or extensions to a controller, so they have access to the execution 
context and preferences. 

In fact, every coroutine must have a controller set (normally by its builder) before it is executed, this is done through
calling some function on the `Coroutine` object that the builder receives. 

* It's open to discussion whether we should allow calling suspension points which are not members/extensions to the 
current controller, or there may be "free-floating" suspension points.  

Note that normally controllers are singletons or at least very long-lived objects, so the need to allocate them does not
impose significant performance penalties.

What can controllers do?
 * define behavior at suspension points,
 * define exception handlers,
 * define handlers for return from the coroutine (e.g. the last expression in the code block, or explicit `return`'s),
 * (maybe something else?)
 
## Type-checking coroutines 
 
The type of the controller is captured in the type-arguments of the `Coroutine` object, and thus is known to the type 
checker, as well as two other important things: the parameters of the coroutine and the type of its customizable internal 
state object (not to be confused with the current state of the state machine, which is always an `Int`, and named `label`
in the code examples above):
  
```
interface Coroutine<C, P, S> { ... }
```  

These types are fixed (maybe except the state type S) at the point of passing the body of a coroutine to the builder, 
because the builder specifies the full type of a coroutine. For example, here's a builder that creates Futures that are
computed on the `ForkJoinPool.commonPool()` from coroutines that take no parameters:
 
```
fun <T> asyncExample(body: Coroutine<CommonPoolFuturesController, () -> Unit, CompletableFuture<T>>): Future<T> {
    // some way of setting the controller
    body.controller = CommonPoolFuturesController 

    // remember the future we need to complete later, when the coroutine is finished
    val f = CompletableFuture<T>()    
    body.state = CompletableFuture<T>()

    // run the first step of the state machine in the ForkJoinPool
    CommonPoolFuturesController.exec { body.firstStep().run(null) }

    // return the created future
    return f
}
```

This function may be called like this:

```
val future = asyncExample {
    await(future)
}
```

Here, `await` is a member of the controller, which may be defined like this:

```
object CommonPoolFuturesController {
    fun <T> await(f: CompletableFuture<T>, continuation c: Continuation<T, *>) {
        f.whenComplete { t, throwable ->
            if (throwable != null) 
                c.runWithException(throwable)
            else          
                c.run(t)
        }
    }

    operator fun <T> handleResult(result: T, coroutine: CompletedCoroutine<CompletableFuture<T>>) {
        coroutine.state.complete(result)
    } 
    
    operator fun handleException(exception: Throwable, coroutine: : CompletedCoroutine<CompletableFuture<T>>) {
        coroutine.state.completeExceptionally(exception)
    }
}
```
 
Note that interfaes `Continuation` and `CompletedCoroutine` (the last one is passed to completion handlers: one for 
result, i.e. normal termination, the other for exception, i.e. abnormal termination) both have a type-parameter: this is 
`S`, the custom state, and it must be compatible with the one of the builder that this coroutine is passed to:
  
```
interface Coroutine<C, P, S> { ... }
interface Continuation<P, S> { ... }
interface CompletedCoroutine<S> { ... }
```

NOTE: it looks like `CompletedCoroutine<S>` may be expressed as `Continuation<Nothing, S>`, but it's up to discussion.

So, a **typing rule**: _all the `S` type-arguments must agree inside a coroutine_.

Now, if we want a generator, all its `yield`'s must agree on the type of the value yielded: 

```
val seq = generate {
    while (true) {
        yield(Random.nextInt())
        yield(0)
    }
}
```

Here, the compiler must be able to look at the arguments to `yield` and deduce that the result of `generate` is 
`Sequence<T>`. This is the kind of analysis that's done for `return` expressions: the type checker collects all 
`return`'s in a lambda and finds a common return type for their expressions. The difference here is that `yield` is
_just a function_, albeit a suspension point. So, we need to mark it somehow to tell the compiler to treat it specially.
Note that there may be more than one such function in the same controller, for example:
    
```    
val seq1 = generate {
    while (true) {
        yieldAll(seq.take(5))
        yield(0)
    }
}
```

This sequence has five items of teh previous one (random numbers and zeros), then a zero, then another five, and so on.
And the type of seqeuence in `yieldAll` must agree with the type passed to `yield`: one is `Sequence<Int>`, the other is
`Int`.
 
We propose to mark a type parameter of such a function as `inferFrom`, so that when `S` parameters are put into agreement,
the one in the builder application could be inferred from those marked `inferFrom`. Yes, teh inferred type is the `S`:

NOTE: the name of the modifier (as well as its applicability rules) is up to discussion.
  
```
fun <T> generate(coroutine: Coroutine<GeneratorsController, () -> Unit, LazySequenceImpl<T>): Sequence<T> {
    coroutine.controller = GeneratorsController
    val seq = LazySequenceImpl<T>
    coroutine.state = seq
    seq.step = coroutine.firstStep()
    return seq 
}

object GeneratorsController {
    fun <inferFrom T> yield(value: T, continuation c: Continuation<Unit, LazySequenceImpl<T>>) {
        c.state.addValue(value)
        c.state.step = c
    }

    fun <inferFrom T> yieldAll(values: Sequence<T>, continuation c: Continuation<Unit, LazySequenceImpl<T>>) {
        c.state.addValues(values)
        c.state.step = c
    }
    
    operator fun resultHandler(r: Unit, c: CompletedCoroutine<LazySequenceImpl<T>>) {
        c.state.completed = true        
    }
}

class LazySequenceImpl<T> : Sequence<T> {
    ...
    
    fun advanceIfNeeded() {
        if (!advancedSinceLastYield) step.run(Unit)
    }
    
    override fun iterator() = object : Iterator<T> {
        override fun hasNext(): Boolean {
            advanceIfNeeded()
            return !completed
        }
        
        override fun next(): T {
            advanceIfNeeded()
            if (completed) throw NoSuchElementException()
            return getNextValue()
        }
    }
    
    ...
}
```

The type checker looks at calls to `yield` and `yieldAll` in the body of the coroutine and creates typing constraints 
for the `Coroutine` parameter of the builder function `generate`, thus deducing that its `T` is `Int` in the example 
above.

# Design Notes: Web Workers

* **Type**: Design notes
* **Author**: Ilmir Usmanov
* **Contributors**: Anton Banykh, Zalim Bashorov, Ilya Gorbunov,
  Svyatoslav Kuzmich, Roman Elizarov.
* **Status**: Under consideration
* **Discussion and feedback**: https://github.com/Kotlin/KEEP/issues/241

## The Problem
While other Kotlin back-ends support multithreading natively, Kotlin/JS does
not. There are two reasons for the limitations. First, the code that goes to the
worker thread needs to be known at compile-time so that the compiler puts it in
either a separate JavaScript file or in a blob. Second, the worker thread does
not have access to the main thread's address space. The only possible way of
communication between threads is message passing. Thus, all the data needs to be
sent to the worker. In other words, worker threads are more like processes in
other back-ends, rather than threads.

Currently, if one wants to create a worker, there are two ways to do this.
Either create a separate Kotlin/JS module, compile it into a separate JavaScript
file, and call the `Worker` constructor, passing the file's path as a parameter.
Or, wrap the worker code in `Blob` and pass the blob to the `Worker`
constructor. Additionally, Mozilla's workers and Node.js's worker threads
provide different APIs.

All in all, comparing to other back-ends, the support of web workers is minimal.
In Kotlin/JVM, for example, the `thread` function provides much more
flexibility.

## Limitations
Before I propose a solution, I would like to list the limitations, which
cannot be avoided and should be taken into account.

### Communication
All the communication between main and worker threads are done via message
passing or `SharedArrayBuffer`. I will stick to message passing and not mention
`SharedArrayBuffer` since it is not required. That, however, can change in the
future.

Not everything can be sent to the worker. In short, only objects and primitives,
which can be copied using
[structured cloning algorithm](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm)
can be sent to the worker. The most notable objects,
which are not supported, are functions, the prototype chain, and DOM nodes.

To send the object, one should call the `Worker.postMessage` function. On the
worker side, one should register an event listener for the `message` event and
return a result to the main thread via the global `postMessage` function (I will
use modern JavaScript for clarity):
```js
// main.js
const worker = Worker("worker.js")
worker.addEventListener("message", message => {
  const { data } = message
  console.log(data.value)
}
worker.postMessage({value: "Ping"})

// worker.js
addEventListener("message", message => {
  const { data } = message
  if (data.value == "Ping")
    postMessage("Pong")
}
```
the example will print `Pong`, as expected.

### Worker Code
Since there are limitations on what can be sent to the worker, and since the
worker does not have access to the main thread's address space, we cannot run
any code on the worker. Most notable, unlike the `thread` function, our yet
theoretical `worker` function cannot accept any lambda. The lambda is not
copyable, and, thus, the lambda's code should be known at compile-time so that
the compiler can put it in a separate JavaScript file.

## Solution
With these limitations in mind, let us tackle how we can solve the problem.

### `worker` Intrinsic
First, let us see what we want to get. In Kotlin/JVM, we would write something
like
```kotlin
fun main() {
  var result: String? = null
  thread {
    result = "Pong"
  }.join()
  println(result!!)
}
```
In Kotlin/JS, however, we cannot block the main thread. Thus, the Kotlin/JS
version of the same code would look like this:
```kotlin
fun main() {
  worker {
    "Pong"
  }.then {
    println(it)
  }
}
```
So, in the stdlib, there will be an intrinsic `worker.`
```kotlin
@ImplicitCodeColoring(forbidsPackages = ["org.w3c.dom.**"])
fun <T> worker(block: WorkerGlobalScope.() -> T): Worker<T> = error("intrinsic")
```
The compiler puts the argument of the intrinsic to a separate JavaScript file.
The intrinsic accepts only block lambdas, i.e., the lambdas like
```kotlin
worker {
  // code
}
```
and passing a lambda stored in a variable is an error:
```kotlin
val c: WorkerGlobalScope.() -> Unit = {}
worker(c) // compilation error
```
This restriction exists since the compiler should be able to determine which
code goes to a separate JavaScript file, and we cannot send functions and
lambdas to a worker.

### Worker Interface
While `WorkerGlobalScope` comes from JavaScript, `Worker` is added to stdlib:
```kotlin
interface Worker<out T> {
  fun then(block: (T) -> Unit): Worker<T>
  fun catch(block: (Throwable) -> Unit): Worker<T>
}
```
`then` and `catch`, unlike the `worker` itself, accept variables, not just
blocks. These functions are transformed into an event listener (see
Compilation Model). Initially, `Worker`'s instances will not be able to be
stored in variables because of captured variables. More on that later.

### Implicit Code Coloring
Just an intrinsic, however, is not enough for anything useful.
If we do not allow any code transfer, we can still copy all functions called
inside the worker block to the JavaScript file, and functions, which they call
transitively, as long as they do not capture lambdas or call forbidden APIs.

To do that, the compiler marks the functions in the call graph and checks
whether they are safe to use in a worker. As long as they do not manipulate DOM
or call lambdas (except parameter lambdas, since they come from the caller, and
the compiler already colored them), they are considered safe. For example,
```kotlin
fun foo(): Int {
  return bar
}
val bar = 1

// ....

worker {
  foo()
  bar
}
```
compiles and works fine. However,
```
val c = { foo() }

worker {
  c()
  ::foo
}
```
results is a compilation error since functional types are not copyable.
This compiler check does not require explicitly marking every function, which
can be used in a worker with a modifier or an annotation. Instead, the compiler
implicitly colors the call graph. Hence, the name, implicit code coloring, as
opposing to explicit code coloring, which coroutines use.

### External functions
The compiler can check not every function. External functions, for example,
cannot be analyzed. Thus, to use them in a worker, one should annotate them with
`@WorkerSafe` annotation:
```kotlin
@WorkerSafe
external fun foo(): Int

worker {
  foo()
}
```

### `master` Intrinsic
`master` intrinsic is a companion of the `worker` intrinsic. It transfers
execution back to the main thread:
```kotlin
fun <T> master(block: Window.() -> T): Worker<T> = error("intrinsic")
```
Instead of putting its argument to a separate JavaScript file, the compiler puts
it to the main one.

With this intrinsic, one can write
```kotlin
val hello = "Hello"
worker {
  val message = "$hello World!"
  master {
    window.alert(message)
  }
}
```
and the program will show an alert. The compiler does not check functions used
in `master`'s block.

Initially, workers will not be able to create other workers. So,
`master { worker { } }` workaround can be used.

### Transferable Data Types
Again, `postMessage` can transfer not every data type.
Initially, a prototype will support only `@Serializable` classes, primitives,
including strings and arrays, and collections of these types.
Capturing any other type into the colored function will result in a compilation
error.

## Compilation Model
After inlining, the compiler colors call graph, including virtual functions.
Because of the closed world model, all overrides of the function are reachable
and can be checked for forbidden APIs usage.
Then it copies all the code required for the worker in a separate JavaScript
file.

The `worker`'s block is transformed:
1. All `return`s are replaced with `postMessage` calls.
2. All captured variable accesses are replaced with message content accesses.
3.  The block is wrapped in a try block and all caught exceptions are sent to the main thread.   
4. The block itself is placed inside an event listener.
5. Event listener for `error` event is added, which always throws
`IllegalStateException`.

Since all user-defined exceptions are passed via messages, `error` event means,
that the compiler has generated invalid code.

In the main script
1. `worker` intrinsic call is replaced with `Worker` constructor.
2. `then` and `catch` calls are replaced with event listeners.
3. Captured variables are placed into the message.

For example (all example are just illustrations of the idea, in reality, it is
likely, that variables will be serialized and deserialized, at least, on the
prototype stage):
```kotlin
worker {
  if (foo == bar) return@worker "Equal"
  return@worker "Not equal"
}.then { println (it)}.catch { throw it }
```
is transformed into
```js
// worker_0.js
addEventListener("message", ($message) => {
  const { data: { $kind, $captured } } = $message
  if ($kind == "START") {
    if ($captured.foo == $data.$captured.bar) {
      postMessage({
        $kind: "RETURN",
        $result: "Equal",
        $captured: {foo: $captured.foo, bar: $captured.bar}
      })
      return
    }
    postMessage({
      $kind: "RETURN",
      $result: "Not equal",
      $captured: {foo: $captured.foo, bar: $captured.bar}
    })
    return
  }
}

// main.js
const $worker_0 = Worker("worker_0.js")
$worker_0.addEventListener("message", ($message) => {
  const { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "RETURN") {
    foo = $captured.foo
    bar = $captured.bar
    println($result)
  } else if ($kind == "THROW") {
    foo = $captured.foo
    bar = $captured.bar
    throw $error
  }
})
$worker_0.addEventListener("error", (err) => {
  throw IllegalStateException(
    "Internal worker error: " + err.message +
    " at " + err.filename + ":" + err.lineno
  )
})
$worker_0.postMessage({
  $kind: "START",
  $captured: {foo: foo, bar: bar}
})
```
I will omit `error`'s event listener in later examples, since its content is
always the same.

### Captured Variables
You might have noticed that captured variables are sent back to the main
script. This is because updates to them should be visible in the main script.

For example:
```kotlin
val result = mutableListOf<String>()
worker {
  result.add("OK")
}.then {
  println(result[0])
}
```
is transformed into
```js
// worker_0.js
addEventListener("message", ($message) => {
  const { data: { $kind, $captured } } = $message
  if ($kind == "START") {
    $captured.result += "OK"
    postMessage({
      $kind: "RETURN",
      $result: Unit,
      $captured: {result: $captured.result}
    })
    return
  }
}

// main.js
var result = //...
const $worker_0 = Worker("worker_0.js")
$worker_0.addEventListener("message", ($message) => {
  const { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "RETURN") {
    result = $captured.result
    println(result[0])
  }
})
$worker_0.postMessage({
  $kind: "START",
  $captured: {result: result}
})
```
This, of course, can lead to data races if multiple workers are involved.

### Exceptions
If the worker throws an exception, the exception is passed as message as well.
```kotlin
worker {
  throw IllegalStateException("Boo")
}.catch {
  throw it
}
```
becomes
```js
// worker_0.js
addEventListener("message", ($message) => {
  const { data: { $kind, $captured } } = $message
  if ($kind == "START") {
    try {
      throw IllegalStateException("Boo")
    } catch (e) {
      postMessage({
        $kind: "THROW",
        $exception: e
      })
    }
  }
}

// main.js
const $worker_0 = Worker("worker_0.js")
$worker_0.addEventListener("message", ($message) => {
  const { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "THROW") {
    throw $exception
  }
})
$worker_0.postMessage({
  $kind: "START",
  $captured: {}
})
```

### `master` calls
If the worker contains `master` call, its content goes to main JavaScript file.
For example:
```kotlin
val o = "O"
var result = ""
worker {
  val k = "K"
  master {
    result = o + k
  }
}.then {
  println(result)
}
```
becomes:
```js
// worker_0.js
addEventListener("message", ($message) => {
  const { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "START") {
    const k = "K"
    var { o, result } = $captured
    postMessage({
      $kind: "MASTER_0_START",
      $captured: {o: o, k: k}
    })
  } else if ($kind == "MASTER_0_RETURN") {
    postMessage({
      $kind: "RETURN",
      $result: $result,
      $captured: {o: $captured.o, result: $captured.result}
    })
  } else if ($kind == "MASTER_0_THROW") {
    postMessage({
      $kind: "THROW",
      $exception: $exception,
      $captured: {o: $captured.o, result: $captured.result}
    })
  }
}

// main.js
var o = "O"
var result = ""
const $worker_0 = Worker("worker_0.js")
$worker_0.addEventListener("message", ($message) => {
  const { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "RETURN") {
    o = $captured.o
    result = $captured.result
  } else if ($kind == "MASTER_0_START") {
    var { o, k, result } = $captured
    result = o + k
    worker_0.postMessage({
      $kind = "MASTER_0_RETURN",
      $result = Unit,
      $captured = {o: o, k: k, result: result}
    })
  }
})
$worker_0.postMessage({
  $kind: "START",
  $captured: {o: o, result: result}
})
```

These examples show, which fields present in `data` object:
1. `$kind` specifies the state in this worker state-machine
2. `$result` contains return value of `worker` or `master` intrinsic,
if the intrinsic finished its execution.
3. `$exception` contains the exception, thrown in `worker` or `master` block
4. `$captured` is a map, containing all captured variables.

Where `$kind` contains not only the kind of the message, but also a unique
identifier in case of `master`.

The compiler might optimize away `$result` and `$exception` in the `worker`
file, if there is no `master` call in the worker-colored part of the
call-graph.

In addition, the last example shows, that exceptions propagate from `master`
block to the `worker` one and back to the main thread. More on that in
"Error Handling" section.

### Passing Lambda Objects to `then` and `catch`
`then` and `catch` intrinsics can accept variables, not only blocks. These
functional objects are called after updating captured variables. For example
```kotlin
val c: (String) -> Unit = {
  println(it)
}

val o = "O"
worker {
  o + "K"
}.then(c)
```
becomes

```js
// main.js
// Worker creation and `postMessage` are omitted
$worker_0.addEventListener("message", ($message) => {
  const { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "RETURN") {
    o = $captured.o
    c($result)
  }
})
```

In prototype stage, the compiler is likely to generate blocks, passed to `then`
and `catch` as separate lambda objects and call them inside the event listener,
instead of inlining them.

## Error Handling
I have already touched upon error handling in the previous section, so this
section elaborates on the topic.

### Exception in Worker Block
I have said, that every exception in worker block is passed to the main thread
via `postMessage`:
```kotlin
worker {
  throw IllegalStateException("Boo")
}
```
becomes
```js
// worker_0.js
addEventListener("message", ($message) => {
  const { data: { $kind, $captured } } = $message
  if ($kind == "START") {
    try {
      throw IllegalStateException("Boo")
    } catch(e) {
      postMessage({
        $kind: "THROW",
        $exception: e
      })
    }
  }
}
```

but this is not the whole story.

### Exception in Worker-Colored Function
In addition to exceptions, thrown in the `worker` block, we have exceptions,
thrown in functions, called in the `worker` block. To pass the exception to the
main thread, the compiler wraps the `worker` block in `try` block, passing
everything it caught to `postMessage`:
```kotlin
worker {
  foo()
}
```
becomes
```js

// worker_0.js
addEventListener("message", ($message) => {
  const { data: { $kind, $captured } } = $message
  if ($kind == "START") {
    try {
      var $result = foo()
    } catch (e) {
      postMessage({
        $kind: "THROW",
        $exception: e
      })
    }
    postMessage({
      $kind: "RETURN",
      $result: $result
    })
  }
}
```

### Exception in Master Block and Function
The same happens when `worker` uses `master` intrinsic - the compiler wraps
the `master` block is `try` and any caught exception is sent to the worker.

## Coroutines
We cannot simply transfer continuation object to the worker, since it holds a
link to its suspend function, and we cannot transfer functions. So, if we want
to support coroutines, we need to transfer `COROUTINE_SUSPENDED` and value to
resume the worker with, and inside the worker resume _correct_ continuation,
not the `worker` block's one.

The example shows the idea
```kotlin
var c: Continuation<Any?>? = null

suspend fun foo() {}

suspend fun returnsInt(): Int = suspendCoroutine {
    saveWorkerContinuation(it) {
        c = it
    }
}

suspend fun test() {
    suspendWorker {
        foo()
        returnsInt()
    }.then {
        println(it)
    }
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(Continuation(EmptyCoroutineContext) {})
}

fun main() {
    builder {
        test()
    }
    c?.resume(42)
}
```

We cannot capture continuation objects, unless they are used in color-changing
intrinsics `saveWorkerContinuation` and `saveMasterContinuation`.

The compiler generates the following for the worker script:
```js
// worker_0.
var c = null;

var $savedContinuation = null;

function returnsInt($completion) {
  c = $completion;
  $savedContinuation = $completion;
  postMessage({$kind: "SAVE_CONTINUATION"})
  return COROUTINE_SUSPENDED;
}

var $continuation = null;

addEventListener("message", ($message) => {
  var { data: { $kind, $result, $exception, $captured } } = $message
  if ($kind == "RESUME") {
    // 1
    if ($continuation == null) {
      $continuation = createContinuation(EmptyContinuation)
    } else if ($exception != undefined) {
      if ($savedContinuation != $continuaion) {
        c.resumeWithException($exception)
        return
      }
    } else {
      if ($savedContinuation != $continuaion) {
        c.resume($result)
        return
      }
    }
    // 2
    while(true) {
      try {
        if ($continuation.$label == 0) {
          $continuation.$label++
          $result = foo()
          if ($result == COROUTINE_SUSPENDED) {
            postMessage({$kind: "SUSPEND"})
            break;
          }
        } else if ($continuation.$label == 1) {
          $continuation.$label++
          $result = returnsInt($continuation)
          if ($result == COROUTINE_SUSPENDED) {
            postMessage({$kind: "SUSPEND"})
            break;
          }
          postMessage({
            $kind: "RETURN",
            $result: $result
          })
          break;
        } else {
          throw new IllegalStateException("Unreachable")
        }
      } catch (e) {
        postMessage({
          $kind: "THROW",
          $exception: e
        })
        break;
      }
    }
  }
})
```

as you can see, `START` signal is replaced with `RESUME`. Additionally,
`SUSPEND` signal it sent when worker suspends. Since `master`
intrinsic turns worker and main threads' code into state-machines, coroutines
simply extend the state-machines with their owns (`// 2`).

In `// 1` we create a continuation object to pass it as completion to suspend
functions, and check, whether we have suspended. If so, resume saved
continuation, unless saved continuation is `worker` block's continuation.

We also send signal to the main thread to save worker continuation via
`SAVE_CONTINUATION` signal. More on that later.

Here is the main script:
```js
var c = null;

function test($completion) {
  const $worker_0 = Worker("worker_0.js")
  var $continuation = // ...
  $worker_0.addEventListener("message", ($message) => {
    const { data: { $kind, $result, $exception, $captured } } = $message
    if ($kind == "THROW") {
      throw $exception
    } else if ($kind == "RETURN") {
      println($result)
    } else if ($kind == "SUSPEND") {
      $continuation.suspend()
    } else if ($kind == "SAVE_CONTINUATION") {
      c = wrapWorkerContinuation($worker_0)
    }
  })
  $worker_0.postMessage({$kind: "RESUME"})
}

// The rest of the script is not changed and omitted
```
where `$continuation.suspend()` simply tells `test` to return
`COROUTINE_SUSPENDED`.

### `suspendWorker` and `suspendMaster` Intrinsic
Unlike `worker` and `master` intrinsics, their suspend counterparts accept
suspend lambdas:
```kotlin
@ImplicitCodeColoring(forbidsPackages = ["org.w3c.dom.**"])
suspend fun <T> suspendWorker(block: suspend WorkerGlobalScope.() -> T): Worker<T> = error("intrinsic")

suspend fun <T> suspendMaster(block: suspend Window.() -> T): Worker<T> = error("intrinsic")
```
allowing them to call suspend functions.

### `saveWorkerContinuation` and `saveMasterContinuation` Intrinsics
`saveWorkerContinuation` has several purposes
1. it saves current continuation
2. it notifies the main thread, that lambda of `saveWorkerContinuation` should
be executed
3. it changes the color of its lambda back to worker-unsafe, allowing one to
   save continuation object in, for example, collection. Remember, we cannot
   transfer continuation objects from one color to another.
   
Saved continuation is later used to resume coroutines on `RESUME` signal. 
   
It is defined as
```kotlin
fun <T, R> saveWorkerContinuation(c: Continuation<T>, block: (Continuation<T>) -> R): R = error("intrinsic")
```

`saveMasterContinuation` is the twin of `saveWorkerContinuation`, performing
the same tasks, but when `master` intrinsic is used.

### `wrapWorkerContinuation` and `wrapMasterContinuation` Functions
These internal functions simply wrap a worker object and then send it a `RESUME`
signal in their `invokeSuspend` method, thus, bridging the gap between main and
worker threads from the caller side, telling the worker or the master to resume
coroutines.

## Open Questions
`then` and `catch` look ugly to me. We cannot, however, simply assume, that
we should wait for the worker to finish its executions - it beats the whole
purpose of web workers.

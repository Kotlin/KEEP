# Design Notes: Code Coloring

* **Type**: Design notes
* **Author**: Ilmir Usmanov
* **Contributors**: Roman Elizarov, Anton Banykh, Mikhail Zarechenskii,
  Sergey Rostov, Andrey Breslav, Zalim Bashorov, Mikhail Belyaev
* **Status**: Under consideration
* **Discussion and feedback**: TBD

While discussing new features, we, as Kotlin Language Research Team, are
constantly running into cases when the context of one block of code is different 
from the context of another. Each this discussion ends with two words: 
"code coloring".

So, it is time to sit down and write about what code coloring is, which cases it 
covers and which issues it brings.

## Use Cases
The first case that comes to mind when we are talking about different 'colors' 
of pieces of code, for example, functions, is <b>coroutines</b>. Since `suspend` 
functions and lambdas accept a `continuation` parameter, they cannot be called 
from ordinary code. Thus, we have two distinct 'worlds': ordinary and 
suspending. For coroutines, we use a modifier `suspend` to 'color' one kind of 
functions. Other languages use the `async` modifier just for the same task. 
Alternatively, there can be no modifier at all: all functions are suspending. 
Examples: Go, Raku, Java's Loom Project.

Another example is, of course, <b>JetPack Compose</b>. There are also two kinds 
of functions, and one of them is marked with `@Composable` annotation. Thus, one 
can view them as two distinct worlds, just like coroutines. Every composable 
function accepts an additional parameter, also like `suspend` function. 
Alternatives from other languages are rare, but they exist. Coeffects from 
functional languages are one of them. The other one is the Jai programming 
language, where every function accepts an additional parameter with things like 
a logger or memory allocator.

On the topic of additional parameters, upcoming <b>multiple receivers</b> 
prototype can be 
viewed as an example of code coloring as well. If a user marks a function with 
the `with<Type>` modifier (we might change the modifier to annotation-like 
`@with` or use a different word), the `Type` will be treated as a receiver of the 
function. To call it, `Type` should be `this` is the calling context. They are 
a replacement for the beforementioned coeffects as well as typeclasses.

All previous examples added additional parameters, some of them changed the code 
(coroutines generate a state-machine), but they did not change the execution 
environment. Let us have a look at examples (most of them are from other 
languages, in an early prototype stage, or merely speculative, as what-if 
examples).

The first such example is not from Kotlin, but Fortran and C/C++. I am talking 
about <b>OpenACC</b> technology. OpenACC was an insentive to code coloring as a 
whole. With OpenACC, a programmer can add a pragma to a block of code, and it 
will be automagically transformed into GPU code (either OpenCL or CUDA/PTX). For 
example,
```C
int sum(int* a, int* b, int *c, int n) {
  #pragma acc parallel loop
  for (int i = 0; i < n; i++) {
    c[i] = a[i] + b[i]
  }
 }
```
1. the loop will be parallelized and compiled into the GPU kernel
2. code to offload `a` and `b` and onload `c` will be generated before and after 
   the loop, respectively
3. the loop will be replaced with the kernel compilation and execution

All in all, the code to represent the same semantics in OpenCL or CUDA requires 
a code, which is multiple sizes of the example.

Note that CPU code will wait for the GPU to finish execution.

In this example, the loop is executed in a completely different execution 
context - on a separate device with a separate memory. Besides, the device has 
limited capabilities: for example, it does not support IO. So, the compiler 
should check that the code does not require these capabilities. The two worlds 
are, in this case, physically separated, yet the code is in one file.

The CPU<->GPU example also shows the aspect of code coloring, which was not 
present in the previous examples: data transfer. We can transfer only a limited 
subset of data: flat and consecutive bytes without pointers. That limits
possible types to arrays, structs, and a combination of these two.

Of course, different execution contexts should not be so different in their 
capabilities. Another example of varying execution contexts, which should be 
tracked in compile-time, would be my prototype of <b>web-workers</b> for 
Kotlin/JS. Unlike Kotlin/JVM threads, web-workers should be known at 
compile-time since the compiler should put a code block representing the worker 
into a separate javascript file and generate a call to a library function, 
passing the path to the file as a parameter. This is a limitation of the 
underlying platform that does not exist in JVM. In Kotlin/JVM, the `thread` 
function accepts any lambda, while in the prototype, the `worker` intrinsic only 
accepts inline-only lambdas. In addition to requiring compiler support, 
web-workers cannot have access to DOM-tree. Thus, they are like OpenACC's GPU 
blocks - not every function can be called in them. Unlike OpenACC, however, we 
can pass ordinary Kotlin objects to a web-worker, as long as the object can be 
represented as a javascript object. In other words, it should be serializable. 
Since we can use almost anything from a web-worker, there is no intention to 
limit functions, which a user can use to those marked with an annotation. The 
coloring can be done by the compiler, and it can figure out that a function 
somewhere in a call graph uses prohibited API.

Running along the theme of generating several outputs from a single input file, 
we can allow <b>mixing code from different back-ends</b>, for example, calling 
Kotlin/JS functions from Kotlin/JVM code. Thus, both front-end and back-end code 
will be in one module. All the data transfer will be automagically generated by 
the compiler.

Of course, these are just examples with vaguely the same theme - we have one 
context, and now we want another, might be completely different in terms of 
capabilities or even physically separated from the main context. Other examples 
include
- Gradle scripts with configuration/run-time difference
- JS/wasm
- Inplace tests
- Compile-only code (metaprogramming)
- MPP
- Isolated threads
- Serverless
- Conditional compilation (ifdef etc.)
- Debug-only code (+asserts)
- RestrictSuspension

## Definition
With the examples out of the way, let us define what code coloring is exactly. 
It is an umbrella of issues techniques where there are two or more contexts in 
one file. The context can be either run-time or compile-time. There can be a 
context switch, but its existence is not required. Syntactically, the context 
switch can be represented as:
```kotlin
context {
}
```
existing examples of the construct are `async` and `launch` from coroutines or 
`init` blocks. The construct can define a context without a switch, like 
`unittest` from D programming language.

Code coloring should also require some sort of compiler support.

Since this is an umbrella, we are likely to split it into separate language 
features and deliver it feature by feature, each with an individual name and 
use-cases.

Now, let us analyze the umbrella by cutting it into, ideally, composable and 
digestible pieces.

## Explicitness
First, code coloring can be explicit, using a modifier or an annotation, or 
implicit, in which the compiler colors code for us. For example, coroutines and 
JetPack Compose fall into the first category, while web-workers are likely to be 
implicit. We have not yet decided on a rule, whether this case of code coloring 
should be explicit or implicit, but there are some guidelines, which we 
unintentionally followed:
1. If the color change changes function signature, it is explicit since we do 
   not want to break Java interop.
2. If the color change limits the function's usage, the coloring is also 
   explicit. For example, suspend functions cannot be used by ordinary ones.

Otherwise, I see no reason why the compiler cannot infer the color by usage. For 
example, in the web-workers example, the compiler can color call graph for us, 
reporting an error when, for example, DOM API is used. Another example is 
compile-time functions (also known as constexpr functions). The compiler can 
infer their constantness from usage like it is done in D programming language.

One can ask, "why not require everything to be explicit, since 'explicit is 
better than implicit, right?'". No, not in this case. Suppose several years down 
the line, we support several features, which depend on code coloring. So, we end 
up with functions, like
```kotlin
@Composable
@with<Context>
@WebWorker
@GPU
@Unittest
constexpr suspend fun foo() = TODO()
```
which is highly undesirable. Sure, everything is explicit, but at the cost of 
expressiveness and ease-of-use. After all, Kotlin ought to be pragmatic language.

### Data transfer
Some of the examples I covered used separate execution contexts — namely OpenACC 
and web-workers. In these cases, we need to transfer data between host and 
device, or main and worker threads. In the case of GPU, there is nothing we can 
do. The architecture dictates the shape of data we can transfer - a consecutive 
area of memory. I.e., arrays and value classes and a combination of them. In the 
case of web-workers, however, there is leeway. We can transfer everything we can 
serialize. Thus, arrays, lists, data and value classes of serializable classes. 
So, we need to disallow the capture of non-serializable types in worker lambdas. 
The same applies to other cases when we need to transfer data from one device to 
another, as in the backend-frontend example.

To summarize, if the data transfer is involved, we might require marking data 
types, so the compiler will check whether we can transfer the data at run-time 
or not.

### No Context Switch
If we just want to color code without mixing the colors at execution time, as 
in `unittest`, MPP, gradle scripts, etc., there is no context switching, no data 
transfer, and the compiler can color the code for us. The only thing that the 
user should provide is end-points, like calling a function in the `unittest` 
block will color the function. Let's take a look into gradle scripts with 
distinct configuration and run-time phases, for example. If we use run-time API 
in the configuration phase, the compiler should report an error. It knows that 
the function is colored as `configuration` since it is called in the 
`configuration` block. These blocks are end-points.

## Issues
While discussing data coloring, we run into several pain points, and it is yet 
unclear how to solve them.

### Exceptions
Coroutines taught us that it is easy to write erroneous yet correct from the 
compiler's point of view code when dealing with error handling.

For example, one can write
```kotlin
try {
  launch { ... }
} catch (e: Exception) { ... }
```
and not catch the exception. This is an open issue, and first, we need to fix it 
in this particular case, keeping in mind other cases, for example, web-workers.

### Sharing mutable state
Sometimes we might want to disallow sharing mutable state. For example, in 
compose, we do not want to allow
capturing mutable state, since the engine does not track the changes in the
mutable variables. Intended use is to use `by state`. Analogously, in coroutines
sharing mutable state leads to hard to debug errors due to the nature of
asyncronous programming.

### Composability
Not every pair of contexts is and should be composable. For example, while it 
makes sense to compose web-workers and coroutines, there is no sense in 
composing web-workers and JetPack Compose, despite the latter's name. This is 
still unclear to me how we can limit the composition. Allowing it is possible if 
we go the path of marker interfaces for contexts. Just extend the interfaces 
with your own one, and you are good to go. Additionally, composability might not 
be the issue since we should limit explicit code coloring and rely on the 
compiler analysis for most cases. So, the compiler will implicitly color any 
given function with as many colors as it needs.

So, the inability to limit the composability might be an issue here, not 
supporting it.

### Limiting capabilities
I am struggling to find a generic way to tell the compiler the capabilities of 
the context. For example, in the case of GPU, which is a very limited platform, 
we can list all possible API in one interface and declare the intrinsic as
```kotlin
@RestrictContext
interface GPU {
  // ...
}

inline fun gpu(block: GPU.() -> Unit) = error("Intrinsic")
```
and disallow any function unless it is marked somehow to run on a GPU, either 
using `GPU` as a receiver or with a modifier or annotation.

I took `@RestrictsSuspension` as an example, since there is no other example
in Kotlin yet. Ideally, a user should be able to extend supported API by either
extending the interface or, like in `@RestrictsSuspension`, by declaring new
functions. This might become tedious for huge API surface. So, the solution
might be to allow whole packages or even modules.

However, in web-workers, we should limit only a subset of API, for example, DOM 
access API. So, we do not want to replace context entirely, just to subtract 
from it. Thus, we might end up with something like
```kotlin
@ImplicitCodeColoring(forbiddenPackages = ["org.w3c.dom.**"])
interface Worker {
  // worker's methods
}

inline fun worker(block: Worker.() -> Unit): Unit = error("Intrinsic")
```
and teach the compiler to check whether the blacklisted APIs are used in the 
colored call graph.

I expanded the example of web-workers in a separate note.

Enhancing the context, i.e., adding the capabilities, is covered by multiple
receivers.

### Marking Explicit Colors
The marking of explicit context is not consistent. That is the issue. We have 
modifiers (`suspend`), annotations (`@Composable`), receivers (`SequenceScope` 
and multiple receivers). I incline to suggest to deprecate everything except 
receivers and rely on the multiple receivers feature to mark explicitly colored 
code. Doing so will also solve explicit colors' composability. For example, 
`suspend` and `@Composable` can be marker interfaces, and thus, to compose them, 
one needs to just extend them both. It might be a good idea to use a different 
word than `with`, though. Like, I don't know, `color` or `context`.
# Context receivers

* **Type**: Design proposal
* **Authors**: Roman Elizarov, Anastasia Shadrina
* **Contributors**: Denis Zharkov, Marat Akhin, Mikhail Belyaev, Ilya Gorbunov, Ilmir Usmanov, Simon Ogorodnik,
  Dmitriy Novozhilov, Mikhail Glukhikh
* **Status**: Proposed
* **Discussion**: [KEEP-259](https://github.com/Kotlin/KEEP/issues/259)
* **Prototype**: In Progress

## Abstract

This is a design proposal for support of context-dependent declarations in Kotlin. 
It covers a large variety of use cases and was previously known and requested under the name of
"multiple receivers", see [KT-10468](https://youtrack.jetbrains.com/issue/KT-10468).

We would appreciate hearing your feedback on this proposal in the [KEEP-259](https://github.com/Kotlin/KEEP/issues/259).

## Table of Contents

<!--- TOC -->

* [Introduction](#introduction)
  * [Context receivers and contextual declarations](#context-receivers-and-contextual-declarations)
  * [Goals](#goals)
* [Detailed design](#detailed-design)
  * [Contextual functions and property accessors](#contextual-functions-and-property-accessors)
  * [Functional types](#functional-types)
  * [Referencing specific receiver](#referencing-specific-receiver)
  * [Resolution algorithm](#resolution-algorithm)
  * [Backwards compatibility](#backwards-compatibility)
  * [JVM ABI and Java compatibility](#jvm-abi-and-java-compatibility)
* [Use cases](#use-cases)
* [Contexts and coding style](#contexts-and-coding-style)
  * [Overloading by the presence of context](#overloading-by-the-presence-of-context)
  * [Performing an action on an object](#performing-an-action-on-an-object)
  * [Providing additional parameters to an action](#providing-additional-parameters-to-an-action)
  * [Providing additional context for an action](#providing-additional-context-for-an-action)
  * [Kotlin builders](#kotlin-builders)
  * [Other Kotlin DSLs](#other-kotlin-dsls)
  * [Designing context types](#designing-context-types)
* [Similar features in other languages](#similar-features-in-other-languages)
  * [Scala given instances and using clauses](#scala-given-instances-and-using-clauses)
  * [Algebraic effects and coeffects](#algebraic-effects-and-coeffects)
* [Alternative approaches and design tradeoffs](#alternative-approaches-and-design-tradeoffs)
  * [Alternative syntax options](#alternative-syntax-options)
  * [Alternative keywords](#alternative-keywords)
  * [Parentheses vs angle brackets](#parentheses-vs-angle-brackets)
  * [Context keyword ambiguities](#context-keyword-ambiguities)
  * [Named context receivers](#named-context-receivers)
  * [Multiple receivers with decorators](#multiple-receivers-with-decorators)
* [Future work](#future-work)
  * [Reflection design](#reflection-design)
  * [Contextual delegated properties](#contextual-delegated-properties)
  * [Local contextual functions and properties](#local-contextual-functions-and-properties)
  * [Callable references to contextual functions](#callable-references-to-contextual-functions)
  * [Removing context receiver from the scope with DslMarker](#removing-context-receiver-from-the-scope-with-dslmarker)
  * [Scope properties](#scope-properties)
  * [Contextual classes and contextual constructors](#contextual-classes-and-contextual-constructors)
  * [Future decorators](#future-decorators)
  * [Unified context properties](#unified-context-properties)
* [Open issues and concerns](#open-issues-and-concerns)
  * [Context receivers abuse and scope pollution](#context-receivers-abuse-and-scope-pollution)
  * [Methods from Any in top-level functions](#methods-from-any-in-top-level-functions)

<!--- END -->

## Introduction

Consider an interface `Scope` that represents some context and another interface `Entity` that we want to define an
action on, so that this action is available only in a context that provides an instance of a `Scope`, without having to
explicitly pass the `Scope` around.

In Kotlin, you can define such a context-restricted declaration using a
[member extension function](https://kotlinlang.org/docs/extensions.html#declaring-extensions-as-members).
A member extension has two receivers: a dispatch receiver from the class and an extension receiver from the method's extension.

```kotlin
interface Entity

interface Scope { // Scope is a dispatch receiver
    fun Entity.doAction() { // Entity is an extension receiver for doAction
        ...
    }
}
```

> A real-life example of a `Scope` could be [`CoroutineScope`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-scope/),
> from the [`kotlinx.coroutines`](https://github.com/Kotlin/kotlinx.coroutines) library. You must provide a `CoroutineScope`
> to be able to launch new coroutines as a part of an action. A real-life example of an `Entity` could be a
> [`Flow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/index.html)
> with an operation to launch a coroutine that collects a flow.

When calling a `doAction` member extension function, its dispatch receiver `Scope` must be present in the caller's scope.
There is no dedicated syntax to explicitly specify a dispatch receiver for such a call. On the other hand, the extension
receiver `Entity` can and is usually specified explicitly using `entity.doAction()` qualified call syntax.
To specify a `Scope` dispatch receiver we can use a **scope function**, like
[with](https://kotlinlang.org/docs/scope-functions.html#with), [run](https://kotlinlang.org/docs/scope-functions.html#run),
or [apply](https://kotlinlang.org/docs/scope-functions.html#apply), to bring it into scope:

```kotlin
with(scope) {
    entity.doAction()
}
```

We say that an extension receiver defines the **object of an action**, while a dispatch receiver effectively serves as
an implicit parameter that must be present in the caller's scope but cannot be specified explicitly. Thus, a member
extension function can be called a context-dependent function, and a dispatch receiver represents the **context of an action**.

The context-oriented approach has many applications in the design of idiomatic Kotlin APIs (for example, see
["An introduction to context-oriented programming in Kotlin" by Alexander Nozik](https://proandroiddev.com/an-introduction-context-oriented-programming-in-kotlin-2e79d316b0a2))
and is a building block of a more generic [code coloring concept](https://github.com/Kotlin/KEEP/blob/master/notes/code-coloring.md).
However, a member extension is now the only way to define a context-dependent declaration, and this form has
multiple limitations that restrict its practical usefulness.

The key one is that a **member extension cannot be declared on a third-party class**. It limits the ability to decouple,
modularize and structure APIs in larger applications. The only way to introduce a context-dependent `Entity.doAction`
extension is to write it as a member of a `Scope`, which is not always appropriate from a modularity standpoint.

> For example, in the `kotlinx.coroutines` library, it would be inappropriate to declare a `Flow.launchFlow()` extension as
> a member of `CoroutineScope`, because `CoroutineScope` is a more general concept and its declaration shall not depend
> on a more specific concept like `Flow`.

Another limitation is that **a member extension is always the extension**. An extension function in Kotlin has an option
of being called with qualified syntax as in `entity.doAction()`. This is a stylistically appropriate syntax when an
action is performed on an entity. However, some functions don't operate on a specific entity and should not be declared
as such. There is no way to declare a top-level function to be called as `doAction()` that would require the presence of
a specific context in scope.

> Use cases for that come a lot. For example, it would be helpful to be able to define a `TransactionScope` and have
> syntax to declare transactional functions that have a requirement of being called only in a `TransactionScope`, but
> forbid an explicit `transaction.doSomething()` call, since they do not work **on** a transaction, but **in the context of**
> a transaction. 

The final limitation of providing context with a member extension is that **only one receiver can represent a context**.
It limits composability of various abstractions, as we cannot declare a function that must be called only within two
or more scopes present at the same time.

> For example, there might be a need to define a function that requires a `CoroutineScope` to be able to launch a
> coroutine and requires a `TransactionScope` at the same time.

### Context receivers and contextual declarations

This proposal introduces the syntax for defining context-dependent declarations with special **context receivers**.
This feature overcomes highlighted limitations and covers a variety of use cases.

The context here is not directly related to the action but is used by the action. It can provide additional operations,
configuration, or execution context. A good example of context would be `Comparator`, `CoroutineScope`, some kind of
`Transaction` or `LoggingContext` (see [Use cases](#use-cases) for details).
A simple **contextual function** is declared like this:

```kotlin
context(Scope)
fun Entity.doAction()
```

A top-level contextual function can also be declared:

```kotlin
context(Scope)
fun doAction()
```

Its key difference from the `Scope.doAction` extension is that it cannot be called with a qualified `scope.doAction()`
syntax, and it has no `this` reference inside its body, since it has no object on which it performs its action.
Moreover, there can be multiple context receivers. See [Detailed design](#detailed-design) section.

### Goals

* Remove all limitations of member extensions for writing contextual abstractions
    * Support top-level (non-member) contextual functions and properties
    * Support adding contextual function and properties to 3rd party context classes
    * Support multiple contexts
* Make blocks of code with multiple receivers representable in Kotlin's type system
* Separate the concepts of extension and dispatch receivers from the concept of context receivers
    * Context receivers should not change the meaning of unqualified `this` expression
    * Multiple contexts should not be ordered during resolution, resolution ambiguities shall be reported
* Design a scalable resolution algorithm with respect to the number of receivers
    * Call resolution should not be exponential in the number of context receivers

## Detailed design

A context requirement for a declaration is expressed by a new modifier with the `context` keyword 
followed by the list of context receiver types in parenthesis. The list can contain one or more comma-separated types
(a trailing comma is supported, too, for use in multi-line declarations).

```kotlin
context(A, B, C)
```

> As a matter of coding style, context receivers are defined after annotations and before other modifiers
> on a separate line.

The following types of declarations can be contextual:

* Functions (top-level, member, extensions functions are currently supported)
* Property getters and setters (of all these kinds, too)

The types listed as context receivers of a declaration are not allowed to repeat, and no pair
of them is allowed to have a subtype relation between them.

> This constraint comes from the greedy nature of the [Resolution algorithm](#resolution-algorithm) and absence
> of any way to explicitly pass context arguments into a call. 

### Contextual functions and property accessors

For functions and property accessors, context receivers are additional **context parameters** of those
declarations. They differ from regular parameters in that they are anonymous and are passed
implicitly just like receivers. 
In the body of the corresponding function or property accessor they bring the corresponding arguments 
into the body scope as implicit receivers for further calls.

Take a look at the following example.

```kotlin
context(Comparator<T>)
infix operator fun <T> T.compareTo(other: T) = compare(this, other)

context(Comparator<T>)
val <T> Pair<T, T>.max get() = if (first > second) first else second
```

* In the first declaration, `compare` is resolved to `Comparator.compare`, because `Comparator<T>` is a context receiver.
* In the second declaration, the expression `first > second` calls the previously defined operator function `compareTo`, because
  `Comparator<T>` is a context receiver and can be implicitly passed to `compareTo` as its context parameter.

If a function or a property accessor is a member of some class or interface and has context receivers, then its
overrides must have context receivers of the same types.

```kotlin
interface Canvas

interface Shape {
    context(Canvas)
    fun draw()
}

class Circle : Shape {
    context(Canvas)
    override fun draw() {
      ...
    }
}
```

> No widening of context types is allowed on override, context receivers are very similar to function 
> parameters in this respect.

### Functional types

The functional type of a contextual function can be denoted with the same modifier `context(...)`, which
should be present at the beginning of the functional type signature.

```kotlin
typealias ClickHandler = context(Button) (ClickEvent) -> Unit
```

In the type system, the functional type with context receivers (just as the functional type with an ordinary receiver)
is equivalent to the similar type having all context receiver types as the types of additional arguments. The resulting
signature of the functional type replicates the textual order in which every argument appears. It means:

* The type `context(C1, C2) R.(P1, P2) -> T` will actually turn into an instance of the type constructor
  `Function5<C1, C2, R, P1, P2, T>`.

* Such assignments are valid:
  ```kotlin
  fun main() {
    var g: context(Context) Receiver.(Param) -> Unit
    g = ::foo         // OK
    g = ::bar         // OK
    g = Receiver::baz // OK
  }
  
  fun foo(context: Context, receiver: Receiver, p: Param) {}

  context(Context)
  fun bar(receiver: Receiver, p: Param) {}

  context(Context)
  fun Receiver.baz(p: Param) {}
  ```

### Referencing specific receiver

Context receivers can never be referenced using a plain `this` expression and never change the meaning of `this`.
However, this proposal introduces another option to reference a receiver of any type, including context one, via the
[labeled `this` expression](https://kotlinlang.org/docs/reference/grammar.html#THIS_AT).
For every receiver in the scope, the compiler generates the label from the name of its type with the following rules:

* If the receiver type is parenthesized, parentheses are omitted
* If the receiver type is nullable, the question mark is omitted
* If the receiver type has type arguments or type parameters, they are omitted
* If the receiver type is a type alias or class, the label is generated from its short name without type parameters
* If the receiver type is functional, no label is generated

```kotlin
context(Logger, Storage<User>)
fun userInfo(name: String): Storage<User>.Info {
    this@Logger.info("Retrieving info about $name")
    return this@Storage.info(name)
}
```

If multiple receivers have the same generated label, none of them can be referenced with the qualified `this`.
In cases where the label cannot be generated or referenced, a workaround is to use a type alias.

```kotlin
typealias IterableClass<C, T> = (C) -> Iterator<T>

context(IterableClass<C, T>)
operator fun <C, T> C.iterator(): Iterator<T> = this@IterableClass.invoke(this)
```

Using labeled `this` may come in handy even without context receivers. If multiple receivers in a nested scope can be 
addressed via plain `this`, the use of it becomes ambiguous and decreases a readability. Using a bare type name rather 
than a function name for a label looks more natural since the object type describes the object better than the scope it 
belongs to ([KT-21387](https://youtrack.jetbrains.com/issue/KT-21387)).

```kotlin
fun List<Int>.decimateEveryEvenThird() = buildSequence {
    var counter = 1
    for (e in this@List) {
        if (e % 2 == 0 && counter % 3 == 0) {
            yield(e)
        }
        counter += 1
    }
}
```

### Resolution algorithm

The current Kotlin call resolution algorithm is documented in the [Kotlin specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution). Contextual receivers introduce a number of changes.

For the purpose of call resolution, context receivers in the scope are considered with all the other implicit receivers in scope. However, they don't have a total hierarchy like other implicit receivers that come from nested syntactic structures.
Instead, they form non-overlapping groups according to the affected scope. 
There is no actual order inside groups, but groups themselves are sorted in the scope order: 
from the innermost to the outermost.

When selecting a candidate of the call, the context parameters of the candidates are initially ignored. Only extension
and dispatch receivers participate in the algorithm of candidate selection.

> This and other features explained below ensure that the algorithm is not exponential 
> with respect to the number of context receivers in the function declaration.

When looking for candidates, the whole group of context receivers is processed.
Multiple applicable candidates in the same group result in ambiguity. If a suitable candidate is found in some group, name resolution ends. In the initially proposed
implementation, we only consider (non-local) contextual function and properties, so there could be only one group of
contexts in a scope.

```kotlin
class A {
    context(B1, B2)
    fun C.f() { 
       // A group: [B1, B2] 
       with (d) { // Add receiver to the scope
           // Resolution order: d -> C -> A -> [B1, B2] -> imports
       }  
    }
}
```

When the candidate target of a call has context requirements itself, those requirements are resolved greedily.
For each context parameter of a candidate, the first implicit receiver with a suitable type is considered to be used as 
a context argument of the corresponding call. If a type of a declared context parameter of a candidate uses a generic
type whose value is not determined yet, then the corresponding type constraints are added to the 
_constraint system_ of this candidate call. If solving this system fails, then the candidate is considered to be inapplicable, 
without trying to substitute different implicit receivers available in the context.

Candidates with context requirements are considered to be _more specific_ for the purpose of call resolution
than the same candidates without context requirements.

> Currently, we don't define a specificity relation between candidates having different sets of context parameters
> for the lack of compelling use cases for doing so. It can be introduced later in a backwards-compatible way
> if needed.

Further details of this algorithm will be presented as a part of the Kotlin specification revision.

### Backwards compatibility

The `context(Ctx)` syntax for a function modifier may change the meaning of a previously valid code if we add support
for local contextual functions in the future. For example:

```kotlin
open class Ctx {
    companion object : Ctx
}

fun context(ctx: Ctx) { ... }

fun foo() {
    context(Ctx) // Invokes function "context" with "Ctx" companion object
    fun bar() { ... } // Local function bar
}
```

It is not a concern in the initially proposed implementation, which does not support local contextual functions and
properties. To support them in the future we'll have to deprecate such ambiguous uses of user-defined `context` functions.
However, we could not find any real Kotlin code that will be affected, so a potential impact of such deprecation is
extremely low.

> We don't need to turn `context` into a hard keyword and forbid using it as a function or property name. It will be
> a soft-keyword and remain allowed for use as an identifier.

### JVM ABI and Java compatibility

In the JVM, the contextual function is just an ordinary method with an expanded parameter list. Parameters have textual
order according to the functional type signature: context receivers go right after the dispatch receiver (if present)
and before the extension receiver (if present). For the contextual property, the same applies to its getter and setter.

Assume the following top-level contextual function signature:

```kotlin
context(C1, C2)
fun R.f(p1: P1, p2: P2)
```

After compilation, it will turn into the following JVM method signature:

```java
public static final void f(C1 c1, C2 c2, R r, P1 p1, P2 p2)
```

And you can call it from Java as a regular static member:

```java
public class TestF {
    public static void test(C1 c1, C2 c2, R r, P1 p1, P2 p2) {
        MainKt.f(c1, c2, r, p1, p2);
    }
}
```

## Use cases

Context receivers can be useful in many domains and applications. 
An assortment of use cases is presented below.

> Most of the use cases are from the [original discussion](https://youtrack.jetbrains.com/issue/KT-10468).

* Injecting loggers and other contextual information into functions and classes
  ```kotlin
  interface LoggingContext {
      val log: Logger // this context provides reference to logger  
  }
  
  context(LoggingContext)
  fun performSomeBusinessOperation(withParams: Params) {
      log.info("Operation has started")
  }
  ```
  
* Calculating density-independent pixels in Android
  ```kotlin
  context(View)
  val Float.dp get() = this * resources.displayMetrics.density
  
  context(View)
  val Int.dp get() = this.toFloat().dp
  ```
  
* Creating JSONs with [JSONObject](https://www.javadoc.io/doc/com.google.code.gson/gson/2.8.5/com/google/gson/JsonObject.html)
  and custom DSL
  
  ```kotlin
  fun json(build: JSONObject.() -> Unit) = JSONObject().apply { build() }

  context(JSONObject)
  infix fun String.by(build: JSONObject.() -> Unit) = put(this, JSONObject().build())

  context(JSONObject)
  infix fun String.by(value: Any) = put(this, value)

  fun main() {
      val json = json {
          "name" by "Kotlin"
          "age" by 10
          "creator" by {
              "name" by "JetBrains"
              "age" by "21"
          }
      }
  }
  ```
  
* Working with mathematical abstractions
  ```kotlin
  context(Monoid<T>)
  fun <T> List<T>.sum(): T = fold(unit) { acc, e -> acc.combine(e) }
  ```
  
* Using structured concurrency
  ```kotlin
  context(CoroutineScope)
  fun <T> Flow<T>.launchFlow() {
      launch { collect() }  
  }
  ```
  
* Declaring transactional functions
  ```kotlin
  context(Transaction)
  fun updateUserSession() {
      val session = loadSession()
      session.lastAccess = now()
      storeSession(session)
  }
  ```
  
* Conveniently scoping automatically closeable resources (flexible “try-with-resources”)
  ```kotlin
  interface AutoCloseScope {
      fun defer(closeBlock: () -> Unit)
  }

  context(AutoCloseScope)
  fun File.open(): InputStream

  fun withAutoClose(block: context(AutoCloseScope) () -> Unit) {
      val scope = AutoCloseScopeImpl() // Not shown here
      try {
          with(scope) { block() }
      } finally {
          scope.close()
      }   
  }

  // usage
  withAutoClose {
      val input = File("input.txt").open()
      val config = File("config.txt").open()
      // Work
      // All files are closed at the end
  }
  ```

## Contexts and coding style

Addition of context receivers to the language creates a new dimension in the coding style considerations — in what cases
it is appropriate to use context receivers and what kind of classes or interfaces are best suited for that role.

From the coding style and naming perspective, members and extensions of context receivers shall be treated very much
like top-level declarations. In fact, context receivers can be viewed as a context-dependent importing mechanism with
the caveat that they effectively import a number of declarations with a wildcard import `*`.
Compare the following declaration of a `doSomething` function with an import:

```kotlin
import mypackage.*

fun doSomething() { ... }
```

And a similar declaration using a context receiver:

```kotlin
context(MyContext)
fun doSomething() { ... }
```

In both cases, the code in the `doSomething` body can refer to the declarations inside either `mypackage` or
inside `MyContext` using their short unqualified names. The names of declarations in the corresponding context,
just like the names of the top-level declarations, must be unambiguous to the maximal extent, to ensure readability
of the resulting code.

In practice, it means that very few existing classes or interfaces in a typical Kotlin codebase would fit a role of
a context receiver. A typical class is designed with `instance.member` call-site usage in mind, as in `user.name`.  
On the other hand, top-level declarations are designed to be used by their short name without a qualifier.

### Overloading by the presence of context

It is tempting to give functions in contextual receivers the same names as the names of existing top-level functions, 
so that their behavior changes in the specific context. For example, the Kotlin standard library has a top-level
[`println`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/println.html) function, so you can write:

```kotlin
fun hello() {
    println("Hello")
}
```

The `println` function is also declared in the [`java.io.PrintWriter`](https://docs.oracle.com/javase/8/docs/api/java/io/PrintWriter.html) class.
By adding `context(PrintWriter)` to the `hello` function declaration, you can change it to 
start printing to `PrintWriter` without otherwise changing a single line of code inside it:

```kotlin
context(PrintWriter)
fun hello() {
    println("Hello")
}
```

It might be a neat trick for a small application, but it is a very error-prone practice for a larger code. It becomes 
all too easy to call other functions from `hello` which also call `println` themselves and to forget about `context(PrintWriter)`:

```kotlin
context(PrintWriter)
fun hello() {
    println("Hello")
    world()
}

// forgot the context
fun world() {
    println("World")
}
```

The above code still compiles (because there is a top-level `println` function) but it does not do what 
you likely intended it to do. **Don't do this**. 

A rule of thumb is that the names of functions available on the context receivers should be distinct from the 
functions available at the top-level in your application.

> For the same reason, it is a bad idea to add language support for any kind of default values for contextual receivers,
as it will make a similar mistake (of forgetting to pass the context along) undetectable during compilation.

### Performing an action on an object

When writing code that performs an action on an object it is customary in Kotlin to refer to their members
and extensions by their short name. It is possible to be explicit using `this.`, but it is not recommended in Kotlin
to write in cases when there are no ambiguities. For example, this is how members and extensions are implemented in
a typical class:

```kotlin
class User(
    val name: String,
    var updateTime: Instant,
) {
    fun updateNow() {
        updateTime = now() // Notice that we don't write this.updateTime here
    }
}
```

To ensure readability it is important to write code so that there is always a single object on which the action
is performed upon and pass all additional information in explicitly named parameters. This practice is enforced by the
Kotlin syntax that allows the definition of only a single extension receiver in a function declaration. So, when writing
extensions that perform an action on an object **don't do this**:

```kotlin
context(User)
fun updateNow() {
    updateTime = now() // BAD STYLE: Don't use a context receiver here
}
```

**Do this**:

```kotlin
fun User.updateNow() {
    updateTime = now() // GOOD STYLE: Action is performed on an extension receiver
}
```

Even though both declarations are similar in many aspects, and their bodies look similar, the declaration of
`fun User.updateNow()` is explicit about the intent to perform an action on the `User` object.

### Providing additional parameters to an action

When an action takes additional parameters that specify what kind of operation shall happen, the normal Kotlin parameters
shall be used, for example, **do this**:

```kotlin
fun User.recordLastLogin(address: InetAddress) {
    lastLoginAddress = address // GOOD STYLE: passing parameter explicitly
}
```

Don't use context parameters as a way to implicitly pass additional parameters, even though it is technically possible.
**Don't do this**:

```kotlin
context(InetAddress)
fun User.recordLastLogin() {
    lastLoginAddress = this@InetAddress // BAD STYLE: Don't use context as an implict parameter
}
```

### Providing additional context for an action

Context receivers shall be used to provide additional, ubiquitous context for actions. As a litmus test, ask yourself
if that information might have been provided via a global top-level scope in a smaller application
with a simpler architecture. If the answer is yes, then it might be a good idea to provide it via a context receiver.
For example, it is a good idea to inject the source of the current time into various time-dependent functions,
so you might declare a context that provides current time and pass it to time-dependent functions as a context parameter:

```kotlin
interface TimeSource {
    fun now(): Instant
}

context(TimeSource)
fun updateNow() {
    updateTime = now() // GOOD STYLE: Use time source from the context
} 
```

### Kotlin builders

The following builder pattern is often used in idiomatic Kotlin code:

```kotlin
fun someObject(builder: SomeObjectBuilder.() -> Unit) =
    SomeObjectBuilder().run {
        builder()
        build()
    }

// Later in code
someObject {
    property = value
    ...
}
```

This builder pattern uses a functional type with an extension receiver `SomeObjectBuilder.() -> Unit` for good and shall
continue doing so. Conceptually, the code inside `someObject { ... }` block performs an action upon the `SomeObjectBuilder` instance
and using an extension receiver for this is in style. We do not recommend using context receivers for such simple builders.

However, context receivers make it possible to define _contextual operators_ &mdash; operators that are available 
only in the context of the corresponding builder, as shown in "Creating JSONs" example in the [Use cases](#use-cases) section.
This is a legitimate use-case of context receivers for builders. 

### Other Kotlin DSLs

Other kinds of Kotlin DSLs, beyond builders, shall reconsider their use of extension receivers. Sometimes a Kotlin DSL
is designed to inject a context into a block. For example, in current Kotlin code bases you might find declarations like

```kotlin
fun withVirtualTimeSource(block: TimeSource.() -> Unit) { ... }

// Later in code
withVirtualTimeSource {
    val time = now() // provides virtual time in this block
}
```

This is a good Kotlin style now, and there is no need to reconsider it for stable APIs. However, new Kotlin code shall
be designed with context receivers in mind:

```kotlin
// GOOD STYLE: Better for newly designed code
fun withVirtualTimeSource(block: context(TimeSource) () -> Unit) { ... }

// Later in code
withVirtualTimeSource {
    val time = now() // Provides virtual time in this block
}
```

This is not only stylistically better, as it clearly shows an intent to provide contextual information. 
It is also better in a larger codebase, because the contextual lambda in `withVirtualTimeSource { ... }` does not
change the meaning of `this` reference, for example:

```kotlin
class Subject { 
    fun doSomething() {
        withVirtualTimeSource {
            val subject = this // `this` still refers to Subject instance
        }
    }
}
```

### Designing context types

You'd usually need to design new types from scratch to use them as context parameters due to the unique requirement on
the naming of their members and extensions. They must be designed with context in mind. Use the same naming guidelines
as if you are designing top-level declarations. A typical business-object would be usually inappropriate as a context receiver.

Prefer interfaces to classes for context receivers. This would help you later on as your application grows — instead
of carrying a number of different contexts in your top-level functions as in:

```kotlin
context(TimeSource, TransactionContext, LoggingContext, ...) // BAD: Too many separate contexts
fun doSomeTopLevelOperation() { ... }
```

You'll have an option of combining multiple contexts into a single meaningfully named interface:

```kotlin
interface TopLevelContext : TimeSource, TransactionContext, LoggingContext, ...

context(TopLevelContext) // GOOD: A combined context
fun doSomeTopLevelOperation() { ... }
```

## Similar features in other languages

Contextual abstractions exist in other languages.

### Scala given instances and using clauses

Scala 2 introduced the first implementation of contextual abstractions with "impicits": implicit objects, definitions,
classes, and parameters. In Scala 3, "implicits" were redesigned and turned into given instances, using clauses and
extension methods. Using clauses have a lot in common with context receivers.

`using` always works together with some `given`. Given instance defines a value of a certain type, which the compiler can
further use to generate an implicit argument for calls with a context parameter of this type.
Meanwhile, a context parameter is defined with a `using` clause.

```scala
// Can be called only in the scope with the given of Ordering[Person] type
def printPersons(s: Seq[Person])(using ord: Ordering[Person]) = ...
```

Context parameters are quite close to the context receivers we're describing in this proposal — they also consume a context
from a caller scope. So the example above can be easily translated into Kotlin, preserving its semantics:

```kotlin
// Can be called only in the scope with the context receiver of Comparator<Person> type
context(Comparator<Person>)
fun printPersons(s: Sequence<Person>) = TODO()
```

### Algebraic effects and coeffects

Algebraic effects is a mechanism that is being implemented in some research languages such as
[Eff](https://www.eff-lang.org/) (with untyped effects) and [Koka](https://github.com/koka-lang/koka) (with typed effects)
to model various effects that a function can have on its environment. For pure functional languages, they provide a
unified abstraction for things like reading and writing state, throwing exceptions, doing input and output, etc.
In essence, effects are similar to exceptions, but, unlike exceptions, which always abort the function's execution
when thrown, effects can choose to continue execution. This is what makes it possible, for example, to use effects
to model a computation that emits a string. For example, take a look at this example from Koka:

```koka
effect emit { // Somewhat similar to 'interface' declaration
    fun emit(msg: string): () // '()' denotes 'Unit' in Koka
}

fun hello(): emit () { // Function has an effect of 'emit', returns `()`
    emit("hello world!")
}
```

The `hello` function must be called with the handler for the `emit` effect. For example, one can
[print](https://koka-lang.github.io/koka/doc/std_core.html#println) emitted messages to the console:

```koka
fun helloToConsole() {
    with handler { fun emit(msg) { println(msg) } }
    hello()
}
```

In languages with effects, the effects are modeled in a type system together with the return type. For example,
the `hello` function in Koka has a type of `() -> emit ()` as it has no parameters, has an `emit` effect, and returns unit.
Here the similarity of effects to checked (typed) exceptions is also apparent as checked exceptions represent a
limited form of typed (checked) effects.

Coeffects constitute a dual approach to modelling the same behavior as with effects. Instead of looking at effects
that a function has, we can look at the context in which the function can be run (see
[Thomas Petricek's work for details](http://tomasp.net/coeffects/)). Kotlin context receivers are a limited form of a typed
(checked) coffect system. We can rewrite the declaration of the `hello` function with an `emit` effect to Kotlin as a
function that requires an `Emit` context:

```kotlin
fun interface Emit {
    fun emit(msg: String)
}

context(Emit)
fun hello() {
    emit("hello world!")
}
```

Similarly to how effectful code can be only run with the handler for the corresponding effect, the contextual functions
can only be run in the corresponding context:

```kotlin
fun helloToConsole() {
    with(Emit { msg -> println(msg) }) {
        hello()
    }
}
```

## Alternative approaches and design tradeoffs

This section describes some alternatives that were considered during design.

The main tradeoff we had to make with the proposed syntax is that in cases when the context is generic, then the use of the
generic parameter happens before its declaration, e.g (from [Use cases](#use-cases) section):

```kotlin
context(Monoid<T>) // T is used
fun <T> List<T>.sum(): T = ...
//  ^^^ T is declared
```

We feel that it is not going to present a problem in idiomatic Kotlin code, since type parameters are usually a few and
named with one uppercase letter, so there is not much need to have auto-completion for them in IDE.
We can also make IDE smart enough to auto-complete the name in the declaration, if the long name is used in the context
before being declared.

### Alternative syntax options

* Direct extensions of single-receiver syntax
  ```kotlin
  fun <T> (Monoid<T>, List<T>).sum(): T = ...
  ```
  Aside from complexity of finding nice syntax that does not interact negatively with other existing and potential
  features of the language, this option does not make it possible to syntactically distinguish the object of the action
  and the additional context of the action.

* Extension block
  ```kotlin
  extension Monoid<T> {
      fun List<T>.sum(): T = ...
  }
  ```
  The nice part of it that is serves a double-duty of addressing the extension grouping request from
  [KT-5670](https://youtrack.jetbrains.com/issue/KT-5670), but it is quite verbose for all the contextual use cases,
  forcing an additional indentation level.

* Context on the right-hand side of function declaration
  ```kotlin
  fun <T> List<T>.sum(): T context(Monoid<T>) = ...
  ```
  This placement would be consistent with Kotlin's `where` clause, but it is not consistent with receivers being specified
  before the function name. Moreover, Kotlin has a syntactic tradition of matching declaration syntax and call-site syntax
  and a context on a call-site is established before the function is invoked.

### Alternative keywords

We've considered a number of alternative keywords.

* `with` (save as scope function to introduce receiver into scope)
  * Note: reusing the same name will make it confusing in discussions
* `using` (Scala name, but in Kotlin may clash with use)
* `given` (will be confusing as Scala uses it for an opposite thing)
* `implicit` (very different thing in Scala)
* Meaning "inside of the additional receiver class or its context/scope"
  * `within`
  * `inside`
  * Note: functions with additional receivers are not really inside, they don't see private
* Meaning "in the context/scope of the receiver class"
  * `incontext`
  * `inscope`
  * **`context` — our choice**
  * `receiver`
  * Highlights implicitness & the fact that they are required
* Meaning "requiring/getting instance of the receiver class"
  * `having`
  * `receiving`
  * `taking`
  * `utilizing`
  * `obtaining`
  * `including`

The choice of `context` was largely driven by its clear use in the prose, as it is quite natural to talk about
"contextual functions" and other contextual abstractions, hence the keyword also fits well.

### Parentheses vs angle brackets

One of the big decisions around design was choosing between parentheses `context(Ctx)` and angle brackets `context<Ctx>`.
Here is the summary of benefits we've found for these two options:

* `context<Ctx>` syntax is consistent with how types are passed as arguments in Kotlin calls. 
  If you have a `Type`, then you pass the corresponding type argument in angle brackets as in 
  `foo<Type>()`, compare this to passing a class in `foo(Type::class)`. The same is true in annotations,
  although annotations with type parameters are rarely used in Kotlin code due to their JVM interop limitations.
  The notable exception is [Android Parcelize](https://developer.android.com/kotlin/parcelize) where you
  could find annotations like`@TypeParceler<ExternalClass, ExternalClassParceler>()`. Angle brackets 
  make this analogy of using a type stand out.
* `context(Ctx)` syntax is consistent with Kotlin parameter declarations. When you declare a function
  with a context receiver, as in `context(Ctx) fun foo(param: Param)`, you actually declare an additional 
  anonymous parameter to a function of type `Ctx`. This analogy becomes especially notable when you 
  consider that the functional type of this declaration, as explained in the [Functional types](#functional-types) section,  
  is equivalent to `(Ctx, Param) -> Unit`. Parentheses make this analogy of declaring a parameter stand out. 

> Another advantage of the angle brackets syntax is that it makes [Backwards compatibility](#backwards-compatibility) 
> concerns go away completely, but they are so minor for parentheses anyway, that we did not pay much attention to
> this difference.

By themselves, these differences do not conclusively point to a choice of which syntactic option is better. 
In the end, the decision was swayed in favor of parentheses, 
because this syntax leaves more doors open for potential future extensions and refinements:

* If we find compelling use-cases, we can naturally add support for named context receivers via 
  `context(name: Ctx)` syntax similarly to named function parameters. 
* If we ever find "type parameter use before declaration" problematic in practice, as explained in 
  the beginning of the [Alternative approaches and design tradeoffs](#alternative-approaches-and-design-tradeoffs) section, 
  the parentheses syntax lends itself to support type parameter introduction before its use
  directly in the `context` parameter declaration as `context<T>(Monoid<T>)`.
* If we ever find the need to support modifiers on context receivers, they will most likely be shared with 
  parameters, due to parameter-like nature of context receivers, as in hypothetical `context(inline Ctx)`.

### Context keyword ambiguities

To avoid potential [Backwards compatibility](#backwards-compatibility) problems in the future with parentheses 
we've considered making new syntax unambiguous everywhere (including function bodies) via additional syntax, 
like `@context(Ctx)`,  `context:(Ctx)`,  `context@(Ctx)`, or `context.(Ctx)`. 
None of the alternatives looked nice or natural.

### Named context receivers

We've looked at various ways to add names to the context receives as an alternative to the qualified `this` syntax
for [Referencing specific context receiver](#referencing-specific-context-receiver). The leading syntactic option
considered was `context(name: Type)` similarly to named function parameters.

However, we did not find enough compelling use cases yet to support such naming, as context receivers are explicitly
designed for something that should be brought into the context, as opposed to a declaration that should be explicitly
referred to by name. Moreover, we do support disambiguation of multiple context receivers using a type-alias if needed.
For cases where you need to bring a named thing into the context, there is a workaround.

Consider an example where you want to bring some `callParameters: Map<String, String>` property into the context of
certain functions in your backend application. If using `context(callParameters: Map<String, String>)` was supported,
it would create a middle-ground concept that, on one hand, should not be brought into scope as a receiver, because
the `Map` interface has lots of methods and is not designed to be used like that, but, on the other hand,
should be implicitly passed down the call chain without syntactic overhead.

Still, you want it named in the context and available for use. A proposed solution is to write the following interface:

```kotlin
interface CallScope {
    val callParameters: Map<String, String>
}
```

Now, all the relevant functions can declare `context(CallScope)` to get access to the `callParameters` property.

### Multiple receivers with decorators

We've looked at a way to combine contextual receivers with decorators into a single feature. Decorators is a potential
future Kotlin feature designed to aid and reduce the amount of magic in typical aspect-oriented programming approaches.
A decorator is a function that wraps another function's body with its code, for example:

```kotlin
// Declaring transactional decorator
decorator fun transactional(block: Transaction.() -> Unit) {
    beginTransation.use { tx ->
        tx.block()
    } // Closes transaction at the end    
}

@transactional // Use decorator to wrap function's body
fun updateUserSession() {
    val session = loadSession()
    session.lastAccess = now()
    storeSession(session)
}
```

This way, the decorator also introduces an additional receiver into the function body and thus can serve as a syntax for
functions with multiple receivers.

However, semantics of declaration-only decorators (e.g. when `fun updateUserSession()` is a part of an interface) are
harder to define consistently. A generic decorator, that would support bringing any context receiver into scope
(e.g. `with` decorator function) will result in harder-to-read use code (something like
`@with<Transaction> fun updateUserSession` was considered). Moreover, bringing additional receivers into scope via
decorators does lend itself to clean separation from the regular extension receiver and to tweaks in the
[Resolution algorithm](#resolution-algorithm) for those additional receivers, such as declaring multiple contexts
without introducing order between them and making sure that context receivers do not otherwise affect the meaning of
unqualified `this` expression.

## Future work

This section lists enhancements that are being prototyped, discussed, or otherwise being considered and are potentially
possible in the future. Feel free to share your ideas in discussion [KEEP-259](https://github.com/Kotlin/KEEP/issues/259).

### Reflection design

Kotlin reflection will have to support the concept of context receivers and represent them in a similar way to dispatch
and extension receivers. The detailed design for reflection is to be done later.

### Contextual delegated properties

The natural extension is to allow operator functions `getValue`, `setValue`, and `provideDelegate` of
[Kotlin delegated properties convention](https://kotlinlang.org/docs/delegated-properties.html) to be contextual.
That former two would allow, for example, using delegation to define transactional properties that can be accessed
only in a context of transaction:

```kotlin
class TransactionalVariable<T> {
    context(Transaction)
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T { ... }
  
    context(Transaction)
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { ... }
}

// Only available for get/set in a context of Transaction
val userName by TransactionalVariable<String>()
```

### Local contextual functions and properties

The current design excludes local functions and properties, because they add technical complications without bringing
substantial benefits. However, supporting them would be a natural future extension. We'll have to design a plan for
dealing with minor [Backwards compatibility](#backwards-compatibility) problems that would arise, some existing uses
of the context name will have to be deprecated.

### Callable references to contextual functions

Support for callable references to contextual functions will need a resolution algorithm that is similar to how
references to global functions are resolved, and unlike references to functions with a receiver:

```kotlin
context(LoggingContext)
fun performSomeBusinessOperation(withParams: Params) { ... }

::performSomeBusinessOperation // Will have type of context(LoggingContext) (Params) -> Unit
```

Currently, we don't have compelling use cases and plans to support special syntax for bound references to
contextual functions (similarly to functions with a receiver). One can always use a lambda, e.g.

```kotlin
val op: (Params) -> Unit =
    { params -> with(createLoggingContext()) { performSomeBusinessOperation(params) } }
```

### Removing context receiver from the scope with DslMarker

Kotlin has a [`@DslMarker`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-dsl-marker/) annotation designed for DSLs.
It is mainly designed to work with [Kotlin builders](#kotlin-builders) which should still be written using extension
receivers as opposed to contextual receivers. However, it might be potentially useful to support `@DslMarker` annotations
on contextual receivers for [Other Kotlin DSLs](#other-kotlin-dsls) if the corresponding use cases arise in the future.

### Scope properties

To call the contextual function, we need the required set of receivers to be brought in the caller scope, which can be
done via scope functions `with`, `run`, or `apply`. However:

* They add an extra pair of curly braces increasing the number of nested scopes and indentation levels.
* They can be used only inside the function scope, while we might want to add a receiver in a class or even file scope.

We consider the future introduction of **scope properties** — a lighter-weight approach to bring a context receiver into
a scope with the regular property declaration syntax using `with` as a new keyword.

```kotlin
class Service {
    with val serviceContext = createServiceContext() // Introduce the scope property with the service context

    fun doSomething() {
        // serviceContext is a context receiver inside the scope of the class, need not be named explicitly
    }
}
```

It is possible to gradually turn `with` into a hard keyword to support even more concise syntax for bringing
annonymous receivers into the scope in a class:

```kotlin
class Service {
    with createServiceContext() // Introduce anonymous scope property
}
```

And, potentially, the same for the local scope 
(expanding on [Algebraic effects and coeffects](#algebraic-effects-and-coeffects) example):

```kotlin
fun helloToConsole() {
    with Emit { msg -> println(msg) }
    hello()
}
```

The detailed design for scope properties is to be presented later.

### Contextual classes and contextual constructors

Contextual classes and contextual constructors are yet another natural future extension of this proposal.
They can be used to require some context to be present for instantiating the class, which has a bunch of use cases.

```kotlin
context(ServiceContext)
class Service {
    fun doSomething() {
        // declarations from ServiceContext are available here
    }
}
```

With the [scope properties](#scope-properties) feature in mind, the above declaration will get desugared to:

```kotlin
class Service
     // Constructor is contextual, needs ServiceContext when it is being invoked
     context(ServiceContext) 
     constructor() {} 

     with this@ServiceContext // capture constructor's param into a scope property

     fun doSomething() {
         // declarations from ServiceContext are available here
     }
}
```

### Future decorators

While we've decided to not rely on decorators for the core support of multiple receivers as explained in the
[Multiple receivers with decorators](#multiple-receivers-with-decorators) section, the very concept of decorators in
a useful one with its own use cases. Moreover, the concept of context receiver and the corresponding changes to the resolution
rules to account for them is an important building block for decorators in the future.

It is important for readability of code written in the Kotlin language to be clear on the meaning of unqualified `this`.
Consider the example decorated function declaration:

```kotlin
@transactional // Use decorator to wrap function's body
fun updateUserSession() { ... }
```

If `updateUserSession` is a top-level function, it should not have any `this` reference. If it is a member function, then
`this` inside the function shall refer to an instance of its container class. A decorator, such as `@transaction`, even
if it adds some declarations to the scope of the function's body, should not be empowered to change the meaning of `this`
inside of it. The concept of context receiver is such a mechanism.

### Unified context properties

Frameworks, libraries, and applications sometimes need to pass various ad hoc local properties throughout the function
call-chains. When a property is used by many functions in the call-chain, then it makes sense to define a dedicated
context interface and explicitly declare all affected functions as contextual. For example, if most functions in our
application need and use some kind of global configuration property, then we can declare the corresponding interface
and mark all the functions in our app with `context(ConfigurationScope)`.

```kotlin
interface ConfigurationScope {
    val configuration: Configuration
}
```

The situation is different when an application uses lots of properties like that, but each function typically uses none or
a few. Examples include authentication information, distributed call tracing, styling properties, etc. 

If we model each property as a separate context interface, then the `context(...)` declarations will quickly get
out of hand. Moreover, it becomes hard to add a new property somewhere deep down the call-chain, as the corresponding
context needs to be passed through the whole call-stack. Another solution is to create an "uber context", which combines
all the contextual properties any piece of the code might need, but this might be impossible in a big modular application,
as specific properties can be local to a module or even local to a few functions in a larger module.

A popular modular solution to pass such context properties is to use
[ThreadLocal](https://docs.oracle.com/javase/8/docs/api/java/lang/ThreadLocal.html) variables. However, thread-locals
are bound to a thread, so any framework whose execution context spans multiple threads must introduce its own solution,
like [CoroutineContext](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/) in Kotlin coroutines,
[CompositionLocal](https://developer.android.com/reference/kotlin/androidx/compose/runtime/CompositionLocal) in Jetpack Compose,
[Context](https://projectreactor.io/docs/core/release/api/reactor/util/context/Context.html) in Project Reactor, etc.
This is challenging for any code that uses several such frameworks together as it is not trivial to ensure preservation
of the context properties when execution spans multiple frameworks. It can also get verbose in the frameworks themselves,
as this context has to be manually passed through the framework code.

If we focus on Kotlin-specific frameworks, then we see that each of them has a framework-specific mechanism to pass
the context around:

* [CompositionLocal](https://developer.android.com/reference/kotlin/androidx/compose/runtime/CompositionLocal) variables
  are passed down the call-chain only via `@Composable` functions.
* [CoroutineContext](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/) elements
  are passed down the call-chain only via `suspend` functions.

When you call a regular function, the context is lost. When you call a suspending function from a composable function,
for example, then the context is lost, too. However, there are use cases where it is convenient to have a unified
`ThreadLocal`-like approach to context properties that can be passed though different types of functions.

A potential future solution is to declare a unified context properties framework in the Kotlin standard library, so that,
first and foremost, it can be used with regular functions, for example:

```kotlin
val contextUser = contextProperty<User?>(null)

context(PropertiesScope) // Can access context properties
fun authenticate() {
    val currentUser = contextUser.current // Retrieve from context
    // ...
}
```

Also, `suspend` and `@Composable` functions can be retrofitted to work as if they are declared with
`context(PropertiesScope)` modifier and so will pass a set of current context properties via their calls,
ensuring interoperability of the corresponding mechanisms.
                               
## Open issues and concerns

This section lists known issues with this proposal that should be mitigated or accepted and weighed against
the benefits this proposal brings.

### Context receivers abuse and scope pollution

Kotlin code may suffer from proliferation of implicit receivers in code. In the worst case, all implicit receivers 
have to be checked to resolve the call during compilation. With many implicit receivers in scope human readers
might also find it hard to figure it out where the declaration that code is using comes from. 

```kotlin
class AClass { // this: AClass 
    fun Extension.doSomething() { // this: Extension
       dsl1 { // this: Builder1
           dsl2 { // this: Builder2
               foo() // where this function is declared? 
           }
       }    
    }    
}
```

Currently, a single pair of nested curly braces `{...}`, being it a class, function, or a lambda, may add at 
most one implicit receiver into scope. This naturally limits proliferation of implicit receivers. You can have at
most as many implicit receivers in scope as the nesting level of your code.

Introducing context receivers makes it possible to add multiple implicit receivers into scope per one nesting level,
removing this natural limitation. If abused, this will make compilation slow (by having to scan move implicit receivers)
and understanding code harder.

The [contexts and coding style](#contexts-and-coding-style) section gives some naming rules and other advice
designed to mitigate potential abuse of context receivers.

### Methods from Any in top-level functions

Counterintuitively, the following code will compile in this design:

```kotlin
context(LoggingContext)
fun weirdToString(): String = toString() // OK
```

However, the following code will not compile (due to resolution ambiguity):

```kotlin
context(LoggingContext, Transaction)
fun weirdToString(): String = toString() // ERROR
```
                                                              
This is an effect of treating context receivers more or less like all other implicit receivers in Kotlin, 
but bundling them into a single group in the [Resolution algorithm](#resolution-algorithm).

All the methods defined on the [`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/) type 
(like `toString`, `equals`, `hashCode`) are available on any Kotlin class and 
thus are also available in any function with a receiver, for example:

```kotlin
fun LoggingContext.weirdToString(): String = toString() // also OK
```

Context receivers do not create an entirely new problem here, but make an existing problem more pronounced. 
One can find legal use cases for `Any` methods on an extension receiver, but we don't know any
sensible use cases with context receivers, so their availability is an unwanted side effect for top-level 
contextual functions. 

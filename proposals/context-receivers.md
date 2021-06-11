# Context receivers

* **Type**: Design proposal
* **Authors**: Roman Elizarov, Anastasia Shadrina
* **Contributors**: Denis Zharkov, Marat Akhin, Mikhail Belyaev, Ilya Gorbunov, Ilmir Usmanov, Simon Ogorodnik,
  Dmitriy Novozhilov, Mikhail Glukhikh
* **Status**: Proposed
* **Prototype**: In Progress

## Feedback

We would appreciate hearing your feedback on this proposal in the (LINK TO KEEP ISSUE).

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

We say that an extension receiver defines an **object of an action**, while a dispatch receiver effectively serves as
an implicit parameter that must be present in the caller's scope but cannot be specified explicitly. Thus, a member
extension function can be called context-dependent function, and a dispatch receiver represents a **context of an action**.

The context-oriented approach has many applications in the design of idiomatic Kotlin APIs (for example, see
["An introduction to context-oriented programming in Kotlin" by Alexander Nozik](https://proandroiddev.com/an-introduction-context-oriented-programming-in-kotlin-2e79d316b0a2))
and is a part of a more generic [code coloring concept](https://github.com/Kotlin/KEEP/blob/5ed1557d3812c4aa2b3856a5a6c92157a891c7d7/notes/code-coloring.md).
However, a member extension is now the only way to define a context-dependent declaration, and this form has
multiple limitations that restrict its practical usefulness.

The key one is that a **member extension cannot be declared on a third-party class**. It limits the ability to decouple,
modularize and structure APIs in larger applications. The only way to introduce a context-dependent `Entity.doAction`
extension is to write it as a member of a `Scope`, which is not always appropriate from a modularity standpoint.

For example, in `kotlinx.coroutines` library, it would be inappropriate to declare a `Flow.launchFlow()` extension as
a member of `CoroutineScope`, because `CoroutineScope` is a more general concept and its declaration shall not depend
on a more specific concept like `Flow`.

Another limitation is that **a member extension is always the extension**. An extension function in Kotlin has an option
of being called with qualified syntax as in `entity.doAction()`. This is a stylistically appropriate syntax when an
action is performed on an entity. However, some functions don't operate on a specific entity and should not be declared
as such. There is no way to declare a top-level function, to be called as `doAction()` that would require a presence of
a specific context in scope.

> Use cases for that come a lot. For example, it would be helpful to be able to define a `TransactionScope` and have
> syntax to declare transactional functions that have a requirement of being called only in a `TransactionScope`.

The final limitation of providing context with member extension is that **only one receiver can represent a context**.
It limits composability of various abstractions, as we cannot declare a function that must be called only within two
or more scopes present at the same time.

> For example, there might be a need to define a function that requires a `CoroutineScope` to be able to launch a
> coroutine and requires a `TransactionScope` at the same time.

### Context receivers and contextual declarations

This proposal introduces the syntax for defining context-dependent declarations with special **context receivers**.
This feature overcomes highlighted limitations and covers a variety of use cases.

The context here is not directly related to the action but is used by the action. It can provide additional operations,
configuration, or execution context. A good example of context would be `Logger`, `Comparator`, or `CoroutineScope`.
A simple **contextual function** would be declared like this:

```kotlin
context(Scope)
fun Entity.doAction()
```

A top-level contextual function can be also declared:

```kotlin
context(Scope)
fun doAction()
```

Its key difference from the `Scope.doAction` extension is that it cannot be called with a qualified `scope.doAction()`
syntax, and it has no `this` inside, since it has no object on which it performs its action.
Moreover, there can be multiple context receivers. See [Detailed design](#detailed-design) section.

### Goals

* Remove all limitations of member extensions for writing contextual abstractions
    * Support top-level (non-member) contextual functions and properties
    * Support adding contextual function and properties to 3rd party context classes
    * Support multiple contexts
* Make blocks of code with multiple receivers representable in Kotlin's type system
* Separate the concepts of extension and dispatch receivers from the concept of context receivers
    * Context receivers should not change the meaning of unqualified this expression
    * Multiple contexts should not be ordered during resolution, resolution ambiguities shall be reported
* Design a scalabe resolution algorithm with respect to the number of receivers
    * Call resolution should not be exponential in the number of context receivers

## Detailed design

As a context requirement for a declaration, context receivers are defined after annotations and before modifiers
with the `context` keyword followed by the list of receiver types. The list can contain one or more types
(a trailing comma is supported, too, for use in multi-line declarations).

```kotlin
context(A, B, C)
```

The following types of declarations can be contextual:

* Functions (top-level, member, extensions functions are currently supported)
* Property getters and setters (of all these kinds, too)

### Contextual functions and property accessors

For functions and property accessors, context receivers are similar to implicit parameters, which bring symbols of the
corresponding types into the body scope and don't need to be passed explicitly at the call-site.

In the following example

```kotlin
context(Comparator<T>)
inline infix operator fun <T> T.compareTo(other: T) = compare(this, other)

context(Comparator<T>)
val <T> Pair<T, T>.max get() = if (first > second) first else second
```

* In the first declaration, `compare` is resolved to `Comparator.compare`, because `Comparator` is a context receiver.
* In the second declaration, the expression `first > second` calls the previously defined function `compareTo`, because
  `Comparator` is a context receiver and can be implicitly passed to `compareTo`.

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

> No widening of context types is allowed on override, similar to parameters.

### Functional types

The functional type of contextual function can be denoted with a similar syntactic construction `context(...)`, which
should be present at the beginning of the functional type signature.

```kotlin
typealias ClickHandler = context(Button) (ClickEvent) -> Unit
```

In the type system, the functional type with context receivers (just as the functional type with an ordinary receiver)
is equivalent to the similar one having all context receivers types as the types of additional arguments. The resulting
signature of the functional type replicates the textual order every argument appears. It means:

* The type `context(C1, C2) R.(P1, P2) -> T` will actually turn into an instance of the type constructor
  `Function6<C1, C2, R, P1, P2, T>`.
* Such assignments are valid:
  ```kotlin
  class Context {
    fun Receiver.method(param: Param) {}
  }

  fun function(context: Context, receiver: Receiver, p: Param) {}

  fun main() {
    var g: context(Context) Receiver.(Param) → Unit
    g = ::function      // OK
    g = Context::method // OK 
  }
  ```

### Referencing specific context receiver

Context receivers can never be referenced using a plain `this` expression and never change the meaning of `this`.
However, both context and extension receivers can be referenced via labeled `this` expression. The compiler generates
the label from the name of receiver type with the following rules:

* If the receiver type is parenthesized, parentheses are omitted
* If the receiver type is nullable, the question mark is omitted
* If the receiver type has type arguments or type parameters, they are omitted
* If the receiver type is a type alias, the label is generated from its name without type parameters
* If the receiver type is functional, no label is generated

```kotlin
context(Logger, Storage<User>)
fun userInfo(name: String): Storage.Info<User> {
  this@Logger.info("Retrieving info about $name")
  return this@Storage.info(name)
}
```

If multiple context receivers have the same generated label, none of them can be referenced with the qualified `this`.
In both cases where the label cannot be generated or referenced, using a type alias is a workaround.

```kotlin
typealias IterableClass<C, T> = (C) -> Iterator<T>

context(IterableClass<C, T>)
inline operator fun <C, T> C.iterator(): Iterator<T> = this@IterableClass.invoke(this)
```

### Resolution algorithm

For the name resolution, context receivers form non-overlapping groups according to the affected scope. There is no
actual order inside groups, but groups themselves are sorted in the scope order: from the innermost to the outermost.

When selecting a resolution candidate, the groups are processed sequentially right after processing names from extension
and dispatch receivers. Multiple candidates in the same group result in ambiguity (which can be resolved with a named
`this` reference). If a suitable candidate is found in some group, name resolution ends. In the initially proposed
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

It is not a concern in the initially proposed implementation, which does not support local contextual function and
properties. To support them in the future we'll have to deprecate such ambiguous uses of user-defined `context` function.
However, we could not find any real Kotlin code that will be affected, so a potential impact of such deprecation is
extremely low.

> We don't need to turn context into a hard keyword and forbid using it as a function or property name. It will be
> a soft-keyword and will stay to be allowed for use as an identifier.

### JVM ABI and Java compatibility

In the JVM, the contextual function is just an ordinary method with an expanded parameter list. Parameters have textual
order according to the functional type signature: context receivers go right after the dispatch receiver (if present)
and before the extension receiver (if present). For the contextual property, the same applies to the getter and setter of it.

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

Context receivers can be useful when:

* Injecting loggers and other contextual information into functions and classes
  ```kotlin
  context(Logger)
  fun performSomeBusinessOperation(withParams: Params) {
    info("Operation has started")
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
* Working with mathematics abstractions
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

> Most of the use cases are from the [original discussion](https://youtrack.jetbrains.com/issue/KT-10468).

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
a context receiver. A typical class is designed with `instance.member` call-side usage in mind, as in `user.name`.  
On the other hand, top-level declarations are designed to be used by their short name without a qualifier.

### Performing an action on an object

When writing a code that performs an action on an object it is customary in Kotlin style to refer to their members
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
Kotlin syntax that allows to define only a single extension receiver in a function declaration. So, when writing
extension that perform an action on an object **don't do this**:

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

Even though both declarations are similar in many aspects, and their bodies look similarly, the declaration of
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

Context receivers shall be used to provide additional, ubiquitous context for actions. As a litmus test as a question
if that corresponding information might have been provided via a global top-level scope in a smaller application
with a simpler architecture. If the answer is yes, then it might be a good idea to provide it via a context receiver.
For example, it is a good idea to inject the source of the current time into various time-dependent functions,
so you might declare a context that providers current time and pass it to time-dependent functions as a context parameter:

```kotlin
interface TimeSource {
  fun now(): Instant
}

context(TimeSource)
fun updateNow() {
  updateTime = now() // GOOD STYLE: Use time from the context
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
continue doing so. Conceptually, code inside `someObject { ... }` block performs an action upon SomeObjectBuilder instance
and using extension receiver for this is in style. Context receivers shall not be used for such builders.

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

### Designing context types

You'd usually need to design new types from scratch to use them as context parameters due to the unique requirement on
the naming of their members and extensions which must be designed with context in mind. Use the same guidelines
if you are designing top-level declarations. A typical business-object would be usually inappropriate as a context receiver.

Prefer interfaces to classes for context receivers. This would help you later on as your application grows — instead
of carrying a number of different contexts in your top-level functions as in:

```kotlin
context(TimeSource, TransactionContext, Logger, ...) // BAD: Too many contexts
fun doSomeTopLevelOperation() { ... }
```

You'll have an option of combining multiple contexts into a single meaningfully named interface:

```kotlin
interface TopLevelContext : TimeSource, TransactionContext, Logger, ...

context(TopLevelContext) // GOOD: A combined context
fun doSomeTopLevelOperation() { ... }
```

## Similar features in other languages

Contextual abstractions exist in other languages.

### Scala given instances and using clauses

Scala 2 introduced the first implementation of contextual abstractions with "impicits": implicit objects, definitions,
classes, and parameters. In Scala 3, "implicits" were redesigned and turned into given instances, using clauses and
extension methods. Using clauses have a lot in common with context receivers.

`using` always works together with some `given`. Given instance defines a value of a certain type, which compiler can
further use to generate an implicit argument for the calls with context parameter of this certain type.
Meanwhile, a context parameter is defined with a using clause.

```scala
// Can be called only in the scope with the given of Ordering[Person] type
def printPersons(s: Seq[Person])(using ord: Ordering[Person]) = ...
```

Context parameters are quite close to context receivers we're describing in this proposal — they also consume a context
from a caller scope. So the example above can be easily translated into Kotlin preserving semantics:

```kotlin
// Can be called only in the scope with the context receiver of Comparator<Person> type
context(Comparator<Person>)
fun printPersons(s: Sequence<Person>) = TODO()
```

### Algebraic effects and coeffects

Algebraic effects is a mechanism that is being implemented in some research language such a as
[Eff](https://www.eff-lang.org/)(with untyped effects) and [Koka](https://github.com/koka-lang/koka)(with typed effects)
to model various effects that a function can have on its environment. For pure functional languages, they provide a
unified abstraction for things like reading and writing state, throwing exceptions, doing input and output, etc.
In essence, effects are similar to exceptions, but, unlike exception, which always abort the function's execution
when thrown, effect can choose to continue execution. This is what makes it possible, for example, to use effects
to model a computation emits a string. For example, take a look at this example from Koka:

```koka
effect emit { // Somewhat similar to 'interface' declaration
  fun emit(msg: string): () // '()' denotes 'Unit'
}

fun hello(): emit () { // Function has an effect of 'emit'
  emit("hello world!")
}
```

The `hello` function can be called with the handler for the `emit` effect. For example, one can
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
[Thomas Petricek's work for details](http://tomasp.net/coeffects/)). Kotlin contexts represent a limited for of typed
(checked) coffect system and we can rewrite the declaration of `hello` function with `emit` effect to Kotlin as a
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
can be run only in the corresponding context:

```kotlin
fun helloToConsole() {
  with(Emit { msg -> println(msg) }) {
    hello()
  }
}
```

## Alternative approaches and design tradeoffs

This section describes some alternatives that were considered during design.

The main tradeoff we had to make with the proposed syntax is that in case when context is generic, then the use of the
generic parameter happens before its declaration, e.g (from [Use cases](#use-cases) section):

```kotlin
context(Monoid<T>) // T is used
fun <T> List<T>.sum(): T = ...
//  ^^^ T is declared
```

We feel that it is not going to present a problem in idiomatic Kotlin code, since type parameters are usually a few and
are usually named with one uppercase letter, so there is not much need to have auto-completion for them in IDE.
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
  [KT-5670](https://youtrack.jetbrains.com/issue/KT-5670), but it is quite verbose for all the contextual use-cases,
  forcing an additional indentation level.

* Context on the right-hand side of function declaration
  ```kotlin
  fun <T> List<T>.sum(): T context(Monoid<T>) = ...
  ```
  This placement would be consistent with Kotiln's `where` clause, but it is not consistent with receivers being specified
  before the function name. Moreover, Kotlin has a syntactic tradition of matching declaration syntax and call-site syntax
  and a context on a call-site is established before the function is invoked.

Alternative keywords

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

The choice of `context` was largely driven by its clear use in the prose, it is quite natural to talk about
"contextual functions" and other contextual abstraction, hence the keyword also fits well.

### Context keyword ambiguities

To avoid potential [Backwards compatibility](#backwards-compatibility) problems in the future we've considered making
new syntax unambiguous everybody (including bodies), like `@context(Ctx)`,  `context:(Ctx)`,  `context@(Ctx)`, or
`context.(Ctx)`. None of the alternatives looked nice.

### Named context receivers

We've looked at various ways to add names to the context receives as an alternative to the qualified this syntax
for [Referencing specific context receiver](#referencing-specific-context-receiver). The leading syntactic option
considered was `context(name: Type)` similarly to named function parameters.

However, we did not find enough compelling use cases yet to support such naming, as context receivers are explicitly
designed for something that should be brought into the context, as opposed to declaration that should be explicitly
referred to by name. Moreover, we do support disambiguation of multiple context receivers using type-alias if needed.
For cases where you need to bring a named thing into the context there is a workaround.

Consider an example where you want to bring some `callParameters: Map<String, String>` property into the context of
certain functions in your backend application. If using `context(callParameters: Map<String, String>)` was supported,
it would create a middle-ground concept that, on one hand, should not be brought into scope as a receiver, because
the `Map` interface has lots of methods and is not designed to be used like that, but, on the other hand,
should be implicitly passed down the call chain without syntactic overhead.

Still, you want it named in the context and available for use. A solution is to write the following interface:

```kotlin
interface CallScope {
  val callParameters: Map<String, String>
}
```

Now, all the relevant functions can declare `context(CallScope)` to get access to a `callParameters` property.

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

This way, decorator also introduces additional receiver into the function body and thus can serve as a syntax for
functions with multiple receivers.

However, semantics of declaration-only decorators (e.g. when `fun updateUserSesssion()` is a part of an interface) are
harder to define consistently. A generic decorator, that would support brining any context receiver into scope
(e.g. `with` decorator function) will result in harder-to-read use code (something like
`@with<Transaction> fun updateUserSession` was considered). Moreover, brining additional receivers into scope via
decorators does not let them to cleanly separate them from the regular extension receiver and to tweak a
[Resolution algorithm](#resolution-algorithm) for those additional receivers, such as declaring multiple contexts
without introducing order between them and making sure that context receivers do not otherwise affect the meaning of
unqualified `this` expression.

## Future work

This section lists enhancements that are being prototyped, discussed, or otherwise being considered and are potentially
possible in the future. Feel free to share your ideas in discussion (LINK TO KEEP ISSUE).

### Reflection design

Kotlin reflection will have to support the concept of context receivers and represent them in a similar way to dispatch
and extension receivers. The detailed design for reflection is to be done later.

### Contextual delegated properties

The natural extension is to allow operator functions `getValue`, `setValue`, and `provideDelegate` of
[Kotlin delegated properties convention](https://kotlinlang.org/docs/delegated-properties.html) to be contextual.
That former two would allow, for example, using delegation to define transactional properties that can be accessed
only in the context of transaction:

```kotlin
class TransactionalVariable<T> {
  context(Transaction)
  operator fun getValue(thisRef: Any?, property: KProperty<*>): T { ... }

  context(Transaction)
  operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { ... }
}

// Only available for get/set in the context of Transaction
val userName by TransactionalVariable<String>()
```

### Local contextual functions and properties

The current design excludes local functions and properties, because they add technical complications without bringing
substantial benefits. However, supporting them would be a natural future extension. We'll have to design a plan for
dealing with minor [Backwards compatibility](#backwards-compatibility) problems that would arise, some existing uses
of context name will have to be deprecated.

### Callable references to contextual functions

Support for callable references to contextual functions will need a resolution algorithm that is similar to how
references to global functions are resolved, and unlike references to functions with receiver:

```kotlin
context(Logger)
fun performSomeBusinessOperation(withParams: Params) { ... }

::performSomeBusinessOperation // Will have type of context(Logger) (Params) -> Unit
```

Currently, we don't have compelling use cases and plans to support any kind of special syntax for bound references to
contextual functions (similarly to functions with receiver). One can always use a lambda, e.g.

```kotlin
val op: (Params) -> Unit =
  { params -> with(logger) { performSomeBusinessOperation(params) } }
```

### Removing context receiver from the scope with DslMarker

Kotlin has [`@DslMarker`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-dsl-marker/) annotation designed for DSLs.
It is mainly designed to work with [Kotlin builders](#kotlin-builders) which should still be written using extension
receivers as opposed to contextual receivers. However, it might be potentially useful to support `@DslMarker` annotations
on contextual receivers for [Other Kotlin DSLs](#other-kotlin-dsls) if the corresponding use cases arise in the future.

### Simpler bringing of contexts into the scope (scope properties)

To call the contextual function, we need the required set of receivers to be brought in the caller scope, which can be
done via scope functions `with`, `run`, or `apply`. However:

* They add an extra pair of curly braces increasing the number of nested scopes and indentation levels.
* They can be used only inside the function scope, while we might want to add a receiver in a class or even file scope.

We consider the future introduction of scope properties — a lighter-weight approach to bring a context receiver into
any type of scope with the regular property declaration syntax and with keyword.

```kotlin
class Service {
  with val logger = createLogger() // Introduce the scope property logger

  fun doSomething() {
    // Use it as a receiver inside the scope of the class
    info("Operation has started")
  }
}
```

The detailed design for scope properties is to be done later.

### Contextual classes and contextual constructors

Contextual classes and contextual constructors are another yet natural future extension of this proposal.
They can be used to require some context to be present for instantiating the class, which has a bunch of use cases.

TODO

### Future decorators

While we've decided to not rely on decorators for the core support of multiple receivers as explained in
[Multiple receivers with decorators](#multiple-receivers-with-decorators) section, the very concept of decorators in
a useful one with its own use cases. Moreover, the concept of context receiver and the corresponding changes to the resolution
rules to account for them is an important building block for decorators in the future.

It is important for readability of code written in the Kotlin language to be clear on the meaning of unqualified `this`.
Consider the example decorated function declaration:

```kotlin
@transactional // Use decorator to wrap function's body
fun updateUserSession() { ...  }
```

If `updateUserSession` is a top-level function, it should not have any `this` reference. If it is a member function, then
`this` inside the function shall refer to an instance of its container class. A decorator, such as `@transaction`, even
if it adds some declarations to the scope of the function's body, should not be empowered to change the meaning of `this`
inside of it. The concept of context receiver is such a mechanism.

### Unified context properties

Frameworks, libraries, and applications sometimes need to pass various ad-hock properties throughout the function
call-chains. When a property is used by many functions in the call-chain, then it makes sense to define a dedicated
context interface and explicitly declare all affected functions as contextual. For example, if most functions in our
application need and use some kind of global configuration property, then we can declare the corresponding interface
and mark all the functions in our app with `context(ConfigurationScope)`.

```kotlin
interface ConfigurationScope {
  val configuration: Configuration
}
```

The situation is different when application uses lots of properties like that, but each function typically uses none or
a few. If we model each property as a separate context interface, then the `context(...)` declarations will quickly get
out of hand. Moreover, it becomes hard to add a new property somewhere deep down the call-chain, as the corresponding
context needs to be passed through the whole call-stack. Another solution is to create an "uber context", which combines
all the contextual properties any piece of the code might need, but this might be impossible in a big modular application,
as specific properties can be local to a module or even local to a few functions in a larger module.

A popular modular solution to pass such context properties is to use
[ThreadLocal](https://docs.oracle.com/javase/8/docs/api/java/lang/ThreadLocal.html) variables. However, thread-locals
are bound to a thread, so any framework whose execution context spans multiple threads must introduce its own solution,
like [CoroutineContext](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/) in Kotlin coroutines,
[CompositionLocal](https://developer.android.com/reference/kotlin/androidx/compose/runtime/CompositionLocal) in JetPack Compose,
[Context](https://projectreactor.io/docs/core/release/api/reactor/util/context/Context.html) in Project Reactor, etc.
This is challenging for any code that uses several such frameworks together as it is not trivial to ensure preservation
of the context properties when execution spans multiple frameworks. It can also get verbose in the the frameworks themselves,
as this context has to be manually passed through the framework code itself.

If we focus on Kotlin-specific frameworks, then we see that each of them focuses on framework-specific mechanism to pass
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

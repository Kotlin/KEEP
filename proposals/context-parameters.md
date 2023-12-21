# Context parameters

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Ahkin, Nikita Bobko, Ilya Gorbunov, Mikhail Zarechenskii, Denis Zharkov
* **Discussion**: [KEEP-367](https://github.com/Kotlin/KEEP/issues/367)

## Abstract

This is an updated proposal for [KEEP-259](https://github.com/Kotlin/KEEP/issues/259), formerly known as _context receivers_. The new design addresses the issues raised by the users of the prototype and across the community. 

This document is not (yet) formally a KEEP, since it lacks some of the technical elements. Those are going to be provided at a later time, but we thought it would be interesting to open the discussion even if the design is not fully formalized.

### Summary of changes from [previous proposal](https://github.com/Kotlin/KEEP/issues/259)

1. Introduction of named context parameters,
2. Removal of `this@Type` syntax, introduction of `summon<A>()`,
3. Not every member of a context receiver is accessible, only those with a context,
4. No subtyping check between different context parameters,
5. Contexts are not allowed in constructors,
6. Callable references resolve their context arguments eagerly,
7. Context-in-classes are dropped.

## Table of contents

* [Abstract](#abstract)
  * [Summary of changes from previous proposal](#summary-of-changes-from-previous-proposal)
* [Table of contents](#table-of-contents)
* [Members with context parameters](#members-with-context-parameters)
* [Use cases](#use-cases)
  * [As implicits](#as-implicits)
  * [As scopes](#as-scopes)
  * [For extending DSLs](#for-extending-dsls)
  * [Context-oriented dispatch / externally-implemented interface / type classes](#context-oriented-dispatch--externally-implemented-interface--type-classes)
  * [Dependency injection](#dependency-injection)
* [Standard library support](#standard-library-support)
  * [Reflection](#reflection)
* [Context receivers](#context-receivers)
  * [⚠️ Preliminary warning](#️-preliminary-warning)
  * [Context receivers and scope](#context-receivers-and-scope)
  * [The migration story for receivers](#the-migration-story-for-receivers)
* [Callable references](#callable-references)
* [Context and classes](#context-and-classes)
* [Technical design](#technical-design)
  * [Syntax](#syntax)
  * [Extended resolution algorithm](#extended-resolution-algorithm)
  * [JVM ABI and Java compatibility](#jvm-abi-and-java-compatibility)
  * [Alternative scoping](#alternative-scoping)
* [Q\&A about design decisions](#qa-about-design-decisions)
* [Acknowledgements](#acknowledgements)


## Members with context parameters

**§1** *(context parameters)*: Every callable member (functions — but not constructors — and properties) gets additional support for **context parameters**. Context parameters are declared with the `context` keyword followed by a list in parentheses. There are two kinds of context parameters:

* **Named context parameters:** with a name, using `name: Type` syntax.
* **Context receivers:** without a name, simply listing the type.

These mirror the two kinds of parameters we can currently declare in Kotlin: value parameters, and receivers. The difference is that they are **implicitly** passed using other context arguments.

```kotlin
context(logger: Logger) fun User.doAction() { ... }
```

**§2** *(context parameters, restrictions)*:

* It is an *error* if the **name** of a context parameter **coincides** with the name of another context or value parameter to the callable.
* We want to warn the user (warning, inspection) if there are two context receivers for which one is a **supertype** of (or equals to) the other, since in that case the shared members would always result in an ambiguity error.
    * This was an error in the previous iteration of the design.

**§3** *(context parameters, order)*: Named context parameters and context receivers may be freely interleaved in a `context` declaration.

* Although people have already referred to some style guidelines, we prefer those to arise organically from the community.

**§4** *(empty context)*: The context may be empty, in which case we can write either `context()` or drop the parentheses to get `context`.

* This affects contextual visibility, as explained below.
* It is useless to declare an empty context in a function without an extension or dispatch receiver, so that case may warrant a warning.
* It is implementation-dependent whether it is allowed to declare two callables with the same signature, except for an empty context in one of them.
    * In the case of Kotlin/JVM, this results in a platform clash unless one of them is renamed.

**§5** *(override)*: Context parameters are part of the signature, and follow the same rules concerning overriding:

* When overriding, the type and order of context parameters must coincide.
    * Even if the context is empty, you cannot override a function without context with one with it, or vice versa.
* It is allowed (yet discouraged) to change the name of a context parameter or its receiver/named status.

**§6** *(naming ambiguity)*: We use the term **context** with two meanings:

1. For a declaration, it refers to the collection of context parameters declared in its signature. We also use the term *contextual function or property*.
2. Within a body, we use context to refer to the combination of the implicit scope, context receivers, and context parameters. This context is the source for context resolution, that is, for "filling in" the implicit context parameters.

**§7** *(function types)*: **Function types** are extended with context parameters, following the same syntax as we have for declarations. However, we do **not** support named context parameters in function types.

* That way we don't have to inspect the body of a lambda because of this. The reasoning is similar to how we restrict lambdas with no declared arguments to be 0 or 1-ary.

```kotlin
context(Transaction) (UserId) -> User?
context(Logger) User.() -> Int
```

Note that, like in the case of regular receivers, those types are considered equivalent (for typing purposes, **not** for resolution purposes) to the function types in which all parameters are declared as "regular" ones.

## Use cases

This is a recollection and categorization of the different use cases we have found for context parameters, including guidelines on which kind of parameter is more applicable (if so).

### As implicits

**§A** *(implicit use case)*: In this case, the context parameter is thought of as a set of services available to a piece of code, but without the ceremony of passing those services explicitly in every call.

* In most cases, those contexts are introduced with a name.
* The context parameter type may or may not have been designed to appear as context.
    * For example, it comes from a Java library.

A `Repository` class for a particular entity or a `Logger` are good examples of this mode of use.

```kotlin
context(users: UserRepository) fun User.getFriends() = ...
```

### As scopes

**§B** *(scope use case)*: In this case we use the context parameter as a marker of being inside a particular scope, which unlocks additional abilities. A prime example is `CoroutineScope`, which adds `launch`, `async`, and so on. In this mode of use:

* The `Scope` type is carefully designed and the functions marked as `context`,
* When used in a function, it appears as context receiver.

This covers other types of scopes as `Transaction`, or `Resource`-related. The `Raise` and `ResourceScope` DSLs from the Arrow project also fit this view.

```kotlin
// currently uses extension receivers
fun ResourceScope.openFile(file: File): InputStream
// but the API is nicer using context receivers
context(ResourceScope) fun File.open(): InputStream
```

We describe later in this document how library authors should ponder context parameters into their existing design.

### For extending DSLs

**§C** *(DSLs use case)*: In this case, contexts are used to provide new members available in a domain-specific language. Currently, this is approached by declaring an interface which represents the "DSL context", and then have member or extension functions on that interface.

```kotlin
interface HtmlScope {
  fun body(block: HtmlScope.() -> Unit)
}
```

Context parameters lift two of the main restrictions of this mode of use:

* It's possible to add new members with extension receiver without modifying the Scope class itself.
* It's possible to add members which are only available when the DSL Scope has certain type arguments.

### Context-oriented dispatch / externally-implemented interface / type classes

**§D** *(Context-oriented dispatch use case)*: Context receivers can be used to simulate functions available for a type, by requiring an interface that uses the type as an argument. This is very similar to type classes in other languages.

```kotlin
interface Comparator<T> {
  context fun T.compareTo(other: T): Boolean
}

context(Comparator<T>) fun <T> max(x: T, y: T) =
  if (x.compareTo(y) > 0) x else y
```

### Dependency injection

**§E.1** *(Dependency injection use case)*: You can view context parameters as values that must be "injected" from the context for the code to work. Since context arguments are completely resolved at compile-time, this provides something like dependency injection in the language.

**§E.1** *(Dependency injection use case, companion object)*: In some cases, you may want to define that instantiating a certain class requires some value — this creates the typical hierarchy of dependency we see in DI frameworks. We can accomplish this by faking a constructor with those contexts:

```kotlin
interface Logger { ... }
interface UserService { ... }

class DbUserService(val logger: Logger, val connection: DbConnection) {
  companion object {
    context(logger: Logger, connection: Connection)
    operator fun invoke(): DbUserService = DbUserService(logger, connection)
  }
}
```

**§E.2** *(Dependency injection use case, discouragement)*: Note that we suggest against this feature, since having parameters explicitly reduces the need to nest too many `context` calls.

```kotlin
// do not do this
context(ConsoleLogger(), ConnectionPool(2)) {
  context(DbUserService()) {
    ...
  }
}

// better be explicit about object creation
val logger = ConsoleLogger()
val pool = ConnectionPool(2)
val userService = DbUserService(logger, pool)
// and then inject everything you need in one go
context(logger, userService) {
   ...
}
```

## Standard library support

**§8** *(`context` function)*: To extend the implicit scope in a contextual manner we provide additional functions in the standard library.

* The implementation may be built into the compiler, instead of having a plethora of functions defined in the standard library.

```kotlin
fun <A, R> context(context: A, block: context(A) () -> R): R = block(context)
fun <A, B, R> context(a: A, b: B, block: context(A, B) () -> R): R = block(a, b)
fun <A, B, C, R> context(a: A, b: B, c: C, block: context(A, B, C) () -> R): R = block(a, b, c)
```

**§9** *(`summon` function)*: We also provide a generic way to obtain a value by type from the context,

```kotlin
context(ctx: A) fun <A> summon(): A = ctx
```

This function replaces the uses of `this@Type` in the previous iteration of the design.

### Reflection

**§10** *(callable reflection)*: The following additions to the `kotlin.reflect` are required for information about members.

```kotlin
interface KParameter {
  enum class Kind { 
    INSTANCE, EXTENSION_RECEIVER, VALUE, 
    **NAMED_CONTEXT****,** **CONTEXT_RECEIVER**
  }
}

val KCallable<*>.declaresContext: Boolean

val KCallable<*>.contextParameters: List<KParameter>
  get() = parameters.filter { 
    it.kind == KParameter.Kind.NAMED_CONTEXT || it.kind == KParameter.Kind.CONTEXT_RECEIVER
  }
  
// 
```

It is possible that `(c.declaredContext && c.contextParameters.isEmpty())` is true if the callable declares an empty context

**§11** *(property reflection)*: Properties with context receivers are not `KProperty0`, `1`, nor `2`, but rather simply `KProperty`.

## Context receivers

### ⚠️ Preliminary warning

**§12** *(context receivers, warning)*: Although the previous iteration of this design was called *context receivers*, and this document spends most of the time speaking about them because they pose a greater design challenge, those are *not* the main use mode of context parameters.

From a documentation (and educational) perspective, we emphasize that *named context parameters* solve many of the problems related to implicitly passing information around, without the caveat of scope pollution. 

```kotlin
context(scope: CoroutineScope) fun User.doSomething() = scope.launch { ... }
```

Context receivers have a narrower use case of delimiting a *context* and making some additional functions only available *within* them. For technical reasons, context receivers (and not named context parameters) also appear in function types, but those types are not usually written by the developers themselves.

### Context receivers and scope

**§13** *(context receivers, motivation)*: we want context receivers to expose a carefully designed API, so the design takes this particular mode of usage into account. For that reason, we ask library designers to think about which members are available when their types are used as context receivers.

**§14** *(contextual visibility)*: Concretely, whenever a context receiver is brought into scope, only the members with a declared context are available without qualification. Note that this includes those with an 0-ary context, which can be thought of as a "marker" for contextual visibility.

```kotlin
interface Logger {
    context fun log(message: String)  // context() fun log(..)
    fun thing()
}

fun Logger.shout(message: String) = log(message.uppercase())

context fun Logger.whisper(message: String) = log(message.lowercase())

context(Logger) fun logWithName(name: String, message: String) =
    log("$name: $message")

context(Logger) fun doSomething() {
    log("Hello")  // ok
    thing()  // unresolved reference 'thing'
    summon<Logger>().thing()  // ok
    shout("Hello")  // unresolved reference 'shout'
    whisper("Hello")  // ok
    logWithName("Alex", "Hello")  // ok
}

fun somethingElse() {
    log("Hello")  // unresolved reference 'log'
    logWithName("Alex", "Hello")  // no context receiver for 'Logger' found
}
```

### The migration story for receivers

**§15** *(receiver migration, caveats)*: It is **not** desirable to change existing APIs from extension receivers to context parameters, because of the potential changes in resolution (different meaning of `this`, changes in callable references). This is especially relevant for libraries which are consumed by virtually everybody (standard library, coroutines).

**§16** *(receiver migration, scope types)*: If your library exposes a "scope" or "context" type, we suggest adding an empty context to their members as soon as possible. Every extension function over this type also needs that modifier.

*How do you know that your type `S` is of such kind?*

* It's often used as extension receiver, but without being the "subject" of the action.
* There's some "run" function that takes a `S.() → R` function as an argument.


The benefit for the consumer of the library is that now they can write functions where the type is a context receiver. This can lead to nicer-to-read code, since it "frees the extension position".

```kotlin
context(ApplicationContext) fun User.buildHtml() = ...
```

**§17** *(receiver migration, functions with subject)*: We encourage library authors to review their API, look for those cases, and provide new variants based on context parameters, instead of merely marking the original function with context.

```kotlin
// Uses extension receivers, but File is in fact the "subject"
fun ResourceScope.openFile(file: File): InputStream

// Doing this is OK, but provides no additonal benefit
context fun ResourceScope.openFile(file: File): InputStream

// The API is nicer using context receivers
@JvmName("contextualFileOpen")  // needed to avoid platform clash
context(ResourceScope) fun File.open(): InputStream
```

**§18** *(receiver migration, alternatives)*: Our design strongly emphasizes that library authors are key in exposing their API using context receivers. As we discussed above, named context parameters should be first considered. However, if you really want this ability for types you do not control, there are two possibilities.

* Named context + `with`

    ```kotlin
    context(ctx: ApplicationContext) fun User.buildHtml() = with(ctx) { ... }
    ```

* Lightweight wrapper type that exposes the functionality as contextual

    ```kotlin
    // if it's an interface, you can delegate
    @JvmInline value class ApplicationContext(
      val app: Application
    ) {
      context fun get() = app.get()
      // copy of every function, but with context on front
    }

    // you can also use it to limit the available methods
    @JvmInline value class MapBuilder<in K, in V>(
      val ctx: MutableMap<K, V>
    ) {
      context fun put(k: K, v: V) = ctx.put(k, v)
    }
    ```

## Callable references

**§19** *(callable references, eager resolution)*: References to callables declared with context parameters are resolved **eagerly**:

* The required context parameters must be resolved in the context in which the reference is created,
* The resulting type does not mention context.

```kotlin
class User
val user = User()

context(users: UserService) fun User.doStuff(x: Int): Int = x + 1

// val x = User::doStuff  // unresolved
// val y = user::doStuff  // unresolved

context(users: UserService) fun example() {
    val g = User::doStuff  // resolve context, g: User.() -> Int
                           // you need to explicitly write the lambda below
    val h: context(UserService) User.() -> Int = { doStuff() }
}
```

**§20** *(callable references, motivation)*: This design was motivated by the pattern of having a set of functions sharing a common context parameter, like in the example below.

```kotlin
context(users: UserService) fun save(u: User): Unit { ... }
context(users: UserService) fun saveAll(users: List<User>): Unit = 
  users.forEach(::save) // ::save is resolved as (User) -> Unit
```

**§21** *(callable references, future)*: We consider as **future** improvement a more complex resolution of callables, in which the context is taken into account when the callable is used as an argument of a function that expects a function type with context.

## Context and classes

**§22** *(no contexts in constructors)*: We do **not** support contexts in constructor declarations (neither primary nor secondary). There are some issues around their design, especially when mixed with inheritance and private/protected visibility.

**§23** *(no contexts in constructors, workaround)*: Note that Kotlin is very restrictive with constructors, as it doesn't allow them to be `suspend` either; the same workarounds (companion object + invoke, function with the name of the class) are available in this case.

**§24** *(no contexts in constructors, future)*: We have defined four levels of how this support may pan out in the future:

1. No context for constructors (current one),
2. Contexts only for secondary constructors,
3. Contexts also in primary constructors, but without the ability to write `val`/`var` in front of them,
4. Support for `val`/`var` for context parameter, but without entering the context,
5. Context parameters declared with `val`/`var` enter the context of every declaration.

**§25** *(no contexts in class declarations)*: The prototype also contains a "context in class" feature, which both adds a context parameter to the constructor, and makes that value available implicitly in the body of the class. We've explored some possibilities, but the conclusion was that we do not know at this point which is the right one. Furthermore, we think that "scoped properties" may bring a better overall solution to this problem; and adding this feature now would get in the way.

## Technical design

### Syntax

**§26** *(`context` is a modifier)*: Everybody's favorite topic! Although the current implementation places some restrictions on the location of the context block, the intention is to turn it (syntactically) into a modifier. In terms of the Kotlin grammar,

```
functionModifier: ... | context
propertyModifier: ... | context

context: 'context' [ '(' [ contextParameter { ',' contextParameter } ] ')' ]
contextParameter: receiverType | parameter

functionType: [ functionContext ] {NL} [ receiverType {NL} '.' {NL} ] ...
functionContext: 'context' [ '(' [ receiverType { ',' receiverType } ] ') ]
```

**Recommended style:** annotations, context parameters, other modifiers as per the [usual style guide](https://kotlinlang.org/docs/coding-conventions.html#modifiers-order).

### Extended resolution algorithm

**§27** *(scope with contexts)*: When **building the scope** corresponding to a callable we need to mark the new elements accordingly.

* Context receivers are added to the scope and marked as such (so differently from the default receiver). They have lower priority than other receivers.
* Named context parameters are added in the same way as value parameters, and marked as such. They have the same priority as regular value parameters.

Note that context receivers are **not** added as default receivers. In particular, this means they are **not** accessible through the `this` reference.

**§28** *(resolution, candidate sets)*: When **building candidate sets** during resolution, we consider context receivers part of the implicit scope, in a similar way as default receivers. However:

* We consider only member callables which are marked with `context`.
* When considering callables where the type of the context receiver appears as extension or dispatch receiver, we consider only those marked with `context`.

Remember that the candidate set from context receivers has less priority than those from other receivers.

```kotlin
class A { context fun foo(x: Int) }
class B { fun foo(x: Object) }

// here 'foo' resolves to the one in 'B'
// because 'B' is introduced in an inner scope
// the fact that the one from 'A' is more specific doesn't play a role
context(A) B.example1() = foo(5)

// 'this' always resolves to 'B'
context(A) B.example2() = this.foo(5)

// to access the members from 'A' use a name, 'summon'
context(A) B.example3a() = summon<A>().foo(5)
context(a: A) B.example3b() = a.foo(5)
context(a: A) B.example3b() = with(a) { foo(5) }
```

**§29** *(resolution, most specific candidate)*: When choosing the **most specific candidate** we follow the Kotlin specification, with one addition:

* Candidates with declared context (even if empty) are considered more specific than those without a context.
* In particular, a declared but empty context is not more specific than one with 1 or more context parameters.

**§30** *(resolution, context resolution)*: Once the overload candidate is chosen, we **resolve** context parameters (if any). For each context parameter:

* We traverse the tower of scopes looking for **exactly one** default receiver, context receivers, or named context parameter with a compatible type.
* It is an ambiguity error if more than one value is found at the same level.
* It is an overload resolution error if no applicable context parameter is found.

We stress that each context parameter is resolved separately. There is no special "link" between all the context receivers and arguments introduced in the same declaration.

```kotlin
interface Logger {
  context fun log(message: String)
}
class ConsoleLogger: Logger { ... }
class FileLogger: Logger { ... }

context(Logger) fun logWithTime(message: String) = ...

context(ConsoleLogger, FileLogger) fun example1a() =
  logWithTime("hello")  // ambiguity error
  
context(console: ConsoleLogger, file: FileLogger) fun example1b() =
  logWithTime("hello")  // ambiguity error
  
context(FileLogger) fun example2() = context(ConsoleLogger()) { 
  logWithTime("hello")  // no ambiguity, uses the new ConsoleLogger
}

context(console: ConsoleLogger, file: FileLogger) fun example3() =
  with(console) { logWithTime("hello") }  // no ambiguity, uses 'console'
  
context(ConsoleLogger) fun FileLogger.example4() =
  logWithTime("hello")  // no ambiguity, uses FileLogger
```

### JVM ABI and Java compatibility

**§31** *(JVM and Java compatibility)*: In the JVM a function with context parameters is represented as a regular function with the context parameters situated at the *beginning* of the parameter list. The name of the context parameter, if present, is used as the name of the parameter.

* Note that parameter names do not impact JVM ABI compatibility.

### Alternative scoping

**§31** *(All receivers in the same scope)*: We have designed an alternative in which all parameters and receivers of a declaration form a single scope. However, we have decided against it, because we wanted the addition of a context in an already-existing function to introduce as few clashes as possible.

```kotlin
class A { context fun foo(x: Int) }
class B { fun foo(x: Object) }

// here 'foo' resolves to the one in 'A'
// because both 'A' and 'B' are in the same scope
// but the one from 'A' is more specific
context(A) B.example1() = foo(5)
```

## Q&A about design decisions

*Q: Why introduce the concept of contextual visibility?*

One of the main objections to the previous design was the potential scope pollution. Although named context parameters make this issue less pressing, there are still problems with bringing a whole context receiver into implicit scope.

In particular, some functions like `toString`, `equals`, ... become ambiguous — or even worse, they resolve to something different from what the developer intended. Since they are declared without context, those functions won't be accessible from context receivers.

*Q: Why only give the author of a callable the ability to add an empty context, and as a result make that callable available from a context receiver?*

We've toyed with several designs here (annotation in the declaration, annotation in the import), but named context parameter + `with` seems to work just fine, and it doesn't bring any new piece to the mix.

*Q: Why do callable references resolve differently with context parameters and extension receivers?*

We've actually considered five different designs; there are the reason we've decided for the described one.

* Even though extension (and dispatch) receivers are implicit in function calls, they are explicit in callable references — think of `User::save`. This is not the case for context parameters.
* We found the use case of passing a reference to a function such `map`, while reusing the context from the outer declaration, a quite important one.

Note that we intend to improve our design to account for additional cases in which this rule fails, but taking the context parameters succeeds. However, this is part of a larger process of function conversions, in which we also want to consider similar behavior for suspend, composable, and other modifiers.

*Q: Why drop the subtyping restriction between context receivers?*

One important piece of feedback in the previous design was the need for functions that introduce more than one value in the context. In turn, that means providing a function such as the following.

```kotlin
fun <A, B, R> context(a: A, b: B, block: context(A, B) () -> R): R
```

The subtyping restriction would disallow this case, since `A` and `B` may (potentially) be subtypes of one another. We've considered adding some sort of additional restrictions at the type level or making this function a built-in, but in the end, we've decided that it's the developer's responsibility to keep a clean and ordered context. An ambiguity error seems reasonable if the problem really arises at the use site.

*Q: Why drop the context-in-class feature altogether?*

As we explored the design, we concluded that the interplay between contexts and inheritance and visibility is quite complex. Think of questions such as whether a context receiver to a class should also be in implicit scope in an extension method to that class, and whether that should depend on the potential visibility of such context receiver. At this point, we think that a good answer would only come if we fully design "`with`` properties", that is, values which also enter the context scope in their block.

An additional stone in the way is that one commonly requested use case is to have a constructor with value parameters, and other where some of those are contextual. However, this leads to platform clashes, so we would need an additional design on that part.

## Acknowledgements

We thank everybody who has contributed to improving and growing the context receiver proposal to this current form. Several people have devoted a significant amount of time being interviewed and sharing their thoughts with us, showing the involvement of the Kotlin community in this process.

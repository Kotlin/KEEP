# Context parameters

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Nikita Bobko, Ilya Gorbunov, Mikhail Zarechenskii, Denis Zharkov
* **Discussion**: [KEEP-367](https://github.com/Kotlin/KEEP/issues/367)

## Abstract

This is an updated proposal for [KEEP-259](https://github.com/Kotlin/KEEP/issues/259), formerly known as _context receivers_. The new design addresses the issues raised by the users of the prototype and across the community. 

This document is not (yet) formally a KEEP, since it lacks some of the technical elements. Those are going to be provided at a later time, but we thought it would be interesting to open the discussion even if the design is not fully formalized.

### Summary of changes from the [previous proposal](https://github.com/Kotlin/KEEP/issues/259)

1. Introduction of named context parameters,
2. Context receivers are dropped,
3. Removal of `this@Type` syntax, introduction of `implicit<A>()`,
4. Contexts are not allowed in constructors,
5. Callable references resolve their context arguments eagerly,
6. Context-in-classes are dropped.

## Table of contents

* [Abstract](#abstract)
  * [Summary of changes from the previous proposal](#summary-of-changes-from-the-previous-proposal)
* [Table of contents](#table-of-contents)
* [Members with context parameters](#members-with-context-parameters)
* [Standard library support](#standard-library-support)
  * [Reflection](#reflection)
* [Simulating receivers](#simulating-receivers)
* [Use cases](#use-cases)
  * [As implicits](#as-implicits)
  * [As scopes](#as-scopes)
  * [For extending DSLs](#for-extending-dsls)
  * [Context-oriented dispatch / externally-implemented interface / type classes](#context-oriented-dispatch--externally-implemented-interface--type-classes)
  * [Dependency injection](#dependency-injection)
* [Callable references](#callable-references)
* [Context and classes](#context-and-classes)
* [Technical design](#technical-design)
  * [Syntax](#syntax)
  * [Extended resolution algorithm](#extended-resolution-algorithm)
  * [JVM ABI and Java compatibility](#jvm-abi-and-java-compatibility)
* [Q\&A about design decisions](#qa-about-design-decisions)
* [Acknowledgments](#acknowledgments)


## Members with context parameters

**§1** *(declaration)*: Every callable member (functions — but not constructors — and properties) gets additional support for **context parameters**. Context parameters are declared with the `context` keyword followed by a list of parameters, each of the form `name: Type`.

* Within the body of the declared member, the value of the context parameter is accessible using its name, similar to value parameters.
* It is allowed to use `_` as a name; in that case, the value is not accessible through any name (but still participates in context resolution).

**§2** *(restrictions)*:

* It is an *error* to declare an **empty** list of context parameters.
* It is an *error* if the **name** of a context parameter **coincides** with the name of another context or value parameter to the callable (except for multiple uses of `_`).

**§3** *(implicitness)*: When calling a member with context parameters, those are not spelled out. Rather, the value for each of those arguments is **resolved** from two sources: in-scope context parameters, and implicit scope. We say that context parameters are **implicit**.

```kotlin
context(logger: Logger) fun logWithTime(message: String) =
  logger.log("${LocalDateTime.now()}: $message")

context(logger: Logger) fun User.doAction() {
  logWithTime("saving user $id")
  // ...
}
```

**§4** *(override)*: Context parameters are part of the signature, and follow the same rules as regular value parameters concerning overriding:

* When overriding, the type and order of context parameters must coincide.
* It is allowed (yet discouraged) to change the name of a context parameter.

**§5** *(naming ambiguity)*: We use the term **context** with two meanings:

1. For a declaration, it refers to the collection of context parameters declared in its signature. We also use the term *contextual function or property*.
2. Within a body, we use context to refer to the combination of the implicit scope and context parameters. This context is the source for context resolution, that is, for "filling in" the implicit context parameters.

**§6** *(function types)*: **Function types** are extended with context parameters. It is only allowed to mention the *type* of context parameters, names are not supported.

* We do not want to inspect the body of a lambda looking for different context parameter names during overload resolution. The reasoning is similar to how we restrict lambdas with no declared arguments to be 0 or 1-ary.

```kotlin
context(Transaction) (UserId) -> User?
context(Logger) User.() -> Int
```

Note that, like in the case of extension receivers, those types are considered equivalent (for typing purposes, **not** for resolution purposes) to the function types in which all parameters are declared as value parameters.

**§7** *(lambdas)*: If a lambda is assigned a function type with context parameters, those behave as if declared with `_` as its name.

* They participate in context resolution but are only accessible through the `context` function (defined below).

```kotlin
fun <A> withConsoleLogger(block: context(Logger) () -> A) = ...

withConsoleLogger {
  val logger = implicit<Logger>()
  // you can call functions with Logger as context parameter
  logWithTime("doing something")
}
```

## Standard library support

**§7** *(`context` function)*: To extend the implicit scope in a contextual manner we provide additional functions in the standard library.

* The implementation may be built into the compiler, instead of having a plethora of functions defined in the standard library.

```kotlin
fun <A, R> context(context: A, block: context(A) () -> R): R = block(context)
fun <A, B, R> context(a: A, b: B, block: context(A, B) () -> R): R = block(a, b)
fun <A, B, C, R> context(a: A, b: B, c: C, block: context(A, B, C) () -> R): R = block(a, b, c)
```

**§8** *(`implicit` function)*: We also provide a generic way to obtain a value by type from the context. It allows access to context parameters even when declared using `_`, or within the body of a lambda.

```kotlin
context(ctx: A) fun <A> implicit(): A = ctx
```

This function replaces the uses of `this@Type` in the previous iteration of the design.

### Reflection

**§10** *(callable reflection)*: The following additions to the `kotlin.reflect` are required for information about members.

```kotlin
interface KParameter {
  enum class Kind { 
    INSTANCE, EXTENSION_RECEIVER, VALUE, 
    CONTEXT_PARAMETER // new
  }
}

val KCallable<*>.contextParameters: List<KParameter>
  get() = parameters.filter { 
    it.kind == KParameter.Kind.CONTEXT_RECEIVER
  }

```

**§11** *(property reflection)*: Properties with context parameters are not `KProperty0`, `1`, nor `2`, but rather simply `KProperty`.

## Simulating receivers

There are cases where the need to refer to members of a context parameter through its name may hurt readability — this happens mostly in relation to DSLs. In this section, we provide guidance on how to solve this issue.

**§12** *(bridge function)*: Given an interface with some members,

```kotlin
interface Raise<in Error> {
  fun raise(error: Error): Nothing
}
```

we want to let users call `raise` whenever `Raise<E>` is in scope, without having to mention the context parameter, but ensuring that the corresponding value is part of the context.

```kotlin
context(_: Raise<E>) fun <E, A> Either<E, A>.bind(): A =
  when (this) {
    is Left  -> raise(error)  // instead of r.raise
    is Right -> value
  }
```

We do this by introducing a **bridge function** that simply wraps the access to the context parameter.

```kotlin
context(r: Raise<E>) inline fun raise(error: Error): Nothing = r.raise(error)
```

**§12** *(receiver migration, members)*: If your library exposes a "scope" or "context" type, we suggest moving to context parameters:

1. functions with the scope type as extension receiver should be refactored to use context parameters,
2. operations defined as members and extending other types should be taken out of the interface definition, if possible,
3. operations defined as members of the scope type should be exposed additionally with bridge functions.

*How do you know that your type `S` is of such kind?*

* It's often used as extension receiver, but without being the "subject" of the action.
* There's some "run" function that takes a `S.() → R` function as an argument.

```kotlin
fun <E> Raise<E>.foo() = ...
// should become
context(_: Raise<E>) fun <E> foo() = ...

interface Raise<in Error> {
  fun <A> Either<Error, A>.bind() = ...
}
// this function can be exposed now as follows
context(_: Raise<E>) fun <E, A> Either<E, A>.bind() = ...
```

**§13** *(receiver migration, run functions)*: We advise keeping any function taking a lambda where the scope type appears as extension receiver as is, and provide a new variant with a context parameter. The latter must unfortunately get a different name to prevent overload conflicts.

```kotlin
fun <E, A> runRaise(block: Raise<E>.() -> A): Either<E, A>
// provide the additional variant
fun <E, A> runRaiseContext(block: context(Raise<E>) () -> A): Either<E, A>
```

**§14** *(receiver migration, source compatibility)*: The rules above guarantee source compatibility for users of the interface.

## Use cases

This is a recollection and categorization of the different use cases we have found for context parameters, including guidelines on which kind of parameter is more applicable (if so).

### As implicits

**§A** *(implicit use case)*: In this case, the context parameter is thought of as a set of services available to a piece of code, but without the ceremony of passing those services explicitly in every call. In most cases, those contexts are introduced with an explicit name.

A `Repository` class for a particular entity or a `Logger` are good examples of this mode of use.

```kotlin
context(users: UserRepository) fun User.getFriends() = ...
```

### As scopes

**§B** *(scope use case)*: In this case we use the context parameter as a marker of being inside a particular scope, which unlocks additional abilities. A prime example is `CoroutineScope`, which adds `launch`, `async`, and so on. In this mode of use:

* The `Scope` type is carefully designed, and the functions are exposed using bridge functions,
* In most cases, the context parameter has no name or is irrelevant.

This covers other types of scopes as `Transaction`, or `Resource`-related. The `Raise` and `ResourceScope` DSLs from the Arrow project also fit this view.

```kotlin
// currently uses extension receivers
fun ResourceScope.openFile(file: File): InputStream
// but the API is nicer using context parameters
context(_: ResourceScope) fun File.open(): InputStream
```

### For extending DSLs

**§C** *(DSLs use case)*: In this case, contexts are used to provide new members available in a domain-specific language. Currently, this is approached by declaring an interface that represents the "DSL context", and then having member or extension functions on that interface.

```kotlin
interface HtmlScope {
  fun body(block: HtmlScope.() -> Unit)
}
```

Context parameters lift two of the main restrictions of this mode of use:

* It's possible to add new members with an extension receiver without modifying the Scope class itself.
* It's possible to add members which are only available when the DSL Scope has certain type arguments.

### Context-oriented dispatch / externally-implemented interface / type classes

**§D** *(context-oriented dispatch use case)*: Context parameters can be used to simulate functions available for a type, by requiring an interface that uses the type as an argument. This is very similar to type classes in other languages.

```kotlin
interface Comparator<T> {
  fun compareTo(one: T, other: T): Boolean
}

context(comparator: Comparator<T>) fun <T> T.compareTo(other: T): Boolean =
  comparator.compareTo(one, other)

context(_: Comparator<T>) fun <T> max(x: T, y: T) =
  if (x.compareTo(y) > 0) x else y
```

### Dependency injection

**§E.1** *(dependency injection use case)*: You can view context parameters as values that must be "injected" from the context for the code to work. Since context arguments are completely resolved at compile-time, this provides something like dependency injection in the language.

**§E.1** *(dependency injection use case, companion object)*: In some cases, you may want to define that instantiating a certain class requires some value — this creates the typical hierarchy of dependency we see in DI frameworks. We can accomplish this by faking a constructor with those contexts:

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

**§E.2** *(dependency injection use case, discouragement)*: Note that we suggest against this feature, since having parameters explicitly reduces the need to nest too many `context` calls.

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

## Callable references

**§15** *(callable references, eager resolution)*: References to callables declared with context parameters are resolved **eagerly**:

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

**§16** *(callable references, motivation)*: This design was motivated by the pattern of having a set of functions sharing a common context parameter, like in the example below.

```kotlin
context(users: UserService) fun save(u: User): Unit { ... }
context(users: UserService) fun saveAll(users: List<User>): Unit = 
  users.forEach(::save) // ::save is resolved as (User) -> Unit
```

**§17** *(callable references, future)*: We consider as **future** improvement a more complex resolution of callables, in which the context is taken into account when the callable is used as an argument of a function that expects a function type with context.

## Context and classes

**§18** *(no contexts in constructors)*: We do **not** support context parameters in constructor declarations (neither primary nor secondary). There are some issues around their design, especially when mixed with inheritance and private/protected visibility.

**§19** *(no contexts in constructors, workaround)*: Note that Kotlin is very restrictive with constructors, as it doesn't allow them to be `suspend` either; the same workarounds (companion object + `invoke``, function with the name of the class) are available in this case.

**§20** *(no contexts in constructors, future)*: We have defined four levels of how this support may pan out in the future:

1. No context for constructors (current one),
2. Contexts only for secondary constructors,
3. Contexts also in primary constructors, but without the ability to write `val`/`var` in front of them,
4. Support for `val`/`var` for context parameter, but without entering the context,
5. Context parameters declared with `val`/`var` enter the context of every declaration.

**§21** *(no contexts in class declarations)*: The prototype also contains a "context in class" feature, which both adds a context parameter to the constructor and makes that value available implicitly in the body of the class. We've explored some possibilities, but the conclusion was that we do not know at this point which is the right one. Furthermore, we think that "scoped properties" may bring a better overall solution to this problem; and adding this feature now would get in the way.

## Technical design

### Syntax

**§22** *(`context` is a modifier)*: Everybody's favorite topic! Although the current implementation places some restrictions on the location of the context block, the intention is to turn it (syntactically) into a modifier. In terms of the Kotlin grammar,

```
functionModifier: ... | context
propertyModifier: ... | context

context: 'context' [ '(' [ parameter { ',' parameter } ] ')' ]

functionType: [ functionContext ] {NL} [ receiverType {NL} '.' {NL} ] ...
functionContext: 'context' [ '(' [ receiverType { ',' receiverType } ] ') ]
```

**Recommended style:** annotations, context parameters, other modifiers as per the [usual style guide](https://kotlinlang.org/docs/coding-conventions.html#modifiers-order).

### Extended resolution algorithm

**§23** *(declaration with context parameters)*: The context parameters declared for a callable are available in the same way as "regular" value parameters in the body of the function. Both value and context parameters are introduced in the same scope, there is no shadowing between them.

**§24** *(function applicability)*: Building the constraint system is modified for lambda arguments. Compared with the [Kotlin specification](https://kotlinlang.org/spec/overload-resolution.html#description), the type of the parameter _U<sub>m</sub>_ is replaced with _nocontext(U<sub>m</sub>)_, where _nocontext_ removes the initial `context` block from the function type.

**§25** *(most specific candidate)*: When choosing the **most specific candidate** we follow the Kotlin specification, with one addition:

* Candidates with context parameters are considered more specific than those without them.
* But there is no other prioritization coming from the length of the context parameter list or their types.

**§26** *(context resolution)*: Once the overload candidate is chosen, we **resolve** context parameters (if any). For each context parameter:

* We traverse the tower of scopes looking for **exactly one** default receiver or context parameter with a compatible type.
    * Anonymous context parameters (declared with `_`) also participate in this process. 
* It is an ambiguity error if more than one value is found at the same level.
* It is an overload resolution error if no applicable value is found.

```kotlin
interface Logger {
  fun log(message: String)
}
class ConsoleLogger: Logger { ... }
class FileLogger: Logger { ... }

context(logger: Logger) fun logWithTime(message: String) = ...

context(console: ConsoleLogger, file: FileLogger) fun example1() =
  logWithTime("hello")  // ambiguity error
  
context(file: FileLogger) fun example2() = context(ConsoleLogger()) { 
  logWithTime("hello")  // no ambiguity, uses the new ConsoleLogger
}

context(console: ConsoleLogger, file: FileLogger) fun example3() =
  with(console) { logWithTime("hello") }  // no ambiguity, uses 'console'
```

### JVM ABI and Java compatibility

**§27** *(JVM and Java compatibility)*: In the JVM a function with context parameters is represented as a regular function with the context parameters situated at the *beginning* of the parameter list. The name of the context parameter, if present, is used as the name of the parameter.

* Note that parameter names do not impact JVM ABI compatibility.


## Q&A about design decisions

*Q: Why drop context receivers?*

One of the main objections to the previous design was the potential scope pollution:

- Having too many functions available in implicit scope makes it difficult to find the right one;
- It becomes much harder to establish where a certain member is coming from.

We think that context parameters provide a better first step in understanding how implicit context resolution fits in Kotlin, without that caveat.

*Q: Why do callable references resolve differently with context parameters and extension receivers?*

We've considered five different designs; these are the reasons we've decided for the described one.

* Even though extension (and dispatch) receivers are implicit in function calls, they are explicit in callable references — think of `User::save`. This is not the case for context parameters.
* We found the use case of passing a reference to a function such `map`, while reusing the context from the outer declaration, a quite important one.

Note that we intend to improve our design to account for additional cases in which this rule fails, but taking the context parameters succeeds. However, this is part of a larger process of function conversions, in which we also want to consider similar behavior for suspend, composable, and other modifiers.

*Q: Why drop the subtyping restriction between context receivers?*

One important piece of feedback in the previous design was the need for functions that introduce more than one value in the context. In turn, that means providing a function such as the following.

```kotlin
fun <A, B, R> context(a: A, b: B, block: context(A, B) () -> R): R
```

The subtyping restriction would disallow this case, since `A` and `B` may (potentially) be subtypes of one another. We've considered adding some sort of additional restrictions at the type level or making this function a built-in, but in the end, we've decided that it's the developer's responsibility to keep a clean and ordered context. An ambiguity error seems reasonable if the problem arises at the use site.

*Q: Why drop the context-in-class feature altogether?*

As we explored the design, we concluded that the interplay between contexts and inheritance and visibility is quite complex. Think of questions such as whether a context parameter to a class should also be in implicit scope in an extension method to that class, and whether that should depend on the potential visibility of such context parameter. At this point, we think that a good answer would only come if we fully design "`with`` properties", that is, values that also enter the context scope in their block.

An additional stone in the way is that one commonly requested use case is to have a constructor with value parameters and another where some of those are contextual. However, this leads to platform clashes, so we would need an additional design on that part.

## Acknowledgments

We thank everybody who has contributed to improving and growing the context parameters proposal to this current form. Several people have devoted a significant amount of time to being interviewed and sharing their thoughts with us, showing the involvement of the Kotlin community in this process.

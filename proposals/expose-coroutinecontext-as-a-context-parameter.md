# Make all `suspend` functions implicitly expose `CoroutineContext` context parameter

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**:
  Alejandro Serrano
* **Issue:**
  [KT-15555 Support suspend get/set properties](https://youtrack.jetbrains.com/issue/KT-15555)
* **Discussion**: [todo](https://github.com/Kotlin/KEEP/discussions/todo)

## Abstract

[KEEP-367](./context-parameters.md) introduces a context parameters feature to Kotlin.

There are multiple ways on how you can can look at context parameters.
One of them is that context parameters are a "better" way to declare global variables.

It's a common knowledge that we should prefer function parameters over global variables – it makes our programs easier to test and parallelize.
But still, programmers tend to use global variables, because passing parameters manually might be too tedious and might require too much ceremony in some cases.

Context parameters partially cover this use case.
They are not as tedious to pass around as regular parameters,
and they don't have the downsides of the global variables.

Turns out that Kotlin already has another existing feature that covers the same use case in a similar way – `suspend` functions.
All `suspend` functions have an implicit `$completion: kotlin.coroutines.Continuation` parameter.
And while the `$completion` parameter itself isn't equivalent to context parameters since the continuations are not just passed around and are rather wrapped on every suspending call,
but the `kotlin.coroutines.Continuation`'s `context` property **is** equivalent to context parameters.

Here is a side-by-side comparison:

<table>
<tr>
<td>

```kotlin
// Context parameters
class User(val name: String)



fun main() {
    context(User("Kodee")) {
        downDeepTheCallStack()
    }
}

context(user: User) fun downDeepTheCallStack() {
    println("Hello ${user.name}!")
}
```
</td>
<td>

```kotlin
// CoroutineContext
class User(val name: String) : AbstractCoroutineContextElement(Companion) {
    companion object : CoroutineContext.Key<User>
}

suspend fun main() {
    withContext(User("Kodee")) {
        downDeepTheCallStack()
    }
}

suspend fun downDeepTheCallStack() {
    println("Hello ${coroutineContext[User]?.name}!")
}
```
</td>
</tr>
</table>

A lot of existing Kotlin code that relies on coroutines already uses `suspend` functions for that purpose (e.g. IntelliJ IDEA, Ktor server applications)

## Table of contents

- [The proposal](#the-proposal)

## The proposal

Goals:
1. Bridge `suspend` and context parameters features together
2. Change the implementation of `kotlin.coroutines.coroutineContext` stdlib property from using a compiler intrinsic to using a common language feature – context parameters
3. Cover the use case of `suspend` properties without the downside of introducing `suspend` properties [KT-15555](https://youtrack.jetbrains.com/issue/KT-15555)

The proposal is to treat all `suspend` functions as if all of them had an implicitly declared unnamed context parameter of type `CoroutineContext`:

```kotlin
// context(_: CoroutineContext) // An implicitly declared context parameter
suspend fun computation() {
    foo() // Green code
}

context(_: CoroutineContext)
fun foo() {}
```

The suggested implicit context parameter doesn't change function signature.

## `coroutineContext` stdlib property

Currently, `suspend` properties are disallowed to encourage users to declare only such properties which are cheap to compute.
If users want to declare a `suspend` property, Kotlin nudges them to use functions instead.

But as identified in [KT-15555](https://youtrack.jetbrains.com/issue/KT-15555),
one valid use case for `suspend` properties is to access `kotlin.coroutines.coroutineContext`,
which in turn **is** a `suspend` property in stdlib.

The proposal makes it possible to rewrite the current `kotlin.coroutines.coroutineContext` declaration from being a magical `suspend` property to becoming a regular property with context parameter instead.

<table>
<tr>
<td>Before</td> <td>After</td>
<tr>
<td>

```kotlin
package kotlin.coroutines

/**
 * Returns the context of the current coroutine.
 */
@SinceKotlin("1.3")
@Suppress("WRONG_MODIFIER_TARGET")
@InlineOnly
public suspend inline val coroutineContext: CoroutineContext
    get() {
        throw NotImplementedError("Implemented as intrinsic")
    }
```
</td>
<td>

```kotlin
package kotlin.coroutines

/**
 * Returns the context of the current coroutine.
 */
@SinceKotlin("1.3")
@InlineOnly
context(context: CoroutineContext)
public inline val coroutineContext: CoroutineContext get() = context



```
</td>
</tr>
</table>

Please note that the suggested change doesn't break binary compatibility.

We kill two birds with one stone:
1. We can close [KT-15555](https://youtrack.jetbrains.com/issue/KT-15555),
  and ask users to prefer properties with context parameters if all they need is an access to `CoroutineContext`.
2. The magical `coroutineContext` compiler intrinsic becomes a proper language feature.

### `suspend` function with explicit `CoroutineContext` context parameter

The following example requires an explicit notice:

```kotlin
context(_: CoroutineContext)
suspend fun foo() {
    bar() // (1) [AMBIGUOUS_CONTEXT_ARGUMENT] Multiple potential context arguments for 'CoroutineContext' in scope.
    baz() // (2)
}

context(_: CoroutineContext)
fun bar() {}

suspend fun baz() {}
```

There are two possible ways to read the code.

**The first way.** The explicit context parameter materializes the implicit context parameter.
It's essentially a way to give a name to the implicit parameter.

We reject this idea because of two reasons:
1. It looks weird

**The second way.** The explicit context parameter is the second

## Concerns

### Implicit context parameter binary signature inconsistency

Normally, an addition of a contextual parameter changes the function's binary signature.
Unfortunately, it's not the case with the implicit context parameter that the compiler adds for `suspend` functions according to this proposal.
We obviously not gonna change binary signature of existing `suspend` functions.

This inconsistency doesn't create any oddities or inconsistencies in the language itself, but only on the binary level.
The proposal is to acknowledge this binary inconsistency and live with it.

### Discoverability

The language feature might be too hard to discover, and unintuitive for inexperienced users.

The connection between `suspend` and context parameters is not something that comes at a glance.

### `CoroutineContext` becomes even more magical

Technically, the compiler is already aware about `kotlin.coroutines.Continuation` type because it performs Continuation-passing style transformation.
And since `kotlin.coroutines.Continuation` refers to `kotlin.coroutines.CoroutineContext`, the compiler transitivelly knows about `CoroutineContext`.

With this proposal, the special treatment for `CoroutineContext` from the compiler becomes even stronger, and it's not a good thing,
because we typically prefer to keep the core of the language as much decoupled from the stdlib as possible.

## Alternatives

No other alternatives to bridge the features crossed our minds.

There is an obvious alternative to not to bridge the features and keep everything as is.

## Dependencies

**(1)**. This proposal, in general, depends on [KEEP-367](./context-parameters.md).

**(2)**. This proposal depends on the fact that contextual parameters "fill in" neither extension, nor dispatch holes:

```kotlin
context(_: String)
fun foo() {
    length // red code
    functionWithExtensionHole() // red code
}

fun String.functionWithExtensionHole() {}
```

If not the decision not to "fill in" the extension and dispatch hole, this proposal would be a breaking change:

```kotlin
suspend fun CoroutineContext.bar() {
    baz() // [AMBIGUOUS_CONTEXT_ARGUMENT] Multiple potential context arguments for 'CoroutineContext' in scope.
}

suspend fun baz() {}
```

Thankfully, it's not the case.

## Release plans

The feature should come out together with Context parameters

## Dissecting `suspend` modifier

You can think of `suspend` modifier as of two features applied simulatniously:
1. An implicitly passed `CoroutineContext` (context parameter)
2. Continuation-passing style / state machine transformation

`suspend` modifier in Kotlin




```kotlin
suspend fun asyncComputation() {
    nonAsyncComputation()
}

context(context: CoroutineContext)
fun nonAsyncComputation() {
}
```

## Feature interaction with `expect` / `actual`

```kotlin
// Common
public expect interface MyCoroutineContext {
    public operator fun <E : Element> get(key: Key<E>): E?
    public interface Key<E : Element>
    public interface Element : MyCoroutineContext {
        public val key: Key<*>
        public override operator fun <E : Element> get(key: Key<E>): E?
    }
}

context(_: MyCoroutineContext)
suspend fun foo() {
}

// Platform
actual typealias MyCoroutineContext = kotlin.coroutines.CoroutineContext
```

## Mock example. `ScopedValues`-like API for `CoroutineContext`

## Change to stdlib

```kotlin
/**
 * Returns the context of the current coroutine.
 */
@SinceKotlin("1.3")
@Suppress("WRONG_MODIFIER_TARGET")
@InlineOnly
public suspend inline val coroutineContext: CoroutineContext
    get() {
        throw NotImplementedError("Implemented as intrinsic")
    }
```

Will change to:

```kotlin
/**
 * Returns the context of the current coroutine.
 */
@SinceKotlin("1.3")
@InlineOnly
context(context: CoroutineContext)
public inline val coroutineContext: CoroutineContext get() = context
```

## IDE integration

1. Add an intention that replaces `suspend` properties (they are illegal to declare) with properties that have the `CoroutineContext` context parameter.
2. Gutter icon.

## Breaking change analysis

## Related features in other languages

Swift - TaskLocal

Java. ThreadLocal, ScopedValues (JEP 506)

Kotlin. CoroutineContext, context parameters, CompositionLocal, @RestrictsSuspension

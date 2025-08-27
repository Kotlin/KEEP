# `CoroutineContext` context parameter in `suspend` functions

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**:
  Alejandro Serrano,
  Marat Akhin,
  Mikhail Zarechenskii,
  Pavel Kunyavskiy,
  Roman Venediktov
* **Issue:**
  [KT-15555 Support suspend get/set properties](https://youtrack.jetbrains.com/issue/KT-15555)
* **Discussion**: [#452](https://github.com/Kotlin/KEEP/discussions/452)
* **Status**: Public discussion

## Abstract

[KEEP-367](./KEEP-0367-context-parameters.md) introduces context parameters feature to Kotlin.

There are multiple ways on how you can think of context parameters.
One of them is that it's possible to pass parameters by position,
it's possible to pass parameters by name,
and, with context parameters, it's possible to pass parameters by context.

Turns out that Kotlin already has another existing feature that has the same property – `suspend` functions.
All `suspend` functions have an implicit `$completion: kotlin.coroutines.Continuation` parameter.
And while the `$completion` parameter itself isn't equivalent to context parameters since the continuations are not just passed around and are rather wrapped and transformed on every suspending call,
but the `kotlin.coroutines.Continuation`'s `context` property of type `kotlin.coroutines.CoroutineContext` **is** equivalent to context parameters.

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

A lot of existing Kotlin code that relies on coroutines already uses `suspend` functions for that purpose
(e.g., IntelliJ IDEA, Ktor server applications)

## Table of contents

- [The proposal](#the-proposal)
  - [`coroutineContext` stdlib property](#coroutinecontext-stdlib-property)
  - [Use case. CPU intensive cooperative cancellation](#use-case-cpu-intensive-cooperative-cancellation)
- [Technical details](#technical-details)
  - [`suspend` function with explicit `CoroutineContext` context parameter](#suspend-function-with-explicit-coroutinecontext-context-parameter)
  - [Declaration-site `CONFLICTING_OVERLOADS` and overridability](#declaration-site-conflicting_overloads-and-overridability)
  - [Overload resolution](#overload-resolution)
  - [Feature interaction with callable references](#feature-interaction-with-callable-references)
  - [`kotlin.synchronized` stdlib function](#kotlinsynchronized-stdlib-function)
- [Concerns](#concerns)
  - [Discoverability](#discoverability)
  - [`CoroutineContext` becomes even more magical](#coroutinecontext-becomes-even-more-magical)
- [Discarded idea. Interop with Compose](#discarded-idea-interop-with-compose)
- [Dependencies](#dependencies)
- [Illustrative example. `ScopedValues`-like API for `CoroutineContext`](#illustrative-example-scopedvalues-like-api-for-coroutinecontext)
- [IDE integration](#ide-integration)
- [Related features in other languages](#related-features-in-other-languages)
- [Alternatives](#alternatives)
  - [Discarded alternative. Treat all `suspend` functions as if they had an implicit `CoroutineContext` context parameter](#discarded-alternative-treat-all-suspend-functions-as-if-they-had-an-implicit-coroutinecontext-context-parameter)
  - [Discarded alternative. Instead of `CoroutineContext` expose `Continuation`](#discarded-alternative-instead-of-coroutinecontext-expose-continuation)
  - [Alternative. Just allow `suspend` properties](#alternative-just-allow-suspend-properties)

## The proposal

Goals:
1. Cover the use case of `suspend` properties without the downside of introducing `suspend` properties [KT-15555](https://youtrack.jetbrains.com/issue/KT-15555)
  (See below for the downside. Properties must be cheap. `suspend` properties are not cheap)
2. Change the implementation of `kotlin.coroutines.coroutineContext` stdlib property from using an internal compiler intrinsic to using a regular language feature – context parameters
3. Bridge `suspend` and context parameters features together, making the language to work as a whole

The proposal is to treat all `suspend` functions
as if their bodies were implicitly enclosed within `context(getContinuation_compiler_intrinsic().context) { /* the rest of the body... */ }` stdlib function:

```kotlin
suspend fun computation() = /* implicitly enclosed within */ context(getContinuation_compiler_intrinsic().context) {
    foo() // Green code
}

context(_: CoroutineContext)
fun foo() {}
```

### `coroutineContext` stdlib property

Currently, `suspend` properties are disallowed to encourage users to declare only such properties which are cheap to compute.
`suspend` properties are not cheap.
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
// @Suppress("WRONG_MODIFIER_TARGET") // The Suppress is no longer required
@InlineOnly
context(context: CoroutineContext)
public inline val coroutineContext: CoroutineContext get() = context



```
</td>
</tr>
</table>

Please note that the suggested change doesn't break binary compatibility since the property is `InlineOnly`.

We kill two birds with one stone:
1. It’s now possible to use properties with context parameters to retrieve data from a coroutine context,
  addressing one of the main cases of [KT-15555](https://youtrack.jetbrains.com/issue/KT-15555).
2. The magical `coroutineContext` compiler intrinsic becomes a proper language feature.

### Use case. CPU intensive cooperative cancellation

Another good fit for the language feature is cooperative cancellation in case of intensive CPU operations inside `suspend` functions.

```kotlin
suspend fun suspendComputation(number: Int) {
    if (number.isPrime()) { // Intensive CPU computation
        // ...
    } else {
        // ...
    }
}

fun Int.isPrime(): Boolean {
    if (this < 2) return false
    for (cur in 2L..Long.MAX_VALUE) {
        if (cur * cur > this) break
        if (this % cur == 0L) return false
    }
    return true
}
```

Non-zero number of users make a mistake by thinking
that checking for cancellation is a `suspend` operation - `coroutineContext.ensureActive()`.
In fact, only access to `coroutineContext` is a `suspend` operation here.
`ensureActive` is a non-`suspend` function.
Because of the confusion,
users may decide to mark their non-`suspend` functions as `suspend` just to be able to access `coroutineContext`,
which unnecessarily reduces their code performance characteristics.

This proposal doesn't directly address the problem.
If we wanted to do so,
we would need to make it possible to access `coroutineContext` from non-`suspend`, non-context-parameter functions,
which is a noticeably harder task.

But this proposal makes progress in the direction.
The example above can be trivially fixed by adding a context parameter,
and calling `coroutineContext.ensureActive()` like so:

```diff
     }
 }

+context(context: CoroutineContext)
 fun Int.isPrime(): Boolean {
     if (this < 2) return false
     for (cur in 2L..Long.MAX_VALUE) {
+        context.ensureActive()
         if (cur * cur > this) break
         if (this % cur == 0L) return false
     }
```

This proposal addresses the problem in two ways:
1. Since `coroutineContext` won't longer have the "suspend" gutter icon,
  users will be better aware that `ensureActive` is non-`suspend`.
2. To prevent users from making their functions unnecessarily `suspend`,
  [we propose adding an IDE inspection](#ide-integration).

> [!NOTE]
> We say "intensive CPU computation" instead of "blocking computation"
> because "blocking computation" may be confused with IO blocking operation.
>
> Blocking IO operations have a different mechanism for cooperative cancellation when run from coroutines -
> `kotlinx.coroutines.runInterruptible`

## Technical details

### `suspend` function with explicit `CoroutineContext` context parameter

The following example requires an explicit notice:

```kotlin
context(explicitContext: CoroutineContext)
suspend fun foo() {
    bar()
    val implicitContext = coroutineContext
    context(explicitContext) {
        bar()
        context(implicitContext) {
            bar()
        }
    }
}

context(_: CoroutineContext)
fun bar() {}
```

If we just apply the mental model from [The proposal section](#the-proposal),
we see that the implicitly injected context is located at a closer level,
which naturally gives us an answer on which `CoroutineContext` should be captured in which cases.

```kotlin
context(explicitContext: CoroutineContext)
suspend fun foo() = /* implicitly enclosed within */ context(getContinuation_compiler_intrinsic().context) {
    bar() // Prints "implicit context"
    val implicitContext = coroutineContext // Captures the implicit context
    context(explicitContext) {
        bar() // Prints "explicit context"
        context(implicitContext) {
            bar() // Prints "implicit context"
        }
    }
}

context(context: CoroutineContext)
fun bar(): Unit = println(context[ContextMarker]!!.marker)

class ContextMarker(val marker: String) : AbstractCoroutineContextElement(Companion) {
    companion object : CoroutineContext.Key<ContextMarker>
}

suspend fun main() {
    withContext(ContextMarker("implicit context")) { // coroutines:            kotlinx.coroutines.withContext
        context(ContextMarker("explicit context")) { // stdlib (experimental): kotlin.context
            foo()
        }
    }
}
```

As it can be seen, unless explicitly reintroduced via `context` stdlib function,
the explicit context parameter `context(explicitContext: CoroutineContext)` is shadowed.
That's why it's also proposed to yield a compiler warning that the explicit context parameter is shadowed by the `suspend`'s function own `CoroutineContext`.

### Declaration-site `CONFLICTING_OVERLOADS` and overridability

In the current Kotlin version, the following code produces `CONFLICTING_OVERLOADS` compilation error

```kotlin
suspend fun myFun() {} // Red code. CONFLICTING_OVERLOADS
fun myFun() {} // Red code. CONFLICTING_OVERLOADS
```

which raises a question if the following code should be a compilation error as well:

```kotlin
context(_: CoroutineContext) fun myFun() {}
suspend fun myFun() {}
```

The question above is equivalent to another question: can a function with the `CoroutineContext` context parameter override `suspend` function:

```kotlin
open class Base {
    open suspend fun foo() {}
}

class Foo : Base() {
    // Is it allowed?
    context(_: CoroutineContext) override fun foo() {} // (1)

    // Related issue: https://youtrack.jetbrains.com/issue/KT-15845
    // For this to work, we just need to generate the following bridge (similar to covariant override bridges on JVM):
    // override suspend fun foo() { // (2)
    //     with(coroutineContext) {
    //         foo() // (1)
    //     }
    // }
}
```

And yet another equivalent example with `expect`/`actual`:

```kotlin
// MODULE: common
expect class Foo {
    suspend fun foo()
}

// MODULE: platform
actual class Foo {
    // Is it allowed?
    actual context(_: CoroutineContext) fun foo() {}
    // Related issue: https://youtrack.jetbrains.com/issue/KT-71223
}
```

Basically, the question boils down to: from the overload resolution perspective,
is `context(_: CoroutineContext) fun` equivalent to `suspend fun`?

This proposal answers "no" to all the questions above:
1. No, `context(_: CoroutineContext) fun` and `suspend fun` don't produce `CONFLICTING_OVERLOADS` compilation error.
2. No, `context(_: CoroutineContext) fun` can't override `suspend fun`.
3. No, `context(_: CoroutineContext) fun` can't actualize `suspend fun`.
4. No, `context(_: CoroutineContext) fun` isn't equivalent to `suspend fun` from the overload resolution perspective.

We prefer to answer "no" because overridability is a very strong type of equivalence.
For example, the current Kotlin version doesn't allow dropping parameters when you override a function.

And just to add a point, if we answered "yes", it'd introduce non-transitive equivalence into overload resolution.
`suspend fun foo() {}` and `context(_: CoroutineContext) fun foo() {}` would be equivalent.
`suspend fun foo() {}` and `fun foo() {}` would be equivalent.
**But!** `context(_: CoroutineContext) fun foo() {}` and `fun foo() {}` would **not** be equivalent.

### Overload resolution

The behavior defined in [The proposal](#the-proposal) naturally answers _the overload resolution question_:

```kotlin
// FILE: regularFun.kt
package regularFun
fun regularVsSuspend() = Unit
fun regularVsContext() = Unit // #1

// FILE: suspendFun.kt
package suspendFun
suspend fun regularVsSuspend() = Unit
suspend fun suspendVsContext() = Unit // #2

// FILE: contextFun.kt
package contextFun
context(_: CoroutineContext) fun suspendVsContext() = Unit // #3
context(_: CoroutineContext) fun regularVsContext() = Unit // #4

// FILE: usage.kt
package usage
import regularFun.*
import suspendFun.*
import contextFun.*

suspend fun main() {
    regularVsSuspend() // Red code. OVERLOAD_RESOLUTION_AMBIGUITY (Already works this way in the current Kotlin)
    suspendVsContext() // Should resolve to #3 according to the current proposal
    regularVsContext() // Should resolve to #4 according to the current proposal
}
```

The motivation for `suspendVsContext` and `regularVsContext` to resolve to \#3 and to \#4 accordingly is the existing behavior:

```kotlin
fun foo() = Unit // #5
context(_: Int) fun foo() = Unit // #6

fun main() {
    context(1) {
        foo() // #6
    }
    foo() // #5
}
```

This place looks suspicious.
The overload resolution ambiguity would be a more natural behavior.
This is a potential subject to change in the context parameters proposal.

### Feature interaction with callable references

Similar to how this proposal allows calling functions with `CoroutineContext` context parameter from `suspend` functions,
we should allow it via callable references:

```kotlin
suspend fun suspendFun() {}
fun regularFun() {}
context(_: kotlin.coroutines.CoroutineContext) fun contextFun() {}

fun main() {
    val foo1: suspend () -> Unit = ::suspendFun // #1 Green code (Already works this way in the current Kotlin)
    val foo2: suspend () -> Unit = ::regularFun // #2 Green code (Already works this way in the current Kotlin)
    val foo3: suspend () -> Unit = ::contextFun // #3 Should it be green or red?
    // but!
    val foo4: kotlin.reflect.KSuspendFunction0<Unit> = ::suspendFun // #4 Green code (Already works this way in the current Kotlin)
    val foo5: kotlin.reflect.KSuspendFunction0<Unit> = ::regularFun // #5 Red code. INITIALIZER_TYPE_MISMATCH (Already works this way in the current Kotlin)
    val foo6: kotlin.reflect.KSuspendFunction0<Unit> = ::contextFun // #6 Should it be green or red? It should be red code, no questions. INITIALIZER_TYPE_MISMATCH
}
```

- Given that \#5 is red, \#6 should be obviously red.
- Since the context and `suspend` functions are different from the perspective of overload resolution ([see the section](#declaration-site-conflicting_overloads-and-overridability)),
  we propose to make \#3 red – `INITIALIZER_TYPE_MISMATCH`.

### `kotlin.synchronized` stdlib function

Suspend calls are forbidden inside `kotlin.synchronized` block.
There are good reasons to disallow this, suspending inside `synchronized` block is unsafe.

But `coroutineContext` property never suspends the coroutine, so `coroutineContext` should be allowed inside the `synchronized` block.

Interestingly, in the current compiler implementation, we forbid suspend functions inside the `synchronized` block,
but we don't forbid suspend properties calls.
It's unclear if it's a bug or an ad-hoc feature to support `coroutineContext` case.

```kotlin
import kotlin.coroutines.*

@Suppress("WRONG_MODIFIER_TARGET") suspend val suspendProperty: Int get() = 42
suspend fun suspendFunction() {}
suspend fun main() {
    synchronized(Any()) {
        suspendFunction() // Error. [SUSPENSION_POINT_INSIDE_CRITICAL_SECTION] The 'foo' suspension point is inside a critical section.
        coroutineContext[CoroutineName] // Green Code. Why?
        suspendProperty // Green Code. Why?
    }
}
```

Anyway, it doesn't matter since the proposal fixes this technical debt,
and when the proposal is implemented, we can reintroduce the compiler check for suspend properties.

## Concerns

### Discoverability

The language feature might be too hard to discover, and unintuitive for inexperienced users.
The connection between `suspend` and context parameters is not something that comes at a glance.

One of the suggestions is to add an IDE intention to ["fix" `suspend` properties](#ide-integration).

### `CoroutineContext` becomes even more magical

Technically, the compiler is already aware of `kotlin.coroutines.Continuation` type because it performs [CPS-transformation](https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html#continuation-passing-style).
And since `kotlin.coroutines.Continuation` refers to `kotlin.coroutines.CoroutineContext`, the compiler transitively knows about `CoroutineContext`.

With this proposal, the special treatment for `CoroutineContext` from the compiler becomes even stronger, and it's not a good thing,
because we typically prefer to keep the core of the language as much decoupled from the stdlib as possible.

## Discarded idea. Interop with Compose

Kotlin Compose framework provides a concept of `androidx.compose.runtime.CompositionLocal`
which is identical to `CoroutineContext` and context parameters.

Here is a side-by-side comparison.

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
// CompositionLocal
val userName: ProvidableCompositionLocal<String> = staticCompositionLocalOf { "" }

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "compose-desktop",
    ) {
        CompositionLocalProvider(userName provides "Kodee") {
            downDeepTheCallStack()
        }
    }
}

@Composable fun downDeepTheCallStack() {
    Text("Hello ${userName.current}!")
}
```
</td>
</tr>
</table>

`CompositionLocal.current` is implemented as follows:

```kotlin
@Stable
sealed class CompositionLocal<T> constructor(defaultFactory: () -> T) {
    // ...

    /**
     * Return the value provided by the nearest [CompositionLocalProvider] component that invokes, directly or
     * indirectly, the composable function that uses this property.
     *
     * @sample androidx.compose.runtime.samples.consumeCompositionLocal
     */
    @OptIn(InternalComposeApi::class)
    inline val current: T
        @ReadOnlyComposable
        @Composable
        get() = currentComposer.consume(this)
}

/**
 * TODO(lmr): provide documentation
 */
val currentComposer: androidx.compose.runtime.Composer
    @ReadOnlyComposable
    @Composable get() { throw NotImplementedError("Implemented as an intrinsic") }

/**
 * Composer is the interface that is targeted by the Compose Kotlin compiler plugin and used by
 * code generation helpers. It is highly recommended that direct calls these be avoided as the
 * runtime assumes that the calls are generated by the compiler and contain only a minimum amount
 * of state validation.
 */
sealed interface Composer {
    // ...
}
```

So the direct analogy of `kotlin.coroutines.CoroutineContext` is `androidx.compose.runtime.Composer`
but the `Composer` is clearly an internal API that isn't supposed to be used directly.
That's why we can't just say that all `@Composable` functions have an implicit `androidx.compose.runtime.Composer` context parameter.

_An alternative design_ for Compose could be:

```kotlin
@Composable
context(@Composable LocalColors: Colors)
fun Button() {
}
```

But unlike `CoroutineContext.Element`s which are bound by types, `CompositionLocal`s are bound by global variables - tokens.
That's why the alternative design is not possible either.

## Dependencies

**(1)** This proposal, in general, depends on [KEEP-367 Context parameters](./KEEP-0367-context-parameters.md).

**(2)** This proposal depends on the fact that contextual parameters _fill in_ neither extension, nor dispatch holes:

```kotlin
context(str: String)
fun foo() {
    length // red code
    functionWithExtensionHole() // red code
    str.length // green code
}

fun String.functionWithExtensionHole() {}
```

If not the decision, this proposal would be a behavioral change:

```kotlin
suspend fun CoroutineContext.bar() {
    baz()
}

fun CoroutineContext.baz() {}
```

Thankfully, it's not the case.
These examples are indicators of context parameters' good design.

## Illustrative example. `ScopedValues`-like API for `CoroutineContext`

Imagine we want to provide [`ScopedValues`-like API](https://openjdk.org/jeps/506) for `CoroutineContext` so that storing data in `CoroutineContext` is as easy as declaring global variables:

```kotlin
// User land
val userName = LocalValue<String>()
val userPassword = LocalValue<String>()

suspend fun main() {
    userName.withValue("Kodee") {
        userPassword.withValue("qwerty") {
            println(userName.get())
            println(userPassword.get())
        }
    }
}
```

The `LocalValue`'s implementation could look like:

```kotlin
// Library code
class LocalValue<T> : CoroutineContext.Key<LocalValue.MyElement<T>> {
    private class MyElement<T>(key: CoroutineContext.Key<*>, val value: T) : AbstractCoroutineContextElement(key)

    suspend fun <R> withValue(value: T, body: suspend CoroutineScope.() -> R): R =
        withContext(MyElement(this, value), body)

    suspend fun get(): T? = coroutineContext[this]?.value
}
```

The first thing that we don't like is `suspend fun get()`, we want to make it a property.
`suspend` properties are forbidden, but thanks to this proposal we can declare a property with context parameter.

```kotlin
// Library code
class LocalValue<T> : CoroutineContext.Key<LocalValue.MyElement<T>> {
    // ...
    context(context: CoroutineContext)
    val value: T?
        get() = context[this]?.value
}
```

Next, we would like to entirely avoid `.value` every time we want to access the `LocalValue`'s value.

```kotlin
// User land
val userName: String by LocalValue<String>()
val userPassword: String by LocalValue<String>()

suspend fun main() {
    ::userName.withValue("Kodee") {
        ::userPassword.withValue("qwerty") {
            println(userName) // Kodee
            println(userPassword) // qwerty
        }
    }
}
```

which this proposal doesn't cover and is a subject for future improvements
https://youtrack.jetbrains.com/issue/KT-77129 and https://youtrack.jetbrains.com/issue/KT-77128

## IDE integration

1. Add an intention that replaces `suspend` properties (they are illegal to declare) with properties that have the `CoroutineContext` context parameter.
  The intention should increase the feature's [discoverability](#discoverability).
2. Similar to how all call-sites of `suspend` functions have a [gutter icon](https://www.jetbrains.com/help/idea/settings-gutter-icons.html) in IDE,
  we should add a gutter icon for all call-sites of functions with context parameters [KTIJ-26653](https://youtrack.jetbrains.com/issue/KTIJ-26653).
3. Create an IDE inspection that checks that if `coroutineContext` is the only `suspend` operation in the `suspend` function,
  then suggest to replace `suspend` with `context(context: CoroutineContext)`.
  Use case: [CPU intensive operations in `suspend` functions](#use-case-cpu-intensive-cooperative-cancellation)

## Related features in other languages

**Swift.** [TaskLocal](https://developer.apple.com/documentation/swift/tasklocal)

**Java.** ThreadLocal, [ScopedValues](https://openjdk.org/jeps/506)

**Kotlin.**
CoroutineContext,
context parameters,
[CompositionLocal](#discarded-idea-interop-with-compose),
[@RestrictsSuspension](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.coroutines/-restricts-suspension/)

## Alternatives

### Discarded alternative. Treat all `suspend` functions as if they had an implicit `CoroutineContext` context parameter

In the current proposal, `suspend fun foo() {}` is treated as if its body is enclosed within `context` function:

```kotlin
suspend fun foo() = context(getContinuation_compiler_intrinsic().context /*intrinsic*/) {
}
```

A considered alternative was to treat all `suspend` functions as if all of them had an _implicit `CoroutineContext` context parameter_:

```kotlin
context(_: CoroutineContext) // implicit context paramter
suspend fun foo() {}
```

The alternative was discarded since it's generally more intrusive as it affects function signatures.

- Unlike the explicitly defined context parameter,
  the implicitly introduced context parameter wouldn't appear in the function binary signature,
  which would create an inconsistency.
- Affecting function signatures carries the risk of unintentionally changing overload resolution

### Discarded alternative. Instead of `CoroutineContext` expose `Continuation`

Instead of exposing `CoroutineContext`, we could expose `kotlin.coroutines.Continuation`.
The alternative is discarded because:

- `Continuation` context parameter doesn't make as much sense as `CoroutineContext` context parameter
- It'd keep [KT-15555 Support suspend get/set properties](https://youtrack.jetbrains.com/issue/KT-15555) issue unresolved
- Unconstrained access to continuation is unsafe.
  Raw continuations are part of the [CPS-transformation](https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html#continuation-passing-style).
  If users want to access a continuation, they must use `suspendCoroutine` stdlib function,
  which gives constrained access to `kotlin.coroutines.SafeContinuation`.

### Alternative. Just allow `suspend` properties

Since the main goal of this proposal is to address [KT-15555 Support suspend get/set properties](https://youtrack.jetbrains.com/issue/KT-15555),
we could just allow `suspend` properties.

As previously mentioned, we want to keep properties cheap to compute.
`suspend` properties are generally not cheap.

But we don't discard the idea of `suspend` properties completely.
It's already totally possible to create non-cheap properties even without `suspend`,
some may say that we have already lost this battle.

We would like to notice that this proposal doesn't close the door for `suspend` properties in the future,
shall we decide to allow them.

Interestingly, this proposal has another advantage over suspend properties.
If you use `suspend` only to access `CoroutineContext`,
the context parameter version is more performant than `suspend` version since it doesn't require [CPS-transformation](https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html#continuation-passing-style) on the call-site.

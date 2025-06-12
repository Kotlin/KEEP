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

There are multiple ways on how you can look at context parameters.
One of them is that context parameters are a _better way_ to declare global variables.
It's common knowledge that we should prefer function parameters over global variables –
it makes our programs easier to test and parallelize.
But still, programmers tend to use global variables because passing parameters manually might be too tedious
and might require too much ceremony in some cases.

Context parameters partially cover this use case.
They are not as tedious to pass around as regular parameters,
and they don't have the downsides of the global variables.

Turns out that Kotlin already has another existing feature that covers the same use case in a similar way – `suspend` functions.
All `suspend` functions have an implicit `$completion: kotlin.coroutines.Continuation` parameter.
And while the `$completion` parameter itself isn't equivalent to context parameters since the continuations are not just passed around and are rather wrapped on every suspending call,
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
  - [`suspend` function with explicit `CoroutineContext` context parameter](#suspend-function-with-explicit-coroutinecontext-context-parameter)
  - [Feature interaction with callable references](#feature-interaction-with-callable-references)
  - [Declaration-site `CONFLICTING_OVERLOADS`](#declaration-site-conflicting_overloads)
  - [Overload resolution](#overload-resolution)
  - [`expect`/`actual` feature interaction](#expectactual-feature-interaction)
- [Concerns](#concerns)
  - [Implicit context parameter binary signature inconsistency](#implicit-context-parameter-binary-signature-inconsistency)
  - [Discoverability](#discoverability)
  - [`CoroutineContext` becomes even more magical](#coroutinecontext-becomes-even-more-magical)
  - [From the overload resolution perspective, `context fun` and `suspend fun` equivalence is not transitive](#from-the-overload-resolution-perspective-context-fun-and-suspend-fun-equivalence-is-not-transitive)
- [Discarded idea. Interop with Compose](#discarded-idea-interop-with-compose)
- [Dependencies](#dependencies)
- [Mock example. `ScopedValues`-like API for `CoroutineContext`](#mock-example-scopedvalues-like-api-for-coroutinecontext)
- [IDE integration](#ide-integration)
- [Related features in other languages](#related-features-in-other-languages)
- [Alternatives](#alternatives)

## The proposal

Goals:
1. Bridge `suspend` and context parameters features together
2. Change the implementation of `kotlin.coroutines.coroutineContext` stdlib property from using an internal compiler intrinsic to using a regular language feature – context parameters
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

Unlike explicitly declared context parameters, `suspend` function implicit context parameter doesn't change function's binary signature.

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
1. We can close [KT-15555](https://youtrack.jetbrains.com/issue/KT-15555),
  and ask users to prefer properties with context parameters if all they need is an access to `CoroutineContext`.
2. The magical `coroutineContext` compiler intrinsic becomes a proper language feature.

### `suspend` function with explicit `CoroutineContext` context parameter

The following example requires an explicit notice:

```kotlin
context(_: CoroutineContext)
suspend fun foo() {
    bar()
    baz()
}

context(_: CoroutineContext)
fun bar() {}

suspend fun baz() {}
```

There are two possible ways to reason about the code.

**The first way.**
The explicit context parameter _explicitializes_ (it's a made up word) the implicit context parameter.
It's essentially a way to give a name to the implicit parameter.

Such an approach raises a question whether the binary signature of the `suspend` function changes once its implicit `CoroutineContext` context parameter is _explicitialized_.

If yes, then the approach cannot be used to "just give a name to the implicit parameter".

If no, then it's puzzling that the addition of context parameter specifically of type `CorouinteContext`, specifically to `suspend` functions doesn't change their signatures.
Besides, what is the binary signature of the following function `context(_: CoroutineContext, _: Int, _: CoroutineContext) fun foo() {}`?

And another question that the approach raises is interaction with `expect`/`actual`:

```kotlin
// MODULE: common
expect class MyCoroutineContext

context(_: MyCoroutineContext)
suspend fun foo() {}

// MODULE: platform
actual typealias MyCoroutineContext = kotlin.coroutines.CoroutineContext
```

It's no longer possible to say what is the function's binary signature shape just by looking at its definition in the common module.
And even worse – the shape may differ from platform to platform.

**The second way.**
The explicit context parameter adds an explicit context parameter alongside the already existing implicit parameter.

It means that the example from the above results in a compilation error with `AMBIGUOUS_CONTEXT_ARGUMENT` message:

```kotlin
context(_: CoroutineContext)
suspend fun foo() {
    bar() // [AMBIGUOUS_CONTEXT_ARGUMENT] Multiple potential context arguments for 'CoroutineContext' in scope.
    baz() // [AMBIGUOUS_CONTEXT_ARGUMENT] Multiple potential context arguments for 'CoroutineContext' in scope.
}

context(_: CoroutineContext)
fun bar() {}

suspend fun baz() {}
```

**The proposal** is to pick _the second way_ since it's less questionable.

The reasonings above give us a hint that if we want to make it possible to explicitialize the implicit context parameter,
then it should be some special syntax:

```kotlin
context(suspend _: CoroutineContext)
suspend fun foo() {}
```

For now, we won't provide any syntax since the use case is already covered by `kotlin.coroutines.coroutineContext` and [`contextOf` function](./context-parameters.md#standard-library-support)

### Feature interaction with callable references

Similar to how [it's not possible](#suspend-function-with-explicit-coroutinecontext-context-parameter) to explicitialize implicit context receiver in regular code for `suspend` functions,
it's still not possible to do that via callable references:

```kotlin
suspend fun foo() {}

fun main() {
    val bar: suspend context(_: CoroutineContext) () -> Unit = ::foo // Red code. [INITIALIZER_TYPE_MISMATCH]
}
```

Similar to how this proposal allows calling functions with `CoroutineContext` context parameter from `suspend` functions,
we should allow it via callable references:

```kotlin
suspend fun suspendFun() {}
fun regularFun() {}
context(_: kotlin.coroutines.CoroutineContext) fun contextFun() {}

fun main() {
    val foo1: suspend () -> Unit = ::suspendFun // Green code
    val foo2: suspend () -> Unit = ::regularFun // Green code
    val foo3: suspend () -> Unit = ::contextFun // Green code
    // but!
    val foo4: kotlin.reflect.KSuspendFunction0<Unit> = ::suspendFun // Green code
    val foo5: kotlin.reflect.KSuspendFunction0<Unit> = ::regularFun // Red code. INITIALIZER_TYPE_MISMATCH
    val foo6: kotlin.reflect.KSuspendFunction0<Unit> = ::contextFun // Red code. INITIALIZER_TYPE_MISMATCH
}
```

### Declaration-site `CONFLICTING_OVERLOADS`

The following code produces `CONFLICTING_OVERLOADS` compilation error

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

This proposal answers "yes" to all the questions above:
1. Yes, `context(_: CoroutineContext) fun` and `suspend fun` produce `CONFLICTING_OVERLOADS` compilation error.
2. Yes, `context(_: CoroutineContext) fun` can override `suspend fun`.
3. Yes, `context(_: CoroutineContext) fun` can actualize `suspend fun`.
4. Yes, `context(_: CoroutineContext) fun` is equivalent to `suspend fun` from the overload resolution perspective.

But we would like to highlight that the following code stays green:

```kotlin
context(_: CoroutineContext) fun myFun() {}
fun myFun() {}
```

which raises [the non-transitive equivalence concern](#from-the-overload-resolution-perspective-context-fun-and-suspend-fun-equivalence-is-not-transitive).

### Overload resolution

The behavior defined in [the previous section](#declaration-site-conflicting_overloads) naturally extends and answers _the overload resolution question_:

```kotlin
// FILE: regularFun.kt
package regularFun
fun regularVsSuspend() = Unit
fun regularVsContext() = Unit // (1)

// FILE: suspendFun.kt
package suspendFun
suspend fun regularVsSuspend() = Unit
suspend fun suspendVsContext() = Unit

// FILE: contextFun.kt
package contextFun
context(_: CoroutineContext) fun suspendVsContext() = Unit
context(_: CoroutineContext) fun regularVsContext() = Unit // (2)

// FILE: usage.kt
package usage

import regularFun.*
import suspendFun.*
import contextFun.*

suspend fun main() {
    regularVsSuspend() // Red code. OVERLOAD_RESOLUTION_AMBIGUITY (in existing Kotlin)
    suspendVsContext() // Red code. OVERLOAD_RESOLUTION_AMBIGUITY
    regularVsContext() // Green code. (2)
}
```

The motivation for `regularVsContext` to resolve to `(2)` is the existing behavior:

```kotlin
fun foo() = Unit // (3)
context(_: Int) fun foo() = Unit // (4)

fun main() {
    with(1) {
        foo() // (4)
    }
    foo() // (3)
}
```

### `expect`/`actual` feature interaction

We would like to explicitly notice that the case mentioned above:

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

should be allowed only when `suspend fun foo` is **effectively `final`** in the `expect` class.

## Concerns

### Implicit context parameter binary signature inconsistency

Normally, the addition of a contextual parameter changes the function's binary signature.
Unfortunately, it's not the case with the _implicit_ context parameter that the compiler adds for `suspend` functions according to this proposal.
We are not going to change binary signature of existing `suspend` functions.

This inconsistency creates an inconsistency between implicit and explicit context parameters.
This inconsistency doesn't create any oddities or inconsistencies in the language itself, but only on the binary level.
The proposal is to acknowledge this binary inconsistency and live with it.

### Discoverability

The language feature might be too hard to discover, and unintuitive for inexperienced users.
The connection between `suspend` and context parameters is not something that comes at a glance.

One of the suggestions is to add an IDE intention to ["fix" `suspend` properties](#ide-integration).

### `CoroutineContext` becomes even more magical

Technically, the compiler is already aware of `kotlin.coroutines.Continuation` type because it performs Continuation-passing style transformation.
And since `kotlin.coroutines.Continuation` refers to `kotlin.coroutines.CoroutineContext`, the compiler transitively knows about `CoroutineContext`.

With this proposal, the special treatment for `CoroutineContext` from the compiler becomes even stronger, and it's not a good thing,
because we typically prefer to keep the core of the language as much decoupled from the stdlib as possible.

### From the overload resolution perspective, `context fun` and `suspend fun` equivalence is not transitive

Pleas read [Overload resolution section](#overload-resolution) before reading this one.

From the overload resolution perspective,
1. `suspend fun foo() {}` and `context(_: CoroutineContext) fun foo() {}` are equivalent
2. `suspend fun foo() {}` and `fun foo() {}` are equivalent
3. **But!** `context(_: CoroutineContext) fun foo() {}` and `fun foo() {}` are not equivalent

It's not the first time when equivalence is not transitive in Kotlin (remember [flexible types](https://kotlinlang.org/spec/type-system.html#flexible-types)),
and we didn't find practical examples where it would break anything, so the proposal is to just live with it.

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

**(1)**. This proposal, in general, depends on [KEEP-367 Context parameters](./context-parameters.md).

**(2)**. This proposal depends on the fact that contextual parameters _fill in_ neither extension, nor dispatch holes:

```kotlin
context(str: String)
fun foo() {
    length // red code
    functionWithExtensionHole() // red code
    str.length // green code
}

fun String.functionWithExtensionHole() {}
```

If not the decision to not fill in the extension and dispatch hole, this proposal would be a breaking change:

```kotlin
suspend fun CoroutineContext.bar() {
    baz() // [AMBIGUOUS_CONTEXT_ARGUMENT] Multiple potential context arguments for 'CoroutineContext' in scope.
}

suspend fun baz() {}
```

Thankfully, it's not the case.
These examples are indicators of context parameters' good design.

## Mock example. `ScopedValues`-like API for `CoroutineContext`

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
    val value: T? get() = context[this]?.value
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

## Related features in other languages

todo

Swift. TaskLocal

Java. ThreadLocal, ScopedValues (JEP 506)

Kotlin. CoroutineContext, context parameters, CompositionLocal, @RestrictsSuspension

## Alternatives

No other alternatives to bridge the features crossed our minds.

There is an obvious alternative to not bridge the features and keep everything as it is.

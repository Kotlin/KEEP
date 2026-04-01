# Typed Delegate Access

- **Type**: Design Proposal
- **Author**: Roman Venediktov
- **Contributors**: Michail Zarečenskij
- **Status**: Public discussion
- **Discussion**: [#468](https://github.com/Kotlin/KEEP/discussions/468)
- **YouTrack Issue**: [KT-30631](https://youtrack.jetbrains.com/issue/KT-30631)

## Abstract

This proposal describes a way to access a typed value of a delegate within the private scope:
```kotlin
class C {
  val dbConnection by lazy { connectToDb() }

  fun close() {
    // Access to the backing property delegate of type Lazy<DbConnection>  
    if (::dbConnection.isInitialized()) { 
      dbConnection.close()
    }
  }
}
```

## Table of contents

* [Motivation](#motivation)
  * [Expose wrapper API](#expose-wrapper-api)
  * [Potential unification of `lateinit` and delegated properties](#potential-unification-of-lateinit-and-delegated-properties)
* [Proposal](#proposal)
* [Details](#details)
  * [Requirements](#requirements)
  * [Behavior in inline functions](#behavior-in-inline-functions)
  * [Hierarchy of `KDelegated`](#hierarchy-of-kdelegated)
  * [Additional extension functions](#additional-extension-functions)
  * [Inlining optimizations](#inlining-optimizations)
  * [Behavior in different scopes](#behavior-in-different-scopes)

## Motivation

### Expose wrapper API

Delegation is often used to hide wrapper logic when the wrapper mainly exposes a single property.
It lets you use `user` instead of repetitive `user.value`, while still preserving behaviors like caching, lazy
initialization, dependency injection, or observability.

However, delegates in Kotlin also hide the API of the wrapped object. The most straightforward example is `isInitialized()` of the 
`kotlin.Lazy` delegate. Today, if one wants to get access to the API of the wrapped object, it's required to store delegate separately
or use reflection:
```kotlin
private val _dbConnection = lazy { connectToDb() }
val dbConnection by _dbConnection
if (_dbConnection.isInitialized()) {
    dbConnection.close()
}
```

This pattern shares the same drawbacks as backing fields: it requires additional names and adds boilerplate.

Other examples of this pattern include:

- An observable value that must be registered once and accessed many times.
- A caching wrapper that needs to be cleared at specific moments but is usually accessed via a simple value property.
- A lazy value that must be eagerly initialized under certain conditions.

Note that, by default, in all these cases the wrapper API should be available only to the holder of such a delegated
property.

### Potential unification of `lateinit` and delegated properties

The second part of the story concerns the `lateinit var` feature. We acknowledge that in most cases, `lateinit var` follows
a contract where a property is assigned once and never changed afterward. We would also like to extend this feature to
work with primitive types. Additionally, to support dependency injection and multithreaded scenarios, we need a setter
and synchronization logic.

One possible solution is to unify `lateinit var` with delegated properties and introduce an `assignOnce` delegate. Such a
delegate could cover lateinit use cases without being restricted to non-primitive types. However, `lateinit` currently
relies on an ad-hoc mechanism to check whether a property has been [initialized](https://github.com/JetBrains/kotlin/blob/2.3.0/libraries/stdlib/src/kotlin/util/Lateinit.kt#L22). 
To support this with delegates, we would need to expose a similar API to users. Conceptually, this is the same situation as `isInitialized()` in
`kotlin.Lazy` but from a different angle.

Note that the specific details of this unification are not yet clear and are out of scope for this proposal.

## Proposal

The proposal is to resolve `::property` differently for delegated properties, allowing access to the typed value of a delegate:

```kotlin
class C {
    val dbConnection by lazy { connectToDb() }

    fun close() {
        ::dbConnection.delegate.also {
            if (it.isInitialized()) {
                it.value.close()
            }
        }
    }
}
```

The first step to achieve it is to introduce an additional set of types `KDelegatedN`, 
which are parameterized by the type of the delegate and declares the `getDelegate()` method with the proper return type. 
For example, for `KDelegated0`:

```kotlin
interface KDelegated<out Delegate>

interface KDelegated0<out Delegate> : KDelegated<Delegate> {
    fun getDelegate(): Delegate
}
```

The second step is to provide a smart-cast for `::property` to `KDelegatedN` for delegated properties.
To prevent leaking implementation details, this smart-cast will be available only inside the private scope of the property.
This is similar to the behavior for [Explicit Backing Fields](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0430-explicit-backing-fields.md), where private type is also accessible only inside the private scope of the property.
Another analogue is a mutable property with a private setter.
It is resolved to `KMutablePropertyN` inside the private scope and to `KPropertyN` outside.
Moreover, the anonymous class generated in the outer scope, does not inherit `KMutablePropertyN`, so it is not possible to access the setter even with the downcast.
See the [Behavior in different scopes](#behavior-in-different-scopes) section for more details.

> Note:
> Hierarchy for KDelegatedN does not inherit from KProperty as it was done in the initial version of this proposal.
> This is done to prevent explosion of interfaces in the hierarchy.
> While the interfaces resulting from inheritance look manageable today:
> 
> ```kotlin
> interface KProperty // with overrides for 0/1/2
> interface KMutableProperty : KProperty // with overrides for 0/1/2
> interface KDelegatedProperty : KProperty // with overrides for 0/1/2
> interface KMutableDelegatedProperty : KMutableProperty, KDelegatedProperty // with overrides for 0/1/2
> ```
> 
> In the future we might implement other kinds of properties, requiring different interfaces, and each of them would have to have a `Delegated` counterpart.
> This not only would end up in too many different interfaces, but also significantly decreases the readability of them.

## Details

### Requirements

New smart-cast to `KDelegatedN` does not work for non-final delegated properties as they might be overridden with other delegates or just common properties.

### Behavior in inline functions

New smart-cast to `KDelegatedN` is disabled inside Public-API inline functions (with `public`, `protected` and `@PublishedApi internal` visibility).
This is required to prevent leaking implementation details.
This behavior is the same as for Explicit Backing Fields.

### Hierarchy of `KDelegated`

The proposed hierarchy of `KDelegated` is as follows:

```kotlin
interface KDelegated<out Delegate>

interface KDelegated0<out Delegate> : KDelegated<Delegate> {
    fun getDelegate(): Delegate
}

interface KDelegated1<T, out Delegate> : KDelegated<Delegate> {
    fun getDelegate(receiver: T): Delegate
}

interface KDelegated2<D, E, out Delegate> : KDelegated<Delegate> {
    fun getDelegate(receiver1: D, receiver2: E): Delegate
}
```

The `getDelegate` method is defined as a method rather than a property, to align with the corresponding methods in `KPropertyN` interfaces.
On the use site, with `KPropertyN` as the main type and a smart-cast to `KDelegatedN`, the `getDelegate` method will be typed as an intersection override, effectively narrowing its return type from `Any?` to the concrete `Delegate` type taken from `KDelegatedN`.

The `getDelegate` method is currently available only for the JVM platform.
The current proposal includes the addition of this method to the `KPropertyN` interfaces on other platforms.

### Additional extension functions

To simplify the usages of the new API, the following additional extension functions are proposed:

```kotlin
// Existing extensions:

/**
 * Returns the instance of a delegated **extension property**, or `null` if this property is not delegated.
 * Throws an exception if this is not an extension property.
 */
fun KProperty1<*, *>.getExtensionDelegate(): Any?

/**
 * Returns the instance of a delegated **member extension property**, or `null` if this property is not delegated.
 * Throws an exception if this is not an extension property.
 *
 * @param receiver the instance of the class used to retrieve the value of the property delegate.
 */
fun <D> KProperty2<D, *, *>.getExtensionDelegate(receiver: D): Any?

// New extensions:

fun <Delegate> KDelegated1<*, Delegate>.getExtensionDelegate(): Delegate

fun <D, Delegate> KDelegated2<D, *, Delegate>.getExtensionDelegate(receiver: D): Delegate

@InlineOnly
inline val <Delegate> KDelegated0<Delegate>.delegate: Delegate
    get() = getDelegate()

@InlineOnly
inline val <T> KDelegated0<Lazy<T>>.isInitialized: Boolean
    get() = delegate.isInitialized()
```

### Inlining optimizations

Generation of an additional class for `KDelegatedN` just to access the delegate might be an undesired performance overhead.
To overcome this, the compiler will try to inline these accesses if they occur on a statically known property in the scope of one function (after inlining).
And if the property reference is not used for anything else, it will be eliminated.

### Behavior in different scopes

The proposed behavior in different cases of reflection is the same as for a property with a private setter.
More precisely:

```kotlin
class C {
    val prop by lazy { 42 }

    fun foo1() {
        val tmp = ::prop // KProperty0<Int> & KDelegated0<Lazy<Int>>
        val tmpDel: Lazy<Int> = tmp.getDelegate() // ok
    }

    fun foo2(other: C) {
        val tmp = other::prop // KProperty0<Int> & KDelegated0<Lazy<Int>>
        val tmpDel: Lazy<Int> = tmp.getDelegate() // ok
    }

    fun foo3() {
        val tmp = C::prop // KProperty1<C, Int> & KDelegated1<C, Lazy<Int>>
        val tmpDel: Lazy<Int> = tmp.getDelegate(C()) // ok
    }

    fun leak(): KDelegated0<Lazy<Int>> = ::prop
}

fun C.leakFoo() {
    val tmp: Lazy<Int> = leak().getDelegate() // ok
}

fun C.externalFoo1() {
    val tmp: KProperty0<Int> = ::prop
    val tmpDel: Any? = tmp.getDelegate() // JVM: IllegalAccessException, ok after `tmp.isAccessible = true`; Other platforms: ok
    tmp as KDelegated0<Int, Lazy<Int>> // ClassCastException
}

fun C.externalFoo2() {
    val tmp: KProperty1<C, Int> = C::prop
    val tmpDel: Any? = tmp.getDelegate(this) // JVM: IllegalAccessException, ok after `tmp.isAccessible = true`; Other platforms: ok
    tmp as KDelegated1<C, Int, Lazy<Int>> // ClassCastException
}

fun reflectionFoo() {
    val tmp: KProperty1<C, *> = C::class.declaredMemberProperties.first() // JVM only
    @Suppress("UNCHECKED_CAST")
    tmp as KDelegated1<C, Lazy<Int>> // ok
    val tmpDel: Lazy<Int> = tmp.getDelegate(C()) // JVM: IllegalAccessException, ok after `tmp.isAccessible = true`; Other platforms: ok
}
```

This behavior is going to be achieved with the following compilation strategy:

- For property references inside the class, an anonymous class is generated as it is now. 
  The only change is that this class will have an additional superinterface, `KDelegatedN`, and it will have `getDelegate` method overridden to prevent `IllegalAccessException`.
- For property references outside the class, the behavior does not change.
- For reflection (including special operator functions `getValue`, `setValue` and `provideDelegate`), the only difference is an additional superinterface of the returned value.

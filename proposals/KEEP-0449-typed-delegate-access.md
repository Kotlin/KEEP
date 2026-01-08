# Typed Delegate Access

- **Type**: Design Proposal
- **Author**: Roman Venediktov
- **Contributors**: Michail Zarečenskij
- **Status**: Public discussion
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

The proposal is to resolve `::property` differently for delegated properties, allowing accessing the typed value of a delegate:

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

The first step to achieve it is to introduce an additional set of types `KDelegatedPropertyN` and `KMutableDelegatedPropertyN`, 
which are parametrized by the type of the delegate and overrides the `getDelegate()` method with the proper return type. 
For example, for `KDelegatedProperty0`:

```kotlin
interface KDelegatedProperty0<out V, out Delegate> 
    : KProperty0<V>, KDelegatedProperty<Delegate> {
    public override fun getDelegate(): Delegate
}
```

The second step is to resolve `::property` to `KDelegatedPropertyN` for delegated properties.
To prevent leaking implementation details, this new resolution will be available only inside the private scope of the property.
This is similar to the resolution for [Explicit Backing Fields](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0430-explicit-backing-fields.md), where private type is also accessible only inside the private scope of the property.
Another analogue is a mutable property with a private setter.
It is resolved to `KMutablePropertyN` inside the private scope and to `KPropertyN` outside.
Moreover, the anonymous class generated in the outer scope, does not inherit `KMutablePropertyN`, so it is not possible to access the setter even with the downcast.
See the [Behavior in different scopes](#behavior-in-different-scopes) section for more details.

## Details

### Requirements

The new resolution does not work for non-final delegated properties as they might be overridden with other delegates or just common properties.

### Resolution in inline functions

The resolution to `KDelegatedPropertyN` is disabled inside Public-API inline functions (with `public`, `protected` and `@PublishedApi internal` visibility).
This is required to prevent leaking implementation details.
This behavior is the same as for Explicit Backing Fields.

### New hierarchy of `KProperty`

The proposed new hierarchy of `KProperty` is as follows:

```kotlin
// Existing interfaces:

public interface KProperty<out V> : KCallable<V>

public interface KMutableProperty<V> : KProperty<V>

public interface KProperty0<out V> : KProperty<V>, () -> V {
    public fun getDelegate(): Any?
}

public interface KMutableProperty0<V> : KProperty0<V>, KMutableProperty<V>

public interface KProperty1<T, out V> : KProperty<V>, (T) -> V {
    public fun getDelegate(receiver: T): Any?
}

public interface KMutableProperty1<T, V> 
    : KProperty1<T, V>, KMutableProperty<V>

public interface KProperty2<D, E, out V> : KProperty<V>, (D, E) -> V {
    public fun getDelegate(receiver1: D, receiver2: E): Any?
}

public interface KMutableProperty2<D, E, V> 
    : KProperty2<D, E, V>, KMutableProperty<V>

// New interfaces:

public interface KDelegatedProperty<out V, out Delegate> : KProperty<V>

public interface KDelegatedProperty0<out V, out Delegate> 
    : KProperty0<V>, KDelegatedProperty<V, Delegate> {
    public override fun getDelegate(): Delegate
}

public interface KMutableDelegatedProperty0<V, out Delegate> 
    : KDelegatedProperty0<V, Delegate>, KMutableProperty0<V>

public interface KDelegatedProperty1<T, out V, out Delegate> 
    : KProperty1<T, V>, KDelegatedProperty<V, Delegate> {
    public override fun getDelegate(receiver: T): Delegate
}

public interface KMutableDelegatedProperty1<T, V, out Delegate> 
    : KDelegatedProperty1<T, V, Delegate>, KMutableProperty1<T, V>

public interface KDelegatedProperty2<D, E, out V, out Delegate> 
    : KProperty2<D, E, V>, KDelegatedProperty<V, Delegate> {
    public override fun getDelegate(receiver1: D, receiver2: E): Delegate
}

public interface KMutableDelegatedProperty2<D, E, V, out Delegate> 
    : KDelegatedProperty2<D, E, V, Delegate>, KMutableProperty2<D, E, V>
```

The `getDelegate` method is currently available only for the JVM platform. 
The current proposal includes the addition of this method to the `KPropertyN` interfaces on other platforms

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

fun <Delegate> KDelegatedProperty1<*, *, Delegate>.getExtensionDelegate(): Delegate

fun <D, Delegate> KDelegatedProperty2<D, *, *, Delegate>.getExtensionDelegate(receiver: D): Delegate

@InlineOnly
inline val <Delegate> KDelegatedProperty0<*, Delegate>.delegate: Delegate
    get() = getDelegate()

@InlineOnly
inline val <T> KDelegatedProperty0<T, Lazy<T>>.isInitialized: Boolean
    get() = delegate.isInitialized()

```

### Inlining optimizations

Generation of an additional class for `KDelegatedPropertyN` just to access the delegate might be an undesired performance overhead.
To overcome this, the compiler will try to inline these accesses if they occur on a statically known property in the scope of one function (after inlining).
And if the property reference is not used for anything else, it will be eliminated.

### Behavior in different scopes

The proposed behavior in different cases of reflection is the same as for property with private setter. 
More precisely:

```kotlin
class C {
    val prop by lazy { 42 }

    fun foo1() {
        val tmp = ::prop // KDelegatedProperty0<Int, Lazy<Int>>
        val tmpDel: Lazy<Int> = tmp.getDelegate() // ok
    }

    fun foo2(other: C) {
        val tmp = other::prop // KDelegatedProperty0<Int, Lazy<Int>>
        val tmpDel: Lazy<Int> = tmp.getDelegate() // ok
    }

    fun foo3() {
        val tmp = C::prop // KDelegatedProperty1<C, Int, Lazy<Int>>
        val tmpDel: Lazy<Int> = tmp.getDelegate(C()) // ok
    }

    fun leak(): KDelegatedProperty0<Int, Lazy<Int>> = ::prop
}

fun C.leakFoo() {
    val tmp: Lazy<Int> = leak().getDelegate() // ok
}

fun C.externalFoo1() {
    val tmp: KProperty0<Int> = ::prop
    val tmpDel: Any? = tmp.getDelegate() // JVM: IllegalAccessException, ok after `tmp.isAccessible = true`; Other platforms: ok
    tmp as KDelegatedProperty0<Int, Lazy<Int>> // ClassCastException
}

fun C.externalFoo2() {
    val tmp: KProperty1<C, Int> = C::prop
    val tmpDel: Any? = tmp.getDelegate(this) // JVM: IllegalAccessException, ok after `tmp.isAccessible = true`; Other platforms: ok
    tmp as KDelegatedProperty1<C, Int, Lazy<Int>> // ClassCastException
}

fun reflectionFoo() {
    val tmp: KProperty1<C, *> = C::class.declaredMemberProperties.first() // JVM only
    @Suppress("UNCHECKED_CAST")
    tmp as KDelegatedProperty1<C, Int, Lazy<Int>> // ok
    val tmpDel: Lazy<Int> = tmp.getDelegate(C()) // JVM: IllegalAccessException, ok after `tmp.isAccessible = true`; Other platforms: ok
}
```

This behavior is going to be achieved with the following compilation strategy:

- For property references inside the class, an anonymous class is generated as it is now. 
  The only change is that this class will have a more precise supertype, and it will have `getDelegate` method overridden to prevent `IllegalAccessException`.
- For property references outside the class, the behavior does not change.
- For reflection (including special operator functions `getValue`, `setValue` and `provideDelegate`), the only difference is a more precise supertype of the returned value.

# lateinit val

* **Type**: Design Proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, Faiz Ilham Muhammad
* **Discussion**: [KEEP-0452](https://github.com/Kotlin/KEEP/discussions/471)
* **Status**: Public Discussion

# Abstract

We propose to introduce `lateinit val` declarations to Kotlin
to provide first-class support for properties
with delayed initialization and assign-once semantics.

`lateinit val` bridges the gap between `lateinit var` and `val`:
it allows late initialization while preventing reassignment
and enabling smartcasts.
It is compiled to delegation to the thread-safe `AssignOnce` delegate
provided by the standard library.

The `AssignOnce` delegate can also be used directly
for cases where customization (e.g., thread-safety mode) is desired,
though as a regular delegated property it does not benefit from smartcasts.

# Table of Contents

<!-- TOC -->
* [Abstract](#abstract)
* [Motivation](#motivation)
* [Goals](#goals)
* [Intended Semantics](#intended-semantics)
* [Design](#design)
  * [`AssignOnce` Delegate](#assignonce-delegate)
  * [`lateinit val` Declaration](#lateinit-val-declaration)
    * [Compilation Strategy](#compilation-strategy)
* [Interaction with Other Features](#interaction-with-other-features)
  * [Annotations](#annotations)
  * [`isInitialized`](#isinitialized)
  * [Serialization](#serialization)
  * [Reflection](#reflection)
<!-- TOC -->

# Motivation

The most popular use cases for Kotlin `lateinit var` properties are:
* **Assign Once**: property initialized during setup or when dependencies become available,
  never changed after that.
  Examples include Android view binding and test data initialization.
* **Dependency Injection**: similar to assign once, but performed by frameworks,
  often guided with annotations like `@Inject`.
  This includes mock injection for testing.

```kotlin
// Assign once
class MyActivity : AppCompatActivity() {
    lateinit var view: ImageView
    
    override fun onCreate(...) {
        view = findViewById(R.id.image)
        // view is never reassigned after this
    }
}

// Dependency injection
class MyApplication {
    @Inject lateinit var service: Service
}
```

These two use cases account for up to 80% of `lateinit var` usage
according to our open-source code survey.
They share a common trait: the property is stable after the first assignment.

However, `lateinit var` does not express this assign-once intent:
* It allows accidental reassignment, which can lead to bugs.
* Smartcasts are not supported, even though the value is effectively stable after initialization.

Due to the compilation scheme, `lateinit var` is also limited to non-nullable reference types only.

# Goals

This proposal aims to introduce first-class support for assign-once properties in Kotlin:
* Express assign-once semantics directly in code, rather than relying on convention.
* Offer runtime support to prevent accidental semantic violations.
* Enable smartcasts for assign-once properties, similarly to stable `val` properties.

In addition, the following secondary goals guided the design:
* Assign-once properties should be safe to use in concurrent contexts by default.
* Annotations, especially DI-related ones like `@Inject`, should work with assign-once properties.
* Assign-once properties should be type-agnostic, including support for nullable types.

# Intended Semantics

Based on the use cases described above,
we define the semantics for assign-once properties as follows.

An assign-once property is declared without an initializer
and starts in a special uninitialized state.
Accessing the property before and after initialization behaves differently:
* **Write**: if the property is uninitialized, it is initialized with the given value.
  Otherwise, an exception is thrown indicating a reassignment attempt.
* **Read**: if the property is initialized, the assigned value is returned.
  Otherwise, an exception is thrown indicating an attempt to read an uninitialized property.

That is, once initialized, the value is retained permanently.
The property is thus stable: every successful read returns the same value.

Adherence to assign-once semantics is enforced at runtime.
There is no requirement for the compiler to ensure at compile time
that the property is initialized before the first read and never reassigned.

A thread-safe implementation additionally guarantees:
* For any number of potentially concurrent assignments,
  exactly one succeeds and all others throw an exception.
* All reads after a successful assignment observe the same value.

# Design

We propose to base assign-once properties on the `AssignOnce` delegate interface
in the standard library.
`lateinit val` declarations are a language-level construct built on top of it,
with additional compiler support such as smartcasts.

## `AssignOnce` Delegate

We introduce an `AssignOnce` interface to the standard library:

```kotlin
@RequiresOptIn
annotation class AssignOnceSubclassing

@SubclassOptInRequired(AssignOnceSubclassing::class)
interface AssignOnce<T> {
    val value: T
    fun initialize(value: T)
    // Optional:
    fun isInitialized(): Boolean
}

operator fun <T> AssignOnce<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value
operator fun <T> AssignOnce<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) { initialize(value) }
```

`AssignOnce` is marked with `@SubclassOptInRequired`
to allow users to provide custom implementations when needed,
while signaling that doing so requires care
to preserve the stability contract.
This also leaves room for changes to the `AssignOnce` behavior in the future.

The standard library provides both thread-safe and non-thread-safe implementations.
A builder function, similar to `lazy`, selects between them:

```kotlin
fun <T> assignOnce(
    mode: AssignOnceThreadSafetyMode = AssignOnceThreadSafetyMode.SAFE
): AssignOnce<T>
```

The thread-safe implementation is used by default.
The non-thread-safe one can be selected
when synchronization overhead is undesirable
and the caller guarantees the absence of data races.

An assign-once property then can be defined by delegation:

```kotlin
class Example {
    var service: Service by assignOnce()

    fun setup() {
        service = createService()
        // Reassignment throws:
        service = createService()
    }
}
```

Properties explicitly delegated to `AssignOnce` are normal delegated properties.
In particular, smartcasts do not work for them.

## `lateinit val` Declaration

We introduce `lateinit val` as a language construct
for declaring assign-once properties.
`lateinit val` is applicable in the same declaration sites as `lateinit var`.

```kotlin
class Example {
    lateinit val service: Service

    fun setup() {
        service = createService()
    }

    fun use() {
        if (service is SpecificService) {
            // smartcast works
            service.specificMethod()
        }
    }
}
```

Unlike properties explicitly delegated to `AssignOnce`,
`lateinit val` properties **support smartcasts**.
Since the value is stable after initialization,
the compiler treats them similarly to `val` properties.

Together with existing declarations,
`lateinit val` contributes to a consistent property model
where the `lateinit` modifier moves compile-time read-write invariants to runtime:

|       |                      `var`                      |               `lateinit var`               |               `lateinit val`               |                      `val`                      |
|:-----:|:-----------------------------------------------:|:------------------------------------------:|:------------------------------------------:|:-----------------------------------------------:|
| read  | after first write<br/>(ensured at compile-time) | after first write<br/>(ensured at runtime) | after first write<br/>(ensured at runtime) | after first write<br/>(ensured at compile-time) |
| write |                     anytime                     |                  anytime                   |       once<br/>(ensured at runtime)        |       once<br/>(ensured at compile-time)        |

Note that we **do not** propose deprecation of `lateinit var`.
It covers use cases beyond assign-once semantics, such as the builder pattern,
and may be preferred in performance-sensitive contexts.

### Compilation Strategy

`lateinit val` declarations are compiled to delegation
to the thread-safe `AssignOnce` implementation:

```kotlin
class Example {
    lateinit val service: Service
    // Compiles to (roughly):
    private val service$delegate = assignOnce<Service>()
    var service: Service
        get() = service$delegate.getValue(this, ::service)
        set(value) = service$delegate.setValue(this, ::service, value)
}
```

Thread-safety by default imposes a synchronization overhead on every access.
In practice, we expect this overhead to be small:
an efficient implementation based on compare-and-set primitives is feasible,
and the intended use cases involve a single write with little or no contention.

An alternative would be a backing-field scheme similar to `lateinit var`,
where `null` or a sentinel object marks the uninitialized state.
The delegation-based scheme is chosen for the following reasons:
* It provides thread-safety through the delegate implementation
  without requiring additional fields to store synchronization primitives.
* It supports both nullable types and dependency injection at the same time:
  * A sentinel object is used to represent the uninitialized state instead of `null`.
  * Dependency injection frameworks can target the property setter,
    resolving the right type through reflection.
* It prevents Java code from modifying the stored value directly,
  as the underlying field is not exposed.

The trade-offs compared to `lateinit var` are:
* An additional allocation for the delegate instance,
  which is a memory and performance penalty.
* No exposed backing field, which may be a source of confusion
  given that `lateinit val` looks similar to `lateinit var`.

# Interaction with Other Features

For properties explicitly delegated to `AssignOnce`,
interaction with other features follows the standard rules for delegated properties.
`lateinit val`, however, does not look like a delegated property despite being compiled as one,
which leads to some non-obvious behaviors discussed below.

## Annotations

Since `lateinit val` is compiled to a delegated property,
there is no backing field for annotations to target.
This is a notable difference from `lateinit var`,
where annotations like `@Inject` target the backing field by default.

For `lateinit val`, DI annotations must use
an explicit `@set:` use-site target to reach the generated setter:

```kotlin
class Application {
    // Works with lateinit var:
    @Inject lateinit var service: Service

    // Requires explicit target with lateinit val:
    @set:Inject lateinit val service: Service
}
```

To ease this, we propose an IDE intention
that suggests adding the appropriate use-site target
for known annotations (e.g., `@set:` for `@Inject`)
when used on `lateinit val` or `AssignOnce`-delegated properties.

## `isInitialized`

Kotlin `lateinit var` properties provide an `isInitialized` check
to query initialization status, implemented as a compiler intrinsic.
However, it is used in only about 5% of `lateinit var` declarations
according to our open-source code survey.

For assign-once properties, building logic around initialization status is discouraged.
If such logic is needed, a nullable `var` or `lateinit var` may be a better fit.
For this reason, we propose to omit `isInitialized` for assign-once properties in the beginning.

If it is introduced later, explicitly delegated properties can support it
through the delegate access feature
([KEEP-0450](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0450-typed-delegate-access.md)):

```kotlin
// In the standard library:
val <T> KDelegatedProperty0<T, AssignOnce<T>>.isInitialized: Boolean
    get() = getDelegate().isInitialized()

class Example {
    var service: Service by assignOnce()

    fun check(): Boolean = ::service.isInitialized
}
```

For `lateinit val`, the check would need to be provided through an intrinsic,
similar to `lateinit var`:

```kotlin
class Example {
    lateinit val service: Service

    fun check(): Boolean = ::service.isInitialized
}
```

## Serialization

Since `lateinit val` is compiled to a delegated property,
it inherits the limitations of delegated properties with respect to serialization.
In particular, `kotlinx.serialization` treats delegated properties as transient by default.
This is another difference from `lateinit var`, which is usually supported by serialization frameworks.

In most intended use cases, assign-once properties hold
non-serializable values such as service instances or Android views,
so this limitation is unlikely to be a practical concern.
Proper handling of `lateinit val` in `kotlinx.serialization`
may be addressed separately in the future.

## Reflection

We propose to expose `lateinit val` properties as `KProperty` in the reflection API,
consistent with their `val` nature.

Although `lateinit val` has a generated setter,
exposing it as `KMutableProperty` would contradict the assign-once semantics.
This may be optionally relaxed in the future
if reflective writes prove necessary in practice.

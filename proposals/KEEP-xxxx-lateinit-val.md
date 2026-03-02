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
* [Migration from `lateinit var`](#migration-from-lateinit-var)
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

TODO

### Compilation Strategy

TODO

# Interaction with Other Features

## Annotations

TODO

## `isInitialized`

TODO

## Serialization

TODO

## Reflection

TODO

# Migration from `lateinit var`

TODO

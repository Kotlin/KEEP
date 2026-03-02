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

## Goals

This proposal aims to introduce first-class support for assign-once properties in Kotlin:
* Express assign-once semantics directly in code, rather than relying on convention.
* Offer runtime support to prevent accidental semantic violations.
* Enable smartcasts for assign-once properties, similarly to stable `val` properties.

In addition, the following secondary goals guided the design:
* Assign-once properties should be safe to use in concurrent contexts by default.
* Annotations, especially DI-related ones like `@Inject`, should work with assign-once properties.
* Assign-once properties should be type-agnostic, including support for nullable types.

# Intended Semantics

TODO

# Design

## `AssignOnce` Delegate

TODO

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

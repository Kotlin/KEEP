# lateinit val

* **Type**: Design Proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, Faiz Ilham Muhammad
* **Status**: Public Discussion
* **Discussion**: [KEEP-0455](https://github.com/Kotlin/KEEP/discussions/475)
* **Supersedes**: [KEEP-0452](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0452-assign-once.md)
  ([Public Discussion](https://github.com/Kotlin/KEEP/discussions/471))

> ## Note
> 
> This proposal is a focused follow-up to [KEEP-0452](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0452-assign-once.md) 
> and its [community discussion](https://github.com/Kotlin/KEEP/discussions/471), 
> capturing the resulting design decisions. 
> Readers are encouraged to consult them for additional context.
> Below is a summary of what changed since KEEP-0452.
> 
> ### Decisions Made
>
> - Drop the delegate-based approach to assign-once property declaration.
> - Introduce `lateinit val` declaration to Kotlin.
> - Treat `lateinit val` as stable and support smartcasts for it.
> - Compile `lateinit val` to a backing field with thread-safe access.
>
> ### New in This Proposal
>
> - [Inheritance](#inheritance): override matching rules for `lateinit val`.
> - [Reflection](#reflection): behavior of `lateinit val` in the reflection API.


# Abstract

We propose to introduce `lateinit val` declarations to Kotlin
to provide first-class support for properties
with delayed initialization and assign-once semantics.

`lateinit val` bridges the gap between `lateinit var` and `val`:
it allows late initialization while preventing reassignment 
and enabling smartcasts.

It is compiled to a private backing field.
Thread-safe getter and setter enforce the assign-once semantics.

# Table of Contents

<!-- TOC -->
* [Abstract](#abstract)
* [Motivation](#motivation)
* [Goals](#goals)
* [Intended Semantics](#intended-semantics)
* [Design](#design)
  * [Compilation Strategy](#compilation-strategy)
* [Interaction with Other Features](#interaction-with-other-features)
  * [Inheritance](#inheritance)
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

The property is thus stable: every successful read returns the same value.

Adherence to assign-once semantics is enforced at runtime.
There is no requirement for the compiler to ensure at compile time
that the property is initialized before the first read and never reassigned.

A thread-safe implementation additionally guarantees:
* For any number of potentially concurrent assignments,
  exactly one succeeds and all others throw an exception.
* All reads after a successful assignment observe the same value.

# Design

We introduce `lateinit val` as a language construct for declaring assign-once properties.
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

`lateinit val` compares to related declarations in the following ways:
* It can be late initialized in any scope, unlike `val` class properties which restrict deferred initialization to `init` blocks.
* It supports **smartcasts**: since the value is stable after initialization, the compiler treats it similarly to `val` properties.
* It has no restrictions on the property type: nullable and primitive types are allowed, in contrast with `lateinit var`.
* It permits no custom setter or getter, similar to `lateinit var`.
* It exposes no backing field on the source level. In particular, `@field:` annotation target is invalid for `lateinit val`.
* It is thread-safe, meaning semantics are preserved in a multithreaded environment.

The backing field is hidden in this design because interacting with it through annotations
could bypass generated setter and violate assign-once semantics.

Thread-safety is required by design because without it, 
concurrent access could silently violate the stability contract that smartcasts rely on,
leading to subtle, hard-to-debug issues.

Together with existing declarations, `lateinit val` contributes to a consistent property model
where the `lateinit` modifier moves compile-time read-write invariants to runtime:

|       |                      `var`                      |               `lateinit var`               |               `lateinit val`               |                      `val`                      |
|:-----:|:-----------------------------------------------:|:------------------------------------------:|:------------------------------------------:|:-----------------------------------------------:|
| read  | after first write<br/>(ensured at compile-time) | after first write<br/>(ensured at runtime) | after first write<br/>(ensured at runtime) | after first write<br/>(ensured at compile-time) |
| write |                     anytime                     |                  anytime                   |       once<br/>(ensured at runtime)        |       once<br/>(ensured at compile-time)        |

Note that we **do not** propose deprecation of `lateinit var`.
It covers use cases beyond assign-once semantics, such as the builder pattern,
and may be preferred in performance-sensitive contexts.

### Compilation Strategy

We propose to compile `lateinit val` declarations to private backing fields
with thread-safe setters and getters that enforce assign-once semantics.
For example, on the JVM, the following code:

```kotlin
class Example {
    lateinit val property: String
}
```

could be compiled to (expressed in Java):

```java
public final class Example {
    private static final Object UNINITIALIZED = new Object();

    private static final AtomicReferenceFieldUpdater<Example, Object> propUpdater =
            AtomicReferenceFieldUpdater.newUpdater(Example.class, Object.class, "_property");

    private volatile Object _property = UNINITIALIZED;

    @NotNull public String getProperty() {
        Object p = _property;
        if (p == UNINITIALIZED) {
            throw new IllegalStateException("Property is uninitialized");
        }
        return (String) p;
    }

    public void setProperty(@NotNull String v) {
        boolean updated = propUpdater.compareAndSet(this, UNINITIALIZED, v);
        if (!updated) {
            throw new IllegalStateException("Property already set");
        }
    }
}
```

On other platforms, native atomic primitives may be used instead of `AtomicReferenceFieldUpdater`.

Note that the `AtomicReferenceFieldUpdater` is a static class field, so
it does not impose memory overhead on the class.
The `UNINITIALIZED` sentinel object is also a static field,
it can even be reused for all `lateinit val` properties of the class.
Another option would be to provide it in the standard library,
but then it would have to be public.
If the sentinel and updater are made `protected` instead of `private`,
inheritors can reuse them rather than generating their own.

Thread-safety imposes a synchronization overhead on every access.
In practice, we expect this overhead to be small:
the intended use cases involve single write with little or no contention.

An alternative would be a public backing field scheme similar to `lateinit var`,
where `null` or a sentinel object marks the uninitialized state.
The proposed scheme is chosen for the following reasons:
* It supports both nullable types and dependency injection at the same time:
  * A sentinel object is used to represent the uninitialized state instead of `null`.
  * Dependency injection frameworks can target the property setter,
    resolving the right type through reflection.
* It prevents Java code from modifying the stored value directly,
  as the backing field is private.

The trade-off compared to `lateinit var` is
no exposed backing field, which may create confusion
given that `lateinit val` looks similar to `lateinit var`.

# Interaction with Other Features

In this section, we describe how `lateinit val` interacts with other language features.

## Inheritance

For override matching purposes, `lateinit val` is treated as `val`.
The one exception is that an `open lateinit val` cannot be overridden by a plain `val`,
because `lateinit val` has a generated setter that `val` lacks:

| declaration \ can be overridden by  | `val` | `lateinit val` | `var` | `lateinit var` |
|:-----------------------------------:|:-----:|:--------------:|:-----:|:--------------:|
|     **abstract or open `val`**      |  yes  |      yes       |  yes  |      yes       |
|       **open `lateinit val`**       |  no   |      yes       |  yes  |      yes       |
|     **abstract or open `var`**      |  no   |       no       |  yes  |      yes       |
|       **open `lateinit var`**       |  no   |       no       |  yes  |      yes       |

Just as with `lateinit var`, abstract `lateinit val` is not supported.
While `open lateinit val` is technically allowed, it is discouraged: 
the late initialization semantics is an implementation detail
that subclasses should not need to inherit or rely on.

## Annotations

Although `lateinit val` has a backing field, we propose to hide it for annotations,
because applying them to the field might bypass the generated setter of the property
and thus violate assign-once semantics.

So DI annotations must use an explicit `@set:` use-site target to reach the generated setter:

```kotlin
class Application {
    // Works with lateinit var:
    @Inject lateinit var service: Service

    // Requires explicit target with lateinit val:
    @set:Inject lateinit val service: Service
}
```

To facilitate this use case, we propose an IDE intention
that suggests adding the appropriate use-site target
for known annotations (e.g., `@set:` for `@Inject`)
when used on `lateinit val` properties.

## `isInitialized`

Kotlin `lateinit var` properties provide an `isInitialized` check
to query initialization status, implemented as a compiler intrinsic.
However, it is used in only about 5% of `lateinit var` declarations
according to our open-source code survey.

For assign-once properties, building logic around initialization status is discouraged.
If such logic is needed, a nullable `var` or `lateinit var` may be a better fit.
For this reason, we propose to omit `isInitialized` for assign-once properties in the initial implementation.

If it is introduced later, the check would need 
to be provided through intrinsic, similar to `lateinit var`.

## Serialization

The backing field of a `lateinit val` effectively has `Any?` type,
so serialization frameworks that rely on it cannot determine the actual property type.
Also, the sentinel object is not serializable.
Thus, serialization frameworks would need to provide special support for `lateinit val` properties.

However, in most intended use cases, assign-once properties hold non-serializable values
such as service instances or Android views, so it might be acceptable to ignore
`lateinit val`s in serialization by default.

## Reflection

We propose to expose `lateinit val` properties as `KProperty` in the reflection API,
consistent with their `val` nature.

Although `lateinit val` has a generated setter,
exposing it as `KMutableProperty` would contradict the assign-once semantics.
This may be optionally relaxed in the future
if reflective writes prove necessary in practice.
Note that the generated setter remains accessible through Java reflection, 
which is sufficient for dependency injection use cases.

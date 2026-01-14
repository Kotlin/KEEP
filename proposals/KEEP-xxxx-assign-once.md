# Assign-Once Properties

* **Type**: Design Proposal
* **Author**: Mikhail Vorobev
* **Contributors**: TODO
* **Discussion**: TODO
* **Status**: Public Discussion

# Abstract

We propose to provide runtime and compile-time support for properties
with delayed initialization and assign-once (stable) semantics.
This functionality bridges the gap between `lateinit var` and `val` properties,
allowing late initialization while keeping benefits of stable properties.
Implementation is based on `AssignOnce` delegate,
but we outline two possibilities to surface the feature:
delegate-first approach and language-builtin approach.

# Table of Contents

<!-- TOC -->
* [Assign-Once Properties](#assign-once-properties)
* [Abstract](#abstract)
* [Table of Contents](#table-of-contents)
* [Motivation](#motivation)
* [Goals](#goals)
* [Semantics](#semantics)
* [Design](#design)
  * [Delegate-First Approach](#delegate-first-approach)
  * [Language-Builtin Approach](#language-builtin-approach)
* [Features](#features)
  * [Thread-Safety](#thread-safety)
  * [Smartcasts](#smartcasts)
  * [Annotations](#annotations)
  * [`isInitialized`](#isinitialized)
  * [Summary](#summary)
* [Implementation](#implementation)
  * [Compilation Strategy](#compilation-strategy)
  * [`AssignOnce` Delegate](#assignonce-delegate)
* [Migration from `lateinit var`](#migration-from-lateinit-var)
* [Additional Considerations](#additional-considerations)
  * [Serialization](#serialization)
  * [No General Stable Semantics](#no-general-stable-semantics)
  * [Using `StableValue`](#using-stablevalue)
<!-- TOC -->

# Motivation

Currently, the most popular use cases by far for Kotlin `lateinit var` properties are:
* **Assign Once**: Variable initialized during setup, initialization, or when dependencies become available. The variable is never changed after that.
For example, Android view binding, test data initialization.
* **Dependency Injection**: Similar to assign once, but done by libraries or frameworks, often guided with annotations. This use-case also includes mock injection for testing.
* **Late Initialization**: Variables with delayed initialization, but possibly reassigned multiple times.
One example of such usage is the builder pattern.

```kotlin
// Assign Once use-case
class MyActivity : AppCompatActivity() {
    lateinit var view: ImageView
    
    // `onCreate` is called early in the activity lifecycle
    override fun onCreate(...) {
        view = findViewById(R.id.image)
        // view is never reassigned after this
    }
}

// Dependency Injection use-case
class MyApplication {
    // `service` is injected on the application creation
    @Inject lateinit var service: Service
    
    fun doStuff() {
        // `service` is guaranteed to be initialized at this point
        service.someMethod()
    }
}

// Late Initialization use-case
class MyRequestBuilder {
    lateinit var headers: Headers
    
    fun build(): Request {
        // `headers` are expected to be initialized externally
        // before calling `build` (they can be reassigned multiple times)
        require(::headers.isInitialized) { 
            "Headers are not initialized" 
        }
        return Request(headers = headers)
    }
}
```

The first two use cases are the most common ones,
accounting for up to 80% of `lateinit var` usage 
according to our open-source code survey.
They are also similar in the semantics: 
the property is stable after the first assignment.

But `lateinit var` declaration does not correspond well to this assign-once semantics:
* It allows accidental reassignment of the property, which can lead to bugs.
* Smartcasts are unsupported for `lateinit var` even if value is actually stable after initialization.

Due to the compilation scheme, `lateinit var` is also limited to non-nullable reference types only.

# Goals

This proposal aims to introduce first-class support for assign-once properties in Kotlin:
* Express assign-once semantics directly in code, rather than relying on convention.
* Offer runtime support to prevent accidental semantic violations.
* Enable smartcasts for assign-once properties, similarly to stable `val` properties.

In addition, the following secondary goals were not hard requirements,
but they guided design and implementation choices:
* Assign-once properties should be type-agnostic, including support for nullable types.
* Annotation usage should remain ergonomic for assign-once properties, 
  especially for DI-related annotations like `@Inject`. 
  See [Features/Annotations](#annotations) for discussion.
* Thread-safe runtime semantics should be available for assign-once properties.
  Whether it should be the default or not is discussed in [Features/Thread-Safety](#thread-safety).

# Semantics

Based on the motivational examples above, 
we define semantics for assign-once properties as follows:
* An assign-once property is declared without an initializer
and starts in a special uninitialized state.
  * There are no restrictions on the type of the property.
    In particular, nullable types are allowed.
* An assign-once property can be assigned after declaration:
  * If it is in the uninitialized state, it is initialized with the given value.
  * Otherwise, an exception is thrown indicating an attempt to reassign the property.
* An assign-once property can be read:
  * If it is in the uninitialized state, an exception is thrown 
    indicating an attempt to read the uninitialized property.
  * Otherwise, the previously assigned value is returned.

It is important to note that adherence to the assign-once semantics 
is enforced at runtime rather than ensured at compile time.
In other words, the compiler would not check statically that an assign-once property
is initialized before the first read and never reassigned after initialization.
In the intended use-cases for assign-once properties,
we expect accidental violations of assign-once semantics to be rare.
Thus, we intend the runtime enforcement to be a bug-catching check.
Users should not build logic around the initialization status of an assign-once property,
a `var` or `lateinit var` should be preferred for such purposes.
This is also an argument against supporting [`isInitialized`](#isinitialized) check.

A thread-safe implementation of properties with assign-once semantics 
should also provide the following guarantees:
* For any number of potentially concurrent assignments,
  exactly one of them succeeds, and all others throw an exception.
* All reads that happen after a successful assignment
  see the same previously assigned value.

Whether assign-once properties should be thread-safe by default
is discussed in the [Thread-Safety](#thread-safety) section.

Below we outline two approaches to bring properties with assign-once semantics to Kotlin.

# Design

In this section we describe two possible ways to introduce assign-once properties in Kotlin:
* **Delegate-first approach**: expose `AssignOnce` delegate directly and define assign-once properties by delegation.
* **Language-builtin approach**: introduce a new property modifier to the language for assign-once properties.

They could technically coexist in the language. 
However, doing so would introduce two ways to express the same semantics,
increasing the cognitive load for users, complicating documentation, 
and imposing maintenance burden on the compiler, IDE, and tooling.

Regardless of the chosen approach, we propose assign-once properties
to be implemented as properties delegated to `AssignOnce` delegate.
For the reasoning behind this choice, see the [Compilation Strategy](#compilation-strategy) section below.

## Delegate-First Approach

We can add `AssignOnce` delegate to the Kotlin standard library,
providing a builder function similar to `lazy`:

```kotlin
fun <T> assignOnce(
    mode: AssignOnceThreadSafetyMode = AssignOnceThreadSafetyMode.SAFE
): ReadWriteProperty<Any?, T> = when (mode) {
    AssignOnceThreadSafetyMode.SAFE -> ThreadSafeAssignOnce()
    AssignOnceThreadSafetyMode.NONE -> UnsafeAssignOnce()
}
```

This way, an assign-once property definition is a delegation:

```kotlin
class Example {
    var property: String by assignOnce()

    fun setup() {
        property = "Initialized"
        // ...
        // throws IllegalStateException
        property = "Reassignment" 
    }
}
```

Delegation allows customization of desired semantics
through builder function parameters and does not
require changes of the syntax. 

On the other hand: 
* The intent of assign-once semantics
is a bit more obfuscated compared to a dedicated language construct.
* If we are to enable smartcasts for assign-once properties, 
the `AssignOnce` delegate would become a special case in the compiler.
See the [Smartcasts](#smartcasts) section below for details.

## Language-Builtin Approach

We could surface assign-once properties in the language directly.
There are at least two ways to do so:
* Extend `lateinit` modifier to `val`s.
This approach expands the existing notion of `lateinit`.
However, `lateinit val`s would become `val`s that can be assigned after the declaration,
which is conceptually confusing as `val`s are expected to be immutable.
* Add a new `assignonce` modifier for `var`s.
It avoids confusion of mutable `lateinit val`s and 
expresses the intent of assign-once semantics more clearly.
However, this approach requires an introduction of a new soft keyword.

```kotlin
class Example {
    assignonce var property: String
    // alternative syntax:
    lateinit val property: String

    fun setup() {
        property = "Initialized"
        // ...
        // throws IllegalStateException
        property = "Reassignment" 
    }
}
```

We would like to hear from the community on which syntax is more natural.

Note that we propose to translate the syntax extension to delegation under the hood,
similar to the delegate-first approach:

```kotlin
class Example {
    var property: String by AssignOnce()
}
```

See the [Compilation Strategy](#compilation-strategy) section below for the reasoning behind this choice.

With the new syntax, a declaration clearly expresses the intent of assign-once semantics. 
However, it comes with the following downsides:
* Customization of the semantics, e.g., thread-safety mode,
is not possible without additional syntax.
* Compilation strategy is more complex than
for other non-delegated properties, especially `lateinit var`s,
which may lead to confusion.
Particularly, interaction with annotations becomes complicated,
as `lateinit val` (or `assignonce var`) does not create a backing field.
See the [Annotations](#annotations) section below for details.

# Features

In this section we discuss the interaction of assign-once properties with other Kotlin features. 
Along the way, we compare both design approaches outlined above.

## Thread-Safety

Currently, `Lazy` is the only delegate in the standard library
which has implementations with different thread-safety modes
and provides synchronization by default.
Other delegates, e.g. `Delegates.vetoable`, are not thread-safe.

On one hand, assign-once properties are similar to `var`s and
`Delegates.vetoable` in that only a write concurrent to another
operation could lead to a data-race condition.
So we could refrain from synchronizing assign-once properties,
requiring users to ensure safety in a multithreaded environment,
similar to `var` declarations.
In contrast, if we take non-synchronized `Lazy` implementation, 
two concurrent reads of a lazy property could trigger 
concurrent initializations and lead to a data-race.
This unintuitive behavior is an argument for
making `Lazy` delegate synchronized by default,
but it does not apply to assign-once properties.

On the other hand, thread-safe `AssignOnce` delegate can be
implemented efficiently with compare-and-set primitives.
Property getter can be further optimized with optimistic read of the 
underlying `_value` field without synchronization,
similar to current thread-safe JVM implementation of `Lazy`.

Also, the intended use-case for assign-once properties
involves just one assignment, during setup or initialization.
Thus, with optimistic reads, the overhead of synchronization
would be negligible in practice.

With the above considerations in mind, we propose to make
assign-once properties thread-safe by default,
independent of the design approach.

Speaking of the design, the delegate-first approach is more flexible
in this regard, as it allows choosing the desired synchronization mode 
through parameters of the builder function.
With the language-builtin approach, we can have to invent additional syntax
if we are to allow customization.

## Smartcasts

Assign-once properties do not change after initialization.
They are similar to `val` and lazy properties in that 
every successful read returns the same value.
We call this behavior stability and such properties stable.

We propose to enable smartcasts for assign-once properties,
so they benefit from the same safety and convenience as `val` properties:

```kotlin
class Example {
    var property: CharSequence by assignOnce()

    fun setup() {
        property = "Initialized"
    }
    
    fun method() {
        if (property is String) {
            // smartcast to String
            println(property.length)
        }
    }
}
```

In the delegate-first approach, assign-once properties
would become an exception among other delegated properties,
for which smartcasts are not supported.
On the other hand, a special keyword for declaring assign-once properties
in the language-builtin approach aligns better with this dedicated support:

```kotlin
class Example {
    // assignonce properties can be smartcasted
    assignonce var property: String

    fun setup() {
        property = "Initialized"
    }
    
    fun method() {
        if (property is String) {
            // smartcast to String
            println(property.length)
        }
    }
}
```

Regardless of the chosen design, 
such smartcasts require the compiler to handle assign-once properties as
special stable delegated properties.
`Lazy` delegate could be handled similarly,
but we consider this enhancement to be outside the scope of this KEEP.
We also refrain from introducing general support for
stable property delegates at this point.
For more details, see the [No General Stable Semantics](#no-general-stable-semantics) section.


## Annotations

One of the most common use-cases for `lateinit var` properties
and one of intended use-cases for assign-once properties is dependency injection.
A considerable number of DI frameworks used in Kotlin come from the Java ecosystem 
and do not provide Kotlin-specific integration.
They often rely on annotations, e.g. `@Inject`, to mark properties for injection.

As assign-once properties are implemented with delegation,
they do not expose a backing field for injection frameworks to target.
Luckily, most DI frameworks support injection through methods as well,
so they could inject through the generated setter of the property.

However, because of the current defaulting rule for annotation application,
one cannot apply annotations to a delegated property without specifying a use-site target explicitly.
The `@set:` target must be used to apply annotation to the setter:

```kotlin
class Application {
    @set:Inject var service: Service by assignOnce()

    // error: no applicable target for Inject
    // @Inject var service: Service by assignOnce()
}
```

From this perspective, the delegate-first approach is more consistent 
as assign-once properties interact with annotations
in the same way as other delegated properties do.
With the language-builtin approach, assign-once properties might 
create an expectation that they have backing fields, 
and annotations can be applied to them similarly to `lateinit var`s, 
but this is not the case:

```kotlin
class Application {
    // error: no applicable target for Inject
    // @Inject assignonce var service: Service

    // correct usage:
    @set:Inject assignonce var service: Service
}
```

One could alter the defaulting rule for annotations 
in the case of `assignonce var`s to hide this complexity,
for example, by applying annotations to the setter by default.
That would make DI annotations like `@Inject` appear to work
as if they were applied to the field:

```kotlin
class Application {
    // `@Inject` is applied to the setter 
    // with an altered defaulting rule
    @Inject assignonce var service: Service
}
```

However, this quickly becomes inconsistent as not all annotations make sense on a setter.
For instance, `@Transient` is typically meant for the stored state.
With an assign-once property the only reasonable target is `@delegate:`:

```kotlin
class Model {
    // error: `@Transient` cannot be applied to setter
    // @Transient assignonce var field: String
    
    // correct usage:
    @delegate:Transient assignonce var field: String
}
```

So modification of the defaulting rule would fix just one class of annotations.
It would also make annotation placement depend on a property modifier in an unobvious way.
For that reason, we do not propose changing the defaulting rule for annotations. 
Instead, we propose an IDE intention that suggests adding `@set:`
automatically for known DI annotations when used on assign-once properties,
regardless of whether the feature is exposed via delegation or built-in syntax.

## `isInitialized`

Kotlin `lateinit var` properties have a built-in `isInitialized` 
check which tells if the property has been initialized.
This check is implemented as a special case for property 
reflection in the compiler and an intrinsic.

The necessity of supporting an analogous check for assign-once properties is debatable,
as they are intended to be initialized explicitly no more than once. 
If special logic around initialization status becomes unavoidable,
it is a sign that plain nullable `var` might be of better use.

If we decide to support such a check,
it is straightforward to do so with the delegate-first approach
assuming that the delegate access feature is introduced in Kotlin:

```kotlin
class AssignOnce<T> {
    // ...
    val isInitialized: Boolean
        get() = _value !== UNINITIALIZED_VALUE
}

fun <T> assignOnce(...): AssignOnce<T> = ...

fun KDelegatedProperty<AssignOnce<*>, *>.isInitialized(): Boolean {
    // this.delegate: AssignOnce<*>
    return this.delegate.isInitialized
}

class Example {
    var property: String by assignOnce()

    fun isPropertyInitialized(): Boolean {
        return ::property.isInitialized()
    }
}
```

With language-builtin approach, to implement `isInitialized` check,
we would have to treat assign-once properties specially by either:
* Expose them as delegated properties in reflection API
and use the same code as above.
* Introduce an intrinsic similar to that for `lateinit var`s:

```kotlin
// Currently in the stdlib:
public inline val @receiver:AccessibleLateinitPropertyLiteral KProperty0<*>.isInitialized: Boolean
    get() = throw NotImplementedError("Implementation is intrinsic")

// Proposed addition:
public inline val @receiver:AccessibleAssignOncePropertyLiteral KProperty0<*>.isInitialized: Boolean
    // Generated code is something like:
    // return (this.getDelegate() as AssignOnce<*>).isInitialized
    get() = throw NotImplementedError("Implementation is intrinsic")
```

## Summary

Below is a brief comparison of the two design approaches
in terms of assign-once properties features.

| Feature         | Delegate-Based Assign-Once Properties                                                                       | Language Built-In Assign-Once Properties                                                  |
|-----------------|-------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| Thread-Safety   | ✅ Customizable with a builder function parameter                                                            | ❌ New syntax is necessary if customization is desired                                     |
| Annotations     | :warning: Use-site target is required which is expected for a delegated property                            | ❌ Use-site target is required which is confusing compared to `lateinit var`               |
| Smartcasts      | :warning: Could be supported, but would make assign-once properties an exception among delegated properties | ✅ Could be supported                                                                      |
| `isInitialized` | :warning: Implementation requires delegate-access or intrinsic                                              | :warning: Implementation requires intrinsic or reflection API support and delegate access |

# Implementation

In this section we briefly discuss the implementation of assign-once properties,
focusing mainly on the reasoning behind the delegation-based compilation scheme.

## Compilation Strategy

If we adopt the language-builtin design for assign-once properties,
compiling them as delegated properties is an unobvious choice.
Naturally, one could consider compilation schemes similar to the `lateinit var`.
Implementing assign-once properties by a backing field
might be simpler and more performant,
saving an allocation compared to the delegation approach.

In this case, the backing field should be private
so that it could not be modified externally,
potentially breaking the assign-once semantics contract.
Generated getter and setter would enforce the contract.

We could choose how to represent the uninitialized state
of the property:
* Use `null` as uninitialized marker.
  Then assign-once properties would be limited to non-nullable types only,
  just like `lateinit var`s.
  But dependency injection annotations can be used in this case
  without an explicit use-site target
  as DI frameworks commonly support injection to private fields.
* Use a special marker object as in `AssignOnce` delegate.
  This way, nullable types are supported.
  But the type of the backing field becomes `Any?`,
  which makes injection possible only through the setter,
  meaning that an explicit `@set:` target is required for DI annotations.

```kotlin
class Example {
    assignonce var property: String
    // null-based compilation scheme:
    private var property: String? = null
        get() { ... }
        set(value: String) { ... }
    // marker-based compilation scheme:
    private var property: Any? = UNINITIALIZED_VALUE
        get() { ... }
        set(value: String) { ... }

    // both schemes support injection through the setter:
    @set:Inject assignonce var service: Service
    // only null-based scheme supports injection through the field:
    @Inject assignonce var service: Service
}
```

Also, if we are to provide thread-safety by default for assign-once properties,
this compilation scheme becomes more complex
as we would have to introduce additional fields
for synchronization primitives.

This makes us believe that compilation to delegated properties is simpler overall,
while it provides a consistent experience,
allowing nullable types and synchronization customization.
However, it comes at the price of a slightly less
convenient interaction with DI annotations.

## `AssignOnce` Delegate

Desired assign-once semantics of a property can be
expressed with the following property delegate:

```kotlin
internal object UNINITIALIZED_VALUE

class AssignOnce<T>(
    private var _value: Any? = UNINITIALIZED_VALUE
): ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (_value === UNINITIALIZED_VALUE)
            throw IllegalStateException("Property ${property.name} is not initialized")
        @Suppress("UNCHECKED_CAST")
        return _value as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (_value !== UNINITIALIZED_VALUE)
            throw IllegalStateException("Property ${property.name} is already initialized")
        _value = value
    }
}
```

This code is only a reference implementation, the actual one might differ  
but should provide the same semantics.
In particular, note that nullable types are supported in contrast to `lateinit var`. 
Thus `null` can be a valid domain value for the property.

Also, the given implementation is not thread-safe.
A synchronized version can be implemented through
compare-and-set operations on the `_value` field.

# Migration from `lateinit var`

It is important to note we **do not** propose deprecation of `lateinit var`s in this KEEP,
because `lateinit var`s have proper use-cases that assign-once properties do not cover,
for example, builder pattern.
Also, a developer might still prefer to use `lateinit var` in some contexts:
* They make a better trade-off for performance and memory usage
  compared to assign-once properties,
  as they do not require additional allocations for delegate instance and synchronization primitives.
* They can be used with dependency injection frameworks that don't support injection through setters or
  with serialization frameworks that can't serialize delegated properties,
  so assign-once properties are not an option.

However, we propose to add a suggestion in IDE that would facilitate 
the transition from `lateinit var` to assign-once properties 
in the dependency injection use-case.
This would also contribute to the discoverability of the feature:

```kotlin
class Application {
    @Inject lateinit var service: Service
    // IDE suggests a rewrite to assignonce var:
    @set:Inject assignonce var service: Service
}
```

# Additional Considerations

In this section we discuss topics that are less immediately relevant 
to the design of assign-once properties, but are worth mentioning for completeness. 

## Serialization

Support for delegated properties in Kotlin serialization frameworks is limited in general.
In particular, `kotlinx.serialization` treats delegated properties as transient by default.
Thus implementing assign-once properties with delegation makes them harder to integrate with serialization.
In this regard, assign-once properties are inferior to `lateinit var`s which are usually supported.

However, we do not address this issue in this proposal for the following reasons:
- We expect that in most of the intended use-cases for assign-once properties,
  they would hold values that are not serializable themselves, 
  e.g., service implementations or Android views, see [Motivation](#motivation).
- We consider serialization support for delegated properties to be
  a separate design problem outside the scope of this KEEP.

If serialization is desired for a property, a developer can do one of the following:
- Implement a custom serializer to support assign-once properties an object has.
- Fall back to `lateinit var`s or even a plain nullable `var`.

## No General Stable Semantics

Delegates `Lazy` and `AssignOnce` both provide stable semantics,
their `getValue` method always returns the same value on successful read.
There are also examples of user-defined delegates with stable semantics.
So it is tempting to introduce general support for stable property delegates to Kotlin,
enabling smartcasts for them.
Suppose we could add an annotation to mark a delegate
or its `getValue` method as stable:

```kotlin
@Target(AnnotationTarget.FUNCTION)
annotation class Stable

class AssignOnce<T>(
    private var _value: Any? = UNINITIALIZED_VALUE
) {
    fun setValue(...): Unit { ... }

    @Stable
    fun getValue(...): T { ... }
}

class Example {
    var property: CharSequence by assignOnce()

    fun useProperty() {
        if (property is String) {
            // smartcast to String
            val s: String = property
        }
    }
}
```

This approach has a couple of problems.

First, stability is an invariant of the whole delegate definition, 
not just of the `getValue` method.
For example, to deduce that `AssignOnce` is stable,
we have to ensure that `setValue` does not change the value after the first assignment.
So verifying the stability of arbitrary user-defined delegates would be hard in practice, 
and the compiler would have to treat it as a trusted assumption.

This way, a mistake in an implementation of a delegate marked as stable would lead
to non-local runtime errors for a code which compiles without any warnings.
Debugging such errors could become highly complex in practice 
because one would need to understand:
* That the error points to a read of a delegated property
which is actually a call to `getValue` method of the delegate.
* That the delegate is marked as stable, 
but it actually isn't due to a bug.
* That the compiler has used the unsound assumption of delegate stability 
to deduce something about the property, e.g., smartcast it.

Second, a delegated property read calls `getValue` method indirectly, 
through the generated accessor:

```kotlin
class Example {
    var property: String by assignOnce()
    // roughly translates to
    private val property$delegate = assignOnce<String>()
    var property: String
        get() = property$delegate.getValue(...)
        set(value: String) = property$delegate.setValue(...)
}
```

This means that, strictly speaking, the compiler would have to
deduce stability of the getter from stability of the delegate's `getValue` method and arguments passed to it.
This indirection introduces yet another special case the compiler must handle.

So general stable-semantics support for delegated properties would end up 
relying on special cases and non-verifiable assumptions which could lead to complicated bugs. 
Because of that, we propose to avoid the complexity of general stable semantics 
and support smartcasts only for assign-once properties for now.

## Using `StableValue`

[JEP-502](https://openjdk.org/jeps/502) proposed to introduce `StableValue` API,
which could potentially serve as a compilation target for assign-once properties on JVM.
However, `StableValue` was removed in JDK 26.
It's successor, [JEP-526: `LazyConstant`](https://openjdk.org/jeps/526), does not fit the use-case.
[Project Amber team suggested](https://mail.openjdk.org/pipermail/amber-dev/2025-November/009470.html) 
that functionality similar to that of `StableValue`
might be exposed in the future through `VarHandle` API.



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
    
    // `onCreate` is called early in the lifecycle
    override fun onCreate(...) {
        view = findViewById(R.id.image)
        // view is never reassigned after this
    }
}

// Dependency Injection use-case
class MyApplication {
    // `service` is injected after the application is created
    @Inject lateinit var service: Service
    
    fun doStuff() {
        // `service` is guaranteed to be initialized at this point
        serivice.someMethod()
    }
}

// Late Initialization use-case
class MyRequestBuilder {
    lateinit var headers: Headers
    
    fun build(): Request {
        // `headers` are expected to be initialized before calling `build`
        // (they can be reassigned multiple times)
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

This proposal aims to introduce better support for assign-once properties in Kotlin:
* Provide a way to clearly express the intent of assign-once semantics in the code.
* Offer runtime support to prevent accidental reassignment.
* Enable smartcasts for assign-once properties.

# Implementation

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
In particular, note that nullable types are supported
in contrast to `lateinit var`. Thus `null` can be a valid domain value for the property.

Also, the given implementation is not thread-safe. 
A synchronized version can be implemented through
compare-and-set operations on the `_value` field.
Any synchronized implementation should provide
safe assign-once semantics with the following properties:
* For any number of potentially concurrent assignments,
exactly one of them succeeds, and all others throw an exception.
* All reads that happen after a successful assignment
see the same previously assigned value.

`AssignOnce` delegate is an implementation of assign-once semantics, 
but there are different ways to surface it in the language.
We outline two possible approaches in the next section.

# Design

In this section we describe two possible ways to introduce assign-once properties in Kotlin:
* **Delegate-first approach**: expose `AssignOnce` delegate directly and define assign-once properties by delegation.
* **Language-builtin approach**: introduce a new property modifier to the language for assign-once properties.

Both options are still backed up by the same `AssignOnce` delegate 
implementation and differ primarily in syntax and ergonomics. 
They also can coexist in the language,
but it does not seem to provide significant benefits,
while increasing complexity.

## Delegate-first Approach

We can surface `AssignOnce` delegate directly in the Kotlin standard library,
providing a builder function similar to `lazy`:

```kotlin
fun <T> assignOnce(
    mode: AssignOnceThreadSafetyMode = AssignOnceThreadSafetyMode.SAFE
): ReadWriteProperty<Any?, T> = when (mode) {
    case AssignOnceThreadSafetyMode.SAFE -> SynchronizedAssignOnce()
    case AssignOnceThreadSafetyMode.NONE -> AssignOnce()
}
```

This way, assign-once properties can be defined by plain delegation:

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
More on that below.

## Language-builtin Approach

We can introduce a new property modifier `assignonce` to the language:

```kotlin
class Example {
    assignonce var property: String
    // another possible syntax is to extend `lateinit` to `val`s:
    // lateinit val property: String

    fun setup() {
        property = "Initialized"
        // ...
        // throws IllegalStateException
        property = "Reassignment" 
    }
}
```

This syntax is translated to a delegated property under the hood:

```kotlin
class Example {
    var property: String by AssignOnce()
}
```

With this approach, a declaration clearly expresses
the intent of assign-once semantics. 
However, it comes with the following downsides:
* Customization of the semantics, e.g., thread-safety mode,
is not possible without additional syntax.
* Compilation strategy is more complex than
for other non-delegated properties, especially `lateinit var`s,
which may lead to confusion.
Particularly, interaction with annotations becomes complicated,
as `assignonce var` does not create a backing field.
More on that below.

# Features

In this section we discuss the interaction of assign-once properties with other Kotlin features. 
Along the way, we compare both design approaches outlined above.

## Synchronization

Currently, `Lazy` is the only delegate in the standard library
which has implementations with different synchronization modes
and provides strong thread-safety by default.
Other delegates, e.g. `Delegates.vetoable`, are not thread-safe.

On one hand, assign-once properties are similar to `var`s and
`Delegates.vetoable` in that only a write concurrent to another
operation could lead to a data-race condition. 
In contrast, if we tak unsychronized `Lazy` implementation, two concurrent reads of a lazy
property could trigger concurrent initializations and lead to a data-race.

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
so they benefit from the same safety and convenience as `val` properties.
This requires the compiler to handle assign-once properties as 
special stable delegated properties. 
`Lazy` delegate could be handled similarly,
but we refrain from introducing general support for
stable property delegates at this point.
For more details, see the [General Stable Semantics](#general-stable-semantics) section.

A special keyword for declaring assign-once properties
in the language-builtin approach aligns well with
this dedicated support for smartcasts.
In the delegate-first approach, assign-once properties
would become an exception among other delegated properties,
for which smartcasts are not supported.

## Annotations

One of the most common use-cases for `lateinit var` properties
and one of intended use-cases for assign-once properties is dependency injection.
A considerable number of DI frameworks used in Kotlin come from the Java ecosystem 
and do not provide Kotlin-specific integration.
They often rely on annotations, e.g. `@Inject`, to mark properties for injection.

As assign-once properties are implemented with delegation,
they do not expose a backing field for injection frameworks to target.
Luckily, most DI frameworks support injection through methods as well,
so they inject to the generated setter of the property.

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

We propose to implement an intention in the IDE which suggests
adding the `@set:` target automatically for known DI annotations
when applied to delegated properties.

From this perspective, the delegate-first approach is more consistent 
because assign-once properties interact with annotations
in the same way as other delegated properties do.
With the language-builtin approach, assign-once properties might 
create an expectation that they have backing fields
and annotations can be applied to them, but that is not the case:

```kotlin
class Application {
    // error: no applicable target for Inject
    // @Inject assignonce var service: Service

    // correct usage:
    @set:Inject assignonce var service: Service
}
```

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
* Introduce an intrinsic similar to that for `lateinit var`s.

# Rationale

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
we have to ensure that `setValue` does not change the value
after the first assignment.
So verifying the stability of arbitrary user-defined delegates would be hard for the compiler in practice.
This means the compiler would have to treat stability as a trusted assumption.

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

So general stable-semantics support for delegated properties would end up relying on non-verifiable assumptions and special cases. Because of that, we propose to avoid the complexity of general stable semantics and support smartcasts only for assign-once properties for now.

## Compilation Strategy

If we adopt the language-builtin design for assign-once properties,
compiling them as delegated properties is an unobvious choice.
Naturally, one could consider compilation schemes similar 
to the `lateinit var`.
Implementing assign-once properties by a backing field
might be simpler and more performant, 
saving an allocation compared to the delegation approach.

In this case, the backing field should be private
so that it could not be modified externally,
potentially breaking assign-once semantics.
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

This makes us believe that compilation to delegated properties
is simpler overall, 
while it provides a consistent experience,
allowing nullable types and synchronization customization.
However, it comes at the price of a slightly less
convenient interaction with DI annotations.

## `StableValue`

[JEP-502](https://openjdk.org/jeps/502) proposed to introduce `StableValue` API,
which could potentially serve as a compilation target for assign-once properties on JVM.
However, `StableValue` was removed in JDK 26.
It's successor, [JEP-526: `LazyConstant`](https://openjdk.org/jeps/526), does not fit the use-case.



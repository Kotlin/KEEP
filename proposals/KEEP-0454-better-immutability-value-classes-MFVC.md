# Better Immutability in Kotlin aka "Value Classes 2.0": Multi-Field Value Classes

* **Type**: Design proposal
* **Author**: Marat Akhin
* **Contributors**: Roman Elizarov, Nikita Bobko, Komi Golova, Pavel Kunyavskiy, Alejandro Serrano Mena, Evgeniy Moiseenko, Alexander Udalov, Wout Werkman, Mikhail Zarechenskiy, Evgeniy Zhelenskiy, Filipp Zhinkin
* **Status**: Public discussion
* **Discussion**: TBD
* **Related YouTrack issue**: [KT-77734](https://youtrack.jetbrains.com/issue/KT-77734)

## Abstract

> Note: the motivation behind the improvements to immutability in Kotlin is covered in their respective [KEEP](TBD).

This proposal talks about the introduction of Multi-Field Value Classes (MFVC) to Kotlin, and presents their design and implementation details.
We discuss what MFVCs represent, what their limitations are, how they interoperate with different platforms which Kotlin supports, and what the migration story around them is.

## Table of Contents

- [Introduction](#introduction)
- [Design](#design)
  - [Multi-Field Value Classes](#multi-field-value-classes)
    - [MFVC Primary Constructor](#mfvc-primary-constructor)
    - [MFVC and Inheritance](#mfvc-and-inheritance)
    - [Abstract MFVC vs Interfaces](#abstract-mfvc-vs-interfaces)
    - [MFVC and `equals`](#mfvc-and-equals)
    - [Value Objects](#value-objects)
    - [Early Initialization](#early-initialization)
    - [Representation](#representation)
    - [Migration](#migration)
      - [Migration between Different Kinds of Value Classes](#migration-between-different-kinds-of-value-classes)
      - [Migration between Non-Value and Value Classes](#migration-between-non-value-and-value-classes)
      - [Migration between Data and Value Classes](#migration-between-data-and-value-classes)
    - [Standard Library](#standard-library)
    - [Other Features and Interactions](#other-features-and-interactions)
      - [MFVC and `===`](#mfvc-and-identity)
      - [MFVC and Smart Casts](#mfvc-and-smart-casts)
      - [MFVC and Compose](#mfvc-and-compose)
        - [Strong Skipping](#strong-skipping)
        - [State Storage and Change Tracking](#state-storage-and-change-tracking)
      - [MFVC and Interop](#mfvc-and-interop)
    - [Possible Extensions](#possible-extensions)
      - [Migration from Stage 0 to Stage 1 via `@JvmExposeBoxed`](#migration-from-stage-0-to-stage-1-via-jvmexposeboxed)
    - [Dependencies](#dependencies)
    - [Summary](#summary)
- [Call to Action](#call-to-action)
- [References](#references)

## Introduction

Adding better immutability to Kotlin is impossible without first introducing a way to describe immutable composite data, consisting of not one (as supported by the current inline value classes), but multiple separate values.
Instead of going for an all-encompassing immutability design, which immediately supports every possible immutability use-case, we propose to approach the problem incrementally, by extending current single-field value classes to support multiple fields.

We also postpone the goal of having definite performance optimizations for value classes to a later stage, and focus on their (shallow) immutability nature.
At the same time, the design allows enough space to easily support these optimizations in a backwards compatible manner.

## Design

### Multi-Field Value Classes

**TL;DR Proposal:** Remove the current restriction that a Kotlin value class can only have one property.
We allow *multiple* primary properties in a value class declaration, backed by stored fields, turning it into a general mechanism for defining **composite value types**.
In short, we are adding multi-field value classes (MFVC).

A value class is still declared with the `value` modifier, but now it can have more than one primary read-only `val` property. For example:

```kotlin
value class Complex(val re: Double, val im: Double)
```

MFVCs represent **shallow immutable**, **value-based** types; e.g., two `Complex` instances with the same `re` and `im` are considered equivalent in all respects, and there is no (immediate) way to distinguish them via identity.
This is because the `===` operator is forbidden on MFVCs, as it is for inline value classes currently.

In other words, their equality is structural.
This is achieved by generating structural implementations of `equals` and `hashCode` functions, similarly to how it is done for data classes.
We also generate a matching `toString` implementation.

Continuing this line of thought, one could say that MFVCs are shallow immutable data classes without identity, and that is actually a "good enough" mental model in many cases.

As for data classes, the state of MFVCs is represented by their primary properties.
However, as they are value-based, this primary state is the *only* thing which is actually stored.
If an MFVC declares some additional properties, they cannot be stored properties, i.e., cannot have a backing field or be implemented via a delegate.

Unlike data classes, MFVCs do not support positional-based destructuring, they should be used together with [name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md).

#### MFVC Primary Constructor

As with data classes, the primary properties of MFVC are also special, for example, they are used in the implementations of their `equals` / `hashCode` / `toString` functions.
With MFVCs being value types, however, their primary properties are even more special, as they fully describe the value shape, i.e., what the value represented by the MFVC actually consists of.

This shape is important, for example, for the purposes of MFVC copying: as the state of an MFVC object is fully defined by its primary properties, one can always recreate it from individual properties.
Also, as the primary properties are stored, the compiler can implement more optimizations of how an MFVC is represented at runtime, knowing what the full MFVC state is.
Another way we can use the primary constructor is for implementing ergonomic updates of primary properties, as they are by definition connected to a way to (re)construct the MFVC object.

In turn, this means that the MFVC primary constructor (and its set of primary properties) should have more stability requirements compared to a regular constructor.

For regular classes, primary properties are mainly a convenience: if you want to change the class' data, you can move the stored state elsewhere, override `equals`/`hashCode`, and still preserve the source and/or binary compatibility.

However, the same is not true for MFVCs, for which primary properties *are* the value: they completely define stored state, structural equality, and copying behavior; and the compiler should be free to use all of these for better performance and memory optimizations of value objects.
This means that moving a property from or to primary properties is a breaking change, and there is no intended way to preserve compatibility.

As a consequence, because primary properties are guaranteed to be stored fields without custom getters, the compiler can rely on their stability for smart casts across module boundaries (see [MFVC and Smart Casts](#mfvc-and-smart-casts)).

#### MFVC and Inheritance

Another difference from data classes is that MFVCs can be abstract, in which case all the usual rules of abstract declarations apply.
Additionally, no properties of an abstract value class can have backing fields, meaning they are either abstract or have custom getters and/or setters.
This means they also cannot have delegated properties (as they need a backing field to store the delegate).

This restriction comes from the MFVCs being value types; if a value type can be created, it should be able to fully control how its state is stored.
As abstract value classes cannot be created, they only describe the shape of the data, but the storage is handled by their concrete inheritors.
This avoids the need to think about partial state w.r.t. value semantics, and also allows one to more easily and efficiently implement optimizations for such value types.

For the same reason, open MFVCs are prohibited.

```kotlin
// If we allow partial state and/or open value classes

abstract /* or open */ value class Base(val x: Int)

value class Derived(val y: Int, val z: Int = 41) : Base(42)

fun test() {
  val a: Base = Derived(43)
  val b: Base = Derived(44)

  println(a == b) // What should this print?
  println(a === b) // And what should this print?

  val base: Base = Base(42)
  println(base == a)
  println(a == base) // And what about this?

  println(base === a)
  println(a === base) // Or about this?
}
```

> Note: while we could allow open MFVCs with no stored state, we believe this case is rare enough that it does not warrant adding as an exception.
> Also, this will not be supported by project Valhalla, meaning our compilation for such stateless open MFVCs would need to be significantly more complicated.

> Note: while project Valhalla allows for Java abstract value classes to have fields, we believe the majority of them will not have any actual stored state.

For final and abstract MFVC, they can inherit from interfaces (as current inline value classes) and from abstract value classes.

This means that value classes cannot inherit from non-value classes (but the opposite is allowed).
The mental model for this restriction is the following: non-value classes require identity, value classes do not have identity.
And you can add identity to a type without one (inherit a non-value class from a value one), but cannot take the identity away (inherit a value class from a non-value one).

#### Abstract MFVC vs Interfaces

With the restriction on the stored state, an abstract MFVC looks very similar to an interface, and that is true.
Their main difference is that, while interfaces do not say anything about their relation with identity, abstract MFVCs state that they are identity-less.

Their main use-case is to represent common implementations of identity-less types aka serve as root types for value hierarchies.

Alternatively, we could introduce a `value interface` concept, but it does not have the same clear separation of identity-ness.

* Regular classes require identity, value classes require identity-less
* Regular interfaces are identity-agnostic, value interfaces require identity-less

This makes the story for potentially mixed "value -> non-value -> value" interface hierarchies more complicated.

Also, as identity (or not having one) is a property of an instantiated object, and objects always have a *single* runtime class, it is more logical to associate "valueness" with classes and not interfaces.

#### MFVC and `equals`

We will also be lifting the current restriction on inline value classes, which are not allowed to override `equals` and `hashCode`.
This original restriction was motivated by the following observation: if we allow overriding `equals(other: Any?)`, then for inline value classes which did override it, `==` comparisons must box the compared values to conform to `Any?`.
And for *inline* value classes we consider such missing optimization as a problem.

Once MFVCs are released, the situation will be as follows.

* If your value class does not override `equals`, the compiler would try to avoid extra boxing if possible.
    * The cases when it is immediately possible would be: `@JvmInline` value classes on JVM, value classes with a single property on non-JVM.
    * In other cases, this optimization is not applied.
* If your value class overrides `equals(other: Any?)`, this optimization is not applied.

As a solution to this, we are working on [custom typed `equals` / `hashCode`](https://youtrack.jetbrains.com/issue/KT-24874) design for (inline) value classes, which would allow us to avoid boxing if it is redundant.
When it is implemented, you would be able to migrate your value classes to typed `equals`, to allow the Kotlin compiler more optimization opportunities.

> Note: these opportunities could also include previously unavailable cases such as MFVC.

#### Value Objects

Similarly to [data objects](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0317-data-objects.md), once we allow MFVC with 1+ primary properties, it makes sense to generalize and also allow MFVC with 0 primary properties aka value objects.
In the same fashion, their main use is to represent unit types.

The difference between `data object` and `value object` mirrors the difference between `data class` and `value class`: data objects have identity, value objects do not.

For data objects, while they are intended to be singletons, you can still observe different instances via referential equality (`===`): for example, through deserialization or reflection.
We discourage relying on referential equality for data objects, but do not prohibit it.
If used incorrectly, this can lead to subtle bugs when code assumes singleton identity but encounters another instance.

For value objects, this problem does not exist: since `===` is disallowed on value types, you cannot observe whether two references point to the same instance or different instances.
When we get a proper identity-less compilation scheme, the compiler is free to select a compilation scheme which stores the singleton instance (as we currently do for objects) or one which "recreates" it on-the-fly whenever it's needed.

> Note: the second option could be extended to support erased parameters or arguments similar to what is available in other programming languages, but we leave this as future work.

```kotlin
sealed interface UserResponse

value object UserUnknown : UserResponse

fun process(...): UserResponse {
  var resp: UserResponse
  // ...
  if (cannotFindUser) {
    resp = UserUnknown
    // could be compiled to
    resp = UserUnknown.getInstance()
    // or to
    resp = UserUnknown()
  }
  // ...
  return resp
}
```

#### Early Initialization

When thinking about MFVCs, we need to consider the restrictions that come from our target platforms, e.g., from project Valhalla.
Some of the restrictions around inheritance we've already covered in the corresponding [section](#mfvc-and-inheritance).

Another important restriction is the change to the object initialization model discussed in [JEP 401](https://openjdk.org/jeps/401) and [JEP 513](https://openjdk.org/jeps/513).

Project Valhalla states that all fields of a value class must be initialized *before* the object becomes observable (i.e., before `super()` call where `this` can leak).
This is more restrictive than regular class initialization today, where initializers run *after* the `super()` call and fields could be seen in a partially initialized state.

As discussed in [JEP 513](https://openjdk.org/jeps/513), this difference can be explained as follows.

```
// Regular initialization

D calls super()
--> C calls super()
    --> B calls super()
        --> A calls super()
            --> java.lang.Object constructor body
        --> A constructor body
    --> B constructor body
--> C constructor body
D constructor body
```

With regular initialization, while the constructors are invoked bottom-up, starting from the derived and going up to the hierarchy root (`java.lang.Object`), the constructor bodies are executed top-down: `java.lang.Object` constructor runs first, then the constructor for the next base class, and so on, and it *ends* with running the constructor for the most derived class.

```
// Early initialization

D prologue
D calls super()
--> C prologue
    C calls super()
    --> B prologue
        B calls super()
        --> A prologue
            A calls super()
            --> java.lang.Object constructor body
        --> A epilogue
    --> B epilogue
--> C epilogue
D epilogue
```

With early initialization, the constructor body is split into two parts: prologue and epilogue.
And the prologues are executed bottom-up, which allows one to ensure part of (or all) initialization is completed before it is continued up the hierarchy.

At the moment, the code which is allowed in the prologue *by the JVM* is relatively restricted.
Specifically, it cannot access `this` instance (which is being initialized) besides assigning its fields; e.g., one cannot call instance methods on `this` or read its fields, but can declare local variables or call static methods.

The requirement for project Valhalla value classes is that all fields are assigned in the prologue, before a call to `super()`.
Together with the inheritance restriction (value classes can inherit only from other value classes), this means that the *complete instance state* is always definitely initialized before `this` becomes published.

To align Kotlin MFVCs with Valhalla restrictions, we need to change the way initialization is done for Kotlin value classes.
Specifically, initialization of primary properties is moved *before* a call to `super()`.

```kotlin
value class Complex(val re: Double, val im: Double) {
    init {
        // ...
    }
}

// Before

fun <init>(re: Double, im: Double) {
    super()
    this.re = re
    this.im = im
    // the rest of the initialization
}

// After

fun <init>(re: Double, im: Double) {
    this.re = re
    this.im = im
    super()
    // the rest of the initialization
}
```

This does not change the behavior of current inline value classes, as they cannot inherit from other classes, and for them a call to `super()` is effectively a no-op.
This is true for all target platforms.

> Important: the current Valhalla design is even more restrictive and states that, by default (if there is no explicit call to `super()`), the complete constructor body of a value class is in the prologue.
> Supporting this depends on designing and implementing a mirror of JEP 513 for Kotlin aka the ability to specify when a call to `super()` happens in the initialization sequence.
> If that design is ready before MFVCs become stable, we can make the default initialization restrictions for Kotlin value classes stronger.

In summary, the key change is that Kotlin MFVC primary properties will be initialized *before* the `super()` call, aligning with project Valhalla's early initialization model and ensuring the complete value state is always available before the object becomes observable.

#### Representation

Unlike current (`@JvmInline`) inline value classes, MFVCs are **not** inlined by the Kotlin compiler.
They are designed to work as shallow immutable value-based types first and foremost, with no immediate performance guarantees.
Thus, their underlying representation will depend on the platform.

> Important: by focusing on immutability first and postponing optimizations, we remove the dependency of MFVC on the release of project Valhalla and its availability for JDK and Android.

By default, an MFVC object is a reference-based box, meaning that at runtime it is possible to have two different references pointing to two different boxes, which hold the same values.
However, as it is (shallow) immutable and uses structural equality, it respects the value semantics: it is practically impossible to observe the different boxes.

* `==` / `equals` / `hashCode` are structural and do not depend on the box
* `===` / `identityHashCode` are disallowed on MFVC objects
* Immutability means it is impossible to change the (shallow) values inside the boxes, to make them distinguishable

```kotlin
value class User(val name: String, val metadata: MutableMap<String, String>)

fun tryToDistinguish() {
  val metadata: MutableMap<String, String> = mutableMapOf()

  var a: User = User("Marat", metadata)
  var b: User = User("Marat", metadata) // make two different boxes with the same values

  println(a == b) // true

  b.metadata["Address"] = "Gelrestraat 16" // also changes `a.metadata`

  println(a == b) // still true
}
```

> Important: if a reference to an MFVC object "leaks" as a reference to `Any` or some interface, it is possible to observe two different MFVC boxes.
> This is something already possible for the current inline value classes also, and we are not aware of any important cases when this is actually problematic.
> Meaning this MFVC representation, while not perfect, is pragmatic enough for most practical cases.
> 
> The design trade-offs around `===` behavior for value types are discussed in detail in [MFVC and `===`](#mfvc-and-identity).

#### Migration

##### Migration between Different Kinds of Value Classes

Our approach to MFVCs and their runtime representations creates some complications w.r.t. migrations.
In essence, it means we have three stages of how one can declare a value class.

1. (Migration Stage 0) `@JvmInline` inline value class / single-property inline value class on other platforms
2. (Migration Stage 1) Multi-field value class before any optimizations (aka represented as regular class)
3. (Migration Stage 2) Multi-field value class with optimizations (aka represented as Valhalla value class or optimized by the Kotlin compiler)

The change from Stage 0 to Stage 1 is the one which is unfortunately a breaking change.
`@JvmInline` value classes are inlined, and that is visible in the ABI.
If one wants to migrate as follows:

```kotlin
// Inline value class
@JvmInline
value class Color(val code: Int) // (0)

// MFVC with a single property
value class Color(val code: Int) // (1)
```

they cannot do this, as the (0) version is used in its inlined form in the compiled code, whereas (1) version works via the boxed representation.

If we were to support this migration, we would need to have a mechanism to compile value classes in both representations, so that already compiled code can use the (0) version and the new code can target the (1) version.

To understand if we actually need this, we need to discuss whether this migration has important use-cases.
We currently believe that in most cases `@JvmInline` value classes are used for their performance optimizations first, and for their immutability second.
This means that the migration from (0) to (1) is not a very immediate problem.

> Note: one potential solution using `@JvmExposeBoxed` annotation is discussed in the [Possible Extensions](#possible-extensions) section.

The change from Stage 1 to Stage 2, on the other hand, should be fully seamless.
The reason for this is as follows.

* On the JVM, we will change the compilation from regular to value classes, but up to our current knowledge this change will be binary compatible.
  While Valhalla value classes have additional restrictions, these restrictions are respected by the MFVC restrictions enforced by the Kotlin compiler.
* On other platforms, we operate within the closed world compilation model, which means that the optimizations are done during the final compilation and their presence or absence does not influence the binary compatibility.

##### Migration between Non-Value and Value Classes

Another migration story is when one wants to migrate their already value-like reference-based types to proper value classes.
For example, this is what is currently being planned for such JDK types as `java.util.Optional` or `java.time.Duration`.
For Kotlin, some examples of these could be `kotlin.time.Instant` and `kotlin.uuid.Uuid`.

As we've already established, the main difference between reference and value classes is the stability of their identity.
Once a reference class becomes value-based, all identity-sensitive operations on it might change their behavior.
These operations include (but probably are not limited to):

* `===` aka reference equality
* `identityHashCode` or similar operations
* synchronization on instances of `Foo`
* intrinsic reference-based APIs such as `WeakReference`

If your type is intended to be used with these operations, it probably should not be changed to a value class.

For cases when one expects to do this migration (e.g., Kotlin standard library), one of the possibilities to smooth the migration is to introduce a Kotlin-specific version of [`@ValueBased` JDK annotation](https://openjdk.org/jeps/390).
This annotation is a way to mark future-to-be value classes which are currently reference classes.
This is used to warn and/or prevent using identity-sensitive operations on them.

```kotlin
package kotlin

@Target(CLASS)
@Retention(BINARY)
@SinceKotlin("2.X")
annotation class WillBecomeValue // Actual name to be decided later
```

Kotlin already supports [reporting warnings](https://youtrack.jetbrains.com/issue/KT-70722) for such Java classes.
Unfortunately, `@ValueBased` is still an internal annotation not available outside the JDK.
By introducing `@WillBecomeValue`, we can support the same migration story, but for the Kotlin (standard) libraries and all our platforms, not only JVM.

##### Migration between Data and Value Classes

Yet another opportunity is for libraries to migrate some of their existing (data) classes to value classes, especially those that already represent immutable values.
A prime example from our own standard library is `Pair` (and `Triple`), which semantically should be value types.

However, many such candidates are currently declared as `data class`, which creates a migration challenge: data classes automatically generate `componentN()`, `copy()`, `equals()`, `hashCode()`, and `toString()` methods, while value classes only generate the latter three.
Simply changing `data class` to `value class` would remove `componentN()` and `copy()`, breaking source and binary compatibility of existing code.

The core reason for this is: `data class` currently conflates two orthogonal concerns, the "data carrier" semantics and the generation of convenience methods.
A cleaner design would decouple these by introducing a way to control the generation independently of the class kind.

> Note: this idea has multiple different tracking issues, e.g., [KT-8466](https://youtrack.jetbrains.com/issue/KT-8466) or [KT-4503](https://youtrack.jetbrains.com/issue/KT-4503).

One of the potential ways to surface this in the language is a special annotation, but there could be other alternatives.
The one presented here is back-of-napkin syntax to explain the idea.

```kotlin
@GenerateDataClassMethods(
    components = GENERATE,  // or NONE, or DEPRECATED, or HIDDEN
    copy = GENERATE,
    equals = GENERATE,
    hashCode = GENERATE,
    toString = GENERATE
)
class Foo(val x: Int, val y: String)
```

With this approach, `data class` becomes syntactic sugar for a class with all parameters set to `GENERATE`.

```kotlin
// These two declarations are equivalent
data class Foo(val x: Int, val y: String)

@GenerateDataClassMethods(components = GENERATE, copy = GENERATE, ...)
class Foo(val x: Int, val y: String)
```

This enables a gradual migration path for types like `Pair`.

```kotlin
// Step 1: Current state
data class Pair<A, B>(val first: A, val second: B)

// Step 2: Migrate to value class, keep generated methods
@GenerateDataClassMethods(components = GENERATE, copy = GENERATE)
value class Pair<A, B>(val first: A, val second: B)

// Step 3: Deprecate generated methods to guide the users toward alternatives
@GenerateDataClassMethods(components = DEPRECATED, copy = DEPRECATED)
value class Pair<A, B>(val first: A, val second: B)

// Step 4: Hide generated methods
@GenerateDataClassMethods(components = HIDDEN, copy = HIDDEN)
value class Pair<A, B>(val first: A, val second: B)

// Step 5: Remove annotation entirely once compatibility is no longer needed
value class Pair<A, B>(val first: A, val second: B)
```

For `componentN()`, deprecation aligns with our move toward [name-based destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md).
For `copy()`, the situation is more nuanced: while we are designing ergonomic update mechanisms for value classes, `copy()` is the primary way to work with immutable data today, and we may need to continue supporting it (possibly with warnings) to support smoother migration.

> Note: value classes already generate structural `equals()`, `hashCode()`, and `toString()` by default, so the annotation on a value class would primarily control `componentN()` and `copy()`.

This design will be refined as we gain more experience with MFVCs and as related features (ergonomic updates, name-based destructuring) mature.

#### Standard Library

With the migration paths described above, we can outline how the Kotlin standard library itself would adopt MFVCs.
The standard library contains types that fall into each of the three migration categories.

**Non-value to value class migration.**
Types like `kotlin.time.Instant` and `kotlin.uuid.Uuid` are currently reference-based classes that are semantically value types: they have no meaningful identity, and all identity-sensitive operations on them (reference equality, synchronization, etc.) are unintended uses.
These are prime candidates for the [`@WillBecomeValue`](#migration-between-non-value-and-value-classes) annotation path.
By annotating them with `@WillBecomeValue` early, we give our users advance warning that identity-sensitive operations on these types will eventually change behavior, and allow IDEs and the compiler to report diagnostics before the actual migration happens.
Once the migration is performed, these types become full MFVCs, benefiting from the value semantics guarantees and potential runtime optimizations.

**Data class to value class migration.**
`Pair` and `Triple` are the canonical examples discussed in the [data-to-value class migration](#migration-between-data-and-value-classes) section.
They are semantically value types, but are currently declared as `data class`es, which means changing them to `value class` would break source and binary compatibility by removing `componentN()` and `copy()`.
The `@GenerateDataClassMethods` annotation path (or its analogue) enables their gradual migration: first preserving all generated methods, then deprecating them (aligning `componentN()` deprecation with the move towards name-based destructuring), and eventually removing them once compatibility is no longer needed.

**Existing inline value classes.**
The standard library already contains a number of `@JvmInline value class` types, including `Duration`, `UInt`, `ULong`, `UByte`, `UShort`, and `Result`.
These types are inline primarily for performance: they avoid heap allocation by erasing to their underlying type at runtime.
For most of them, staying as single-field inline value classes is the right default, as they do not need additional fields.
The [Stage 0 → Stage 1 migration](#migration-between-different-kinds-of-value-classes) path exists for cases where an existing inline value class needs to evolve to support richer state (e.g., adding fields) or other improvements, but we do not currently anticipate this being necessary for the existing stdlib inline types.

More detailed migration story for the Kotlin stdlib will be covered separately.

#### Other Features and Interactions

<a id="mfvc-and-identity"></a>

##### MFVC and `===`

One subtle part of the design is how MFVCs interact with *interfaces*, *generics* and *reference equality (`===`)*.

Just as for inline value classes, the use of `===` on MFVC instances is disallowed.
Since MFVCs have no stable identity, comparing them by reference cannot yield meaningful, predictable information.

However, things get trickier once an MFVC value is passed as *a generic type, as `Any` or as an interface it implements*.
In that case, you can observe that the runtime may allocate two distinct boxes for what is semantically "the same" value.

```kotlin
val a = Complex(1.0, 2.0)
val b = Complex(1.0, 2.0)

infix fun <T> T.isReferenceEqualTo(t: T): Boolean = this === t

println(a isReferenceEqualTo b)  // could be false if different boxes are compared
```

> Note: an opposite problem will be encountered by Java users when project Valhalla releases, and `@ValueBased` classes (such as `java.lang.Integer`) become value classes.
> Whereas now you can distinguish between two differently allocated `java.lang.Integer` instances, with project Valhalla they become indistinguishable, as Java's `==` on value objects performs a *substitutability test* (structural comparison) rather than identity comparison, even when the value is accessed through an `Object` reference or generic type parameter.

At the moment, we do not believe this is something which will be often encountered by Kotlin users, and thus do not consider this as a blocker for MFVC design.

If this changes at some point, we could devise a solution, but all the approaches we have considered so far carry a heavy cost.

One possible option for compile-time guarantees is to restrict the use of `===` in error-prone cases (e.g., for generics).
For example, we could say `===` is available only on types which are not `Value`, e.g., for `T : !Value`.
That would require serious changes to the Kotlin type system and the introduction of limited negative types, or for the users to manually propagate these negative type bounds across their code.

A runtime option would be to implement stricter runtime checks for `===` in debug / test mode, which log and/or panic in situations when `===` is used on value objects.
As such checks would have a severe performance penalty, doing it always would be undesirable.
This is less intrusive than previous option, as it does not require one to change their code, but is somewhat foreign to the Kotlin ecosystem, which usually does not distinguish between release and debug configurations that much.

> Note: at the same time, there are plans to allow more runtime checks for debug builds on Kotlin/Native ([KT-71000](https://youtrack.jetbrains.com/issue/KT-71000)).
> This means that the runtime option, while unusual, is potentially also a possible solution.

Another runtime option is to consistently change the semantics of `===` itself for value objects on *all* platforms: instead of performing identity (reference) comparison, `===` on value objects would perform a *substitutability test*.
This approach directly eliminates the box-identity problem: two boxes holding the same value would always be `===`, making the behavior predictable and consistent.
It also aligns with the direction taken by project Valhalla, where Java's `==` on value objects performs a substitutability test even through an `Object` reference or generic type parameter.

On non-JVM platforms, there is a non-trivial runtime cost: the implementation of `===` would need to inspect the runtime type of its arguments, detect that they are value objects, and fall back to structural comparison.
It would be a significant implementation effort to minimize the performance impact of such change.

##### MFVC and Smart Casts

Another interesting interaction of MFVC is with *smart cast mechanism*.

Smart casts in Kotlin rely on the compiler proving that a reference is immutable along all its observable paths.

```kotlin
data class User(val name: String?)

fun printName(user: User?) {
    if (user?.name != null) {
        // safe: compiler knows name cannot suddenly change to `null`
        println(user.name)
    }
}
```

With regular identity-based classes, the compiler is conservative: even trivial `val` properties (without a custom getter) are not considered stable w.r.t. smart casts across different modules.
The reason being that one could add a custom getter in a future version, which would make the smart cast unsafe.

```kotlin
// module lib
data class User(val name: String?)

// module app
fun printName(user: User?) {
    if (user?.name != null) {
        // unsafe: in future versions, name could have a custom getter
        println(user.name)
    }
}
```

For value classes, however, we could stipulate a stronger guarantee.
As we already [discussed](#mfvc-primary-constructor), their primary constructor and its primary properties are special, as they describe the stored value, and this is used by the compiler at compile- and run-time.

This allows us to say that primary properties of an MFVC are stable parts of its API / ABI, which means that we can rely on their stability for smart casts.

##### MFVC and Compose

Compose uses a stability system to decide whether composable functions can be skipped during recomposition: a type is *stable* if Compose can determine whether a value has changed between recompositions.
The Compose compiler plugin infers stability by examining class structure: all-`val` properties with stable types make a class stable; any `var` property, or a property whose type comes from an external module without Compose compiler support, makes it unstable.

MFVCs satisfy these requirements structurally.
Their primary properties are `val` and they are guaranteed stored fields.
As a result, an MFVC whose primary property types are themselves stable could be automatically inferred as stable by the Compose compiler, with no annotation needed.

This is the same mechanism that makes value-like data classes stable, but there is an important difference.
The Compose compiler currently treats any class from an external module that was not itself compiled with the Compose compiler as unstable, regardless of its properties.
For MFVCs, thanks to their restrictions, it should be safe for the Compose compiler to do cross-module stability inference on them.

If an MFVC has a primary property of an unstable type (e.g., `List<T>` from the standard library, which could be a `MutableList` at runtime), the Compose compiler infers the MFVC as unstable.
Current fix would be the same as for data classes: using `kotlinx.collections.immutable` types, or applying `@Stable` / `@Immutable` explicitly.
In the future, once we have deep immutability for MFVC, they would represent the compile-time checked stable types for Compose.

###### Strong Skipping

Starting with Kotlin 2.0.20, *strong skipping mode* is enabled by default.
Without strong skipping, a composable function with any unstable parameter is always recomposed: it cannot be skipped at all.
With strong skipping, such a composable becomes skippable, but the runtime uses instance equality (`===`) rather than `equals()` to decide whether an unstable argument has "changed".
In other words, strong skipping is an optimistic performance heuristic which trusts that in most cases for unstable types their updates create a new instance.

Because MFVCs are shallow immutable, this assumption often holds: state updates produce new instances rather than mutating existing ones.
This means that it is significantly harder to erroneously skip recomposition over MFVCs.
At the same time, as we do not provide a stable identity for value objects, `===` could return `false` if we decided to re-create the box of an object, causing an unneeded recomposition.

Dealing with this is dependent on how we implement the [`===` for MFVC](#mfvc-and-identity).
An option which is always available to us would be to update strong skipping in Compose to use `equals` for unstable MFVCs.

###### State Storage and Change Tracking

Compose's `MutableState<T>` stores its value in a `StateStateRecord<T>` whose field is typed `var value: T`.
This allows only for "all-or-nothing" change tracking for the complete state, but not for its individual parts.

MFVCs open a natural path to support tracking of composite data, thanks to the properties of their [primary constructor](#mfvc-primary-constructor) and them being value types.
Compose compiler could track individual properties of an MFVC state, their reads and their updates, and fine-tune its recomposition engine to consider partial state dependencies.

```kotlin
@Composable
fun UserView(user: User) {
  Row {
    Text("${user.name}")
    Separator()
    Text("(${user.nickname})")
  }
}
```

Given the properties of MFVCs, Compose can analyze `fun UserView` and record that it needs restarting only when either `user.name` or `user.nickname` are updated.
This fundamentally means that we get more fine-grained state change tracking for MFVC parameters of composable functions, without the need for manual boilerplate.

```kotlin
@Composable
fun UserView(name: String, nick: String) {
  Row {
    Text("$name")
    Separator()
    Text("($nick)")
  }
}
```

Further improvements are possible, once we get ergonomic updates, as they could be made trackable by Compose also.

##### MFVC and Interop

For interop, there are two different stories, for [migration stage 1 and 2](#migration-between-different-kinds-of-value-classes).

For migration stage 1, when we are still compiling MFVCs as their boxed representation, interop is relatively easy, as it is basically interop with regular Kotlin classes.

Developers can start using MFVCs immediately even in their multiplatform code, which is good, but their uses will not be 100% idiomatic on their respective platforms.
Also, as they would change their representation in the future, it would be beneficial to support warnings similar to the ones we discussed for [Non-Value to Value class migration](#migration-between-non-value-and-value-classes) on each specific platform.

For migration stage 2, the interop story depends on whether we are working with reference-based platforms (JVM / JS) or platforms with proper value type support (Native / Wasm).

For reference-based platforms, we (for the most part) continue compiling to and interoperating via boxed representations.
On the project Valhalla JVM, these boxes will be value classes, but most of the "valueness" is done by the JVM, invisible to us.
So the API / ABI and the interop layer remain the same as for stage 1.

For platforms with value types, the story is more interesting.
The easier default is to say nothing changes, and MFVCs are exposed as their boxed versions, same as for migration stage 1.

Alternatively, Native and Wasm could expose MFVCs as structures.
If so, we would need to make sure they are never subject to arbitrary in-place mutation aka never exposed via pointers.
A more in-depth design for advanced interop on these platforms will follow.

#### Possible Extensions

##### Migration from Stage 0 to Stage 1 via `@JvmExposeBoxed`

For cases where migration from `@JvmInline` value classes (Stage 0) to MFVC (Stage 1) is needed, the [`@JvmExposeBoxed`](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0394-jvm-expose-boxed.md) annotation provides a gradual migration path.

> Important: at the moment, we believe that this migration will be needed only for very specific cases of inline value classes, and the majority of them could remain `@JvmInline`.

The core problem is that `@JvmInline` value classes use an inlined (unboxed) representation in JVM bytecode, while Stage 1 value classes use a boxed representation.
Switching directly would break binary compatibility for all code which uses a value class in signatures.

`@JvmExposeBoxed` addresses this by generating public boxed variants of value class members alongside the optimized inlined variants.
This enables a step-by-step migration:

| Annotations                                     | Inlined | Boxed | Preferred |
|-------------------------------------------------|---------|-------|-----------|
| `@JvmInline`                                    | Yes     | No    | Inlined   |
| `@JvmInline @JvmExposeBoxed`                    | Yes     | Yes   | Inlined   |
| `@JvmInline(deprecated = true) @JvmExposeBoxed` | Yes     | Yes   | Boxed     |
| (none)                                          | No      | Yes   | Boxed     |

> Note: the `@JvmInline(deprecated = true)` is a back-of-the-envelope syntax to illustrate the concept; the actual syntax may differ in the final design.

The migration strategy is:

1. Add `@JvmExposeBoxed` to expose both inlined and boxed representations
2. Mark the inlined representation as deprecated via `@JvmInline(deprecated = true)`, which makes the compiler prefer the boxed representation and warns users still relying on inlined variants
3. Eventually remove `@JvmInline` entirely, leaving only the boxed representation

This allows library authors to migrate their value classes from Stage 0 to Stage 1 while giving downstream users time to adapt their code.

#### Dependencies

MFVC release is tentatively dependent on the following features.

* Introduction of `kotlin.WillBecomeValue` (final name to be decided) annotation for a better migration story of libraries.
* Custom `equals` / `hashCode` for value classes, to unlock more potential for compiler optimizations of `==` comparisons.
* Name-based destructuring, to support convenient deconstruction of value objects.
* Early initialization design, to be able to make the early initialization of value classes even more flexible.

#### Summary

With MFVCs, one gets the ability to model more complicated immutable data than is possible now (aka with multiple properties), while also getting DX improvements in other areas (such as smart casts).
The current design focuses on their value and shallow immutable nature, and the future introduction of project Valhalla on the JVM and Kotlin-specific optimizations on other platforms would allow us to get additional performance benefits.

However, mutating MFVCs is painful, as you need to create a new instance via a constructor call.
To solve this, we will be designing and implementing a way to support ergonomic updates of value classes, once MFVCs are available as an experimental feature.
The respective KEEP will become available some time later.

## Call to Action

We are interested in your feedback on the overall design for MFVCs, but are particularly interested in the following three aspects.

* Are there some hard requirements of MFVCs we missed which you would like us to cover?
* Would you (not) migrate some of your existing classes to MFVCs and what should be different in the migration story?
* How should `===` work for value types and how important is its performance for you?

> Important: if you (and you probably do) have some questions and feedback on other parts of the overall Better Immutability in Kotlin story, e.g., ergonomic updates or deep immutability, please send them either to the [Motivation KEEP](TBD) if they are high-level ones or to their specific KEEPs once they are published.

## References

* [Design Notes on Kotlin Value Classes](https://github.com/Kotlin/KEEP/blob/main/notes/0001-value-classes.md)
* [Multi-Field Value Classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0340-multi-field-value-classes.md)
* [Valhalla Benchmarks](https://github.com/zhelenskiy/valhalla-benchmarks)
* [JEP 401: Value Classes and Objects (Preview)](https://openjdk.org/jeps/401)
* [JEP 513: Flexible Constructor Bodies](https://openjdk.org/jeps/513)
* [JEP 390: Warnings for Value-Based Classes](https://openjdk.org/jeps/390)
* [KEEP-0104: Inline Classes](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0104-inline-classes.md)
* [KEEP-0317: Data Objects](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0317-data-objects.md)
* [KEEP-0394: `@JvmExposeBoxed`](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0394-jvm-expose-boxed.md)
* [KEEP-0438: Name-Based Destructuring](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0438-name-based-destructuring.md)

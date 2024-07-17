# Expose boxed inline value classes

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Mikhail Zarechenskii, Ilmir Usmanov
* **Discussion**: ??
* **Status**: ??
* **Related YouTrack issue**: [KT-28135](https://youtrack.jetbrains.com/issue/KT-28135/Add-a-mechanism-to-expose-members-of-inline-classes-and-methods-that-use-them-for-Java)
* **Previous proposal**: [by @commandertvis](https://github.com/Kotlin/KEEP/blob/commandertvis/jvmexpose/proposals/jvm-expose-boxed-annotation.md)

## Abstract

We propose modifications to how value classes are exposed in JVM, with the goal of easier consumption from Java. This includes exposing the constructor publicly, and giving the ability to exposed boxed variants of operations.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Design goals](#design-goals)
* [Expose factory method](#expose-factory-method)
  * [Serialization](#serialization)
  * [Other design choices](#other-design-choices)
* [Expose operations and members](#expose-operations-and-members)
  * [No argument constructors](#no-argument-constructors)
  * [Other design choices](#other-design-choices-1)
* [Further problems with reflection](#further-problems-with-reflection)
  * [Other design choices](#other-design-choices-2)
* [Comparison with Scala](#comparison-with-scala)

## Motivation

[Value (or inline) classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) are an important tool to create a _lightweight wrapper_ over another class. One important example are classes which introduce additional invariants over another one, like an integer which is always greater than zero.

```kotlin
@JvmInline value class PositiveInt(val number: Int) {
  init { require(number >= 0) }
}
```

In order to keep its lightweight nature, the Kotlin compiler tries to use the _unboxed_ variant -- in the case above, an integer itself -- instead of its _boxed_ variant -- the `PositiveInt` class -- whenever possible. However, in order to prevent clashes between different overloads, the name of those functions is _mangled_; those functions operate directly on the underlying unboxed representation, with additional checks coming from the `init` block.

```kotlin
fun PositiveInt.add(other: PositiveInt): PositiveInt =
  PositiveInt(this.number + other.number)

// is compiled down to code similar to
fun Int.add-1bc5(other: Int): Int =
  PositiveInt.check-invariants(this.number + other.number)
```

Note: we use `PositiveInt.check-invariants` for illustrative purposes, the [inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) defines the concrete compilation scheme that the compiler ought to follow.

The main drawback of this compilation scheme is that a type like `PositiveInt` cannot be (easily) consumed from Java (or other JVM languages other than Kotlin). One benefit is that library authors no longer need to choose between using a value class (which is more performant, but only available for Kotlin consumers) or a (data) class (which always requires boxing but can be used anywhere).

### Design goals

"Exposing" a `value` class is not a single entity. The following are some scenarios that we would like to support:

1. Creating boxed values: for example, creating an actual instance of `PositiveInt`.
2. Calling operations defined over value classes over their boxed representations.
3. Making inline value classes with Java-idiomatic patterns, especially those using reflection.

---

Apart from the particular problems, we set the following cross-cutting goals for the proposal.

**Minimize API surface duplication**: if we expose every single member and function taking a value class with the corresponding version using the boxed type, this essentially duplicates the API surface. Developers may want full control over which functions are exposed this way; on the other hand, having to annotate every single function is also tiring.
  
**Ensure that invariants are never broken**: especially in the "frontier" between Kotlin and Java, we need to provide an API that does all the corresponding checks. We are OK with introducing some performance penalties when using the API from Java, but no additional penalties should be paid if the class is used in Kotlin.

**Compatibility with current compilation scheme**: if we alter the way inline classes are currently compiled in a non backward compatible way, we could create a huge split in the community. Or even worse, we will end up supporting two different schemes in the compiler.

---

It is a **non-goal** for this KEEP to decide what may happen in a world in which value classes are part of the JVM platform ("post-Valhalla") and potentially do not need inlining and boxing. Any future KEEP touching those parts of the language should also consider its interactions with this proposal.

## Expose factory method

The [current compilation scheme](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#inline-classes-abi-jvm) exposes the constructor of the boxed class as _synthetic_, which makes it unavailable from Java. Note that this constructor does _not_ execute the `init` block, it's essentially boxing the value. We propose to introduce a static factory method `of` that executes any prerequisites and builds the value, with the same visibility as the constructor of the value class.

```kotlin
@JvmInline value class PositiveInt(val number: Int) {
  init { require(number >= 0) }
}

// compiles down to
class PositiveInt {
  public <init>(Int): void
  public constructor-impl(Int): Int  // executes the 'init' block
  // and others

  public static of(number: Int): PositiveInt =
    <init>(constructor-impl(number))
}
```

We think this is the right choice for a couple of reasons:

1. Using `of` as a factory method is well-known in the Java ecosystem (for example, to create collections);
2. It is binary compatible with previous users of this code.

### Serialization

`kotlinx.serialization` currently ensures that [value classes are correctly serialized](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/value-classes.md#serializable-value-classes), even when allocated. This must remain the case once this KEEP is implemented.

### Other design choices

**Put it under a flag**, in other words, only expose the boxed constructor when some annotation or compiler flag is present. In this case, we could not find a realistic scenario where this expose would be counter-productive; in the worst case in which you expose the constructor but not operations you just have a way to create useless values.

The only drawback is that the ABI exposed in the JVM changes from one version of the compiler to the next, albeit in a binary compatible way. There is a risk involved in downgrading the compiler version, as it may break Java clients, but this scenario is quite rare.

**Exposing a constructor directly**: instead of using a factory method, `PositiveInt.of(3)`, expose a constructor, `PositiveInt(3)`. The problem is that the current compilation scheme already defines a constructor, which does _not_ execute the `init` block. Changing the behavior is possible -- after all, we just add more checks -- but this may have a significant performance impact.

## Expose operations and members

The current compilation scheme transforms every operation where a value class is involved into a static function that takes the unboxed variants, and whose name is mangled to prevent clashes. This means those operations are not available for Java consumers. We propose introducing a new `@JvmExposeBoxed` annotation that exposes a variant of the function taking and returning the boxed versions instead (if more than one argument or return type is a value class, the aforementioned variant uses the boxed versions for _all_ of them). We call it the _boxed variant_ of the function.

The `@JvmExposeBoxed` annotation may be applied to a declaration, or a declaration container (classes, files). In the latter case, it should be taken as applied to every single declaration within it.

If the `@JvmExposeBoxed` annotation is applied to a member of a value class (or the value class itself, or the containing file), the boxed variant is compiled to a member function of the corresponding boxed class. The unboxed variant is still compiled as a static member of the class.

The name of the boxed variant coincides with the name given in the Kotlin code unless a `@JvmName` annotation is present. In that case, the name defined in the annotation should be used.

The following is an example of the compilation of some operations over `PositiveInt`. The `box-impl` and `unbox-impl` refer to the operations defined in the [current compilation scheme](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#inline-classes-abi-jvm) for boxing and unboxing without checks.

```kotlin
@JvmInline @JvmExposeBoxed
value class PositiveInt(val number: Int) {
  init { require(number >= 0) }

  fun add(other: PositiveInt): PositiveInt = ...
}

@JvmExposeBoxed
fun PositiveInt.duplicate(): PositiveInt = ...

// compiles down to
class PositiveInt {
  public <init>(Int): void

  public add(other: PositiveInt): PositiveInt =
    box-impl(add-1df3(unbox-impl(this), unbox-impl(other)))
  // mangled version
  public static add-1df3($this: Int, other: Int): Int 

  // and others
}

public duplicate($this: PositiveInt): PositiveInt =
  box-impl(duplicate-26b4(unbox-impl($this)))
// mangled version
fun duplicate-26b4($this: Int): Int
```

### No argument constructors

Frameworks like Java Persistence require classes to have a [default no argument constructor](https://www.baeldung.com/jpa-no-argument-constructor-entity-class). We propose to expose a no argument constructor whenever a default value is given to the underlying property of the value class (in addition to the factory methods). Continuing with our example of positive integers, the following code,

```kotlin
@JvmInline value class PositiveInt(val number: Int = 0) {
  init { require(number >= 0) }
}
```

generates (1) `PositiveInt.of(n: Int)`, (2) `PositiveInt.of()` which uses the default value, and (3) a constructor `PositiveInt()` with the same visibility as the one defined in the class.

Note that exposing this constructor directly is OK, as we expect the given default value to satisfy any requirement in the `init` block.

### Other design choices

**Annotate the value class**: in a [previous proposal](https://github.com/Kotlin/KEEP/blob/commandertvis/jvmexpose/proposals/jvm-expose-boxed-annotation.md), the annotation was applied to the value class itself, not to the operations, and would force every user of that class to create the boxed and unboxed versions. We found this approach not flexible enough: it was completely in the hands of the developer controlling the value class to decide whether Java compatibility was important. This opinion may not coincide with that of another author, which may restrict or expand the boxed variants in their code. 

**Use a compiler flag instead of an annotation**: we have also considered exposing this feature using a compiler flag, `-Xjvm-expose-boxed=C,D,E`, where `C`, `D`, and `E` are the names of the classes for which boxed variants of the operations should be generated. We found two drawbacks to this choice:

- Users are not accustomed to tweaking compiler flags, which are often hidden in the build file; in contrast to annotations.
- It does not give the flexibility of exposing only a subset of operations. The current proposal allows that scenario if you annotate each operation independently, but also allows `@file:JvmExposeBoxed` if you want a wider stroke.

## Further problems with reflection

The current proposal does not solve all of the problems that inline value classes have with reflection. Many of them stem from the fact that, once compiled to the unboxed variant, there is no trace of the original boxed variant.

```kotlin
class Person(val name: String, val age: PositiveInt)

// compiles down to
class Person {
  fun getName(): String
  fun getAge(): Int  // ! not PositiveInt
}
```

In the case above, libraries may inadvertently break some of the requirements of `PositiveInt` if a `Person` instance is created using reflection.

We are currently investigating the required additional support. Some Java serialization libraries, like [Jackson](https://github.com/FasterXML/jackson-module-kotlin/blob/master/docs/value-class-support.md) already include specialized support for value classes. At this point, collaboration with the maintainers of the main libraries in the Java ecosystem seems like the most productive route.

### Other design choices

**Mark usages of unboxed classes**: we have investigated the possibility of annotating every place where an unboxed variant is used with a reference to the boxed variant.

```kotlin
// compiles down to
class Person {
  fun getName(): String
  fun getAge(): @JvmBoxed(PositiveInt::class) Int
}
```

**Validation**: building on the previous idea, `@JvmBoxed` could be implemented as a [Bean validator](https://beanvalidation.org/), so frameworks like Hibernate would automatically execute the corresponding checks. Alas, the Bean Validation specification is a huge mess right now. By Java 7 it was part of the standard distribution in the `javax.validation` package, but now it's moved to `jakarta.validation` (this is in fact the [main change](https://beanvalidation.org/3.0/) between versions 2 and 3 of the specification). It is impossible to provide good compatibility with many Java versions on those grounds.

## Comparison with Scala

The closest feature to inline classes in the JVM comes from Scala. In this case, there's quite a big difference between Scala 2 and 3.

Scala 3 is based on [_opaque type aliases_](https://docs.scala-lang.org/scala3/reference/other-new-features/opaques-details.html) ([SIP-35](https://docs.scala-lang.org/sips/opaque-types.html)). The core idea is that when you mark a type alias as opaque, the translation is only visible in the scope it was defined. For example, inside `opaquetypes` below you can treat `Logarithm` and `Double` interchangeably, but outside they are treated as completely separate types.

```scala
package object opaquetypes {
  opaque type Logarithm = Double
}
```

You then define operations over the opaque type using a companion. In Kotlin terms, that amounts to defining all operations as extension methods. The aforementioned SIP does not consider compatibility with Java. In fact, it mentions that opaque types are actually erased before going into each of the backends.

The closest feature to inline classes in Scala is _value classes_ ([SIP-15](https://docs.scala-lang.org/sips/value-classes.html)). They are available in both Scala 2 and 3 but are considered deprecated in the latter. Value classes follow a compilation scheme very close to Kotlin's, but the original class implementation stays available. In other words, it works as if the constructors and members were exposed, using our terminology.

- One detail is that instead of mangling, the "unboxed versions" of the methods are available in a (companion) object. Nevertheless, they still require some name adjustments to prevent clashes.



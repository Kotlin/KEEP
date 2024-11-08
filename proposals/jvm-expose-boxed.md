# Expose boxed inline value classes in JVM

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Mikhail Zarechenskii, Ilmir Usmanov
* **Discussion**: [#394](https://github.com/Kotlin/KEEP/issues/394)
* **Status**: Under discussion
* **Related YouTrack issue**: [KT-28135](https://youtrack.jetbrains.com/issue/KT-28135/Add-a-mechanism-to-expose-members-of-inline-classes-and-methods-that-use-them-for-Java)
* **Previous proposal**: [by @commandertvis](https://github.com/Kotlin/KEEP/blob/commandertvis/jvmexpose/proposals/jvm-expose-boxed-annotation.md)

## Abstract

We propose modifications to how value classes are exposed in JVM, with the goal of easier consumption from Java. This includes exposing the constructor publicly and giving the ability to expose boxed variants of operations.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Design goals](#design-goals)
* [The `JvmExposeBoxed` annotation](#the-jvmexposeboxed-annotation)
  * [Explicit API mode](#explicit-api-mode)
* [Expose boxed constructors](#expose-boxed-constructors)
  * [Serialization](#serialization)
  * [No argument constructors](#no-argument-constructors)
  * [JVM value classes](#jvm-value-classes)
  * [Other design choices](#other-design-choices)
* [Expose operations and members](#expose-operations-and-members)
  * [`Result` is excluded](#result-is-excluded)
  * [Other design choices](#other-design-choices-1)
  * [Further problems with reflection](#further-problems-with-reflection)
* [Discarded potential features](#discarded-potential-features)
* [Comparison with Scala](#comparison-with-scala)

## Motivation

[Value (or inline) classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) are an important tool to create a _lightweight wrapper_ over another class. One important example is given by classes that introduce additional invariants over another one, like an integer always greater than zero.

```kotlin
@JvmInline value class PositiveInt(val number: Int) {
  init { require(number >= 0) }
}
```

In order to keep its lightweight nature, the Kotlin compiler tries to use the _unboxed_ variant — in the case above, an integer itself — instead of its _boxed_ variant — the `PositiveInt` class — whenever possible. To prevent clashes between different overloads, the names of some of those functions must be _mangled_, that is, transformed in a specific way. The mangled functions operate directly on the underlying unboxed representation, with additional checks coming from the `init` block. The [inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) KEEP defines the whole compilation scheme, for this example is enough to know that `constructor-impl` is where the `init` block ends up living.

```kotlin
fun PositiveInt.add(other: PositiveInt): PositiveInt =
  PositiveInt(this.number + other.number)

// is compiled down to code similar to
class PositiveInt {
  // static member from the JVM perspective
  public static constructor-impl(number: Int): Int {
    require(number >= 0)
    return number
  }
}
// 1bc5 is completely made up
fun Int.add-1bc5(other: Int): Int =
  PositiveInt.constructor-impl(this.number + other.number)
```

The main drawback of this compilation scheme is that a type like `PositiveInt` cannot be (easily) consumed from Java (or other JVM languages other than Kotlin). This KEEP tries to rectify this problem, providing a way to consume value classes easily from Java. As a side benefit, library authors no longer need to choose between using a value class (which is more performant, but only available for Kotlin consumers) or a (data) class (which always requires boxing but can be used anywhere).

> [!NOTE]
> In the following code examples we use `static` to highlight those places where the compilation scheme produced a static method in JVM bytecode.
> This does not imply any particular stance on future inclusion of `static` members in the Kotlin language.

### Design goals

"Exposing" a `value` class is not a single concept. The following are some scenarios that we would like to support:

1. Creating boxed values: for example, creating an actual instance of `PositiveInt`.
2. Calling operations that take or return value classes using their boxed representations.
3. Making inline value classes work with Java-idiomatic patterns, especially those using reflection.

---

Apart from the particular problems, we set the following cross-cutting goals for the proposal.

**Minimize API surface duplication**: if we expose every single member and function taking a value class with the corresponding version using the boxed type, this essentially duplicates the API surface. Developers may want full control over which functions are exposed this way; on the other hand, having to annotate every single function is also tiring.
  
**Ensure that invariants are never broken**: especially in the "frontier" between Kotlin and Java, we need to provide an API that does all the corresponding checks. We are OK with introducing some performance penalties when using the API from Java, but no additional penalties should be paid if the class is used in Kotlin.

**Compatibility with current compilation scheme**: if we alter the way inline classes are currently compiled in a non-backward compatible way, we could create a huge split in the community. Or even worse, we will end up supporting two different schemes in the compiler.

> [!IMPORTANT]
> This KEEP changes _nothing_ about how value classes are handled in Kotlin code.
> The new boxed variants are only accessible to other JVM languages,
> the Kotlin compiler shall hide them, as it currently does with the
> already-implemented compilation scheme.

## The `JvmExposeBoxed` annotation

We propose introducing a new `@JvmExposeBoxed` annotation, defined as follows.

```kotlin
@Target(
  CONSTRUCTOR, PROPERTY, FUNCTION, // callables
  CLASS, FILE,                     // containers
)
annotation class JvmExposedBoxed(val jvmName: String = "", val expose: Boolean = true)
```

Whenever a _callable declaration_ (function, property, constructor) is annotated with `@JvmExposeBoxed` and `expose` is set to `true` (the default), a new _boxed_ variant of that declaration should be generated. How this is done differs between constructors and other operations, as discussed below.

Since annotating every single callable declaration would be incredibly tiresome, the annotation may also be applied to declaration _containers_, such as classes and files. In that case, it should be taken as applied to every single declaration within it, with the same value for `expose`. It is not allowed to give an explicit value to `jvmName` when using the annotation in a declaration container.

The consequence of the rules above is that if we annotate a class,

```kotlin
@JvmExposeBoxed @JvmInline value class PositiveInt(val number: Int) {
  fun add(other: PositiveInt): PositiveInt = ...
}
```

this is equivalent to annotating the constructor and every callable member,

```kotlin
@JvmInline value class PositiveInt @JvmExposeBoxed constructor (val number: Int) {
  @JvmExposeBoxed fun add(other: PositiveInt): PositiveInt = ...
}
```

We actually expect in most cases for the annotation to be applied class-wise or even file-wise, `@file:JvmExposeBoxed`, since this uniformly enables or disables the feature.

> [!IMPORTANT]
> Whether you expose the (boxing) constructor of value class has no influence on whether you can expose boxed variants of operations over that same class. The compilation scheme never calls that constructor, moving between the boxed and unboxed worlds is done via `box-impl` and `unbox-impl`, as described below.

### Explicit API mode

Whether an operation is available or not in its boxed variant is very important for interoperability with other languages. For that reason, this KEEP mandates that whenever an operation mentions a value class, and [explicit API mode](https://github.com/Kotlin/KEEP/blob/master/proposals/explicit-api-mode.md) is enabled, the developer _must_ indicate whether the operation should or not be exposed. The latter is done using `@JvmExposeBoxed(expose = false)`.

## Expose boxed constructors

The [current compilation scheme](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#inline-classes-abi-jvm) exposes the constructor of the boxed class as _synthetic_, which makes it unavailable from Java. This constructor does _not_ execute the `init` block, it just boxes the value.

Note that when boxing needs to occur, the compilation scheme does not call this constructor directly. Rather, it uses the static `box-impl` method for that type.

```kotlin
fun positiveIntSingleton(number: PositiveInt): List<PositiveInt> = listOf(number)
// is "translated" into the following code
fun positiveIntSingleton-3bd7(number: Int): List<PositiveInt> = listOf(PositiveInt.box-impl(number))
```

We propose a new compilation scheme which _replaces_ the previous one with respect to constructors.

- The previous synthetic constructor, which does not execute the `init` block, now takes an additional `BoxingConstructorMarker` argument. This argument is always passed as `null`, but allows us to differentiate the constructor from other (like `DefaultConstructorMarker`). The implementation of `box-impl` is updated to call this constructor.
- We get a new non-synthetic constructor which executes any `init` block; in the current compilation scheme, this amounts to calling `constructor-impl` over the input value. The visibility of this new constructor should coincide with that of the constructor of the value class if the boxed variant of the constructor ought to be exposed, and be `private` otherwise.

```kotlin
@JvmExposeBoxed @JvmInline value class PositiveInt(val number: Int) {
  init { require(number >= 0) }
}

// compiles down to
class PositiveInt {
  private synthetic <init>(number: Int, marker: BoxingConstructorMarker)

  public <init>(number: Int): void = <init>(constructor-impl(number), null)

  public static constructor-impl(Int): Int  // executes the 'init' block
  public static box-impl(number: Int): PositiveInt = <init>(number, null)
  // and others
}
```

Note that this change is binary compatible with previous users of this code:

- The previous compilation scheme never calls the constructor directly; it always uses `box-impl` if boxing is required in the Kotlin side. But the signature of `box-impl` is not changed, only its implementation.
- Java users were not able to call the constructor, since it was marked as synthetic (and private). So exposing the constructor is compatible.
- In the scenario in which the constructor was called directly (for example, by reflection), the code is still correct. There might be some performance hit, since now the `init` block is executed, but this is not a big concern.

### Serialization

`kotlinx.serialization` currently ensures that [value classes are correctly serialized](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/value-classes.md#serializable-value-classes), even when allocated. This must remain the case once this KEEP is implemented.

### No argument constructors

Frameworks like Java Persistence require classes to have a [default no argument constructor](https://www.baeldung.com/jpa-no-argument-constructor-entity-class). We propose to expose a no argument constructor whenever a default value is given to the underlying property of the value class (in addition to the factory methods). Continuing with our example of positive integers, the following code,

```kotlin
@JvmExposeBoxed @JvmInline value class PositiveInt(val number: Int = 0) {
  init { require(number >= 0) }
}
```

exposes both the constructor taking a single integer, but also another one without the optional argument.

```kotlin
class PositiveInt {
  private synthetic <init>(number: Int, marker: BoxingConstructorMarker)

  public <init>(number: Int): void = <init>(constructor-impl(number), null)
  public <init>(): void = <init>(0)
}
```

### JVM value classes

It is not a goal of this KEEP to decide what should happen with Kotlin value classes once the JVM exposes that feature ([JEP-401](https://openjdk.org/jeps/401)). The compilation scheme presented above follows the guidelines for _Migration of existing classes_, so it should be possible to neatly migrate to a JVM value class without further changes.

### Other design choices

**Exposing a factory method**: instead of exposing the constructor, use `PositiveInt.of(3)`. This option is nowadays quite idiomatic in the Java world (for example, in `List.of(1, 2)`), but not as much as constructors. Using a factory method also has the drawback of possible clashes for the name of the factory method, which we avoid when using the constructor.

## Expose operations and members

The current compilation scheme transforms every operation where a value class is involved into a static function that takes the unboxed variants, and whose name is mangled to prevent clashes. This means those operations are not available for Java consumers.

In the new compilation scheme, if a boxed variant is requested, the compiler shall produce a callable taking and returning the boxed versions. If more than one argument or return type is a value class, the aforementioned variant uses the boxed versions for _all_ of them.

The name of the boxed variant coincides with the name given in the Kotlin code unless the `jvmName` argument of the annotation is present. In that case, the name defined in the annotation should be used. Note that `@JvmName` annotations apply to the unboxed variant.

> [!NOTE]
> There is a corner case in which `@JvmExposeBoxed` with a name is always needed: when the function has no argument which is a value class, but returns a value class.
> In that case the compilation scheme performs no mangling. As a result, without the annotation the Java compiler would produce an ambiguity error while resolving the name.

The following is an example of the compilation of some operations over `PositiveInt`. The `box-impl` and `unbox-impl` refer to the operations defined in the [current compilation scheme](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#inline-classes-abi-jvm) for boxing and unboxing without checks.

```kotlin
@JvmInline @JvmExposeBoxed
value class PositiveInt(val number: Int) {
  init { require(number >= 0) }

  fun add(other: PositiveInt): PositiveInt = ...
}

@JvmExposeBoxed("dupl")
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

public dupl($this: PositiveInt): PositiveInt =
  box-impl(duplicate-26b4(unbox-impl($this)))
// mangled version
fun duplicate-26b4($this: Int): Int
```

### `Result` is excluded

Even though on paper the [`Result`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-result/) in the standard library should be covered by this KEEP, this is _not_ the case. The reason is that the compiler performs no mangling, and every `Result` value is treated as `java.lang.Object`.

```kotlin
fun weirdIncrement(x: Result<Int>): Result<Int> =
    if (x.isSuccess) Result.success(x.getOrThrow() + 1)
    else x

// compiles down to
fun weirdIncrement(x: java.lang.Object): java.lang.Object
```

More concretely, if `@JvmExposeBoxed` is applied to a callable using `Result` either as parameter or return type, that position should be treated as a non-value class.

### Other design choices

**Annotate the value class**: in a [previous proposal](https://github.com/Kotlin/KEEP/blob/commandertvis/jvmexpose/proposals/jvm-expose-boxed-annotation.md), the annotation was applied to the value class itself, not to the operations, and would force every user of that class to create the boxed and unboxed versions. We found this approach not flexible enough: it was completely in the hands of the developer controlling the value class to decide whether Java compatibility was important. This opinion may not coincide with that of another author, which may restrict or expand the boxed variants in their code. 

**Use a compiler flag instead of an annotation**: we have also considered exposing this feature using a compiler flag, `-Xjvm-expose-boxed=C,D,E`, where `C`, `D`, and `E` are the names of the classes for which boxed variants of the operations should be generated. We found two drawbacks to this choice:

- Users are not accustomed to tweaking compiler flags, which are often hidden in the build file; in contrast to annotations.
- It does not give the flexibility of exposing only a subset of operations. The current proposal allows that scenario if you annotate each operation independently, but also allows `@file:JvmExposeBoxed` if you want a wider stroke.

### Further problems with reflection

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

## Discarded potential features

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

You then define operations over the opaque type using a companion. In Kotlin terms, that amounts to defining all operations as extension methods. The aforementioned SIP does not consider compatibility with Java. In fact, it mentions that opaque types are simply erased before going into each of the backends.

The closest feature to inline classes in Scala is _value classes_ ([SIP-15](https://docs.scala-lang.org/sips/value-classes.html)). They are available in both Scala 2 and 3 but are considered deprecated in the latter. Value classes follow a compilation scheme very close to Kotlin's, but the original class implementation stays available. In other words, it works as if the constructors and members were exposed, using our terminology.

- One detail is that instead of mangling, the "unboxed versions" of the methods are available in a (companion) object. Nevertheless, they still require some name adjustments to prevent clashes.



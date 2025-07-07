# Expose boxed inline value classes in JVM

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Mikhail Zarechenskii, Ilmir Usmanov
* **Discussion**: [#394](https://github.com/Kotlin/KEEP/issues/394)
* **Status**: Experimental in Kotlin 2.2
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
* [Expose boxed constructors](#expose-boxed-constructors)
  * [Serialization](#serialization)
  * [No argument constructors](#no-argument-constructors)
  * [JVM value classes](#jvm-value-classes)
  * [Other design choices](#other-design-choices)
* [Expose operations and members](#expose-operations-and-members)
  * [Abstract members](#abstract-members)
  * [When no exposure may happen](#when-no-exposure-may-happen)
  * [Annotations](#annotations)
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
  // function-like
  FUNCTION, CONSTRUCTOR,
  PROPERTY_GETTER, PROPERTY_SETTER,
  // classifiers
  CLASS,
)
@Retention(RUNTIME)
annotation class JvmExposedBoxed(val jvmName: String = "")
```

It is not allowed to apply the annotation to members marked with the `suspend` modifier.

Whenever a _function-like declaration_ (function, constructor, property accessor) is annotated with `@JvmExposeBoxed`, a new _boxed_ variant of that declaration should be generated. How this is done differs between constructors and other operations, as discussed below. The compiler should report a _warning_ if no boxed variant of the annotated declaration exists, that is, when the annotation has no effect.

Since annotating every single declaration in class would be incredibly tiresome, the annotation may also be applied to entire classes. In that case, it should be taken as applied to every single function-like declaration within it _which can be exposed_. It is not allowed to give an explicit value to `jvmName` when using the annotation in a class. Note that the annotation is not propagated to nested classes nor companion objects.

The consequence of the rules above is that if we annotate a class,

```kotlin
@JvmExposeBoxed @JvmInline value class PositiveInt(val number: Int) {
  fun add(other: PositiveInt): PositiveInt = ...
  fun toInt(): Int = number
}
```

this is equivalent to annotating the constructor and both members,

```kotlin
@JvmInline value class PositiveInt @JvmExposeBoxed constructor (val number: Int) {
  @JvmExposeBoxed fun add(other: PositiveInt): PositiveInt = ...
  @JvmExposeBoxed fun toInt(): Int = number
}
```

One expected common use case, mainly for library authors, is to expose boxed variants of every function where this is possible.
We expect compilers to provide a special _flag_, like `-Xjvm-expose-boxed`, that enables this feature uniformly.

> [!IMPORTANT]
> Whether you expose the (boxing) constructor of value class has no influence on whether you can expose boxed variants of operations over that same class. The compilation scheme never calls that constructor, moving between the boxed and unboxed worlds is done via `box-impl` and `unbox-impl`, as described below.

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

In the new compilation scheme, if a boxed variant is requested, the compiler shall produce a function taking and returning the boxed versions. If more than one argument or return type is a value class, the aforementioned variant uses the boxed versions for _all_ of them.

The name of the boxed variant coincides with the name given either in Kotlin code or in the `@JvmName` unless the `jvmName` argument of the annotation is present.
To make it entirely clear, here are the four cases to be considered.

| | No exposed name | Exposed name |
|-|-----------------|--------------|
| No `JvmName` | Unboxed: mangled <br /> Boxed: given name | Unboxed: mangled <br /> Boxed: given exposed name |
| With `JvmName` | Unboxed **and** boxed: <br /> given `JvmName` | Unboxed: given `JvmName` <br /> Boxed: given exposed name |

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

### Abstract members

> [!WARNING]
> Exposing abstract members involves the [same problems as using `@JvmName` on those members](https://youtrack.jetbrains.com/issue/KT-31420/Support-JvmName-on-interface-or-provide-other-interface-evolution-mechanism). This proposal describes how exposing abstract members ought to work, once the other issues are resolved.

When an abstract member (either in an interface or a class) is marked with `@JvmExposeBoxed`, a _concrete_ bridge function is generated which calls the abstract member.

```kotlin
interface Foo {
  @JvmExposeBoxed fun duplicate(value: PositiveInt): PositiveInt
}

// compiles down to
interface Foo {
  abstract fun duplicate-26b4($this: Int): Int

  fun duplicate(value: PositiveInt): PositiveInt =
    box-impl(duplicate-26b4(unbox-impl(value)))
}
```

### When no exposure may happen

One fair question is what should happen if the user indicates that they want to expose on a function-like declaration in which no inline value class in involved. We consider two different cases:

- If the declaration is explicitly marked, that is, if the `@JvmExposeBoxed` annotation appears on the declaration itself, an _error_ should be raised.
- If exposure is implicitly declared, by either a `@JvmExposeBoxed` on a class level, or by a compiler flag, the declaration is simply ignored.

There is a corner case which needs special treatment: namely a top-level function or property which has no argument which is a value class, but returns a value class.
In that case the compilation scheme performs no mangling.
As a result, if we exposed both variants under the same name, the Java compiler would not be able to resolve which one we refer to, undermining the whole purpose of exposing it.

- If the declaration is explicitly marked, an _error_ should be raised, unless a `@JvmExposeBoxed` annotation with an explicit name appears.
- If exposure is implicitly declared, the boxed variant should not be generated.

### Annotations

Annotations should be carried over to the boxed variant of a declaration, with the exception of `@JvmName` and `@JvmExposeBoxed`.

- If a declaration has a `@JvmName` annotation, that should appear only in the _unboxed_ variant, which is the one whose name is affected.
- If a declaration has a `@JvmExposeBoxed` annotation, that should appear only in the _boxed_ variant.

Furthermore, `@JvmExposeBoxed` annotations on classes "travel" to the declarations within it. As a result, both annotation processors and runtime reflection see `@JvmExposeBoxed` both in the class and in each individual operation.

### Other design choices

**"Contagious" value classes**: in a [previous proposal](https://github.com/Kotlin/KEEP/blob/commandertvis/jvmexpose/proposals/jvm-expose-boxed-annotation.md), the annotation was applied to the value class itself, not to the operations, and would force every user of that class to create the boxed and unboxed versions. We found this approach not flexible enough: it was completely in the hands of the developer controlling the value class to decide whether Java compatibility was important. This opinion may not coincide with that of another author, which may restrict or expand the boxed variants in their code. 

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

**Annotating properties**: in a previous iteration it was allowed to annotate properties, which were seen as "containers" for their accessors. Ultimately we found that in most scenarios in which `@JvmExposeBoxed` was used in a property, a name for the getter was also required anyway, so we decided to make this case more explicit.

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



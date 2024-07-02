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
* [Expose boxed constructors](#expose-boxed-constructors)
    * [No argument constructors](#no-argument-constructors)
    * [Other design choices](#other-design-choices)
* [Expose operations and members](#expose-operations-and-members)
    * [Other design choices](#other-design-choices-1)
* [Problems with reflection](#problems-with-reflection)
    * [Other design choices](#other-design-choices-2)

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

"Exposing" a `value` class is not a single entity. We have found (at least) four different scenarios that we would like to support:

1. Creating boxed values: for example, creating an actual instance of `PositiveInt`.
2. Calling operations defined over value classes over their boxed representations.
3. Making inline value classes with Java-idiomatic patterns, especially those using reflection.

---

Apart from the particular problems, we set the following cross-cutting goals for the proposal.

**Minimize API surface duplication**: if we expose every single member and function taking an value class with the corresponding version using the boxed type, this essentially duplicates the API surface. Developers may want full control over which functions are exposed this way; on the other hand, having to annotate every single function is also tiring.
  
**Ensure that invariants are never broken**: especially in the "frontier" between Kotlin and Java, we need to provide an API which does all the corresponding checks. We are OK with introducing some performance penalties when using the API from Java, but no additional penalties should be paide if the class is used in Kotlin.

**Compatibility with current compilation scheme**: if we alter the way inline classes are currently compiled in a non backward compatible way, we could create a huge split in the community. Or even worse, we will end up supporting two different schemes in the compiler.

## Expose boxed constructors

The [current compilation scheme](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#inline-classes-abi-jvm) exposes the constructor of the boxed class as _synthetic_, which makes it unavailable from Java. We propose to remove that modifier from the constructor, and use the same visibility in the constructor as the one defined in the value class.

```kotlin
@JvmInline value class PositiveInt(val number: Int) {
  init { require(number >= 0) }
}

// compiles down to
class PositiveInt {
  public <init>(Int): void

  // and others
}
```

We think this is the right choice because of a couple of reasons:

1. It does not increase the binary size, as it only exposes a constructor which was already there (albeit synthetic);
2. It is binary compatible with previous users of this code.

### No argument constructors

Frameworks like Java Persistence require classes to have a [default no argument constructor](https://www.baeldung.com/jpa-no-argument-constructor-entity-class). This case would be covered by a combination of the proposal above, and the ability to give default values to arguments of a value class. Continuing with our example of positive integers, the following code,

```kotlin
@JvmInline value class PositiveInt(val number: Int = 0) {
  init { require(number >= 0) }
}
```

generates the constructor taking a single integer, but also another one without the optional argument. Since value classes may only have one argument, this means that making it optional creates a no argument constructor.

### Other design choices

**Put it under a flag**, in other words, only expose the boxed constructor when some annotation or compiler flag is present. In this case, we could not find a realistic scenario where this expose would be counter-productive; in the worst case in which you expose the constructor but not operations you just have a way to create useless values.

The only drawback is that the ABI exposed in the JVM changes from a version of the compiler to the next, albeit in a binary compatible way. There is a risk involved in downgrading the compiler version, as it may break Java clients, but this scenario is quite rare.

**Exposing a factory method**: instead of exposing the constructor, use `PositiveInt.of(3)`. This option is nowadays quite idiomatic in the Java world (for example, in `List.of(1, 2)`), but not as much as constructors. Using a factory method also has the drawback of possible clashes for the name of the factory method, which we avoid when using the constructor.

## Expose operations and members

The current compilation scheme transforms every operation where a value class is involved into a static function that takes the unboxed variants, and whose named is mangled to prevent clashes. This means those operarions are not available for Java consumers. We propose introducing a new `@JvmExposeBoxed` annotation that exposes a variant of the function taking the boxed versions instead.

The `@JvmExposeBoxed` annotation may be applied to a declaration, or to a declaration container (classes, files). In the latter case, it should be takes as applied to every single declaration within it.

If the `@JvmExposeBoxed` annotation is applied to a member of a value class (or the value class itself, or the containing file), then it is compiled to a member function of the corresponding boxed class, not as a static method.

The name of the declaration must coincide with the name given in the Kotlin code, unless a `@JvmName` annotation is present. In that case, the name defined in the annotation should be used.

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

### Other design choices

**Annotate the value class**: in a [previous proposal](https://github.com/Kotlin/KEEP/blob/commandertvis/jvmexpose/proposals/jvm-expose-boxed-annotation.md) the annotation was applied to the value class itself, not to the operations, and would force every user of that class to create the boxed and unboxed versions. We found this approach not flexible enough: it was completely on the hand of the developer controlling the value class to decide whether Java compatibility was important. This opinion may not coincide with that of another author, which may restrict or expand the boxed variants in their own code. 

**Use a compiler flag instead of an annotation**: we have also considered exposing this feature using a compiler flag, `-Xjvm-expose-boxed=C,D,E`, where `C`, `D`, and `E` are the names of the classes for which boxed variants of the operations should be generated. We found two drawbacks for this choice:

- Users are not accostumed to tweaking compiler flags, which are often hidden in the build file; in contrast to annotations.
- It does not give the flexibility of exposing only a subset of operations. The current proposal allows that scenario if you annotate each operation independently, but also allows `@file:JvmExposeBoxed` if you want a wider stroke.

## Problems with reflection

The current proposal does not solve all of the problems that inline value classes have with reflection. Many of them stem from the back that, once compiled to the unboxed variant, there is no trace of the original boxed variant.

```kotlin
class Person(val name: String, val age: PositiveInt)

// compiles down to
class Person {
  fun getName(): String
  fun getAge(): Int  // ! not PositiveInt
}
```

In the case above, libraries may unadvertedly break some of the requirements of `PositiveInt` if a `Person` instance is created using reflection.

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


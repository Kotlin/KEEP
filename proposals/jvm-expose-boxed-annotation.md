# `@JvmExposeBoxed` annotation for opening API of inline classes to Java

* **Type**: Design proposal
* **Authors**: Iaroslav Postovalov, Ilmir Usmanov
* **Status**: TODO
* **Prototype**: In progress
* **Discussion and feedback**: TODO

This document describes an annotation for the transformation of inline classes to be convenient for use from Java.

## Motivation and use-cases

Functions taking and returning `@JvmInline` classes are unavailable from Java because their name contains `-` with hash suffix.

Functions declared in an inline class are compiled to hyphen-mangled static methods taking underlying type, except in cases when a bridge method to implement interface is required.

The constructor of an inline class is generated private and synthetic, so inaccessible from Java.

All these characteristics lead to that inline classes being completely cut off from Java API; however, they can be useful for interoperability inside one module, for framework compatibility, and for writing libraries providing support of Java.

Related issues:

1. To access the property of an inline class, reflection has to be used ([KT-50518](https://youtrack.jetbrains.com/issue/KT-50518)).
2. Java frameworks like Mockito have problems with methods returning unboxed inline classes ([KT-51641](https://youtrack.jetbrains.com/issue/KT-51641)).
3. Inaccessible constructors of inline classes ([KT-47686](https://youtrack.jetbrains.com/issue/KT-47686)).
4. Issues of inline class methods with kapt (https://github.com/Kotlin/KEEP/issues/104#issuecomment-449782492).
5. A general issue about JVM compatibility of value classes ([KT-50689](https://youtrack.jetbrains.com/issue/KT-50689)).

The issues that are related to the behavior of frameworks can be addressed by documenting the existence of mangled boxing methods and the getter method of the inline class main property, but others require changing the ABI of inline classes and functions related to them.

## Proposed API

Adding a new `@kotlin.jvm.JvmExposeBoxed` annotation is proposed to address the problem. Hence, its purpose is to ensure that the inline class is exposed as its JVM class in all APIs to simplify the development of libraries designed for use from Java written in Kotlin.

The annotation is defined as

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@SinceKotlin("...")
@OptionalExpectation
public expect annotation class JvmExposeBoxed
```

The actual class is present only on JVM.

Its behavior consists in adding API of the marked inline class as its boxed variant; hence, only `@JvmInline` value classes can be annotated.

In added functions, the value parameters and return value are boxed if their type is inline class marked with new annotation. A hash code mangled implementation of the function for usage from Kotlin should be created as usual.

```kotlin
@JvmInline
@JvmExposeBoxed
value class Example(val s: String)

fun f(x: Example): Example = TODO()
```

```java
public static f-NmaSWX8(Ljava/lang/String;)Ljava/lang/String; // for Kotlin
public static f(LExample;)LExample; // for Java
```

The current design does not affect properties. If one needs to expose a getter method returning an inline class, it should be created as a function:

```kotlin
@get:JvmName("getX-impl")
val x: Example = TODO()

fun getX() = x
```

```java
public static getX-impl()Ljava/lang/String; // for Kotlin
public static getX()LExample; // for Java
```

One of the problems is that one cannot instantiate an inline class with its constructor because it is generated with `private` and `synthetic` byte code flags. A solution for that could be annotating a constructor `@JvmExposeBoxed`  to have a constructor exposed by the compiler (it also will create an internal synthetic overload of it taking something like `Nothing?`).

```kotlin
@JvmInline 
@JvmExposeBoxed
value class Example(val s: String)
```

A `JvmExpose` annotation should be added to the class to achieve the following Java syntax:

```java
ExampleKt.f(new Example("42"));
```

Usually, the constructor of the inline class is used to perform boxing of it. Annotating it with `@JvmExpose` will lead to creating a new, synthetic constructor (with placeholder parameter of type `java.lang.Void`, probably, to avoid signature clash) for boxing, enabling the default one for the user (making it not synthetic).

All other functions and properties declared in the boxed exposed inline class become available as normal object methods on JVM. Bridges are generated for all of them as it is already done for inline classes implementing interfaces. If one of the bridge methods takes another instance of inline classes, it is taken boxed, too. However, `box-impl` and `unbox-impl` methods are intentionally left unavailable for users to not break encapsulation and constructor invariants.

Example of ABI for the following class:

```kotlin
@JvmInline
@JvmExposeBoxed
value class Example(val s: String) {
    fun x()
    fun y(another: Example)
}
```

```java
public final class Example {
  //// Public ABI intended for Java callers:

  // Visibility matches with the visibility of the s property in the code
  public getS()Ljava/lang/String;
  public x()V
  public y(LExample;)V
  
  public toString()Ljava/lang/String;
  public hashCode()I
  public equals(Ljava/lang/Object;)Z

  // Not synthetic constructor,
  // visibility matches with the one declared in the code,
  // calls constructor-impl
  public <init>(Ljava/lang/String;)V 
      
  //// Mangled ABI for Kotlin callers:
  public synthetic unbox-impl()Ljava/lang/String;
  public static x-impl(Ljava/lang/String;)V
  public static y-NmaSWX8(Ljava/lang/String;Ljava/lang/String;)V
  public static toString-impl(Ljava/lang/String;)Ljava/lang/String;
  public static hashCode-impl(Ljava/lang/String;)I
  public static equals-impl(Ljava/lang/String;Ljava/lang/Object;)Z
  public static constructor-impl(Ljava/lang/String;)Ljava/lang/String;
  public static synthetic box-impl(Ljava/lang/String;)LExample;
  public static equals-impl0(Ljava/lang/String;Ljava/lang/String;)Z

  // Synthetic constructor, not calls constructor-impl for boxing
  private synthetic <init>(Ljava/lang/String;Ljava/lang/Void;)V

  @Lkotlin/jvm/JvmInline;()
  @Lkotlin/jvm/JvmExposeBoxed;()
}
```

(Insignificant details like the `final` flag of methods, all inline classes are final themselves, and nullity annotations are omitted. Order of generation is insignificant, too.)

## Questions

* No interaction with Valhalla described for the value classes case
    * https://openjdk.org/projects/valhalla/
    * https://openjdk.org/projects/valhalla/design-notes/state-of-valhalla/01-background
        * The investigation for Valhalla compatibility is postponed until the prototype is ready.
* What about MPP
    * On other platforms, `@JvmInline` classes do not differ from the usual ones, so they will not be affected by `@JvmExposeBoxed` as well.

##  Other considered name variants

* `JvmInlineExposed`
* `JvmInlineBoxed`
* `JvmBoxedValue`

# Repeatable annotations

* **Type**: Design proposal
* **Author**: Alexander Udalov
* **Status**: Accepted
* **Prototype**: Implemented
* **Discussion**: [KEEP-257](https://github.com/Kotlin/KEEP/issues/257)
* **Related issues**: [KT-12794](https://youtrack.jetbrains.com/issue/KT-12794) [KT-47928](https://youtrack.jetbrains.com/issue/KT-47928) [KT-47971](https://youtrack.jetbrains.com/issue/KT-47971)

The goal of this proposal is to extend the existing Kotlin feature of repeatable annotations to allow binary- and runtime-retained repeatable annotations, while making it fully interoperable with repeatable annotations in Java.

## Background

Repeatable annotation is the one that might be applied multiple times to the same element.

Kotlin 1.5 supports repeatable annotations with retention `SOURCE`. Declaring such annotation only requires annotating it with `@kotlin.annotation.Repeatable` meta-annotation:

```kotlin
@Repeatable 
annotation class A(val value: Int)

@A(0) 
@A(1) 
@A(42)
fun element() {}
```

Unfortunately, Kotlin repeatable annotations are incompatible with the same feature in Java. In Java, repeatable annotations are declared with `@java.lang.annotation.Repeatable` meta-annotation which takes the “container” class (see [docs](https://docs.oracle.com/javase/tutorial/java/annotations/repeating.html)) which declares an array of annotation values:

```java
// Java

@Repeatable(AContainer.class)
@interface A {
    int value();
}

@interface AContainer {
    A[] value();
}
```

Note that the explicit container annotation is always needed in Java, even if the annotation has retention `SOURCE`.

## Problem

Since Kotlin’s `Repeatable` does not declare a container annotation, it’s not possible to use Kotlin repeatable annotations in Java. Moreover, the lack of Java interop here is one of the main reasons that up until this point, Kotlin repeatable annotations could only be declared with retention `SOURCE`.

We could just replicate the Java design in Kotlin by adding `val container: KClass<out Annotation>` to `kotlin.annotation.Repeatable`. However, it’s problematic at least for two reasons:

1. It would be a breaking change for the existing Kotlin code which declares repeatable annotations with retention `SOURCE`.
2. Perhaps more importantly, the Java design exposes an implementation detail, namely how the annotations are stored in the bytecode and/or represented in the Java language, to be compatible with bytecode before repeatable annotations were supported in Java. This presents unnecessary boilerplate, and does not align well with the concept of a multiplatform language that is Kotlin, whose language features are supposed to be platform-independent.

Thus we arrive at the following basic requirements:

* Kotlin repeatable annotations should be repeatable from the point of view of Java, which means that in the bytecode, they should be declared as `@java.lang.annotation.Repeatable` with some container annotation.
* Yet we don’t want the Java-esque design where the container annotation is declared explicitly in the source code for every new repeatable annotation.
* However, instructing the compiler to always generate the container annotation automatically is not flexible enough for cases when you want to convert existing code from Java to Kotlin, keeping it ABI-compatible, since you need a way to provide a custom name for the container annotation.
* Also, we'd like do avoid breaking changes if possible.

## Proposal

The proposal that solves all of this is as follows:

* Annotating an annotation with `@kotlin.annotation.Repeatable` makes it repeatable both in Kotlin and in Java. For Java, the compiler generates `@java.lang.annotation.Repeatable` with an automatically generated **implicit** container class named **`Container`** declared inside the annotation.
* If you need to specify a **custom name** for the container annotation, you can override this behavior by **explicitly annotating** the annotation with `@kotlin.jvm.JvmRepeatable(Container::class)`. The compiler will not generate an implicit container class in this case.
* `kotlin.jvm.JvmRepeatable` is just a typealias for `java.lang.annotation.Repeatable`.

In addition to this, the compiler will also **treat all Java-repeatable annotations as Kotlin-repeatable**.

## Examples

1) ```kotlin
   @Repeatable 
   annotation class Tag(val name: String)
   ```

	Here, Kotlin automatically generates an **implicit** container annotation class `Tag.Container`, and marks `@Tag` as `@java.lang.annotation.Repeatable` in the bytecode, so that it’s repeatable in Java as well. Repeating usages of `@Tag` are generated in the JVM bytecode as values in `@Tag.Container`. For example:

   ```kotlin
   // JVM bytecode: @Tag.Container(value = {@Tag("lorem"), @Tag("ipsum")})
   @Tag("lorem") @Tag("ipsum")
   fun test() = ...
   ```

    The container class can be accessed from Java sources as `Tag.Container`.

2) ```kotlin
   @Repeatable 
   @JvmRepeatable(Tags::class)
   annotation class Tag(val name: String)

   annotation class Tags(val value: Array<Tag>)
   ```

    Here, **explicit** container class is provided, so implicit class is not generated. The annotation is Java-repeatable because it’s explicitly annotated as such, with the container class `Tags`, which is used to store repeating instances in the bytecode:

   ```kotlin
   // JVM bytecode: @Tags(value = {@Tag("lorem"), @Tag("ipsum")})
   @Tag("lorem") @Tag("ipsum")
   fun test() = ...
   ```

3) ```kotlin
   @JvmRepeatable(Tags::class)
   annotation class Tag(val name: String)

   annotation class Tags(val value: Array<Tag>)
   ```

	Here, `Tag` is annotated as Java-repeatable, but not as Kotlin-repeatable. Since all Java-repeatable annotations are automatically Kotlin-repeatable, this behaves as in example 2.

## Details

Marking an annotation repeatable in Java results in additional constraints for the annotation container class. The same constraints will be checked for the Kotlin annotation if it’s annotated with `@JvmRepeatable` ([KT-47928](https://youtrack.jetbrains.com/issue/KT-47928)):

1. The container class has to have a property `value` of an array type of the annotation, and all other properties (if any) must have default values specified.
2. The **retention** of the container class must be **greater or equal** than that of the annotation class (assuming `SOURCE < BINARY < RUNTIME`).
3. The **target** set of the container class must be a **subset** of the annotation class' target set.

The compiler will report an error if any of these constraints is not met.

Also, the compiler will report an error if a non-`SOURCE`-retained annotation is repeated when JVM target bytecode version 1.6 is used.

In case of an implicit container class, it’s generated with the required property `value`, and both the same retention and target as the annotation class. Also, for reasons explained in the next section, it’s annotated with an internal annotation `@kotlin.jvm.internal.RepeatableContainer`:

```kotlin
@Repeatable 
@Target(CLASS, FUNCTION)
@Retention(BINARY)
annotation class Tag(val name: String) {
    // Automatically generated by the compiler:
    //
    // @Target(CLASS, FUNCTION)
    // @Retention(BINARY)
    // @kotlin.jvm.internal.RepeatableContainer
    // public annotation class Container(val value: Array<Tag>)
}
```

Another error is introduced in case the container annotation is applied manually at the same time as the contained annotation, if the latter is repeated:

```kotlin
@Tags(["lorem"]) // error!
@Tag("ipsum") @Tag("dolor")
fun test() = ...
```

Note that there will be no error if the contained annotation (`@Tag` in this example) is applied not more than once, because such code was allowed since Kotlin 1.0.

Another error is going to be reported if an annotation class annotated with `@kotlin.annotation.Repeatable` declares a nested class named `Container` ([KT-47971](https://youtrack.jetbrains.com/issue/KT-47971)).

## Reflection

The following changes in `kotlin-reflect` are needed:

* Existing extension function `KAnnotatedElement.findAnnotation` will return the **first** instance of a repeating annotation if it’s applied multiple times.
* A new extension function `KAnnotatedElement.findAnnotations` will be added.
    * For annotations applied multiple times, it returns the list of all values.
    * For annotations applied only once, it returns the list of that one value (regardless of whether the annotation is declared as repeatable or not).
    * There will be two declarations:
        * `inline fun <reified T : Annotation> KAnnotatedElement.findAnnotations(): List<T>`
        * `fun <T : Annotation> KAnnotatedElement.findAnnotations(klass: KClass<T>): List<T>`
* Existing member function `KAnnotatedElement.annotations` will behave as follows:
    * For Java repeatable annotations, as well as for Kotlin repeatable annotations with *explicit* container, it works as `getAnnotations` in Java reflection: returns the container annotation type. Manual unpacking/flattening of its value is required to get all the repeated entries.
    * For Kotlin repeatable annotations with *implicit* container, it will **automatically flatten** the values and return repeated annotation entries as they are declared in the source code.
        * The reason for this behavior is that we don’t want to expose the implicit container class, which is an implementation detail from the Kotlin's point of view
        * Checking that each annotation needs to be unwrapped is costly, so we’re going to optimize implicit container detection via name (its name is always `"Container"`) and whether it’s annotated via `@kotlin.jvm.internal.RepeatableContainer`

## Timeline

The feature is going to be available starting from Kotlin 1.6.0-M1.

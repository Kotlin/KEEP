# `@JvmExpose` annotation to explicitly mark accessible API for Java

* **Type**: Design proposal
* **Authors**: Iaroslav Postovalov, Ilmir Usmanov
* **Status**: TODO
* **Prototype**: In progress
* **Discussion and feedback**: TODO

This document describes an annotation for the transformation of API written in Kotlin to be convenient for use from Java.

## Motivation and use-cases

When creating an API for Java in Kotlin, one always has to handle a lot of problems with different tools:

1. Mangling.

Internal functions and properties are mangled by Kotlin without any documentation of it.
However,
creating internal declarations seems to be a viable way to create API available for Java but not available for Kotlin.

The workaround is using `@JvmName`:

```kotlin
// Kotlin API
class Example {
    internal fun noJvmName() {}

    @JvmName("jvmName")
    internal fun jvmName() {
    }
}
```

```java
// Java usage
new Example().noJvmName$example_main(); // looks awful
new Example().jvmName();
```

2. Overloads

Using default parameter values requires user to mark functions with `@JvmOverloads`. 

3. Inline classes

Functions taking and returning `@JvmInline` classes as well as properties of such classes are visible as its internal property's type,
and it breaks the idea of inline classes when used from Java.

The only possible workaround is writing a part of API in Java taking boxed instances of value classes.
Moreover, constructors of inline classes are synthetic; hence, a factory method is needed.

4. Suspending functions

Suspending functions can't be successfully called from Java because of necessity to instantiate `Continuation`.
A widely known workaround is to wrap the result of the function to `CompletableFuture`:

```kotlin
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

suspend fun s(): Int = 42

fun sForJava(): CompletableFuture<Int> = GlobalScope.future {
    s()
}
```

However, this way requires including `org.jetbrains.kotlinx:kotlinx-coroutines-jdk8`,
hence can't be proposed as a new feature of the language.

Another way to make a workaround similar to way `suspend fun main` is implemented is calling `runSuspend`:
```kotlin
import kotlin.coroutines.jvm.internal.*

suspend fun s(): Int = 42

@Suppress("INVISIBLE_MEMBER")
fun sForJava(): Int {
    var result: Result<Int>? = null
    runSuspend {
        result = Result.success(s())
    }
    return result!!.getOrThrow()
}
```

## Proposed API

Adding a new `@kotlin.jvm.JvmExpose` annotation is proposed to address all the listed problems at once,
so its purpose is to ensure that an API can be called from Java freely
and to handle Kotlin features
that were designed without attention to calling from Java
in order to simplify development of libraries for Java and Kotlin written in Kotlin.

Its behavior can be described as “annotated function is guaranteed to be available from Java by exactly the name defined either in the code or in the string parameter”; hence, the following limits are imposed:

1. A `private` function cannot be marked as well as a function defined in private and local classes and objects.
2. A `@JvmSynthetic` function cannot be marked
3. `@JvmOverloads` is assumed

Marking a `public` function without any features leading to mangling is allowed, for example, to show that the API is “Java-friendly.”

Example usages:

```kotlin
class SomeThings {
    @JvmExpose // equivalent to @JvmName("forJava")
    internal fun forJava() {}
}
```

Additionally, marking an `internal` function with `@JvmExpose` designates that calling it from another module in Java is **not** an error, so IDE inspections should handle it.

Combination of `@JvmExpose internal` and `@JvmSynthetic public` allows creating a completely non-overlapping API for Java and Kotlin; however, it looks like abuse, so, probably, it should be an antipattern:

```kotlin
class X {
    @JvmExpose internal fun a(consumer: java.util.function.Consumer<Int>) = consumer.accept(42)
    @JvmSynthetic fun a(consumer: (Int) -> Unit) = consumer(42)
}
```

### `JvmExpose` on functions with `@JvmInline` parameters or return type

Functions related to `@JvmInline value class`  require special treatment since their representation in JVM differs significantly from ordinary ones.

First, the value parameters and return value of a function marked with `@JvmExpose` should be boxed if their type is inline class. The reason is that inline classes are cumbersome to use from Java in their unboxed form. A mangled implementation of the function for unboxed Kotlin usage should be created as well.

```kotlin
// Example.kt

@JvmInline
value class Example(val s: String)

@JvmExpose 
fun f(x: Example): Example = TODO()
```

```java
public static Example f-impl(java.lang.String x)
public static Example f(Example x) {  }
```

The certain problem is that one cannot instantiate an inline class with its constructor because it is generated with `ACC_SYNTHETIC`. A solution for that could be annotating a constructor `@JvmExpose`  to have a constructor exposed by the compiler (it also will create an internal synthetic overload of it taking something like `Nothing?`). Since this requirement is unobvious, an IDE inspection must report that instances of an inline class taken as an argument of the exposed function cannot be instantiated.

```kotlin
@JvmInline
value class Example @JvmExpose constructor(val s: String)
```

A `JvmExpose` annotation should be added to the constructor of `Example` to achieve the following Java syntax:

```java
ExampleKt.f(new Example("42"));
```

Usually, constructor of the inline class is used to perform boxing of it.
Annotating it with `@JvmExpose` will lead to creating a new,
synthetic constructor (with placeholder parameter of type `java.lang.Void`, probably,
to avoid signature clash) for boxing,
enabling the default one for user. 

### Suspending exposed functions

Functions that are both `suspend` and annotated with `@JvmExpose` should not take a continuation as normal ones
because it is impossible to use it in Java conveniently,
so they can block the thread like `suspend fun main` or `@Test suspend` do.
A possible problem is handling functions that actively use kotlinx.coroutines types (from `Deferred` to `Flow`),
which are not convenient for Java users at all.
As in the previous subsection, a mangled normal function should be created for Kotlin:
М
```kotlin
@JvmExpose 
suspend fun f(x: Int): Int = TODO()
```

```java
public static Object f-impl(int x, @NotNull Continuation $completion)
public static int f(int x)
```

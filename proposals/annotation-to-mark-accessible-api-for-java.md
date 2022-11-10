# `@JvmExpose` annotation to explicitly mark accessible API for Java

* Type: Design proposal
* Authors: Iaroslav Postovalov, Ilmir Usmanov
* Status: TODO
* Discussion and feedback: TODO

This document describes an annotation for the transformation of API written in Kotlin to be convenient for use from Java.

## Motivation and use-cases

Currently, `@JvmName` is used in three use cases:

1. To bypass the “Platform declaration crash” error in many cases when Kotlin overloads are more potent than JVM ones. In this case, the user is not interested in setting a specific name.

Example:

```
// Example.kt

fun takeList(list: List<Int>) {}
fun takeList(list: List<Long>) {}
// Platform declaration clash: The following declarations have the same JVM signature (takeList(Ljava/util/List;)V):
```

```
// Example.kt

@JvmName("takeListOfInt")
fun takeList(list: List<Int>) {}

@JvmName("takeListOfLong")
fun takeList(list: List<Long>) {}
```

Such JVM names are usually chosen randomly (primarily when the API is not meant to be called from Java) and are not visible to Kotlin users of these overloads.

1. To clarify API for Java users or to get away from name mangling of declarations sometimes performed by the compiler. In the example above, the names of overloads make sense when used from Java; it is still another use case:

```
ExampleKt.takeListOfInt(List.of(42));
```

Sometimes names used in Kotlin are just unclear (mainly because of operator name conventions) when used from Java, and it can be improved by changing the JVM name:

```
class X

@JvmName("sum")
operator fun X.plus(other: X) = X()

```

```
var result = ExampleKt.sum(new X(), new X());
```

And in some cases, as it was said, `@JvmName` is used for exposing methods whose names were mangled by the compiler or just unavailable from Java:

```
class SomeThings {
    @JvmName("forJava") // to avoid mangled name like forJava$example_module_main
    internal fun forJava() {}

    @JvmName("dollarDollarDollar")
    fun `$$$`() {}
}
```

**Use cases 1 and 2 are very different: while the first is just bypassing a technical limitation of JVM to declare the desired Kotlin API, the second one is related to the scenario when an API for Java is implemented in Kotlin.**

1.  Changing the name of file classes to be called from Java.

```
@file:JvmName("Utils")
```

## Proposed API

### Exposing an API to Java

`@kotlin.jvm.JvmExpose` annotation is proposed to handle use-case 2, so its purpose is to ensure that an API can be called from Java. It also has an optional `String` argument `jvmName` (or `name`) with the default value `""` assuming name defined in the code.

Its behavior can be described as “annotated function is guaranteed to be available from Java by exactly the name defined either in the code or in the string parameter”; hence, the following limits are imposed:

1. A `private` function cannot be marked as well as a function defined in private and local classes and objects.
2. A `@JvmSynthetic` function cannot be marked
3. Obviously, this annotation is incompatible with `@JvmName`.

Marking a `public` function without any features leading to mangling is allowed, for example, to show that the API is “Java-friendly.”

Example usages:

```
class X

@JvmExpose("sum") // equivalent to @JvmName("sum")
operator fun X.plus(other: X) = X()

class SomeThings {
    @JvmExpose // equivalent to @JvmName("forJava") or @JvmExpose("")
    internal fun forJava() {}
}
```

Additionally, marking an `internal` function with `@JvmExpose` designates that calling it from another module in Java is **not** an error, so IDE inspections should handle it.

Combination of `@JvmExpose internal` and `@JvmSynthetic public` allows creating a completely non-overlapping API for Java and Kotlin; however, it looks like abuse, so, probably, it should be an antipattern:

```
class X {
    @JvmExpose internal fun a(consumer: java.util.function.Consumer.Consumer<Int>) = consumer.accept(42)
    @JvmSynthetic fun a(consumer: (Int) -> Unit) = consumer(42)
}
```

### Combination of cases

To handle cases when clashing methods are to be both disambiguated and exposed to Java, only `@JvmExpose` should be enough:

```
@JvmExpose("takeListOfInt")
fun takeList(list: List<Int>) {}

@JvmExpose("takeListOfLong")
fun takeList(list: List<Long>) {}
```

Applying both annotations to one function has to be prohibited.

### Special treatment for exposed functions using `@JvmInline value class`

Functions related to `@JvmInline value class`  require special treatment since their representation in JVM differs significantly from ordinary ones.

First, the value parameters and return value of a function marked with `@JvmExpose` should be boxed if their type is inline class. The reason is that inline classes are cumbersome to use from Java in their unboxed form. A mangled implementation of the function for unboxed Kotlin usage should be created as well.

```
// Example.kt

@JvmInline
value class Example(val s: String)

@JvmExpose 
fun f(x: Example): Example = TODO()
```

```
public static Example f-impl(java.lang.String x)
public static Example f(Example x) {  }
```

The certain problem is that one cannot instantiate an inline class with its constructor because it is generated with `ACC_SYNTHETIC`. A solution for that could be annotating a constructor `@JvmExpose`  to have a constructor exposed by the compiler (it also will create an internal synthetic overload of it taking something like `Nothing?`). Since this requirement is unobvious, an IDE inspection must report that instances of an inline class taken as an argument of the exposed function cannot be instantiated.

```
@JvmInline
value class Example @JvmExpose constructor(val s: String)
```

A `JvmExpose` annotation should be added to the constructor of `Example` to achieve the following Java syntax:

```
ExampleKt.f(new Example("42"));
```

### Suspending exposed functions

Functions that are both `suspend` and annotated with `@JvmExpose` should not take a continuation as normal ones because it is impossible to implement it in Java conveniently, so they can block the thread like `suspend fun main` does. A possible problem is handling functions that actively use kotlinx.coroutines types (from `Deferred` to `Flow`), which are not convenient for Java users at all. As in the previous subsection, a mangled normal function should be created for Kotlin:

```
@JvmExpose 
suspend fun f(x: Int): Int = TODO()
```

```
public static Object f-impl(int x, @NotNull Continuation $completion)
public static int f(int x)
```

### When the `main` function is exposed

```
@JvmExpose("main")
fun mememain() {
}
```

An exposed function with a name `"main"` has to be the main method on JVM as well as ones named with `@JvmName`.

## Interference with other KEEPs

A related concept is introduced in [KEEP-302](https://github.com/Kotlin/KEEP/issues/302) “Binary Signature Name” because it also assumes refusal from using the `JvmName` annotation in some cases.

This proposal is designed in such a way that `@BinarySignatureName` will be another mode of changing the JVM name but without the functions of JvmExpose (making API suitable for Java).

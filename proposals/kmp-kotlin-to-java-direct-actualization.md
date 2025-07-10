**REDIRECT TO**: https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0391-kmp-kotlin-to-java-direct-actualization.md

# KMP Kotlin-to-Java direct actualization

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**: Dmitriy Novozhilov, Kevin Bierhoff, Mikhail Zarechenskiy, Pavel Kunyavskiy
* **Discussion**: [KEEP-391](https://github.com/Kotlin/KEEP/issues/391)
* **Status**: Implemented as experimental feature in 2.1. Stabilization plans are unclear due to implementation challenges
* **Related YouTrack issue**: [KT-67202](https://youtrack.jetbrains.com/issue/KT-67202)

**Definition.** ClassId is a class identifier that consists of three independent components: the package where class is declared in, names of all outer classes (if presented), and the name of the class itself.
In Kotlin, it could be represented as the data class:
```kotlin
data class ClassId(val package: String, val outerClasses: List<String>, val className: String)
```

Two ClassIds are equal if their respective components are equal.
Example: `ClassId("foo.bar", emptyList(), "baz")` and `ClassId("foo", listOf("bar"), "baz")` are different ClassIds

## Introduction

In Kotlin, there are **two ways** to write an actual declaration for the existing expect declaration.
You can either write an actual declaration with the same ClassId as its appropriate expect and mark the appropriate declarations with `expect` and `actual` keywords (From now on, we will call such actualizations _direct actualizations_),
or you can use `actual typealias`.

**The first way.**
_direct actualization_ has a nice property that two declarations share the same ClassId.
It's good because when users move code between common and platform fragments, their imports stay unchanged.
But _direct actualization_ has a "downside" that it doesn't allow declaring actuals in external binaries (jars or klibs).
In other words, expect declaration and its appropriate actual must be located in the same "compilation unit."
[Below](#direct-actualization-forces-expect-and-actual-to-be-in-the-same-compilation-unit) we say why, in fact, it's not a "downside" but a "by design" restriction that reflects the reality.

**The second way.**
Contrary, `actual typealias` forces users to change the ClassId of the actual declaration.
(An attempt to specify the very same ClassId in the `typealias` target leads to `RECURSIVE_TYPEALIAS_EXPANSION` diagnostic)
But we gain the possibility to declare expect and actual declarations in different "compilation units."

> [!NOTE]
> Though it's a philosophical question what is "the real actual declaration" in this case.
> Is it the `actual typealias` itself (which is still declared in the same "compilation unit"), or is it the target of the `actual typealias` (which, in fact, can be declared in external jar or klib)?

|                                                                     | _Direct actualization_ | `actual typealias` |
|---------------------------------------------------------------------|------------------------|--------------------|
| Do expect and actual share the same ClassId?                        | Yes                    | No                 |
| Can expect and actual be declared in different "compilation units"? | No                     | Yes                |

While `actual typealias` already allows actualizing Kotlin expect declarations with Java declarations (Informally: Kotlin-to-Java actualization), _direct actualization_ only allows Kotlin-to-Kotlin actualizations.
The idea of this proposal is to support _direct actualization_ for Kotlin-to-Java actualizations.

## Motivation

As stated in the [introduction](#introduction), unlike `actual typealias`, _direct actualization_ allows to keep the same ClassIds for common and platform declarations in case of Kotlin-to-Java actualization.

One popular use case for Kotlin-to-Java actualization is KMP-fying existing Java libraries.
For library authors, the possibility to keep the same ClassIds between common and platform declarations is highly valuable:

- Since it avoids the creation of two ClassIds that refer to the same object, it avoids the confusion on which ClassId should be used
- It simplifies the migration of client code from the Java library to a KMP version of the same library (no need to replace imports)
- It avoids duplication of potentially entire API surface, which can otherwise become cumbersome
- Later replacing the Java actualization with a Kotlin `actual` class is possible without keeping the previous `actual typealias` in place indefinitely

## The proposal

**(1)** Introduce `kotlin.annotations.jvm.KotlinActual` annotation in `kotlin-annotations-jvm.jar`
```java
package kotlin.annotations.jvm;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface KotlinActual {
}
```

-   The annotation is intended to be used on Java declarations
-   **Any usage** of the annotation in Kotlin will be prohibited (even as a type, even as a reference).
    As if `KotlinActual` was annotated with `@kotlin.Deprecated(level = DeprecationLevel.ERROR)`
-   The annotation in Java will function similarly to the `actual` keyword in Kotlin
-   It doesn't make sense to mark the annotation with `@ExperimentalMultiplatform` since `OPT_IN_USAGE_ERROR` is not reported in Java
-   `ElementType.FIELD` annotation target is not specified by design. It's not possible to actualize Kotlin properties with Java fields

**(2)** If Kotlin expect and Java class have the same ClassId, Kotlin compiler should consider Kotlin expect class being actualized with the appropriate Java class.
In other words, support _direct actualization_ for Kotlin-to-Java actualization.

**(3)** Kotlin compiler should require using `@KotlinActual` on the Java top level class and its respective members.
The rules must be similar to the one with `actual` keyword requirement in Kotlin-to-Kotlin actualization.

**(4)** If `@KotlinActual` is used on Java class members that don't have respective Kotlin expect members, it should be reported by the Kotlin compiler.
The rules must be similar to the one with `actual` keyword requirement in Kotlin-to-Kotlin actualization.
Please note that Kotlin can only detect excessive `@KotlinActual` annotations on methods of the classes that actualize some existing Kotlin expect classes.
Since Kotlin doesn't traverse all Java files, it's not possible to detect excessive `@KotlinActual` annotation on the top-level Java classes for which a respective Kotlin expect class doesn't exist.
For the same reason, it's not possible to detect excessive `@KotlinActual` annotation on members of such Java classes.
For these cases, it's proposed to implement Java IDE inspection.

**Worth noting.**
1. Java wasn't capable and still is not capable of actualizing Kotlin top-level functions in any way.
2. Kotlin expect classes are not capable of expressing Java static members [KT-29882](https://youtrack.jetbrains.com/issue/KT-29882)

Example of a valid Kotlin-to-Java direct actualization:
```kotlin
// MODULE: common
expect class Foo() {
    fun foo()
}

// MODULE: JVM
@kotlin.annotations.jvm.KotlinActual public class Foo {
    @kotlin.annotations.jvm.KotlinActual public Foo() {}
    @kotlin.annotations.jvm.KotlinActual public void foo() {}

    @Override
    public String toString() { return "Foo"; } // No @KotlinActual is required
}
```

## actual keyword is a virtue

An alternative suggestion is to match only by ClassIds, and to drop `actual` keyword in Kotlin-to-Kotlin actualizations, and to drop `@KotlinActual` annotation in Kotlin-to-Java actualization.

The suggestion was rejected because we consider `actual` keyword being beneficial for readers, much like the `override` keyword.

-   **Misspelling prevention.**
    The explicit `actual` keyword (or `@KotlinActual` annotation) helps against misspelling
    (esp. when Kotlin supports expect declarations with bodies [KT-20427](https://youtrack.jetbrains.com/issue/KT-20427))
-   **Explicit intent.**
    Declarations may be written solely to fullfil the "expect requirement" but the declarations may not be directly used by the platform code.
    The `actual` keyword (or `@KotlinActual` annotation) explictly signals the intention to actualize member rather than accidentally defining a new one.
    (too bad that members of `actual typealias` break the design in this place)
-   **Safeguards during refactoring.**
    If the member in expect class changes (e.g. a new parameter added), `actual` keyword (or `@KotlinActual` annotation) helps to quickly identify members in the actual class that needs an update.
    Without the keyword, there might be already a suitable overload in the actual class that would silently become a new actualization.

To support our arguments, we link Swift community disscussions about the explicit keyword for protocol conformance (as of swift 5.9, no keyword is required):
[1](https://forums.swift.org/t/pre-pitch-explicit-protocol-fulfilment-with-the-conformance-keyword/60246), [2](https://forums.swift.org/t/keyword-for-protocol-conformance/3837)

## The proposal doesn't cover Kotlin-free pure Java library use case

There are two cases:
1. The user has a Kotlin-Java mixed project, and they want to KMP-fy it.
2. The user has a pure Java project, and they want to KMP-fy it.

The first case is handled by [the proposal](#the-proposal).

In the second case, the common part of the project is Kotlin sources that depend on `kotlin-stdlib.jar`.
The common part of the project may also define additional regular non-expect Kotlin declarations.
JVM part of the project depends on common.

If users want to keep their JVM part free of Kotlin, they have to be accurate and avoid accidental usages of `kotlin-stdlib.jar`, and avoid declaring additional non-expect declarations in the common.
[The proposal](#the-proposal) doesn't cover that case well since the design would become more complicated.
The current proposal is a small incremental addition to the existing model, and it doesn't block us from covering the second case later if needed.

`KotlinActual` annotation has `SOURCE` retention by design.
This way, the annotation is least invasive for the Java sources, and it should be enough to have compile-only dependency on `kotlin-annotations-jvm.jar`.
Which doesn't contradict the "pure Java project" case.

## Kotlin-to-Java expect-actual incompatibilities diagnostics reporting

**Invariant 1.** Kotlin compiler cannot report compilation errors in non-kt files.

In Kotlin-to-Kotlin actualization, expect-actual incompatibilities are reported on the actual side.

In Kotlin-to-Java actualization, it's proposed to report incompatibilities on the expect side.
It's inconsistent with Kotlin-to-Kotlin actualizations, but we don't believe that Kotlin-to-Java actualization is significant enough to break the _invariant 1_.
The reporting may be improved in future versions of Kotlin.

## Direct actualization forces expect and actual to be in the same compilation unit

In the [introduction](#introduction), we mentioned that _direct actualization_ forces expect and actual to be in the same "compilation unit."
It's an implementation limitation that we believe is beneficial, because it reflects the reality.

It's a common pattern for libraries to use a unique package prefix.
We want people to stick to that pattern.
_Direct actualization_ for external declarations encourages wrong behavior.

-   Imagine that it is possible to write an expect declaration for the existing JVM library via _direct actualization_ mechanism.
    Users may as easily decide to declare non-expect declarations in the same package, which leads to the "split package" problem in JPMS.
-   Test case: there is an existing JVM library A.
    Library B is a KMP wrapper around library A.
    Library B provides expect declarations for the library A.
    Later, Library A decides to provide its own KMP API.
    If Library B could use _direct actualization_, it would lead to declarations clash between A and B.

## The frontend technical limitation of Kotlin-to-Java direct actualization

Frontend transformers are run only on Kotlin sources.
Java sources are visited lazily only if they are referenced from Kotlin sources.

Given frontend technical restriction, it's proposed to implement Kotlin-to-Java _direct actualization_ matching and checking only on IR backend.

## Alternatives considered

-   Implicitly match Kotlin-to-Java with the same ClassId if some predefined annotation is presented on the expect declaration.
-   `actual typealias` in Kotlin without target in RHS.
-   `actual` declaration that doesn't generate class files.

    It could be some special annotation that says that bytecode shouldn't be generated for the class.
    The idea is useful by itself, for example, in stdlib, to declare `kotlin.collections.List`.

    The disadvantages are clear: unlike the current proposal, there will be no compilation time checks;
    compared to the current proposal, it will result in excessive code duplication in the expect-actual case.

See [actual keyword is a virtue](#actual-keyword-is-a-virtue) to understand why alternatives were discarded.
Besides, the proposed solution resembles the already familiar Kotlin-to-Kotlin _direct actualization_, but makes it available for Java.

## Unused declaration inspection in Java

IntelliJ IDEA implements an unused declaration inspection.
The `javac` itself doesn't emit warnings for unused declarations.

The inspection in IDEA should be changed to account for declarations annotated with `@kotlin.annotations.jvm.KotlinActual`.

## Feature interaction with hierarchical multiplatform

There is no feature interaction. It's not possible to have Java files in intermediate fragments.

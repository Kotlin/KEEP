# Instantiation of annotation classes

* **Type**: Design proposal
* **Author**: Leonid Startsev
* **Status**: In review
* **Prototype**: In progress
* **Issue:** [KT-45395](https://youtrack.jetbrains.com/issue/KT-45395)

## Synopsis

Allow calling constructors of annotation classes in arbitrary code
to obtain an instance of a particular annotation class.

## Motivation

### 1. Better interoperability with Java

In Java, annotations are represented as interfaces, and therefore it is possible to implement
those interfaces. Various Java APIs make use of this and may require an instance of `Annotation` interface
to be passed to them (see [KT-25947](https://youtrack.jetbrains.com/issue/KT-25947) for details).

### 2. Less kotlin-reflect usage

While [it's possible to call annotation constructor via reflection](https://youtrack.jetbrains.com/issue/KT-25947#focus=Comments-27-4203054.0-0), such an approach is unobvious, cumbersome and involves usage of Kotlin reflection implementation in a separate kotlin-reflect runtime library.
The ability to directly call annotation constructor would make this task easier.

### 3. Better multiplatform support

While annotations are common in the JVM world, their applications in multiplatform programming are still limited.
Sometimes, it makes user code a second-class citizen compared to specialized solutions, such as a compiler plugin.
For example, while [kotlinx.serialization plugin](extensions/serialization.md) automatically instantiates and stores [SerialInfo annotations](https://kotlin.github.io/kotlinx.serialization/kotlinx-serialization-core/kotlinx-serialization-core/kotlinx.serialization/-serial-info/index.html) in generated serializer,
user-written serializer need to implement and store such annotations manually (see [Kotlin/kotlinx.serialization#328](https://github.com/Kotlin/kotlinx.serialization/issues/328)).

## Description

While it might seem logical to follow Java convention and allow annotation classes to be open, it is clear that all use-cases exist around an annotation class instance. Therefore, it makes sense to provide a mechanism to instantiate an annotation class instead of implementing it. Such an approach also allows complying with [`Annotation`'s contract on equals and hashCode](https://docs.oracle.com/javase/8/docs/api/java/lang/annotation/Annotation.html#equals-java.lang.Object-) without additional user code written.

The most straightforward way to instantiate a class in Kotlin is to call its constructor. This proposal suggests allowing user code to call annotation class constructor to obtain an instance. User code may look like this:

```kotlin
annotation class InfoMarker(val info: String)

fun processInfo(marker: InfoMarker) = ...

fun main(args: Array<String>) {
    if (args.size != 0)
        processInfo(getAnnotationReflective(args))
    else
        processInfo(InfoMarker("default"))
}
```

Current annotation class' limitations (no members including secondary constructors, no non-vals parameters, etc.) remain intact.

### Implementation

Note that a particular implementation class for JVM is not used in the example and is hidden as an implementation detail.
This allows the compiler to perform certain optimizations — a special synthetic implementation class can be generated on-demand, like with lambdas or SAM
conversions, to avoid additional code generation for every annotation in a project.
Also, on Native, unlike JVM, annotation classes themselves don't have to be interfaces and can be instantiated directly.

### Java interop

With the strategy that generates annotation implementation on-demand, it becomes possible in future
to allow calling constructors of annotations defined in Java code as well.
On the other side, synthetic implementation of Kotlin annotation is not visible to Java code. This is not necessarily a problem because Java clients can still manually implement any annotation in Java way.
# Design Notes on Kotlin Value Classes

* **Type**: Design notes
* **Author**: Roman Elizarov
* **Contributors**: Alexander Udalov, Andrey Breslav, Dmitry Petrov, Ilmir Usmanov, Ilya Gorbunov, Marat Akhin, Maxim Shafirov, Mikhail Zarechenskiy, Stanislav Erokhin  
* **Status**: Under consideration
* **Discussion and feedback**: [KEEP-237](https://github.com/Kotlin/KEEP/issues/237)

This is not a design document, but an exploratory description of the current state of value classes in Kotlin and potential ways for their future evolution. The purpose of this document is to define a common terminology, provide some insight into potential avenues for features that are based on value classes, and thus establish a channel for discussion with the Kotlin community on use-cases for these features, their potential syntax, impact on existing Kotlin code, etc.

We, in the Kotlin team, are aware of and try to follow 
[minus 100 points](https://docs.microsoft.com/en-us/archive/blogs/ericgu/minus-100-points)
language design rule.  Any new feature in the language has to clear a high bar on utility it brings to compensate for the complexity it will entail. This document lists a lot of ideas that are complex both conceptually and in terms of implementing them in the compiler. The decision on whether they are that much useful to be added into the language is still to be made.

**Table of contents**

<!--- TOC -->

* [Introduction](#introduction)
  * [Built-in primitive value classes](#built-in-primitive-value-classes)
  * [Inline classes are user-defined value classes](#inline-classes-are-user-defined-value-classes)
  * [Deep immutability vs shallow immutability](#deep-immutability-vs-shallow-immutability)
  * [Compiling value classes: the single-field restriction](#compiling-value-classes-the-single-field-restriction)
  * [Project Valhalla](#project-valhalla)
  * [Multifield value classes before Valhalla](#multifield-value-classes-before-valhalla)
  * [Value classes vs structs](#value-classes-vs-structs)
* [Immutability and value classes](#immutability-and-value-classes)
  * [Updating immutable classes](#updating-immutable-classes)
  * [Mutable properties of immutable types](#mutable-properties-of-immutable-types)
  * [Compiling mutable properties on JVM](#compiling-mutable-properties-on-jvm)
  * [Abstracting mutation into functions](#abstracting-mutation-into-functions)
  * [Var this as a ref (inout) parameter in disguise](#var-this-as-a-ref-inout-parameter-in-disguise)
  * [Updating properties with mutating setters](#updating-properties-with-mutating-setters)
  * [Extension receiver vs dispatch receiver](#extension-receiver-vs-dispatch-receiver)
  * [Deep mutations](#deep-mutations)
  * [Call-site syntactic indicator of mutation](#call-site-syntactic-indicator-of-mutation)
  * [Value interfaces](#value-interfaces)
  * [Read-only collections vs immutable collections](#read-only-collections-vs-immutable-collections)
  * [Mutating functions returning a value](#mutating-functions-returning-a-value)
  * [Mutating operators](#mutating-operators)
  * [Mutating function types and lambdas](#mutating-function-types-and-lambdas)
  * [Mutating scope functions](#mutating-scope-functions)
* [Legacy and migration](#legacy-and-migration)
  * [Migrating built-in primitive types to value classes](#migrating-built-in-primitive-types-to-value-classes)
  * [Strings and other value-based classes](#strings-and-other-value-based-classes)
  * [Augmented mutating assignment operator](#augmented-mutating-assignment-operator)
  * [Mutating functions naming convention](#mutating-functions-naming-convention)
  * [Shall class immutability be the default?](#shall-class-immutability-be-the-default?)
* [Value classes and arrays](#value-classes-and-arrays)
  * [Arrays of inline value classes](#arrays-of-inline-value-classes)
  * [Valhalla arrays](#valhalla-arrays)
  * [Reified value arrays](#reified-value-arrays)
  * [Boxed value arrays](#boxed-value-arrays)
  * [Unifying arrays of references and values](#unifying-arrays-of-references-and-values)
  * [Efficient generic collections](#efficient-generic-collections)
* [Name-based construction of classes](#name-based-construction-of-classes)
  * [An example with a mutable class](#an-example-with-a-mutable-class)
  * [Uninitialized properties](#uninitialized-properties)
  * [DSL-style initialization blocks for mutable classes](#dsl-style-initialization-blocks-for-mutable-classes)
  * [Flexible initialization for readonly properties](#flexible-initialization-for-readonly-properties)
  * [Flexible initialization for mutable properties of value classes](#flexible-initialization-for-mutable-properties-of-value-classes)
  * [ABI strategy for uninitialized properties](#abi-strategy-for-uninitialized-properties)

<!--- END -->
 
## Introduction

Instances of all user-defined classes in Kotlin have an identity and the corresponding 
[referential equality operator](https://kotlinlang.org/docs/reference/equality.html) (`===`).
It is a vital concept for mutable classes. When you have two references that point to the same instance, then modification that had happened though one reference are visible through the other reference because, in fact, the single underlying object instance is being modified
([playground](https://pl.kotl.in/JqL2vR0ES)):

```kotlin
data class Project(val name: String, var stars: Int = 0)

fun main() {
    val a = Project("Kotlin")
    val b = a // reference to the same instance
    println("b = $b") // b = Project(name=Kotlin, stars=0)
    a.stars += 9000 // modify through a
    println("b = $b") // b = Project(name=Kotlin, stars=9000)
    println(a == b) // true -- same value
    println(a === b) // true -- same instance
}
```

For immutable classes, though, the concept of identity does not have much use. Take a `String` class, for example. 
`String` instances are immutable, so you cannot pull the same trick as with the mutable `Project` class above. Any operation that modifies a string returns a new instance of a string and cannot have any effect on the original one. Moreover, to modify an immutable class like `String` we have to declare the corresponding reference to it as `var`
([playground](https://pl.kotl.in/0Qtt8mivq)):

```kotlin
fun main() {
    var a = "Kotlin" // must be var to modify it
    val b = a // reference to the same instance
    println("b = $b") // b = Kotlin
    a += "!" // modify a
    println("b = $b") // b = Kotlin
    println(a == b) // false -- different value
    println(a === b) // false -- different instance
}
```

However, these immutable classes in Kotlin still have an identity, so, one can write code that depends on the identity of individual `String` instances and distinguishes two `String` instances that are structurally equal, but have a different identity 
([playground](https://pl.kotl.in/sGrxMKIdb)):

```kotlin
fun main() {
    val a = "Kotlin"
    val b = a.filter { it != ' ' } // Still "Kotlin"
    println(a == b) // true -- same value
    println(a === b) // false -- different instance
}
```

The above code is fragile. The implementation of 
[String.filter](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/filter.html)
in the Kotlin standard library does not specify its behavior with respect to returning the same or different instance and can be changed in the future.
**Relying on a specific identity of an immutable class like String is a bad programming practice**.

> There are a number of legitimate cases for using identity of immutable classes, though. If identities of two immutable classes are the same, they must represent the same value, so a typical implementation of the equals (value comparison) method for immutable classes (like `String`) compares their identities first, as a fast-path performance optimization. Also, some operations on immutable classes (unlike the above example with `filter`) might provide an additional guarantee that the result of the operation has the same identity if the operation did not do anything and with such an operation an identity comparison can be used to see if the operations had made any changes.

### Built-in primitive value classes

Since its inception, Kotlin has a number of primitive built-in standard library classes `Int`, `Long`, `Double`, etc that don’t have a stable identity at all. They behave like regular classes for all the purposes with the exception that they don’t have the very concept of identity, and the reference equality operator (`===`) for them is deprecated (and will be removed in the future). They are prime examples of _value classes_. To see that they don’t have a stable identity you can cast them to a reference-based `Any` type. This forces a value class to be boxed, creating a temporary identity object. Having done that, you can apply a reference equality operation to the result
([playground](https://pl.kotl.in/pq8ftp4mZ)):

```kotlin
fun main() {
    val a = 2021
    val b = a
    println(a == b) // true -- same value
    println((a as Any) === (b as Any)) // false -- different instance
}
```

**Values classes are immutable classes that disavow the concept of identity for their instances.** 
Two instances of a value class with the same contents (fields) are indistinguishable for all purposes.

This allows compiler and runtime to freely choose their actual representation. If their instances are small enough, they can be directly stored inside other objects and passed between functions by embedding their value. If they occupy a lot of memory then it is more efficient to pass around a reference to a memory location that contains their value (aka a box). Moreover, when a value class is boxed, a compiler is free to copy it at any time and create a box with a new identity as needed.

### Inline classes are user-defined value classes

Inline classes 
(see the corresponding [Inline Classes KEEP](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md)) 
were experimentally implemented in Kotlin since 1.2.30 and are, in fact, **user-defined value classes**. 
Their primary feature is that they explicitly disavow identity and reference equality operator (`===`) for them is not available (produces a compilation error). This allows a compiler to optimize representation of Kotlin inline classes, storing their underlying value instead of a box in many cases.

> The umbrella issue for Kotlin inline value classes is [KT-23338](https://youtrack.jetbrains.com/issue/KT-23338).

The original proposal was to use the `inline` modifier before the `class` keyword. That was discovered to be confusing, because users who are familiar with inline functions in Kotlin find that `inline class` is quite a different concept. In particular:

* The functions of inline class are not inline.
* Inline classes do not provide any additional semantic benefits to the programmer like inline functions do (providing ability to do non-local returns).
* Unlike inline function, which is always inlined by the Kotlin compiler, the inline class is not actually “inlined” in all cases. Just like built-in value classes in Kotlin (`Int`, `Long`, etc), there are many cases when inline class must be boxed and passed by reference to the resulting identity object.

Inline modifier for a class essentially just takes away its identity and brings additional restrictions.

The very meaning of the word “inline” seems to indicate that the developer intent was to avoid boxing those classes. However, Kotlin is not a systems programming language. Even though performance is very important for Kotlin, it does not feel right to have this kind of purely performance-oriented mental model in an application programming language.

> In an ideal world, a compiler should be capable of figuring out that an immutable class with a single underlying field would be more efficiently passed around without boxing it, simply passing its underlying value around. This optimization is sometimes knows as boxing elimination, allocation elimination, stack allocation, or scalar replacement. The potential for this compiler optimization is hindered by the fact that objects have an identity. Modern compilers/VMs perform escape analysis to figure out when a reference to an instance does not escape some scope and optimize away the corresponding box if possible. However, for large code-bases and big data-structures this escape analysis does not work, because there is always a chance that there is a piece of code lurking somewhere that would rely on the identity of the particular object. The compiler is rarely capable of proving that it can drop this particular object’s identity and optimize away boxing.

A good mental model for Kotlin is:

* **Step 1:** I, as a developer, declare that this is a value class, and I’m not going to rely on a stable identity of its objects.
* **Step 2:** Now compiler can safely optimize away boxing whenever it can.

Combining all of that, the decision was reached to rename the `inline` modifier to `value`.

### Deep immutability vs shallow immutability

When we say that “value classes are immutable”, we mean a _shallow immutability_ where we only guarantee in the language that direct fields of the corresponding value class cannot be mutated. When we want to highlight this difference and bring attention to the fact that value class immutability is only shallow, we can say they are _readonly classes_. However, Kotlin value classes are designed to be used to represent truly deeply immutable data-structures. We expect that developers will be usually writing value classes that only contain other immutable classes in their fields, and so we would be calling them simply immutable classes most of the time.

> It is important, though, to always refer to Kotlin collection interface types like List as read-only collections. They are different from immutable collections. See a separate section on “Read-only collections vs immutable collections”.

The ability to write a value class that is still mutable (because it references some mutable class) is pragmatic. For example, if we need to lazily store a result of some computation, we can just add `Lazy<T>` field to a value class. This is allowed, despite the fact that `Lazy` is technically a mutable class.

There are certain domains where there needs to be a guarantee of deep immutability for a data structure rooted at a specific object. This recursive requirement on a data structure arises in many domains, especially when different parts of code are executed in different contexts and there are constraints on what kinds of classes can be transferred or shared between these execution contexts. There can be a recursive requirement of serializability or a recursive requirement for some other domain-specific constraint. For example, only certain classes of objects can be transferred to JS workers, only limited classes of objects can be shared with GPU, etc. Language support for such recursive data-structure requirements will cover the case of deeply immutable objects, too, but it is outside the scope of this document.

### Compiling value classes: the single-field restriction

Kotlin is multiplatform and has different compiler backends with different compilation models.

**Kotlin/Native** has _closed world_ compilation. In the closed world compiler knows all the classes that will be run at runtime and is free to choose the underlying ABI (application binary interface) for objects of value classes (whether they should be boxed or not) based on the whole-program analysis, without having to get any hints from the developer. Kotlin/Native can support value classes with multiple fields. Moreover, Kotlin/Native, being based on LLVM, could be passing around and storing value objects with multiple underlying fields without boxing them with relative ease.

**Kotlin/JS** is moving to a closed-world model, too, with a new IR-based backend, and has quite a number of compilation strategies to choose from, but returning multiple values from a JS function will still require boxing.

However, with **Kotlin/JVM** the story is different. First, Kotlin/JVM compiles code in an _open world_ model. Every public Kotlin/JVM function can be called by an arbitrary separately-compiled JVM code that is known only at run-time, so public Kotlin/JVM functions must have a stable ABI which has to be statically selected based on the signature of the function that is being compiled.

Second, on JVM there is no efficient way (yet) to support value classes with more than one underlying field, because they will have to be boxed every time they are returned from a function. Value classes with a single underlying field can be compiled efficiently, and we have designed a stable JVM ABI for them with function name mangling (that is covered in the [inline classes KEEP](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md)) to fit the calling convention for Kotlin inline value classes into the constraints of JVM.

These are the basic reasons that user-defined value classes are currently restricted to just one underlying field, but we will be lifting those restrictions in the future. More on it follows below.

### Project Valhalla

Java Project Valhalla 
(see [The State of Valhalla](https://cr.openjdk.java.net/~briangoetz/valhalla/sov/01-background.html) by Brian Goetz) 
is promising to bring user-defined primitive classes to JVM 
(see also [the draft JEP for primitive classes](https://openjdk.java.net/jeps/8251554) by Dan Smith).

> The state of Valhalla document still calls them “inline classes”, but JEP draft is calling them “primitive classes”. It looks like JVM design team is inclining to name them primitive classes in the end. This name has changed multiple times (the original name was “value classes”), and the name may still change again before the final release.

In the future, in a Valhalla-capable JVM, JVM primitive classes will enable efficient representation of Kotlin value classes with an arbitrary number of underlying fields on JVM. However, the exiting, limited implementation of Kotlin value classes with a single underlying field has a lot of use-cases and an enormous community demand. We cannot wait to make it stable until Valhalla becomes available in some unclear future. That was a motivation to require a `@JvmInline` annotation on all Kotlin/JVM value classes even as we currently have only a single compilation strategy for them on JVM. It means that in Kotlin 1.5 value classes for Kotlin/JVM are declared like this:

```kotlin
@JvmInline
value class Color(val rgb: Int)
```

The `@JvmInline` annotation makes it explicit that something special with this class is going on in JVM, and it enables us to support non-annotated value class in the future Valhalla JVM by compiling them using the capabilities of the Project Valhalla.

> The key design driver to naming the annotation `@JvmInline` was conceptual continuity for early adopters of Kotlin value/inline classes. This naming move is still risky, since it assumes that the corresponding Valhalla concept will indeed get named a “primitive class”. If Valhalla matures to a release with “inline class” naming then we’ll have to deprecate Kotlin `@JvmInline` annotation and find another name for it to avoid confusion with a stable Valhalla JVM release.

Why not the reverse? Why not a use a plain value class now, and add some `@Valhalla` value class in the future? The answer is that, so far, Valhalla promises to be the right way to implement value classes on JVM and Kotlin’s key design principle in choosing defaults is such that “the right thing”, the thing that developers will be using most of the time, should correspond to the shorter code. Ultimately, with the Project Valhalla, it will be the right thing to compile a default (non-annotated) value class with Valhalla, so it means that we must require an annotation now, with pre-Valhalla value classes, to avoid breaking changes for stable Kotlin libraries in the future.

### Multifield value classes before Valhalla

Even when Valhalla-capable JVM becomes available, it will not be widely adopted very fast. At what point and under what conditions the Kotlin language should support user-defined value classes with more than one underlying field?

> The issue for multifield value classes is [KT-1179](https://youtrack.jetbrains.com/issue/KT-1179).

It is not right to make an important Kotlin language feature like “support for value class with multiple fields” dependent on a specific backend. The Kotlin’s multiplatform philosophy is that all Kotlin language features (with as few exceptions as possible) should be supported by all its backends. The compilation strategy and performance on different backends could be different (and is different for many existing Kotlin features), so it is fine if multi-field value classes work for all Kotlin platforms (JVM, JS, Native), but might not be as efficiently compiled when targeting pre-Valhalla JVM.

> The motivation to have some backend-specific features, like `dynamic` type in Kotlin/JS, is typically driven by the specific platform integration needs.

In fact, there are legitimate use-cases for “inlined” multi-field value classes support on pre-Valhalla JVM. Consider the following example:

```kotlin
@JvmInline
value class Complex(val re: Double, val im: Double)
```

On a pre-Valhalla JVM all operations returning `Complex` values will have to return a box. However, in tight computation loops the JVM should be able to eliminate those boxes after performing escape analysis. This is not going to be 100% reliable optimization, but a typical mathematical code that does a lot of complex computation should still perform faster with it.

What is important, is that passing `Complex` values into functions and storing them as fields inside other objects can be done directly without boxing even before Valhalla. This can lead to significant overall improvements and is an improvement direction that is worth considering in the future before Valhalla takes shape.

> At this point, this is all theoretical. A performance benefit (if any) will have to be measured for specific use-cases before making a decision to actually implement support for `@JvmInline` value class with multiple fields on a pre-Valhalla JVM.

### Value classes vs structs

A number of languages, like C++, C#, Rust, Go, Swift, and others have a concept of a `struct`.

> C++ `struct` is just a `class` that defaults to public visibility for its members, so the discussion below is also relevant to C++ classes. However, modern languages that have both `class` and `struct` keywords tend to give them quite different semantics unlike C++.

A struct is a similar, but a slightly different concept than a value class in Kotlin. First, some of those languages support mutable structs that have an identity (even if they are stack-allocated), can be referenced and modified in place. For this discussion, we’ll limit ourselves to immutable structs. The conceptual model of a struct in those languages is that a struct is a composite bunch of values that is always directly embedded into another struct/class when used as a field and is typically always “passed by value” into functions, copying their contents, unless some other explicit language mechanism is used to pass the corresponding struct by reference or to explicitly box it into an object with identity. It means that structs are not always the right choice for modelling business entities, which tend to be complex, contain lots of fields, and may quickly become very big. Copying structs to pass them around may become very expensive.

The conceptual model behind value classes is different. A developer’s intent of writing a value class is to explicitly disavow its identity and to declare, upfront, its immutability. This declaration enables compiler optimization such as passing those values directly, but it does not guarantee them.

Kotlin is multiplatform, and some platforms may be simply incapable of supporting such optimizations. Moreover, even on platforms that are capable of such optimization, it is more efficient to represent big value classes as references to the corresponding boxes, and the compiler is often in good position to make these decisions. In cases where storing and passing the values directly is important and where compiler heuristics do not give the optimal result, we can support additional annotations in the source code, allowing developer to give a hint to the compiler with the desired strategy.

> Primitive class in JVM Project Valhalla is conceptually similar to a Kotlin value class in this respect. JVM will allocate instances of big primitive classes on the heap and will pass around a reference to the corresponding memory using its own heuristics on what to consider a “big value”.

## Immutability and value classes

The concept of a value class serves as a solid basis to embrace immutable data. Immutability is important for design of modern systems. Notably, immutable data is often used for asynchronous programming to make it less error-prone. Immutability is not cheap, but in a modern application development we can often afford it 
(see more discussion in [Immutability We can Afford](https://elizarov.medium.com/immutability-we-can-afford-10c0dcb8351d) by Roman Elizarov).
The story of immutability ties especially well into Kotlin coroutines.

However, Kotlin still treats immutable classes as somewhat second-class. Kotlin has symmetric support for mutable `var` and readonly `val` properties, but this symmetry is skin-deep. Consider the following code that maintains a mutable state and periodically distributes updates to it.

```kotlin
data class State(
    var lastUpdate: Instant,
    var tags: List<String>,
    // ...
)

class Repository {
    private val state = State(/*...*/)

    fun addTag(tag: String) {
       state.lastUpdate = now()
       state.tags += tag
       notifyOnChange(state.copy()) // BE CAREFUL HERE !   
    }
}
```

Now, since the current `state` is modeled by the mutable `State` class, the challenge becomes to correctly implement call to the `notifyOnChange` function whenever any kind of asynchronous pipeline is involved (as it often happens in UIs, for example). The Kotlin `data class` feature helps in this particular example, as we can use `copy()`. In general, it is quite error-prone with a deeply mutable data structure since `copy()` is only shallow.

Immutability saves us here as we don’t need to use `copy()`. There’s no risk of accidentally forgetting to copy an immutable object. The code with immutable state will look like this:

```kotlin
data class State(
    val lastUpdate: Instant, // var -> val
    val tags: List<String>,  // var -> val
    // ...
)

class Repository {
    private var state = State(/*...*/) // val -> var

    fun addTag(tag: String) {
       state = state.copy(
           lastUpdate = now(), 
           tags = state.tags + tag
       )
       notifyOnChange(state) // NO NEED TO COPY HERE !    
    }
}
```

As you can see, what we’ve gained in copy-avoidance in the call to `notifyOnChange` we’ve more than lost with inconvenient updates of immutable state:

* We cannot use compound assignments like `+=` with immutable properties.
* We cannot as easily use arbitrary logic in our update code (ifs, loops, etc) as `copy` has to be called once or calls to `copy` have to be repeated over and over.
* It is hard to modify deeply nested immutable structures, as it produces hard-to-understand nested `copy` calls.

### Updating immutable classes

**In order for immutability to shine in Kotlin, updates to immutable structures should not be more verbose in source code than updates of mutable data.** 
Otherwise there’s always going to be a desire to cut corners and introduce mutability just for the sake of writing more concise code with less boilerplate. That is undesirable, since immutability makes code safer. Writing “the right code” should not require more effort than writing “the wrong code”.

> The idea follows from a total analogy with coroutines that hid callback-hell from the source code. With coroutines the source looks elegant. Most of the time application developers don’t care what target code the compiler produces behind the scenes to implement it as long as the source code clearly expresses the developer’s intent with respect to the business logic of the code.

Thus, the ultimate Kotlin syntax for working with immutable classes is the same as for working with mutable classes, but without the need to ever worry about `copy()`:

```kotlin
fun addTag(tag: String) {
    state.lastUpdate = now()
    state.tags += tag
    notifyOnChange(state)    
}
```

> The above syntax is conceptually a sugar for the explicit `state = state.copy(...)` invocation in the previous version. For the purpose of this section you can think of this version of `addTag` function as being compiled to the code that uses `copy` under the hood.

This looks scary at the first sight just like design for calls of `suspend` functions looked scary first without the explicit `await` keyword in the source. Let us see what this syntax entails.

First, enabling this syntax for regular classes or data classes would be confusing. `state.field = value` syntax already has a specific (different) meaning in the world of mutable classes. Moreover, regular classes have identity. When you write `state = state.copy(lastUpdate = now())` it is quite clear to the reader that the identity of the current `state` object is going to change, since there is a clear assignment to a `state` variable, but if you write `state.lastUpdate = now()` that is not so immediately clear that `state` identity is being updated. It could create a puzzler when `State` is a mutable class itself and can, essentially, be updated in two separate ways &mdash; by mutating an object or by creating a different object.

> Things like `var list: MutableList<T>` (where you have a mutable property storing a reference to an instance of a mutable type) are allowed in Kotlin now, but are quite error-prone and are generally considered bad code style. We don’t want to make it worse by adding new ways to create a copy of a mutable type.

That is where the concept of _immutable identity-less value class_ comes into play. For a value class like `State` the statement` state.lastUpdate = now()` cannot have any other meaning, but to create a copy with a different value for `lastUpdate`. If a `state` is not declared as `var`, then this code will not compile at all. It is safe to allow this short-hand mutation syntax for value types as there will be no confusion on what it does. The style of this code fits the Kotlin vision for fun in development &mdash; we can work with immutable classes without any ceremony.

### Mutable properties of immutable types

To support `state.lastUpdate = now()` syntax for value types, we need to be able to declare `lastUpdate` property of the immutable value class `State` as a _mutable property_. One option is to invent a new keyword, in addition to `val` and `var` that means “immutable property that can be updated by creating a new value of the containing class behind the scenes”.  However, we can see that value classes, being immutable, cannot have regular `var` (mutable) properties anyway, so we can safely reuse `var` keyword for this purpose. That is, when we write:

```kotlin
value class State(
    var lastUpdate: Instant,
    // ...
}
```

The meaning of this `var` (since it is written inside the `value` class) is that you can use `state.lastUpdate = now()` statement when state itself is `var` (mutable). To make sure this syntactic option is not blocked in the future, we now forbid definition of any kind of `var` properties (including extension properties) in value classes.

> The similar syntactic option is chosen by Swift, whose structs are likewise immutable value types. Swift has `var` (mutable) and `let` (immutable) properties and uses `var` to denote properties of structs that can be mutated to get the new value of the struct. This Swift’s feature is the main inspiration for looking into the similar syntactic feature in Kotlin.

On the surface, this looks like a stretch for the usual meaning of `var` property, but if you take a closer look at the example of how those mutable properties are updated in the source code (just like mutable `var` properties of mutable classes), then this does not look like a stretch anymore.

> The corresponding issue for convenient update convention for immutable value classes is [KT-44653](https://youtrack.jetbrains.com/issue/KT-44653).

### Compiling mutable properties on JVM

The simple implementation for mutable properties of immutable value classes on JVM could be done via existing approach like `copy(...)` function for data classes. However, the `copy` function has a problem with respect to separate compilation and stable ABI. Adding more properties to a class breaks ABI of the `copy` function for all its previously compiled clients.

> See a detailed explanation in [Public API challenges in Kotlin](https://jakewharton.com/public-api-challenges-in-kotlin/) by Jake Wharton.

Mutable classes do not have a similar problem. You don’t have to use `copy` function with mutable classes and usually you don’t. Properties of mutable classes are compiled to a pair of `getXxx` and `setXxx` methods on JVM and are stable with respect to the natural evolution of the corresponding mutable classes. New properties can be added to mutable classes while preserving binary compatibility with respect to the clients that were compiled against an older version of the corresponding class. The goal of 1st-class support for immutable classes (on par with mutable classes) motivates us to look for a better solution.

A mutable property `var lastUpdate: Instant` of a value class `State` can be desugared for Java/JVM into the following two methods:

```java
class State {
    Instant getLastUpdate(); // a usual Kotlin getter
    State withLastUpdate(Instant value); // a "wither" to mutate an immutable class
}
```

With this compilation strategy `state.lastUpdate = now()` statement would get desugared during compilation into `state = state.withLastUpdate(now())`.

The seeming downside of this strategy is that a sequence of updates like this:

```kotlin
state.lastUpdate = now()
state.tags += tag
```

Would be compiled using two `withXxx` calls, allocating an intermediate `State` object:

```kotlin
state = state.withLastUpdate(now())
state = state.withTags(state.tags + tag)
```

The intermediate `State` object does not escape the local scope and would be optimized away by an advanced JVM. However, these kinds of optimization that are based on escape analysis are not reliable. Fortunately, we can further optimize such code in Kotlin/JVM if it is written inside the `State` class itself (or maybe even in the same module). A Kotlin compiler can analyze what combination of State properties are being updated together in this module, generate a specialized internal updating method, and compile this code into something like:

```
state = state.with$LastUpdate$Tags$internal(now(), state.tags + tag)
```

> Here we explicitly use the identiless-ness of value classes. Because an identity of a value class is not important the compiler is free to split or combine sequence of updates to value classes in any way. It is even possible to temporarily keep updated properties of value classes in local variables up to the moment when an instance of a value class is needed to pass it to some other function and only then create an actual, updated, value class instance.

Even the basic variant of this optimization would provide efficient and reliable mass-mutation for value classes. For cases when such mutation should be performed from another class or module, abstraction mutation into functions will help. See the next section.

For a closed-world compilation (like in Kotlin/Native) we can make sure that chained mutations of value classes are always optimized and do not perform unnecessary allocations.

### Abstracting mutation into functions

It is not enough just to support short-hand copying convention like `state.lastUpdate = now()`. You should be able to abstract these kinds of operations on value classes into functions without losing syntactic convenience, just like you do for mutable classes. Let us see how you can write a “mutating” method for an immutable class `State` in the current version of Kotlin:

```kotlin
fun State.updatedNow(): State =
    state.copy(lastUpdate = now())
```

We have to write functions that update immutable classes in a “wither style”. Sometimes a convention of naming such functions as `withXxx` may be used, but we don’t follow it here. Instead, in this example we follow Kotlin naming convention that is used to distinguish two kind of methods on mutable classes (see `sort()` vs `sorted()`, etc). Anyway, the very declaration of such a function is a boilerplate:

* A type has to be mentioned twice: as a receiver type and as a return type.
* The intent of writing a function that returns an updated receiver is not immediately clear and can get lost in the boilerplate, especially for more complex functions with many parameters.

It is not that bad if you have few such functions, but for a big data model it quickly becomes tenuous without some kind of code generation or without a language feature that we will be talking about here.

With support for mutable properties in value classes the body of this function might be changed to:

```kotlin
fun State.updatedNow(): State {
    var result = this // must declare as var ...
    result.lastUpdate = now() // to perform update of immutable state
    return result
}
```

This gives no improvement and is still far cry from the simplicity of working with mutable classes. So, members and extensions of value classes should support a mechanism to declare a function with a mutable receiver, that is, a function that exposes `this` receiver reference as `var` and implicitly returns the updated value of `this`. The best way to achieve it is via a modifier for a function declaration:

```kotlin
mutating fun State.updateNow() { // implicitly returns new State
    lastUpdate = now() // this.lastUpdate = is possible because of var this
}
```

> Swift uses `mutating` modifier for this purpose and that is why it is used as a tentative name for this feature. It is a well-fitting name for Kotlin, too.

A _mutating function_ for an immutable value class returns new value behind the scenes, without having to explicitly repeat the receiver type as the result type in the signature of the function declaration.

As you can see, this way we start a conceptual process of flipping defaults in favor of immutability for user-defined classes. This is not novel in Kotlin. In the standard library the shorter-named `List` is readonly (hence immutable if its element type is immutable), and a longer name of `MutableList` is explicitly used when mutability is needed. This implicitly makes immutability a default choice, which is great, since immutability is safer. In the same fashion, it is consistent and safe to forbid regular functions from modifying objects of value classes unless they explicitly opt-in with the dedicated `mutating` keyword.

Mutating functions also bring small niceties to other corners of the language with the appropriate support from the standard library. E.g. this kind repetitive code:

```kotlin
myVeryLongBooleanFlag = !myVeryLongBooleanFlag
```

could become straightforward and repetition-avoiding with`mutating fun Boolean.toggle()` that might be added to the standard library in the future:

```kotlin
myVeryLongBooleanFlag.toggle()
```

> The name of `Boolean.toggle()` mutating extension function is inspired by the corresponding function from Swift.

### Var this as a ref (inout) parameter in disguise

One can argue that mutating functions essentially bring 
[C#-style `ref` parameters](https://docs.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/ref#passing-an-argument-by-reference)
to Kotlin, also known as [`inout` parameters in Swift](https://docs.swift.org/swift-book/LanguageGuide/Functions.html).

A `ref` parameter is a way to declare a function parameter such that you can only pass a `var` property argument which the function can modify. 
If the Kotlin had `ref` parameters, then it would be fitting to mark it on a call-side with some kind of modifier to make it explicit.

> For example, in C# the call to a method with an `ref` parameter looks like `toggle(ref myVeryLongBooleanFlag)`. while in Swift the syntax for calling a function with an `inout` parameter is `toggle(&myVeryLongBooleanFlag)`.

So a mutating function can be seen as a function with a `ref this`. Does support for mutating functions mean Kotlin starts on the road of adding `ref` parameters in the future? Let us see.

From day one Kotlin was designed to encourage good programming practices. Drawing its inspiration from Java, Kotlin had added common idioms and widely accepted design patterns as language features as well as removed features and capabilities that often serve as source of bugs or otherwise produce hard to understand code.

In a world of mutable classes it considered normal to write methods that are called with a receiver of a certain mutable class and mutate the corresponding instance they are called for. It is also a good practice to name such method with a verb that brings attention to their mutating nature, for example:

```kotlin
account.updateBalance(transferDetails)
```

However, it is considered a bad practice to mutate objects that were passed to a method as its arguments, even when it references an instance of a mutable class. A programmer reading the above code naturally expects that `transferDetails` object is not changed. If it does change, that would be surprising.

It makes sense to codify this practice into the language itself, by only allowing mutation on the function receiver. In this case, when both `account` and `transferDetails` are represented with immutable value classes, then above call can only mutate the `account` instance. There will be no way to write surprising code that mutates its immutable parameter.

However, even in the Kotlin standard library we can find exceptions to the “no parameter mutation” rule. Consider the following function signature (a simplified version of the actual Kotlin stdlib function):

```kotlin
fun <T> Iterable<T>.filterTo(
    destination: MutableCollection<in T>,
    predicate: (T) -> Boolean
)
```

It filters its receiver into the first parameter, which has a type of a mutable collection, mutating the object that the parameter references to. Why does this function even exists? It exists to enable writing efficient code with mutable collections. It does make sense in the world of immutable collections, too, but let us see how it would be actually used here. For example, with 
[kotlinx.collections.immutable](https://github.com/Kotlin/kotlinx.collections.immutable) library 
you can write something like this:

```kotlin
val result = persistentList.update { dest ->
    otherCollection.fiterTo(dest) { /* predicate */ }
}
```

Here, when updating a `PersistentCollection` (which is an immutable data structure) we perform its update in a batch for efficiency reasons using its `update` function. It represents its updatable view inside the `update` function block with a `MutableList` interface. Here, again, a mutable interface is used. So, there seems to be no compelling need for `ref` parameters beyond a special case of mutating functions that update their receiver.

> There is a fringe case of functions like C#-style `swap(ref x, ref y)` that flips two variables, which is indeed useful when writing certain low-level algorithms. However, designing a `ref` parameters language feature just for the sake of few such functions is not prudent.

### Updating properties with mutating setters

In light of mutating functions, the special approach to mutable (`var`) properties of value classes can be seen as support for mutating setters. A regular mutable property of a regular mutable class has a getter and a setter:

```kotlin
class State { // mutable
    var lastUpdate: Instant
        get() { /* getter returning Instant */ }
        set(value: Instant) { /* setter updating State */ }
}
```

An immutable class instance cannot be modified, but we can still write setters for it if the setters are marked as mutating:

```kotlin
value class State { // immutable
    var lastUpdate: Instant
        get() { /* getter returning Instant */ }
        /*mutating*/ set(value: Instant) { /* setter mutating State */ }
}
```

We don’t have to require explicitly writing this `mutating` modifier on a value class setter. As was shown previously, it would be confusing to support regular non-mutating setters with value classes anyway (their instances are immutable and cannot be directly updated in the usual sense), so all setters for `var` properties of value classes can be implicitly considered mutating.

This makes declaration of user-defined properties without backing fields with value classes just as concise as the corresponding code for mutable classes. For example, a `State` class can expose its `lastUpdate: Instant` property as a string and support its update with the same amount of code that would be written for a mutable class:

```kotlin
value class State { // immutable value class
    var lastUpdate: Instant // mutable property with a backing field

    var lastUpdateString: String // computed mutable property
        get() = lastUpdate.toString()
        set(s: String) { // This is mutating function with var this
            lastUpdated = Instant.parse(s);
        }
}
```

### Extension receiver vs dispatch receiver

Kotlin functions can have two receivers and there is work in progress to introduce ability to add more receivers 
(see [KT-10468](https://youtrack.jetbrains.com/issue/KT-10468) Multiple receivers on extension functions/properties). 
Let us see how they interact with mutating functions.

```kotlin
class Dispatch {
    mutating fun Extension.doSomething()
}
```

The `doSomething` function has an _extension receiver_ `Extension` and a _dispatch receiver_ `Dispatch`. The intent of this declaration is to enable `extension.doSomething()` call in the context of a `Dispatch` receiver instance. Here the extension receiver is the _primary_ one as it is explicitly specified in the call-site usage of this function:

```kotlin
with(dispatch) { // dispatch receiver must be in the call context
    extension.doSomething() // extension receiver can be specified before the dot
}
```

From the section on `ref` parameters we can see that modifying the dispatch context from the `doSomething` function is a bad style. It is not even explicitly passed as an argument to the function. In the rare case when it might be needed, it can be achieved using a mutable dispatch receiver class. This leads us to conclude that the `mutating` modifier should apply only to the extension receiver of the function in this case. We can use the same analysis if Kotlin gets support for more receivers.

### Deep mutations

As was noted in the beginning of this section on immutability, the whole extent of convenience when working with mutable vs immutable data shows when you start to model nested business domain entities with them. Updating a deeply nested property in a mutable business object is as easy as writing:

```kotlin
order.delivery.status.message = updatedMessage
```

An equivalent update in the immutable data model currently requires writing something like the following code:

```kotlin
order = order.copy(
    delivery = order.delivery.copy(
        status = order.delivery.status.copy(
            message = updatedMessage
        )
    )
)
```

It is critical for acceptance of immutable data structures in Kotlin to make writing this kind of update as simple as the code for mutable classes:

```kotlin
order.delivery.status.message = updatedMessage
```

The difference between the code for an immutable value and the syntactically-same code for a mutable class, is that this code will not compile for an immutable value unless the `order`, `delivery`, `status`, and `message` properties are all declared as `var`, while the same code for a mutable business data model needs only the `message` property to be `var`.

The same consideration applies to invocation of mutation function in the deep chain of accesses as in `order.delivery.status.isComplete.toggle()`.

### Call-site syntactic indicator of mutation

The previous example with deep mutations serves as a good illustration that it is not very helpful to try to add some kind of call-site indication as to which variable in this chain is “actually mutated” and which part “is copied”. From the viewpoint of the developer who writes this kind of business code it is irrelevant. As long as the domain model that the developer is working with is mostly immutable (which is a common practice, for example, in highly asynchronous systems), object identity is not used, there is no reason to bring attention to this difference. Mutating value classes is safe. There’s no risk of accidentally mutating some object that might be also referenced from another piece of code.

However, mutating regular mutable classes is dangerous and is a source of countless bugs. That is why IntelliJ IDEA brings extra attention to all mutable state in an application by underlying mutable variables by default. So, in fact, when working with `var order: Order`, then example from the previous section will be rendered like this:

<code><u>order</u>.delivery.status.message = updatedMessage</code>

An order, being a variable, will be underlined in this code. This brings attention to the fact that any other code that works with this `var order` will see its change. There is no need to underline any of the `delivery`, `status`, or `message` properties if they are `var` properties of the immutable value classes. Updating a mutable property of a value class is safe. It cannot affect any other piece of code that might have kept a reference to the same object. An update of a mutable property of a value class produces a new value object, previous value object, which other code could have potentially referenced, remains intact.

Likewise, there is no immediate benefit in requiring extra call-side syntax for calls to mutating functions on value classes. They are just as safe as assignments. They are less error-prone than regular mutating methods on mutable classes.

It would be great if we can figure out a way to distinguish (and, at least, underline in an IDE) mutating methods of regular, mutable classes. However, unlike value classes, where non-mutating methods and mutating methods are clearly distinct (by the virtue of the `mutating` modifier), there is no such clear distinction in the world of mutable classes. Developers can only rely on naming conventions and deep knowledge of libraries they use to avoid various mutation-related pitfalls.

> We’d also like to explore potential approaches to explicitly marking mutating methods of mutable classes in the future, so they are distinct from read-only methods of mutable classes. It is a simple concept, that C++, for example, supports in the form of `const` methods. However, from a standpoint of modern software development practices, it is quite clear that C++ had chosen the wrong default. The default should be for the absence of mutation, while the ability to mutate an object should be explicit. Anyway, this thought avenue is out of the scope of this document that is focused on immutable value classes.

### Value interfaces

As value classes with mutable properties and mutating functions become used the need for further abstractions will quickly arise. For example, one would like to have multiple implementations of immutable lists sharing a common `ImmutableList<T>` interface. However, as we saw before, mutating functions and mutating properties will be supported only on value classes. It means that while value classes can implement interfaces, working with those interfaces will not be as syntactically convenient when they need to be mutated. For example, if an `ImmutableList` is declared like this:

```kotlin
interface ImmutableList<T> {
    fun add(element: T): ImmutableList<T> // note: boilerplate return type    
}
```

Then working with it is quite verbose and repetitive as in `myListOfItems = myListOfItems.add(newItem)`.
To bring the power of value classes to interfaces we can introduce the concept of value interfaces. They will support mutating functions and their `var` properties will implicitly have mutating setters.


```kotlin
value interface ImmutableList<T> {
    mutating fun add(element: T) // no more return-type boilerplate
}

var myListOfItems: ImmutableList<Item>
myListOfItems.add(newItems) // now without repetition
```

The `value interface` declaration will guarantee that all its implementing classes are value classes that have no identity and thus creating copies of their instances whenever needed for mutating operations is fine.

> Mutating functions on interfaces implicitly return the "self" type &mdash; a new value of the same type that the operation was called for, so that can be applied to `var` properties of any type that extends or implements the corresponding this value interface. This is more restricted than the general-purpose [KT-6494](https://youtrack.jetbrains.com/issue/KT-6494) Self types supports.

A standard library will need to define some root interface, which we can tentatively call `AnyValue` that will be implemented by all value classes and extended by value interfaces. This would allow using `AnyValue` interface in generic parameter constraints to define functions that can be only applied to value classes. Thus, generic mutating extension functions can be defined. Some use-cases for such functions will be shown later.

> JVM Project Valhalla plans to introduce a similar JVM `PrimitiveObject` interface that can be used to enforce the requirement for being implemented only by JVM primitive classes. However, for Kotlin we’ll need to support a migration strategy where some legacy values are compiled before Valhalla, while newer ones use Valhalla, so the actual design must be more complicated than simply making `AnyValue` interface extend JVM `PrimitiveObject`.

Note, that marking an interface or class as `value` adds additional restrictions to how it can be used and thus it is not, in general, neither a binary-compatible change, nor a source-compatible change for a stable library. See a separate section on migration.

### Read-only collections vs immutable collections

Kotlin has mutable collection types (e.g. `MutableList`) and read only collection types (e.g. `List`), and we always make a point of calling them _read only_ as opposed to _immutable_ collections (e.g. like the ones provided by [kotlinx.collections.immutable](https://github.com/Kotlin/kotlinx.collections.immutable) library). The difference is subtle, but an important one.

Consider an instance of the `ArrayList` class. This object can be referenced both via a `MutableList` interface and via a read-only `List` interface. Thus, any pieces of code that holds a reference via a read-only `List` type cannot be sure that it will not be mutated at the next moment through some other reference of a mutable type. It simply cannot happen with immutable values. An object of an immutable value class cannot be mutated. An immutable collection cannot meaningfully implement `MutableList` interface. A code holding an instance reference via an `ImmutableList` interface type cannot see it change.

This potential 3rd-party mutation of a read-only `List` is usually not a concern in simple single-threaded synchronous code. A code that creates a mutable `ArrayList` instance passes it around into other functions via a read-only `List` interface and does not try to modify it while other functions use it. Yet, as code become more complex, collection references get stored somewhere, shared, passed around, it becomes important to carefully follow the practice of defensive copying to avoid sharing/storing a mutable collection that will be later modified. Immutable collections, just like all immutable data, free developers from all these defensive copying concerns.

We cannot reuse the read-only `List` type from the Kotlin standard library in the meaning of an immutable list, we cannot declare `List` as a value interface, since `List` interface can be and is implemented by mutable, non-value classes. We’ll have to introduce a separate set of interfaces, like `value interface ImmutableList<T>`, to be implemented by immutable value classes for the purpose of their use in immutable data structures. This explosion of collection interfaces (`List`, `MutableList`, `ImmutableList`) with a lot of duplication in the utility functions that are available for those interfaces, can be addressed by introducing a separate language feature to perform mutability-projection of types. The `MutableList` interface can become a _mutable projection_ of a `List` interface, written as `mutable List` (here `mutable` becomes a modifier for a type, not a part of its name anymore), while `ImmutableList` can likewise become an _immutable projection_ of a `List` interface, written as `immutable List`. All the methods and functions declared on a `List` will be marked with appropriate modifiers, allowing compiler to figure out which mutability type projection gives access to which functions. However, further elaboration of this potential scheme is outside the scope of this document, since these kinds of tagged projections are useful outside the world of mutability/immutability and should be undertaken in separate design effort.

> It is also worth noting, that there is a lot of existing research into this kind of type-lattices with mutable, read-only, and immutable types, including a number of research and experimental languages supporting them. Some of those systems provide means that allow creation of mutable instances in the limited scope of construction and, somehow ensuring those instance did not escape the construction code via a mutable type, upgrade those instances to an immutable type without having to copy them. We might be able to adapt some of that research for Kotlin, too, further narrowing the gap between mutable and immutable data types, but that’s a separate topic. Here we focus on enhancing the story of immutable value types in Kotlin.

### Mutating functions returning a value

There are compelling use-cases to support mutating functions that return a value in addition to modifying their receiver. In particular, there are operations on collections that will benefit from such a feature. For example, it is helpful for a `remove` operation on persistent (immutable) set to return a Boolean value indicating if the element was present (and likewise for `add`):

```kotlin
mutating fun <T> ImmutableList<T>.remove(value: T): Boolean
```

This makes an immutable-collection API that is based on mutating functions more efficient and more pleasant to use than “wither style” functions that are chained with dot-call. You can naturally use and update immutable collections that are stored as fields in value classes:

```kotlin
value class State(
    var tags: ImmutableList<String>, // mutable property
    var madePendingAt: Instant?,
    // ...
)

mutating fun State.makePending() {
    if (tags.add("pending")) {
        madePendingAt = now()
    }
}
```

Support for writing this kind of business logic in a direct imperative style, provides a seamless transition for developers from working with mutable data structures to working with immutable ones.

> You can see clear parallels here with how Kotlin coroutines, that enabled writing asynchronous code in direct imperative style, lowered entry barrier into otherwise challenging field.

It will not be possible to efficiently compile mutating functions returning a value on a pre-Valhalla JVM. Behind the scenes the corresponding JVM function will have to return a pair with its result and an updated receiver value. However, this pair will be a temporary object that does not escape outside the caller context, so we can expect that the existing JVM escape analysis will perform quite well to optimize the corresponding code and eliminate temporary allocations.

### Mutating operators

Kotlin has a number of [conventions for operator overloading](https://kotlinlang.org/docs/reference/operator-overloading.html)
and they need to take into account immutable value classes.

**Unary prefix operators** (`unaryPlus`, `unaryMinus`, not), 
**increment and decrement operators** (`inc`, `dec`) , 
**binary arithmetic operators** (`plus`, `minus`, `times`, `div`, `rem`, `rangeTo`), 
**in operator** (`in`), are already designed to work on immutable values and don’t need any special treatment.

**Augmented assignments** (`plusAssign`, etc) mutate their receiver, but are supported via the corresponding binary operations and work for immutable values via them, so they need no special treatment either.

**Indexed access operator** (`get`) does not perform any mutation, but **indexed assignment operator** (`set`) will have to be tweaked to support ergonomic operation on immutable collections. A mutating indexed assignment operator can be supported:

```kotlin
mutating operator fun ImmutableList<T>.set(index: Int, value: T)
```

With this operator, modifying even deeply nested immutable data structures with collections becomes easier:

```kotlin
order.delivery.addressLines[0] = updatedFirstLine
```

Delegate convention (`by`) will need to be likewise tweaked to support value-mutating setters (“withers”).

### Mutating function types and lambdas

To make a language feature complete, every new kind of function shall have the corresponding functional type, so that any piece of code can be property extracted into a lambda and repeating pieces of boilerplate code can be refactored into higher-order functions. This motivates support for mutating functional type 
`mutating R.(P1, P2, ... ) -> T`.

Mutating functional types allow extraction of common logic over value classes. For example, consider the following code:

```kotlin
mutating fun State.addTag(tag: String) {
    tags += tag
    // ... some other business-driven updates here
    lastUpdate = now()    
}
```

When updating `Status` class like this we might want to ensure that its `lastUpdate` is set to `now()` only when the state was actually changed:

```kotlin
mutating fun State.addTag(tag: String) {
    val prevState = this
    tags += tag
    // ... some other business-driven updates here
    if (this != prevState) lastUpdate = now()
}
```

If this logic is needed in different functions that mutate `State`, it can be extracted to a higher-order function with the help of mutating functional type:


```kotlin
inline mutating fun State.update(block: mutating State.() -> Unit) {
    val prevState = this
    block()
    if (this != prevState) lastUpdate = now()    
}
```

Which is used like this:

```kotlin
mutating fun State.addTag(tag: String) = update {
    tags += tag
    // ... some other business-driven updates here
}
```

### Mutating scope functions

A mutating function does not explicitly return a new value, so, just like functions on a mutable class they cannot be directly chained. If you perform a sequence of mutations on some mutable `val state: MutableState`, or on some immutable `var state: State`, you could write them in imperative do-style:

```kotlin
state.doFirst()
state.doSecond()
state.doThird()
val outcome = state.getResult()
```

When `state` is mutable we can use Kotlin scope function run to avoid repetition of the common `state.` prefix:

```kotlin
val outcome = state.run { // this = state here
    doFirst()
    doSecond()
    doThird()
    getResult()
}
```

However, for a value class `State` this code will not compile. Inside the `run { ... }` block the reference to `this` is not modifiable and mutating functions cannot be called on it. With the help of mutating functional types we can define a dedicated scope function `runMutating` for this case in the standard library:

```kotlin
mutating inline fun <T : AnyValue, R> T.runMutating(block: mutating T.() -> R): R {
    return block() // mutates this and returns block's result
}
```

Now, changing `state.run { ... }` to `state.runMutating { ... }` in the above code will make it work as expected for an immutable value class `State`. The downside of this approach is that developer will have to take an extra effort to use immutable classes and various standard library functions will have to come in separate mutating versions.

As you can see, the actual code of this `runMutating` function is the same as its run cousin, with the only difference that it is restricted to work on `AnyValue` and has a `mutating` modifier both for itself and for its lambda. We can use a different approach. We can allow marking `inline` generic functions with `mutating` modifier even when they are defined as extensions on an arbitrary type `T`, which is not necessarily a value class:

```kotlin
mutating inline fun <T, R> T.run(block: mutating T.() -> R): R {
    return block() // can mutate this if T is a value class
}
```

> This is roughly analogous with how inline functions can be implicitly used in suspending context.

The meaning of this `mutating` function and the corresponding functional type of its parameter is that it is allowed to mutate its receiver when `T` is a value class (and thus can have mutating functions) and is not allowed to mutate it when `T` is a regular (non-value) class. So, in a sense, it is conditionally mutating depending on valueness of its receiver. With this feature all the appropriate scope functions from the standard library will simply be marked as `mutating` and will work in a familiar way without having to invent new names for them.

In particular, this would also allow us to retrofit `apply` function so that it serves a double-duty of chaining mutations. Let’s modify `apply` function from the standard library so that it can have mutating operations in its body and will return an updated version of the value, without being mutating function itself. Unlike `run`, we’ll have to change the body of its code a bit, too (but it will not affect its semantics for regular mutable classes):


```kotlin
// Note that it is not mutating itself, it returns mutated result instead
inline fun <T> T.apply(block: mutating T.() -> Unit): T {
    var receiver = this // copy receiver into a variable
    receiver.block() // call potentially mutating block on the receiver
    return receiver // return updated receiver
}
```

Now consider, for example, a `states: Flow<State>`. If we have `State.updatedNow(): State` function written in a “wither style” that modifies a state and returns a new state, then we can use `.map { ... }` operator on it to get a flow of updated states:

```kotlin
val updatedStates = states.map { it.updatedNow() }
```

When we start modelling our domain using mutating functions we no longer define `updatedNow()` function, we have mutating `updateNow()` function instead (note how the verb in the name changed its form). So we cannot just call `it.updateNow()`, since it is not mutable inside the map. Here is where the mutation-supporting version of `apply` can be used to adapt the world of mutating functions to the world of “functions that expect a new value to be returned from a wither-style function” (like `map`) so that we can use mutating functions with them:

```kotlin
val updatedStates = states.map { it.apply { updateNow() } }
```

However, if this combination becomes popular, we might consider providing a specially-named version of `map` to capture this pattern. In the end, we can straighten the road from mutable classes to immutable classes only so far. There will be some rough edges between the chaining code style of `a.withFirst().withSecond()` and an imperative code style of `a.doFirst(); a.doSecond()`.

> Note, that updateNow is still a function without side effects, even though its call is written in imperative style. `updateNow()` returns an updated copy of the `State` and the combination of `apply { updateNow() }` makes its result explicit, so that it can be used with `map { ... }` operator.

## Legacy and migration

While the goal of first-class support for immutable value classes is noble, the challenge is that there is a lot of existing code that either uses mutable classes or is based on immutable classes that are written and are used in chaining code style, with functions that are declared to return updated values.

### Migrating built-in primitive types to value classes

As noted in the introduction, primitive built-in classes like `Int`, `Long`, `Double`, etc from the Kotlin standard library are already value-like and don’t support identity. We could declare them as value classes in the standard library. However, the future of value classes calls for special treatment of mutable `var` properties and there could be some code that already defines extension var properties for primitive built-in classes, e.g. the following code is allowed in Kotlin now:

```kotlin
var Int.extension
    get() { /* do something */ }
    set(value: Int) { /* do something else */ }
```

We can argue that this code is unquestionably bad style. It is conceptually unreasonable to have this mutable property on an immutable class, since it cannot mutate the instance it is being called on anyway. However, this code still could be there, so we cannot just break it. We can only deprecate such code and gradually make it illegal according to 
[the principles of Kotlin Evolution](https://kotlinlang.org/docs/reference/evolution/kotlin-evolution.html).

This calls for some kind of `@DeprecatedVarProperties` annotation that one can put on such a class (either before or after turning it into a value class) to give an advance warning to future breaking of such code. It can also have an optional `DeprecationLevel` (`WARNING` or `ERROR`). Only after such legacy `var` properties were given enough time to be gone from the Kotlin code bases, we can change the meaning of `var` properties for them to mutating properties.

However, we don’t have to wait for this migration cycle to complete before we start introducing new mutating properties for the old value classes. The only syntactic inconvenience is that we have to be explicitly marking them with `mutating` modifier in the source code. For example, with migration of `Int` class to become a `value class`, its `Int.sign` extension can be upgraded from readonly (`val`) property to mutating (`var`) property:

```kotlin
mutating var Int.sign: Int // has to be explicitly marked mutating due to legacy
    get() = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
    set(value: Int) = when {
        value > 0 && this < 0 || value < 0 && this > 0 -> this = -this
        value == 0 && this != 0 -> this = 0
    }
```

Now, it would become possible not only to read `x.sign` of an integer, but also update it with a statement like `x.sign = desiredSign`.

### Strings and other value-based classes

Kotlin standard library contains a lot of other immutable classes: `String`, `Regex`, `Pair`, etc. All these immutable classes conceptually represent values and would benefit from being explicitly declared as such, as value classes, too. However, we cannot declare them as being value classes right away, and there is one more reason, beyond mutating properties that were discussed in the previous section.

These immutable classes, like `String`, don’t protect identities of their instances in any way. Conceivably, there could be code that somehow depends on it. We would need to introduce `@DeprecatedIdentity` annotation for the purpose of migrating them to value classes.

Why is this important? Getting rid of identity enables optimizations. Take a `String` class, for example. Even if JVM is not yet planning to turn string instances into JVM primitive classes, Kotlin/Native might benefit from being able to get rid of string identities. For example, short strings can be optimized and packed into values, which could be beneficial for some applications.

The same is true for all other immutable classes. Because these classes are immutable, it really makes no sense to maintain their identity. The identity is only conceptually important for mutable classes.

Unfortunately, getting rid of identity is not an easy process with respect to the legacy code that might depend on it. Once a value type like `String` gets upcast to a reference type like `Any`, it acquires some identity. In Kotlin today, this identity is stable. If the same instance of a string `“A”` gets upcast to `Any` multiple times, the result is the reference to the same object with the same identity, which we can confirm using a reference equality operator ([playground](https://pl.kotl.in/7Caszi_y-)):

```kotlin
fun main() {
    val a = "A" // a string
    val b: Any = a // upcast to Any
    val c: Any = a // upcast to Any again
    println(b === c) // true result is guaranteed now
}
```

If, for example, Kotlin/Native starts optimizing short strings as values and strips them of stable identity, then the above code might start producing `false` result of comparison. However, there is no way we can detect this kind of code and start warning that its behavior will be changing in the future, since this code performs an allowed reference comparison of two instances of type `Any` and the actual comparison can be buried deeply in the library code (as in the case of `IdentityHashMap`).

There are two approaches to this breaking change. One is to just make it in a major Kotlin version and live with it, while trying a combination of heuristics to identify and warn on as much potentially broken code as possible in advance. We can start marking all identity-dependent types (like `IdentityHashMap`) and functions with a special annotation and warn on all attempts to pass a `@DeprecatedIdentity` classes to them. Given relatively rare use on identity-sensitive operations in the general Kotlin code, this approach could be fruitful in cleaning up Kotlin code of potentially-broken-in-future identity-sensitive operations without producing too much false negative noise.

The other is to use the approach of JVM Project Valhalla to reference comparisons. Valhalla plans to implement reference comparisons between JVM primitives by comparing the underlying values. This way, the actual identity of the boxed value classes will never be visible to the user code. With Valhalla, JVM will be free to optimize allocations and copies of its primitive (value) classes without affecting runtime semantics of any kind of user code. However, the Valhalla approach still does not support non-breaking migration of plain immutable classes to Valhalla primitive (value) classes. The nature of a breaking change is different, though. Modifying a bit the code that was shown in the introduction, this code today sees two strings with the same value but having different identity ([playground](https://pl.kotl.in/sQkVU6vy2)):

```kotlin
fun main() {
    val a = "Kotlin"
    val b: Any = a.filter { it != ' ' } // Still "Kotlin"
    val c: Any = a // reference to a
    println(b == c) // true -- same value
    println(b === c) // false -- different instance
}
```

If `String` is turned into a `value class` and runtime system strips away its identity (as we can do in Kotlin/Native without waiting for Valhalla), then Valhalla-like approach to a reference comparison operator (`===`) will change the behavior of this code to return `true`.

So, it seems that turning existing immutable classes into value classes to enable their performance optimization will necessitate acceptance of some form of a breaking change in behavior for the existing code that relies on the identity of those existing immutable classes. Don’t write this kind of code.

### Augmented mutating assignment operator

Without 1st-class support for mutating functions, working with immutable data requires some boilerplate code. Today, a typical mutation operation on an immutable data structure, like `copy()` function in a data class, is declared to return an updated version of the data. Lots of operations on collections and other values are declared like that. Some of them, like arithmetic operators (`plus`, `minus`, etc), enjoy the benefits of Kotlin operator overloading. For example `BigInteger` instances (which are immutable) can be conveniently updated like this:

```kotlin
myBigValue += increment // DRY shortcut to myBigValue = myBigValue + increment
```

However, when a function on an immutable value does not have a corresponding operator, it can be only used for mutation in a verbose and repetitive way, like this:

```kotlin
myBigValue = myBigValue.and(mask)
```

Repetitiveness in such code can be reduced by introducing a special context-specific call operator that would be available only in certain context (here - in assignment context) and would allow dropping repeated mentioning of the receiver:

```kotlin
myBigValue = .and(mask)
```

> See [KT-44585](https://youtrack.jetbrains.com/issue/KT-44585) Augmented updating assignment operator (call context convention).

> This syntax is also consistent with [KT-21661](https://youtrack.jetbrains.com/issue/KT-21661) Allow calling Boolean functions and properties in 'when' branches syntax that likewise proposes syntactic shorting from full form of `when { x.name() -> ... }` to a shorter version of `when(x) { .name() -> ... }`.

### Mutating functions naming convention

Kotlin coding conventions have the following advice on 
[choosing good names](https://kotlinlang.org/docs/reference/coding-conventions.html#choosing-good-names):

> The name of a method is usually a verb or a verb phrase saying what the method does: `close`, `readPersons`. The name should also suggest if the method is mutating the object or returning a new one. For instance `sort` is sorting a collection in place, while `sorted` is returning a sorted copy of the collection.

Kotlin standard library generally follows this naming convention that allows to distinguish functions mutating their receiver and functions returning updated version of the data. Sometimes it is achieved by putting verbs in different form (`sort`/`sorted`), sometimes different verbs are used, like `filter` (for read-only collections) and `retainAll` (for mutable collections).

This makes it easy to figure a naming convention for immutable collections. We can take functions for mutable collections and declare the corresponding mutating functions with the same name on immutable collections, making learning curve for switching from mutable to immutable collections as straightforward as possible. A `collection.sort()` call would do conceptually the same thing both for mutable and for immutable collections.

However, this naming convention is not consistent. Fully-immutable classes, like `String`, that could not have any kind of mutating methods now, do not usually follow it. For example, `String.replace(...)` uses quite an imperative verb in its name, but it does not update the string it is called on. Instead, it is returning a copy of the string with replacement. If we were designing the `String` class from scratch, we might have called the corresponding function `replaced()`, reserving the name `replace()` for a mutating function that updates its receiver. However, this ship has sailed. Changing these names would be too big of a breakage for Kotlin ecosystem to swallow. We’ll have to live with it and find some other approach to naming helpful mutating function in those cases.

There’s always a backup plan of not introducing mutating functions for the legacy immutable classes at all, but to rely on mutation operator from the previous section. Instead of repetitive `myString = myString.replace('-', '_')` we can write `myString = .replace('-', '_')` to avoid repetition, thus alleviating the need for mutating functions.

### Shall class immutability be the default?

In light of addition of the `value class` concept we run into the conundrum of picking the defaults in the language. Originally, Kotlin was designed with a slight tint into immutability where it matters:

* `val` and `var` keywords in Kotlin are equally short and conceptually close, too, which makes it easy for developers to express intent on not modifying the values they declare in their functions, but does not put a penalty on having to work with mutable variable in all kinds of contexts, since a substantial fraction of modern industry-scale code (of the kind the Kotlin is designed to support well) is written around mutable data structures.
* For collections, for example, `MutableList`, is longer and more explicit, by design, than a read-only `List`, since typical code usually passes read-only collections around.

However, times change. As distributed backends, asynchronous code, and reactive UIs become widespread, modelling user-defined business-related data-structures with immutable value classes becomes more popular. We often find ourselves in a situation where we’d rather see a short name, for example, a `User`, referring to an immutable class that we can safely pass around without risk of it being accidentally mutated by mistake. Support for value classes gives us that, while support for mutating functions and mutable properties on them still allows convenient updates of those values in the isolated pieces of code that need to do it, without any concessions in convenience compared to mutable classes.

However, declaring those immutable classes is going to require extra thought. By default, the `class` keyword means “a potentially mutable thing with identity”, while opting out of identity with the `value class` requires an extra effort from a developer. Is this the right choice? It is not clear without an extra research. If we see evidence that immutable value classes are increasingly dominating in the design of real-life Kotlin code, then we can gradually flip the default by asking developers to add some an `identity` modifier before regular classes. Existing `data class` could be implicitly treated as `identity class`, easing this migration process. This explicitness in declaring an identity-capable class would open a road to flipping the default and making `value` modifier optional for immutable classes in the far away future. Anyway, right now it is all too early to speculate about.

## Value classes and arrays

Interaction of arrays and value classes in Kotlin is a complicated one. It has its roots in the way arrays work on JVM and the need for Kotlin to interoperate with Java ecosystem. For a value type Int Kotlin has two arrays types.

* `Array<Int>` maps to Java type `Integer[]` and stores references to boxes (not an efficient memory representation).
* `IntArray` maps to Java type `int[]` and stores primitive integers without boxing (efficient memory representation).

From a standpoint of the Kotlin developer, the way API looks and what kind of values can be stored in those arrays, they are essentially the same type. It is always more efficient to use `IntArray` than `Array<Int>`. However, because of the way reification of types and JVM interoperability in Kotlin, the following code produces
non-efficient `Array<Int>`:

```kotlin
val a = listOf(1, 2, 3).toTypedArray() // Array<Int>
```

There is no straightforward way on JVM to have a generic method that produces an efficient array type (`int[]`) for primitives. These array types on primitives are not assignment-compatible to arrays of reference types (`Object[]`).

In order to get an efficient `IntArray`, one has to explicitly use a special function `toIntArray()`. These functions are specialized for all thee 8 primitive array types in JVM: `BooleanArray`, `CharArray`, `ByteArray`, `ShortArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray` using a source-code-generator as a part of the standard library build process. Moreover, since these arrays are all mutually incompatible in JVM method signatures, all the extensions for various operations on those arrays have to be generated in 8 copies &mdash; one for each of those arrays, in addition to a generic version that works for any `Array<T>` (the one that inefficiently stores JVM primitive types).

### Arrays of inline value classes

For a user-defined inline value class like `@JvmInline value class Color(val rgb: Int)` the `Array<Color>` type on JVM can only store boxes, since it has to be compatible with all the generic functions that are defined as working on the generic `Array<T>` type.

For an efficient storage of arrays of an inline value class `Color` on JVM an underlying type of `int[]` shall be used. However, it cannot be operated upon by generic JVM methods. One can define a `@JvmInline value class ColorArray(private val a: IntArray)` and implement various operation for it.

That is the actual approach that is currently taken by the standard library to support four unsigned integers `UByte`, `UShort`, `UInt`, and `ULong` that are implemented as inline value classes. Their corresponding array types are source-code-generated in the standard library. Moreover, the Kotlin compiler comes with a hard-coded mapping between the corresponding unsigned types and their array types for use with varargs, so that `foo(vararg a: UByte)` actually uses `UByteArray` as the type of `a` value in the function code, just like `foo(vavarg a: Int)` is hard-coded to map to an efficient `IntArray`.

Obviously, this approach to arrays of value classes does not scale to user-defined types. Even support for the unsigned integer types this way is already a stretch, since it expands the number of additional function copies that the standard library has to have for various functions that work on arrays. So, for now, arrays of unsigned integer types will be experimental until a better way to support them is implemented.

> The issue for varargs of inline value classes is [KT-33565](https://youtrack.jetbrains.com/issue/KT-33565).

### Valhalla arrays

Project Valhalla promises to unify arrays of JVM primitives (both built-in and user-defined ones) so that they can be used in a generic fashion by generic code. However, at the time of writing, the plan on how exactly this will be achieved and, more importantly, what exactly will happen to legacy `int[]` types with respect to their unification with Valhalla arrays of primitives in not clear. The other challenge is that the timeline for Valhalla is not clear either, but the problem with arrays needs to be solved for value classes in Kotlin regardless of Valhalla.

### Reified value arrays

A potential solution is to rethink the approach of arrays of values in Kotlin. Instead of separate, unrelated, primitive array types like `IntArray`, `LongArray`, etc we can introduce of a new unified type for storing arrays of values `VArray<T>`. The goal of this type is to efficiently store values (unlike `Array<T>` that uses inefficient storage for values). This type will have the following key constraint: `T` can only refer to a **reified type**. It means you can only use it with a concrete type like `VArray<Int>` or in the context of an `inline` function with a `reified` type parameter. This is quite a serious constraint, meaning that you cannot use `VArray<T>` as a field in a class, yet it still solves many important use-cases.

> The issue for reified value arrays is [KT-44654](https://youtrack.jetbrains.com/issue/KT-44654).

During compilation on JVM `VArray<Int>` gets represented as `int[]`, `VArray<Long>` as `long[]`, etc. Moreover, value arrays of user-defined inline value classes get represented as primitive arrays of the corresponding carrier types, so `VArray<Color`> compiles to `int[]`. For reference types `VArray<T>` gets mapped to `Array<T>`.

Primitive array types in stdlib can be declared as type-aliases to the corresponding value array types, so we have
`typealias IntArray = VArray<Int>`, etc. So, in the core language, the number of distinct array types will be down from 9 (or 13 if you count unsigned integers) to just two: `Array<T>` (storing references) and `VArray<T>` (storing values).

Kotlin compiler will specialize inline functions that use `VArray<T>` to the corresponding primitive arrays.
It means that various array-manipulating functions can be defined once and then will get properly specialized for all primitive array types, including arrays on user-defined inline value classes. For example, a function like `filterTo`, which is now source-code-generated in the standard library for all the primitive array types, can become generic on its type parameter `T` and be declared for all `VArray<T>`:

```kotlin
inline fun <reified T, C : MutableCollection<in T>> VArray<T>.filterTo(
    destination: C, predicate: (T) -> Boolean
): C {
    for (e in this) if (predicate(e)) destination.add(e)
    return destination
}
```

Another example would be a generic `maxOrNull` function that works for any array of comparable types:

```kotlin
public inline fun <reified T : Comparable<T>> VArray<T>.maxOrNull(): T? {
    if (isEmpty()) return null
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (max < e) max = e
    }
    return max
}

```

Some functions might need to return new instances of `VArray<T>`. This would be enabled via a `VArray<T>(size) { /* init */ }` constructor function which would also require a reified type parameter and so will be specialized by the Kotlin compiler during inlining of the corresponding function.

The introduction of `VArray<T>` also solves the issue of efficient support for varargs of user-defined inline value classes. The function with `vararg colors: Color` parameter will get `colors` of the type `VArray<Color>` that gets compiled on JVM as `int[]`. At the same time, a generic function with non-reified type parameter `T` and `vararg a: T` will continue to be based on `Array<T>`.

### Boxed value arrays

There will be cases when value arrays need to be boxed, just like it happens with all value classes. For example, in the following code:

```kotlin
val a = VArray<Color>(n) { /* init */ }
val b: Any = a // upcast to Any
```

Fortunately, Array types on JVM (and in Kotlin) do not define any value-based `toString` nor value comparison semantics, so we can simply erase them to their generic carriers when they escape to a generic/reference-based context. In this case, we can store a reference to the underlying `int[]` into the property `b`. The downside of this strategy is that we will not be able to implement precise type checks on those references (e.g. `b is UIntArray` will paradoxically return `true`), but given isolated array use-cases this does not seem to be a big issue in practice.

> This strategy will not allow to meaningfully implement `contentDeepToString()` function on arrays. An alternative strategy based on boxing `VArray` when it escapes into generic context (just like it now happens with experimental arrays of unsigned integers) can be considered, too.

### Unifying arrays of references and values

The Kotlin types of `Array<T>` and `VArray<T>` can be further unified. The difference between them is that the former always stores a reference to the value, while the latter uses the most efficient value storage option. Nullable versions of Kotlin value types are already reference-based, so `Array<Int?>` and `VArray<Int?>` will get mapped to the same `Integer[]` type on JVM anyway. We can introduce a reference-forcing type operator that will have a similar effect of `T?`, but without an additional burden of nullity. Let’s call it `Ref<T>`. This way, `Array<T>` can become a typealias to `VArray<Ref<T>>`.

With this change, most of the generic array operations that we needed to define twice (for `Array<T>`  and for `VArray<T>`) will have to be defined just once.

The last block on this road that we’ll have to overcome is to ensure a proper default for the future users of Kotlin. It is quite obvious that `Array<T>` is aptly named, but is unfortunately an inefficiently implemented type. Ideally, it should be deprecated and replaced with `VArray<T>` in all the code in the future. However, it is not clear if we can reclaim the name of the `Array<T>` type to retrofit it in the meaning of `VArray<T>`. Anyway, this is not very critical, since it is quite a low-level type that should not be used a lot in application code.

### Efficient generic collections

The remaining issue with arrays is supporting them in generic context in a pre-Valhalla JVM. Consider a user-defined generic collection class that uses arrays internally:

```kotlin
class MyCollection<T> {
    private var a: VArray<T>
}
```

Because `T` is non-reified, this will not be directly possible, and the implementation will be forced to use the legacy `Array<T>` (or `VArray<Ref<T>>`), which maps to `Object[]` on JVM and inefficiently stores references to boxed values.

A conceivable solution is to support reified type parameters for classes to make it work. This can be implemented by storing a reference to a special type token inside a hidden field of the class with a reified type parameter. The same technique will allow usage of `VArray<T>` in such classes with reified type parameters.

This type token will provide access to virtual strategies for retrieving the corresponding values from the underlying arrays as needed. Since `int[]`, `long[]` and other primitive array types on JVM can be only unified under an `Object` type, this type token will have to provide accessors like `getValue(array: Object, index: Object): Object` that retrieve the value from the primitive array at the corresponding index and will have to return a box of the corresponding value type. Whether this code will work efficiently in the end (after compilation by JVM) remains to be seen, but the early experiments we did in this direction were not very encouraging performance-wise. It looks like this technique can be used to bring down storage requirements for generic collections that store value types, but the performance of various data manipulation algorithms on those collections can actually suffer without further efforts to optimize it.

Another complication with generic collections of value types is that their implementations often need to pre-allocate arrays of larger size without having actual values to put into the arrays. This necessitates the design of some kind of C#-style `default` values for all value types. Alternatively, instead of support for default values in all contexts, the default values can be supported only in the narrow context of allocating and cleaning up array elements, without giving any guarantee on their actual value representation.

We will not further delve into the details in this document. All in all, full support for efficient generic collections of value types will take a lot of design and implementation effort on today’s JVMs and it may turn out that Project Valhalla will be able to deliver a solution to this problem faster. Time will show.

## Name-based construction of classes

Kotlin's data classes have positional constructors and positional destructuring. They are great for small classes (pairs, key/values, index+value, etc) but don’t scale to more complex domain entities that you’d find in any enterprise software, even in the Kotlin compiler itself. Positional destructuring is easy to get rid of (one can just stop using data modifier and design some alternative approach to `hashCode`/`equals`/`toString` generation), but the positional constructors are part of “primary constructor” feature for any class, including a value class. Support for named parameters partially alleviates this. However, while named parameters are great for functions with a few to a dozen parameters, they are not very flexible, and they don’t scale well to many parameters that you’d find in a typical business entity.

### An example with a mutable class

There is a workaround that is currently available for mutable classes. For a concrete example, consider this mutable class, the kind of which might be found in the depths of a big enterprise:

```kotlin
class User(
    var id: Int,
    var name: String,
    // ... dozen other properties, some with defaults
)
```

Because this class has all its properties in its primary constructor you can construct it while specifying its properties by name: `User(id = 123, name = "foo")`. However, this approach suffers from the following problems:

* **ABI Evolution Problem:** You’ve now committed the order of properties to your ABI. For big class the order of properties did not actually matter for you when you designed your API, but you still have to fix them somehow and maintain this order from now on and forever.

> See a detailed explanation in [Public API challenges in Kotlin](https://jakewharton.com/public-api-challenges-in-kotlin/) by Jake Wharton.

* **Flexible Init Problem:** When initializing complex entities you cannot put complex logic that ties together values of multiple properties together like this
  `if (something) { name = "foo"; type = Regular } else { ... }`
  Instead, when calling such a constructor, you always have to provide values for all properties one by one.

When class is mutable, you can write a class without a primary constructor and with a builder function to overcome both the ABI evolution and the flexible init problems:

```kotlin
class User {
    var id: Int = 0
    var name: String = ""
    // ...
}

inline fun User(builder: User.() -> Unit): User = User().apply(builder)
```

> See also [AutoDSL Project](https://github.com/juanchosaravia/autodsl) that automates writing this kind of builders.

Now you can write `User { id = 123; name = "foo" }` to construct a class having all the flexibility in how its properties are initialized.

This approach also works for immutable value classes that support mutable (`var`) properties if mutating functional type is used for the builder, but it would stretch their implementation strategy. Business entities can have lots of fields and can get initialized from a different module. Altogether it will produce code with many temporary allocations that will be harder to eliminate after the fact.

Additional downsides of this approach are:

* You have to add default values to all the properties and thus you cannot have “required” properties, like you could have them with a regular constructor.
* You have to write boilerplate for DSL builder function for every class or use a plugin to generate them for you.

### Uninitialized properties

The idea is to solve these problems at the language level by adding support for _uninitialized properties_. This would allow writing just:

```kotlin
class User {
    var id: Int // uninitialized (required) property
    var name: String
    // ... other properties, some may have defaults
}
```

> These uninitialized properties are similar to C# `initonly` properties, but they don’t need a special modifier due to fortunate peculiarities of the original Kotlin design. However, for `expect class` this is not so straightforward and some kind of C#-style `initonly`/`uninitialized` modifier will have to be introduced for those cases.

Compiler provides a synthetic DSL builder function automatically:

```kotlin
inline fun User(builder: (uninitialized User).() -> Unit): User = /* MAGIC */
```

The object in the receiver position of this function is marked by the special `uninitialized` modifier.

> This `uninitialized` modifier does not need to be available to user-defined functions from day one, but there is an interesting use-case to make it available for any type, even for an interface with `val`/`var` properties, where an implementation of this interface is created by proxy and during the initialization of its instance inside DSL builder code it would be helpful to get compiler validation that all properties are initialized.

Now one can write `User { id = 123; name = "foo" }` for creation of such a class, which will work in the following way:

* During code generation, all the user-declared constructors gain additional hidden parameter for uninitialized properties (see “ABI strategy details” section).
* Call to the compiler-provided DSL builder function results in capturing constructor parameters to local variables, without calling the actual constructor.
* Uninitialized fields are kept separately while builder block executes.
* As soon as all uninitialized fields are definitely assigned (DA), the actual constructor is called.
* `this` inside the DSL builder function becomes available only after all the uninitialized fields are DA.

> The last two points look fragile, but they are needed to better support a mix of uninitialized and other kind of properties (either initialized or properties with custom setters, that can be only called on a constructed instance). A simpler version of this approach will only support assignment to uninitialized properties inside the block and will always construct the instance at the end of the block.

### DSL-style initialization blocks for mutable classes

A regular mutable class (without uninitialized properties, all its fields having a default) will benefit from automatically getting a synthetic DSL builder function, too. Because it has no uninitialized fields, all of them are immediately DA, so constructor is called immediately. Basically, it works exactly as if user had defined the corresponding builder:

```kotlin
inline fun User(builder: User.() -> Unit): User = User().apply(builder)
```

> The compiler-generated builder function shall have a lower priority in case there is a user-defined one for backwards compatibility.

### Flexible initialization for readonly properties

Read only (`val`) fields can be initialized this way, too. This is not currently possible with user-defined one-liner at all and this provides a major improvement in usability for immutable classes with read only fields:

```kotlin
class User { // immutable
    val id: Int // uninitialized val
    val name: String
    // ...
}

// creation
User {
    id = 123
    name = "foo"
    // ...
}
```

> Not having to add `initonly` to every such property (like in C#) is important to lowering barrier for immutable classes and making them 1st-class things, providing smoother transition from classes with mutable fields to classes with immutable fields and to value classes with mutable properties.

### Flexible initialization for mutable properties of value classes

With support for uninitialized fields, an immutable value class that defines mutable (`var`) properties is liberated from the burden of having to always specify them in the constructor or provide them with a default when they are defined in the body. A value class will be able to have uninitialized mutable properties and could be declared without committing to a specific constructor order of its properties.

```kotlin
value class User { // immutable value class
    var id: Int // uninitialized mutable property
    var name: String
    // ...
}

// creation
User {
    id = 123
    name = "foo"
    // ...
}
```

### ABI strategy for uninitialized properties

To solve the ABI evolution problem the following backwards-compatible compilation strategy can be used:

* If (and only if) a class has uninitialized properties, then automatically generate a synthetic `xxx.Builder` class that has all the uninitialized properties of this class as simple mutable fields and an additional parameter with an instance of this builder class to ABI of all the constructors.
* The DSL-builder block assigns all the values to the corresponding properties and, as soon as they all DA, calls the constructor of the original class.

For example:

```kotlin
[value] class User(val id: Int) { // required constructor param
val name: String // uninitialized property
}

val user = User(123) { name = "foo" }
```

Gets desugared to:

```kotlin
[value] class User(val id: Int, builder: User.Builder) {
    val name: String = builder.name // initialized from the builder

    class Builder { // synthetic, not accessible directly from the source 
        val name: String // platform-specific uninitialized field
    }
}

val user = run {
    __id = 123 // capture the original constructor param
    __builder = User.Builder() // create builder
    __builder.name = "foo" // assign to builder fields
    User(__id, __builder) // create instance when all props are DA
}
```

> The issue for support of uninitialized properties with automatic builder generation for them is [KT-44655](https://youtrack.jetbrains.com/issue/KT-44655). 

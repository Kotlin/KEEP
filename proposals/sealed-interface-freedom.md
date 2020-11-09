# Sealed interfaces and sealed classes freedom

* **Type**: Design proposal
* **Author**: Roman Elizarov
* **Status**: Under consideration

## Introduction and motivation

Kotlin has the concept of a `sealed class` since its first version. Initially, it was only allowed to write inheriting
classes inside a `sealed class` that it was somewhat relaxed in Kotlin 1.1 allowing to write them on the top-level, too
(see [KT-11573](https://youtrack.jetbrains.com/issue/KT-11573) and [Sealed class inheritance](sealed-class-inheritance.md) KEEP).

A number of use-cases has surfaced to allow for even more freedom in sealed classes hierarchies
(see [KT-13495](https://youtrack.jetbrains.com/issue/KT-13495)) both in the same file and ability to split 
large sealed class hierarchies into several files to avoid very big source files. 

A need to be able to define `sealed interface` has repeatedly showed up during the design of various APIs
(see [KT-20423](https://youtrack.jetbrains.com/issue/KT-20423) and [KT-22286](https://youtrack.jetbrains.com/issue/KT-22286)).

These issues have been augmented by the Java language and JVM which are gaining first-class support of 
sealed classes and sealed interfaces &mdash; experimentally since JDK 15 (see [JEP 360](https://openjdk.java.net/jeps/360)), 
tentatively planning to become stable in JDK 16 or later. In order to properly interoperate with the corresponding
Java language features, Kotlin needs to introduce the matching concepts
(the corresponding issue is [KT-42433](https://youtrack.jetbrains.com/issue/KT-42433)).

The goals of this proposal are:

- Introduce the concept of `sealed interface`.
- Allow more freedom to `sealed class`, unify both `selead interface` and `sealed class` as the same concept.
- Seamlessly interoperate with JDK 15+ sealed classes and interfaces.

## Design of sealed interfaces

Add support for `sealed` modality on `interface` type with any visibility. 

> Here and below the word "subclass" includes extending interfaces and implementing objects, unless a more restricted 
> meaning is implied by the context. 

Other classes or interfaces  _in the same compilation unit_ and _in the package_ may implement or extend the sealed interface, 
forming a set of direct subclasses that is closed for further extension and is known at compile-time. 
Direct subclasses may be top-level or nested inside any number of other named classes, named interfaces, or named objects in the same package,
Subclass of a sealed interface can be `enum class`, but `annotation class` subclass is not allowed. 

> Kotlin `annotation class` is not allowed to implement any interfaces whatsoever.  
 
Subclasses are allowed to have any visibility as long as they are otherwise compatible with normal Kotlin inheritance rules. 
It is an error to subclass a sealed interface in a different compilation unit or in a different package.
Subclasses of a sealed interface must have a proper qualified name &mdash; they _cannot be_ local nor anonymous objects.
`fun interface` is not allowed to be `sealed`, because the primary way of implementing a `fun interface` is via
functional conversion that creates anonymous instances, too.   

> The restrictions above allow more freedom than a `sealed class` currently provides, owning to the use-cases that
> has surfaced during practical use of sealed classes. However, these restrictions are compatible with restrictions 
> on sealed classes that are introduced by JVM specification.
>
> Unlike Java, Kotlin does not require any kind of `permits` annotation even when subclasses are specified in another 
> file, which honors Kotlin tradition of avoiding source-code repetition of information that could be interred by 
> the compiler. Just like return types of functions are optionally specified, we do reserve a possibility that, 
> in the future, we might add an optional way to specify some kind of `permits` list, but we are reluctant to do this 
> from day one due to the lack of evidence that this will significantly help to substantiate additional syntactic 
> feature in the language.  We plan to address the needs of seeing the list of subclasses by other tools, such as IDE 
> and  an API compatibility validator.

An `interface` in Kotlin is `open` by default. Among the class _modalities_ supported by Kotlin 
(`open`, `sealed`, `abstract`, and `final`) only `open` have been allowed to be (redundantly) specified for an interface 
and this proposal adds support for a `sealed` modality. Specifying both `sealed` and `open` modalities together is an error.

> Unlike Java, Kotlin subclasses of a sealed interface don't have special rules for their modality. An implementing 
> class is `final` by default and extending interface is `open` by default, just like otherwise normal Kotlin class
> or interface.       

## More freedom for sealed classes

Restrictions on placement of subclasses of a `sealed class` are relaxed to match those of the `sealed interface`
above, which are repeated below for completeness. 

Other classes or interfaces  _in the same compilation unit_ and _in the package_ may implement or extend the sealed class, 
forming a set of direct subclasses that is closed for further extension and is known at compile-time. 
Direct subclasses may be top-level or nested inside any number of other named classes, named interfaces, or named objects in the same package. 
Subclasses are allowed to have any visibility as long as they are otherwise compatible with normal Kotlin inheritance rules. 
It is an error to subclass a sealed class in a different compilation unit or in a different package.
Subclasses of a sealed class must have a proper qualified name &mdash; they _cannot be_ local nor anonymous objects.

> Note, that `enum class` cannot extend a `sealed class`, since enum classes are not allowed to extend other classes,
> but they can implement interfaces, including a `sealed interface`.

## Exhaustive when matches

A Kotlin `when(x)` expression follows special exhaustive matching rules when its subject `x` has a compile-time type
that is `sealed`. These rules are extended to support sealed interfaces. When `x` is a class type, then the set of 
its subclasses that are known at compile-time forms a tree, which is built by recursively visiting its subclasses that 
are also sealed. Compile-time exhaustiveness check ensures that all paths in this tree are covered by `is T` type tests 
or instance tests for objects that extend this sealed class. A non-exhaustive `when(x)` produces an error or a warning
depending on whether its result is used or not.
  
This algorithm is extended to support `sealed interface` type of a `when` subject `x`. 
Because of potential multiple inheritance of interfaces, it entails that the recursive hierarchy of its sealed subclasses  
now forms a directed acyclic graph (DAG). Also, there is a possibility that enum classes 
implement sealed interfaces. In this case, all the enum entries of the corresponding enum class are included as a part 
of this DAG (effectively treating `enum class` and a special case of a `sealed class` with object-only subclasses). 
The algorithm will cover `is T` type tests (when `T` is a part of this DAG) and instance euqality tests for 
objects and enum entries that are part of this DAG.

> Note, that we _do not_ propose to add support for non-sealed interfaces and classes into the exhaustiveness analysis
> or otherwise using information outside of this recursively-built DAG. The DAG for analysis is formed only by 
> sealed subclasses with the leaf non-sealed interfaces, objects, and enums that extend or implement the 
> sealed classes or interfaces in the DAG. The only recognized `is T` type tests are the ones that refer to the types 
> in the corresponding DAG. In particular, a use-case described in 
> [KT-20297](https://youtrack.jetbrains.com/issue/KT-20297) where `is T` check refers to an interface `T` 
> outside of this DAG is not going to be supported.          

## JVM compilation strategy and interoperability

Kotlin shall recognize JVM classes and interfaces with a `PermittedSubclasses` attribute in their classfile as
the `sealed` class or interface, enabling all the exhaustive matching features that Kotlin sealed 
classes and interfaces enjoy.

Mixed sealed hierarchies between Java and Kotlin will not be supported. All subclasses of a sealed Kotlin class or 
interface must be defined in Kotlin and all subclasses of a Java sealed classes or interface must be defined in Java.
     
> We do expect this to be too restrictive in practice, since the whole sealed hierarchy must be colocated 
> in the same module and package anyway, so it can be converted from Java to Kotlin as a whole if needed.
        
### Legacy JVM ABI (pre 15)

On a target JVM version before 15 the compilation strategy of a `sealed class` is going to stay the same
as it is now. The strategy is to compile all constructors of such a class as `private` in JVM, while 
adding a corresponding `public synthetic` constructor with an additional `kotlin.jvm.internal.DefaultConstructorMarker`
parameter as means of protection from its inheritance 
by source code in Java (since Java does not resolve `synthetic` declarations). This strategy is fully compatible
with relaxed restrictions on sealed classes.

For a `sealed interface` this proposal is not to attempt any protection scheme, but just compile an interface
as if it was not sealed. Instead, we would implement only an IDE checker for the Java code that is attempting to
extend or implement a sealed Kotlin interface.

> We've considered at least two protection schemes: mangling the JVM name of a sealed interface and adding 
> synthetic members that are not implementable in Java to it. We've found both schemes to be quite disruptive with 
> lots of problems that do not balance relatively minor inconvenience of accidentally implementing a Kotlin sealed 
>interface from Java code.
 
### New JVM ABI (JDK 15+)

On a target JVM starting from version 15 the proposal is to emit `RestrictedSubclasses` JVM attribute for  
Kotlin sealed interfaces and classes, getting rid of synthetic constructor protection scheme
for sealed classes that is explained above.

> Note, that other Kotlin backends (JS and Native) do not maintain a stable open-world ABI, so the question of a 
> particular compilation strategy is not part of this design document, but is an implementation detail 
> of the corresponding backends. 

## Reflection

Support of `sealed interface` means the following adjustments in the behavior of Kotlin reflection functions.
* [KClass.isSealed](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/is-sealed.html)
  will return `true` for a `sealed interface`. 
* [KClass.sealedSubclasses](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/sealed-subclasses.html)
  will return a list of the corresponding subcalsses of a `sealed interface`.
  
## Open issues and future opportunities

The concept of a `sealed interface` makes it conceptually possible to support `internal` functions in such interfaces
to aid in the design of interface-based APIs that have the functions that are needed for their implementation details
hidden from their clients (see comments in [KT-22286](https://youtrack.jetbrains.com/issue/KT-22286)).   
 


  
# Companion extensions and blocks

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Nikita Bobko, Yuliya Karalenka, Roman Venediktov, Mikhail Vorobev, Mikhail Zarechenskii
* **Discussion**: [#467](https://github.com/Kotlin/KEEP/discussions/467)
* **Status**: In progress
* **Related YouTrack issues**:
  [KT-11968](https://youtrack.jetbrains.com/issue/KT-11968),
  [KT-15595](https://youtrack.jetbrains.com/issue/KT-15595),
  [KT-16872](https://youtrack.jetbrains.com/issue/KT-16872)
* **Previous related proposal**:
  [KEEP-427](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0427-static-member-type-extension.md),
  [KEEP-347](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md)

## TL;DR

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion { // companion block
        val Zero: Vector get() = Vector(0.0, 0.0)
    }
}

// companion extension
companion val Vector.UnitX get() = Vector(1.0, 0.0)
```

* Companion extensions may be defined even if the type doesn't have a companion
  object or companion block (including types coming from Java).
* Members in companion blocks become static members in supported platforms.
* Companion blocks and extensions take precedence over companion objects in
  the general case.

## Context

During the [discussion](https://github.com/Kotlin/KEEP/discussions/427) of the 
[_Static members and type extensions_](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0427-static-member-type-extension.md)
and the previous [_Statics and static extensions_](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md)
proposals we have gathered some important feedback about the overall design.
And maybe the most important one is that there is no _single_ problem, but a
pile of different problems whose importance is deemed different by different
members of the community.

In this document we describe those problems, and propose a couple of features
to solve those problems. Although each feature could be described separately,
keeping both of them under the same umbrellas showcases how they should work
together.

## Table of contents

* [TL;DR](#tldr)
* [Context](#context)
* [Table of contents](#table-of-contents)
* [Problems with the status quo](#problems-with-the-status-quo)
* [High-level description](#high-level-description)
    * [Companion extensions](#companion-extensions)
    * [Companion blocks](#companion-blocks)
    * [Discarded ideas](#discarded-ideas)
* [Main design decisions](#main-design-decisions)
    * [On syntax](#on-syntax)
* [Technical details](#technical-details)
    * [Declaration](#declaration)
    * [Resolution](#resolution)
    * [Initialization](#initialization)
    * [Compilation strategy](#compilation-strategy)
    * [Reflection](#reflection)
* [Migration](#migration)
* [What this proposal means for the JVM](#what-this-proposal-means-for-the-jvm)
* [Acknowledgements](#acknowledgements)


## Problems with the status quo

The design for [_class members_](https://en.wikipedia.org/wiki/Static_%28keyword%29#As_a_class_member_specifier)
in Kotlin revolves around the notion of [companion object](https://kotlinlang.org/docs/object-declarations.html#companion-objects).

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion object {
        val Zero: Vector = Vector(0.0, 0.0)
    }
}
```

The companion object inherits a lot of its properties from being an object
defined within the classifier. On top of that, their members are accessible
using the name of the type only — for example, we don't need to write
`Vector.Companion.Zero` below.

```kotlin
fun Vector.normalize() = when (this) {
    Vector.Zero -> this
    else -> ...
}
```

This design gives a lot of flexibility to companion objects — for example,
they may implement an interface — but has proven insufficient in a few
different aspects.
At this point, it is important to stress that these problems affect
**different users** by **different levels**, and any solution implicitly
assigns relative importance to them. Since Kotlin has a big user base, any
problem that affects a seemingly minor percentage of users may still affect
a large absolute number of people.

**(#1) Extending the companion scope**.
Companion objects are in many ways no different than any other object in Kotlin.
In particular, you can write an _extension_ to a companion object to create
a new callable that can be accessed through the classifier's name.

```kotlin
val Vector.Companion.UnitX get() = Vector(1.0, 0.0)
```

There is an important restriction, though: the companion object class **has to
exist**. In practice, that means that if the original author of the classifier
didn't write a `companion object` at all, this ability is not available.
It's an [old issue](https://youtrack.jetbrains.com/issue/KT-11968) to remove
this gate-keeping, and make extending the companion scope available regardless
of the presence of an actual companion object class.

**(#2) More direct mapping to platform statics.**
Kotlin prides itself in good interoperability with the underlying platforms
it runs. And most of them (JVM, JS, Swift) feature a notion of _static member_.
In some scenarios, it becomes very important to generate such a static member
from Kotlin code, usually because some framework requires it.

The current approach — a combination of `companion object` and `@JvmStatic` —
becomes quite cumbersome if needed often. For example, this is how you use it
in [combination with JUnit](https://medium.com/@theAndroidDeveloper/please-read-this-before-using-beforeclass-in-kotlin-junit-tests-890596692eb2):

```kotlin
class Tests {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() { ... }
    }
}
```

This design also requires a deep knowledge of the compilation strategy to be
used successfully, even if the developer knows both Kotlin and the target
platform well. In many cases, failing to add the corresponding annotation
either breaks at runtime, or may succeed to run with unintended behavior
(like tests failing or passing when they should not).

**(#3) Multiplatform `@Static` annotation.**
The current design also require an annotation for every platform
(`@JvmStatic`, `@JsStatic`, and so forth). This issue impact authors of
multiplatform libraries mostly, that need to remember all those annotations
to expose their API in the way expected by every target platform.

**(#4) Additional object allocation.**
Since companion objects are indeed objects, they require an allocation the
first time they are used. This is a problem in the JVM and Native targets.

For example, using `Vector.Zero` in the code above
would result in `Vector.Companion.INSTANCE.getZero()` in JVM bytecode.

```java
class Vector {
    static class Companion {
        private Companion() { }
        public static Companion INSTANCE = Companion();
        public Vector getZero() { return new Vector(0.0, 0.0); }
    }
}
```

This companion object and instance is created even if you mark the member
using `@JvmStatic` or similar annotation.

```java
class Vector {
    static Vector getZero() {
        // calls the instance method
        return Vector.Companion.INSTANCE.getZero();
    }
}
```

An important remark to this problem is that it heavily impacts applications
with lots of classes, and henceforth lots of companion objects, such as
IntelliJ IDEA. Furthermore, "translating" code from Java to Kotlin suddenly
duplicates the amount of classes to be initialized, that in turn may impact
start-up performance.

In the case of Native, there's an additional cost on top of allocation:
since that target does not feature a JIT, every access to companion object 
might potentially need to check whether it is already created.

This issue has led to request for [not creating both methods](https://youtrack.jetbrains.com/issue/KT-16872)
and [dropping the companion if empty](https://youtrack.jetbrains.com/issue/KT-15595).
However, doing so in a naive way impact binary compatibility, since methods
or class that were created before would no longer be available.

**(#5) No expect/actual matching**.
Since static members do not exist in the Kotlin language, matching classes
in a multiplatform environment becomes more complicated than expected.
For example, it may be desirable to declare a expected `Vector` type,

```kotlin
expect class Vector {
    companion object {
        val Zero: Vector
    }
}
```

and match it with an actual Java type where `getZero` is a static method.

This problem is more niche than the others, but heavily impacts libraries
trying to transition to multiplatform, since they cannot easily wrap existing
code into a facade of expect classes and functions.

## High-level description

The previous proposal to solve the aforementioned problems used a single
concept — static scopes — as the unifying framework for solving them.
The introduction of such a new concept is always difficult in a
programming language, since it may have unexpected interactions with the
existing features. In the new design we try to follow a few principles:

- **No multiplication of concepts.** Instead of bringing a new concept to
  the language, try to generalize existing ones. In particular, we shall
  use the concept of _companion_ as a guide.
- **Simple and to the point.** Try to solve the problems that we have,
  and no more. Leaving some nice use cases out must be weighed against having
  simpler rules in the language.
- **Expose only as much as needed.** Do not expose into the language what
  could be solved by making the compiler or its analyses better.

### Companion extensions

This is a direct way to declare functions accessible through the type name
(that is, class members). This solves problem #1.

```kotlin
companion val Vector.UnitX get() = Vector(1.0, 0.0)
```

We keep companion extensions to the **top-level**, since that greatly simplifies
the resolution model. With the advent of context parameters this restriction
is not as heavy as before, since you can request any additional scope to be
present using them.

In companion extensions there is **no implicit `Companion` receiver** present. 
That way
the code generation strategy doesn't change, regardless of the presence of
a companion object. Note that you can still access members in the companion
object; you just need to use qualify it with `Type.` explicitly.

One of the advantages of members in a companion object is that you don't need
to import them explicitly if you had already imported the type. This seems like
a very useful property, so we want to explore ways to **automatically import**
companion extensions define alongside the type itself.

### Companion blocks

Solving problem #4 requires changes to the current code generation strategy. 
In particular, in order to generate code that doesn't allocate a companion
object we need to ensure that its instance is not used. We have considered
several approaches to this mechanism, ultimately choosing for companion blocks.

**Companion blocks** look and feel very similar to companion objects, but 
lack the "object" part in it.

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion {
        val Zero: Vector get() = Vector(0.0, 0.0)
    }
}
```

That is, members in this block inherit the main properties of a _companion_:

- Being accessible through the name of the type,
- Participate in context-sensitive resolution;

but do not include any of the properties associated with an _object_:

- There is no classifier attached to them one may refer to,
- There is no instance of an object that holds them.

The compilation strategy for companion blocks should not be fixed.
However, in those platforms that support statics, we propose to use those
features, solving #2 and #4. This also alleviates the need for #3,
that remains out-of-scope for this KEEP document

### Discarded ideas

The key point in the design of this facility is how to expose the fact that
in some members the companion object instance is not available (and maybe
ultimately never created). We have looked as options outside the language
itself (compiler flags) and within the language (annotation, block).

**Compiler flag.** By switching `-companion-compilation=static` we request
the compiler to forbid instances of a `companion object`. In turn, that means
that the members define there can be turned into static members of the enclosing
class (in platforms that support it) and we can even remove the empty object.

This approach however is very implicit — since it depends on a flag in your
build file — and also too global — you cannot switch it per class. 
In addition, it bends the rules of the language too much, since we are using
the concept of `companion object` for several different things.

**Annotation in the companion object.** If we expose the behavior described
above using an annotation, we fix some of the immediate issues, but we are
still bending the notion of `companion object`. For example, adding or removing
the annotations have profound effects on the binary compatibility.

**Namespaces.** In the discussion about statics the notion of _namespace_
was discussed. The concept of companion block is influenced by this idea.
However, we don't want to pursue a more generalized notion of namespace at
this point, because it doesn't take us closer to solving the problems we've
described above.

## Main design decisions

The proposal outlined above still requires a couple of important design
decisions, which we outline and discuss here.

**No instance.** Companion block members and companion extensions are **not**
accessible through instances of the corresponding type. However, the rules
ensure that in the body of a class you can still access those members without
qualification, unless they conflict with an actual member.

Another side of this decision is that the name of the type is **not** an
**expression**, since there is no underlying value.
You actually should use a companion _object_ for that use case.
One consequence of this decision is the general prohibition of operators.

```kotlin
class Example {
    companion {
        // if we were allowed to write this...
        fun plus(x: Int): Int { ... }
    }
}

// ...then we should be allowed to write this, too
val x = Example + 1
```

However, we recognize that the `invoke` and `of` operators cover important
functionality: providing "fake constructors", and constructing collection
literals, respectively. In those cases the type name works less as a value
and more like scoping mechanism.

**Blocks and extensions before objects.** At the moment of writing, the Kotlin
specification gives more priority to static members in comparison to companion
object members. This rule is easily extended to companion block members 
— they take the place of static members in that rule —, but leaves the question
of when companion extensions should be accounted for.

In general, we want to give priority to companion blocks and extensions over
objects; if in the same scope, the former ones should prevail.

```kotlin
class Example {
    companion object
}

fun Example.Companion.foo() = ...  // (1)
companion fun Example.foo() = ...  // (2)

fun bar() = Example.foo()  // resolves to (2)
```

The crux of the question is that you can have extensions to the companion
objects introduced in many different scopes — in contrast to companion
extensions, that are only available at top-level. This leaves us with two
possibilities, that translate to the question of whether the last line is
resolved to the companion object member (1) or the companion extension (2).

```kotlin
class Example {
    companion object {
        fun foo() = ...  // (1)
    }
}

companion fun Example.foo() = ...  // (2)

fun bar() = Example.foo()
```

_Make companion blocks and extensions work as a single receiver, separate from
the companion object receiver_.
This means that both scopes are exhausted before moving to the companion object.
In the example above, that means that the last line resolves to (2), the
companion extension.

_Make all companion declarations work as a single receiver_.
This mean each of the scopes, until top-level, interleaves resolution for
companion blocks or extensions and companion object members. In the example
above, the last line resolved to (1), since the member scope of the companion
object has more priority than top-level scope.

In this proposal we decide to go with the first model. There are several
reasons for that choice, mostly for the sake of simplicity.

- We can easily explain `T.foo()` (where `T` is a type) to first try resolving
  with companion blocks and extensions, and then try resolving as
  `T.Companion.foo()`.
- It gives a clear answer to what is in scope and with what priority when
  we are in the body of a companion block, companion extension, and companion
  object member, by reusing the same machinery (receivers) that we already
  have in Kotlin (see examples Example 2.1 to Example 2.3).
- Companion object members and extensions can always be disambiguated by
  _explicitly_ writing `T.Companion`, something that is not possible with
  the other kinds of companions.

**Accommodate platform initialization semantics.** We want the "translation"
from Kotlin code into the semantics of each platform to be straightforward.
As we discuss in the _Initialization_ section below, our current targets 
heavily differ on this point, so we need to cater to the common denominator.
In our design this is visible by only allowing property initializers in 
companion blocks, but not a more general notion of `init` for such blocks.

### On syntax

During the 
[previous iteration](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0427-static-member-type-extension.md)
of this proposal the syntax for extensions was quite different.

```kotlin
val Vector::companion.UnitX get() = Vector(1.0, 0.0)
```

To understand which syntax should be preferred, we conducted user research
focused on the comprehensibility of both syntax variants. The version that uses
`companion` as a modifier was easier to understand and caused fewer moments of 
confusion especially among experienced Kotlin developers.

## Technical details

### Declaration

**§1.1** (_grammar_): the [Kotlin grammar](https://kotlinlang.org/spec/syntax-and-grammar.html#syntax-grammar)
is modified as follows. Note that only function and property declarations are
allowed in a companion block.

```diff
   classMemberDeclaration:
       ...
     | companionObject
+    | companionBlock
+
+  companionBlock:
+    'companion' {NL} companionBlockBody
+
+  companionBlockBody:
+     '{'
+     {NL}
+     companionBlockDeclarations
+     {NL}
+     '}'
+
+  companionBlockDeclarations:
+     {companionBlockDeclaration [semis]}
+
+ companionBlockDeclaration:
+     functionDeclaration
+   | propertyDeclaration

   memberModifier
       ...
+    | 'companion' 
```

**§1.2.1** (_companion blocks, aggregation_): more than one companion block
may be defined on a single class.

**§1.2.2** (_companion blocks, object_): companion blocks may not be defined
if the enclosing classifier is an object.

**§1.2.3** (_companion blocks and companion objects_): it is allowed
to declare both companion blocks and a companion
object on the same class. In most situations the companion block has highest
priority than the companion object, as described in the _Resolution_ section.

**§1.2.4** (_companion blocks, members_): constructor and initialization blocks
are not allowed inside a companion block. Extension members are not allowed
in a companion block. Only the `invoke` and `of` operators may be defined in a 
companion block.

**§1.2.5** (_companion blocks, modifiers_): members defined within a companion
block may not be marked as `open`, `abstract`, `final`, or `override`.
As a consequence, all members in a companion block must have a body, except
for those marked `expect` or `external`.

**§1.2.6** (_companion blocks, imports_): members of companion blocks may be
imported by using the name of the classifier followed by a dot and the name
of the companion block member.
This syntax aligns with how static methods from Java classes can be currently
imported in Kotlin code.

**§1.3.1** (_companion extensions, top-level_): the `companion` modifier may
only be used on top-level callables.

**§1.3.2** (_companion extensions, receiver_): companion extensions must define a
receiver type. Such receiver type:

- Must be a declared classifier or a type alias (so no type parameters);
- Must not be an object, only classes and interfaces are allowed;
- Should not have any given type arguments.

Any other positions in the signature bear no additional restrictions.

```kotlin
fun <T> Example.from(elements: List<T>)  // allowed
fun <A> A.emptyList(): List<A>           // rejected
```

This is even more restrictive that what it's allowed to write in a 
[class literal](https://kotlinlang.org/spec/expressions.html#class-literals),
the other place where Kotlin developers routinely see parametrized types
without their type arguments:

- Type parameters cannot be used, even if they are `reified`.
- Even `kotlin.Array` must appear without type arguments.

**§1.3.3** (_companion extensions, type aliases_): if the receiver type
of a companion extension is a type alias, then it should be taken as referring
to the outer type in the expansion.

```kotlin
typealias Foo<T> = List<Pair<T, T>>

companion fun Foo.companionExtension() = ...
// equivalent to
companion fun List.companionExtension() = ...
```

The following pre-processing is done before choosing the type the alias refers
to in the companion extension receiver position:

- Nullability (either `?` or `& Any`) is dropped,
- Function types are rejected.

The additional constraints from §1.3.2 must also be satisfied after expansion.

This rule is important in multiplatform scenarios, in which an `expect` class
may be `actual`ized via a type alias.

**§1.3.4** (_companion extensions, operators_): only the operator `invoke`
may be defined as a companion extension. Note that the `of` operator may
**not** be defined as a companion extension.

**§1.3.5** (_companion extension properties_): companion extension properties
are not subject to the
[restrictions over extension properties](https://kotlinlang.org/spec/declarations.html#extension-property-declaration).
In particular, they are allowed to have initializers and backing fields,
unless some other rule of the language forbids that. In turn, this means they
may be annotated with `@JvmField`, among other properties.
  
**§1.3.6** (_companion extensions, receiver is not a parameter_): the receiver
type in a companion extension does **not** count as a formal parameter.

**§1.3.7** (_companion extensions, no annotations_): it is not allowed to 
annotate the receiver type of a companion extension in any way —
including the `@receiver` and `@type` use sites.

This choice allows a platform to completely _erase_ those in code generation,
if desired.

**§1.4** (_constants_): the rules for [constant properties](https://kotlinlang.org/spec/declarations.html#constant-properties)
are updated to include properties defined in companion blocks or as companion
extensions.

**Example 1.1** (_example_): the following code should be accepted. Note that it
uses two separate companion blocks, declares properties with an initializer,
and the last one is marked as constant.

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion {
        val Zero: Vector = Vector(0.0, 0.0)
    }

    fun normalize(): Vector = when (this) {
        Zero -> this
        else -> /* compute normalized vector */
    }

    companion {
        const val Dimensions: Int = 2
    }
}
```

**Example 1.2** (_operators_): the following code uses `invoke` to define
a fake constructor that `suspend`s.

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion {
        suspend operator fun invoke(file: StructureFormat): Vector {
            val x = file.readDouble()
            val y = file.readDouble()
            return Vector(x, y)
        }
    }
}
```

**§1.5** (_enumerations_): [enumeration entries and other "unofficially static"
members](https://kotlinlang.org/spec/declarations.html#enum-class-declaration)
in a `enum` class "officially" become members of the companion block.

```kotlin
enum class Direction { UP, DOWN }

// should be considered as follows
class Direction : Enum<Direction> private constructor() {
    companion {
        public val UP: Direction
        public val DOWN: Direction

        public val entries: EnumEntries<Direction>
        public fun valueOf(value: String): Direction
        public fun values(): Array<Direction>
    }
}
```

### Resolution

The specification introduces the notion of 
[phantom static implicit `this`](https://kotlinlang.org/spec/overload-resolution.html#receivers)
to define interoperability with languages that feature statics. In this section
we reuse this notion to account for companion blocks and extensions.

> [!NOTE]
> In the following we heavily refer to the
> [Overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution)
> section of the
> [Kotlin language specification](https://kotlinlang.org/spec/introduction.html).

**§2.1.1** (_phantom static implicit `this`_): companion block members and
companion extensions are available through the phantom static implicit `this`
receiver.

On this regards, the specification states that:

> The phantom static implicit `this` receiver has higher priority than the
> current class companion object receiver;

The practical consequence is that members defined in companion blocks or as
companion extensions always "win" over the companion object.
This is a reasonable compromise, since you can always refer to the companion
object by using its classifier name.

**§2.1.2** (_`this` expressions_): phantom static implicit `this` is not
available through [explicit `this` expressions](https://kotlinlang.org/spec/overload-resolution.html#receivers).
Access to companion block members must always be done either implicitly or
through an explicit type receiver, as defined in §2.3.1.

**§2.2.1** (_companion block members, receiver_): the implicit `this` receiver
for the class is **not** available for companion block members. As a result,
the phantom static implicit `this` becomes the receiver with the most priority.

The current class companion object receiver is not present either. But we
cannot define it anyway, as companion blocks and objects may not co-exist.

**§2.2.2** (_companion extensions, receiver_): in the body of a companion
extension the phantom static implicit `this` is introduced with the highest
priority.

**§2.3.1** (_resolution, calls with an explicit type receiver_):
the specification contains specific rules for
[calls with an explicit type receiver](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver).
The rules are updated as follows (updates in boldface):

> [..] for a callable f with an explicit type receiver `T` the following sets
> are analyzed (in the given order):
> 
> - Static member **and companion block member** callables named `f` of type `T`;
> - Static member callables named `f` of type `T` declared implicitly;
> - **Companion extensions named `f` of type `T`**;
> - The overload candidate sets for call `T.f()`, 
>   where `T` is a companion object of type `T`.

This approach is consistent with the definition of phantom static `this` in the
specification as a receiver with more priority than the companion.

**Example 2.1** (_priority_): the code below exemplifies how companion extensions win
even over companion object members, and how to refer to companion object
members in such a situation.

```kotlin
class Example {
    companion object {
        fun test() { } // (1)
    }
}

companion fun Example.test() { }  // (2)

fun example() {
    Example.test()            // resolves to (2)
    Example.Companion.test()  // resolves to (1)
}
```

**Example 2.2** (_absence of companion object members in extensions_): the code below
exemplifies the fact that companion object members are not available in
companion extensions, since they only have the phantom static implicit `this`
as an implicit receiver.

```kotlin
class Example {
    companion object {
        fun test() { }
    }
}

companion fun Example.example() {
    test()          // error
    Example.test()  // resolves to the companion object member
}
```

**Example 2.4** (_resolution in companion blocks_): the code below exemplifies that
companion block members and companion block extensions may be called without
qualification from any of those places.

```kotlin
class Example {
    companion {
        fun functionInCompanionBlock()

        fun testInCompanionBlock() {
            functionInCompanionBlock()
            companionExtensionFunction()
        }
    }
}

companion fun Example.companionExtensionFunction() { }

companion fun Example.testInCompanionExtension() {
    functionInCompanionBlock()
    companionExtensionFunction()
}
```


**Example 2.4** (_receiver in companion objects_): in the case of members or extensions
to the companion object, the corresponding receiver is introduced with the 
highest priority. In the case of companion object members, the phantom static
implicit `this` of the enclosing class is still available.

```kotlin
class Example {
    companion {
        fun foo() { }  // (1)
        fun bar() { }  // (2)
    }

    companion object {
        fun foo() { }  // (3)

        fun example1() {
            foo()          // resolves to (3)
            Example.foo()  // resolves to (1)
            bar()          // resolves to (2)
            Example.bar()  // resolves to (2)
        }
    }
}

fun Example.Companion.example2() {
    foo()          // resolves to (3)
    Example.foo()  // resolves to (1)
    bar()          // error
    Example.bar()  // resolves to (2)
}
```

**§2.3.2** (_resolution, callable references_): companion block members and
companion extensions are taken into account to
[resolve callable references](https://kotlinlang.org/spec/overload-resolution.html#resolving-callable-references).
The rules are updated as follows (updates in boldface):

> [..] building overload candidate sets for both type and value receiver
> candidates, they are considered in the following order.
>
> - Static member **and companion block member** callables named `f` of type `T`;
> - **Companion extensions named `f` of type `T`**;
> - The overload candidate sets for call `t::f`, where `t` is a value of type `T`;
> - The overload candidate sets for call `T::f`, where `T` is a companion object of type `T`.

In the case of a companion extension, the receiver type is not visible in
the type of the obtained callable reference.

**Example 2.5** (_callable references_): the code below should be accepted.

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion {
        val Zero = Vector(0.0, 0.0)
    }
}

companion fun Vector.getUnitVector(dimension: Int): Vector { ... }

fun test() {
    val z: KProperty<Vector> = Vector::Zero
    val d: (Int) -> Vector = Vector::getUnitVector
}
```

**§2.3.3** (_resolution, access trough type alias_): using a type alias in the
position of a type for both calls and references follows the same rules as 
in companion extension signatures (see §1.3.3).

```kotlin
class Example<T> {
    companion {
        fun foo(): Int = 1
    }
}

typealias TA = Example<Int>?

fun test() {
    val x = TA.foo()  // same as 'Example.foo'
}
```

**§2.4 (_constant scope_):** [constant properties](https://kotlinlang.org/spec/declarations.html#constant-properties)
in companion blocks are considered a separate scope, upward-linked to the
rest of the static and companion scope (from the companion blocks members).

As a consequence, it is possible to refer to constant properties defined
anywhere in a class during companion initialization of that same class.

### Initialization

Companion block properties may have initializers, but it is not immediately
clear when those should run. Given the multiplatform nature of Kotlin,
we need to cater for different initialization semantics.

When talking about initialization, the first question is _when_ it is performed.
There is a strict bound: companion block properties must be initialized, at
the very latest, before they are accessed for the first time. But in some
platforms, like the JVM, this initialization happens in response to many other
runtime events, like when the class containing the property is instantiated.
Other platforms, like Swift, have _lazy_ semantics that defer initialization of
a property until the very latest moment possible.

> [!NOTE]
> The JVM has a very specific
> [set of rules](https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-5.html#jvms-5.5)
> for the moment static initialization runs.
> The rules in this proposal are compliant with the JVM requirements.

Furthermore, some platforms like the JVM perform _bulk initialization_.
That means that every static field (including the one representing the companion
object) is initialized at the same time. At the other end of the spectrum
we have _per-property initialization_, like in Swift, in which each property
is initialized differently.

> [!WARNING]
> When using bulk initialization it is possible to leak
> intermediate values. For example, the following code,
>
> ```kotlin
> class Bulk {
>     companion {
>         val a = getTheB()
>         val b = 5
>         fun getTheB() = b
>     }
> }
> ```
>
> results in `a` having the (default) value `0` in the JVM.

Given our goal of behaving as close as possible to the usual behavior in
each platform, we shall only describe when companion block properties
are _required_ to be initialized. The compiler or the runtime are free to
perform such initialization at any point _before_ such requirement.

**§3.1** (_general rule_): the initializer of a property in a companion block,
if present, _must_ run before the first time the property is accessed. Such
initialization may trigger more, if other properties with initializer are
mentioned.

If any step in the initialization process leads to a loop, it results in
unspecified behavior. This aligns with the "regular" initialization order in the
[specification](https://kotlinlang.org/spec/declarations.html#classifier-initialization).

**§3.2.1** (_bulk initialization, requirements_): in platforms with bulk
initialization semantics, initialization of properties in companion blocks
_must_ happen before one of these conditions are met:

1. one of the non-constant companion block members of that classifier is accessed;
2. the companion object of that classifier is accessed;
3. a constructor of that classifier is called, either directly or indirectly
   via one of its subclasses.

It is not required to initialize the companion block members in parent
classifiers, unless these are required by calling their constructors.
Note that in the case of interfaces a subclass does not call a constructor.

**§3.2.2** (_bulk initialization, order_):
whenever bulk initialization happens, it should respect program order, with
the companion object being initialized at the point it's declared. The only
exception are _constants_, which should be initialized _before_ any other
property in companion blocks.

This proposal does not mandate any guarantees when accessing values defined
later in program order during bulk initialization. As discussed above, this
may result in intermediate or default values leaking.

**§3.3** (_companion extensions_):
we remark that calling a companion extension does _not_ imply the
initialization of the classifier being extended, as per the rules above.
On the other hand, companion extension properties are treated with respect
to initialization as top-level properties without a receiver.

```kotlin
companion val Vector.hasFiniteBasis: Boolean = true

fun findBasis(): List<Vector> {
  if (!Vector.hasFiniteBasis) return emptyList()
  // at this point companion initialization of 'Vector' may not have been performed
}
```

**§3.4** (_enumerations_):
in the case of enumerations, initialization of properties in companion block
must happen after the initialization of:

1. enumeration entries,
2. initialization related to the implicit members `entries` and `values`.

That means that members in companion blocks may freely refer to enumeration
entries without fear of initialization loops.

### Compilation strategy

**§4.1.1** (_JVM, companion block members_): companion block members are
compiled into static members in the enclosing class. Backing fields of 
properties are compiled into static private fields.

**§4.1.2** (_JVM, companion initialization_): companion initialization is
compiled into the static initializer `<clinit>`, following the order described
in §3.2.2.

**§4.1.3** (_JVM, companion extensions_): companion extensions are compiled
as static members of the corresponding `FileKt` class, without any value
parameter standing for the receiver type. Backing fields of properties
are compiled into static private fields.

The name of this static member is the same given in the source code, unless
it has been overridden using a `@JvmName` annotation.

**Example 4.1.1** (_JVM example_): the code below,

```kotlin
// in file 'Vector.kt'

data class Vector(val x: Double, val y: Double) {
    companion {
        val Zero: Vector = Vector(0.0, 0.0)
        const val Dimensions: Int = 2
    }
}

companion fun Vector.getUnitVector(dimension: Int): Vector { ... }
```

is compiled into bytecode equivalent to the following Java code,

``` java
class Vector {
    // instance fields and constructor omitted 

    private static final Vector Zero;
    public static Vector getZero() { return Zero; }

    public static final int Dimensions = 2;

    static {
        Zero = new Vector(0.0, 0.0);
    }
}

class VectorKt {
    static Vector getUnitVector(int dimension) { ... }
}
```

**Example 4.1.2** (_avoiding platform clashes_): the rules above may lead to platform
clashes in both class bodies and companion extensions. Note that the JVM
forbids defining a static and instance method with the same signature. The
solution is to use `@JvmName` to rename one of them.

```kotlin
class Example {
    fun foo() { ... }

    companion {
        @JvmName("createFoo")
        fun foo() { ... }
    }
}

// both have to 'void()' as signature
@JvmName("barExample") companion fun Example.bar() { ... }
@JvmName("barVector")  companion fun Vector.bar() { ... }
```

**§4.1.4** (_JVM, backing fields of companion objects_):
currently, the state of a companion object is stored using static fields in the
enclosing class, using the same name as the properties declared there. This may
result in a clash with backing fields of companion block properties.

For that reason, we propose to change the compilation scheme of companion objects.
In particular, whenever a private field is introduced in a class due to a
declaration in its companion object, then its name should be _mangled_.
The proposed mangling scheme is `CompanionObjectName$fieldName`.

This rule does _not_ cover two cases in which the introduced field
[is not private](https://www.baeldung.com/kotlin/companion-objects-in-java),
namely when using `@JvmField` annotation, or the `lateinit` and `const`
modifiers. In that case introducing two properties with the same name, one
in the companion object and one in a companion block, lead to a _platform clash_.

For example, the following Kotlin code,

```kotlin
class Vector(...) {
  companion object {
    const val Dimensions: Int = 2
    val SpecialVectors: MutableMap<Vector, String> = mutableMapOf()
  }
}
```

is compiled down in the JVM to the equivalent of the following Java code,

```java
public class Vector {
  // companion object instance
  public static Companion Companion { }
  // constant
  public static final int Dimensions = 2;
  // backing field of companion object property
  private static MutableMap<Vector, String> Companion$SpecialVectors;

  static {
    Companion$SpecialVectors = new LinkedHashMap();
    Companion = new Companion();
  }

  class Companion {
    public final MutableMap<Vector, String> getSpecialVectors() {
      return Vector.Companion$SpecialVectors;
    }
  }
}
```

**§4.1.5** (_JVM, expect/actual matching_): the described compilation strategy
should be taken into account for `actual`izing an `expect` declaration with
a Java implementation. In particular, static members in a Java class may
`actual`ize companion block members.

**§4.2.1** (_JS, companion block members_): companion block members are
compiled into [static members](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Classes/static)
of the enclosing class.

Note that static members are available through the
prototype chain, but when accessed through the class name the right overload
is chosen, as shown in
[this example in MDN](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Classes/static#using_static_members_in_classes).

**§4.2.2** (_JS, companion initialization_): the initializer for a property
in a companion block is translated into the initializer for the corresponding
(private) backing field.

**§4.2.3** (_JS, companion constants_): constants do not introduce a backing
field, instead the property is directly exposed, and the initializer turns
into the initializer of that property.

**§4.2.4** (_JS, companion extensions_): companion extensions are compiled
as any other top-level declaration. The name in JS is taken verbatim from
the declaration, although it can be overridden using `@JsName`.

**Example 4.2.1** (_JS example_): the code below,

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion {
        val Zero: Vector = Vector(0.0, 0.0)
        const val Dimensions: Int = 2
    }
}

companion fun Vector.getUnitVector(dimension: Int): Vector { ... }
```

is compiled to the following JS code,

``` js
class Vector {
    static #Zero = new Vector(0.0, 0.0);
    static get Zero() { return Vector.#Zero; }

    static Dimensions = 2;
}

function getUnitVector(dimension) { ... }
```

**§4.3.1** (_Swift, choice for `static`_):
Swift features both `static` and `class` members, collectively known as
[type methods](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/declarations#Special-Kinds-of-Methods)
and [type properties](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/properties#Type-Properties).
The difference between both is that `class` members may be overridden in
subclasses, whereas `static` cannot.

From those choices, `static` is the closest to the semantics in this proposal.
Alas, you cannot hide or redefine a `static` member, either, in contrast to Java.

```swift
class A {
    static var number: Int { 1 }
}

class B : A {
    // cannot override static property
    static var number: Int { 2 }
}
```

The Swift compiler, however, contains a escape hatch for these situations by
means of the `@_nonoverride` private attribute. Any potential problems cannot
be experienced if you always access the member using the name of the enclosing
class, that is, `A.number` or `B.number`.

We have considered different several alternatives, including mangling the naming
or introducing additional arguments, but in all cases the resulting API cannot 
be easily consumed from Swift. We could also use the `class` modifier, but that
brings questions about how to handle potentially conflicting overrides.

**§4.3.2** (_Swift, only export_): in this proposal we only defined how
the members from companion blocks and extensions should be exposed for
consumption in Swift code. This proposal does not specify particular behavior
for initialization if the requirements in the _Initialization_ section are
satisfied; nor how backing fields and constants should be compiled.

**§4.3.3** (_Swift, companion block members_): companion block members are
exposed as type methods and properties, using `@_nonoverride static`.

**§4.3.4** (_Swift, companion extensions_): companion extensions are exposed
as an [extension](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/extensions/)
holding the members. Other than that, the compilation scheme is similar
to that of companion block members.

**Example 4.3.1** (_Swift example_): the code below,

```kotlin
data class Vector(val x: Double, val y: Double) {
    companion {
        val Zero: Vector = Vector(0.0, 0.0)
        const val Dimensions: Int = 2
    }
}

companion fun Vector.getUnitVector(dimension: Int): Vector { ... }
```

is exposed as the following Swift interface,

``` swift
class Vector {
    @_nonoverride static var Zero: Vector { get }
    @_nonoverride static var Dimensions: Int { get }
}

extension Vector {
    @_nonoverride static func getUnitVector(dimension: Int) -> Vector
}
```

### Reflection

**§5.1** (_companion block members_):
in those platforms in which `kotlin.reflect` contains `KDeclarationContainer`,
the package is updated as follows.

1. A new interface for containers of companion blocks.

    ```kotlin
    interface KCompanionDeclarationContainer {
      val companionMembers: Collection<KCallable<*>>
    }
    ```

2. `KClass` implements `KCompanionDeclarationContainer`.

**§5.2** (_companion "receiver"_):
the following property is added to `KCallable`. This property should be `null`
whenever the callable is neither a companion extension nor defined in a 
companion block.

```diff
  interface KCallable<out R> {
+   val companionParameter: KCompanionParameter?
  }
```

The `KCompanionParameter` interface mirrors `KParameter`.

```kotlin
interface KCompanionParameter {
  val type: KClass<*>
  val kind: Kind

  enum class Kind {
    COMPANION_BLOCK,
    COMPANION_EXTENSION
  } 
}
```

Note that the `type` is a `KClass` and not a `KType` because those relate to
the _class itself_, not a particular instantiation thereof.

**§5.3** (_no additional arguments to `call`_):
no value should be passed in the position of receiver for neither 
companion block members nor companion extensions
when using `call`, `callBy` from `KCallable`, and the corresponding
functions from the `KProperty` hierarchy.

```kotlin
val p: KProperty0<Vector> = Vector::Zero  // note the 0 here
val t = p.companionParameter?.type        // KClass representing 'Vector'
val zero = p.get()                        // no argument required
```

## Migration

One fair question is what should happen with the companion objects already
in place, and their extensions: should those be migrated over to companion
blocks and extensions? First of all, note that migration is not possible if
the companion object (1) is used as value, (2) participates in inheritance,
or (3) defines operators other than `invoke` and `of`.

We should acknowledge that removing the companion object is
a _binary breaking change_, and library authors should act accordingly.
If their goal is to re-expose the same functionality from companion objects
as companion blocks, the best solution is to use `@JvmStatic` (and similar
annotations in other platforms). Another solution that improves on problem #4
is to manually keep both versions, but that has additional boilerplate.

```kotlin
class Example {
    companion {
        fun foo() { ... }
    }

    companion object {
        fun foo() = Example.foo()  // resolves to the companion block
    }
}
```

If breaking binary compatibility is not a concern and none of the use cases at
the beginning of this section is required, migration to companion blocks is
advisable. We _recommend_ beginning by migrating extensions to the companion
object to companion extensions, and then dropping `object` to turn into a
companion block. That way we have working code on each stage.

## What this proposal means for the JVM

JVM remains one of the most important targets for Kotlin, and in the _problems_
section we discussed issues with code generation and interoperability on that
platform. In this section we discuss how the entire proposal "translates" to
the JVM side of things.

Companion blocks translate to **static members**. Since this mapping works well
in both directions, this solves problems #2 (mapping to platform statics) and 
#5 (expect/actual matching). 

Using static members alleviate the need for an object allocation, solving
problem #4. However, in order to have good performance, we need a way to
prevent re-computation of values; for that reason the proposal allows for
properties in companion blocks and companion extensions to have **backing
fields and initializers**.

On the topic of initialization, this proposal exposes a much narrower notion
than static initialization in Java. In particular, there is **no `init` block
for companion blocks**; only initializers in properties therein. This allows
us to accommodate per-property semantics of initialization, as described in
the corresponding section.

## Acknowledgements

We owe special thanks to the following members of the Kotlin community 
who kindly reviewed preliminary iterations of this proposal.

* Ivan "CLOVIS" Canet
* Dmitry Kandalov
* Amanda Hinchman-Dominguez
* Jake Wharton

We would also like to thank all the members of the community that have
participated in our usability study, and to Natalia Mishina, who conducted
the study and provided the findings in a digestible and actionable manner.

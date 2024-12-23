# Nested (non-capturing) type aliases

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Ivan Kochurkin
* **Discussion**: [KEEP-406](https://github.com/Kotlin/KEEP/issues/406)
* **Status**: In discussion
* **Related YouTrack issue**: [KT-45285](https://youtrack.jetbrains.com/issue/KT-45285/Support-nested-and-local-type-aliases)

## Abstract

Right now type aliases can only be used at the top level. The goal of this document is to propose a design to allow them within other classifiers, in case they do not capture any type parameters of the enclosing declaration.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
* [Proposed solution](#proposed-solution)
    * [Reflection](#reflection)
    * [Multiplatform](#multiplatform)

## Motivation

[Type aliases](https://github.com/Kotlin/KEEP/blob/master/proposals/type-aliases.md) can simplify understanding and maintaining code. For example, we can give a domain-related name to a more "standard" type,

```kotlin
typealias Context = Map<TypeVariable, Type>
```

As opposed to value classes, type aliases are "transparent" to the compiler, so any functionality available through `Map` is also available through `Context`.

Currently, type aliases may only be declared at the top level. This hinders their potential, since type aliases may be very useful in a private part of the implementation; so forcing to introduce the type alias at the top level pollutes the corresponding package. This document aims to rectify this situation, by providing a set of rules for type aliasing within other declarations.

```kotlin
class Dijkstra {
    typealias VisitedNodes = Set<Node>

    private fun step(visited: VisitedNodes, ...) = ...
}
```

One additional difficulty when type aliases are nested come from the potential **capture** of type parameters from the enclosing type. Consider the following example:

```kotlin
class Graph<Node> {
    typealias Path = List<Node>  // ⚠️ not supported
}
```

Here the type alias `Path` refers to `Node`, a type parameter of `Graph`. In a similar fashion to variables mentioned within local functions, we say that the `Path` type alias _captures_ the `Node` parameter. In this KEEP we only introduce support for **non-capturing** type aliases. Note that in most cases the captured parameter can be "extracted" as an additional parameter to the type alias itself.

```kotlin
class Graph<Node> {
    typealias Path<Node> = List<Node>
}
```

As a consequence of this non-capturing design, type aliases to [inner](https://kotlinlang.org/spec/declarations.html#nested-and-inner-classifiers) classifiers must be restricted.

Going even further than capture, it is a **non-goal** of this KEEP to provide abstraction capabilities over type aliases, like [abstract type members](https://docs.scala-lang.org/tour/abstract-type-members.html) in Scala or [associated type synonyms](https://wiki.haskell.org/GHC/Type_families) in Haskell. Roughly speaking, this would entail declaring a type alias without its right-hand side in an interface or abstract class, and "overriding" it in an implementing class.

```kotlin
interface Collection {
    typealias Element
}

interface List<T>: Collection {
    typealias Element = T
}

interface IntArray: Collection {
    typealias Element = Int
}
```

> [!NOTE]
> This KEEP supersedes the original [type alias KEEP](https://github.com/Kotlin/KEEP/blob/master/proposals/type-aliases.md) on the matter of nested type aliases.

## Proposed solution

We need to care about two separate axes for nested type aliases.

- **Visibility**: we should guarantee that type aliases do not expose types to a broader scope than originally intended.
- **Capturing**: we should guarantee that type parameters of the enclosing type never leak, even when they are implicitly referenced.

As a general _design principle_, nested type aliases should behave similarly to nested classes. This principle also allows freely exchanging classsifiers and type aliases in the source code, a helpful property for refactoring and library evolution.

**Rule 1 (nested type aliases are type aliases)**: nested type aliased must conform to the same [rules of non-nested type aliases](https://github.com/Kotlin/KEEP/blob/master/proposals/type-aliases.md), including rules on well-formedness and recursion.

**Rule 2 (scope)**: nested type aliases live in the same scope as nested classifiers.

- In particular, type aliases cannot be overriden in child classes. Creating a new type alias with the same name as in a parent class merely _hides_ that from the parent.

It is **not** allowed to define local type aliases, that is, to define them in bodies (including functions, properties, initializers, `init` blocks).

**Rule 3 (visibility)**: the visibility of a type alias must be equal to or weaker than the visibility of every type present on its right-hand side. Type parameters mentioned in the right-hand side should not be accounted.

```kotlin
class Service {
    internal class Info { }

    // wrong: public typealias mentions internal class
    typealias One = List<Info>

    // ok: private typealias mentions only public and internal classes
    private typealias Two = Map<String, Info>
}
```

**Rule 4 (non-capturing)**: nested type aliases may _not_ capture type parameters of the enclosing classifier.

> [!TIP]
> As a rule of thumb, a nested type alias is correct if it could be used as the supertype or a parameter type within a nested class living within the same classifier.

We formally define the set of captured type parameters of a type `T` with enclosing parameters `P`, `capture(T, P)`, as follows.

- If `T` is a type parameter, `capture(T, P) = { T }`;
- If `T` is a nested type access `A.B`, `capture(T, P) = capture(B, capture(A, P))`;
- If `T` is an inner type `I<A, ..., Z>`, `capture(T, P) = capture(A, P) + ... + capture(Z, P) + P`;
- If `T` is of the form `C<A, ..., Z>`, with `C` not inner, or `(A, ..., Y) -> Z`, `capture(T, P) = capture(A, P) + ... + capture(Z, P)`;
- If `T` is a nullable type `R?`, `capture(T, P) = capture(R, P)`;
- If `T` is `*`, then `capture(*, P) = { }`;
- Any other [kinds of types](https://kotlinlang.org/spec/type-system.html#type-kinds) in the Kotlin type system are not denotable, as thus may not appear as the right hand side of a type alias.

For a generic nested type alias declaration,

```kotlin
class Outer<O1, ..., On> {
    typealias Alias<T1, ... Tm> = Rhs
}
```

we first compute `capture(Rhs, { O1, .. On })`. The type alias is correct if the result of that computation is a subset of the set of type parameters of the type alias itself, `{ T1, ..., Tm }`.

The following nested type aliases exemplify this calculation.

```kotlin
class Example<T> {
    // capture(List<Int>, { T }) = { } ⊆ { } => OK
    typealias Foo = List<Int>

    // capture(List<T>, { T }) = { T } ⊈ { } => not allowed
    typealias Bar = List<T>

    // capture(List<A>, { T }) = { A } ⊆ { A } => OK
    typealias Baz<A> = List<A>

    // capture(Map<T, A>, { T }) = { T, A } ⊈ { A } => not allowed
    typealias Qux<A> = Map<T, A>


    inner class Inner<A> { }

    // capture(Inner<Int>, { T })
    // = capture(Int, { T }) + { T }
    // = { T } ⊈ { } => not allowed
    typealias Moo = Inner<Int>

    // capture(Example<S>.Inner<Int>, { T })
    // = capture(Inner<Int>, capture(Example<S>, { T }))
    // = capture(Inner<Int>, { S })
    // = capture(Int) + { S } = { S } ⊆ { S } => OK
    typealias Boo<S> = Example<S>.Inner
}
```

**Rule 5 (type aliases to inner classes)**: whenever a type alias to an inner class, a "type alias constructor" with an extension receiver should be generated, according to the [corresponding specification](https://github.com/Kotlin/KEEP/blob/master/proposals/type-aliases.md#type-alias-constructors-for-inner-classes). This constructor should be generated in the **static** scope for nested type aliases.

```kotlin
// declaration.kt
class A {
    inner class B { }

    typealias I = B
    // generates the following "type alias constructor"
    // here "static" is pseudo-syntax only
    static fun A.I() = A.B()
}

class C {
    typealias D = A.B
    // generates the following "type alias constructor"
    // here "static" is pseudo-syntax only
    static fun A.D() = A.B()
}

// incorrectUsage.kt
val i = A().I()    // ⚠️ `I` lives in the static scope of `A`
val d = A().C.D()  // ⚠️ cannot use `C.D()` to refer to a function

// correctUsage.kt
import A.*  // imports `I`
import C.*  // imports `D`

val d = A().D()
```

The example above highlights the (maybe surprising) consequence that you cannot use `A().I()` without additional imports, even though those are not required for `A().B()`.

### Reflection

The main reflection capabilities in [`kotlin.reflect`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.reflect/) work with expanded types. As a result, this KEEP does not affect this part of the library.

The current version of [`kotlinx-metadata`](https://kotlinlang.org/api/kotlinx-metadata-jvm/) already supports [type aliases within any declaration](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata/-km-declaration-container/type-aliases.html). So in principle the public API is already prepared for this change.

### Multiplatform

Kotlin supports [`expect` and `actual` declarations](https://kotlinlang.org/docs/multiplatform-expect-actual.html) for Multiplatform development.

For top-level declarations, it is forbidden to create a `expect typealias`, but it is allowed to actualize an `expect class` with an `actual typealias`.

We propose to completely forbid nested type aliases to take part on the actualization process. That means that:

- The prohibition about `expect typealias` also covers nested type aliases.
- It is not possible to actualize a nested class with a nested type alias.

Note that this restriction needs to be checked whenever a top-level `expect` class is actualized by a type alias.

```kotlin
// expect.kt
expect class E {
  class I
}

// actualIncorrect.kt
class A {
  typealias I = Int
}

actual typealias E = A  // actualizing nested 'expect class' with typealias not allowed

// actualCorrect.kt
class B {
  class I
}

actual typealias E = B  // ok
```

Note that in this case actualizing expected nested classes with type aliases allows breaking the assumption that the nested classes actually "lives" within the outer class. In the example above, it may end up being the case that `E.I` (a nested class) is actually `Int`.

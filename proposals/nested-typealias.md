# Nested (and inner) type aliases

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: 
* **Discussion**: 
* **Status**: 
* **Related YouTrack issue**: [KT-45285](https://youtrack.jetbrains.com/issue/KT-45285/Support-nested-and-local-type-aliases)

## Abstract

Right now type aliases can only be used at the top level. The goal of this document is to propose a design to allow them within other classifiers.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
* [Proposed solution](#proposed-solution)
    * [Reflection](#reflection)
* [Design questions](#design-questions)

## Motivation

Type aliases can simplify understanding and maintaining code. For example, we can give a domain-related name to a more "standard" type,

```kotlin
typealias Context = Map<TypeVariable, Type>
```

As opposed to value classes, type aliases are "transparent" to the compiler, so any functionality available through `Map` is also available through `Context`.

Currently, type aliases may only be declared at the top level. This hinders their potential, since type aliases may be very useful in a private part of the implementation; so forcing to introduce the type alias at the top level pollutes the corresponding package. This document aims to rectify this situation, by providing a set of rules for type aliasing within other declarations.

```kotlin
class Dijkstra {
    typelias VisitedNodes = Set<Node>

    private fun step(visited: VisitedNodes, ...) = ...
}
```

It is a **non-goal** of this KEEP to provide abstraction capabilities over type aliases, like [abstract type members](https://docs.scala-lang.org/tour/abstract-type-members.html) in Scala or [associated type synonyms](https://wiki.haskell.org/GHC/Type_families) in Haskell. Roughly speaking, this would entail declaring a type alias without its right-hand side in an interface or abstract class, and "overriding" it in an implementing class.

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

## Proposed solution

We need to care about two separate axes for nested type aliases.

- **Visibility**: we should guarantee that type aliases do not expose types to a broader scope than originally intended.
- **Inner**: classifiers defined inside another classifier may be marked as [inner](https://kotlinlang.org/spec/declarations.html#nested-and-inner-classifiers), which means they capture the type parameters of the enclosing type. Type aliases should not break any type system guarantees.

We extend the syntax of type aliases as follows. A type alias declaration marked with the `inner` keyword is said to be _inner_, otherwise we refer to it as _nested_.

```
[modifiers] ['inner'] 'typealias' simpleIdentifier [{typeParameters}] '=' type
```

**Rule 1 (scope)**: nested type aliases live in the same scope as nested classifiers and inner type aliases live in the same scope as inner classifiers.

- In particular, type aliases cannot be overriden in child classes. Creating a new type alias with the same name as in a parent class merely _hides_ that from the parent.

**Rule 2 (visibility)**: the visibility of a type alias must be equal to or weaker than the visibility of every type present on its right-hand side. Type parameters mentioned in the right-hand side should not be accounted.

```kotlin
class Service {
    internal class Info { }

    // wrong: public typealias mentions internal class
    typealias One = List<Info>

    // ok: private typealias mentions only public and internal classes
    private typealias Two = Map<String, Info>
}
```

**Rule 3 (type parameters)**: nested type aliases may _not_ refer to the type parameters of the enclosing classifier. Inner type aliases may do. In both cases type parameters may be included as part of the type alias declaration itself.

```kotlin
class Example<T> {
    // this alias is not allowed to capture `T`
    typealias Foo = List<Int>

    // these aliases capture the type parameter `T`
    inner typealias Bar = List<T>
    inner typealias Qux<A> = Map<T, A>

    // inner type aliases may, but need not capture
    inner typealias Moo = Int
}
```

We refer to nested and inner typealiases in the same way that we do with other nested and inner classifiers.

```kotlin
fun example1(
  foo: Example.Foo,
  bar: Example<String>.Bar,
  qux: Example<String>.Qux<Int>,
  moo: Example<String>.Moo,
)
```

**Rule 4 (inner classes)**: type aliases which refer to inner classes within the same classifier and do not prefix it with a path must be marked as `inner`. The reasoning behind it is that inner classes implicitly capture the type parameters of the outer class.

- Note that it is possible to create a nested (not inner!) type alias when a full type is given.

```kotlin
class Example<T> {
  inner class Inner

  inner typealias One = Inner
  typealias Two = Example<Int>.Inner
}

fun example2(
  inner: Example<String>.Inner,
  one: Example<String>.One,
  two: Example.Two, // expands to Example<Int>.Inner
)
```

### Reflection

The main reflection capabilities in [`kotlin.reflect`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.reflect/) work with expanded types. As a result, this KEEP does not affect this part of the library.

The current version of [`kotlinx-metadata`](https://kotlinlang.org/api/kotlinx-metadata-jvm/) already supports [type aliases within any declaration](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata/-km-declaration-container/type-aliases.html). So in principle the public API is already prepared for this change.

## Design questions

**Expect / actual**: should we allow nested `expect` (and correspondingly, `actual`) type aliases?

- This is very close to abstracting over types, which is a non-goal of this KEEP.
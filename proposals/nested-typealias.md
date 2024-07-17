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

It is a **non-goal** of this KEEP to provide abstraction capabilities over type aliases, like [abstract type members](https://docs.scala-lang.org/tour/abstract-type-members.html) in Scala or [associated type synonyms](https://wiki.haskell.org/GHC/Type_families) in Haskell. Roughly speaking, this would entail declaring a type alias without its left-hand side in an interface or abstract class, and "overriding" it in an implementing class.

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
- **Inner**: classifiers defined inside another classifier may be marked as [inner](https://kotlinlang.org/spec/declarations.html#nested-and-inner-classifiers), which requires an instance of the outer type to be in the context. Once again, nested type aliases should not break those guarantees.

We extend the syntax of type aliases as follows. A type alias declaration marked with the `inner` keyword is said to be _inner_, otherwise we refer to it as _nested_.

```
[modifiers] ['inner'] 'typealias' simpleIdentifier [{typeParameters}] '=' type
```

**Rule 1 (visibility)**: the visibility of a type alias must be equal to or weaker than the visibility of every type present in its left-hand side.

```kotlin
class Outer {
    internal class Inner { }

    // wrong: public typealias mentions internal class
    typealias One = List<Inner>

    // ok: private typealias mentions only public and internal classes
    private typealias Two = Map<String, Inner>
}
```

**Rule 2 (inner)**: type aliases referring to inner classes must be marked as `inner`.

- Inner type aliases may also refer only to non-inner classes.

```kotlin
class Big {
  class Nested { }
  inner class Inner { }

  typealias A = Nested  // ok
  typealias B = Inner   // wrong
  inner typealias C = Nested  // ok
  inner typealias D = Inner   // ok
}
```

## Design questions

**Question 1 (inner on other values)**: should we allow inner type aliases to depend not only on the outer instance but also on other properties?

```kotlin
class Foo(val big: Big) {  // 'Big' as above
  inner typealias X = big.Inner
}
```

One important question here is what is the semantics of such a definition:

- Is the left-hand side chosen during initialization?
- Should we ensure that the type alias is somehow "stable"? If so, what are the rules for such stable references to types?

**Question 2 (expect / actual)**: should we allow nested `expect` (and correspondingly, `actual`) type aliases?

- This is very close to abstracting over types, which is a non-goal of this KEEP.
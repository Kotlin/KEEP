# Resolution of links to extensions

* **Type**: KDoc proposal
* **Author**: Oleg Yukhnevich
* **Status**: In progress
* **Target issue**: [dokka/3555](https://github.com/Kotlin/dokka/issues/3555)
* **Discussion**: [KEEP-385](https://github.com/Kotlin/KEEP/issues/385)

## Summary

Define clear logic on how to resolve links to extensions when writing documentation for Kotlin code.

## Motivation

During the migration of Dokka analysis to K2, several questions arose around KDoc links resolution.
In particular, it was unclear how a link to an extension should be resolved in the presence of type parameters.
Link resolution in KDoc is not fully specified, and for this reason some cases are currently implemented
differently in K1 and K2 analysis.

The goal of this document is to try to describe consistent rules on how KDoc links to extensions should be resolved.

```kotlin
/**
 * resolved with both K1 and K2 analysis
 * - [Iterable.map]      (fun <T, R> Iterable<T>.map)
 * - [Iterable.flatMap]  (fun <T, R> Iterable<T>.flatMap)
 * - [Iterable.flatten]  (fun <T> Iterable<Iterable<T>>.flatten)
 * - [Iterable.min]      (fun <T : Comparable<T>> Iterable<T>.min)
 * resolved with K1, UNRESOLVED with K2 analysis
 * - [List.map]
 * - [List.flatMap]
 * UNRESOLVED with both K1 and K2 analysis
 * - [List.flatten]
 * - [List.min]
 */
fun test() {}
```

## Overview

### Syntax for links to extensions

To refer to an extension function/property, it's possible to use one of the following syntax constructs:

* `[extensionName]` - like with top-level functions or members in class scope
* `[Type.extensionName]` where `Type` is a receiver - like with member functions outside class scope

```kotlin
fun String.extension() {}

/**
 * [extension] - resolved
 * [String.extension] - resolved
 */
fun testStringExtension() {}
```

> Note: the behaviour for links to extension properties is the same as for links to extension functions and while a
> document will mostly use `function` in text, it could be treated as `function/property`.

In case there are multiple extensions with the same name in the same package but different receiver, it's possible to
refer to a specific extension via `[Type.extensionName]` (this is a specific case of links to overloaded declarations:
[KT-15984](https://youtrack.jetbrains.com/issue/KT-15984/Quick-documentation-Kdoc-doesnt-support-specifying-a-particular-overloaded-function-or-variable-in-a-link),
[dokka/80](https://github.com/Kotlin/dokka/issues/80)).
Additionally, it could be used to represent a link in a KDoc more naturally,
so that the links to extensions will look in the same way as links to members.
Support for resolution of links to extensions functions via `[Type.extensionName]` was tracked in scope
of [KT-13299](https://youtrack.jetbrains.com/issue/KT-13299) (most likely).

Simple example for such an ambiguity is from kotlinx-coroutines `CoroutineScope.isActive` and
`CoroutineContext.isActive`:

```kotlin
fun CoroutineScope.isActive() {}
fun CoroutineContext.isActive() {}

/**
 * [isActive] - resolved, (IDE redirects to first call)
 * [CoroutineScope.isActive] - resolved
 * [CoroutineContext.isActive] - resolved
 */
fun testCoroutines() {}
```

### Multiple ways to refer to the same declaration

Links to member functions outside class scope are resolved only via `[Type.extensionName]` where `Type` could be both
type where the function is declared, and all it’s inheritors:

```kotlin
interface Parent {
    fun member()
}
interface Child : Parent

/**
 * [member] - UNRESOLVED
 * [Parent.member] - resolved
 * [Child.member] - resolved
 * [String.member] - UNRESOLVED
 */
fun testMember() {}
```

As with links to member functions, it’s possible to refer to an extension not only on `Type` declared as receiver in
extensions, but also on its inheritor, f.e:

```kotlin
interface Parent
interface Child : Parent

fun Parent.extension() {}

/**
 * [extension] - resolved
 * [Parent.extension] - resolved
 * [Child.extension] - resolved
 * [String.extension] - UNRESOLVED
 */
fun testExtension() {}
```

Links to extensions where the receiver is generic follow the same rules. In this case, implicit bound is `Any?`:

```kotlin
interface Parent
interface Child : Parent

fun <T> T.genericExtension() {}

/**
 * [genericExtension] - resolved
 * [Parent.genericExtension] - resolved
 * [Child.genericExtension] - resolved
 * [String.genericExtension] - resolved
 */
fun testGenericExtension() {}
```

It’s also possible to define explicit bound `Type` for type parameter.
In this case extensions will be resolved on all types which match this bound:

```kotlin
interface Parent
interface Child : Parent

fun <T : Parent> T.genericBoundExtension() {}

/**
 * [genericBoundExtension] - resolved
 * [Parent.genericBoundExtension] - resolved
 * [Child.genericBoundExtension] - resolved
 * [String.genericBoundExtension] - UNRESOLVED
 */
fun testGenericBoundExtension() {}
```

All of this causes the following links to be resolved:

```kotlin
interface Parent
interface Child : Parent

fun Any.anyExtension() {}

/**
 * [anyExtension] - resolved
 * [Any.anyExtension] - resolved
 * [Parent.anyExtension] - resolved
 * [Child.anyExtension] - resolved
 * [String.anyExtension] - resolved
 */
fun testAnyExtension() {}

///////////////////////////////

// `let` is from stdlib
// inline fun <T, R> T.let(block: (T) -> R): R

/**
 * [let] - resolved
 * [Any.let] - resolved
 * [Parent.let] - resolved
 * [Child.let] - resolved
 * [String.let] - resolved
 */
fun testLet() {}
```

## Problem description

As stated at the beginning of the document, on current moment links to extensions where receiver has type parameters,
such as `List<T>` or `KSerializer<T>`, works differently in K1 and K2 as there is no clear specification on how it
should work.

To illustrate the problem, the following examples will use those shared declarations:

```kotlin
// simple root interface with type parameter: List, Deferred, KSerializer, etc.
interface Container<A> {
    fun containerMember(): A
}

// intermediate interface (which can contain other methods): MutableList, CompletableDeferred, etc.
interface TContainer<B> : Container<B>

// interface with bound for type parameter: custom KSerializer which could encapsulate serialization logic for numbers
abstract class TNumberBoundContainer<C : Number> : Container<C>

// final implementations with fixed type parameter 
class NumberContainer : Container<Number>
object IntContainer : Container<Int>
object StringContainer : Container<String>
```

There is no difference when referring to member functions from types with or without type parameters as well as for
extensions with star-projection:

```kotlin
// shared declarations, copied, for sample completeness
interface Container<A> {
    fun containerMember(): A
}
interface TContainer<B> : Container<B>
abstract class TNumberBoundContainer<C : Number> : Container<C>
class NumberContainer : Container<Number>
object IntContainer : Container<Int>
object StringContainer : Container<String>

/**
 * [Container.containerMember] - resolved
 * [TContainer.containerMember] - resolved
 * [TNumberBoundContainer.containerMember] - resolved
 * [NumberContainer.containerMember] - resolved
 * [IntContainer.containerMember] - resolved
 * [StringContainer.containerMember] - resolved
 */
fun testContainerMember() {}

///////////////////////////////

fun Container<*>.containerExtension() {}

/**
 * [Container.containerExtension] - resolved
 * [TContainer.containerExtension] - resolved
 * [TNumberBoundContainer.containerExtension] - resolved
 * [NumberContainer.containerExtension] - resolved
 * [IntContainer.containerExtension] - resolved
 * [StringContainer.containerExtension] - resolved
 */
fun testContainerExtension() {}
```

In the case of extensions with receivers with fixed type parameters,
we have several unexpected unresolved links, both with K1 and K2:

```kotlin
// shared declarations, copied, for sample completeness
interface Container<A> {
    fun containerMember(): A
}
interface TContainer<B> : Container<B>
abstract class TNumberBoundContainer<C : Number> : Container<C>
class NumberContainer : Container<Number>
object IntContainer : Container<Int>
object StringContainer : Container<String>

// type parameter is fixed to the final class
fun Container<Int>.containerIntExtension() {}

/**
 * [Container.containerIntExtension] - resolved
 * [TContainer.containerIntExtension] - UNRESOLVED - UNEXPECTED
 * [TNumberBoundContainer.containerIntExtension] - UNRESOLVED - UNEXPECTED
 * [NumberContainer.containerIntExtension] - UNRESOLVED - expected
 * [IntContainer.containerIntExtension] - resolved
 * [StringContainer.containerIntExtension] - UNRESOLVED - expected
 */
fun testContainerIntExtension() {}

// type parameter is fixed to an abstract class
fun Container<Number>.containerFixedNumberExtension() {}

/**
 * [Container.containerFixedNumberExtension] - resolved
 * [TContainer.containerFixedNumberExtension] - UNRESOLVED - UNEXPECTED
 * [TNumberBoundContainer.containerFixedNumberExtension] - UNRESOLVED - UNEXPECTED
 * [NumberContainer.containerFixedNumberExtension] - resolved
 * [IntContainer.containerFixedNumberExtension] - UNRESOLVED - expected
 * [StringContainer.containerFixedNumberExtension] - UNRESOLVED - expected
 */
fun testContainerFixedNumberExtension() {}

// type parameter is out projection of an abstract class
fun Container<out Number>.containerOutNumberExtension() {}

/**
 * [Container.containerOutNumberExtension] - resolved
 * [TContainer.containerOutNumberExtension] - UNRESOLVED - UNEXPECTED
 * [TNumberBoundContainer.containerOutNumberExtension] - resolved
 * [NumberContainer.containerOutNumberExtension] - resolved
 * [IntContainer.containerOutNumberExtension] - resolved
 * [StringContainer.containerOutNumberExtension] - UNRESOLVED - expected
 */
fun testContainerOutNumberExtension() {}
```

We can’t resolve `[TContainer.containerIntExtension]` even if `TContainer` just extends `Container` propagating `T`
without any additional constraints. The same is applied for a case with bounded type parameter
(`TNumberBoundContainer`).  
Both those cases can be successfully resolved by compiler, and those functions could be called on corresponding
receivers.

Initially it was not possible to refer to functions which has receiver with bound type parameters in a such way
(at least on IDEA side), it was fixed for K1
in [https://youtrack.jetbrains.com/issue/KTIJ-24576](https://youtrack.jetbrains.com/issue/KTIJ-24576).  
In K2 on the current moment the simplest solution is implemented, where only receiver of extension function is resolved
as `Type`:

```kotlin
// shared declarations, copied, for sample completeness
interface Container<A> {
    fun containerMember(): A
}
interface TContainer<B> : Container<B>
abstract class TNumberBoundContainer<C : Number> : Container<C>
class NumberContainer : Container<Number>
object IntContainer : Container<Int>

fun <T> Container<T>.containerGenericExtension() {}

/**
 * [Container.containerGenericExtension] - resolved
 * [TContainer.containerGenericExtension] - UNRESOLVED ONLY in K2
 * [TNumberBoundContainer.containerGenericExtension] - UNRESOLVED ONLY in K2
 * [NumberContainer.containerGenericExtension] - UNRESOLVED ONLY in K2
 * [IntContainer.containerGenericExtension] - UNRESOLVED ONLY in K2
 */
fun testContainerGenericExtension() {}

///////////////////////////////

fun <T : Number> Container<T>.containerGenericBoundExtension() {}

/**
 * [Container.containerGenericBoundExtension] - resolved
 * [TContainer.containerGenericBoundExtension] - UNRESOLVED - UNEXPECTED
 * [TNumberBoundContainer.containerGenericBoundExtension] - UNRESOLVED ONLY in K2
 * [NumberContainer.containerGenericBoundExtension] - UNRESOLVED ONLY in K2
 * [IntContainer.containerGenericBoundExtension] - UNRESOLVED ONLY in K2
 */
fun testContainerGenericBoundExtension() {}
```

Note: K2 compiler resolves declarations which are "UNRESOLVED ONLY in K2" when used in code.

More complex scenarios:

```kotlin
fun <T, R : Iterable<T>> R.modify(block: R.() -> Unit): R
fun <T : Comparable<T>, R : Iterable<T>> R.modifyComparable(block: R.() -> Unit): R

/**
 * [modify] - resolved with K1 and K2
 * [Iterable.modify] - resolved with K1 and K2
 * [List.modify] - resolved with K1, UNRESOLVED with K2
 */
fun testModify() {}

/**
 * [modifyComparable] - resolved with K1 and K2
 * [Iterable.modifyComparable] - UNRESOLVED with K1, resolved with K2
 * [List.modifyComparable] - UNRESOLVED with K1 and K2
 */
fun testModifyComparable() {}
```

## Proposed solution

**Resolve `[Type.extensionName]` links with any `Type` which can be used as a receiver in code (resolved by compiler).**

Links to extensions in a form of `[Type.extensionName]` should be treated in the same way as with member functions, so
it should be possible to refer to extensions defined for supertypes.
When functions have type parameters, with or without bounds, those functions which can be called on `Type` should be
resolved.

This is conformed to the behavior of current analysis with fixes of some issues with resolve mentioned with `UNEXPECTED`
comment in examples (there could be other examples not mentioned in the document), so there are no breaking changes to
how we resolve links but rather an attempt at defining the general rules.

Such resolution logic brings several benefits such as:

1. Links to members and extensions are defined in the same way for types and its inheritors;
2. Links in KDoc and function calls use the same semantics;
3. If a member is converted to an extension (and vice versa), all KDoc links will be still resolved.

As a general rule, KDoc links to extensions in a form of `[Type.extensionName]` should be resolved in all cases
when it's possible to write a [callable reference](https://kotlinlang.org/docs/reflection.html#callable-references)
in a form of `Type::extensionName`. If `Type` has type parameters, e.g `interface Container<T, C: Iterable<T>>`,
then the link should be resolved if it's possibly to substitute them with some concrete types in callable reference,
e.g `Something<Any, List<Any>>`, so that `extensionName` is resolved.

Here are two examples to illustrate this behaviour:

1. Simple case, no variance:
    ```kotlin
    interface NoVariance<T>
    interface NumberNoVariance : NoVariance<Number>
    
    fun <T> NoVariance<T>.extension() {}
    
    /**
     * [NoVariance.extension] - should be resolved
     * [NumberNoVariance.extension] - should be resolved
     */
    fun testExtension() {
        NoVariance<Any>::extension // resolved
        NoVariance<Nothing>::extension // resolved
        NoVariance<String>::extension // resolved
    
        NumberNoVariance::extension // resolved
    }
    
    fun NoVariance<String>.stringExtension() {}
    
    /**
     * [NoVariance.stringExtension] - should be resolved
     * [NumberNoVariance.stringExtension] - should be UNRESOLVED
     */
    fun testStringExtension() {
        NoVariance<Any>::stringExtension // UNRESOLVED: compiler produces 'Unresolved reference' error
        NoVariance<Nothing>::stringExtension // UNRESOLVED: compiler produces 'Unresolved reference' error
        NoVariance<String>::stringExtension // resolved
    
        NumberNoVariance::stringExtension // UNRESOLVED: compiler produces 'Unresolved reference' error
    }
    ```

2. Lists and iterables from the initial example:
    ```kotlin
    // definitions of interfaces and functions from kotlin-stdlib for example completness
    
    interface Iterable<out T>
    interface Collection<out E> : Iterable<E>
    interface List<out E> : Collection<E>
    
    fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R> = TODO()
    fun <T, R> Iterable<T>.flatMap(transform: (T) -> Iterable<R>): List<R> = TODO()
    fun <T> Iterable<Iterable<T>>.flatten(): List<T> = TODO()
    fun <T : Comparable<T>> Iterable<T>.min(): T? = TODO()
    
    /**
     * all should be resolved
     * - [Iterable.map]
     * - [Iterable.flatMap]
     * - [Iterable.flatten]
     * - [Iterable.min]
     * - [List.map]
     * - [List.flatMap]
     * - [List.flatten]
     * - [List.min]
     */
    fun test() {
        Iterable<Any>::map // resolved
        Iterable<Any>::flatMap // resolved
        Iterable<Iterable<Any>>::flatten // resolved
        Iterable<Comparable<Comparable<*>>>::min // resolved
    
        List<Any>::map // resolved
        List<Any>::flatMap // resolved
        List<Iterable<Any>>::flatten // resolved
        List<Comparable<Comparable<*>>>::min // resolved
    }
    ```

> Note: technically speaking, callable references to `map` and `flatMap` in this case will not compile.
>
> There will be an error from the compiler: Not enough information to infer type variable R.
> That's correct, as type parameters of functions cannot be specified in a generic callable reference and could be
> inferred only based on an expected type.
>
> Still, it doesn't affect call resolution, as in such cases those type parameters are not used in receiver,
> and so the compiler resolves the callable reference correctly: there is no 'Unresolved reference' error produced.
> This means that declarations should be resolved also in KDoc links.

All of the above could be described like this:

**KDoc link to an extension in a form of `[Type.extensionName]` should be resolved
if and only if call `typeInstance.extensionName(_)` is resolved by compiler where `typeInstance` is of type `Type<_>`:**

> Note: `_` here are just placeholders (a.k.a. typed hole)

```kotlin
/**
 * - [Iterable.map]     - should be resolved if `iterable.map(_)` is resolved
 * - [Iterable.flatMap] - should be resolved if `iterable.flatMap(_)` is resolved
 * - [List.map]         - should be resolved if `list.map(_)` is resolved
 * - [List.flatMap]     - should be resolved if `list.flatMap(_)` is resolved
 */
fun testExplicit(
    iterable: Iterable<Any>,
    list: List<Any>
) {
    iterable.map {} // resolved
    iterable.flatMap { listOf(0) } // resolved

    list.map {} // resolved
    list.flatMap { listOf(0) } // resolved
}
```

## Alternatives considered

There were a few alternatives considered but were rejected because of various reasons:

* Resolve links where `Type` is the same type which is used as receiver in an extension.
  If receiver is generic, resolve only `[extensionName]` syntax.
  Rejected because:
    * in the case of migration from member to extension or widening of a receiver type, old KDoc links will be broken.
      F.e:
      ```kotlin
      // version 1 of the lib
      fun String.doSomething() {}
      
      // in an app
      /**
       * [String.doSomething] - resolved
       */
      fun testSomething() {
        "".doSomething() // resolved
      }
      
      // then in version 2 of the lib
      fun CharSequence.doSomething() {}
      
      // in an app
      /**
       * [String.doSomething] - becomes unresolved
       */
      fun testSomething() {
        "".doSomething() // still resolved
      }
      ```
    * in the case of type hierarchies with extensions on supertypes, it's easy to leak an abstraction when referring to
      both members and extensions or extensions on different super types in the same KDoc block.
      F.e in the following example `[HeadersBuilder.append]` will be unresolved, and so we
      need to use `[MessageBuilder.append]` which looks inconsistent and when reading requires more attention, as
      you now need to know the full hierarchy:
      ```kotlin
      interface MessageBuilder { fun build(): String }
      interface HeadersBuilder: MessageBuilder
      
      fun MessageBuilder.append(text: String) {}
      fun HeadersBuilder.appendIfAbsent(text: String) {}
      
      /**
       * [HeadersBuilder.appendIfAbsent] and [MessageBuilder.append] can be used to configure an object
       * after that [HeadersBuilder.build] can be used to build the object
       */
      fun testHeaders(builder: HeadersBuilder) {}
      ```
* Resolve links only via `[extensionName]` and do not resolve `[Type.extensionName]` at all, so treat extension
  functions as just functions.
  Rejected because:
    * extensions in Kotlin are first-class entities, and from a call-site perspective they look like regular functions.
      So it's not convenient when in KDoc you should refer to them a lot differently than in code.
    * major breaking change with no clear benefits

## Other languages

* [Java](https://docs.oracle.com/en/java/javase/22/docs/specs/javadoc/doc-comment-spec.html):
    * No extensions
    * The semantics of referring to members are the same as in Kotlin (including inheritors)
* [Scala](https://docs.scala-lang.org/overviews/scaladoc/for-library-authors.html)
    * Extensions are defined similar to Kotlin
    * Referring to extensions is possible only by top-level `[[extensionName]]` without relying on receiver

```scala
class Point(val x: Int)

extension(p: Point)
def ext: Int = p.x

/**
 * [[Point.x]] - resolved, member
 * [[Point.ext]] - unresolved
 * [[ext]] - resolved
 */
def test(): Unit = {}
```

* [Dart](https://dart.dev/language/comments\#documentation-comments)
    * Extensions are defined in named sections
    * Referring to extensions is possible only via this named section

```dart
class DateTime {
    int get year;
}
extension DateTimeCopyWith on DateTime {
    DateTime copyWith () { ... }
}

/// [DateTime.year] - resolved, member
/// [DateTime.copyWith] - unresolved
/// [DateTimeCopyWith.copyWith] - resolved
void test () {}
```

* [Rust](https://doc.rust-lang.org/rustdoc/how-to-write-documentation.html)
    * Same as dart, but extensions are defined in traits
    * Referring to extensions is possible only via trait
    * RustRover supports resolving via the main type while `cargo doc` can’t resolve them

```rust
trait OptionExt < A > {
    fn wrap (&self) -> Option<&Self> { Some(self) }
}

impl<A> OptionExt < A > for A {}

/// [Option::is_some] - resolved, member function
/// [Option::wrap] - unresolved by `cargo doc`, resolved by RustRover
/// [`Option<String>::wrap`] - same as `Option::wrap`
/// [OptionExt::wrap] - resolved
/// [`OptionExt<String>::wrap`] - resolved
fn main () {}
```

* [Swift](https://www.swift.org/documentation/docc/)
    * Extensions are declared in `unnamed` blocks
    * Generics have different semantics/syntax for structs and protocols
    * Referring to extensions is possible in the same way as in Kotlin
    * Mostly tries to resolve those links, which can be called in code (resolved by compiler)
    * When there are generics involved results are not always predictable and `Docc` works inconsistently comparing to
      `XCode`

```swift
public protocol Container {
    associatedtype Item
}
public protocol TContainer: Container {}
public protocol TSignedIntegerContainer: Container where Item: SignedInteger {}
public struct SignedIntegerContainer < T: SignedInteger > : Container {
    public typealias Item = T
}
public struct IntContainer: Container {
    public typealias Item = Int
}
public struct IntTContainer: TContainer {
    public typealias Item = Int
}
public struct IntTSignedIntegerContainer: TSignedIntegerContainer {
    public typealias Item = Int
}
public struct StringContainer: Container {
    public typealias Item = String
}

// extensions
// kotlin analogs are:
// - fun Container<*>.extensionOnAny()
// - fun <T> Container<T>.extensionOnAny()
public extension Container {
    func extensionOnAny (){}
}
// kotlin analogs are:
// - fun Container<Int>.extensionOnInt()
public extension Container where Item == Int {
    func extensionOnInt (){}
}
// kotlin analogs are:
// - fun Container<out SignedInteger>.extensionOnSignedInteger()
// - fun <T: SignedInteger> Container<T>.extensionOnSignedInteger()
public extension Container where Item: SignedInteger {
    func extensionOnSignedInteger (){}
}
public extension Container where Item == String {
    func extensionOnString (){}
}

/// ``Container/extensionOnAny()`` - resolved
/// ``Container/extensionOnInt()`` - resolved
/// ``Container/extensionOnSignedInteger()`` - resolved
/// ``Container/extensionOnString()`` - resolved
///
/// ``TContainer/extensionOnAny()`` - unresolved in docc, resolved in XCode
/// ``TContainer/extensionOnInt()`` - unresolved
/// ``TContainer/extensionOnSignedInteger()`` - unresolved
/// ``TContainer/extensionOnString()`` - unresolved
///
/// ``TSignedIntegerContainer/extensionOnAny()`` - unresolved in docc, resolved in XCode
/// ``TSignedIntegerContainer/extensionOnInt()`` - unresolved
/// ``TSignedIntegerContainer/extensionOnSignedInteger()`` - unresolved
/// ``TSignedIntegerContainer/extensionOnString()`` - unresolved
///
/// ``SignedIntegerContainer/extensionOnAny()`` - resolved
/// ``SignedIntegerContainer/extensionOnInt()`` - unresolved in XCode, resolved in docc
/// ``SignedIntegerContainer/extensionOnSignedInteger()`` - unresolved in XCode, resolved in docc
/// ``SignedIntegerContainer/extensionOnString()`` - unresolved in XCode, resolved in docc
///
/// ``IntContainer/extensionOnAny()`` - resolved
/// ``IntContainer/extensionOnInt()`` - unresolved in XCode, resolved in docc
/// ``IntContainer/extensionOnSignedInteger()`` - unresolved in XCode, resolved in docc
/// ``IntContainer/extensionOnString()`` - unresolved
///
/// ``IntTContainer/extensionOnAny()`` - resolved
/// ``IntTContainer/extensionOnInt()`` - unresolved in XCode, resolved in docc
/// ``IntTContainer/extensionOnSignedInteger()`` - unresolved in XCode, resolved in docc
/// ``IntTContainer/extensionOnString()`` - unresolved
///
/// ``IntTSignedIntegerContainer/extensionOnAny()`` - resolved
/// ``IntTSignedIntegerContainer/extensionOnInt()`` - unresolved in XCode, resolved in docc
/// ``IntTSignedIntegerContainer/extensionOnSignedInteger()`` - unresolved in XCode, resolved in docc
/// ``IntTSignedIntegerContainer/extensionOnString()`` - unresolved
///
/// ``StringContainer/extensionOnAny()`` - resolved
/// ``StringContainer/extensionOnInt()`` - unresolved
/// ``StringContainer/extensionOnSignedInteger()`` - unresolved
/// ``StringContainer/extensionOnString()`` - unresolved in XCode, resolved in docc
public protocol Test {}
```

## Appendix

Some notes regarding links to extension functions, which are nice to know.

### Extensions on receivers with type parameters and nullability

It’s not possible to specify type parameters or nullability in KDoc links.
So it’s not possible to refer to a specific extension which differs only in receiver type parameter or nullability
(similar to overloading by function parameters):

```kotlin
/**
 * [stringExtension] - resolved
 * [String.stringExtension] - resolved
 * [String?.stringExtension] - UNRESOLVED
 */
fun testString() {}

fun String.stringExtension() {}
fun String?.stringExtension() {}

///////////////////////////////

/**
 * [listExtension] - resolved
 * [List.listExtension] - resolved
 * [List<Int>.listExtension] - UNRESOLVED
 * [List<Long>.listExtension] - UNRESOLVED
 */
fun testList() {}

fun List<Int>.listExtension() {}
fun List<Long>.listExtension() {}
```

### Extension and member with the same name

If an extension has the same name as a member, it’s possible to refer to an extension only via `[extensionName]`, as
`[Type.extensionName]` will always prefer members during link resolution:

```kotlin
interface Something {
    fun doWork()
}

fun Something.doWork(argument: String) {}

/**
 * [doWork] - resolved as extension
 * [Something.doWork] - resolved as member
 */
fun testSomething() {}
```

It's still possible to distinguish links to member and extensions in a form of `[Type.extensionName]` using import
aliases:

```kotlin
package org.example

import org.example.doWork as doWorkExtension

interface Something {
    fun doWork()
}

fun Something.doWork(argument: String) {}

/**
 * [Something.doWorkExtension] - resolved as extension
 * [Something.doWork] - resolved as member
 */
fun testSomething() {}
```

### Absolute and relative links

It’s possible to refer to declarations both absolutely (using fully qualified names) and relatively (based on scope and
imports where the link is defined).
Though, with links to extensions with `Type` like `[Type.extensionName]` it’s possible to refer via fully qualified name
only for `Type`.
While when using `[extensionName]` fully qualified name is possible only for `extensionName`.

```kotlin
package com.example

fun String.extension()

/////////////////////

package test

// import com.example.extension

/**
 * [org.example.extension] - absolute, resolved
 * [extension] - relative, resolved if it's imported
 * [String.extension] - relative, resolved if it's imported
 * [kotlin.String.extension] - absolute for the type, relative for function, resolved if it's imported
 * [kotlin.String.com.example.extension] - UNRESOLVED
 * [com.example.String.extension] - UNRESOLVED
 */
fun test() {}
```

Similar behavior exists for extensions defined in `object` (or other scope):

```kotlin
// import Namespace.namespaceFunction
// import Namespace.namespaceExtension

object Namespace {
    fun namespaceFunction() {}
    fun String.namespaceExtension() {}
}

/**
 * [namespaceFunction] - resolved if it's imported
 * [namespaceExtension] - resolved if it's imported
 * [Namespace.namespaceFunction] - resolved
 * [Namespace.namespaceExtension] - resolved
 * [String.namespaceExtension] - resolved in K2 ONLY if it's imported
 */
fun testNamespace() {}


/**
 * [Duration.Companion.seconds] - resolved to `val ***.seconds` defined in `Duration.Companion` (overloaded by receiver)
 * [Int.seconds] // resolved to `val Int.seconds` defined in `Duration.Companion` if it's imported
 */
fun testDuration() {}
```

The most common type which is affected by this is `kotlin.time.Duration`:

```kotlin
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

/**
 * [Duration.Companion.seconds] - resolved to one of `seconds` function defined in `Duration.Companion` (multiple overloads by receiver)
 * [Int.seconds] // resolved to `val Int.seconds` defined in `Duration.Companion` if it's imported
 */
fun testDuration() {}
```

Rules for the link resolution could be described like this:

* `[FUNCTION]` / `[PROPERTY]` / `[CLASS]`
    * relative links to **top-level** declarations (including extensions)
    * relative links to **imported** declarations (f.e from `companion object`)
* `[PACKAGE.FUNCTION]` / `[PACKAGE.PROPERTY]` / `[PACKAGE.CLASS]` - absolute links to top level declarations
  (including extensions)
* `[CLASS.CLASS]` - relative links to inner classes
* `[PACKAGE.CLASS.CLASS]` - absolute links to inner classes
* `[CLASS.FUNCTION]` / `[CLASS.PROPERTY]`:
    * relative links to **members** defined in CLASS (or its inheritors)
    * relative links to **extensions** defined for CLASS (or its inheritors)
* `[PACKAGE.CLASS.FUNCTION]` / `[PACKAGE.CLASS.PROPERTY]`:
    * absolute links to **members** defined in PACKAGE.CLASS (or its inheritors)
    * **partially** absolute links to **extensions** defined for PACKAGE.CLASS (or its inheritors).  
      Partially means that we can specify PACKAGE for CLASS, but if FUNCTION is from different package, this package
      should be imported

### Context parameters

With the introduction
of [Context parameters](https://github.com/Kotlin/KEEP/blob/context-parameters/proposals/context-parameters.md) in case
we do nothing, the situation will not change and `context` will not affect how links will be resolved.
If we treat `context parameters` (at least initially) as just other function parameters then it falls into the “function
parameters overload” problem mentioned in the begging of the document and will be discussed separately.

```kotlin
fun function() {}
fun Scope.extension() {}

context(scope: Scope) fun contextFunction() {}
context(scope: Scope, otherScope: OtherScope) fun contextFunction() {}
context(otherScope: OtherScope) fun contextFunction() {}

context(scope: Scope) fun String.contextExtension() {}

/**
 * [function] - resolved
 * [extension] - resolved
 * [String.extension] - resolved
 *
 * No overload by context parameters
 * [contextFunction] - resolved
 * [contextExtension] - resolved
 * [String.contextExtension] - resolved
 *
 * With an imaginary syntax of overload by context parameters
 * [(Scope) contextFunction]
 * [(Scope) String.contextExtension]
 * or
 * [context(Scope) contextFunction]
 * [context(Scope) String.contextExtension]
 */
fun testContext() {}
```

Even if we introduce the possibility of overload by context parameters, most likely it will not affect how links to
extensions will be resolved.
Context parameters are just an additional set of parameters to perform overload additionally to functions parameters.
It’s out of scope of this document and should be discussed in the scope of support for referring to specific overloads.

# Resolution of links to extensions

* **Type**: KDoc proposal
* **Author**: Oleg Yukhnevich
* **Status**: Submitted
* **Target issue**: [dokka/3555](https://github.com/Kotlin/dokka/issues/3555)
* **Discussion**: TBD

## Summary

Define clear logic on how to resolve links to extensions when writing documentation for Kotlin code.

## Motivation

References to extensions where receiver has type parameters, such as `List<T>` or `KSerializer<T>` currently works
differently in K1 comparing to K2 as described in [dokka/3555](https://github.com/Kotlin/dokka/issues/3555).

```kotlin
/**
 * resolved with both K1 and K2 analysis (all extensions are defined on [Iterable])
 * - [Iterable.map]
 * - [Iterable.flatMap]
 * - [Iterable.flatten]
 * - [Iterable.min]
 * resolved with K1, unresolved with K2 analysis ([List] implements [Iterable])
 * - [List.map]
 * - [List.flatMap]
 * unresolved with both K1 and K2 analysis
 * - [List.flatten] (defined as `fun <T> Iterable<Iterable<T>>.flatten()`)
 * - [List.min]     (defined as `fun <T : Comparable<T>> Iterable<T>.min()`)
 */
fun testLists() {}
```

## Overview

### Syntax for references to extensions

References to extension functions/properties can be done in two ways: via simple reference like `[functionName]` or with
additional receiver `Type` like `[Type.functionName]`. The behaviour for extension properties is the same as for
extension functions and while a document will mostly use `function` wording, it could be treated as `function/property`.

```kotlin
fun String.extension() {}

/**
 * [extension] - resolved
 * [String.extension] - resolved
 */
fun testStringExtension() {}
```

Extension function can be referenced in the same way as just top level function (`[functionName]`), but, there are cases
when there are multiple extensions with the same name in the same package and so in this case `[Type.functionName]` can
be used to resolve reference to specific function (specific case of
overloads: [KT-15984](https://youtrack.jetbrains.com/issue/KT-15984/Quick-documentation-Kdoc-doesnt-support-specifying-a-particular-overloaded-function-or-variable-in-a-link), [dokka/80](https://github.com/Kotlin/dokka/issues/80)).
It can also be used to represent reference in comment more naturally so that reference to extensions will look the same
way as reference to members. References to extensions functions via `[Type.functionName]` was tracked (most likely)
in [KT-13299](https://youtrack.jetbrains.com/issue/KT-13299).

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

### Multiple ways to reference the same declaration

References to member functions are resolved only via `Type.functionName` where `Type` could be both type where the
function is declared, and all it’s inheritors:

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

As with references to member functions, it’s possible to reference extensions not only on `Type` declared as receiver in
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

References to extensions where receiver is generic work in the same way. In this case, implicit bound is `Any?`:

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

It’s also possible to define explicit bound `Type` for generic. In this case extensions will be resolved on all types
which are matched by generic:

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

As stated in the beginning, on current moment references to extensions where receiver has type parameters, such as
`List<T>` or `KSerializer<T>` works differently in K1 comparing to K2 as there is no clear specification on how it
should work.

The following examples will use those shared declarations:

```kotlin
// simple root interface with generic: List, Deferred, KSerializer, etc.
interface Container<A> {
    fun containerMember(): A
}

// intermediate interface (which can contain other methods): MutableList, CompletableDeferred, etc.
interface TContainer<B> : Container<B>

// interface with added bound for generic: custom KSerializer which could encapsulate serialization logic for numbers
abstract class TNumberBoundContainer<C : Number> : Container<C>

// final implementations with fixed generic 
class NumberContainer : Container<Number>
object IntContainer : Container<Int>
object StringContainer : Container<String>
```

There is no difference when referencing member functions from types with or without type parameters as well as for
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

In the case of extensions with fixed generics, we have several unexpected unresolved references, both with K1 and K2:

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

// type parameter is fixed to final class
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

// type parameter is fixed to abstract class
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

// type parameter is out projection of abstract class
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

We can’t resolve `TContainer.containerIntExtension` even if `TContainer` just extends `Container` propagating `T`
without any additional constraints. The same is applied for a case with bounded generic (`TNumberBoundContainer`).  
Both those cases can be successfully resolved by compiler, and those functions could be called on corresponding
receivers.

Initially it was not possible to reference in such a way functions which has generic with bound type arguments (at least
on IDEA side), it was fixed for K1
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

Note: K2 compiler resolves declarations which are unresolved only in K2 when used in code.

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

**Resolve references for any `Type` which can be used as a receiver in code (resolved by compiler).**

References to extensions in a form of `Type.functionName` should be treated in the same way as with member functions, so
it should be possible to reference extensions defined for supertypes. When functions have generics or bounds, those
functions which can be called on `Type` should be resolved.

This is conformed to the behavior of current analysis with fixes of some issues with resolve mentioned with `UNEXPECTED`
in examples (there could be other examples not mentioned in the document), so there are no breaking changes but rather
an attempt at defining the general rules.

Such resolution logic brings several benefits such as:

1. References to members and extensions are defined in the same way for types and its inheritors;
2. References in KDoc and function calls use the same semantics;
3. If a member is converted to an extension (and vice versa), all KDoc references still will be resolved;

When thinking about type parameters matching in KDoc, it's possible to infer type constraints/requirements to better see
matching rules.
The rules in compiler are more complex than that, this is just a basic idea:

* requirements are coming from extensions:
    * for `fun <T> List<T>.something()` - generic type requirement will be `out Any?`
    * for `fun <T: Number> List<T>.somethingNumber()` - generic type requirement will be `out Number`
* constraints are coming from types:
    * for `interface List<out T>` - generic type constraint will be `out Any?`
    * for `interface Container<T> : List<T>` - generic type constraint will be `out Any?`
    * for `interface NContainer<T: Number> : List<T>` - generic type constraint will be `out Number`
    * for `interface NNContainer : List<Number>` - generic type constraint will be `out Number`
    * for `interface SContainer : List<String>` - generic type constraint will be `out String`
    * for `interface NoVariance<T>` - generic type constraint will be still `out Any?` as any type is possible
    * for `interface NNoVariance : NoVariance<Number>` - generic type constraint will be `Number` (no `out` variance)
* Matching:
    * `something` should match any subtype of `List` as it's a type constraint is `out Any?`
    * `somethingNumber` should match only when `Type` is subtype which have matching constraint `out Number`.
      So f.e `SContainer` is not available.

Coming back to the first example, with this proposal all links should be resolved:

```kotlin
/**
 * - [Iterable.map]
 * - [Iterable.flatMap]
 * - [Iterable.flatten]
 * - [Iterable.min]
 * - [List.map]
 * - [List.flatMap]
 * - [List.flatten] (defined as `fun <T> Iterable<Iterable<T>>.flatten()`)
 * - [List.min]     (defined as `fun <T : Comparable<T>> Iterable<T>.min()`)
 */
fun testLists() {}
```

## Alternatives considered

There were a few alternatives considered but were rejected because of various reasons:

* Resolve references where `Type` is the same type which is used as receiver in an extension. If receiver is
  generic, resolve only `functionName` syntax. Rejected because:
    * in case of migration from member to extension or widening of a receiver type, old KDoc references will be broken.
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
    * in case of type hierarchies with extensions on supertypes, it's easy to leak an abstraction when referencing
      links. F.e in the following example `[HeadersBuilder.append]` will be unresolved, and so we
      need to use `[MessageBuilder.append]` which looks and inconsistent and when reading requires more attention, as
      you now need to know the hierarchy:
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
* Resolve references only by `functionName` and do not resolve `Type.functionName` at all, so treat extension functions
  as just functions. Rejected because:
    * extensions in Kotlin are first-class entities, and from a call-site perspective they look like regular functions.
      So it's not convenient when in KDoc you should reference them a lot differently then in code.
    * big breaking change with no clear benefits

## Other languages

* [Java](https://docs.oracle.com/en/java/javase/22/docs/specs/javadoc/doc-comment-spec.html):
    * No extensions
    * Member references are referenced in the same way as in Kotlin (including inheritors)
* [Scala](https://docs.scala-lang.org/overviews/scaladoc/for-library-authors.html)
    * Extensions are defined similar to Kotlin
    * Referencing extensions is possible only by top-level `functionName` without relying on receiver

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
    * Extensions are defined in named `objects`
    * Extensions can be referenced only via this named object

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
    * Extensions can be referenced only via trait
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
    * Referencing extensions is possible in the same way as in Kotlin
    * Mostly tries to resolve those references, which can be called in code (resolved by compiler)
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

Some notes regarding references to extension functions, which are nice to know.

### It’s not possible to use generics or nullable types as `Type` during referencing extensions

So it’s not possible to distinguish references between such extensions (same issues as with overloading by arguments):

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

### If an extension has the same name as a member, it’s possible to reference an extension only via

`functionName` syntax

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

It's possible to overcome this on the user side via import aliases:

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

### Absolute and relative references

It’s possible to reference declarations both absolutely (with package and class name) and relatively (based on scope and
imports where declaration is defined). Though, with extension functions referenced by `Type` like `[Type.functionName]`
it’s possible to use absolute reference only for `Type`. While when using `[functionName]` absolute reference is
possible only for `functionName`.

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

Rules for the resolve of references could be described like this:

* `[FUNCTION]` / `[PROPERTY]` / `[CLASS]`
    * relative references to **top-level** declarations (including extensions)
    * relative references to **imported** declarations (f.e from `companion object`)
* `[PACKAGE.FUNCTION]` / `[PACKAGE.PROPERTY]` / `[PACKAGE.CLASS]` - absolute references to top level declarations
  (including extensions)
* `[CLASS.CLASS]` - relative references to inner classes
* `[PACKAGE.CLASS.CLASS]` - absolute references to inner classes
* `[CLASS.FUNCTION]` / `[CLASS.PROPERTY]`:
    * relative references to **members** defined in CLASS (or its inheritors)
    * relative references to **extensions** defined for CLASS (or its inheritors)
* `[PACKAGE.CLASS.FUNCTION]` / `[PACKAGE.CLASS.PROPERTY]`:
    * absolute references to **members** defined in PACKAGE.CLASS (or its inheritors)
    * **partially** absolute references to **extensions** defined for PACKAGE.CLASS (or its inheritors).  
      Partially means that we can specify PACKAGE for CLASS, but if FUNCTION is from different package, this package
      should be imported

### Context parameters

With the introduction
of [Context parameters](https://github.com/Kotlin/KEEP/blob/context-parameters/proposals/context-parameters.md) in case
we do nothing, the situation will not change and `context` will not affect how references will be resolved. If we treat
`context parameters` (at least initially) as just other function parameters then it falls into the “function parameters
overload” problem mentioned in the begging of the document and will be discussed separately.

```kotlin
fun function() {}
fun String.extension() {}

context(scope: Scope) fun contextFunction() {}
context(scope: Scope, scope2: String) fun contextFunction() {}
context(scope: String) fun contextFunction() {}

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
 * With the possibility of overload by context parameters
 * [(Scope) contextFunction]
 * [(Scope) String.contextExtension]
 * or
 * [context(Scope) contextFunction]
 * [context(Scope) String.contextExtension]
 */
fun testContext() {}
```

Even if we introduce the possibility of overload by context parameters, most likely it will not affect how references to
extension functions will be resolved. Context parameters just add a layer of reference overloads additionally
to extension functions. It’s out of scope of this document and should be discussed in the scope of support for
referencing specific overloads.

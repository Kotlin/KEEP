# Static members and type extensions

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Nikita Bobko, Roman Efremov, Pavel Kunyavskiy,
Faiz Ilham Muhammad, Dmitriy Novozhilov, Stanislav Ruban, Roman Venediktov, 
Michail Zarečenskij, Denis Zharkov
* **Discussion**: [KEEP-427](https://github.com/Kotlin/KEEP/discussions/427)
* **Status**: In review
* **Related YouTrack issues**:
  [KT-11968](https://youtrack.jetbrains.com/issue/KT-11968),
  [KT-15595](https://youtrack.jetbrains.com/issue/KT-15595),
  [KT-16872](https://youtrack.jetbrains.com/issue/KT-16872)
* **Previous related proposal**:
  [KEEP-347](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md)
* [**Prototype**](https://github.com/JetBrains/kotlin/tree/rr/serras/static-scopes)
  (uses different syntax)

## Abstract

We propose to bring the idea of _statics_ in Kotlin with _static members_ and
_type extensions_. 
The underlying idea is surfacing the notion of _static scope_ more clearly
in the language.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Abstract statics](#abstract-statics)
* [Proposal](#proposal)
  * [Ambiguity in naming](#ambiguity-in-naming)
  * [Objects have no static scope](#objects-have-no-static-scope)
  * [From companion objects to statics](#from-companion-objects-to-statics)
  * [Fake constructors](#fake-constructors)
  * [`T::type` is not a type nor a expression](#ttype-is-not-a-type-nor-a-expression)
  * [Coding conventions](#coding-conventions)
* [Technical details](#technical-details)
  * [Declaration](#declaration)
  * [(Lack of) inheritance](#lack-of-inheritance)
  * [Resolution](#resolution)
  * [Initialization](#initialization)
  * [Compilation strategy](#compilation-strategy)
  * [Reflection](#reflection)
* [Alternative design choices](#alternative-design-choices)
  * [Syntax and naming](#syntax-and-naming)
  * [Compilation strategy](#compilation-strategy-1)
  * [Previous statics proposal](#previous-statics-proposal)
  * [Extension companion objects](#extension-companion-objects)
* [Discarded ideas](#discarded-ideas)

## Motivation

Kotlin lacks a notion of _static member_ as found in other programming
languages. Static members are a good example of a concept which fits many
different roles:

- Static members have _no state_, which makes them great for utility functions.
  The usual `StringUtils` in every Java/C# project is a witness for that.
- By including static members, a class also works as a _namespace_. One nice
  example is `List.of` in Java, which fits the general pattern of factory.

Instead of providing static members, Kotlin supports those goals by using
_objects_, and marking an object nested in a class as its _companion_ to make
it accessible through the name of the class.
Alas, this design falls short in a few areas:

1. In order to create an extension callable accessible through the class name,
  you have to extend the companion object type. But if the class does not have
  such an object — it was not introduced by the developer or comes from a
  platform where no such notion exists — this becomes impossible.
  (🔗 [YouTrack issue](https://youtrack.jetbrains.com/issue/KT-11968)).
2. There is a mismatch with the concept of static member from the main
  platform Kotlin runs on (namely, the JVM). Kotlin features a `@JvmStatic`
  annotation, but its behavior is not always the preferred one. In particular,
  the compiler still keeps a "copy" of the function in the object.
  (🔗 [YouTrack issue 1](https://youtrack.jetbrains.com/issue/KT-15595),
   🔗 [YouTrack issue 2](https://youtrack.jetbrains.com/issue/KT-16872)).
3. It is not clear where some elements of the language live; the main example
  being enumeration entries.

### Abstract statics

It is a **non**-goal of this KEEP to provide any abstraction facility over
static members (see _Technical details_ for its consequence).
This is especially challenging since static members are not involved in
inheritance relations.

> [!IMPORTANT]
> This proposal supersedes [KEEP-347](https://github.com/Kotlin/KEEP/issues/347).
> The main differences are described [below](#previous-statics-proposal).

## Proposal

We propose to solve the problems mentioned above by introducing the notion of
**static scope** of a type into the language and giving more control to the
developer about in which a declaration resides. Interestingly enough, the notion
of static scope already exists in the Kotlin specification, for example when
[enumeration entries](https://kotlinlang.org/spec/declarations.html#enum-class-declaration)
are introduced; and is also part of the conceptual model in the K2 compiler.

This notion of static scope surfaces in the language in two modifiers:
`static` for members, and `::type` for extension declarations
(we dive later into the reason for choosing two different words).
As a sneak preview of the solution, this proposal makes the following code
compile, with the intuitive behavior of `static` and type extensions.
Note that at _usage_ site the calls look exactly as if the members were
defined in the companion object; the consumer need not be aware of whether
a member is defined in one way or another.

```kotlin
data class Vector(val x: Double, val y: Double) {
  static val Zero: Vector = Vector(0.0, 0.0)
}

val Vector::type.UnitX: Vector = Vector(1.0, 0.0)
val Vector::type.UnitY: Vector = Vector(0.0, 1.0)

fun print(v: Vector): String = when (v) {
  Vector.Zero -> "zero vector"
  Vector.UnitX, Vector.UnitY -> "unit vector"
  else -> "(${v.x}, ${v.y})"
}

// using context-sensitive resolution (KEEP-379)
fun print(v: Vector): String = when (v) {
  Zero -> "zero vector"
  UnitX, UnitY -> "unit vector"
  else -> "(${v.x}, ${v.y})"
}
```

As hinted above, this proposal aims to
provide a _single uniform_ perspective on the problem of statics,
so it covers members and extensions in a coherent way. 
This is done by giving the
developer more control about both the dispatch and extension receivers.

Most Kotlin developers are acquainted with the notion of _extension receiver_,
which becomes available as `this` in the body of an extension callable.

```kotlin
fun Vector.plus(other: Vector): Vector = ...
```

However, when you define a callable _within_ a classifier, `this` is not an
extension but a _dispatch receiver_.

```kotlin
data class Vector(val x: Double, val y: Double) {
  // dispatch receiver is 'Vector'
  // no extension receiver
  fun normalize(): Vector { ... }
}
```

In fact, a callable may define both a dispatch and an extension receiver.

In this proposal these two receivers are generalized to a more general notion
of **scope**. These scopes may refer to:

- The **instance or inner scope**, which means that the callable is
  available through an _instance_ of the corresponding type.
  This is the default scope for members.
- The **static scope**, which means that the callable is available
  through the _name_ of the corresponding classifier.
  This is the default scope for nested classifiers.

The names of the scopes are chosen due to its similarity with nested and inner
classes, which also correspond to accessing the class through the name of the
enclosing classifier, or through an instance thereof, respectively.

```kotlin
data class Vector(val x: Double, val y: Double) {
  // static dispatch receiver scope
  static val Zero: Vector = Vector(0.0, 0.0)

  // static dispatch receiver scope
  // inner extension receiver scope
  static val Int.asVector(): Vector = ...
}

    // static extension receiver scope
val Vector::type.UnitX: Vector = Vector(1.0, 0.0)
```

### Ambiguity in naming

Unfortunately, using the name _static_ everywhere brings a lot of ambiguity.
In particular, it becomes difficult to distinguish, from a syntactic point
of view between:

1. members of a class with a static dispatch and an inner scope as extension,
2. top-level functions (no dispatch) with static scope as extension.
  
To solve this problem, we introduce two separate terms:

- `static` is used when the _dispatch_ receiver is a static scope,
- `::type` is used when the _extension_ receiver is a static scope.

In that way, case (1) is unambiguously referred to as a _static extension_,
whereas case (2) is called a _type extension_.

All possible cases are summarized below; if you want to
emphasize that something is _not_ static, you can prefix it with `member`.
In the same way, sometimes the `Function` part is dropped.

```kotlin
class Example {
  fun function() = ...
  static fun staticFunction() = ...
  static fun Foo.staticExtensionFunction() = ...

  fun Foo::type.typeExtensionFunction() = ...
  static fun Foo::type.staticTypeExtensionFunction() = ...
}

fun Example::type.typeExtensionFunction() = ...
```

> [!IMPORTANT]
> Throughout this document we still refer to the scope used by a type
> extension as the _static scope_, not as the _type scope_.

### Objects have no static scope

We **forbid** declaring static members or extensions within objects
(including within companion objects).
The same restriction applies to type extensions: the type they
extend may not be an object.
Note that this rule agrees with the fact that you cannot declare a
companion object within another object.

The conceptual model for this restriction is that the instance and static scopes 
_coincide_ for an object. That is, you can access the same members using the
name of the type or the (singleton) instance of the object.

```kotlin
object FactorialCache {
  fun compute(n: Int): Int = ...
}

fun example() {
  // we can access 'compute' through the type name (static scope)
  val factorialOfThree = FactorialCache.compute(3)

  // but also through as a value of that type (inner scope)
  val cache = FactorialCache
  val factorialOfFive = cache.compute(5)
}
```

This model with that embodied in the specification.
When defining the
[scopes in a classifier](https://kotlinlang.org/spec/declarations.html#classifier-declaration-scopes),
the specification states:

> For an object declaration, static classifier body scope and the actual
> classifier body scoped are one and the same.

Unfortunately, we cannot change the current compilation scheme for objects
without breaking binary compatibility. We intend to investigate a better
_namespace_ feature that would allow us to compile those members as static
if available in the platform.

### From companion objects to statics

This proposal does **not deprecate companion** objects in any mode or form.
However, we expect developers to prefer statics whenever they are sufficient
for their needs, and only reach to companion objects when an instance or some
shared state is required.

We want to blur the distinction between statics and companion objects at use
site as much as possible. For that reason, we follow the general rule that
wherever you can access static members or type extensions,
you should also be able to access companion object members or extensions.

One place in the language where this rule already surfaces is _enumeration
entries_. In particular, in the body of an enumeration entry -- a static
member -- you can access members from the companion object _without_
qualification.

We also aim to support migration from code as the following.

```kotlin
class Example {
  companion object {
    @JvmStatic fun foo() = ...
  }
}
```

Into a version in which the static function becomes the "main" counterpart.

```kotlin
class Example {
  static fun foo() = ...

  companion object {
    @Deprecated("Use the static version") fun foo() = Example.foo()
  }
}
```

In the JVM, from the binary compatibility perspective this is a two-way road.
We expect developers to start with static members most of the time, and turn
them into a companion object with `@JvmStatic` annotations if an instance or
shared state is needed.

### Fake constructors

[Constructors](https://kotlinlang.org/spec/declarations.html#constructor-declaration)
in Kotlin are quite limited. A common pattern to circumvent those limitations
is the _`invoke` in companion_  pattern.

```kotlin
class CoolnessService {
  companion object {
    suspend fun invoke(parameters: ConnectionParameters): CoolnessService = ...
  }
}
```

Although this proposal forbids operators from using static scopes in general,
the `invoke` operation is an exception to that rule.

```kotlin
class CoolnessService {
  static suspend fun invoke(parameters: ConnectionParameters): CoolnessService = ...
}
```

Those `invoke` functions are still **not** constructors for the purposes of the
language, and they eventually need to call a "real" constructor. However, from
the use-site perspective, they allow providing constructor-like syntax whenever
suspension or context parameters are required.

### `T::type` is not a type nor a expression

In this proposal static is a notion reserved for _scopes_, there is no _type_
corresponding to that same notion. This has several ramifications in the design:

- It is not possible to form a function type including static scope receivers.

    ```kotlin
    fun foo(block: Int::type.() -> Unit) { }  // ill-formed type
    ```

- The compilation scheme erases type receivers (at least in JVM).
- To obtain the type of a callable reference, static dispatch and type extension
  receivers are dropped.

    ```kotlin
    fun Int::type.bar(): Int = 3

    val f = ::bar  // has type '() -> Int'
    ```

Similarly, there is no _value_ corresponding to the static scope of a class.

```kotlin
val i = Int::type  // wrong
```

The main consequence is that giving more priority to static members in a given
scope that they already had becomes impossible. This is in contrast to
(companion) objects, which can be prioritized using functions like `with`.

### Coding conventions

Static members and extensions follow the usual
[coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
The only exception is that static properties, which would use
`SCREAMING_SNAKE_CASE` in most cases when following either the
[current rules](https://kotlinlang.org/docs/coding-conventions.html#property-names)
or [Android's](https://developer.android.com/kotlin/style-guide#constant_names)
, may use `UpperCamelCase` instead.

## Technical details

### Declaration

**§1.1** _(grammar)_: the Kotlin grammar is modified as follows,
introducing the new dispatch scope as a modifier,
and generalizing receiver types to receiver scopes in declarations.

```diff
  anonymousInitializer:
+   [dispatchModifier {NL}]
    'init'
    ...

  functionDeclaration:
    [modifiers]
    'fun'
    [{NL} typeParameters]
-   [{NL} receiverType {NL} '.']
+   [{NL} receiverScope {NL} '.']
    {NL} simpleIdentifier {NL}
    ...

  propertyDeclaration:
    [modifiers]
    ('val' | 'var')
    [{NL} typeParameters]
-   [{NL} receiverType {NL} '.']
+   [{NL} receiverScope {NL} '.']
    ({NL} (multiVariableDeclaration | variableDeclaration))
    ...

  modifier:
-   (... | platformModifier) {NL}
+   (... | platformModifier | dispatchModifier) {NL}

+ dispatchModifier:
+   'static'

+ receiverScope:
+     receiverType
+   | identifier '::' 'type'
```

**§1.2** _(static members)_:
callables declared using the static scope should be introduced in the static
scope of the corresponding classifier.
We shall refer to those members as **static members**.

**§1.3** _(restrictions on static members)_:
the following declarations may **not** be marked as `static`:
- top-level declarations, since there is no classifier to hatch into;
- members in an `object`;
- local declarations (defined in the body of another callable),
  including [anonymous function declarations](https://kotlinlang.org/spec/expressions.html#anonymous-function-declarations).

```kotlin
object Greeter {
  // error, static member in object
  static fun anonymousHello() { ... }

  fun hello(name: String) { ... }
}

class Classroom {
  // ok, object appears as instance scope
  static fun Greeter.helloFolks() = hello(name = "folks")
}
```

**§1.4** _(type extensions)_:
we shall refer to declarations using `::type` in receiver
position as **type extensions**. We refer to the receiver as either
the **static scope receiver**.

**§1.5** _(restrictions on type extensions)_:
it is not possible to declare type extensions to `object`s.

```kotlin
// error, type extension to an object
fun Greeter::type.helloEverybodyTwice() { ... }
```

The type before `::type` must be just a class name, without any generic
arguments; this restriction is similar to what it's allowed to write 
in a [class literal](https://kotlinlang.org/spec/expressions.html#class-literals).
It is allowed to use a type alias only when the expansion contains no type
generic arguments. Furthermore, it's forbidden to use a _type parameter_.

```kotlin
fun <A> A::type.Null(): A? = null  // error

fun List<Int>::type.UpTo(n: Int): List<Int> = ...  // error!
fun List::type.UpTo(n: Int): List<Int> = ...       // ok
```

**§1.6** _(constant static properties)_:
the rules for
[constant properties](https://kotlinlang.org/spec/declarations.html#constant-properties)
are updated to include static (member) properties.

```kotlin
class Vector(...) {
  const static val Dimensions: Int = 2  // ok
}
```

**§1.7** _(no scopes for accessors)_:
property accessors always inherit the scope
of the enclosing property. In particular, it is not possible to declare one
accessor in a different scope than the other.
As a consequence, it's forbidden to mark a property accessor with `static`.

```kotlin
class CoolnessService {
  static val ServiceId = "😎" // ok

  static val DeveloperId
    get() = "Cool Inc."       // ok

  val Message: String
    static get() = "Hello"    // error!
}
```

**§1.8** _(static operators)_:
if an
[operator convention](https://kotlinlang.org/spec/operator-overloading.html#operator-overloading)
requires a dispatch or extension receiver, this requirement may **not** be
fulfilled using the static scope receiver.

For example, the following is an incorrect definition of a `plus` operator:

```kotlin
operator fun Vector::type.plus(n: Int) = ...
```

The only exception is the
[`invoke` operator](https://kotlinlang.org/spec/overload-resolution.html#callables-and-invoke-convention),
that can be defined as `static`.

```kotlin
class CoolnessService {
  suspend static fun invoke(parameters: ConnectionParameters) = ...
}
```

This restriction does not close the door to new operators being defined in the
static scope; it only limits those available at the moment of writing this KEEP.

**§1.9** _(static members require a body)_:
static members must always declare a body, unless they are marked as `expect`
or `external`.

**§1.10** _(no enclosing type parameters)_:
static members may not reference type parameters from its enclosing type.
In most cases, this restriction can be worked around by introducing a fresh
type parameter in the member itself.

```kotlin
interface List<A> {
  // not allowed, it refers 'A'
  static fun empty(): List<A>

  // allowed, introduces a new 'A'
  static fun <A> empty(): List<A>
}
```

**§1.11** _(enumerations)_:
[enumeration entries and other "unofficially static" members in a `enum class`](https://kotlinlang.org/spec/declarations.html#enum-class-declaration)
"officially" become static members of its enclosing class.

```kotlin
enum class Direction { UP, DOWN }

// should be considered as follows
class Direction {
  static val UP: Direction
  static val DOWN: Direction

  public final static val entries: EnumEntries<Direction>
  public final static fun valueOf(value: String): Direction
  public final static fun values(): Array<Direction>
}
```

**§1.12** _(annotations)_:
static scope receivers may not be annotated. In particular, that means
that the `@receiver` use site target is not allowed, and that you cannot
attach an annotation with a type target to `T::type`.

### (Lack of) inheritance

**§2.1** _(no inheritance for static members)_:
static members do not participate in inheritance.
As a consequence, static members may not be marked with the modifiers
`open`, `abstract`, `final`, or `override`.

**§2.2** _(no inheritance, hiding)_:
as a consequence, it is possible to declare a static member with the same
signature in a subclass without any additional modifier. The new member
**hides** the one from its parent, does **not override** it.

**§2.3** _(no inheritance, actualization)_:
as a consequence, `actual static` members which correspond to a
`expect static` member must be defined in the _exact same `actual` classifier_.
That is, it is not possible for those members to come from the static scope
of a superclass.

**§2.4** _(no inheritance, superclass scope linking)_:
the static scope of a classifier is
[linked](https://kotlinlang.org/spec/scopes-and-identifiers.html#linked-scopes)
to those of its superclasses. This behavior may look as if those members were
somehow inherited when using a short name.

```kotlin
open class A {
    static fun foo() = Unit
}

class B : A() {
    static fun main() {
        foo()    // ok
        B.foo()  // unresolved
    }
}

fun main() {
    B.foo()  // unresolved
}
```

This mirrors the behavior with nested classes:

```kotlin
open class A {
    class Nested
}

class B : A() {
    fun foo() {
        Nested()    // ok
        B.Nested()  // unresolved
    }
}

fun main() {
    B.Nested()  // unresolved
}
```

[Deprecating this superclass scope linking](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md#deprecate-superclass-scope-linking)
was part of the previous proposal for statics.
This proposal keeps the rules unchanged, since more investigation is required.
If this deprecation is possible, it shall turn into a new, separate, proposal.

**§2.5** _(inheritance and type extensions)_:
member type extensions participate in inheritance in the regular fashion.
In this case the static scope receiver must _strictly coincide_.

```kotlin
abstract class A {
  abstract fun Example::type.example(): Int
}

abstract class B1: A() {
  override fun Example::type.example(): Int = 3  // ok
}

abstract class B2: A() {
  override fun example(): Int = 3  // does *not* override the member from 'A'
}
```

The ability to define several type extensions in the same class or their
inheritance may be further restricted by the
[compilation strategy](#compilation-strategy).

### Resolution

For the purposes of resolution we use the notion of
[_phantom implicit static `this`_](https://kotlinlang.org/spec/overload-resolution.html#receivers)
as defined in the specification:

> [...] a phantom static implicit `this` is a special receiver, which is
> included in the receiver chain for the purposes of handling static functions 
> from enum classes. It may also be used on platforms to handle their 
> static-like entities, e.g., static methods on JVM platform.

The goal of this section is to generalize that notion to cover the new static
scopes defined in this proposal.

**§3.1** _(phantom implicit static `this` matching)_:
the phantom implicit static `this` receiver may only stand for a static scope
(dispatch in a static member, extension in a type extension).
This receiver may _not_ stand for a companion object.

**§3.2** _(receivers introduced by type extensions)_:
in the body of a declaration of a type extension for type `T`
both the phantom implicit static `this` and the companion object receivers
are introduced (the latter only if available), with the same priority as
defined by the specification.
Note that these receivers are also available on static members, as described
in the [specification](https://kotlinlang.org/spec/overload-resolution.html#receivers).

In practice, that means that within a type extension you can use
members from the companion object without qualification, but not vice versa.

The following piece of code exemplifies the different receivers (and scopes)
available at different points.

```kotlin
class Foo { companion object }

class Bar {
  fun Foo::type.bar1(f: Foo) {
    // available receivers and scopes (from highest to lowest priority)
    // - phantom static Foo
    // - Foo.Companion
    // - this@Bar
    // - phantom static Bar
    // - Bar.Companion
  }

  fun Foo.Companion.bar2(f: Foo) {
    // available receivers and scopes (from highest to lowest priority)
    // - Foo.Companion
    // - this@Bar
    // - phantom static Bar
    // - Bar.Companion
  }

  static fun bar3() {
    // available receivers and scopes (from highest to lowest priority)
    // - phantom static Bar
    // - Bar.Companion
  }

  companion object {
    // available receivers and scopes (from highest to lowest priority)
    // - Bar.Companion
    // - this@Bar
    // - phantom static Bar
    // - (Bar.Companion)
  }
}
```

**§3.3** _(static wins over companion)_:
we do not change what is stated in the specification:

> The phantom static implicit `this` receiver has higher priority than the current
> class companion object receiver;

The implications of this rule are much larger once this proposal is implemented.
In practice, that means that if nothing else disambiguates between two
functions, once defined as static member and other within the companion object,
the former shall be preferred.

The following piece of code exemplifies this behavior:

```kotlin
class Example {
  static fun foo() = companionMethod()

  static fun inBoth() = ...    // (a)
  static fun bar() = inBoth()  // resolves to (a)

  companion object {
    fun companionMethod() = ...
    fun inBoth() = ...         // (b)
    fun bar() = inBoth()       // resolves to (b)
  }
}
```

One additional reason for this design is that it is always possible to
explicitly choose the overload in the companion by using the name of the
companion object. For example, `Example.Companion.inBoth()` above.

**§3.4** _(`this` expressions)_:
phantom static implicit `this` is **not** available through explicit
[`this` expressions](https://kotlinlang.org/spec/overload-resolution.html#receivers).

```kotlin
class Bar {
  fun Foo::type.test(f: Foo) {
    val me = this  // 'this' has type 'Bar', not 'Foo'
    ...
  }
}
```

**§3.5** _(callable references)_:
static scope receivers are removed from the signature of a callable to obtain its type.

It is still an error to create a reference to a member with both
dispatch and extension receiver, except from within the class
where the dispatch receiver is bound.

```kotlin
// static val Zero = ... --> we drop the 'Vector' static scope
val z = Vector::Zero  // KProperty0<Vector>
// val Vector::type.UnitX = ... --> we drop the 'Vector' static scope
val u = Vector::UnitX  // KProperty0<Vector>

class Example {
  static fun bar(n: Int): Vector = ...
  static fun Vector.baz(n: Int): Vector = ...

  fun example() {
    val r = ::bar        // (Int) -> Vector
    val z = Vector::baz  // Vector.(Int) -> Vector
  }
}

val r = Example::bar  // (Int) -> Vector
val e = Example::baz  // error, 'Example::type' and 'Vector' are receivers
```

**§3.6** _(instance always sees static)_:
for the purposes of resolution, the static and instance parts of a classifier
are considered separately, each of them effectively working as a separate
body scope. As described by the
[specification](https://kotlinlang.org/spec/declarations.html#constructor-declaration-scopes),
the constructor scope is upward-linked to the static classifier body scope.

As a consequence of this rule, all static members are accessible at any
point in the constructor or instance scope, even if they are defined textually
after.

**§3.7** _(constant scope)_:
[constant properties](https://kotlinlang.org/spec/declarations.html#constant-properties)
are considered a separate scope, upward-linked to the static scope.

As a consequence, it is possible to refer to constant properties defined
anywhere in a class during static initialization of that same class.

**§3.8** _(imports)_:
the rules for
[importing](https://kotlinlang.org/spec/packages-and-imports.html#importing)
are not updated from the current ones.

Note that there is no ambiguity between importing elements from the static
or the companion object scope:

```kotlin
import Example.foo            // 'foo' from static scope of 'Example'
import Example.Companion.foo  // 'foo' from companion scope of 'Example'
```

**§3.9** _(star-imports)_:
it is allowed to [star-import](https://kotlinlang.org/spec/packages-and-imports.html#importing)
a class. This is equivalent to importing all the static members and classifiers
of that class. Star-importing a class with no static members or classifiers
is **not** an error, but tools may issue a warning in this case.

Note that ths scenario with static members is different from that of objects.
Star-importing in objects is not possible because you would end up with
repeated `toString`, `equals`, and potentially more inherited members.
In contrast, the static scope is tied to each specific class.

**§3.10** _(resolution, calls without explicit receiver)_:
calls without explicit receiver work as described in the
[specification](https://kotlinlang.org/spec/overload-resolution.html#call-without-an-explicit-receiver).
Note that the presence of the phantom static `this` now may bring more members
into consideration than before this proposal.

**§3.11** _(resolution, calls with an explicit type receiver)_:
the [specification](https://kotlinlang.org/spec/overload-resolution.html#call-with-an-explicit-type-receiver)
contains specific rules for calls with an explicit type receiver. However,
those account only for static members, not for type extensions.

Instead, we propose to aligns these calls with explicit (regular) receivers.
We simply consider the calls as having two potential explicit receivers:
first, the static phantom `this`, and then the companion object receiver.
The search using two receivers is interleaved, that is, _for each_ potential
scope we consider the two possibilities, before moving to the parent scope.

```kotlin
class Example {
  static fun foo() = ...

  companion object {
    fun foo() = ...
  }

  static fun example() {
    foo()  // resolves to the static 'foo'
  }
}

fun Example::type.hi() = foo()         // resolves to the static 'foo'
fun Example.Companion.bye() = foo()    // resolves to 'foo' in the companion

fun meet() = Example.foo()             // resolves to the static 'foo'
fun greet() = Example.Companion.foo()  // resolves to 'foo' in the companion
```

Note that the regular scoping rules still apply. In the example below, the
function is resolved to the one with a companion object receiver scope,
because the dispatch receiver scope `Companion` has higher priority than
`TypeExtension`.

```kotlin
class Example { companion object }

class TypeExtension {
  fun Example::type.foo() { ... } // (s)
}

class Companion {
  fun Example.Companion.foo() { ...} // (c)
}

fun example() {
  with (TypeExtension()) {
    with (Companion()) {
      Example.foo()  // resolves to (c)
    }
  }
}
```

### Initialization

**§4.1** _(minimal requirements for static initialization)_:
initialization of the static scope of a classifier happens _once_, _before_ the
first time one of these conditions are met:
1. one of the non-constant static members of that classifier is accessed;
2. the companion object of that classifier is accessed;
3. a constructor of that classifier is called, either directly or indirectly via
  one of its subclasses.

The compiler or the runtime are free to initialize a static scope at any
point if they comply with the rules above; initialization need not happen
_right before_ the first time one of those conditions are met.

In some platforms initialization also happens as result of other operations
(for examples, here are the rules for the
[JVM](https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-5.html#jvms-5.5)).
This still complies with the rules stated above, which lay down a _minimal_
set of scenarios in which static initialization must happen.

**§4.2** _(initialization of parent classes)_:
in general, accessing a static member in a class does _not_ require the
initialization of its parent classifiers, unless they explicitly refer to it.
This is especially important for constructors, since in that case the static
initialization of a parent class happens because the constructor of the parent
class is called.

In the case of interfaces a subclass does not call a constructor. As a result, item
(3) in §4.1 does not apply to interfaces. Static initialization may be deferred
until one of the other rules apply.

> [!NOTE]
> The behavior of interfaces in the JVM is quite complex:
> the static initializer only runs when the interface contains default methods.
> This rule allows this behavior as valid implementation, but other behaviors
> are also allowed, and may surface in other platforms.

**§4.3** _(initialization for type extensions)_:
we remark that calling a type extension does _not_ imply the
initialization of the classifier being extended, as per the rules above.

```kotlin
val Vector::type.hasFiniteBasis: Boolean = true

fun findBasis(): List<Vector> {
  if (!Vector.hasFiniteBasis) return emptyList()
  // at this point Vector may not be statically initialized

  // ...
}
```

**§4.4** _(initialization order, general rule)_:
initialization of static members occurs in two phases. In each of those phases
initialization is performed in program text order.

1. Initialization of static constants, if required by the platform,
2. Initialization of static properties, static `init` blocks, and companion
   objects.

If any of the properties are accessed before they are initialized,
the value of the property is unspecified. If any step in the initialization
process leads to a loop, it results in unspecified behavior.
This aligns with the "regular" initialization order in the
[specification](https://kotlinlang.org/spec/declarations.html#classifier-initialization).

> [!NOTE]
> This rule cements the fact that companion objects are somehow treated as
> static properties of the enclosing class.

**§4.4** _(initialization order, enumerations)_:
in the case of enumerations, the static initialization order is as follows:

1. enumeration entries,
2. initialization related to static implicit property `entries`,
3. user-defined static initialization, as per the previous paragraph.

### Compilation strategy

To satisfy the goal of good interoperability with the underlying platform, we
have to define how these scopes affect the compilation.

> [!WARNING]
> This part of the design about type extensions is still under development. 
> Please read the [alternative design choices](#alternative-design-choices)
> section, and provide feedback during the KEEP review process.

**§5.1** _(static members)_:
declarations defined in the `static` dispatch scope should be
compiled down to static members in the underlying platform, shall that notion
exist. JVM and JS are examples of such platforms.

If the underlying platform supports several notions similar to statics,
the one in which members are _not_ inherited should be preferred.
Swift, with its [type methods](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/methods/#Type-Methods),
is an example of such a platform. In the case of Swift, `static` methods should
be preferred over `class` methods.

**§5.2** _(no platform-specific static)_:
it is not allowed to mark a static member with any of the platform-specific
annotations related to this matter, including `@JvmStatic` and `@JsStatic`.

**§5.3** _(type extensions, erasure)_:
the compiler does not introduce a parameter standing for the static scope
receiver in a type extension.
Enough metadata should be kept to recreate the right signature
when called from other Kotlin code.

The compiler should try to recreate the original signature in mixed-language
inheritance chains, in a similar way as done now for properties.

**§5.4** _(type extensions, no mangling scheme)_:
as opposed to the
[previous KEEP for statics](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md#static-extensions-on-jvm),
this proposal does _not_ introduce a mangling scheme for compilation.

Keeping the original name seems more aligned with good interoperability.
However, as a result, additional binary names may be required to prevent
platform clashes.
For example, without the `@JvmName` annotation, both properties in the code
below get the same signature, henceforth leading to a platform clash.

```kotlin
val Int::type.zero = ...

@JvmName("zeroVector")
val Vector::type.zero = ...
```

> [!WARNING]
> In JVM, the same signature may not be defined for a static and a non-static
> member of the same class. This may lead to additional platform clashes.

As a result of the rules above, the following Kotlin code:

```kotlin
data class Vector(...) {
  static val Zero: Vector = Vector(0.0, 0.0)
}

val Vector::type.UnitX: Vector = Vector(1.0, 0.0)
```

would be compiled down in the JVM as follows:

```java
class Vector {
  private static Vector Zero = new Vector(0.0, 0.0); // backing field
  public static Vector getZero() { return this.zero; }
}

class MyFileKt { // defined in myFile.kt
  public static Vector getUnitX() { return new Vector(1.0, 0.0); }
}
```

**§5.5** _(static fields)_:
backing fields of static properties, and properties marked with platform-
specific annotations equivalent to `@JvmField`, should be compiled to
static fields in the underlying platform, shall that notion exist.

**§5.6** _(backing fields of companion objects, JVM)_:
currently, the state of a companion object is stored using static fields in the
enclosing class, using the same name as the properties declared there. This may
result in a clash with backing fields of static properties.

For that reason, we propose to change the compilation scheme of companion objects.
In particular, whenever a private field is introduced in a class due to a
declaration in its companion object, then its name should be _mangled_.
The proposed mangling scheme is `CompanionObjectName$fieldName`.

This rule does _not_ cover two cases in which the introduced field
[is not private](https://www.baeldung.com/kotlin/companion-objects-in-java),
namely when using `@JvmField` annotation, or the `lateinit` and `const`
modifiers. In that case introducing two properties with the same name, one
in the companion object and one in static scope, lead to a _platform clash_.

For example, the following Kotlin code

```kotlin
class Vector(...) {
  companion object {
    const val Dimensions: Int = 2
    val SpecialVectors: MutableMap<Vector, String> = mutableMapOf()
  }
}
```

is compiled down in the JVM as follows:

```java
public class Vector {
  // companion object instance
  public static Companion Companion { }

  // constant
  public static final int Dimensions = 2;

  // backing field of companion object property
  private static MutableMap<Vector, String> Companion$SpecialVectors;

  static {
    SpecialVectors = new LinkedHashMap();
    Companion = new Companion();
  }

  class Companion {
    public final MutableMap<Vector, String> getSpecialVectors() {
      return Vector.Companion$SpecialVectors;
    }
  }
}
```

Note that in most cases such a property in the companion object can be freely
refactored to be a static property of the class, keeping even binary
compatibility.

```kotlin
class Vector(...) {
  const static val Dimensions: Int = 2
  static val SpecialVectors: MutableMap<Vector, String> = mutableMapOf()

  companion object {
    val SpecialVectors: MutableMap<Vector, String> get() = Vector.SpecialVectors
  }
}
```

### Reflection

The design in this section aligns with the dropping of static dispatch and
extension receivers from function types in callable references.

**§6.1** _(static members)_:
in those platforms in which `kotlin.reflect` contains `KDeclarationContainer`,
the package is updated as follows.

1. A new interface for containers of static members.

    ```kotlin
    interface KStaticDeclarationContainer {
      val staticMembers: Collection<KCallable<*>>
    }
    ```

2. `KClass` implements `KStaticDeclarationContainer`.

**§6.2** _(type extensions)_:
the following property is added to `KCallable`. This property should be `null`
whenever the callable is not a type extension.

```kotlin
  interface KCallable<out R> {
+   val typeExtensionReceiver: KClass?
  }
```

Note that the type is `KClass` and not `KType` because type extensions
extend the _class itself_, not a particular instantiation thereof.

**§6.3** _(no additional arguments to `call`)_:
no value should be passed in the position of static dispatch or extension
receivers when using `call`, `callBy` from `KCallable`, and the corresponding
functions from the `KProperty` hierarchy.

```kotlin
val p: KProperty0<Vector> = Vector::Zero  // note the 0 here
val t = p.typeExtensionReceiver           // KClass representing 'Vector'
val zero = p.get()                        // no argument required
```

## Alternative design choices

### Syntax and naming

As discussed above, static members and type extensions get different names
even though they both refer to a similar underlying concept. This is done
to prevent potential ambiguity.

In a previous iteration of the proposal we uniformly used `static` in all
positions, and referred to type extensions as _static scope_ extensions.

```kotlin
val Vector::static.UnitX: Vector = Vector(1.0, 0.0)
```

However, even in internal discussions it was clear that the difference between
"static extension" and "static scope extension" was too little and prone to
problems. As a result, we went with a larger difference.

Apart from `::type`, the suffix `::class` was also considered. In fact, this
syntax is slightly better in that you are cannot use just any type when
declaring a type extension (`List<Int>::type` is not allowed). However,
it would lead to a scenario in which `Int::class` have completely different
meanings in a signature and in a body.

```kotlin
fun Int::class.zero() {  // type extension over 'Int'
  val k = Int::class     // k has type 'KClass<Int>'
}
```

### Compilation strategy

The current proposal for compilation strategy has a strong point its
easy interoperability with other JVM languages, but poses a challenge for
mixed inheritance and reflection.

```kotlin
// A.kt
open class A {
  open fun Example::type.foo(): Int = ...
}

// B.java
class B extend A {
  // 'Example::type' is erased
  @Override int foo() { ... }
}

// C.kt
class C : B() {
  // what is the correct option?
  override fun foo() = ...
  override fun Example::type.foo() = ...
}
```

This is not the first time similar problems arise, though. Kotlin also "erases"
properties into their accessors from the JVM point of view, and requires
an _enhancement_ mechanism to solve these problems.

An additional risk of the current proposal are the potential platform clashes
from functions which are completely distinct from the Kotlin side.

```kotlin
@JvmName("IntZero")  fun  Int::class.zero() = ...
@JvmName("LongZero") fun Long::class.zero() = ...
```

These disadvantages bear the question: are there other (better) alternatives?

The previous KEEP for statics proposed a
[mangling scheme](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md#static-extensions-on-jvm),
prepending the name of the class to the name of the function.

```kotlin
open class A {
  open fun Example::type.foo(): Int = ...
}
// becomes
class A {
  int Example$foo() { ... }
}
```

Even though this proposal minimizes platform clashes, it does not solve the
problem of mixed inheritance completely: you need to understand if
`Example$foo` is just a function with a weird name, or comes from a type
extension. Even though the function is callable from Java, it uses a symbol
often associated with internal names.
Furthermore, we think that the risk of platform clashes is quite small.

There are other ways in which we can bring disambiguation without changing 
the name. For example, by introducing an additional parameter of the same
type of the class we extend, but which always will be `null`, or an array
of that class, which always will be an empty array. This choice, though,
feels even more artificial than name mangling.

A final option is to completely hide type extensions from Java, in a similar
way as how we hide functions with inline value classes. We feel that this
choice goes against one of the goals of this KEEP, which is good 
interoperability with Java and other JVM languages.

### Previous statics proposal

This proposal is syntactically quite similar to the
[previous one on the matter]([KEEP-347](https://github.com/Kotlin/KEEP/blob/statics/proposals/statics.md)),
but strongly differs in the conceptual model it entails. In particular,
`static` is now more tightly coupled with the notion of scope in the language.

This proposal does **not** include _static objects_.
Under a simple facade (simply compile everything inside to static members)
static objects hide a lot of complexity. We found it particularly
challenging to answer whether a static object gives rise to a "real" type or not.

### Extension companion objects

Another possibility that has been explored is to allow adding new companion
objects to an already existing class. During resolution, the compiler checks
the companion object within the class and then extension companion objects
defined for the same class.

```kotlin
companion object Vector.Directions {
  val UnitX: Vector = Vector(1.0, 0.0)
}
```

This model keeps the notion of companion object as the main carrier for
class-related functionality. However, it does not provide an answer to the
problem of compiling down to static members in the underlying platform.

## Discarded ideas

These are some extensions to this proposal that were deemed not useful enough.

**`inner` for members, `static` for classes**:
we considered allowing the `inner` modifier for members to double down on
the notion of "this is not static". Similarly, we thought of allowing the
`static` modifier for nested classes, to strengthen the idea of "not inner".

The only benefit seems to be uniformity in the language ("everything" can be
marked as inner or static). However:
- there does not seem to be a compelling use case for marking members as inner;
- it brings a potential split in our community on whether member should always
  have a explicit scope or not. This affects in particular `explicitAPI` mode.

**Static scope paths for disambiguation**:
we considered allowing `Type::type` to also appear as a way to disambiguate
a callable. For example, if function `foo` was declared both in static scope and
in the companion object, you could be very explicit by writing
`Type::type.foo`.

However, it seems that we would need this in very few occasions, since:
- Static wins over companions, and you can already disambiguate to the latter
  by writing `Type.Companion.foo`.
- For callable references, specifying the type disambiguates in most cases.
- You can import a static callable with renaming to avoid ambiguity.

Furthermore, it would not be possible to write `val x = Type::type`, since
there is no type to assign to such an expression.
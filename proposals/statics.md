# Kotlin statics and static extensions

* **Type**: Design proposal
* **Authors**: Roman Elizarov
* **Contributors**: Simon Ogorodnik, Mikhail Zarechensky, Marat Ahin, Alexandr Udalov
* **Status**: Proposed, in public review
* **Issue**: [KT-11968](https://youtrack.jetbrains.com/issue/KT-11968)
  Research and prototype namespace-based solution for statics and static extensions
* **Discussion**: [KEEP-348](https://github.com/Kotlin/KEEP/issues/348)

## Table of contents

<!--- TOC -->

* [Introduction](#introduction)
  * [Static members primer](#static-members-primer)
  * [Static extension primer](#static-extension-primer)
  * [Static object primer](#static-object-primer)
* [Detailed Design](#detailed-design)
  * [Static members grammar](#static-members-grammar)
    * [Option for static section syntax](#option-for-static-section-syntax)
    * [Option for static modifier syntax](#option-for-static-modifier-syntax)
  * [Static members and scope](#static-members-and-scope)
  * [Static objects](#static-objects)
  * [Constant static properties](#constant-static-properties)
  * [Intended use of static properties and functions](#intended-use-of-static-properties-and-functions)
  * [Static initialization](#static-initialization)
  * [Static body scope](#static-body-scope)
  * [Static declarations resolution and import](#static-declarations-resolution-and-import)
  * [Static objects resolution and import](#static-objects-resolution-and-import)
  * [Statics visibility](#statics-visibility)
  * [Statics and inheritance](#statics-and-inheritance)
  * [Extension functions as static members](#extension-functions-as-static-members)
  * [Static extensions](#static-extensions)
  * [Static extensions on generic classes](#static-extensions-on-generic-classes)
  * [Extensions on static objects](#extensions-on-static-objects)
  * [Static extensions resolution and import](#static-extensions-resolution-and-import)
  * [Static extensions vs static members](#static-extensions-vs-static-members)
  * [Static extensions as members](#static-extensions-as-members)
  * [Static extensions vs extensions as static members](#static-extensions-vs-extensions-as-static-members)
* [Code style](#code-style)
  * [Class-related functions](#class-related-functions)
  * [Constructor functions](#constructor-functions)
* [JVM ABI](#jvm-abi)
  * [Static members of classes and interfaces on JVM](#static-members-of-classes-and-interfaces-on-jvm)
  * [Static objects on JVM](#static-objects-on-jvm)
  * [Constant static properties on JVM](#constant-static-properties-on-jvm)
  * [Static extensions on JVM](#static-extensions-on-jvm)
  * [Interaction with JVM-specific annotations](#interaction-with-jvm-specific-annotations)
* [Statics in other languages](#statics-in-other-languages)
* [Potential future improvements](#potential-future-improvements)
  * [Static scope projection reference](#static-scope-projection-reference)
  * [Static interfaces and static overrides](#static-interfaces-and-static-overrides)
  * [Static operators](#static-operators)
* [Open issues](#open-issues)
  * [Static section vs static modifier](#static-section-vs-static-modifier)
  * [Callable references to static members](#callable-references-to-static-members)
  * [Static soft keyword ambiguity](#static-soft-keyword-ambiguity)
  * [Migration of objects to static objects](#migration-of-objects-to-static-objects)
  * [Reflection](#reflection)
  * [Deprecate superclass scope linking](#deprecate-superclass-scope-linking)
  * [Mangling scheme for static extensions on JVM](#mangling-scheme-for-static-extensions-on-jvm)
  * [ABI for non-JVM platforms](#abi-for-non-jvm-platforms)
  * [Static object alternatives and namespaces](#static-object-alternatives-and-namespaces)

<!--- END -->

## Introduction

This proposal introduces the concepts  of **static members**, **static extensions**, and **static objects** to
the Kotlin programming language. The main motivations behind this proposal are:

* Enable static extensions of 3rd party classes, which is the top most voted feature in Kotlin.
* Provide a lighter-weight mechanism for defining static members that do not access instance variable
  as an alternative to companion objects in many use-cases.
* Simplify interoperability with JVM statics that Kotlin compiler has to support anyway — more concise
  and clear usage of Java frameworks that rely on statics, easier Java to Kotlin migration.

> It is a non-goal of this proposal to deprecate or to completely replace companion objects.

All in all, the new concepts are designed to address the following major problems in the language design:

* [KT-11968](https://youtrack.jetbrains.com/issue/KT-11968)
  Adding statically accessible members to an existing Java class via extensions
* [KT-15595](https://youtrack.jetbrains.com/issue/KT-15595)
  Optimize away unused and empty private companion object
* [KT-16872](https://youtrack.jetbrains.com/issue/KT-16872)
  JvmStatic annotation on companion object functions creates an extra method
* [KT-16900](https://youtrack.jetbrains.com/issue/KT-16900)
  Allow 'const val' in classes and interfaces

This proposal also opens a road to potential future extensions of the language that need some ability to extend classes
as opposed to extension of instances, namely:

* [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871) Collection literals
* [KT-45587](https://youtrack.jetbrains.com/issue/KT-45587) Tuples (structural literals and structural types)
* [KT-49392](https://youtrack.jetbrains.com/issue/KT-49392) Implement static interface (companion/static requirements)

**OPEN ISSUE**: This proposal has a major open issue on what kind of declaration syntax to use for static members:
**static sections** or **static modifiers**. See [Static section vs static modifier](#static-section-vs-static-modifier)
for details on their pros and cons. The text of the proposal is written showing both alternatives side by side.

### Static members primer

Kotlin programming language has a concept of _top-level functions_ that are analogous to static functions in languages
like Java and C#. In particular, top-level functions don’t have any `this` reference in their scope.

However, inside the Kotlin classes the closest thing Kotlin has are members inside _companion objects_.
Consider the following example of a `Color` class for an RGB color:

```kotlin
class Color(val rgb: Int) {
    companion object {
        fun fromRGB(r: Int, g: Int, b: Int): Color { /* impl */ }
        val RED = Color(0xff0000)
        // other declarations
    }
}
```

The above declarations does more than declaring `Color.fromRGB` and `Color.RED`. It also declares a
`Color.Companion` object which does not add any additional value to this particular usage of companion object
and creates additional complications:

* Every function in the companion object has `this` reference to the companion object, which usually is not used in
  any meaningful way.
* Companion object can be extended using `fun Color.Companion.extFunction()` syntax but only if the class had explicitly
  declared the companion object, which makes extending 3rd party classes without a companion object impossible.
* Companion object gets compiled into a separate class on Kotlin/JVM even though it does not usually carry any state.
* You cannot access `Color.fromRGB` from Java directly, you’ll need to add `@JvmStatic` to those declaration,
  which creates additional byte code on JVM.
* All members of companion object have to be grouped together in the declaration of the class, which sometimes is not
  convenient in larger classes.

In this proposal we introduce a concept of a **static members** as a direct replacement for the majority of today’s usage
for companion object.

The example code can be converted into the following:

**Option: Static section syntax.**

```kotlin
class Color(val rgb: Int) {
    static {
        fun fromRGB(r: Int, g: Int, b: Int): Color { /* impl */ }
        val RED = Color(0xff0000)
        // other declarations
    }
}
```

Declarations inside a static section are called **static members**.

> A class can have several static sections, thus in larger classes static members can be grouped according to their
> function next to the corresponding instance members if needed.

> Unlike companion objects, static sections cannot be named and cannot have their own visibility modifiers, which
> also makes them easier to understand and to use in practice.

**Option: Static modifier syntax.**

```kotlin
class Color(val rgb: Int) {
    static fun fromRGB(r: Int, g: Int, b: Int): Color { /* impl */ }
    static val RED = Color(0xff0000)
}
```

Declarations with a static modifier are called **static members**.

Static members, regardless of the syntax that is used to declare them, address all shortcomings of companion objects
that were listed above:

* Static members do not have any `this` reference similar to top-level functions.
* Static members can be introduced outside the lexical scope of the class using
  **static extension** syntax `fun Color.static.extFunction()`.
* Static members compile directly into the original class, without a need to generate a separate class on Kotlin/JVM.
* A static `Color.fromRGB` function can be accessed as a static member from Java directly.
* Static members can be mixed together with instance members, so they can be grouped in the code according
  to their intended purpose.

### Static extension primer

Unlike companion objects, static members do not introduce any kind of stable object reference. They only expand
the static scope of a class. It enables writing extensions for static scope of any class. For example, a use-case from
[KT-11968](https://youtrack.jetbrains.com/issue/KT-11968)
(Adding statically accessible members to an existing Java class via extensions) can be written like this
using a new **static extension** syntax:

```kotlin
val Intent.static.SCHEME_SMS: String
    get() = "sms"
```

Here, `Intent` is a 3rd-party Java clas. With this declaration, one can write `Intent.SCHEME_SMS` to get a string
`"sms"`.

### Static object primer

Kotlin has `object` declarations, which are used for multiple purposes. One of the current uses for an object
declaration is to group a number of related declarations under a common namespace. For example, the Kotlin standard
library has the following declaration:

```kotlin
object Delegates {
    fun <T : Any> notNull(): ReadWriteProperty<Any?, T> = NotNullVar()
    // other declarations
}
```

The goal of this object is to ensure that call-sites for the declarations inside it, like `notNull()`, are prefixed with
a namespace `Delegates` and look like `Delegates.notNull()`. However, just like a companion object, this kind of object
usage introduces a superfluous `Delegates` instance that works as `this` inside of the `notNull` function without being
actually used. A new **static object** feature allows for such grouping to be performed without introducing
an object instance:

```kotlin
static object Delegates {
    fun <T : Any> notNull(): ReadWriteProperty<Any?, T> = NotNullVar()
    // other declarations
}
```

The members of such a `static object` work similarly to top-level functions. They don't have `this` inside of them and
get compiled directly to static functions on JVM.

The rationale for rolling out the `static object` feature at the same moment when static declarations are introduced
is that if static declarations become available without static objects, it will immediately cause the
"static utility class" boilerplate pattern to appear in the Kotlin code. That is, developers would work around the
absence of static objects by declaring a class with only static members inside to replicate the effect a
static object for grouping multiple functions or properties under a single namespace. This pattern will be hard
to get rid of later, since it would introduce unnecessary type of the class, in addition to the static methods
themselves.

## Detailed Design

The section goes into the details of the design.

### Static members grammar

There are two syntactic choices for static member declaration: **static section** and **static modifier**.
Both of them are explained in the separate sections below.

#### Option for static section syntax

From the standpoint of Kotlin grammar, static section gets added
to `classMemberDeclaration` and, grammatically, looks very much like `init` section,
but has `classBody` block inside of it:

```text
classBody : '{' classMemberDeclarations '}' ;

classMemberDeclarations : (classMemberDeclaration semis?)* ;

classMemberDeclaration
  : declaration
  | companionObject
  | anonymousInitializer
  | secondaryConstructor
  | staticSection         // New class member declaration variant
  ;

staticSection : 'static' classBody ;
```

There are the following additional semantic restrictions on static sections:

* Static section is not allowed directly inside any kind of `object` declaration (including companion object),
  but is allowed inside a nested class or an interface of an object.
* Static section is not allowed inside another `static` section.
* Static section is not allowed inside a local class or interface.
* Static section is not allowed inside an inner class.

> Rationale: static sections inside objects or other static sections don't bring additional value, since those
> containers with their contents are already conceptually static. The restrictions on placing statics inside
> local and inner classes simply extend existing restrictions on static declarations (like named objects) inside them.

Static sections can appear inside a `class` or an `interface`. All the declarations inside a static sections are said
to be _static members_ of the corresponding class or interface.

Despite grammar of the static section listing `classBody` as the static section contents,
only the following declarations are allowed directly inside the static section:

* Function and property declarations — called _static functions_ and _static properties_.
* Anonymous initializers (`init { ... }` sections) — called _static initializers_.

Other declarations (including `class`, `interface`, `object`, and `typealias` declarations) are not allowed
inside a static section.

> Rationale: `class`, `interface`, and `object` declarations inside a static section make no sense, as
> such declarations are already static by default and do not have access to an outer class instance.
> They should be written directly inside the corresponding class or interface, not inside its static section.
> `typealias` declarations are currently allowed only on top-level and lifting this restriction is
> outside the scope of this proposal.

Static members cannot have `operator` modifier.
See [Static operators](#static-operators) for potential lifting of this restriction in the future.

There can be multiple static sections inside a class or an interface. This way, in larger classes, it becomes possible
to group static declarations based on their functionality. For example, private helper static methods can be
situated somewhere close to the place where they are used.

#### Option for static modifier syntax

A new `static` modifier gets introduced for the purpose of marking _static members_ of classes and interfaces.

There are the following semantic restrictions on the placement of static modifiers:

* Static modifier is only allowed on:
  * Function and property declarations — such declarations are called _static functions_ and _static properties_.
  * Anonymous initializers (`init { ... }` sections) — such section are called _static initializers_.
* Static modifier is not allowed on other declarations (including `class`, `interface`, and `typealias` declarations).
  * Static modifier on `object` declaration is used to denote [Static objects](#static-objects) and have a
    different semantics from a static declaration on functions and properties.

> Rationale: static modifier on a `class`, `interface` declarations make no sense, as
> such declarations are already static by default and do not have access to an outer class instance.
> `typealias` declarations are currently allowed only on top-level and lifting this restriction is
> outside the scope of this proposal.

* Static modifiers on members (functions and properties) are not allowed directly inside any kind of
  `object` declaration (including companion object), but are allowed inside a nested class or an interface of an object.
  * Note that `static object` is allowed inside other objects, see [Static objects](#static-objects).
* Static modifiers are not allowed inside a local class or interface.
* Static modifiers are not allowed inside an inner class.
* Static members cannot have `operator` modifier.
  * See [Static operators](#static-operators) for potential lifting of this restriction in the future.

### Static members and scope

Static declarations get placed into the same declaration scope as the instance member declarations in the containing class
or interface. Thus, unlike companion objects, it is illegal to declare static members with names that conflict
with the instance members of the class or interface, yet it is Ok to properly overload functions. Also,
it is illegal for a static member to hide instance members that are inherited from any superclasses or superinterface.

> As explained in [Statics and inheritance](#statics-and-inheritance), the static declarations themselves are not
> inherited, so no hiding happens between statics with the same signature in related classes.

For example:

**Option: Static section syntax.**

```kotlin
class Outer {
    val x = 0 // member property
    fun foo(x: Int) {} // member function
    static {
        val x = 0 // ERROR: Conflicting declaration
        fun toString() = "Outer" // ERROR: Hides inherited member from Any
        fun foo(x: Int) {} // ERROR: Conflicting overload
        fun foo(x: Any) {} // OK: A proper overload
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Outer {
    val x = 0 // member property
    fun foo(x: Int) {} // member function
    static val x = 0 // ERROR: Conflicting declaration
    static fun toString() = "Outer" // ERROR: Hides inherited member from Any
    static fun foo(x: Int) {} // ERROR: Conflicting overload
    static fun foo(x: Any) {} // OK: A proper overload
}
```

### Static objects

The object declaration supports a new `static` modifier. Such an object is said to be a **static object** and is
referred to, as usual, by its name in the code. Unlike regular objects, static objects cannot be referenced as a
value or as a type by themselves (see [Static scope projection reference](#static-scope-projection-reference) for
discussion on potentially allowing it in the future).

Static objects can contain directly inside them the following declarations:

* Function and property declarations — called _static functions_ and _static properties_.
* Anonymous initializers (`init { ... }` sections) — called _static initializers_.
* Nested object declarations (both static and non-static ones), but companion object is not allowed.
* Nested classes (but no inner classes).
* Nested interfaces.

Declarations inside static objects have no `this` reference.

```kotlin
static object Namespace {
    val property = 42 // static property
    fun doSomething() { // static function
        property // OK: Can refer to static property in the same scope
        this // ERROR: Static objects have no instance
    }
}

fun main() {
    Namespace.doSomething() // OK
    val x = Namespace // ERROR: Cannot reference static object as value
    val y: Namespace? = null // ERROR: Cannot reference static object as type
}
```

Static objects cannot implement interfaces nor extend classes. Their only intended purpose is to serve as a
namespace grouping related functions.

> With static object having no value, nor type, nor identity, their ability to extend objects and implement interface would
> create a  weird exception where the super class or interface implementation will be getting access to `this` reference,
> while the methods of the static objects themselves have no access to `this`. However, in the future, if needed,
> we may introduce a separate concept of [Static interfaces and static overrides](#static-interfaces-and-static-overrides).

Static objects do not have value and do not extend `Any`. Still, to future-proof them, they are forbidden from declaring
any functions with the same Kotlin or platform signatures that are declared in the `kotlin.Any` class. For example:

```kotlin
static object Namespace {
    fun toString() = "Namespace" // Error: not allowed to use the signatures matching members of `kotlin.Any`
}
```

The mental model for the concept of `static object` is that it is very much like a regular named object,
but all its methods are static and do not use object instance in any way, so there is no stable reference
to this object, as it is not needed. Another way to look at it, is that `static` modifier for an `object` has
a similar effect as a `value` modifier for a `class` &mdash; it is an indicator that a stable reference
is not needed.

Static object is a performance-oriented tweak to the concept of a named object. It need not be taught
to beginner Kotlin developers. When used properly, one does not need to understand what difference
`static` modifier for a named `object` makes. Beginner Kotlin developers can simply ignore `static` modifier
before `object` and still make sense of the code.

> An early prototype of this design used a `namespace` keyword instead of `static object`.
> [Static object alternatives and namespaces](#static-object-alternatives-and-namespaces) section goes into
> more details on alternatives for `static object` design and the rationale for picking the design proposed here.

### Constant static properties

Static properties can have `const` modifier similarly to top-level and object properties.

> Note: On JVM adding/removing `const` modifier is a binary incompatible change.
> See [Constant static properties on JVM](#constant-static-properties-on-jvm).

### Intended use of static properties and functions

The primary use of static function and properties is to provide utilities that are intended to be used
in the scope of the corresponding class or interface without the need have an instance of the corresponding class
or shall be qualified by the name of their containing class.

This is especially relevant for creation functions and constants that cannot be put on the top-level
due to their short, ambiguous names. For example, it would be a bad style to declare a top-level property
called `empty` (as this short name might be appropriate for any container and does specify which one it is about),
but `MyContainer.empty` is non-ambiguous and can be declared as a static member without introduction
of a superfluous companion object whose instance is not needed:

**Option: Static section syntax.**

```kotlin
class MyContainer {
    static {
        val empty: MyContainer = MyContainer()
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class MyContainer {
    static val empty: MyContainer = MyContainer()
}
```

### Static initialization

Static properties can have initializers, classes and interfaces can have static initialization sections with code.
Together they form static initialization code. This code gets executed, from top to bottom, in the program order,
when the containing class or interface gets initialized.

> The rules that define when the corresponding class gets initialized are currently platform-specific.

When a class or interface has a static section as well as a companion object, then the initialization of the
companion object instance also happens in the program order of the companion object declaration with respect to
its placement in the source code. For example, the following code
prints `1`, `2`, `3`, `4`, `5` in order when the class `Example` gets loaded:

**Option: Static section syntax.**

```kotlin
class Example {
    static {
        val x = run { println("1") }
        init { println("2") }
    }

    companion object {
        val y = run { println("3") }
    }

    static {
        init { println("4") }
        val z = run { println("5") }
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Example {
    static val x = run { println("1") }
    static init { println("2") }

    companion object {
        val y = run { println("3") }
    }

    static init { println("4") }
    static val z = run { println("5") }
}
```

> Rationale: mixing of companion objects with statics is expected to be rare in practice, mostly driven
> by migration. The proposed program-order-initialization rule is easier to remember and gives developer flexibility
> in addressing the specific needs of initialization order in their code.

As it is usual for initialization in Kotlin, the process of static initialization may have cycles, leading to
unspecified and platform-depended behavior when attempting to access not-yet-initialized values.
Kotlin compiler may attempt to detect and report initialization cycles as compile-time warnings or errors,
but is not strictly required to do so.

### Static body scope

The body scope of the static function, static property getters/setter, and static init section
does not have `this` reference and behaves similarly to a top-level declaration.

However, all the static declarations inside a class, interface, or (static) object can refer to all the declarations
in the scope of their containing class, interface, or object by their unqualified name
(see [Static members and scope](#static-members-and-scope)). For example:

**Option: Static section syntax.**

```kotlin
class Outer {
    static {
        val staticVal = "OK"
    }
    interface NestedInterface
    class NestedClass : NestedInterface
    object NestedObject

    static {
        fun staticFunction() {
            this // ERROR: Unresolved
            val ic = NestedClass() // OK
            ic as NestedInterface // OK
            NestedObject // OK
            staticVal // OK: it does not matter that it is in a different static section, it is still the same scope
        }
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Outer {
    static val staticVal = "OK"
    interface NestedInterface
    class NestedClass : NestedInterface
    object NestedObject

    static fun staticFunction() {
        this // ERROR: Unresolved
        val ic = NestedClass() // OK
        ic as NestedInterface // OK
        NestedObject // OK
        staticVal // OK
    }
}
```

### Static declarations resolution and import

The following declarations of classes and interfaces are collectively called **static declarations**:

* Static function and property declarations — **static member declarations**.
* Nested classes, interfaces, and objects (but not inner classes).

> The rule of thumb, is that static declarations do not have access to their containing scope via
`this` instance reference and cannot be accessed, themselves, using the reference to an instance of the scope.
They are accessed statically in the corresponding scope or via the name of the corresponding scope.

The following basic rules drive the resolution of static declarations in Kotlin:

* Static declarations are available by their unqualified name from the code that is lexically contained inside the
  corresponding class or interface.
* Static declarations are NOT available automatically in the extensions of the corresponding classes or in places
  where an instance of the corresponding class is otherwise available as a receiver in the context.
* Static declarations are available with a qualified name of `<ClassName>.<unqalifiedName>`.
* In case of ambiguity between a reference to a static declaration and a declaration from a companion object,
  the reference to a static declaration wins. Companion object members can be always accessed using a name of the
  companion object (e.g. `<ClassName>.Companion.<unqalifiedName>`) if needed.
* Static declarations can be imported by a regular import directive either individually or using a `*` wildcard.

> These rules consistently extend the current approach to resolution of existing static declarations in Kotlin
> (nested classes, interfaces, and objects) to the newly introduced concept of static members.

Example:

**Option: Static section syntax.**

```kotlin
package color

class Color(val rgb: Int) {
    static {
        val RED = Color(0xff0000) // static member of `Color` class
    }

    fun foo() {
        RED // OK: can use simple unqualified name here
    }

    class NestedClass {
        fun bar() {
            foo() // ERROR: cannot access instance members without `this` reference
            RED // Ok, can access static declarations via a simple name
        }
    }
}

fun Color.bar() {
    RED // ERROR: not lexically inside `Color`, statics are not available by simple name
    Color.RED // OK: via a qualified name
}
```

**Option: Static modifier syntax.**

```kotlin
package color

class Color(val rgb: Int) {
    static val RED = Color(0xff0000) // static member of `Color` class

    fun foo() {
        RED // OK: can use simple unqualified name here
    }

    class NestedClass {
        fun bar() {
            foo() // ERROR: cannot access instance members without `this` reference
            RED // Ok, can access static declarations via a simple name
        }
    }
}

fun Color.bar() {
    RED // ERROR: not lexically inside `Color`, statics are not available by simple name
    Color.RED // OK: via a qualified name
}
```

Now, in another file:

```kotlin
package user

import color.Color.* // import all static declarations inside Color

fun baz() {
    RED // OK: was imported using wildcard
}
```

Note, that static members behave like nested classes, interface, and objects with respect to import and not like
companion object members. In the above example, if `Color.RED` was declared in the companion object, then it
will not be accessible via `import color.Color.*`, but would require `import color.Color.Companion.RED` without
ability to import everything using `*`, because star import is not supported for singleton objects.

### Static objects resolution and import

All declarations inside a `static object` are considered to be static declarations and behave just like
static declarations in classes and interfaces as explained above. The difference between them and regular (non-static)
object declarations is that it is possible to use star import. For example:

```kotlin
package com.example

static object Namespace {
  fun doSomething() {
      /* does something */
      this // ERROR: Not available
  }
}
```

In another file:

```kotlin
package user

import com.example.Namespace.*

fun main() {
    doSomething() // OK: imported from a static object `Namespace` via star import
}
```

> This means, that removing a `static` modifier to an object declaration is not a source-compatible change,
> as it can break some previously compiling code. Adding a `static` modifier is not a source compatible change
> either, because `static objects` lacks identity and cannot be referenced as explained in the section on
> [Static objects](#static-objects). Neither change is binary compatible, too.
> See [Migration of objects to static objects](#migration-of-objects-to-static-objects) section for further discussion.

### Statics visibility

**Option: Static section syntax.** Unlike companion objects, static sections themselves cannot have any
visibility modifiers.

Static members of classes and interfaces are `public` by default, just like regular instance members.
Their visibility can be reduced to `internal` or `private` with a similar effect as for instance members.
Internal static members are accessible only from the same module, while private members are accessible only
from the lexical scope of the containing class or interface.

Private static declarations are utilities that intended to be used only in the lexical scope of the
corresponding class or interface.

> Static members cannot have `protected` visibility.

### Statics and inheritance

Static declarations are not inherited. They can only be accessed using the name of the class or interface
they are directly contained within.

However, class scope is [linked](https://kotlinlang.org/spec/scopes-and-identifiers.html#linked-scopes) to the scope
of its parent classes in Kotlin, so inside the class it is possible to access existing static declarations
(nested classes, interfaces, objects, companion object members, and Java statics) from its superclasses
(but not from its superinterfaces) using a short name.

We do not plan to make it possible to access newly introduced static members (properties and functions) through such
a parent class scope linking mechanism and, as a separate research, will see if it is feasible and desirable
to deprecate such linking altogether in the future.
See [Deprecate superclass scope linking](#deprecate-superclass-scope-linking) section for details.

For example:

**Option: Static section syntax.**

```kotlin
open class BaseClass {
    object ObjectX // existing static declaration
    static {
        val x = 1 // static member
    }
}

interface BaseInterface {
    object ObjectY // existing static declaration
    static {
        val y = 2 // static member
    }
}

class DerivedClass : BaseClass(), BaseInterface {
    object ObjectZ // existing static declaration
    static {
        val z = 3 // static member
    }

    fun foo() {
        ObjectX // OK, BUT: from a linked scope of a superclass `BaseClass`, might be deprecated in the future
        ObjectY // ERROR: superinterface scope is not linked
        ObjectZ // OK: from the scope for this class
        x // ERROR: Static member is not available through a linked scope
        y // ERROR: superinterface scope is not linked
        z // OK: from the scope for this class
    }
}

fun test() {
    DerivedClass.z // OK: Declared in `DerivedClass`
    DerivedClass.x // ERROR: Not declared in `DerivedClass`, not inherited
    BaseClass.x    // OK: Declared in `BaseClass`
}
```

**Option: Static modifier syntax.**

```kotlin
open class BaseClass {
    object ObjectX // existing static declaration
    static val x = 1 // static member
}

interface BaseInterface {
    object ObjectY // existing static declaration
    static val y = 2 // static member
}

class DerivedClass : BaseClass(), BaseInterface {
    object ObjectZ // existing static declaration
    static val z = 3 // static member

    fun foo() {
        ObjectX // OK, BUT: from a linked scope of a superclass `BaseClass`, might be deprecated in the future
        ObjectY // ERROR: superinterface scope is not linked
        ObjectZ // OK: from the scope for this class
        x // ERROR: Static member is not available through a linked scope
        y // ERROR: superinterface scope is not linked
        z // OK: from the scope for this class
    }
}

fun test() {
    DerivedClass.z // OK: Declared in `DerivedClass`
    DerivedClass.x // ERROR: Not declared in `DerivedClass`, not inherited
    BaseClass.x    // OK: Declared in `BaseClass`
}
```

> Rationale: Statics can be always imported explicitly if needed. The linking of superclass scope
> (but not from superinterface) was a design decision that is inspired by the way in which statics
> are inherited from superclasses (but not from superinterfaces) in Java, which is a legacy design decision
> stemming from the fact, that Java interfaces did not have static initially. However, statics are resolved
> statically and any kind of their inheritance (even if it is called linking) is confusing, because it does
> not really take the actual run-time type into account.

### Extension functions as static members

Extension functions can be declared as static members, similarly to
[extensions that can be declared as instance members](https://kotlinlang.org/docs/extensions.html#declaring-extensions-as-members).
Such extensions can be imported and/or resolved according to general rules on static declarations.
Unlike extensions declared as instance members, extensions declared as static members have only a single _extension receiver_,
and they are statically dispatched. They don't have a _dispatch receiver_.

For example:

**Option: Static section syntax.**

```kotlin
class Color(val rgb: Int) {
    static {
        // Extension declared in static section
        private fun Int.toHexByte(index: Int) =
            // `this` is a reference to an extension receiver of type `Int`
            (this ushr (index * 8) and 0xff).toString(16).padStart(2, '0')
    }

    fun toString() = "(r=${rgb.toHexByte(2)}, g=${rgb.toHexByte(1)}, b=${rgb.toHexByte(0)}"
}
```

**Option: Static modifier syntax.**

```kotlin
class Color(val rgb: Int) {
    // Extension declared with static modifier
    private static fun Int.toHexByte(index: Int) =
        // `this` is a reference to an extension receiver of type `Int`
        (this ushr (index * 8) and 0xff).toString(16).padStart(2, '0')
    }

    fun toString() = "(r=${rgb.toHexByte(2)}, g=${rgb.toHexByte(1)}, b=${rgb.toHexByte(0)}"
}
```

Such extensions are useful as they allow to limit extension's scope to one class. Unlike extensions declared
as instance members, extensions declared as static members can be used from inside other static declarations of
the corresponding class. For example:

**Option: Static section syntax.**

```kotlin
class Outer {
    static {
        fun Int.extensionAsStaticMember() { /* implementation */ }
    }

    fun Int.extensionAsInstanceMember() { /* implementation */ }

    object Nested { // a static declaration inside an Outer class
        fun test() {
            1.extensionAsStaticMember() // OK: Available in the static context
            2.extensionAsInstanceMember() // ERROR: Not available in the static context
        }
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Outer {
    static fun Int.extensionAsStaticMember() { /* implementation */ }

    fun Int.extensionAsInstanceMember() { /* implementation */ }

    object Nested { // a static declaration inside an Outer class
        fun test() {
            1.extensionAsStaticMember() // OK: Available in the static context
            2.extensionAsInstanceMember() // ERROR: Not available in the static context
        }
    }
}
```

### Static extensions

**Static extensions** are declared similarly to regular extension functions with `<ClassName>.static` as the
receiver type. While regular extensions are declared with `<ClassName>` as the receiver type and are somewhat analogous to
putting a member into class scope, static extensions are declared with `<ClassName>.static` as the receiver type,
as they are somewhat analogous to putting a member into a static scope of the class.

> With static section syntax for static members, the syntax for static extensions is designed to be mnemonic with
> respect to how static section is declared inside a class, analogous to putting a member into a
> `static {}` section inside the class.

The `static` here is a soft keyword. See [Static soft keyword ambiguity](#static-soft-keyword-ambiguity) section
for implications on backwards compatibility.

The body for the static extension can use static members of the corresponding class by their short name. This
is similar to a traditional extension that puts a reference to the class into scope, making all the instance members
from the corresponding class available by their short name. Here the scope contains a _phantom static implicit receiver_
of the corresponding class, making all the static members of the corresponding class available by their short name.

```kotlin
fun Color.static.parse(s: String): Color { // static extension
    RED // OK: unqualified `Color.RED` reference to a static member works here
    this // ERROR: Unresolved: static extension has no real receiver
    rgb  // ERROR: Unresolved: instance members of the `Color` class are not present in its phantom static receiver
    // ...
}
```

The phantom static implicit receiver is added as a phantom receiver to the receiver chain with the same priority
as regular receiver for the regular extension function would have been added.
See the [receivers](https://kotlinlang.org/spec/overload-resolution.html#receivers) section
in the specification for details.

The reference to static scope `<ClassName>.static` cannot be used by itself as a value or as type.
See [Static scope projection reference](#static-scope-projection-reference) for
discussion on potentially allowing it in the future.

Static extensions cannot have `operator` modifier.
See [Static operators](#static-operators) for potential lifting of this restriction in the future.

### Static extensions on generic classes

Static extensions are defined on a _class_, not on a specific _type_. That means, that when the generic class
is being extended only the class name is being specified. For example:

```kotlin
class Box<T>(val value: T)

fun <T> Box.static.of(value: T): Box<T> = Box(value)
//      ^^^ It is an error to write Box<T> here
```

### Extensions on static objects

Static extensions can be declared for [Static objects](#static-objects) in exactly the same way
as [Static extensions](#static-extensions) for classes and interfaces using `<StaticObjectName>.static` as receiver.
They follow all the same rules as other static extensions.

For example:

```kotlin
static object Namespace {
    // static member
    fun doSomething() {}
}

// extension on static object
fun Namespace.static.ext() {
    this // ERROR: It has no receiver
    doSometing() // OK: Via phantom static implicit receiver
}
```

> Rationale: the requirement to have an explicit `.static` modifier makes it explicit for the reader of this
> extension function that it has no `this` receiver in scope without having to find the declaration of
> the object that is being extended to verify that it is a static object. It will also aid with
> [Migration of objects to static objects](#migration-of-objects-to-static-objects) as the extensions on
> the objects that are being migrated to static objects can clearly and independently migrate from
> being regular extensions to static extensions.

### Static extensions resolution and import

Static extensions can be called in several ways. One way is using making a qualified class with
`<ClassName>.<extensionName>` as in, for example, `Color.parse(s)`. Similarly to the regular extensions, the
corresponding static extensions has to be imported in order for this call to be resolved.

For example, a package `ext` declares extension on `color.Color`:

```kotlin
package ext

import color.Color // import `Color` to use it by short name

// Declares `parse` in `ext` package as a static extension on `color.Color`
fun Color.static.parse(s: String): Color { /* implementation */ }
```

Now, a package `users` imports both `color.Color` and `ext.parse` in order to use it:

```kotlin
package user

import color.Color // import `Color` to use it by short name
import ext.parse // import `parse` static extension to resolve it

val yellow = Color.parse("yellow") // OK: Call resolves
```

Static extensions can be called by their short name from the scope of the corresponding classes
when imported, for example:

```kotlin
package color

import ext.parse // import `parse` static extension to resolve it

class Color(val rgb: Int) {
    fun test() {
        parse("test") // OK: Resolves to static extension call
    }
}
```

Static extensions on a class can be also called by their short name from the scope of other static extensions on
the same class. For example:

```kotlin
package ext2

import color.Color // import `Color` to use it by short name
import ext.parse // import `parse` static extension to resolve it

fun Color.static.anotherExtension() {
    parse("test") // OK: Resolves to static extension call
}
```

The call to the static extension of a class `C`, unlike the call to the static member, does not cause the
class `C` to be initialized. For example, given the following class declaration.

**Option: Static section syntax.**

```kotlin
class C {
    static {
        val property = println("initialized")
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class C {
    static val property = println("initialized")
}
```

The following code produces output as given in the comments:

```kotlin
fun C.static.foo() = println("foo")

fun main() {
    C.foo()     // Prints foo
    C.property  // Prints initialized
}
```

### Static extensions vs static members

Unlike static members, static extensions can be called by their short name only in two contexts as explained
in the above [Static extensions resolution and import](#static-extensions-resolution-and-import) section:

* From inside the lexical scope of the corresponding class.
* From inside the lexical scope of another static extension on the corresponding class.

While static members can be imported anywhere to be used by their short name, static extensions do not have such a
capability. In other contexts static extensions are used only via `<ClassName>.<unqualifiedName>` syntax.
For example:

**Option: Static section syntax.**

```kotlin
package exmp

class Example {
    static {
        fun staticMember() {}
    }
}

fun Example.static.staticExtension() {}
```

**Option: Static modifier syntax.**

```kotlin
package exmp

class Example {
    static fun staticMember() {}
}

fun Example.static.staticExtension() {}
```

In another package we must import `exmp.Example` in order to make unqualified class name `Example` available
first, then we can import a static member and a static extension:

```kotlin
package test

import exmp.Example
import exmp.Example.staticMember
import exmp.staticExtension

fun test() {
    Example.staticMember()    // OK
    Example.staticExtension() // OK
    staticMember()            // OK, because of import exmp.Example.staticMember
    staticExtension()         // ERROR
}
```

It does no matter what kind of import is used &mdash; using star imports leads to the same results.

> If [Static scope projection reference](#static-scope-projection-reference) support is added to this design
> in the future, then scope functions could be used bring in static scope into arbitrary places in the code,
> thus allowing for usage of static extensions by their short name in those places of the code.

### Static extensions as members

Static extensions can be declared as members similarly to how
[regular extensions can be declared as members](https://kotlinlang.org/docs/extensions.html#declaring-extensions-as-members).
Such extensions have a _dispatch receiver_ on the corresponding class in scope as a member,
giving them access to the members of the  corresponding class, and a _phantom static implicit receiver_
as a [static extension](#static-extensions). For example, the following code declares a static
extension property on `Color` as a member of class `Widget`:

```kotlin
class Widget {
    private val backgroundColor: Color = initializeBackgroundColor()

    // static extension property on Color as a member in Widget
    val Color.static.background: Color
        get() = backgroundColor

}
```

With such a declaration, any code in the scope of `Widget` can refer to the widget's background color as
`Color.background`, which creates a DSL for uniform access to all colors via `Color.<xxx>` references in code.

### Static extensions vs extensions as static members

One of the most confusing aspects of adding statics to Kotlin is that there will be to similarly looking, yet
different concepts [Static extensions](#static-extensions) and
[Extension functions as static members](#extension-functions-as-static-members).

For example:

**Option: Static section syntax.**

```kotlin
class Example {
    static {
        fun Int.ext1() { // extension declared as static member
            this // has type Int
        }
    }
    fun Int.static.ext2() { // static extension declared as member
        this // has type Example
    }
}
```

**Option: Static modifier syntax.**

The confusion is more apparent with the static modifier syntax, which makes their declaration
very much alike:

```kotlin
class Example {
    static fun Int.ext1() { // extension declared as static member
        this // has type Int
    }
    fun Int.static.ext2() { // static extension declared as member
        this // has type Example
    }
}
```

To summarize the difference between them:

* Extension declared as static member (`ext1`) operates on an instance of the class it extends (`this: Int` in the example)
  and has no reference to the outer class (`Example`) in its scope. It can be used only from the lexical scope
  of the containing class or its static extensions, or if imported (via `import Example.ext1`), using
  a qualified call on an instance of a class it extends (`intInstance.ext1()`).

* Static extension declared as member (`ext2`) operates in the context of an instance of an outer class (`this: Example`)
  and has no reference to an instance of the class its static scope it extends (`Int`). It can be uesd only when
  the instance of the outer class (`Example`) is in scope using qualified call on a class it
  statically extends (`Int.ext2()`).

## Code style

We expect that introduction of statics and static extensions in Kotlin will have a major impact on the style of
Kotlin libraries, as well as on the style of the Kotlin standard library itself.

### Class-related functions

It is now customary to complement interfaces, like `List` from the standard library, with top-level functions
that provide instances of these interfaces, like `emptyList()` and `listOf()` that all return `List` instances.
Semantically, these functions are tightly related to the corresponding interface, but syntactically they are
not tied to the interface `List` itself.

With statics, it becomes possible to declare static extension function `List.empty()` instead of
[`emptyList()`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/empty-list.html),
`List.of()` instead of
[`listOf()`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/list-of.html),
etc. The advantage of such transition is that it becomes easier to discover
all `List`-related top-level functions just by typing `List.` and using IDE autocompletion. Moreover,
3rd party library can add their domain-specific functions as static extensions so that they
will be available with `List.xxx` syntax as well.

In effect, the implicit knowledge that the functions like `emptyList()` and `listOf()` are related to the `List`
interface will become explicitly represented in the syntax, as those all those functions will be declared
as static extensions of the `List` interface.

This explicit connection will also improve documentation, as it will become possible to automatically group all
the `List`-related static functions and static extensions into a single section in the docs.

Migration from the top-level functions to the corresponding static extensions can be performed using the
regular deprecation and replacement. However, migration of the standard library functions like `emptyList()` to
static extensions like `List.empty()` will have to be performed with care. They are so popular in the existing
Kotlin code, that even giving deprecation warning on them will be too disruptive. We will need
some new mechanism to make this transition smoother, see [KT-54106](https://youtrack.jetbrains.com/issue/KT-54106)
Provide API for perpetual soft-deprecation and endorsing uses of more appropriate API.

### Constructor functions

The other common Kotlin pattern are so-called _constructor functions_. Using the `List` example again, there
is a [`List(size: Int, init: (index: Int) -> T): List<T>`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list.html)
constructor function for the `List` interface. Today, its connection to the `List` interface is only apparent from
its name and return type and is not explicit in the language.

If support for [static operators](#static-operators) is added in the future, it will become possible to declare it
as a static extension `operator fun <T> List.static.invoke(...)`, thus preserving the call-site `List(...)` usage,
but making it explicitly connected to the `List` interface.

## JVM ABI

This sections describes the compilation strategy on JVM in details.

### Static members of classes and interfaces on JVM

On JVM all static class and interface members are compiled as the static methods on JVM, including
[Extension functions as static members](#extension-functions-as-static-members). For example:

**Option: Static section syntax.**

```kotlin
class Outer {
    static {
        val x = 0
        fun foo(x: Int) {}
        fun Int.ext() {}
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Outer {
    static val x = 0
    static fun foo(x: Int) {}
    static fun Int.ext() {}
}
```

Get compiled as the following equivalent Java code:

```java
public class Outer {
    public static int getX() { return 0; }
    public static void foo(int x) {}
    public static ext(int $this$ext) {}
}
```

Static initialization sections are compiled into the corresponding static initialization method on JVM, preserving
the program order of all static initializers, including companion object initialization.

Note, that JVM 1.8 supports private static interface members, even though the Java language supports
them starting only from Java 9, so all Kotlin static members can be compiled for all supported JVM targets.

### Static objects on JVM

On JVM [Static objects](#static-objects) are compiled as utility classes with static members.
For example:

```kotlin
static object Namespace {
    val property = 42
    fun doSomething() {}
}
```

Get compiled as the following equivalent Java code:

```java
public class Namespace {
    public static int getProperty() { return 42; }
    public static void doSomething() {}
}
```

Note, that similarly to Kotlin file-classes (class `XxxKt` that is produced when compiling top-level declaration
from Kotlin file `Xxx.kt`), we will not generate private constructor in those utility classes for static objects.
So, it would be possible to create an instance of static object class from Java, but the instance will be of no
practical use, as its methods are static and the type of class itself is not used anywhere.

Initialization sections of static objects are compiled into the corresponding static initialization method on JVM,
preserving the program order of all static initializers.

In Kotlin, the reference to the static object and the type of the class to which the methods are compiled
cannot exist, but it can accidentally get exposed to Kotlin code via Java, which sees Kotlin static objects
as regular classes. In this case Kotlin compiler will report an error &mdash; the same
error that happens when Kotlin file-classes `XxxKt` get exposed to Kotlin compiler via Java. For example:

```java
// Java code
public class Expose {
    // an instance of Kotlin static object class
    public static Namespace NAMESPACE = new Namespace();
}
```

```kotlin
// Kotlin code
fun main() {
    Expose.NAMESPACE // ERROR: Cannot access class 'Namespace'
}
```

### Constant static properties on JVM

On JVM constant static properties of classes, interface, and static objects get compiled directly into public static
fields without the corresponding getter. For example:

**Option: Static section syntax.**

```kotlin
class Color(val rgb: Int) {
    static {
        const val BITS = 24
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Color(val rgb: Int) {
    static const val BITS = 24
}
```

Get compiled as the following equivalent Java code:

```java
public class Color {
    // constructor and fields are omitted
    public static final int BITS = 24;
}
```

> Note: It means that adding/removing Kotlin `const` modifier is not a binary compatible change on JVM.

Private static constants in interfaces have the same problem on JVM 1.8 as other private static members.
See [Static members of classes and interfaces on JVM](#static-members-of-classes-and-interfaces-on-jvm).

### Static extensions on JVM

On JVM [Static extensions](#static-extensions) get compiled as the corresponding static or member functions of
the containing file class or class (depending on the placement in the source code). The phantom static implicit
receiver is not represented in the JVM function signature in any way. They entirely look like functions without
any receiver.

The static extensions are supposed to have short names, as their effective namespace is confined to the
static scope of the class they extend. So, in order to reduce the incidence of JVM platform declaration
clashes and to make stack-traces easier to read, the default JVM name of the static extension is defined as
the short name of the class being extended, `$` (dollar sign), and the function name.

For example:

```kotlin
// In file Parsing.kt
fun Color.static.parse(s: String): Color = TODO()
```

Get compiled as the following equivalent Java code:

```java
public class ParsingKt {
    public static Color Color$parse(String s) { /*...*/ }
}
```

If the static extensions need to be used a part of the public Java API, then the `@JvmName` annotation can be used,
when applicable, to specify the desired JVM name. For example:

```kotlin
// In file Parsing.kt
@JvmName("parseColor")
fun Color.static.parse(s: String): Color = TODO()
```

Get compiled as the following equivalent Java code:

```java
public class ParsingKt {
    public static Color parseColor(String s) { /*...*/ }
}
```

[Static extensions as members](#static-extensions-as-members) receive the same treatment. For example:

```kotlin
class Widget {
    val Color.static.background: Color
        get() = TODO()
}
```

Get compiled as the following equivalent Java code:

```java
public class Widget {
    public Color getColor$background() { /*...*/ }
}
```

Here, the compound JVM name `Color$background` of this static extension gets further processed to produce the JVM name
of the getter method. Other kinds of JVM name mangling, like the mangling scheme used for functions with inline
value class parameter types, would likewise receive the compound name of the static extension as an input.

> Alternative schemes for the names of static extensions on JVM are discussed in
> [Mangling scheme for static extensions on JVM](#mangling-scheme-for-static-extensions-on-jvm).

### Interaction with JVM-specific annotations

Static members of classes, interfaces, and static objects interact with JVM-specific annotations
from the `kotlin.jvm` package as explained below. The only special cases are the following:

* `@JvmName` is supported, including on static interface members (note: not supported on interface instance members).
* `@JvmOverloads` is supported, including on static interface members (note: not supported on interface instance members).
* `@JvmStatic` is not supported, as they are already compiled to static members.
* `@Transient` is not supported, as static properties are not a part of serialized instance state.

Other JVM-specific annotations are supported normally on static members just like on regular instance members and
top-level declarations, including `@JvmField`, `@JvmSynthetic`, `@JvmSuppressWildcards`, `@JvmWildcard`,
`@Synchronized`, `@Volatile`, `@Strictfp`, `@Throws`.

## Statics in other languages

The overwhelming majority of popular and growing languages support some form of static declarations. However, there
are differences in how static methods are represented syntactically and how the calls to static methods are dispatched.

We say that the static method dispatch is always _static_ if the call to a static method in a specific call-site is
resolved at a compile-time or link-time to a single declaration. We say that static methods support _dynamic dispatch_
if some forms of static methods calls can call different static method declarations depending on the runtime class
that the code operates with.

**C++**, **C#** and **Java** use `static` modifier keyword to designate static members of classes (and interfaces in Java and C#).
They are always dispatched statically using the call-site syntax of `ClassName.method()` in C# and Java
and `ClassName::method()` in C++.
C# 11 (.NET 7) has introduced
[static virtual members in interfaces](https://learn.microsoft.com/en-us/dotnet/csharp/whats-new/tutorials/static-virtual-interface-members)
which allow static declarations to be overridden and their calls can be dynamically dispatched in generic methods
using `T.method()` call-site syntax when `T` is a type parameter.
See [Static interfaces and static overrides](#static-interfaces-and-static-overrides)
for potential introduction of a similar feature in Kotlin in the future.

**JS/TS** uses `static` modifier keyword to designate static members of classes.
In JS/TS static methods are reified as members of the corresponding class object, so they can be overridden
in subclasses and their calls can be dynamically dispatched when the reference to the class object is passed at runtime
using `cls.method()` call-site syntax where `cls` can be any expression that provides the reference to the target class.
The target class for `cls.method()` call can be retrieved via `ClassName` syntax for statically known class or
via `obj.constructor` syntax for a run-time object.

**Python** uses `@classmethod` and `@staticmethod` decorators to declare static class members, with the only difference that
`@classmethod` also receives a reference to the corresponding Python class as its first parameter.
Both kinds of static methods become members of the class objects and can be called dynamically
in a way that is somewhat similar to JS/TS using `cls.method()` syntax.

**Swift** uses `static` and `class` modifier keywords to designate static members of classes and protocols.
Calls to static members of classes are statically dispatched when `ClassName.method()` syntax is used.
Swift `class` methods (only in classes) can be overridden in subclasses and their calls are dynamically dispatched
when `type.method()` syntax is used. Type references can be retrieved via `ClassName.Type` syntax for a
statically known class or via `type(of: obj)` for a run-time object.
Swift protocols can only declare `static` methods which serve as _requirements_ for the classes that
adopt the corresponding protocols (both `class` and `static` methods in an adopting class meet the requirement),
and they are dynamically dispatched using `type.method()` syntax.
There are no statically dispatched protocol members in Swift. There is no call-site syntax of `ProtocolName.method()`.

**Rust** distinguishes static and instance members by the presence of the first `&self`/`self` parameter in a method
declaration. Calls to static members in Rust are statically dispatched using `ClassName::method()` syntax.
Static methods of Rust traits are dispatched dynamically.
There are no statically dispatched trait members in Rust. There is no call-site syntax of `TraitName::method()`.

## Potential future improvements

This section gives an overview of potential future extensions that have narrower uses are not likely to be
implemented in the initial release, but can be added later if needed.
It does not go into the details of their design.

### Static scope projection reference

In the initial design presented in this KEEP, the `<ClassName>.static` syntax is only used
to declare [Static extensions](#static-extensions) on classes and interface, and
[Extensions on static objects](#extensions-on-static-objects).

`<ClassName>.static` syntax can be extended in the
future with value semantics to represent both the value and its own type.
We'll call it _static scope projection_ since it refers to static declarations from class, interface, or static object.

As a value, static scope projection can be used to bring the static scope of other classes, interfaces, or objects
into the code block using scope functions, similarly to wildcard import, but with local effect.

For example:

```kotlin
fun test() {
    with(Color.static) { // bring static scope using a scope function
        RED // can access static member of Color via a simple name
    }
}
```

A static scope projection value does not have a stable identity and is internally implemented as a special-purpose
value class whose scope contains all the static declarations from the scope of the corresponding class.

The value of the static projection `<ClassName>.static` it the only member of its own type `<ClassName>.static`
that is a direct subtype of `Any`. The types of different static scope project are always unreleased despite
relations of the corresponding classes. For example, even if class `B` is subclass of class `A`, the `B.static` type
is not a subtype of `A.static` type.

### Static interfaces and static overrides

In the initial design presented in this KEEP, static methods cannot be overridden and cannot be dispatched dynamically,
static objects cannot implement interfaces. This can be changed by introducing a concept of a _static interface_.
The prerequisite of this feature includes support for [Static scope projection reference](#static-scope-projection-reference).
For example:

```kotlin
static interface Parseable<T> {
    fun parse(s: String): T // static interface with `parse` function
}
```

Unlike regular interfaces, that establish requirements for instance methods, static interfaces will establish
requirements for static methods and could be implemented by the static methods of classes, interfaces, or static objects.
For example:

**Option: Static section syntax.**

```kotlin
class Color(val rgb: Int) : Parseable<Color> {
    static {
        override fun parse(s: String): Color { /* impl */ }
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Color(val rgb: Int) : Parseable<Color> {
    static override fun parse(s: String): Color { /* impl */ }
}
```

When a class implements a static interface its instances do not implement the static interface, but
its static scope projection does. For example:

```kotlin
fun main() {
    val parser: Parseable<Color> = Color.static // OK
    val color = parser.parse("red") // OK
    println(color is Parseable<Color>) // Prints "false"
}
```

### Static operators

In this initial design presented in this KEEP, the static methods and static extensions cannot have an `operator` modifier.
However, some operators can have interesting use-cases when declared as static members or static extensions.

For example, as shown in the [Constructor functions](#constructor-functions) section, it
is worthwhile to allow `static operator fun invoke(...)` in the future.

Other Kotlin features in the future might be totally reliant on static operators for their functioning.
For example, [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871) collection literals
can use `static operator fun buildCollection(...)` declared on a type as a static member or a static extension to
figure out how the instance of the corresponding type shall be constructed from the collection literal syntax,
depending on the context in which the corresponding literal is used. For example:

```kotlin
val list: List<Int> = [1, 2, 3] // calls List.buildCollection(...) operator function
val set: Set<Int> = [1, 2, 3] // calls Set.buildCollection(...) operator function
```

## Open issues

This section lists open issues in the design and discusses alternatives for some of the design decisions.

### Static section vs static modifier

The major open issue of this design is the declaration syntax for static members of classes and interface.
There are two choices: **static sections** and **static modifier**. The following summary lists benefits
of each of the choices:

**Option: Static section syntax.**

Benefits of static section syntax:

* It is easy to migrate existing companion object declarations to statics in cases when the companion object
  instance was not really needed by simply replacing `companion object` with `static`. The concept of
  static section should be easy to learn for existing Kotlin developers.
* Currently established practice of grouping all statics together that is enforced by the companion object
  concept will continue to be softly enforced with `static {}` section, yet providing some additional
  flexibility for large classes that can declare multiple static sections.
* With `static {}` section syntax for static members declaration of static extension
  as `fun SomeClass.static.ext()` becomes mnemonic. You can picture it in your mind as placing the
  extension into the static section of the corresponding class.
* With static sections it becomes easier to make sense out of more complicated declarations like
  [Extension functions as static members](#extension-functions-as-static-members).

Disadvantages of static section syntax:

* Single static declarations become more visually verbose as they have to be wrapped into the static section,
  and all static declaration bodies acquire an additional level of indentation.
* Static section syntax is different from the [statics in other languages](#statics-in-other-languages),
  so it would put a learning barrier for Kotlin developers coming from other languages.

In order to see how the syntax of static methods and extensions work together with **static section syntax**,
here is the showcase of various possible combinations:

```kotlin
class Example {
    fun AnotherClass.foo1() {} // Instance extension for another class declared as member
    fun AnotherClass.static.foo2() {} // Static extension for another class declared as member
    static {
        fun AnotherClass.foo3() {} // Instance extension for another class declared as static member
        fun AnotherClass.static.foo4() {} // Static extension for another class declared as static member
        fun foo5() {} // Static method
    }
}
```

**Option: Static modifier syntax.**

Benefits of static modifier syntax:

* It makes statics in Kotlin work very much [statics in other languages](#statics-in-other-languages).
* The syntax of static modifier is more concise for occasional static declarations and consumes less horizontal space
  for writing their bodies.

Disadvantages of static modifier syntax:

* Migration from `companion object` to static modifiers will change every line of code that has static method declarations
  and their bodies due to change in indentation. The concept of statics will be very different from the concept of
  companion objects that Kotlin developers know and use nowadays.
* The language will cease to enforce the placement of all the statics together, leading to move varied codebases
  with statics scattered around.
* The distinction between static methods `static fun foo()` and static extensions `fun SomeClass.static.ext()` becomes
  harder to discern and to visualize. It could be even more confusing in other combinations. For example, the
  distinction between `static fun AnotherClass.ext()` (extension for another class declared as static member) and
  `fun AnotherClass.static.ext()` (static extension for another class) will be harder
  to explain and to articulate in words, as they look and read very much alike.

In order to see how the syntax of static methods and extensions work together with **static modifier syntax**,
here is the showcase of various possible combinations:

```kotlin
class Example {
    fun AnotherClass.foo1() {} // Instance extension for another class declared as member
    fun AnotherClass.static.foo2() {} // Static extension for another class declared as member
    static fun AnotherClass.foo3() {} // Instance extension for another class declared as static member
    static fun AnotherClass.static.foo4() {} // Static extension for another class declared as static member
    static fun foo5() {} // Static method
}
```

**Not an option: Support both static section and static modifier syntax.**

Note, that supporting both static section and static modifier syntax is not an option, especially in the initial
support of statics in Kotlin. This would move the choice of syntax to the coding style, which would make both the
problem of picking a more suitable syntax and the problem of learning the Kotlin language even more complicated.
We'll have to make once choice of syntax and accept all its disadvantages for the sake of having a consistent syntax
across Kotlin codebases.

### Callable references to static members

The design of callable references to static members via `::member` or `ClassName::member` syntax is to be specified in
this KEEP later, but they should definitely become available with the initial release of statics.

One thing to note is that statics will solve another long-standing problems with
companion objects related to references, see [KT-9315](https://youtrack.jetbrains.com/issue/KT-9315)
"A companion object should be able to make property references of containing class that are not prefixed by
classname and not ambiguous".

In particular, the following code (adapted from the above issue) shall work with statics out-of-the-box:

**Option: Static section syntax.**

```kotlin
class Outer(val one: String, val two: String) {
    static {
        fun createMappings(): List<String> =
            setOf(::one, ::two).map { it.name } // WORKS! No need to write Outer::one, Outer::two
    }
}
```

**Option: Static modifier syntax.**

```kotlin
class Outer(val one: String, val two: String) {
    static fun createMappings(): List<String> =
        setOf(::one, ::two).map { it.name } // WORKS! No need to write Outer::one, Outer::two
}
```

### Static soft keyword ambiguity

Usage of `static`soft keyword in the syntax of [Static extensions](#static-extensions) creates the following
ambiguity:

```kotlin
class Example {
    class static {} // a valid declaration of a nested class
}

// Currently parsed as extension on `Example.static` class.
fun Example.static.ext() {}
```

The detailed design on how to deal with this ambiguity is TBD. Initially, the compiler will have to parse this
code as it was parsed before, which will complicate the implementation of `Example.static` construct as it'll require
the extra resolution step. We'll need to develop some kind of deprecation cycle to remove this ambiguity.
The reasonable approach to such deprecation is to deprecate all nested and inner classes, interfaces, and objects
with the name `static`.

### Migration of objects to static objects

We expect a large number of companion objects to be migrated to statics. This is a easy in cases where the
corresponding companion objects were not part of the stable public API. However, change from companion object to
statics is not binary compatible, so it is not an option for stable libraries API. The same story is for
migration from regular `object` declaration that are used as a namespace to `static object` declarations
(e.g. `Delegates` from the standard library).

So, we'll need to design a mechanism to perform such migration in gradual way. The most obvious approach it to
add some kind of `@ToBeMigratedToStatics` declaration for objects (including companion objects) that has the
following effects:

* It makes it deprecated to use the value and the type of the corresponding object for any other
  purpose as directly calling its members.
* On JVM, it will add static bridges to all the members of such an object as if they were marked with `@JvmStatic`,
  with an important difference that the code will actually move into the static method, while the instance method
  will be retained for binary compatibility as a bridge.
* On JVM, it will compile all new call calling such members to call the corresponding static method, so
  that when the members are made truly static, this code still links and works.
* It will allow importing members of migrating companion objects via `import ClassName.member` syntax, instead
  of `import ClassName.Companion.member` syntax, the latter syntax being deprecated for companion objects under migration.

This way, the libraries can first mark their to-be-migrated object with this new annotation, then give plenty of time
to their users to recompile their code, and then replace the objects with statics. The transition time depends on
the library compatibility policy. In practice, it means that the Kotlin standard library will be forever in such
migration mode for its old companion object declarations.

### Reflection

Support for static members, static extensions, and static objects in Kotlin reflection will have to
be designed later and added to this document.

### Deprecate superclass scope linking

In Kotlin, class scope is [linked](https://kotlinlang.org/spec/scopes-and-identifiers.html#linked-scopes) to the scope
of its parent classes in Kotlin, so inside the class it is possible to access existing static declarations
(nested classes, interfaces, objects, companion object members, and Java statics) from its superclasses
(but not from its superinterfaces) using a short name. For example:

```kotlin
open class Parent {
    class Nested
}

class Derived : Parent() {
    val x = Nested() // Works using short name of Nested
}
```

We should research how often this feature in used in practice and consider deprecating this feature.
The fix during deprecation will be to explicitly import the class in need via `import Parent.Nested` or
to use its fully qualified name in code. IDE can be made smart enough to automatically add the corresponding
import when the short name is typed.

### Mangling scheme for static extensions on JVM

[Static extensions on JVM](#static-extensions-on-jvm) section gives one scheme for mangling the name
of static extensions so that it is readable nicely on JVM. It is not clear-cut decision on which scheme to choose,
so here is a larger list of options. They vary in the separator that is used (or an absence thereof) and
in the order of components. As example, we'll show JVM names for the following static extensions:

```kotlin
val Color.static.background: Color       // Color.background
fun Color.static.parse(s: String): Color // Color.parse
fun <T> Box.static.of(value: T): Box<T>  // Box.of
```

| Alternative  | Scheme                  | `Color.background` on JVM | `Color.parse` on JVM | `Box.of` on JVM |
|--------------|-------------------------|---------------------------|----------------------|-----------------|
| 0 (proposed) | Class$name              | `getColor$background`     | `Color$parse`        | `Box$of`        |
| 1            | name$Class              | `getBackground$Color`     | `parse$Color`        | `of$Box`        |
| 2            | lower(Class)upper(name) | `getColorBackground`      | `colorParse`         | `boxOf`         |
| 3            | nameClass               | `getBackgroundColor`      | `parseColor`         | `ofBox`         |
| 4            | Class_name              | `getColor_background`     | `Color_parse`        | `Box_of`        |
| 5            | Class::name             | `getColor::background`    | `Color::parse`       | `Box::of`       |

### ABI for non-JVM platforms

ABI for non-JVM platforms will have to be designed and added to this document. Kotlin/JVM platform provides
the strictest backwards and forwards compatibility guarantees, as well as seamless two-way interoperability, hence
the JVM ABI is taken care of first.

### Static object alternatives and namespaces

The `static object` syntax from [Static objects](#static-objects) section of this proposal is one of its most
controversial parts with a number of alternative, but it is the result of a pragmatic compromise.

An early prototype of this design used a `namespace` keyword instead of `static object`
and attempted to base the whole terminology around the concept of a namespace. So, static members were namespace members
and static extensions were namespace extensions. And here in lies the problem: static objects feature is a fringe part
of this design proposal. The feature that every Kotlin developer will use are static members, some will also use static
extensions, and only a few will use static objects. So, a good design has to get the most important,
the most actively used part of the proposal right &mdash; static members. However, it would be inappropriate
to call them "namespace members" in Kotlin. An overwhelming majority of popular programming languages calls
them static members, see [statics in other languages](#statics-in-other-languages). It is not in Kotlin design
philosophy to invent a new name for a long-established concept, even if that new name might somehow better
reflect the concept.

So, having made the decision to call static members what everyone calls the &mdash; static members, we come to
the hard choice of picking the name for the concept of static objects. Now, remember that static objects are
going to be a fringe, rarely used feature. Inventing a totally new name for the rarely used feature is not an
option. To minimize the number of concepts in the language, it has to reuse the concept of statics.

So, static it is, but static what? The default way of grouping a bunch of functions and, optionally, some state
in Kotlin is through an object. In Kotlin, you can allocate an anonymous object using `object {}` expression
or declare a named object using `object XXX {}` declaration. In both cases, you get a thing that posses a
stable reference as many things in Kotlin do. But not all things in Kotlin posses a stable reference. Instances of
numbers in Kotlin and instances of other value classes are objects in Kotlin, but they don't have a stable
reference. So, having a stable reference might initially seem to be an integral part of being an object, but it is not.

This observation reflects the pragmatic approach to choosing defaults in Kotlin. It is easier to see in comparison
with a hypothetical programming language that puts performance above pragmatics as the cornerstone of
its design philosophy. In this hypothetical performance-centered language every feature that adds any kind of
runtime costs would require developer to write extra code to acknowledge and be explicit about such cost.
For example, a provision of stable reference for objects incurs costs, so in that hypothetical language
objects would not have any stable reference by default, unless explicitly requested. However, in Kotlin defaults
are chosen pragmatically, to reflect the most common usage for writing application-level code.

In Kotlin design philosophy application developers are primarily concerned with the business logic of their code and
performance is a secondary concern, if a concern at all. For performance-sensitive code Kotlin provides additional
opt-in capabilities to be used by experts who write performance-sensitive code. One such performance-oriented
capability is a `value class`, and another such performance-oriented capability is a `static object`.
They take away performance-affecting "provides a stable reference" feature of classes and objects respectively
for those cases where you can do away without it. If you take legal Kotlin code and replace `value class` with `class`
and `static object` with `object` the code will mostly compile and work as before
(maybe with few non-essential differences), but will lose some efficiency.

It is perfectly fine if developer who wants to combine several functions into a common namespace uses `object XXX {}`
for that purpose. If they know about static objects they can gain a bit of additional efficiency by declaring
their functions inside a `static object XXX {}`, but there is not much harm is they don't. That is another reason while
static objects do not deserve a separate concept on their own.

From the syntactic point of view, we can consider an option of using `static XXX {}` instead of a `static object XXX {}`
and name them "named static sections" instead of "static objects". However, this option is problematic for two main reasons.
First, it requires `static` to become a hard keyword on par with `fun`, `class`, `interface`, etc. That is
a way more disruptive and more breaking change. Second, it breaks the spirit of Kotlin design that tries to minimize the
number of core concepts, opting to modify them instead. Kotlin does not have `enum`, but `enum class`, Kotlin does
not have `record` but `data class`, etc. It is in Kotlin spirit to have `static object` as opposed to a separate concept.

Another alternative for the `static object` concept is a "local package". That is, we can add `package XXX {}` syntax
instead of `static object XXX {}`. The package and the static object are really similar &mdash; they contain the same kind 
of static declarations, they are imported in a similar way, etc. Two downsides of this choice are differences in the 
naming convention that are already established (lowercase for packages and camelcase for objects) and the confusion 
it might cause on JVM, as local packages will not be represented by packages on JVM.

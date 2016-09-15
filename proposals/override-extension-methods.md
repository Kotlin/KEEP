# Overriding Extension Methods

* **Type**: Design proposal
* **Author**: Johannes Neubauer
* **Status**: Shepherded
* **Shepherd**: [@dnpetrov](https://github.com/dnpetrov)
* **Prototype**: *not yet*

## Feedback 

Discussion has been started in [this pull request](https://github.com/Kotlin/KEEP/pull/35) regarding type-checking of reified type parameters so far. Further discussion should be done in [the pull request for this proposal](https://github.com/Kotlin/KEEP/pull/46). An issue in the KEEP project has to be opened.

## Summary

Support dynamic dispatch for the extension receiver of extension methods via allowing to override extension functions.

## Motivation

Extension methods are statically dispatched. Currently, dispatching has to be done manually (if necessary), which is boiler-plate and error-prone. Often this is the intended behavior and it is much faster than dynamic dispatch. Hence, adding dynamic dispatch as standard behavior is not an option, especially as the *implicit extension receiver* is used for dynamic dispatch of extension functions, if the extension method is defined on a class. Additionally, this would break a lot of existing code.

Instead introducing the concept of overriding for extension functions (analogous to overriding member functions) is proposed. This proposal is intended to be the first in a row (iff successful) for adding sophisticated semantics for function/method lookup that allows to create behavior for objects that is more object-oriented, and at the same time offers type-safety, performance (where necessary), and backward compatibility (no source-breaking).

## Description

The following example shows a possible syntax as well as semantics for function overriding for an *(explicit) extension receiver* in action. First, let us see how it works for member functions and then we will take a look at a solution for extension functions.

The task is to create a (more or less) complex inheritance structure of classes with one (root) base class which offers the possibility to copy instances of any object of these classes and call polymorphically a method `foo()` on the copies. All this should be able using a list with the root class as type argument:

```kotlin
fun main(args: Array<String>) {
    val l = arrayOf(A(), B(), C(), D())
    
    // prints "A\nB\nC\nD\n"
    l.map { it.copy() }.forEach { it.foo() }
}

open class A {
    open fun foo() {
        println("A")
    }

    open fun copy(): A {
        val copy = this.javaClass.newInstance()
        // do copy stuff
        return copy
    }
}

open class B: A() {
    override fun foo() {
        println("B")
    }

    override fun copy(): B {
        val copy = super.copy() as B
        // do copy stuff
        return copy
    }
}

open class C: B() {
    override fun foo() {
        println("C")
    }

    override fun copy(): C {
        val copy = super.copy() as C
        // do copy stuff
        return copy
    }
}

open class D: A() {
    override fun foo() {
        println("D")
    }

    override fun copy(): D {
        val copy = super.copy() as D
        // do copy stuff
        return copy
    }
}
```

The cast in each `copy()`-method can be avoided by using generics like this (only implementation of `A` and `B` for brevity):

```kotlin
open class A {
    open protected fun <T: A> copy(clazz: Class<out T>): T {
        val a = clazz.newInstance()
        // do copy stuff
        return a
    }

    open fun foo() {
        println("A")
    }

    open fun copy(): A {
        return copy(this.javaClass)
    }
}

open class B: A() {
    override fun <T: A> copy(clazz: Class<out T>): T {
        val b = super.copy(clazz)
        // do copy stuff
        return b
    }

    override fun foo() {
        println("B")
    }

    override fun copy(): B {
        return copy(this.javaClass)
    }
}
```

Now consider a scenario, where we have an existing (compilation) unit/module *M1* that is not under our control, e.g., a third-party library. In M1 a set of interfaces exists, that all inherit from a base interface `A` and have a complex inheritance structure among each other (so very similar to the former issue):

```kotlin
// module M1
package m1

interface A
interface B: A
interface C: A
interface D: C
interface E: B, C
// ...
```

Our client code of the third-party module M1 is called *M2*. Assume that we need to copy a list of objects conforming to `A` and again call method `foo` on it (dynamically dispatched):

```kotlin
// module M2
package m2

val l = // retrieve some objects conforming to `A`, `B`, ...

// should output "A\nB\nC\nD"
l.map { it.copy() }.forEach { it.foo() }

```

Unfortunately, the interface currently does not offers neither a copy nor a foo method. So the task is to implement a method `foo` in M2 which takes an object of any subtype of `A` and prints out the name of the class of the extension receiver (if an override is available for this class):

```kotlin
// (still) module M2

// mark as overridable
open fun A.foo() {
    println("A")
}

//overrides A.foo
override fun B.foo() {
    println("B")
}

//overrides B.foo
override fun C.foo() {
    println("C")
}

//overrides A.foo
override fun D.foo() {
    println("D")
}
```

Next let us define a corresponding copy method taking an object of any subtype of `A` and returning a copy of that object (using for brevity the variant with cast from above). Of course, if no extension method exists for a subclass/interface of `A` the copy method on `A` will be used:

```kotlin
// (still) module M2

// mark as overridable
open fun A.copy(): A {
    val copy = this.javaClass.newInstance()
    // do copy stuff
    return copy
}

//overrides A.copy
override fun B.copy(): B {
    val copy = super.copy() as B
    // do copy stuff
    return copy
}

//overrides B.copy
override fun C.copy(): C {
    val copy = super.copy() as C
    // do copy stuff
    return copy
}

//overrides A.copy
override fun D.copy(): D {
    val copy = super.copy() as D
    // do copy stuff
    return copy
}
```

This should **not** be limited to methods with no parameters. It should just behave like overriding normal member methods (parameters are statically dispatched). The same holds for overloading methods in a type (but this works already as expected in extension functions).

The scope of such an extension method (**both** overriding or not) is the same as before. So, if we have a third compilation unit *M3* (or another package `m3`) the extension methods do not interfere with our local ones:

```kotlin
// module M3
package m3

open fun A.foo() {
    println("extension for A in M3.")
}

val b = B()

// prints "extension for A in M3." and **not** "B"
b.foo()
```

If we import the other extension it is used and name clashes occur as usual:

```kotlin
// (still) module M3

// import all extension methods from module M2 (consider that the package `m2` is in M2) 
import m2.*

// results in an compilation error "This function has the same signature as m2.foo(A)"
open fun.A.foo() {
  println("extension for A in M3.")
}
```

## Interplay with Type Parameters

A nice sideeffect of this proposal for extension functions with a type parameter as extension receiver could be, that you can do something, that is not possible with member functions, easily:

```kotlin
// (alternative variant of) module M2

// mark as overridable
open fun <T: A> T.copy(): T {
    val copy = this.javaClass.newInstance()
    // do copy stuff
    return copy
}

//overrides `fun <T: A> T.copy`
override fun <T: B> T.copy(): T {
    val copy = super.copy()
    // do copy stuff
    return copy
}

//overrides `fun <T: B> T.copy`
override fun <T: C> T.copy(): T {
    val copy = super.copy()
    // do copy stuff
    return copy
}

//overrides `fun <T: A> T.copy`
override fun <T: D> T.copy(): T {
    val copy = super.copy()
    // do copy stuff
    return copy
}
```

## Realization

Technically this can be realized via a single dispatch method with the most general extension receiver type, which uses a `when` expression that takes into account all overriding extension functions in scope (including imported ones) and makes a static call to the most specialized matching function. The respective overriding functions get a leading `_` to distinguish them. The following example shows a realization (what the compiler would output) in *pseudo kotlin*. First, let us see how the code we give the compiler would look like:

```kotlin
// module M1
package m1

open class A
open class B: A()
open class C: B()
open class D: A()

// module M2
package m2
import m1.*

open fun A.foo() {
    println("A")
}

override fun B.foo() {
    println("B")
}

override fun C.foo() {
    println("C")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nB\nC\nA\n" since we have no override for `D`
l.forEach { it.foo() }

// module M3 (in this example we add here the function `D.foo`)
package m3
import m1.*
import m2.*

override fun D.foo() {
    println("D")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nB\nC\nD\n"
l.forEach { it.foo() }
```

Second, we take a look at the compilers pseudo kotlin output would look like (it is not really "pseudo" as it compiles and works in kotlin 1.0.3 😇):

```kotlin
// module M1
package m1

open class A
open class B: A()
open class C: B()
open class D: A()

// module M2
package m2
import m1.*

fun A.foo() {
    when(this) {
        is C -> _foo(c = this)
        is B -> _foo(b = this)
        is A -> _foo(a = this)
    }
}

fun _foo(a: A) {
    println("A")
}

fun _foo(b: B) {
    println("B")
}

fun _foo(c: C) {
    println("C")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nB\nC\nA\n" since we have no override for `D`
l.forEach { it.foo() }

// module M3 (in this example we add here the function `D.foo`)
package m3

import m2._foo
import m1.*

fun A.foo() {
    when(this) {
        is D -> _foo(d = this)
        is C -> _foo(c = this)
        is B -> _foo(b = this)
        is A -> _foo(a = this)
    }
}

fun _foo(d: D) {
    println("D")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nB\nC\nD\n"
l.forEach { it.foo() }
```

If we would not add any override in M3 the dispatch method `A.foo` would be imported instead of redefining it, using the `_foo` as in the example above.

Next, let us take a look at the realization of `super`-calls in overriden extension methods. Take this enhanced example in (future-) kotlin:

```kotlin
// module M1
package m1

open class A
open class B: A()
open class C: B()
open class D: A()

// module M2
package m2
import m1.*

open fun A.foo() {
    print("A")
}

override fun B.foo() {
    super.foo()
    print("B")
}

override fun C.foo() {
    super.foo()
    print("C")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nAB\nABC\nA" since we have no override for `D`
l.forEach { it.foo() }

// module M3 (in this example we add here the function `D.foo`)
package m3
import m1.*
import m2.*

override fun D.foo() {
    super.foo()
    print("D")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nAB\nABC\nAD"
l.forEach { it.foo(); println() }
```

The realization in pseudo kotlin compiler output would add an additional implicit parameter to all overriden `_*` methods which retrieves the jump address (here it is a lambda) to the super function (this again compiles well in kotlin 1.0.3 😇:

```kotlin
// module M1
package m1

open class A
open class B: A()
open class C: B()
open class D: A()

// module M2
package m2
import m1.*

fun A.foo() {
    when(this) {
        is C -> _foo(c = this, superFunction = { c: C -> _foo(b = c, superFunction = { b: B -> _foo(a = b) }) })
        is B -> _foo(b = this, superFunction = { b: B -> _foo(a = b) })
        is A -> _foo(a = this)
    }
}

fun _foo(a: A) {
    print("A")
}

fun _foo(b: B, superFunction: (B) -> Unit) {
    superFunction(b)
    print("B")
}

fun _foo(c: C, superFunction: (C) -> Unit) {
    superFunction(c)
    print("C")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nAB\nABC\nA" since we have no override for `D`
l.forEach { it.foo() }

// module M3 (in this example we add here the function `D.foo`)
package m3

import m2._foo
import m1.*

fun A.foo() {
    when(this) {
        is D -> _foo(d = this, superFunction = { d: D -> _foo(a = d) })
        is C -> _foo(c = this, superFunction = { c: C -> _foo(b = c, superFunction = { b: B -> _foo(a = b) }) })
        is B -> _foo(b = this, superFunction = { b: B -> _foo(a = b) })
        is A -> _foo(a = this)
    }
}

fun _foo(d: D, superFunction: (D) -> Unit) {
    superFunction(d)
    print("D")
}

val l = arrayOf(A(), B(), C(), D())

// prints "A\nAB\nABC\nAD"
l.forEach { it.foo() }
```

## Outlook

A possible addition to this proposal would be to allow to override `open` member functions via extension functions for (sub)classes that do not overide the given method of the superclass themselves like this (introducing a new keyword `overload`):

```kotlin
// M1
package m1

open class A {
  open fun foo() {
    println("A")
  }
}

open class B: A()

// M2
package m2

override fun B.foo() {
  println("B")
}
```

Another addition would be to allow to explicitly overload methods in subtypes if they are defined open like this:

```kotlin
open class A {
  open fun eat(a: A) {
    println("delicious")
  }
}

open class B {
  overload fun eat(b: B) {
    println("tasty")
  }
}

val b: A = B()

// prints "tasty"
b.eat(b)

val a = A()

//prints "delicious"
a.eat(b)
```

As overloading currently works on the same class (and not on a type hierarchy), only. This would be something that could be added to extension functions analogously:

```kotlin
interface A
interface B: A

open fun A.eat(a: A) {
  println("delicious")
}

overload fun eat(b: B) {
  println("tasty")
}
```

This is **not** about co- and contravariance, but just about overloading of methods in a type hierarchy.

## Further Discussion

This feature is completely additive as the current behavior stays the same although I think that the current overload behavior of extension functions in kotlin is inconsistent with member functions: It is allowed to overload an extension function with a function of the same signature (and statically dispatched) but not on member functions (when omitting the open keyword). It would be more consistent to allow this on member functions, too (which is completly additive):

```kotlin
open class A {
  fun foo() {
    println("A")
  }
}

class B: A {
  // should be allowed and overload A.foo since A.foo is not `open`. 
  // If A.foo would have been `open`, this would be denied and the 
  // keyword `override` is required (and then it behaves like a normal 
  // overriden method).
  fun foo() {
    println("B")
  }
}

val b: A = B()
b.foo() // prints "A"
val b2 = B()
b2.foo() // prints "B"

// rationale, this is perfectly ok in current kotlin:

open class A
class B: A

fun A.foo() {
  println("A")
}

fun B.foo() {
  println("B")
}

val b: A = B()
b.foo() // prints "A"
val b2 = B()
b2.foo() // prints "B"
``` 

## Open Questions

Is overriding as shown in section "Interplay with Type Parameters" ok?

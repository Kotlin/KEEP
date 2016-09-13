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

Now consider a scenario, where we have an existing compilation unit (module) *M1* that is not under our control, e.g., a third-party library. In M1 a set of interfaces exists, that all inherit from a base interface `A` and have a complex inheritance structure among each other (so very similar to the former issue):

```kotlin
// compilation unit M1
interface A
interface B: A
interface C: A
interface D: C
interface E: B, C
// ...
```

Our client code of the third-party module M1 is called *M2*. Assume that we need to copy a list of objects conforming to `A` and again call method `foo` on it (dynamically dispatched):

```kotlin
// compilation unit M2

val l = // retrieve some objects conforming to `A`, `B`, ...

// should output "A\nB\nC\nD"
l.map { it.copy() }.forEach { it.foo() }

```

Unfortunately, the interface currently does not offers neither a copy nor a foo method. So the task is to implement a method `foo` in M2 which takes an object of any subtype of `A` and prints out the name of the class of the extension receiver (if an override is available for this class):

```kotlin
// (still) compilation unit M2

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

## Outlook

A possible addition to this proposal would be to allow to override `open` member functions via extension functions for (sub)classes that do not overide the given method of the superclass themselves like this (introducing a new keyword `overload`):

```kotlin
// M1
open class A {
  open fun foo() {
    println("A")
  }
}

open class B: A()

// M2
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

## Open Questions

*Not yet.*

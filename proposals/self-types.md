# Self types

* **Type**: Design proposal
* **Authors**: Maksim Grankin
* **Status**: Prototype implemented
* **Issue**: [KT-6494](https://youtrack.jetbrains.com/issue/KT-6494)


A **Self type** is type that refers to the receiver type.

```kotlin
open class A {
    fun foo(): Self {
        return this;
    }
}

class B : A() {
    fun b() {
        println("Inside B")
    }
}

val x = B().foo() // foo return type is B
x.b() // stdout: Inside B
```

## Motivation

Self types can be implemented by programmer with boilerplate code using recursive generics and some additional unchecked casts. 
They can be used in multiple useful and popular patterns, so there is a reason and community need to make it a language feature.

## Usage examples

One of the most common example of Self types application is [abstract builder pattern](https://medium.com/@hazraarka072/fluent-builder-and-powering-it-up-with-recursive-generics-in-java-483005a85fcd). However, in Kotlin builders are usually implemented via extension recievers. Although, if we want to have transformation chain of immutable object/data, Self types are really usefull.

### Transformation chains

Using transformation chains we can implement *Lazy* containers. *Lazy* container is container that contains computation for some *real* container. This allows to create a sequence of changes to container without computing it only when needed.

```kotlin

abstract class Lazy<T, out Self : Lazy<T, Self>>(val computation: () -> T) {
    protected abstract fun create(computation: () -> T): Self
}

abstract class LazyContainer<T, out Self : Lazy<T, Self>>(computation: () -> T) :
    Lazy<T, Self>(computation) {
    fun applyFunction(f: (T) -> T): Self = create { f(computation()) }
}

class LazyList<T>(computation: () -> List<T>) : LazyContainer<List<T>, LazyList<T>>(computation) {
    override fun create(computation: () -> List<T>): LazyList<T> = LazyList(computation)
    fun add(elem: T): LazyList<T> = create { computation() + elem }
}

class LazySet<T>(computation: () -> Set<T>) : LazyContainer<Set<T>, LazySet<T>>(computation) {
    override fun create(computation: () -> Set<T>): LazySet<T> = LazySet(computation)
    fun add(elem: T): LazySet<T> = create { computation() + elem }
}
val list = LazyList { listOf(1, 2, 3) }
			.applyFunction { l -> l.subList(1, 2) }
			.add(15)
			.computation()
val set = LazySet { setOf(1, 2, 3) }
			.applyFunction { s -> s.map { it + 1 }.toSet() }
			.add(3)
			.computation()
```

With **Self type** feature the same code would look much easier to read.

```kotlin
import kotlin.Self

@Self
abstract class Lazy<T>(val computation: () -> T) {
    protected abstract fun create(computation: () -> T): Self
}

@Self
abstract class LazyContainer<T>(computation: () -> T) :
    Lazy<T, Self>(computation) {
    fun applyFunction(f: (T) -> T): Self = create { f(computation()) }
}

class LazyList<T>(computation: () -> List<T>) : LazyContainer<List<T>, LazyList<T>>(computation) {
    override fun create(computation: () -> List<T>): LazyList<T> = LazyList(computation)
    fun add(elem: T): LazyList<T> = create { computation() + elem }
}

class LazySet<T>(computation: () -> Set<T>) : LazyContainer<Set<T>, LazySet<T>>(computation) {
    override fun create(computation: () -> Set<T>): LazySet<T> = LazySet(computation)
    fun add(elem: T): LazySet<T> = create { computation() + elem }
}
val list = LazyList { listOf(1, 2, 3) }
			.applyFunction { l -> l.subList(1, 2) }
			.add(15)
			.computation()
val set = LazySet { setOf(1, 2, 3) }
			.applyFunction { s -> s.map { it + 1 }.toSet() }
			.add(3)
			.computation()
```

### Observer pattern

Observer pattern can also be implemented using **Self types**. 

```kotlin
abstract class AbstractObservable<out Self : AbstractObservable<Self>> {
    private val observers: MutableList<(Self) -> Unit> = mutableListOf()

    fun observe(observer: (Self) -> Unit) {
        observers += observer
    }

    @Suppress("UNCHECKED_CAST")
    protected fun notification() {
        observers.forEach { observer ->
            observer(this as Self)
        }
    }
}

class User(val name: String) : AbstractObservable<User>() {
    val friends: MutableList<User> = mutableListOf()

    var status: String? = null
        set(value) {
            field = value
            notification()
        }
}

class Company(val name: String) : AbstractObservable<Company>() {
    val potentialEmployees: MutableList<User> = mutableListOf()

    var hiring: Boolean = false
        set(value) {
            field = value
            notification()
        }
}

val user1 = User("Maxim").apply {
    observe {
        if (it.status != null) {
            it.friends.forEach { friend ->
                println("Sending message to friend {${friend.name}} about new status: ${it.status}")
            }
        }
    }
}

val company: Company = Company("ITMO University").apply {
    observe {
        it.potentialEmployees.forEach { potentialEmployee ->
            println(
                "Sending notification to potential employee " +
                "{${potentialEmployee.name}} that company hiring status is:" +
                " ${if (it.hiring) "Hiring" else "Freeze"}."
            )
        }
    }
}

company.potentialEmployees.add(user1)
company.potentialEmployees.add(user2)

company.hiring = true
val user2 = User("Ivan")
user1.friends.add(user2)

user1.status = "Looking for a new job"
```

With **Self type** feature the same code would look much easier to read and **do not contain unchecked casts**

```kotlin
import kotlin.Self

@Self
abstract class AbstractObservable {
    private val observers: MutableList<(Self) -> Unit> = mutableListOf()

    fun observe(observer: (Self) -> Unit) {
        observers += observer
    }

    protected fun notification() {
        observers.forEach { observer ->
            observer(this)
        }
    }
}

class User(val name: String) : AbstractObservable<User>() {
    val friends: MutableList<User> = mutableListOf()

    var status: String? = null
        set(value) {
            field = value
            notification()
        }
}

class Company(val name: String) : AbstractObservable<Company>() {
    val potentialEmployees: MutableList<User> = mutableListOf()

    var hiring: Boolean = false
        set(value) {
            field = value
            notification()
        }
}

val user1 = User("Maxim").apply {
    observe {
        if (it.status != null) {
            it.friends.forEach { friend ->
                println("Sending message to friend {${friend.name}} about new status: ${it.status}")
            }
        }
    }
}

val company: Company = Company("ITMO University").apply {
    observe {
        it.potentialEmployees.forEach { potentialEmployee ->
            println(
                "Sending notification to potential employee " +
                "{${potentialEmployee.name}} that company hiring status is:" +
                " ${if (it.hiring) "Hiring" else "Freeze"}."
            )
        }
    }
}

company.potentialEmployees.add(user1)
company.potentialEmployees.add(user2)

company.hiring = true

// stdout: Sending notification to potential employee Maxim that company hiring status is: Hiring
// stdout: Sending notification to potential employee Ivan that company hiring status is: Hiring

val user2 = User("Ivan")
user1.friends.add(user2)

user1.status = "Looking for a new job"
// stdout: Sending message to friend Ivan about new status: Looking for a new job
```

### Recursive containers

```kotlin
abstract class AbstractNode<out Self : AbstractNode<Self>>(val children: List<Self>)

class Node<out Self : Node<Self>>(children: List<Self> = emptyList()) : AbstractNode<Self>(children) {
    fun doTheBest() = println(42)
}

val betterTree = Node<Node<*>>(listOf(
    Node<Node<*>>(),
    Node<Node<*>>(listOf(Node<Node<*>>()))
))

betterTree.children
    .flatMap { it.children }
    .forEach { it.doTheBest() }
```

With **Self type** feature the same code would look much easier to read.


```kotlin
import kotlin.Self

@Self
abstract class AbstractNode(val children: List<Self>)

@Self
class Node(children: List<Self> = emptyList()) : AbstractNode<Self>(children) {
    fun doTheBest() = println(1)
}

val betterTree = Node(listOf(
    Node(),
    Node(listOf(Node()))
))

betterTree.children
    .flatMap { it.children }
    .forEach { it.doTheBest() }
```

### Abstract factory


```kotlin
abstract class Element<out Factory>(val factory: Factory)

interface Factory<out Self : Factory<Self>> {
    fun create(): Element<Self>
}

abstract class SpecificFactory<out Self : SpecificFactory<Self>> : Factory<Self> {
    abstract fun doSpecific()
}

class ConcreteFactory : SpecificFactory<ConcreteFactory>() {
    override fun create(): Element<ConcreteFactory> = object : Element<ConcreteFactory>(this) {}
    override fun doSpecific() = println("Soo concrete!")
}

fun <Factory : SpecificFactory<Factory>> test(element: Element<Factory>) {
    element.factory.doSpecific()
}
```

With **Self type** feature the same code would look much easier to read.


```kotlin
abstract class Element<out Factory>(val factory: Factory)

@Self
interface Factory> {
    fun create(): Element<Self>
}

@Self
abstract class SpecificFactory : Factory<Self> {
    abstract fun doSpecific()
}

class ConcreteFactory : SpecificFactory<ConcreteFactory>() {
    override fun create(): Element<ConcreteFactory> = object : Element<ConcreteFactory>(this) {}
    override fun doSpecific() = println("Soo concrete!")
}

fun <Factory : SpecificFactory<Factory>> test(element: Element<Factory>) {
    element.factory.doSpecific()
}
```

## Design

Self-type behaves exactly same as covariant recursive generic type parameter that is bounded with bound.

```kotlin
interface Foo<out Self : Foo<Self>> {

}
```

Can be rewriten as:

```kotlin
import kotlin.Self

@Self
interface Foo {

}
```

Special type parameter for type `Self` would be in the end of type parameters list.

```kotlin
import kotlin.Self

@Self
interface Foo<A, B> {}

// This is not real representation inside compiler, but enough to understand for user.
interface Foo<A, B, out Self : Foo<A, B, Self>> {}
```

### `Self` bound

`Self` type is bounded to the nearest receiver.

```kotlin
@Self
class Foo {
    @Self
    class Bar {
        fun x(): Self // Self from Bar
    }

    fun y(): Self // Self from Foo
}
```

With this design decision it is imposible to access `Self` type of `Foo` inside `Bar`. It is considered to be not very popular situation, so user can create their own recursive generic for `Foo` or `Bar`.

### `this` assignment

Only `this` that refers to function receiver is assignable to the self-type with the correspoding bound.

```kotlin
@Self
abstract class A {
    fun self(): Self = this

    fun other(a: A): Self = a.self() // Do not compile as we cannot return Self of an other object
}
```

### Covariance

As type `Self` is `out` it can be used only in covariant position.

```kotlin
import kotlin.Self

@Self
interface Foo {
    fun foo(): Self // compiles
    fun bar(s: Self) // compile error
}
```

Input generic position:

```kotlin
import kotlin.Self

@Self
interface Foo {
    fun foo(f: Bar<Self>): Self // compiles
}
```

Return generic position:

```kotlin
import kotlin.Self

@Self
interface A {

}

@Self
interface B {
    fun a(): A<Self>
}

```
Super type argument position:

```kotlin
import kotlin.Self

@Self
interface Foo {}

@Self
interface Bar : Foo<Self> {}
```

### New instance of class with self-type

Type argument for `Self` type will be set implicitly, but also can be set explicitly by user.

```kotlin
import kotlin.Self

@Self
class Foo<T> {
    ...
}

val fooImplicit = Foo<Int>()
val fooExplicit = Foo<Int, Foo<Int, *>>()
```

## Self-types specification

We will use `{T}.Self` to describe type `Self` for type `T`. 

```kotlin
@Self
interface A {}
```

So type `Self` for interface `A` would be `A.Self`.

### Safe-values:

There are two types of values that can be typed as `Self` for type `A`.

1. `this`
2. `A` constructor call.


1. `B <: A => B.Self <: A.Self` to support override for methods with type `Self` in the return position.
2. `B <: A => B.Self <: A` so we can use `this` as the value for type `A`.
3. `Nothing <: A.Self` and `A.Self <: Any`.
4. `B !<: A.Self` if `B` does not fit rules (1) and (3).

Rule (4) guarantees that only values considered safe may have self-type.

Common supertype:

* `CST(B.Self, A) ~ CST(B, A)`

### Safe-positions

`Self` type is the same as the covariant recursive generic parameter, but with some additional implicit casts.

Self-type [positions](https://kotlinlang.org/spec/declarations.html#type-parameter-variance):

* If `A` is a dispatch receiver then `A.Self` can be used only in covariant positions.
* If `A` is an extension receiver then `A.Self` can be used in all positions.

Self-type [capturing](https://kotlinlang.org/spec/type-system.html#type-capturing):

* If `A` is a dispatch receiver then `A.Self` behaves as a covariant type argument:
  * For a covariant type parameter `out F` captured type `K <: A.Self`;
  * For invariant or contravariant type parameter `K` is ill-formed type.
* If `A` is an extension receiver then `A.Self` behaves as invariant type argument.

### Safe-calls

Self-type behaves as the same covariant recursive generic type parameter. So, self-type materializes to the eceirver type. Value will be validated on the declaration-site by safe-values rules.


## Other languages experience

### Swift

* [Self type documentation](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/types/#Self-Type)
* [Associated types](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/generics/#Associated-Types)

Swift protocols are like Rust traits (limited type classes). Protocols can be conformed (implemented) by classes. A class can inherit one another.

```swift
protocol Protoc {
    func className() -> Self
}

class Superclass : Protoc {
    func className() -> Self { return self }
}

class Subclass: Superclass { }

let a = Superclass()
print(type(of: a.className())) // Prints "Superclass"

let b = Subclass()
print(type(of: b.className())) // Prints "Subclass"

let c: Superclass = Subclass()
print(type(of: c.className())) // Prints "Subclass"

let d: Protoc = Subclass()
print(type(of: d.className())) // Prints "Subclass"

```

Swift prohibits using a new object where self is expected:

```swift
final class Foo {
    /*
    error: cannot convert return expression of type 'Foo' to return type 'Self'
    func f() -> Self { return Foo() }
     ^~~~~
                                    as! Self
    */
    func f() -> Self { return Foo() }
}
```

In non-return positions Self is available only in protocols and class declaration should use itself instead of Self:

```swift
/*
error: covariant 'Self' or 'Self?' can only appear as the type of a property, subscript or method result; did you mean 'A'?
*/
class A {
    func f(s: Self) -> Self { return s }
}

protocol P {
    func f(s: Self) -> Self
}

class B: P {
    func f(s: B) -> Self { return self }
}

protocol ArrayP {
    func f() -> Array<Self>
    func g(arr: Array<Self>) -> Array<Self>
}

class D: ArrayP {
    // error: covariant 'Self' or 'Self?' can only appear in the top level of method result type
    func f() -> Array<Self> { return Array() }

    // error: method 'f()' in non-final class 'D' must return 'Self' to conform to protocol 'ArrayP'
    func f() -> Array<D> { return Array() }

    // error: method 'g(arr:)' in non-final class 'D' must return 'Self' to conform to protocol 'ArrayP'
    func g(arr: Array<D>) -> Array<D> { return Array() }
}

final class E: ArrayP {
    func f() -> Array<E> { return Array() }
    func g(arr: Array<E>) -> Array<E> { arr }
}
```

### Associated types

Accociated types can be used to achive similar behavior to self-types, but for one-level hierarchy:

```swift
protocol Protoc {
    associatedtype S
    func f() -> S
    func g(s: S)
    func arr() -> Array<S>
}

class A: Protoc {
    typealias S = A
    func f() -> A { return self }
    func g(s: A) { s.specific() }
    func arr() -> Array<A> { return [self] }
    func specific() {}
}

class B: Protoc {
    override func f() -> B { return self }
    override func g(x: B) {}
    override func arr() -> Array<B> { return [self, self] }
}

func test(_ a: some S, _ b: some S) {
    a.g(s: a.f())
    a.g(s: b.f())
}
```

## TypeScript

[Documentation](https://www.typescriptlang.org/docs/handbook/2/classes.html#this-types)

```typescript
class A {
    foo(): this {
        return this;
    }
}

class B extends A {
    bar(): this {
        return this;
    }
}

var b: B = new B();
var x: B = b.foo().bar();
```

Type `this` was added in 1.7 version of TypeScript and some older code became non-compatible. Example:

```typescript
class Foo {
  foo() {
    var x = this;
    x = new Foo();
  }
}
```


In TypeScript >= 1.7 this code would fail with compilation error: `Type 'C' is not assignable to type 'this'. 'C' is assignable to the constraint of type 'this', but 'this' could be instantiated with a different subtype of constraint 'C'`.

This is the problem that we shouldn't have in Kotlin and that is why we use annotation `@Self` in this proposal.

## Rust

[Documentation](https://doc.rust-lang.org/reference/paths.html#self-1)

Obviously, `Rust` do not have inheritance and self-types implemented via macrosses, but example may be useful to understand how self-types may be used `Rust` engineer.

```rust
trait T {
    type Item;
    fn new() -> Self;
    fn f(&self) -> Self::Item;
}
```

Type `Self` in `Rust` also allows user to have a reference on any type alias within scope of receiver.

Type `Self` is reserved keyword, so some constructions give compilation error:

```rust
trait T {
    type Self;
}
```

`expected identifier, found keyword `Self`  expected identifier, found keyword`.

Type `Self` is also covariant.

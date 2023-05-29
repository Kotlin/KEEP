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

class B : A {
    
}

val x: B = B().foo(); // foo return type is B
```

## Motivation

Self types can be implemented by programmer with boilerplate code using recursive generics and some additional unchecked casts. 
They can be used in multiple useful and popular patterns, so there is a reason and community need to make it a language feature.

## Usage examples

One of the most common example of Self types application is [abstract builder pattern](https://medium.com/@hazraarka072/fluent-builder-and-powering-it-up-with-recursive-generics-in-java-483005a85fcd). However, in Kotlin builders are usually implemented via extension recievers.Although, if we want to have transformation chain of immutable object/data, Self types are really usefull.

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
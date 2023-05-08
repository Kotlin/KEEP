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

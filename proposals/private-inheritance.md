# Private inheritance

* **Type**: Design proposal
* **Author**: Roman Sakno
* **Contributors**: Roman Sakno
* **Status**: Under consideration
* **Prototype**: Not started

## Synopsis
Aggregation is one of mostly used patterns in OOP that helps to create more complex logic using combination of other projects with respect to encapsulation. Kotlin offers several ways to do that:

* [Implementation by Delegation](https://kotlinlang.org/docs/reference/delegation.html#implementation-by-delegation) which under the hood aggregates original implementation of interface and delegates method calls for top-level class to aggregates object.
* 


## Language implementation

```kotlin
class Derived(a: Int, b: Int): private Base1(a + b), Base2(a), Serializable{

  fun foo() = baz() //baz() from Base1
  fun bar() = this@Base1.baz() //the same but with explicit receiver
}
```

Even public members of aggregated object are not visible outside of top-level class.

Name resolution order:
1. Look for own member in the top-level class
1. Look for member in the inherited class
1. Look for member in companion object (if declared)
1. Look for member in privately inherited class

Alternative syntax:
```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable{
  companion val base2 = Base1(a + b) //or even companion val: Base1(a + b)
  
  fun foo() = baz() //baz() from Base1
  fun bar() = base2.baz() //the same but with explicit receiver
}
```

Even public members of aggregated object are not visible outside of top-level class.

Name resolution order:
1. Look for own member in the top-level class
1. Look for member in the inherited class
1. Look for member in companion object (if declared)
1. Look for member in privately inherited class

## Compiler implementation
Compiler implementation is trivial: every privately inherited class transformed into `private val` declaration:

Given:
```kotlin
class Derived(a: Int, b: Int): private Base1(a + b), Base2(a), Serializable{

  fun foo() = baz() //baz() from Base1
}
```

Transformation result:
```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable {
  private val base2 = Base1(a + b)

  fun foo() = base2.baz()
}
```

The complexity hides in lexical scope resolution for the specified name.

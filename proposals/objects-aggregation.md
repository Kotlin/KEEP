# Objects aggregation

* **Type**: Design proposal
* **Author**: Roman Sakno
* **Contributors**: Roman Sakno
* **Status**: Under consideration
* **Prototype**: Not started

## Synopsis
Aggregation is one of mostly used patterns in OOP that helps to create more complex logic using combination of other projects with respect to encapsulation. Kotlin offers several ways to simplify aggregation in some cases:

* [Implementation by Delegation](https://kotlinlang.org/docs/reference/delegation.html#implementation-by-delegation) under the hood aggregates original implementation of interface and delegates method calls of top-level class to aggregated implementation
* [Companion object](https://kotlinlang.org/docs/reference/object-declarations.html#companion-objects) allows to aggregate singleton object at class-level (not instance level) and make their members to be accessible without instantiation of outer class.

But there is no way to achieve the same convenience as provided by **companion** objects at instance-level rather than class-level.

The main idea of this proposal is to give implicit access to members of aggregated object rather than existing **val**/**var** declarations with dot notation for accessing their members.

## Language implementation

### C++-like syntax
C++ supports private inheritance using **private** keyword declared before parent class name in inheritance list. This syntax can be reused in Kotlin to declare aggregated objects: 
```kotlin
class Derived(a: Int, b: Int): private Base1(a + b), Base2(a), Serializable{

  fun foo() = baz() //baz() from Base1
  fun bar() = this@Base1.baz() //the same but with explicit receiver
}
```

It is possible to specify more than one aggregated object:
```kotlin
class Derived(a: Int, b: Int): private Base1(a), private Base2(b), Base3()
```

Even public members of aggregated object are not visible outside of top-level class. The only allowed visibility modifiers are **protected** and **private**. If aggregated class is declared with **protected** modifier then its members are implicitly accessible from derived class.

#### Aggregation by Delegation
Ability to aggregate object passed as constructor parameter:
```kotlin
open class Derived(b: Base1): protected Base1 by b, Base2(a), Serializable
```

**val** and **var** declaration still applicable to such constructor parameter.

#### Override aggregation
Ability to override protected aggregation in the derived class through delegation to the constructor parameter
```kotlin
open class A(b: Base1 = Base1("foo")): protected Base1 by b

class B: A(Base1("bar"))
```

#### Overload aggregation
Ability to hide aggregation (but not override) in derived class. Bug or feature???
```kotlin
open class Derived1(a: Int, b: Int): private Base1(a + b) {

  fun foo() = baz() //baz() from Base1
  fun bar() = this@Base1.baz() //the same but with explicit receiver
}

class Derived2: Derived1(2, 3), private Base1(8) {
  fun foo() = baz() //baz from Base1 aggregated by Derived2 class, not Derived1
}
```
The same is applicable for **protected** visibility modifier.

#### Summary

Pros:
1. Small modification of language grammar
1. Readable for developers who are familiar with C++ private inheritance. Moreover, such kind of declaration has similar semantics.

Cons:
1. **public** and **internal** visibility modifiers are not allowed. Otherwise, these modifiers will break encapsulation and allow client code to see aggregated objects.
1. Syntax is not obvious and doesn't show what is happening under the hood


### Kotlin-friendly syntax
This version of syntax just add additional context for **companion** keyword and make it usable in conjunction with **val**:
```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable{
  private companion val base1 = Base1(a + b) //explicitly defined name of aggregation
  
  fun foo() = baz() //baz() from Base1
  fun bar() = base2.baz() //the same but with explicit receiver
}
```
or shortly,
```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable{
  private companion val: Base1(a + b) //like companion object without explicit name
  
  fun foo() = baz() //baz() from Base1
  fun bar() = this@Base1.baz() //the same but with explicit receiver
}
```

#### Constructor parameter
Ability to aggregate object passed as constructor parameter:
```kotlin
class Derived(private companion val b: Base1): Base2(a), Serializable
```

**val** and **var** declaration still applicable to such constructor parameter.

#### Override aggregation
Ability to override protected aggregation in the derived class:
```kotlin
open class A(a: Int, b: Int){
  protected companion open val: Base(a + b)
}

class B(a: Int, b: Int): A(a, b){
  protected companion override val: Base(a) 
}
```

#### Summary

Pros:
1. Less magic. Easy to understand what is happening under the hood
1. Any visibility modifier can be used for companion val, even **public** and **internal**
1. Getter syntax is applicable: `private companion val base1 get() = Base1(a + b)`
1. Explicit specification of exact type to aggregate: `private companion val base1: Interface = Base1(a + b)`. In this case implicit access to members is applicable only for declared type `Interface`

Cons:

1. More verbose code:

| Cpp-like syntax | Kotlin-friendly syntax |
| ---- | ---- |
| `class Derived(a: Int, b: Int): private Base1(a + b), Base2(a), Serializable{` | `class Derived(a: Int, b: Int): Base2(a), Serializable{`|
| `fun foo() = bar()` | `private companion val: Base1(a + b)` |
| `}` | `fun foo() = bar()`
| | `}` |

## Compiler implementation
Compiler implementation is trivial: every aggregated object should be transformed into `private val` (or `protected val`, according with aggregation visibility modifier) declaration:

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

The complexity of compilation lies in the field of lexical scope resolution for the specified name. To solve this issue, the following resolution order can be applied:
1. Looking for own member in the top-level class
1. Looking for member in the inherited class
1. Looking for member in companion object (if declared)
1. Looking for member in implicitly aggregated object

# Companion values

* **Type**: Design proposal
* **Author**: Roman Sakno
* **Contributors**: Roman Sakno
* **Status**: Under consideration
* **Prototype**: Not started

## Synopsis
Composition and aggregation are frequently used patterns in OOP that helps to create more complex logic using combination of other projects with respect to encapsulation. Kotlin offers several ways to simplify aggregation in some cases:

* [Implementation by Delegation](https://kotlinlang.org/docs/reference/delegation.html#implementation-by-delegation) under the hood aggregates original implementation of interface and delegates method calls of top-level class to aggregated implementation
* [Companion object](https://kotlinlang.org/docs/reference/object-declarations.html#companion-objects) allows to aggregate singleton object at class-level (not instance level) and make their members to be accessible without instantiation of outer class.

But there is no way to achieve the same convenience as provided by **companion** objects at instance-level rather than class-level.

The main idea of this proposal is to give implicit access to members of aggregated/composed object rather than existing **val**/**var** declarations with dot notation for accessing their members.

## Language implementation
**companion** keyword can be applied to **val** or **var** declarations inside of class declaration or at constructor parameter level. This keyword indicates that all members of such value will be accessible implicitly inside of class.

```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable{
  private companion val base1 = Base1(a + b) //explicitly defined name
  
  fun foo() = baz() //baz() from Base1
  fun bar() = base1.baz() //the same but with explicit receiver
}
```
or shorter,
```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable{
  private companion val: Base1(a + b) //like companion object without explicit name
  
  fun foo() = baz() //baz() from Base1
  fun bar() = this@Base1.baz() //the same but with explicit receiver
}
```
Class may have more than one companion value.

### Constructor parameter
Ability to aggregate object passed as constructor parameter:
```kotlin
class Derived(private companion val b: Base1): Base2(a), Serializable
```

**val** and **var** declaration still applicable to such constructor parameter.

### Overriding of companion value
Ability to override protected aggregation in the derived class:
```kotlin
open class A(a: Int, b: Int){
  protected companion open val: Base(a + b)
}

class B(a: Int, b: Int): A(a, b){
  protected companion override val: Base(a) 
}
```

### Explicit specification of companion type
Ability to declare the type which members will be accessible implicitly
```kotlin
class A {
  private companion val b: Interface = BazClass()
  
  fun foo() = baz() //baz() from Interface
}
```

### Custom getter
Ability to define custom getter for companion value
```kotlin
class A {
  private companion val logger: Logger
     get() = Logger.getLogger("LogName")
  
  fun foo() = info("Log info") //logger.info("Log info")
}
```

### Mutable companion object
Ability to declare mutable companion value
```kotlin
class A {
  private companion var logger: Logger = Logger.getLogger("LogName")
  
  fun foo() = info("Log info") //logger.info("Log info")
}
```
**lateinit** and **by** modifiers are also supported.

### Declaration at parameters level
Ability to declare companion value as constructor parameter.

```kotlin
class A(private companion val input: B()){
  fun foo() = bar() //B.bar()
}
```
This kind of declaration supports only named companion value.

### Declaration inside of function signature
Implicit access to object members passed as parameter
```kotlin
fun foo(companion b: Bar, x: Int, y: Int){
  baz() //the same as b.baz()
}
```

It is possible to declare two companion parameters but with different types. The following code is illegal:
```kotlin
fun foo(companion x: Int, companion y: Int){
  toLong() //which implicit receiver we should use? x or y?
}
```

## Compiler implementation
Compiler implementation is trivial: every composed object should be transformed into `private val` (or `protected val`, according with composition visibility modifier) declaration:

Given:
```kotlin
class Derived(a: Int, b: Int): Base2(a), Serializable{
  private companion val base1 = Base1(a + b)

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

For companion values without explicitly defined name compiler should emit **synthetic** modifier.

Given:
```kotlin
open class Derived(a: Int, b: Int): Base2(a), Serializable{
  protected companion val: Base1(a + b)

  fun foo() = baz() //baz() from Base1
}
```

Transformation result:
```kotlin
open class Derived(a: Int, b: Int): Base2(a), Serializable {
  @JvmSynthetic
  protected val $Base1_companion$ = Base1(a + b)

  fun foo() = $Base1_companion$.baz()
}
```

The complexity of compilation lies in the field of lexical scope resolution for the specified name. To solve this issue, the following resolution order can be applied:
1. Looking for own member in the top-level class
1. Looking for member in the inherited class
1. Looking for member in companion object (if declared)
1. Looking for member in implicitly composed/aggregated object

### Limitations
Companion value cannot have nullable type.
Two companion values cannot have identical types.

## Practical use
Logging methods can be accessible implictly inside of class:
```kotlin
open class A{
  protected companion val: ILogger = LoggerFactory.getLogger(A::class.qualifiedName)
  
  fun foo(){
    info("Log entry") //the same as this@ILogger.info
  }
}

class B: A(){
  fun bar(){
    info("Another log entry") //the same as this@ILogger.info because companion value has protected visibility
  }
}
```

Composition of services in Spring Framework:
```kotlin
@Component
class A(@Autowired private companion val dao: DaoService){

  fun findUser(name: String): User{
    val id = getUserIdByName(name) //dao.getUserIdByName
    return User(id)
  }
}
```

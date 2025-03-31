# Internal Members in Interface

* **Type**: Design proposal
* **Authors**: Sebastian Sellmair
* **Status**: In Progress
* **Prototype**: Implemented

## Synopsis

Lift restriction of not allowing 'internal' members inside interfaces

## Motivation

Current Situation: Declaring members inside interfaces either requires them to be `public`, or `private` with default
implementation.

```kotlin
interface MyInterface {
    internal val myProperty: Any
    // ^
    // [WRONG_MODIFIER_CONTAINING_DECLARATION] Modifier 'internal' is not applicable inside 'interface'

    internal fun myFunction()
    // ^
    // [WRONG_MODIFIER_CONTAINING_DECLARATION] Modifier 'internal' is not applicable inside 'interface'
    
}
```

This however can become problematic for interfaces declared as `internal` whereas the implementation is part of the
public API: 

```kotlin
internal interface MyInternalInterface {
    val myProperty: Any
}

class MyPublicClass: MyInternalInterface {
    override val myProperty: Any = TODO()
} 
```

As this member will therefore always be exposed publicly despite the contract defined as interface being internal. 


## Implementation
Declaring internal members shall be allowed inside interfaces:

```kotlin
interface MyInterface {
    internal val myProperty: Any
    internal fun myFunction()
}
```

Where implementations of such interfaces inside the same module will be able to override the member
with internal (or promote the member to public):

```kotlin
class MyPublicClass: MyInterface {
    internal override val myProperty: Any = TODO() // OK!
    override val myProperty: Any = TODO() // OK, Still internal
    public override val myProperty: Any = TODO() // OK, explicitly promoted to public
} 
```

This behaviour will therefore work exactly like an `abstract class` with internal members.


#### Implementing a public interface with internal members from another module
In the case of a public interface defining internal members, the same error as a public abstract class defining
internal members will be emitted:

```kotlin
class AnotherModule : MyInterface {
// ^
//  [INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER_ERROR] AnotherModule inherits invisible abstract members: internal abstract val myProperty: Any
}

class AnotherModule : MyAbstractClass() {
// ^
//  [INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER_ERROR] AnotherModule inherits invisible abstract members: internal abstract val myProperty: Any
}
```
# Local and top-level lateinit vars

* **Type**: Design proposal
* **Author**: Dmitry Petrov
* **Contributors**: Alexander Udalov
* **Status**: Implemented in 1.2
* **Prototype**: Implemented

Discussion: https://github.com/Kotlin/KEEP/issues/86

## Problem description

Classes (including objects and enum entries) can contain `lateinit` properties, 
which provide postponed initialization semantics. 
If a property is read before it is initialized, an exception is thrown.

Similar functionality is useful not only for class properties, 
but also for top-level properties and local variables.

In case of local variables, it also allows initializing local variables in local
functions and lambdas (which works as a makeshift replacement for an effect system):
```kotlin
fun foo() {
    lateinit var x: Bar
    synchronized { 
        x = bar()
    } 
    // ...
}
```

## Design details

All relevant restrictions related to lateinit class member properties apply.
`lateinit` modifier is not applicable to:
* read-only properties (`val`s)
* properties of nullable types (including generic types with nullable upper bound)
* properties of primitive types
* delegated properties
* properties with initializer

On JVM, `lateinit` top-level properties expose their backing field 
with the same visibility as a property setter. 

In case of `lateinit` local vars, optimizing compiler can eliminate redundant 
initialization checks (as a part of redundant null check elimination pass).

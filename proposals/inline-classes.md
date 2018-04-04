# Inline classes

* **Type**: Design proposal
* **Author**: Mikhail Zarechenskiy
* **Contributors**: Andrey Breslav, Denis Zharkov, Roman Elizarov, Stanislav Erokhin
* **Status**: Under consideration
* **Prototype**: Implemented in Kotlin 1.2.30

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/104).

## Summary

Currently, there is no performant way to create wrapper for a value of a corresponding type. The only way is to create a usual class, 
but the use of such classes would require additional heap allocations, which can be critical for many use cases.    

We propose to support identityless inline classes that would allow to introduce wrappers for a values without additional overhead related 
to additional heap allocations.

## Motivation / use cases

Inline classes allow to create wrappers for a value of a certain type and such wrappers would be fully inlined. 
This is similar to type aliases but inline classes are not assignment-compatible with the corresponding underlying types.

Use cases:

- Unsigned types
```
inline class UInt(private val value: Int) { ... }
inline class UShort(private val value: Short) { ... }
inline class UByte(private val value: Byte) { ... }
inline class ULong(private val value: Long) { ... }
```

- Native types like `size_t` for Kotlin/Native
- Inline enum classes 
    - Int enum for [Android IntDef](https://developer.android.com/reference/android/support/annotation/IntDef.html)
    - String enum for Kotlin/JS (see [WebIDL enums](https://www.w3.org/TR/WebIDL-1/#idl-enums))
- Units of measurement
- Result type (aka Try monad) [KT-18608](https://youtrack.jetbrains.com/issue/KT-18608)
- Inline property delegates
- Inline wrappers

## Description

Inline classes are declared using soft keyword `inline` and must have a single property:
```
inline class Foo(val i: Int)
```
Property `i` defines type of the underlying runtime representation for inline class `Foo`, while at compile time type will be `Foo`.

From language point of view, inline classes can be considered as restricted classes, they can declare various members, operators, 
have generics. 

Example:
```
inline class Name(val s: String) : Comparable<Name> {
    override fun compareTo(other: Name): Int = s.compareTo(other.s)
    
    fun greet() {
        println("Hello, $s")
    }
}    

fun greet() {
    val name = Name("Kotlin") // there is no actual instantiation of class `Name`
    name.greet() // method `greet` is called as a static method
}
```

## Current limitations

Currently, inline classes must satisfy the following requirements:

- Inline class must have a public primary constructor with a single value parameter
- Inline class must have a single read-only (`val`) property as an underlying value, which is defined in primary constructor
- Underlying value cannot be of the same type that is containing inline class
- Inline class cannot have `init` block
- Inline class must be final
- Inline class can implement only interfaces
- Inline class cannot have backing fields
    - Hence, it follows that inline class can have only simple computable properties (no lateinit/delegated properties)
- Inline class cannot have inner classes
- Inline class must be a toplevel class 

### Other restrictions

The following restrictions are related to the usages of inline classes:

- Referential equality (`===`) is prohibited for inline classes
- vararg of inline class type is prohibited
```
inline class Foo(val s: String)

fun test(vararg foos: Foo) { ... } // should be an error  
```

## Java interoperability  

Each inline class has its own wrapper, which is boxed/unboxed by the same rules as for primitive types.
Basically, rule for boxing can be formulated as follows: inline class is boxed when it is used as another type.
Unboxed inline class is used when value is statically known to be inline class.

Examples:
```
interface I

inline class Foo(val i: Int) : I

fun asInline(f: Foo) {}
fun <T> asGeneric(x: T) {}
fun asInterface(i: I) {}
fun asNullable(i: Foo?) {}

fun <T> id(x: T): T = x

fun test(f: Foo) {
    asInline(f)
    asGeneric(f) // boxing
    asInterface(f) // boxing
    asNullable(f) // boxing
    
    val c = id(f) // boxing/unboxing, c is unboxed
}
```

Since boxing doesn't have side effects as is, it's possible to reuse various optimizations that are done for primitive types.

### Type mapping on JVM

Depending on the underlying type of inline class and its declaration site, inline class type can be mapped to the underlying type or to the 
wrapper type.  


| Underlying Type \ Declaration Site | Not-null | Nullable | Generic |
| --------------------------------------------- | --------------- | ---------------- | ---------------- |
| **Reference type** | Underlying type | Underlying type | Wrapper type |
| **Nullable reference type** | Underlying type  | Wrapper type | Wrapper type |
| **Primitive type** | Underlying type  | Wrapper type | Wrapper type |
| **Nullable primitive type** | Underlying type  | Wrapper type | Wrapper type |

For example, from this table it follows that nullable inline class which is based on reference type is mapped to the underlying type:
```
inline class Name(val s: String)

fun foo(n: Name?) { ... } // `Name?` here is mapped to String
```

## Methods from `kotlin.Any`

Inline classes are indirectly inherited from `Any`, i.e. they can be assigned to a value of type `Any`, but only through boxing.

Methods from `Any` (`toString`, `hashCode`, `equals`) can be useful for a user-defined inline classes and therefore should be customizable. 
Methods `toString` and `hashCode` can be overridden as usual methods from `Any`. For method `equals` we're going to introduce new operator 
that represents "typed" `equals` to avoid boxing for inline classes:
```
inline class Foo(val s: String) {
    operator fun equals(other: Foo): Boolean { ... }
}
```

Compiler will generate original `equals` method that is delegated to the typed version.  

By default, compiler will automatically generate `equals`, `hashCode` and `toString` same as for data classes.

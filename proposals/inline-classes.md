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

We propose to support identityless inline classes that would allow to introduce wrappers for values without additional overhead related 
to additional heap allocations.

## Motivation / use cases

Inline classes allow to create wrappers for a value of a certain type and such wrappers would be fully inlined. 
This is similar to type aliases but inline classes are not assignment-compatible with the corresponding underlying types.

Use cases:

- Unsigned types
```kotlin
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
```kotlin
class A {
    var something by InlinedDelegate(Foo()) // no actual instantiation of `InlinedDelegate`
}


inline class InlinedDelegate<T>(var node: T) {
    operator fun setValue(thisRef: A, property: KProperty<*>, value: T) {
        if (node !== value) {
            thisRef.notify(node, value)
        }
        node = value
    }

    operator fun getValue(thisRef: A, property: KProperty<*>): T {
        return node
    }
}
```

- Inline wrappers
    - Typed wrappers
    ```kotlin
    inline class Name(private val s: String)
    inline class Password(private val s: String)
    
    fun foo() {
        var n = Name("n") // no actual instantiation, on JVM type of `n` is String
        val p = Password("p")
        n = "other" // type mismatch error
        n = p // type mismatch error
    }
    ```

    - API refinement
    ```
    // Java
    public class Foo {
        public Object[] objects() { ... }
    }
    
    // Kotlin
    inline class RefinedFoo(val f: Foo) {
        inline fun <T> array(): Array<T> = f.objects() as Array<T>
    }
    ``` 

## Description

Inline classes are declared using soft keyword `inline` and must have a single property:
```kotlin
inline class Foo(val i: Int)
```
Property `i` defines type of the underlying runtime representation for inline class `Foo`, while at compile time type will be `Foo`.

From language point of view, inline classes can be considered as restricted classes, they can declare various members, operators, 
have generics. 

Example:
```kotlin
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
- Inline class with undefined (recursively defined) generics, e.g. generics with an upper bound equal to the class, is prohibited
    ```kotlin
    inline class A<T : A<T>>(val x: T) // error
    ```
- Inline class cannot have `init` block
- Inline class must be final
- Inline class can implement only interfaces
- Inline class cannot have backing fields
    - Hence, it follows that inline class can have only simple computable properties (no lateinit/delegated properties)
- Inline class cannot have inner classes
- Inline class must be a toplevel class 

Sidenotes:

- Let's elaborate requirement to have public primary constructor and restriction of `init` blocks.
For example, we want to have an inline class for some bounded value:
    ```kotlin
    inline class Positive(val value: Int) {
        init { 
            assert(value > 0) "Value isn't positive: $value" 
        }
    }
  
    fun foo(p: Positive) {}
    ```
    
    Because of inlining, method `foo` have type `int` from Java POV, so we can pass to method `foo` everything we want and `init` 
    block will not be executed. Since we cannot control behaviour of `init` block execution, we restrict it for inline classes.
    
    Unfortunately, it's not enough, because `init` blocks can be emulated via factory methods:
    ```kotlin
    inline class Positive private constructor(val value: Int) {
        companion object {
            fun create(x: Int) {
                assert(x > 0) "Value isn't positive: x"
                return Positive(x)  
            }  
        }
    }
  
    fun foo(p: Positive) {}
    ```
    
    Again, method `foo` have type `int` from Java POV, so we can indirectly create values of type `Positive` 
    even with the presence of private constructor.
    
    To make behaviour more predictable and consistent with Java, we demand public primary constructor and restrict `init` blocks.

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
```kotlin
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
boxed type.  

| Underlying Type \ Declaration Site | Not-null | Nullable | Generic |
| --------------------------------------------- | --------------- | ---------------- | ---------------- |
| **Reference type** | Underlying type | Underlying type | Boxed type |
| **Nullable reference type** | Underlying type  | Boxed type | Boxed type |
| **Primitive type** | Underlying type  | Boxed type | Boxed type |
| **Nullable primitive type** | Underlying type  | Boxed type | Boxed type |

For example, from this table it follows that nullable inline class which is based on reference type is mapped to the underlying type:
```kotlin
inline class Name(val s: String)

fun foo(n: Name?) { ... } // `Name?` here is mapped to String
```

Also, if inline class type is used in generic position, then its boxed type will be used:
```
// Kotlin: sample.kt

inline class Name(val s: String)

fun generic(names: List<Name>) {} // generic signature will have `List<Name>` as for parameters type
fun simple(name: Name) {}

// Java
class Test {
    void test() {
        String name = SampleKt.simple();
        List<Name> ls = Samplekt.generic(); // from Java POV it's List<Name>, not List<String>
    }
}
```


#### Generic inline class mapping

Consider the following sample:
```kotlin
inline class Generic<T>(val x: T)

fun foo(g: Generic<String>) {}
```

Now, type `Generic<String>` can be mapped either to `java.lang.String` or to `java.lang.Object`. 
To make the whole rule more consistent, currently we propose to map `Generic<SomeType>` always to `java.lang.Object`, 
i.e. we'll map upper bound of type parameter.

* Sidenote: maybe it's worth to consider inline classes with reified generics:
    ```kotlin
    inline class Reified<reified T>(val x: T)
    
    fun foo(a: Reified<Int>, b: Reified<String>) // a has type `Int`, b has type `String`
    ``` 

Generic inline classes with underlying value not of type that defined by type parameter are mapped as usual generics:
```kotlin
inline class AsList<T>(val ls: List<T>)

fun foo(param: AsList<String>) {}
```

In JVM signature `param` will have type `java.util.List`, 
but in generic signature it will be `java.util.List<java.lang.String>` 

## Methods from `kotlin.Any`

Inline classes are indirectly inherited from `Any`, i.e. they can be assigned to a value of type `Any`, but only through boxing.

Methods from `Any` (`toString`, `hashCode`, `equals`) can be useful for a user-defined inline classes and therefore should be customizable. 
Methods `toString` and `hashCode` can be overridden as usual methods from `Any`. For method `equals` we're going to introduce new operator 
that represents "typed" `equals` to avoid boxing for inline classes:
```kotlin
inline class Foo(val s: String) {
    operator fun equals(other: Foo): Boolean { ... }
}
```

Compiler will generate original `equals` method that is delegated to the typed version.  

By default, compiler will automatically generate `equals`, `hashCode` and `toString` same as for data classes.

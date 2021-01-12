# Inline classes

* **Type**: Design proposal
* **Author**: Mikhail Zarechenskiy
* **Contributors**: Andrey Breslav, Denis Zharkov, Dmitry Petrov, Ilya Gorbunov, Roman Elizarov, Stanislav Erokhin, Ilmir Usmanov
* **Status**: Beta since 1.4.30
* **Prototype**: Implemented in Kotlin 1.2.30

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/104).

## Summary

Currently, there is no performant way to create wrapper for a value of a corresponding type. The only way is to create a usual class, 
but the use of such classes would require additional heap allocations, which can be critical for many use cases.    

We propose to support identityless inline classes that would allow to introduce wrappers for values without additional overhead related 
to additional heap allocations.

Inline classes are a subset of value-based classes, which are classes without identity and hold values only.

Inline classes behave like primitive types. Like primitive types, passing inline class to function or returning from it does not
require a wrapper with a few exceptions, described in the relevant section.

## Motivation / use cases

Inline classes allow creating wrappers for a value of a certain type and such wrappers would be fully inlined. 
This is similar to type aliases but inline classes are not assignment-compatible with the corresponding underlying types.

Use cases:

- Unsigned types
```kotlin
@JvmInline
value class UInt(private val value: Int) { ... }
@JvmInline
value class UShort(private val value: Short) { ... }
@JvmInline
value class UByte(private val value: Byte) { ... }
@JvmInline
value class ULong(private val value: Long) { ... }
```

- Native types like `size_t` for Kotlin/Native
- Inline enum classes 
    - Int enum for [Android IntDef](https://developer.android.com/reference/android/support/annotation/IntDef.html)
    - String enum for Kotlin/JS (see [WebIDL enums](https://www.w3.org/TR/WebIDL-1/#idl-enums))
    
    Example:
    ```kotlin
    @JvmInline
    enum class Foo(val x: Int) {
        A(0), B(1);
        
        fun example() { ... }
    }
    ```
    
    The constructor's arguments should be constant values and the values should be different for different entries.


- Units of measurement
- Result type (aka Try monad) [KT-18608](https://youtrack.jetbrains.com/issue/KT-18608)
- Inline property delegates
```kotlin
class A {
    var something by InlinedDelegate(Foo()) // no actual instantiation of `InlinedDelegate`
}


@JvmInline
value class InlinedDelegate<T>(var node: T) {
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
    @JvmInline
    value class Name(private val s: String)
    @JvmInline
    value class Password(private val s: String)
    
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
    @JvmInline
    value class RefinedFoo(val f: Foo) {
        inline fun <T> array(): Array<T> = f.objects() as Array<T>
    }
    ``` 

## Description

Inline classes are declared using soft keyword `value` and must have a single property:
```kotlin
value class Foo(val i: Int)
```
In Kotlin/JVM, however, they should be annotated with additional `@JvmInline` annotation:
```kotlin
@JvmInline
value class Foo(val i: Int)
```
In Kotlin/Native and Kotlin/JS, because of the closed-world model, value-based classes with single read-only property are inline classes.
In Kotlin/JVM we require the annotation for inline classes, since we are going to support value-based classes, which are a superset of
inline classes, and they are binary incompatible with inline classes. Thus, adding and removing the annotation will be a breaking change.

The property `i` defines type of the underlying runtime representation for inline class `Foo`, while at compile time type will be `Foo`.

From language point of view, inline classes can be considered as restricted classes, they can declare various members, operators, 
have generics. 

Example:
```kotlin
@JvmInline
value class Name(val s: String) : Comparable<Name> {
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

- Inline class must have a primary constructor with a single value parameter
- Inline class must have a single read-only (`val`) property as an underlying value, which is defined in primary constructor
- Underlying value cannot be of the same type that is containing inline class
- Inline class with undefined (recursively defined) generics, e.g. generics with an upper bound equal to the class, is prohibited
    ```kotlin
    @JvmInline
    value class A<T : A<T>>(val x: T) // error
    ```
- Inline class must be final
- Inline class can implement only interfaces
- Inline class cannot have backing fields
    - Hence, it follows that inline class can have only simple computable properties (no lateinit/delegated properties)
- Inline class must be a toplevel or a nested class. Local and inner inline classes are not allowed.
- Inline classes cannot have `var` properties as well as extension `var` properties.

Let us explain the rationale behind the last limitation. We want the `value.properties = 1` syntax to change the value of value-based
class: instead of generating `value.setProperty(1)` the compiler will generate something like `value = value.clone(property = 1)`.
So, we reserve the syntax of mutating property to mutate the class in the future.

### Other restrictions

The following restrictions are related to the usages of inline classes:

- Referential equality (`===`) is prohibited for all value-based classes, including inline classes
- vararg of inline class type is prohibited
```
@JvmInline
value class Foo(val s: String)

fun test(vararg foos: Foo) { ... } // should be an error  
```

## Java interoperability  

Each inline class has its own wrapper that is represented as a usual class on JVM. This wrapper is needed to box values of inline class
types and use it where it's impossible to use unboxed values. Rules for boxing are pretty the same as for primitive types 
and can be formulated as follows: inline class is boxed when it is used as another type. Unboxed inline class is used when value is 
statically known to be inline class.

Examples:
```kotlin
interface I

@JvmInline
value class Foo(val i: Int) : I

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

Since boxing doesn't have side effects as is, it's possible to reuse various optimizations that are done for primitive types to avoid
extra boxing/unboxing operations.

### Type mapping on JVM (without mangling)

#### Top-level types

##### Inline classes over primitive types
Consider the following example:
```kotlin
@JvmInline
value class ICPrimitive(val x: Int)

fun foo(i: ICPrimitive) {}
fun bar(i: ICPrimitive?) {}
```

Function `foo` can take only values of the underlying type of `ICPrimitive` type, which is `Int`, therefore, such inline class types are 
mapped to the underlying types. Here, `ICPrimitive` will be mapped to primitive `int`.
 
Function `bar` can also take `null` literal, which is not of type `int`, therefore, such inline class types are mapped to the reference
type. Here, `ICPrimitive?` will be mapped to `LICPrimitive;`.

So, 
- not-null inline class types over primitive types are mapped directly to the underlying primitive type
- nullable inline class types over primitive types are mapped to the boxed reference type (wrapper of an inline class)

##### Inline classes over not-null reference types

Now, let's consider an inline class over some reference type:
```kotlin
@JvmInline
value class ICReference(val s: String)

fun foo(i: ICReference) {}
fun bar(i: ICReference?) {}
```

With the type `ICReference` rationale is the same, it can't hold `nulls`, so this type will be mapped to `String`.
Next, function `bar` can hold `null` values, but note that underlying type of `ICReference` is a reference type `String`, which
can hold `null` values on JVM and can be safely used as a mapped type.

So, 
- not-null inline class types over reference types are mapped directly to the underlying reference type
- not-null inline class types over not-null reference types are mapped directly to the underlying reference type

##### Inline classes over nullable reference types

Now, what if inline class is declared over some nullable reference type?
```kotlin
@JvmInline
value class ICNullable(val s: String?)

fun foo(i: ICNullable) {}
fun bar(i: ICNullable?) {}
```

`ICNullable` can't hold `nulls`, so it can be safely mapped to `String` on JVM.
`ICNullable?` can hold `nulls` and also inline classes over `nulls`: `ICNullable(null)`.
It's important to distinguish such values:
```kotlin
fun baz(a: ICNullable?, b: ICNullable?) {
    if (a === b) { ... }
}

fun test() {
    baz(ICNullable(null), null)
}
``` 
If we map `ICNullable?` to `String` as in the previous example, it will not be possible to distinguish `ICNullable(null)` from `null` as on JVM
they will be represented by value `null`, therefore `ICNullable?` should be mapped to the `LICNullable;`

So, 
- not-null inline class types over nullable reference types are mapped directly to the underlying reference type
- nullable inline class types over nullable reference types are mapped to the boxed reference type (wrapper of an inline class)

##### Inline classes over other inline classes

Besides these cases, inline class can also be declared over some other inline class:
```kotlin
@JvmInline
value class IC2(val i: IC)
@JvmInline
value class IC2Nullable(val i: IC?)
```

Mapping rules for `IC2Nullable` are simple:
- `IC2Nullable` -> mapped type of `IC?`
- `IC2Nullable?` -> `LICNullable;`

Mapping rules for `IC2` are the following:
- `IC2` -> mapped type of `IC`
- `IC2?` -> 
    - fully mapped type of `IC` if it's a non-null reference type
    - `LIC2;` if fully mapped type of `IC` can hold `nulls` or it's a primitive type

Rationale for these rules is the same as in the previous steps: for nullable types, it should be possible to hold
`null` and distinguish `nulls` from inline classes over `nulls`. 

Example, let's consider the following hierarchy of inline classes:
```kotlin
@JvmInline
value class IC1(val s: String)
@JvmInline
value class IC2(val ic1: IC1?)
@JvmInline
value class IC3(val ic2: IC2)

fun foo(i: IC3) {}
fun bar(i: IC3?) {} 
```

Here `IC3` will be mapped to the type `String`, `IC3?` will be mapped to `LIC3;` as it should be possible to distinguish `null` 
from `IC3(IC2(null))`. But if `IC2` was declared as `inline class IC2(val ic1: IC1)`, then `IC3` would be mapped to `String`.

#### Generic types

If inline class type is used in generic position, then its boxed type will be used:
```
// Kotlin: sample.kt

@JvmInline
value class Name(val s: String)

fun generic(names: List<Name>) {} // generic signature will have `List<Name>` as for parameters type
fun simple(): Name = Name("Kt")

// Java
class Test {
    void test() {
        String name = SampleKt.simple();
        List<Name> ls = Samplekt.generic(); // from Java POV it's List<Name>, not List<String>
    }
}
```

This is needed to preserve information about inline classes at runtime.

#### Generic inline class mapping

Consider the following sample:
```kotlin
@JvmInline
value class Generic<T>(val x: T)

fun foo(g: Generic<Int>) {}
```

Now, type `Generic<Int>` can be mapped either to `java.lang.Integer`, `java.lang.Object` or to primitive `int`.

Same question arises with arrays:
```kotlin
@JvmInline
value class GenericArray<T>(val y: Array<T>)

fun foo(g: GenericArray<Int>) {} // `g` has type `Integer[]` or `Object[]`?
``` 

Therefore, because of this ambiguity, such cases are going to be forbidden in the first version of inline classes.

* Sidenote: maybe it's worth to consider inline classes with reified generics:
    ```kotlin
    @JvmInline
    value class Reified<reified T>(val x: T)
    
    fun foo(a: Reified<Int>, b: Reified<String>) // a has type `Int`, b has type `String`
    ``` 

Generic inline classes with underlying value not of type that defined by type parameter or generic array are mapped as usual generics:
```kotlin
@JvmInline
value class AsList<T>(val ls: List<T>)

fun foo(param: AsList<String>) {}
```

In JVM signature `param` will have type `java.util.List`, 
but in generic signature it will be `java.util.List<java.lang.String>` 

### Reflection

_Note: this functionality is added in Kotlin 1.3.20._

Class literals and `javaClass` property are available for expressions of inline class types.
In both cases resulting `KClass` or `java.lang.Class` object will represent wrapper for used inline class:
```kotlin
@JvmInline
value class Duration(val seconds: Int)

fun test(duration: Duration) {
    // the following expressions are translated into class objects for "Duration" class
    Duration::class
    duration::class
    duration.javaClass
    
    assertEquals(duration::class.toString(), "class Duration")
    assertEquals(Duration::class.simpleName, "Duration")  
}
```

Also, it's possible to use `call`/`callBy` for functions that have inline class types in their signatures:
```kotlin
@JvmInline
value class S(val value: String) {
    operator fun plus(other: S): S = S(this.value + other.value)
}

class C {
    private var member: S = S("")
    
    fun memberFun(x: S, y: String): S = x + S(y)

    fun unboundRef() = C::member.apply { isAccessible = true }
    fun boundRef() = this::member.apply { isAccessible = true }
}

private var topLevel: S = S("")

fun test() {
    val c = C()
    
    assertEquals(S("ab"), C::memberFun.call(C(), S("a"), "b"))
    
    assertEquals(Unit, c.unboundRef().setter.call(c, S("ab")))
    assertEquals(S("ab"), c.unboundRef().call(c))
    assertEquals(S("ab"), c.unboundRef().getter.call(c))

    assertEquals(Unit, c.boundRef().setter.call(S("cd")))
    assertEquals(S("cd"), c.boundRef().call())
    assertEquals(S("cd"), c.boundRef().getter.call())

    val topLevel = ::topLevel.apply { isAccessible = true }
    assertEquals(Unit, topLevel.setter.call(S("ef")))
    assertEquals(S("ef"), topLevel.call())
    assertEquals(S("ef"), topLevel.getter.call())
}
```

## Methods from `kotlin.Any`

Inline classes are indirectly inherited from `Any`, i.e. they can be assigned to a value of type `Any` but only through boxing.
This is the same as for primitives, for example, `Int` doesn't inherit `Any` but its boxed version (`java.lang.Object`) does. 

Methods from `Any` (`toString`, `hashCode`, `equals`) can be useful for a user-defined inline classes and therefore should be customizable. 
Methods `toString` and `hashCode` can be overridden as usual methods from `Any`. For method `equals` we're going to introduce new operator 
that represents "typed" `equals` to avoid boxing for inline classes:
```kotlin
@JvmInline
value class Foo(val s: String) {
    operator fun equals(other: Foo): Boolean { ... }
}
```

Compiler will generate original `equals` method that is delegated to the typed version.  

By default, compiler will automatically generate `equals`, `hashCode` and `toString` same as for data classes.

## Arrays of inline class values

Consider the following inline class:
```kotlin
@JvmInline
value class Foo(val x: Int)
``` 

To represent array of unboxed values of `Foo` we propose using new inline class `FooArray` over an array:
```kotlin
@JvmInline
value class FooArray(private val storage: IntArray): Collection<Foo> {
    operator fun get(index: Int): UInt = Foo(storage[index])
    ...
}
```
While `Array<Foo>` will represent array of **boxed** values:
```
// jvm signature: test([I[LFoo;)V
fun test(a: FooArray, b: Array<Foo>) {} 
``` 

This is similar how one works with arrays of primitive types such as `IntArray`/`ByteArray`, it allows explicitly differ array of 
unboxed values from array of boxed values.

This decision doesn't allow declaring `vararg` parameters that represent array of unboxed inline class values, because it's impossible to
associate vararg of inline class type with the corresponding array type. For example, without additional information it's impossible to match
`vararg v: Foo` with `FooArray`. Therefore, we are going to prohibit `vararg` parameters for now.

#### Other possible options:

- Treat `Array<Foo>` as array of unboxed values by default

    Pros:
    - There is no need to define separate class to introduce array of inline class type
    - It's possible to allow `vararg` parameters
    
    Cons:
    - `Array<Foo>` can implicitly represent array of unboxed and array of boxed values:
    ```java
    // Java
    class JClass {
        public static void bar(Foo[] f) {}
    }
    ```
    From Kotlin point of view, function `bar` can take only `Array<Foo>`
    - Not clear semantics for generic arrays:
    ```kotlin
    fun <T> genericArray(a: Array<T>) {}

    fun test(foos: Array<Foo>) {
      genericArray(foos) // conversion for each element? 
    }
    ```

 
- Treat `Array<Foo>` as array of boxed values and introduce specialized `VArray` class with the following rules:
    - `VArray<Foo>` represents array of unboxed values
    - `VArray<Foo?>` or `VArray<T>` for type paramter `T` is an error
    - (optionally) `VArray<Int>` represents array of primitives
    
    Pros:
    - There is no need to define separate class to introduce array of inline class type
    - It's possible to allow `vararg` parameters
    - Explicit representation for arrays of boxed/unboxed values
    
    Cons:
    - Complicated implementation and overall design


## Expect/Actual inline classes

To declare expect inline class one can use `expect` modifier:
```kotlin
expect value class Foo(val prop: String)
```

Note that we allow to declare property with backing field (`prop` here) for expect inline class, which is different for usual classes.
Also, since each inline class must have exactly one value parameter we can relax rules for actual inline classes:
```kotlin
// common module
expect value class Foo(val prop: String)

// platform-specific module
@JvmInline
actual value class Foo(val prop: String)
```
For actual inline classes we don't require to write `actual` modifier on primary constructor and value parameter.

Currently, expect value class requires actual value and vice versa. 

## Overloads, private constructors and initialization blocks

Let's consider several most important issues that appear in the current implementation.

*Overloads*

Signatures with inline classes that are erased to the same type on the same position will be conflicting:
```kotlin
@JvmInline
value class UInt(val u: Int)

// Conflicting overloads
fun compute(i: Int) { ... }
fun compute(u: UInt) { ... }

@JvmInline
value class Login(val s: String)
@JvmInline
value class UserName(val s: String)

// Conflicting overloads
fun foo(x: Login) {}
fun foo(x: UserName) {}
```

One could use `JvmName` to disambiguate functions, but this looks verbose and confusing. Inline class types are normal types 
and we'd like to think about inline classes as about usual classes with several restrictions, it allows thinking less about 
implementation details.
    
*Non-public constructors and initialization blocks*

Before 1.4.30, inline classes required having a public primary constructor without `init` blocks in order 
to have clear initialization semantics. This is needed because of values that can come from Java:
```
// Kotlin
@JvmInline
value class Foo(val x: Int) 

fun kotlinFun(f: Foo) {}

// Java:

static void test() {
    kotlinFun(42); // constructor or initialization block wasn't called
}
```
    
As a result, it was impossible to encapsulate underlying value or create an inline class that will represent some constrained values:
```kotlin
@JvmInline
value class Negative(val x: Int) {
    init {
        require(x < 0) { ... }
    }
}
```

Note that these problems were fixed with mangling of constructors and functions, accepting inline classes. Thus, since 1.4.30
we lift the restrictions.

### Mangling

To mitigate described problems, we propose introducing mangling for declarations that have top-level inline class types in their signatures.
Example:
```kotlin
@JvmInline
value class UInt(val x: Int)

fun compute(x: UInt) {}
fun compute(x: Int) {}
```

We'll compile function `compute(UInt)` to `compile-<hash>(Int)`, where `<hash>` is a mangling suffix for the signature.
Now it will not possible to call this function from Java because `-` is an illegal symbol there, but from Kotlin point of view 
it's a usual function with the name `compute`.

As these functions are accessible only from Kotlin, the problem about non-public primary constructors and `init` blocks becomes easier. 

#### Mangling rules

*Simple functions*

Simple functions with inline class type parameters are mangled as `<name>-<hash>`, where `<name>` is the original function name, 
and `<hash>` is a mangling suffix for the signature. Mangling suffix can contain upper case and lower case Latin letters, digits, `_` and `-`. 
This scheme applies to property getters and setters as well.

The suffix calculating algorithm has changed in 1.4.30.

In 1.4.30 it is the following:
1. Collect function signature, concatenating parameter string representations,
   where

   1.1. Inline class is represented by its ASM-like descriptor, for example
   `UInt`'s descriptor is `Lkotlin/UInt;`

   1.2. If the inline class is nullable, it has '?' before ';' in the
   descriptor, to distinguish nullable from not-null inline classes, since
   change in nullability should be incompatible change, just like primitives,
   so, `UInt?` turns into `Lkotlin/UInt?;`

   1.3. Everything else is replaced with "_".

   1.4. The signature is wrapped in parentheses, so `foo(UInt, Int)`'s signature
   is `(Lkotlin/UInt;_)`

   1.5. If the function is a method, returning inline class, ":$descriptor" is
   appended

2. The signature's hash is computed.

In a Kotlin-like pseudocode the algorithm looks like
```kotlin
// parameters contain all value parameters, including receiver
fun calculateSuffix(parameters: List<ValueParameter>): String =
   "-" + md5base64(collectSignature(parameters))

fun collectSignature(parameters: List<ValueParameter>): String =
   parameters.joinToString { it.type.getSignatureElement() }

fun Type.getSignatureElement() =
   """
   L${it.type.erasedUpperBound()}${
    if (it.isInlineClass() && it.type.isNullable()) "?" else ""
   };
   """

// Take a String, compute its MD5, take first 6 bytes of the result,
// and represents it as base64 using RFC4648_URLSAFE encoder without
// padding (effectively using 'a'..'z', 'A'..'Z', '0'..'9', '-', and '_').
fun md5base64(signature: String): String =
   base64(md5(signature.toByteArray()).copyOfRange(0, 5))
```
where `erasedUpperBound` is
```kotlin
fun Type.erasedUpperBound() =
  if (this is Class)
    if (this.isInlineClass()) fqName else "_"
  else (this as TypeParameter).erasedUpperBound()

fun TypeParameter.erasedUpperBound() =
  superTypes.find { it !is Interface && it !is Annotation }
    ?: superTypes.first().erasedUpperBound()

```

Before 1.4.30, however, the algorithm was different:

1. Collect function signature, joining parameter representations to string with
   a comma with a space as a delimiter, where

   1.1. Parameter is represented by its ASM-like descriptor for its Kotlin
   class. For example, if the parameter type is `Int`, its representation is
   `Lkotlin/Int;`

   1.2. If the parameter is nullable, it has '?' before ';' in the
   descriptor, so, `Int?` is turned into `Lkotlin/Int?;`

   1.4. The signature is wrapped in parentheses, so `foo(UInt, Int)`'s signature
   is `(Lkotlin/UInt;, Lkotlin/Int;)`

   1.5. If the function is a method, returning inline class, ":$descriptor" is
   appended

2. The signature's hash is computed.

By default, the compiler uses the new scheme when generating mangled functions.
This, however, leads to ABI changes, so the old compilers would not be able to
compile against newly compiled bytecode. One can use
`-Xuse-14-inline-classes-mangling-scheme` compiler flag to force the compiler
to use 1.4.0 mangling scheme and preserve binary compatibility.

The compiler, however, is able to link against both new and old mangling
schemes: if it does not find a function with new mangled suffix it uses the old
one.

1.4.30 standard library uses the old scheme to preserve binary compatibility.

*Constructors* 

Constructors with inline class type parameters are marked as private, and have a public synthetic accessor with additional marker parameter. 
Note that unlike mangled simple functions, hidden constructors can clash, but we consider that a less important issue than type safety.

*Functions inside inline class*

Each function inside inline class is mangled. By default, if such function doesn't have a parameter of inline class type, then it will
get suffix `-impl`, otherwise, it will be mangled as a simple function with inline class type parameters.

*Overridden functions inside inline class*

Overridden functions inside inline class are mangled same as usual ones, but compiler we'll also generate bridge to override function 
from interface.

### Inline classes ABI (JVM)

Let's consider the following inline class:
```kotlin
interface Base {
    fun base(s: String): Int
}

@JvmInline
value class IC(val u: Int) : Base {
    fun simple(y: String) {}
    fun icInParameter(ic: IC, y: String) {}
    
    val simpleProperty get() = 42
    val propertyIC get() = IC(42)
    var mutablePropertyIC: IC
            get() = IC(42)
            set(value) {}
    
    override fun base(s: String): Int = 0
    override fun toString(): String = "IC = $u"
}
```

On JVM this inline class will have next declarations:
```
public final class IC implements Base {
    // Underlying field
    private final I u

    // Members

    public base(Ljava/lang/String;)I
    public toString()Ljava/lang/String;

    // Auto generated methods from Any
    public equals(Ljava/lang/Object;)Z
    public hashCode()I

    // Synthetic constructor to hide it from Java
    private synthetic <init>(I)V

    // function to create and initialize value for `IC` class
    public static constructor-impl(I)I

    // fun simple(y: String) {}
    public final static simple-impl(ILjava/lang/String;)V

    // fun icInParameter(ic: IC, y: String) {}
    public final static icInParameter-8euKKQA(IILjava/lang/String;)V

    // val simpleProperty: Int
    public final static getSimpleProperty-impl(I)I

    // val propertyIC: IC
    public final static getPropertyIC-impl(I)I

    // getter of var mutablePropertyIC: IC
    public final static getMutablePropertyIC-impl(I)I

    // setter of var mutablePropertyIC: IC
    public final static setMutablePropertyIC-kVEzI7o(II)V

    // override fun base(s: String): Int 
    public static base-impl(ILjava/lang/String;)I

    // override fun toString(): String
    public static toString-impl(I)Ljava/lang/String;

    // Methods to box/unbox value of inline class type
    public final static synthetic box-impl(I)Lorg/jetbrains/kotlin/resolve/IC;
    public final synthetic unbox-impl()I

    // Static versions of auto-generated methods from Any
    public static hashCode-impl(I)I
    public static equals-impl(ILjava/lang/Object;)Z

    // Reserved method for specialized equals to avoid boxing
    public final static equals-impl0(II)Z 
}
```

Note that member-constructor (`<init>`) is synthetic, this is needed because with the addition of `init` blocks it will not evaluate them,
they will be evaluated in `constructor-impl`. Therefore we should hide it to avoid creating non-initialized values from Java. 
To make it more clear, consider the following situation:
```kotlin
@JvmInline
value class WithInit(val u: Int) {
    init { ... }
}

fun foo(): List<WithInit> {
    // call `constructor-impl` and evaluate `init` block
    val u = WithInit(0)
     
    // call `box-impl` and constructor (`init`), DO NOT evaluate `init` block again 
    return listOf(u)  
}
```

Note that declarations that have inline class types in parameters not on top-level will not be mangled:
```
@JvmInline
value class IC(val u: Int)

fun foo(ls: List<IC>) {}
```

Function `foo` will have name `foo` in the bytecode.

To disable mangling, one can use `@JvmName` annotation.

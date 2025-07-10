# Common atomics

* **Type**: Standard Library API proposal
* **Author**: Maria Sokolova
* **Contributors**: Filipp Zhinkin, Vsevolod Tolstopyatov, Ilya Gorbunov, Alexander Shabalin, Dmitry Khalanskiy
* **Status**: Implemented in Kotlin `2.1.20-Beta2` and marked as `@ExperimentalAtomicApi`
* **Target issue**: [KT-62423](https://youtrack.jetbrains.com/issue/KT-62423/Consider-providing-Common-atomic-types)
* **Discussion and feedback**: [KEEP-398](https://github.com/Kotlin/KEEP/issues/398)

## Summary

Introduce common atomic types in Kotlin Standard Library to simplify the development of KMP applications and libraries which exploit
concurrency.

## Motivation

For those who are writing new KMP applications/libraries that exploit concurrency or porting existing Kotlin/JVM apps/libs to KMP,
a lack of common atomic types could make life much harder as atomic-dependent code has to be duplicated between different source sets to use
platform-specific atomic types, or regular types on non-concurrent platforms (JS, WASM).

Given that atomics use scenarios, which do not differ across platforms, but only their API changes, providing some common subset
that could be used in common sources, seems reasonable.

## Atomics in other languages

* **C#/.Net**: in .Net, there are no boxed atomic types; all operations are performed on references to variables (fields).
    * https://learn.microsoft.com/en-us/dotnet/api/system.threading.interlocked
* **C++, Rust, Go**: atomics are value types, an atomic field could be shared as a reference/pointer.
    * https://en.cppreference.com/w/cpp/atomic/atomic
    * https://doc.rust-lang.org/std/sync/atomic/
    * https://pkg.go.dev/sync/atomic
* **Swift**: `swift-atomics` library provides both reference (`ManagedAtomic`) and value (`UnsafeAtomic`) atomic types.
    * [Atomics/Types/UnsafeAtomic](https://github.com/apple/swift-atomics/blob/main/Sources/Atomics/Types/UnsafeAtomic.swift)
    * [Atomics/Types/ManagedAtomic](https://github.com/apple/swift-atomics/blob/main/Sources/Atomics/Types/ManagedAtomic.swift)
* **Java**: provides boxed Atomic types and atomic field updaters/var handles
    * [java/util/concurrent/atomic/AtomicInteger](https://docs.oracle.com/javase%2F8%2Fdocs%2Fapi%2F%2F/java/util/concurrent/atomic/AtomicInteger.html)
    * [java/util/concurrent/atomic/AtomicIntegerFieldUpdater](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/AtomicIntegerFieldUpdater.html)

## Boxed or inline atomics?

Boxed types find their usage in scenarios when there’s no object shared between threads, but a state needs to be shared across multiple objects accessed concurrently
(in other languages, one may use a reference or a pointer to some atomic variable).

Field updaters and var handles (in Java) work well when atomic fields constitute an internal state of a shared object.
(As a poor man's alternative to this, when there's only a single atomic field, some may extend `AtomicInteger`/`AtomicLong` classes to avoid boxed types overhead.)
Both field updaters and var handles are not that handy though, so unless a developer is striving to squeeze the last bits of performance out of concurrent code,
boxed types look like a more convenient alternative.

**Could we provide both user-friendly boxed types and a collection of atomic intrinsics that can be applied to a property reference?**

We would like to avoid the trade-off between writing convenient code at the expense of performance and writing less readable code for better performance.
It would be ideal to make this change as automatic as possible.
There are some ideas on how this can be achieved using the existing tools of the `kotlinx-atomicfu` library.

> Atomics optimization is a subject for further improvement, which is out of scope in this KEEP.
> 
> See [atomicfu and optimized atomics](#atomicfu-and-optimized-atomics) section for more details.

## Implementation options

K/N provides the following atomic types in [kotlin.concurrent](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.concurrent/) package:

* `AtomicInt`
* `AtomicLong`
* `AtomicReference`
* `AtomicIntArray`
* `AtomicLongArray`
* `AtomicArray`

Java provides similar types in [java.util.concurrent.atomic](https://docs.oracle.com/javase%2F8%2Fdocs%2Fapi%2F%2F/java/util/concurrent/atomic/AtomicInteger.html) package and
JVM implementation of Kotlin atomics can delegate to those at runtime.

For JS/Wasm trivial single-threaded implementation can be provided.

>By default, Wasm has nothing to do with threads and atomics. However, for WASI, [there’s a proposal to introduce both](https://github.com/WebAssembly/threads/blob/main/proposals/threads/Overview.md#atomic-memory-accesses), and atomic operations are defined on memory.

The API design involved two considerations: on the one hand, we wanted to have common atomic types with our own API, 
on the other hand, since JVM implementation of Kotlin atomics relied on the implementation of Java atomics, Java influenced our API decisions.
We explored the following paths:

### Java atomic typealias

One considered approach was to use Java's atomic API directly by implementing Kotlin atomics as **typealiases** to their Java counterparts:

```kotlin
public actual typealias AtomicInt = java.util.concurrent.atomic.AtomicInteger
```

This approach would have been the most straightforward to implement and offered several benifits:

* Smooth migration from Java to Kotlin atomics (simply change the import package name and atomic constructors).
* Automatic atomic API evolution (new Java atomic methods could be used without additional effort).
* JVM developers are familiar with this API.

However, despite these advantages, this solution had some serious downsides:

* Java atomics are _open_, therefore, Kotlin atomic types would be _open_ as well.
  While this could be managed with e.g. `@SubclassOptInRequired` annotation, this behavior does not align with Kotlin's design principles.

* Moreover, Java atomics extend `Number`, which is also not desirable for our design.

* Kotlin reserves `get` and `set` keywords for providing accessors to the corresponding property.
* The methods from the Java Atomics API with names starting with _"get"_ are treated as properties in Kotlin. For example:

```kotlin
val a = AtomicInt()
a.plain += 1 // getPlain/setPlain
a.andDecrement // getAndDecrement
a.andIncrement // getAndIncrement
```

This code is valid by design, changing this behaviour would be a _breaking change_, 
and we would prefer to avoid building our core API based on ad-hoc exclusion rules.

### Built-in types

As an alternative option, we could implement atomics as [**built-in types**](https://kotlinlang.org/spec/type-system.html#built-in-types).

Here are several builtin options, which we considered:

#### 1. Implement Atomics like `kotlin.String`

> `kotlin.String` and `java.lang.String` are incompatible types however, 
> for interoperability, `java.lang.String` types from Java are loaded as `kotlin.String`.
> During codegen `kotlin.String` are mapped to `java.lang.String`.

**At FIR stage:**
-In Kotlin code Kotlin atomics and Java atomics are incompatible.
-Java Atomics from Java code are mapped to Kotlin atomics.

**At Codegen:**
- Kotlin atomics are mapped to Java atomics.

**Issues:**

1.1 Kotlin-Java-Kotlin hierarchy

This solution would be a breaking change for the following scenario of Kotlin-Java-Kotlin hierarchy (the existing inheritance would break) ❌.

```kotlin
// Kotlin
open class A {
   open fun foo(x: AtomicInteger) {}
}

// Java
class B extends A {
   public void foo(@NotNull AtomicInteger x) {} // an argument type is mapped to AtomicInt now
}

// Kotlin
class C : B() {}

//ERROR:
//Inherited platform declarations clash: The following declarations have the same JVM signature (foo(Ljava/util/concurrent/atomic/AtomicInteger;)V):
//    fun foo(x: AtomicInteger): Unit defined in C
//    fun foo(x: AtomicInt): Unit defined in C
```

The reason is that at FIR stage Java atomics from java code will be mapped to Kotlin atomics,
so the declaration `foo` from Java class `B` will be seen by the Kotlin compiler as `public fun foo(x: AtomicInt)`.
Thus, there will be two different declarations available in class `C`,
but during Codegen Kotlin atomics will be mapped to Java atomics, which will cause the declaration clash.

1.2 Consider this existing Kotlin code, which obtains Java atomic from Java code:

```kotlin
// Java
class B {
    private final AtomicInteger counter = new AtomicInteger(99);
    public AtomicInteger atomicCounter() { return counter; }
}

// Kotlin
fun foo(atomicCounter: AtomicInteger) {...}

foo(b.atomicCounter()) // Argument type mismatch: actual type is 'kotlin.concurrent.atomics.AtomicInt!', but 'java.util.concurrent.atomic.AtomicInteger' was expected.
```

And this would be another breaking change ❌. Plus, this kind of API is widespread in Java.

> The same behavior is true for Kotlin Strings, but this was not an issue as there was no existing Kotlin code, which used Java Strings, when Kotlin Strings were introduced.

#### 2. Implement Atomics like `kotlin.Nothing`

> `kotlin.Nothing` and `java.lang.Void` are incompatible types, and `java.lang.Void` types from Java are loaded as `java.lang.Void`. 
> At codegen `kotlin.Nothing` is mapped to `java.lang.Void`.

**At FIR stage:**
- In Kotlin code, Kotlin atomics and Java atomics are incompatible.
- Java Atomics from Java code are seen as Java atomics.

**At Codegen:**
- Kotlin atomics are mapped to Java atomics.

**Issues:**

2.1 With this solution, the following Kotlin-Java-Kotlin hierarchy would result in an error.

```kotlin
// Kotlin
open class A {
  open fun foo(x: AtomicInt) {}
}

// Java
class B extends A {
  public void foo(@NotNull AtomicInteger x) {}
}

// Kotlin 
class C : B() {}


// compileKotlin error:
//Inherited platform declarations clash: The following declarations have the same JVM signature (foo(Ljava/util/concurrent/atomic/AtomicInteger;)V):
//fun foo(x: AtomicInteger): Unit defined in C
//fun foo(x: AtomicInt): Unit defined in C
```

The reason is that at FIR check Kotlin and Java atomic types are different,
so there are two different declarations available in class `C`,
but during Codegen Kotlin atomics are mapped to Java atomics, which causes the declaration clash.

Though this would not be a breaking change (the old Kotlin code using Java atomics will not be broken), this would be a problem only in the following cases:
- A user migrates from Java atomics to Kotlin atomics and gets the K-J-K hierarchy problem, like in the example above -> compilation fails
- Or the new method `foo(x: AtomicInt)` is added to the Kotlin class and then overriden in Java

**How we can address this Kotlin-Java-Kotlin hierarchy problem:**

- From the front-end point of view, there can be a subtyping relation between `AtomicInt` and `AtomicInteger`,
then we can load java classes doing the full mapping like in the option 1, but mapping java `AtomicInteger` types to _flexible types_ `AtomicInt`..`AtomicInteger`.
- Forbid to use Kotlin atomics in open functions (via `@OptIn` or a compiler flag).
- We could introduce a compiler check: some Java function overrides a Kotlin function with Kotlin atomics in the signature.

**Can we actualize an expect function with `k.c.a.AtomicInt` with an actual function with `j.u.c.a.AtomicInteger`?**

With the implementation, when Kotlin and Java atomics are incompatible types until codegen, 
one cannot actualize an expect function having a `k.c.a.AtomicInt` in its signature with the function using `j.u.c.a.AtomicInteger`.

- When the actual function is written in Kotlin
  - the user should migrate to `k.c.a.AtomicInt` in kotlin code first.
- When the actual function is written in Java 
  - Unfortunately, an expect Kotlin function using Kotlin atomics cannot be actualized with a Java implementation using Java atomics.
  Though if this scenario appears to be popular, it may be supported in the compiler by introduction of _flexible type mapping_ at FIR, 
  which was mentioned as one of the possible solutions to the Kotlin-Java-Kotlin hierarchy problem above.

**Java Interop:**

In this solution, we do not map Java atomics obtained from Java to Kotlin atomics, so we need conversion functions for interop.

```kotlin
public fun AtomicInt.asJavaAtomic(): AtomicInteger = this as AtomicInteger

public fun AtomicInteger.asKotlinAtomic(): AtomicInt = this as AtomicInt
```

And a similar conversion functions for Atomic Arrays:

```kotlin
public fun AtomicIntArray.asJavaAtomicArray(): AtomicIntegerArray = this as AtomicIntegerArray

public fun AtomicIntegerArray.asKotlinAtomicArray(): AtomicIntArray = this as AtomicIntArray
```

### Resolution

The second implementation option was selected as the final approach: Kotlin atomics are mapped to Java atomics only during code generation,
at FIR stage Kotlin and Java atomics are incompatible types for both Kotlin and Java code.

For now, we did not address the issue with Kotlin-Java-Kotlin hierarchy and marked Atomics with `@ExperimentalAtomicApi` annotation.
We will process user’s feedback, and in case this appears to be an actual problem,
we can proceed with either introducing `@OptIn` annotation on open Kotlin functions with Kotlin atomics in the signature,
or go for one of the possible solutions described above.

> We've considered other implementation options as well, though none have proven to be ideal. You can find more details [here](#alternative-options-of-jvm-implementation).

## API design

Built-in types allowed us the flexibility to introduce any API, provided we have corresponding Java counterparts to map this API to.
The following API options were considered:

**1. Keep API identical to Java.**

<details>
<summary>Common AtomicInt API equal to Java</summary>

```kotlin
public expect class AtomicInt {
  public fun get(): Int

  public fun set(newValue: Int)

  public fun getAndSet(newValue: Int): Int
  
  public fun compareAndSet(expected: Int, newValue: Int): Boolean

  public fun compareAndExchange(expected: Int, newValue: Int): Long
  
  public fun getAndAdd(delta: Int): Int

  public fun addAndGet(delta: Int): Int

  public fun getAndIncrement(): Int
  
  public fun incrementAndGet(): Int
  
  public fun decrementAndGet(): Int

  public fun getAndDecrement(): Int
  
  public override fun toString(): String
}
```
</details>

👍 Pros:
* All the advantages of keeping Java atomics API that we would get with **typealias** (smooth migration, easy knowledge transfer, automatic API evolution).
* The problem with interpretation of Java _"get"_ methods as Kotlin properties, which we mentioned in the section about typealias, will not occur here.

☹️ Cons:
* In this case we would have Kotlin entities with names, which contradict with Kotlin naming conventions. E.g., new Java methods `getPlain` / `setPlain`, will look like a `plain` property.

> In order to use [Java atomics API](https://docs.oracle.com/en%2Fjava%2Fjavase%2F22%2Fdocs%2Fapi%2F%2F/java.base/java/util/concurrent/atomic/AtomicReference.html) which is not provided by Kotlin atomics, 
> one should first cast Kotlin atomic to Java atomic, and then invoke a method:
> ```
> val a = AtomicInt(0)
> a.asJavaAtomicInt().updateAndGet { ... }
> ```
> How are we going to add new Java API to Kotlin atomics? 


**2. Keep API identical to Java but add a `@Volatile var value` property instead of `get`/`set` methods.**

<details>
<summary>Common AtomicInt with value property</summary>

```kotlin
public expect class AtomicInt {
  @Volatile public var value: Int

  public fun getAndSet(newValue: Int): Int
  
  public fun compareAndSet(expected: Int, newValue: Int): Boolean

  public fun compareAndExchange(expected: Int, newValue: Int): Long
  
  public fun getAndAdd(delta: Int): Int

  public fun addAndGet(delta: Int): Int

  public fun getAndIncrement(): Int
  
  public fun incrementAndGet(): Int
  
  public fun decrementAndGet(): Int

  public fun getAndDecrement(): Int
  
  public override fun toString(): String
}
```
</details>

👍 Pros:
* Atomic value accessors will match the Kotlin style: `a.value = 5` instead of `a.set(5)`.
* Already existing similar [kotlinx-atomicfu API](https://github.com/Kotlin/kotlinx-atomicfu/blob/master/atomicfu/src/commonMain/kotlin/kotlinx/atomicfu/AtomicFU.common.kt).

☹️ Cons:
* All the disadvantages of the previous solution.
* Erroneous usage pattern of `value` property is quite popular, and doesn't look suspicious; this may cause unconscious concurrent bugs, which are hard to catch on review e.g.:

```kotlin
private val requestCounter = atomic(0L)

while(true) {
    ...
    requestCounter.value += 1 // NON-ATOMIC! Should use of a.incrementAndGet() instead
}
```

_Maybe IDE could help?_

IDE could underline such erroneous value usages.
Though this may look confusing, when the easy and correct looking code is replaced with something more complicated, 
e.g.: `a.value += 1` will be replaced with `a.incrementAndGet()`.

**3.Adopt a well-established naming convention: `load`/`store`/`fetch`**

If we decide for Java independent naming, then this would be the proposed API:

```kotlin
public expect class AtomicInt {
  public fun load(): Int
  
  public fun store(newValue: Int)
  
  public fun exchange(newValue: Int): Int

  public fun compareAndSet(expected: Int, newValue: Int): Boolean
  
  public fun compareAndExchange(expected: Int, newValue: Int): Int
  
  public fun fetchAndAdd(delta: Int): Int
  
  public fun addAndFetch(delta: Int): Int
  
  public fun fetchAndIncrement(): Int
  
  public fun incrementAndFetch(): Int
  
  public fun decrementAndFetch(): Int
  
  public fun fetchAndDecrement(): Int
  
  public inline operator fun plusAssign(delta: Int)
  
  public inline operator fun minusAssign(delta: Int)

  public override fun toString(): String
}
```

<details>
<summary>AtomicLong</summary>

```kotlin
public expect class AtomicLong {
  public fun load(): Long

  public fun store(newValue: Long)

  public fun exchange(newValue: Long): Long
  
  public fun compareAndSet(expected: Long, newValue: Long): Boolean

  public fun compareAndExchange(expected: Long, newValue: Long): Long
  
  public fun fetchAndAdd(delta: Long): Long

  public fun addAndFetch(delta: Long): Long

  public fun fetchAndIncrement(): Long
  
  public fun incrementAndFetch(): Long
  
  public fun decrementAndFetch(): Long

  public fun fetchAndDecrement(): Long
  
  public inline operator fun plusAssign(delta: Long)

  public inline operator fun minusAssign(delta: Long)
  
  public override fun toString(): String
}
```

</details>

<details>
<summary>AtomicBoolean</summary>

```kotlin
public expect class AtomicBoolean { 
  public fun load(): Boolean

  public fun store(newValue: Boolean)

  public fun exchange(newValue: Boolean): Boolean
  
  public fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean

  public fun compareAndExchange(expected: Boolean, newValue: Boolean): Boolean
  
  public override fun toString(): String
}
```

</details>

<details>
<summary>AtomicReference</summary>

```kotlin
public expect class AtomicReference<T> {
  public fun load(): T
  
  public fun store(newValue: T)
  
  public fun exchange(newValue: T): T
  
  public fun compareAndSet(expected: T, newValue: T): Boolean

  public fun compareAndExchange(expected: T, newValue: T): T
  
  public override fun toString(): String
}
```

</details>

More complex atomic transformations, like multiplying a value, or performing a bitwise operation on it,
could be built using the provided core API.
To avoid the necessity of writing boilerplate code required to implement such transformations,
the library will provide a family of `update` functions:
- `update`
- `fetchAndUpdate`
- `updateAndFetch`

All these functions should accept a transformer function responsible for computing a new value.
As names suggest, update functions will either return nothing, the old or the updated value.
To perform an arbitrary transformation atomically, these functions will invoke a supplied
transformation in a loop until a compare-and-set operation will succeed. That means
the transformation will be invoked one or more times.

The only atomic type that will not get its update functions is `AtomicBoolean`,
as all transformations of its values are trivial and could be easily expressed using the core API.

<details>
<summary>Update-functions API</summary>

```kotlin
public expect inline fun AtomicInt.update(transform: (Int) -> Int): Unit
public expect inline fun AtomicInt.fetchAndUpdate(transform: (Int) -> Int): Int
public expect inline fun AtomicInt.updateAndFetch(transform: (Int) -> Int): Int

public expect inline fun AtomicLong.update(transform: (Long) -> Long): Unit
public expect inline fun AtomicLong.fetchAndUpdate(transform: (Long) -> Long): Long
public expect inline fun AtomicLong.updateAndFetch(transform: (Long) -> Long): Long

public expect inline fun <T> AtomicReference<T>.update(transform: (T) -> T): Unit
public expect inline fun <T> AtomicReference<T>.fetchAndUpdate(transform: (T) -> T): T
public expect inline fun <T> AtomicReference<T>.updateAndFetch(transform: (T) -> T): T
```
</details>

**3.1 Why `load`/`store`?**

* `a.value++` could be confused with atomic increment of the value, while the result of `a.load()` cannot be incremented like that.
* It translates semantics more clearly: `a.load()` tells explicitly that a value will be loaded and that should nudge towards storing a local copy where previously one could write something like:
  `if (a.value == 42) if (a.compareAndSet(a.value, 42)) { ... })`.
* It aligns with the vocabulary used across many other languages.
* It could be extended to support various memory orderings quite naturally by providing an overload accepting the ordering type (`load(Ordering)/store(Ordering, Value)`).


**3.2 Why `fetchAndAdd`?**

Given that `load`/`store` are atomic value accessors, we had these options for the names of the remaining methods:

* Keeping Java-like methods `getAndAdd`/`getAndIncrement`, we would contradict ourselves. And as was already mentioned `get ` is reserved for getters. ➖
* Using `load` word for these methods (e.g. `loadAndAdd`/`loadAndIncrement`) would be misleading, because `load` does not have semantics of using the loaded value within an operation. ➖
* Apply _fetch-and-modify_ naming scheme: `fetch` word is consistently used in the atomic API in other languages and it semantically means that the obtained value is used within the operation. ✅

  * `fetchAndAdd`/`addAndFetch`
  * `fetchAndIncrement` / `incrementAndFetch`
  * `fetchAndDecrement` / `decrementAndFetch`

**3.3 Exchange methods**
* `exchange` is introduced to replace Java-like `getAndSet` method.
  Moreover, _exchange_ suffix is already present in `compareAndExchange` method.
* `compareAndSet` is left unchanged though, as it is a standard name for this operation already.

**3.4 Atomic increment**

For any of the proposed options it would be nice to introduce `+=` and `-=` operators in addition to increment methods. They will simplify code patterns where
the atomic value is incremented, but the return value is not immediately needed.

```kotlin
private val requestCounter = atomic(0L)

while(true) {
    ...
    requestCounter += 1 // Now this increment is atomic
}
```

_Why don't we introduce `++` / `--` operators?_

According to the definition of [increment/decrement operators](https://kotlinlang.org/docs/operator-overloading.html#increments-and-decrements), 
the incremented value should be a mutable variable.

**3.5 What is with Java interop?**

This solution makes migration from Java to Kotlin atomics more complicated than just changing the import package name.
Though we could use the help of IDE to simplify the migration: 
provide suggestions to replace a Java Atomic with a Kotlin atomic, automatic renaming the types and methods, etc. 
This is just a task to implement, which is out of scope of this KEEP.

👍 Pros:
* We're already on the complicated path of introducing built-in types, thereby we can shape our own Kotlin style API.
* This naming aligns with atomic API across many other languages.
* Solves naming concerns from the previous solutions.

☹️ Cons:
* The migration is not fully automated.
* The introduction of a new vocabulary for atomics obstructs easy knowledge transfer.

### Atomic Array types

Atomic Arrays API aligned with the last API option for Atomic types would be the following:

```kotlin
public expect class AtomicIntArray public constructor(size: Int) {
    public val size: Int
    
    public fun loadAt(index: Int): Int
    
    public fun storeAt(index: Int, newValue: Int)
    
    public fun exchangeAt(index: Int, newValue: Int): Int
    
    public fun compareAndSetAt(index: Int, expectedValue: Int, newValue: Int): Boolean
    
    public fun compareAndExchangeAt(index: Int, expectedValue: Int, newValue: Int): Int
    
    public fun fetchAndAddAt(index: Int, delta: Int): Int
    
    public fun addAndFetchAt(index: Int, delta: Int): Int
    
    public fun fetchAndIncrementAt(index: Int): Int
    
    public fun incrementAndFetchAt(index: Int): Int
    
    public fun fetchAndDecrementAt(index: Int): Int
    
    public fun decrementAndFetchAt(index: Int): Int
    
    public override fun toString(): String
}

public expect fun AtomicIntArray(size: Int, init: (Int) -> Int): AtomicIntArray
```

<details>
<summary>AtomicLongArray</summary>

```kotlin
public expect class AtomicLongArray public constructor(size: Int) {
  public val size: Int
  
  public fun loadAt(index: Int): Long
  
  public fun storeAt(index: Int, newValue: Long)
  
  public fun exchangeAt(index: Int, newValue: Long): Long
  
  public fun compareAndSetAt(index: Int, expectedValue: Long, newValue: Long): Boolean
  
  public fun compareAndExchangeAt(index: Int, expectedValue: Long, newValue: Long): Long
  
  public fun fetchAndAddAt(index: Int, delta: Long): Long
  
  public fun addAndFetchAt(index: Int, delta: Long): Long
  
  public fun fetchAndIncrementAt(index: Int): Long
  
  public fun incrementAndFetchAt(index: Int): Long
  
  public fun fetchAndDecrementAt(index: Int): Long
  
  public fun decrementAndFetchAt(index: Int): Long
  
  public override fun toString(): String
}

public expect inline fun AtomicLongArray(size: Int, init: (Int) -> Long): AtomicLongArray
```

</details>

<details>
<summary>AtomicArray</summary>

```kotlin
public expect class AtomicArray<T> public constructor(size: Int) {
  public val size: Int
  
  public fun loadAt(index: Int): T

  public fun storeAt(index: Int, newValue: T)

  public fun exchangeAt(index: Int, newValue: T): T
  
  public fun compareAndSetAt(index: Int, expectedValue: T, newValue: T): Boolean
  
  public fun compareAndExchangeAt(index: Int, expectedValue: T, newValue: T): T

  public override fun toString(): String
}

public expect inline fun <reified T> AtomicArray(size: Int, init: (Int) -> T): AtomicArray<T>
```

</details>

Following scalar atomic API design, an update-functions API will be provided for atomic arrays too
and it will include the following functions:
- `updateAt`
- `updateAndFetchAt`
- `fetchAndUpdateAt`

These functions will allow applying arbitrary transformations to a selected array element atomically.
Please refer to the scalar atomics type section of this document for more details on similar operations.

<details>
<summary>Update-functions API</summary>

```kotlin
public expect inline fun AtomicIntArray.updateAt(index: Int, transform: (Int) -> Int): Unit
public expect inline fun AtomicIntArray.updateAndFetchAt(index: Int, transform: (Int) -> Int): Int
public expect inline fun AtomicIntArray.fetchAndUpdateAt(index: Int, transform: (Int) -> Int): Int

public expect inline fun AtomicLongArray.updateAt(index: Int, transform: (Long) -> Long): Unit
public expect inline fun AtomicLongArray.updateAndFetchAt(index: Int, transform: (Long) -> Long): Long
public expect inline fun AtomicLongArray.fetchAndUpdateAt(index: Int, transform: (Long) -> Long): Long

public expect inline fun <T> AtomicArray<T>.updateAt(index: Int, transform: (T) -> T): Unit
public expect inline fun <T> AtomicArray<T>.updateAndFetchAt(index: Int, transform: (T) -> T): T
public expect inline fun <T> AtomicArray<T>.fetchAndUpdateAt(index: Int, transform: (T) -> T): T
```
</details>

**1. Atomic array size**

All Kotlin Arrays introduce `size` property for getting the size of the array.

**2. Atomic array accessors**

K/N atomic arrays currently define `get`/`set` operators to access atomic array elements:

```kotlin
public operator fun get(index: Int): Int
public operator fun set(index: Int, newValue: Int): Unit
```

The operators are used like this:

```kotlin
val a: Int = arr[5] // get
arr[5] = 77 // set
// Though for other operations we should pass an index of an element as an argument.
arr.compareAndSet(5, 77, 88)
```

It's proposed to replace `get`/`set` operators with `loadAt`/`storeAt` methods:

```kotlin
val a: Int = arr.loadAt(5) // get
arr.storeAt(5, 77) // set
arr.compareAndSetAt(5, 77, 88)
```

Motivation:
* Consistency with atomic `load`/`store` value accessors
* It's harder to misuse the return value, trying to increment an atomic array element:
```kotlin
// With get operator
val arr = AtomicIntArray(10)
arr[5]++ // A user may actually mean atomic increment: arr.fetchAndIncrementAt(5)
```

```kotlin
// With load method
val arr = AtomicIntArray(10)
arr.fetchAndIncrementAt(5) // This is the only way to atomically increment an array element, arr.loadAt(5) cannot be incremented
```

* Consistent usage of all methods passing an index of an element as an argument.


**3. _at_ suffix**

Once we pass an element index as an argument, a call like `arr.store(5, 77)` is less expressive and clear than `arr.storeAt(5, 77)`.
`storeAt` improves readability and makes it immediately clear, that `77` is stored at index `5`.

This syntax also alligns with Kotlin collections methods like `elementAt`,`removeAt`...


## Atomicfu and optimized atomics

As mentioned earlier, our goal is to automate the inlining of boxed atomics 
and avoid the need for users to use a separate, inconvenient API set with intrinsics invoked on a property reference.

We can use `kotlinx-atomicfu` library for this purpose. Currently, `kotlinx-atomicfu` has the set of its own atomic types (`kotlinx.atomicfu.AtomicInt/Long/Boolean/Ref`),
and when the _atomicfu compiler plugin_ is applied, these `kotlinx.atomicfu` atomics are inlined.

Our future goal is to eliminate the separate set of `kotlinx.atomicfu` atomics entirely.
The _atomicfu compiler plugin_ would then directly inline `kotlin.concurrent.atomics` atomic types on demand,
without requiring users to replace standard library atomics with those from the library.

**Problem:** the _atomicfu compiler plugin_ imposes certain constraints on the usage of atomics.
E.g., it can only inline atomics that are `private`/`internal` `val` properties, which are not saved/passed elsewhere etc. (see [atomicfu constraints](https://github.com/Kotlin/kotlinx-atomicfu?tab=readme-ov-file#usage-constraints))

**How it could be solved:**

* The _atomicfu compiler plugin_ could first check which atomics can be inlined (atomics that are not exposed in public or passed as a parameter etc.) and inline only those atomics.
* Introduce a special annotation (e.g. `@InlineAtomic`) to mark the atomics, which a user wants to inline. Then the _atomicfu compiler plugin_ checks constraints for those atomics and inlines them.
* Only inline atomics which were created with `atomic(0)` factory function.
* FIR plugin that checks whether an atomic can be inlined and provides IDE inspection if the constraints are not satisfied.

> Streamlining the `kotlinx-atomicfu` machinery to Kotlin is an explicit next step of this proposal and a subject of the next KEEP after atomics stabilization.

## Implementation details for all backends

### Alternative options of JVM implementation

In the [Atomic API design section](#atomic-types) above, we've described built-in types as the most reasonable way to implement atomics. 
Aside from that, we've also considered other alternatives:

* **Inheritance**

```kotlin
actual class AtomicInt : java.util.concurrent.atomic.AtomicInteger() {
  public fun load(): Int
  public fun store(newValue: Int)
  
  // Implement all the methods that differ from Java Atomic API
}
```

**Why not:** `kotlin.concurrent.atomics.AtomicInt` and `java.util.concurrent.atomic.AtomicInteger` are different types.
Java atomics and inheritors of Java atomics are not Kotlin atomics.

Therefore, we need conversion functions for interop (`asJavaAtomic` / `asKotlinAtomic`).
But these conversion functions cannot be easily implemented because we cannot just wrap the current value into the new box but need to update the same address in memory.

* **Value class**

```kotlin
@JvmInline
actual value class AtomicInt(private val atomicInt: AtomicInteger) {
    fun load() = atomicInt.get()
}
inline fun AtomicInt(x: Int) = AtomicInt(AtomicInteger(x))
```

**Why not:**
* Loosely defined different value semantics on different platforms
* JVM atomics will have no identity (`===`), while K/N atomics will have it.
* Value classes are not interoperable with Java, e.g., signatures of functions with inline classes get mangled, and this may cause behaviors like this:

```kotlin
// kotlin
@JvmInline
value class MyAtomic(val a: AtomicInteger)

open class A {
    fun foo(x: MyAtomic) {}
}

// java
abstract class B extends A {
    public void bar(@NotNull AtomicInteger x) {
        super.foo(x); // compileJava error! cannot find symbol: method foo(@org.jetbrains.annotations.NotNull AtomicInteger)
    }
}
```



### K/N implementation

There are K/N Atomics in [kotlin.concurrent](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.concurrent/) package, 
with API that differs from the proposed options for common atomic API and it is not marked as experimental. 

E.g. K/N atomics provide `@Volatile public var value: T` and Java-like increment methods `getAndIncrement` / `getAndAdd`.
The methods, which would differ from the decided common API will be deprecated and left in this package for a number of release cycles. 

### JS / WASM implementation

Implementation for these backends will be single threaded.

## Placement

Kotlin atomics will be placed in the new `kotlin.concurrent.atomics` package of the Kotlin Standard Library.
There is a reason to introduce a new package instead of keeping atomics in the existing `kotlin.concurrent` package, where we have K/N atomics already.

If there were introduced classes like `AtomicLong` or `AtomicBoolean` in the existing `kotlin.concurrent` package, 
this might cause the resolution ambiguity between `kotlin.concurrent` and `java.util.concurrent.atomic` package, 
since the Java package provides the classes with the equal names. 

More specifically, if there are star explicit imports of both packages in the existing code, updating the version of `kotlin-stdlib` would cause a compilation error: 

```kotlin
import java.util.concurrent.atomic.*
import kotlin.concurrent.*
```

Thus, to avoid these potential issues, it was decided to place atomics in the new `kotlin.concurrent.atomics` package.
This means, that the exisiting K/N atomics from `kotlin.concurrent` package will be deprecated,
and it will be possible to replace them with the new actual K/N atomics with the new API.

## Java 9

There is some API that was introduced [since Java 9 or newer](https://docs.oracle.com/en%2Fjava%2Fjavase%2F22%2Fdocs%2Fapi%2F%2F/java.base/java/util/concurrent/atomic/AtomicReference.html).

We can expand Kotlin atomics API with this new Java API later.

If we want to do so for JVM atomics, then we can either implement this API with existing methods (e.g. `compareAndExchange` via `compareAndSet`) for Java 8 and
delegate to Java implementation, when we support **multi-release jars**.

## Future advancements

* Additional API that provides memory ordering options.
* Optimizable atomics as part of Kotlin (see [atomicfu section](#atomicfu-and-optimized-atomics))

## IDE support

* Underline usages of Java atomics in the new Kotlin code, like it's currently done for `java.lang.String`.
* Automatic migrations from Java to Kotlin atomics (see the [API design](#api-design) section).

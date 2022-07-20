# Sealed Inline Classes
* **Type**: Design proposal
* **Author**: Ilmir Usmanov
* **Contributors**: Roman Elizarov, Alexander Udalov
* **Status**: Experimental
* **Prototype**: TODO

Discussion of this proposal is held in [this issue](TODO).

## Summary

Currently, there is no type-safe way to create an inline class, which can hold several types, similar to union type. 
The only way to use `Any?` as underlying type, which leads to a lot of `is` checks and cast the underlying value to expected type.

We propose to support sealed inline classes, which are inline classes, which can hold several distinct types. 

Sealed inline classes are sealed classes as well, which allows a programmer to distinguish the types, which the inline class can hold.

## Motivation / use cases

The main use-case for sealed inline classes is `Result`. Currently it is decraled as inline class with underlying type `Any?`
```kotlin
@JvmInline
value class Result<out T>(val value: Any?) {
  class Failure(val throwable: Throwable)
}
```

The proposed changes to `Result` are
```kotlin
@JvmInline
sealed value class Result<out T> {
  @JvmInline
  value class Success<out T>(val value: T): Result<T>()
  value class Failure(val throwable: Throwable): Result<Nothing>()
}
```

The changes also rely on [inline classes with generic underlying value](https://youtrack.jetbrains.com/issue/KT-32162/Allow-generics-for-inline-classes).

So, to create the `Result` value one will use constructors instead of factory functions. In other words, instead of
```kotlin
val success = Result.success(1)
val failure = Result.failure(IllegalStateException("fail"))
```

a programmer can write
```kotlin
val success = Result.Success(1)
val failure = Result.Failure(IllegalStateException("fail"))
```

In addition, since sealed inline classes are sealed classes, one can use `when` to check the value, in case of `Result` 
one currently should use `isSuccess` and `isFailure` utility function, another solution is, of course, `getOrThrow`.

So,
```kotlin
val s = try {
  result.getOrThrow()
} catch(e: Throwable) {
  e.message!!
}
```

becomes
```koltin
when(result) {
  is Result.Success<*> -> result.value
  is Result.Failure -> result.throwable.message!!
}
```

As you can see, sealed inline classes support smart casts.

## Restrictions

Sealed inline classes are both inline classes and sealed classes. Thus, they are implemented differently from usual inline classes.

- Sealed inline class children can be either inline and noinline. For example, in `Result`, `Success` is an inline class 
  and `Failure` - noinline. Inline children shall be annotated with `@JvmInline` annotation, since they are inline classes. 
- In Kotlin/JS and Kotlin/Native, there is no need to annotate the inline children.

- All sealed inline classes are mapped to `Any?`. That is because their underlying types are union of all the inline class children's underlying types with noinline children types. 

For example, if the sealed inline class is declared as
```kotlin
@JvmInline
sealed value class IC {
  @JvmInline
  value class ICString(val s: String): IC()
  @JvmInline
  value class ICInt(val i: Int): IC()
  value class Error(val throwable: Throwable): IC()
}
```

The resulting underlying type is `String | Int | IC.Error`, which is `Any?`. 

Of course, we can map to common supertype. Consider the following example
```kotlin
@JvmInline
sealed value class IC {
  @JvmInline
  value class ICInt(val i: Int): IC()
  @JvmInline
  value class ICLong(val l: Long): IC()
}
```

here, we can use `Number` as the underlying type. However, adding non-`Number` child will change the underlying type, 
leading to changes in function signatures of the users, which breaks binary compatibility. For that reason, we always map sealed inline classes to `Any?`.

- Boxed inline class children have the type of the parent. Since the motivation for sealed inline classes is `Result`, we want to keep existing behavior. Thus, there can be no `Result.Success` type in runtime. They are represented as `Result`.

- Noinline children are also boxed. In addition to being consistent with existing behavior of `Result`, there is also type safety reason for that decision.

Consider the following example.
```kotlin
@JvmInline
sealed value class I

@JvmInline
value class IC(val i: I): I()

value class O: I()

fun foo(): I = IC(O())
```

If we do not box `O` to `I`, when we map to `Any?` (remember, sealed inline classes are mapped to `Any?`), it will appear, that `foo` returns `O` instead of `IC`.

In case of `Result`, that will mean, that there is no way to represent `Success(Failure)`.

Thus, we box noinline children with sealed inline class type, when the sealed inline class type should be boxed as well. In other words, when inline class with underlying type `Any?` should be boxed.

This is the reason for the following restrictions.

- Noinline children (classes and objects) should have `value` modifier, as shown in the examples. Since the noinline children can be boxed, they cannot have stable identity. In other words, they are identitiless, and we use `value` modifier to mark them.

- Children of sealed inline classes cannot implement interfaces.

 - Inline children's boxes are represented as parents, so, they cannot implement interfaces, which are different from the parent's interfaces.

 - Noinline children are boxed to parents as well, so, their superinterfaces cannot be different.

- Underlying types of children should be distinguishable. That leads to the following restrictions:
  - Final class and class or interface: the final class cannot extend or implement the other type.
  - Open class and open class: they cannot be subtypes of one another.
  - Open class and interface: forbidden - one can extend the class and implement the interface at the same time, so there will be no way to distinguish them.
  - Interface and interface: ditto.

- Sealed inline classes cannot have primary constructors.

- If sealed inline class is a child of another sealed inline class, there can be no other inline children, including other sealed inline class, since sealed inline classes are mapped to `Any?`, which is open class, which other classes override and there is no way to distinguish `Any?` from other type.

- Value objects must be a child of sealed inline class.

- Value objects cannot be annotated with `@JvmInline`.

- Other restrictions of inline classes also apply to sealed inline classes.
  - Sealed inline classes can only implement interfaces.
  - Sealed inline classes and inline children cannot have backing fields.
  - Sealed inline classes and their children do not support referencial equality (`===`).
  - Sealed inline classes and inline children cannot be local or inner.

## JVM representation
All sealed inline classes are mapped to `Any?`. The compiler generates synthetic field for the underlying value:

```kotlin
@JvmInline
sealed value class IC
```

becomes:

```kotlin
class IC {
  val $value: Any?
}
```

There can be no boxed inline children.

If one of children has primitive underlying value, the value is boxed:
```kotlin
@JvmInline
sealed value class IC

@JvmInline
value class ICInt(val i: Int): IC()

value object ICObject: IC()

val ic: IC = ICInt(1)
```

the compiler generates the following code

```kotlin
val ic = Integet.valueOf(1)
```

The following rulues for passing to function and returning from the function apply:

- When we pass sealed inline class, it is mapped to `Any?`:
```kotlin
fun foo(ic: IC)
```

becomes:
```kotlin
fun foo-<hash>(ic: Any?)
```

`<hash>` is computed using usual [inline classes mangling rules](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#mangling-rules).

- When we pass nullable sealed inline class, it is mapped to boxed selaed inline class:
```kotlin
fun foo(ic: IC?)
```

becomes:
```kotlin
fun foo-<hash>(ic: IC?)
```

- When we pass inline child, it is mapped to reference underlying type:
```kotin
fun foo(icInt: ICInt)
```

becomes:
```kotlin
fun foo-<hash>(icInt: Int?)
```

- When we pass nullable inline child, it is mapped to sealed inline class:
```kotlin
fun foo(icInt: ICInt?)
```

becomes:
```kotlin
fun foo-<hash>(icInt: IC?)
```

Since there can be no boxed inline children.

- When we pass noinline child, nothing special happens:
```kotlin
fun foo(icObject: ICObject)
``` 

becomes:
```kotlin
fun foo(icObject: ICObject)
```

## Is checks
Consider the following example
```kotlin
interface I

interface I2

@JvmInline
sealed value class IC: I {
  @JvmInline
  value class InlineI(val i: I): IC()
  @JvmInline
  value class InlineI2(val i2: I2): IC()

  value class Noinline: IC()
}

if (ic is IC.InlineI) {
  // ...
}
```

In `if (ic is InlineI)` we should check, that underlying type of `ic` is `I`, but `Noinline` also implements `I`, so, we need to first check, that `ic` is not `Noinline` and only then we can check for `is I`.

So, for each inline child the compiler generates `is` method to do exactly that - check, that the underlying value is none of noinline children and then check, that underlying type is expected type.
```kotlin
static fun IC.is-InlineI(value: Any?): Boolean {
  when (value) {
    is IC.Noinline -> return false
    is I -> return true
    else -> return false
  }
}

static fun IC.is-InlineI2(value: Any?): Boolean {
  when (value) {
    is IC.Noinline -> return false
    is I2 -> return true
    else -> return false
  }
}
```
There names are `is-$className`, where `className` is the name of inline class child.

## Methods
Since we cannot have boxed inline children, we have to change how we handle methods. We have to generate redirections to methods in the sealed inline class itself. So, we check, which method should be called and then call it.

The hierarchy I will use is the following

```kotlin
@JvmInline
sealed value class I1 // top

@JvmInline
sealed inline class I2 : I1() // middle

value object O1: I1() // bottom

value class I3(val a: Any?): I2() // bottom

value object O2: I2() // bottom
```

### toString, hashCode, equals, etc

```kotlin
@JvmInline
sealed value class I1

@JvmInline
sealed value class I2 : I1()

value object O1: I1() {
    override fun toString(): String = "O1"
}

@JvmInline
value class I3(val s: String): I2() {
    override fun toString(): String = "I3"
}

object O2: I2()
```

Note, that `O2` does not override `toString`, so we should call `Any.toString()`
is case of `O2`, since `I2` does not override `toString` as well.

Inside `I1.toString-impl` we will have something like
```kotlin
// In I1
fun `toString-impl`(value: Any?): String {
    when(value) {
        is O1 -> return (value as O1).toString()
        is O2 -> return (value as Any).toString()
        else -> return I3.toString-impl(value)
    }
}

// In I2
// Nothing

// In I3
fun `toString-impl`(value: Any?): String = "I3${`$value`}"
```

The logic for `toString` applies for all methods, decrared in interfaces: if the method is not overridden in inline child, we do not generate redirect.

Note, that there is no function generated for the middle - we do not need one,
since we cannot have objects of the middle type. Thus, we do not have `super`
calls in the hierarchy outside the top.

## Open function on top, override in bottom

```kotlin
@JvmInline
sealed value class I1 {
    open fun str(): String = "I1"
}

@JvmInline
sealed value class I2 : I1()

sealed object O1: I1() {
    override fun str(): String = "O1"
}

@JvmInline
value class I3(val a: Any?): I2() {
    override fun str(): String = "I3"
}

value object O2: I2()
```

The compiler generates the following:

```kotlin
fun `str-impl`(value: Any?): String {
    when (value) {
        is O1 -> return (value as O1).str()
        is O2 -> return "I1"
        else -> return I3.str-impl(value)
    }
}

// In I2
// Nothing

// In I3
fun `str-impl`(value: Any?): String = "I3"
```

Note, that we simply copy original body if there is no override in children.

## Open function on top, override in middle and bottom

```kotlin
@JvmInline
sealed value class I1 {
    open fun str(): String = "I1"
}

@JvmInline
sealed value class I2 : I1() {
    override fun str(): String = "I2"
}

value object O1: I1()

@JvmInline
value class I3(val a: Any?): I2() {
    override fun str(): String = "I3"
}

value object O2: I2()
```

Generated code:

```kotlin
// In I1
fun `str-impl`(value: Any?): String {
    when (value) {
        is O1 -> return "I1"
        else -> return I2.str-impl(value)
    }
}

// In I2
fun `str-impl`(value: Any?): String {
    when (value) {
        is O2 -> return "I2"
        else -> return I3.str-impl(value)
    }
}

// In I3
fun `str-impl`(value: Any?): String = "I3"
```

## Open function on top, override in middle

```kotlin
@JvmInline
sealed value class I1 {
    open fun str(): String = "I1"
}

@JvmInline
sealed value class I2 : I1() {
    override fun str(): String = "I2"
}

value object O1: I1()

@JvmInline
value class I3(val a: Any?): I2()

value object O2: I2()
```

becomes:

```kotlin
/// In I1
fun `str-impl`(value: Any?): String {
    when (value) {
        is O1 -> return "I1"
        else -> return I2.str-impl(value)
    }
}

// In I2
fun `str-impl`(value: Any?): String = "I2"
```

## Open function on top, no override

```kotlin
@JvmInline
sealed value class I1 {
    open fun str(): String = "I1"
}

@JvmInline
sealed value class I2 : I1()

value object O1: I1()

@JvmInline
value class I3(val a: Any?): I2()

value object O2: I2()
```

The simplest case:

```kotlin
fun `str-impl`(value: Any?): String = "I1"
```

## Open function in middle, no override

```kotlin
@JvmInline
sealed value class I1

@JvmInline
sealed value class I2 : I1() {
    open fun str(): String = "I2"
}

value object O1: I1()

@JvmInline
value class I3(val a: Any?): I2()

value object O2: I2()
```

Here, we need to generate synthetic method in `I1`, since we only box to `I1`
and we need to get to `I2`.

```kotlin
// In I1
synthetic fun `str-impl`(value: Any?): String = I2.`str-impl`(value)

// In I2
fun `str-impl`(value: Any?): String = "I2"
```

## Open function in middle, override in bottom

```kotlin
@JvmInline
sealed value class I1

@JvmInline
sealed value class I2 : I1() {
    open fun str(): String = "I2"
}

value object O1: I1()

@JvmInline
value class I3(val a: Any?): I2() {
    override fun str(): String = "I3"
}

value object O2: I2()
```

The function in top is still synthetic.

```kotlin
// In I1
synthetic fun `str-impl`(value: Any?): String = I2.`str-impl`(value)

// In I2
fun `str-impl`(value: Any?): String {
    when (value) {
        is O2 -> return "I2"
        else -> return I3.`str-impl`(value)
    }
}

// In I3
fun `str-impl`(value: Any?): String = "I3"
```

# Definitely not-nullable types

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Status**: Accepted
* **Prototype**: In progress
* **Discussion**: [KEEP-268](https://github.com/Kotlin/KEEP/issues/268)
* **Related issues**: [KT-26245](https://youtrack.jetbrains.com/issue/KT-26245)

The goal of this proposal is to allow explicitly declare definitely not-nullable type

## Background

Types based on generic parameters may or may not contain nulls depending on the nullability of their bounds:
```kotlin

fun <T : CharSequence?> foo(t: T, tn: T?) {
    t.length // call is not allowed, `t` may be null
    tn.length // call is not allowed, `tn` may be null
}

fun <F : CharSequence> bar(f: F, fn: F?) {
    f.length // call is allowed, `f` may not be null
    fn.length // call is not allowed, `fn` may be null
}
```

So, basically `T` type when the type parameter has only nullable upper bound is considered nullable, thus it cannot be 
dereferenced.
And it's quite understandable, since it's legal to have a call like `foo<String?>(null, null)` that might lead 
to NullPointerException at runtime if dereferencing was allowed.

At the same time, `t!!.length` is a valid call, so `t!!` should have some special type other than `T`.
Within compiler, such types are represented as `T & Any`, i.e. intersection type of `T` and `Any`.
Simply speaking, intersection of a type with not-nullable `Any` would make the former not-nullable, and that's what 
we need to assign it as a type for `t!!` expression.

Also, there's a special term for such types: definitely not-nullable type.

## Problem

In most cases, there's no need to declare such types in code explicitly since they are being introduced via type
inference silently.

But there are some scenarios when an explicit type declaration becomes necessary.

The original use case came from the need to override an annotated Java method
```java
public interface JBox {
    <T> void put(@NotNull T t);
}
```    

To implement/override `JBox::put` in Kotlin, one need to specify that while `T` generic parameter is nullable, the formal
`t` parameter can't be null.
And to the version Kotlin 1.5 or earlier it's effectively impossible.

## Proposal

The proposal is to introduce new syntax for such type kind: `T!!`.
This new syntax should only be allowed if `T` is resolved to a type parameter with nullable upper bounds being nullable.

The semantics of that type kind may be defined as any of the following statements (they all are interchangeable):
- `T!!` is an [intersection type](https://kotlinlang.org/spec/type-system.html#intersection-types) having a form of `T & Any`
- `T!!` is populated with all values from `T` beside `null`

Such types should be considered just as any other denotable types and allowed to be used in all contexts where `T` type 
might be used: parts of public signature, local declarations, etc.

## Examples

Using in plain public declarations

```kotlin
fun <T> elvisLike(x: T, y: T!!): T!! = x ?: y

fun main() {
    elvisLike<String>("", "").length // OK
    elvisLike<String>("", null).length // Error: 'null' for not-nullable type
    elvisLike<String?>(null, "").length // OK
    elvisLike<String?>(null, null).length // Error: 'null' for not-nullable type

    elvisLike("", "").length // OK
    elvisLike("", null).length // Error: 'null' for not-nullable type
    elvisLike(null, "").length // OK
}
```

Overrides of Java annotated methods

```kotlin
// FILE: A.java
import org.jetbrains.annotations.*;

public interface A<T> {
    public T foo(T x) { return x; }
    @NotNull
    public T bar(@NotNull T x) {}
}

// FILE: main.kt

interface B<T1> : A<T1> {
    override fun foo(x: T1): T1
    override fun bar(x: T1!!): T1!!
}
```

## Open questions

1) New syntax might look a bit confusing:
   - When being used for expressions `!!` means something unsafe that should be used with precautions 
     while value of a type `T!!` is already proved to be not-nullable.
   - `!` character in types is already used [flexible types](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md)
2) Expressions having a form of `if (x > 0) 1 else v as T!!` currently are being parsed as `(if (x > 0) 1 else v as T)!!`
while considering parsing priorities it makes sense to parse it like `if (x > 0) 1 else v as (T!!)`
   - It seems that previous parse tree should be deprecated
   - See [KT-47445](https://youtrack.jetbrains.com/issue/KT-47445)

## Timeline

The prototype is being worked on, and the feature is planned to be included under the `-language-version 1.6` flag to Kotlin 1.5.30, and enabled by default since Kotlin 1.6.

# Definitely non-nullable types

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Status**: Accepted
* **Prototype**: In progress
* **Discussion**: [KEEP-268](https://github.com/Kotlin/KEEP/issues/268)
* **Related issues**: [KT-26245](https://youtrack.jetbrains.com/issue/KT-26245)

The goal of this proposal is to allow explicitly declare definitely non-nullable type

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
Simply speaking, intersection of a type with non-nullable `Any` would make the former non-nullable, and that's what
we need to assign it as a type for `t!!` expression.

Also, there's a special term for such types: definitely non-nullable type.

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

The proposal is to introduce strictly limited syntax for intersection types exactly for the case: `T & Any`
This new syntax should only be allowed if `T` is resolved to a type parameter with nullable upper bounds being nullable.

The semantics of that type kind may be defined as any of the following statements (they all are interchangeable):
- `T & Any` is an [intersection type](https://kotlinlang.org/spec/type-system.html#intersection-types)
- `T & Any` is populated with all values from `T` beside `null`

While in the parser, any form of intersection types might be supported, the compiler must reject anything
but the types which left side is a type parameter with nullable upper bound and the right one should be resolved to
exactly non-nullable `kotlin.Any`.

Such types should be considered just as any other denotable types and allowed to be used in all contexts where `T` type 
might be used: parts of public signature, local declarations, etc.

## Syntax ambiguities

At some point, it's likely that bitwise arithmetic operations `&` and `|` will be supported in Kotlin, and it's worth preventing
possible relevant ambiguities between them and intersection types.

Thus, we propose to forbid intersection types at the following positions:
- `is/as` operators. Expressions like `expr as T & Any` shall be parsed as `(expr as T) & Any`.
  But still it's possible to have the following `expr as (T & Any)`
- Receiver type references: `fun T & Any.foo()` shall be rejected, while `fun (T & Any).foo()` must be allowed.
- The same logic for receiver types in function types: `(T & Any).() -> Unit` is OK, while `T & Any.() -> Unit` is not.

## Examples

Using in plain public declarations

```kotlin
fun <T> elvisLike(x: T, y: T & Any): T & Any = x ?: y

fun main() {
    elvisLike<String>("", "").length // OK
    elvisLike<String>("", null).length // Error: 'null' for non-nullable type
    elvisLike<String?>(null, "").length // OK
    elvisLike<String?>(null, null).length // Error: 'null' for non-nullable type

    elvisLike("", "").length // OK
    elvisLike("", null).length // Error: 'null' for non-nullable type
    elvisLike(null, "").length // OK
}
```

Overrides of Java annotated methods

```kotlin
// FILE: A.java
import org.jetbrains.annotations.*;

public interface A<T> {
    T foo(T x);
    @NotNull
    T bar(@NotNull T x);
}

// FILE: main.kt

interface B<T1> : A<T1> {
    override fun foo(x: T1): T1
    override fun bar(x: T1 & Any): T1 & Any
}
```


## Timeline

The prototype is being worked on, and the feature is planned to be included under the `-language-version 1.7` or
with `-XXLanguage:+DefinitelyNonNullableTypes` flag and enabled by default since Kotlin 1.7.

# Type classes

* **Type**: Design proposal
* **Author**: Raul Raja
* **Status**: New
* **Prototype**: -

## Summary

The goal of this proposal is to enable `typeclasses` and lightweight `Higher Kinded Types` in Kotlin to enable ad-hoc polymorphism and better extension syntax.
Type classes is the most important feature that Kotlin lacks in order to support a broader range of FP idioms.
Kotlin already has an excellent extension mechanism where this proposal fits nicely. As a side effect `Type classes as extensions` also allows for compile time
dependency injection which will improve the current landscape where trivial applications rely on heavy frameworks based on runtime Dependency Injection.
Furthermore introduction of typeclasses usages for `reified` generic functions with a more robust approach that does not require those to be `inline` or `reified`.

## Motivation

* Support Typeclass evidence compile time verification
* Support a broader range of Typed FP patterns
* Enable multiple extension functions groups for type declarations
* Enable compile time DI through the use of the Typeclass pattern
* Enable alternative for inline reified generics

## Description

We propose to introduce a new top level declaration `typeclass` that allows for generic definition of typeclasses and their instances with the same style as extension functions are defined

```kotlin
typeclass Monoid {
    fun Self.combine(b: Self): Self
    fun empty(): Self
}
```

The above declaration can serve as target for implementations for any arbitrary datatype.
In the implementation below we provide evidence that there is an `Int: Monoid` instance that enables `combine` and `empty` on `Int`

```kotlin
extension Int : Monoid {
    fun Int.combine(b: Int): Int = this + b
    fun empty(): Int = 0
}

1.combine(2) // 3
Int.empty() // 0
```

Because of this constrain where we are stating that there is a `Monoid` constrain for a given type `A` we can also encode polymorphic definitions based on those constrains:

```kotlin
fun <A : Monoid> add(a: A, b: A): A = a.combine(b)
add(1, 1) // compiles
add("a", "b") // does not compile: No `String: Monoid` instance defined in scope
```

## Compile Time Dependency Injection

On top of the value this brings to typed FP in Kotlin it also helps in OOP contexts where dependencies can be provided at compile time:

```kotlin
typeclass Context {
  fun Self.config(): Config
}
```

```kotlin
package prod

extension Service: Context {
  fun Service.config(): Config = ProdConfig
}
```

```kotlin
package test

extension Service: Context {
  fun Service.config(): Config = TestConfig
}
```

```kotlin
package prod

service.config() // ProdConfig
```

```kotlin
package test

service.config() // TestConfig
```

## Overcoming `inline` + `reified` limitations

Typeclasses allow us to workaround `inline` `reified` generics and their limitations and express those as typeclasses instead it:

```kotlin
typeclass Reified {
    val selfClass: KClass<Self>
}
```

Now a function that was doing something like:

```kotlin
inline fun <reified T> foo() { .... T::class ... }
```

can be replaced with:

```kotlin
fun <T : Reified> fooTC() { .... T.selfClass ... }
```

This allows us to obtain generics info without the need to declare the functions `inline` or `reified` overcoming the current limitations of inline reified functions.

Currently `inline fun <reified T> foo()` can't be called in non reified context such as:

```kotlin
class MyClass<T> {
  fun doFoo() = foo<T>() //fails to compile because T is not reified.
}
```

because `T` is not reified the compiler won't allow this position. With type classes this will be possible:

```kotlin
class MyClass<T: Reified> {
  fun doFoo() = fooTC<T>() // Compiles because there is evidence that there is an instance of `Reified` for `T`
}
```

## Composition and chain of evidences

Type class instances and declarations can encode further constrains in their generic args so they can be composed nicely:

```kotlin
extension Option<A: Monoid> : Monoid {

  fun empty(): Option<A> = None

  fun Option.combine(ob: Option<A>): Option<A> =
    when (this) {
      is Some<A> -> when (ob) {
                      is Some<A> -> Some(this.value.combine(b.value))
                      is None -> ob
                    }
      is None -> this
  }
    
}
```

The above instance declares a `Monoid: Option<A>` as long as there is a `A: Monoid` in scope.

```kotlin
Option(1).combine(Option(1)) // Option(2)
Option("a").combine(Option("b")) // does not compile. Found `Option<A>: Monoid` instance providing `combine` but no `String: Monoid` instance was in scope
```

We believe the above proposed encoding fits nicely with Kotlin's philosophy of extensions and will reduce the boilerplate compared to other langs that also support typeclasses such as Scala where this is done via implicits.

## Typeclasses over type constructors

We recommend if this proposal is accepted that a lightweight version of higher kinds support is included to unveil the true power of typeclasses through the extensions mechanisms

A syntax that would allow for higher kinds in these definitions may look like this:

```kotlin
typeclass FunctionK<F<_>, G<_>> {
  fun <A> invoke(fa: F<A>): G<A>
}

extension Option2List : FunctionK<Option, List> {
  fun <A> invoke(fa: Option<A>): List<A> =
    fa.fold({ emptyList() }, { listOf(it) })
}
``` 

If Higher Kinds where added along with typeclasses to the lang an alternate definition to the encoding below:

```kotlin
typeclass Functor {
    fun Self.map(b: Self): Self
}
```

could be provided such as:

```kotlin
typeclass F<_> : Functor {
    fun <A, B> map(fa: F<A>, f: (A) -> B): F<B>
}

extension Option: Functor {
    fun <A, B> map(fa: Option<A>, f: (A) -> B): Option<B>
}
```

Here `F<_>` refers to a type that has a hole on it such as `Option`, `List`, etc. 

A use of this declaration in a polymorphic function would look like:

```kotlin
fun <F<_> : Functor, A, B> transform(fa: F<A>, f: (A) -> B): F<B> = F.map(fa, f)

transform(Option(1), { it + 1 }) // Option(2)
transform("", { it + "b" }) // Does not compile: `String` is not type constructor with shape F<_>
transform(listOf(1), { it + 1 }) // does not compile: No `List<_>: Functor` instance defined in scope.
```

Some of this examples where originally proposed by Roman Elizarov and the Kategory contributors where these features where originally discussed https://kotlinlang.slack.com/archives/C1JMF6UDV/p1506897887000023 
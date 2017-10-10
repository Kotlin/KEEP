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

We propose to use the existing `interface` semantics allowing for generic definition of typeclasses and their instances with the same style interfaces are defined

```kotlin
interface Monoid<A> {
    fun A.combine(b: A): A
    fun empty(): A
}
```

The above declaration can serve as target for implementations for any arbitrary datatype.
In the implementation below we provide evidence that there is a `Monoid<Int>` instance that enables `combine` and `empty` on `Int`

```kotlin
extension object IntMonoid : Monoid<Int> {
    fun Int.combine(b: Int): Int = this + b
    fun empty(): Int = 0
}

1.combine(2) // 3
Int.empty() // 0
```

Because of this constrain where we are stating that there is a `Monoid` constrain for a given type `A` we can also encode polymorphic definitions based on those constrains:

```kotlin
fun <A> add(a: A, b: A): A given Monoid<A> = a.combine(b)
add(1, 1) // compiles
add("a", "b") // does not compile: No `String: Monoid` instance defined in scope
```

## Compile Time Dependency Injection

On top of the value this brings to typed FP in Kotlin it also helps in OOP contexts where dependencies can be provided at compile time:

```kotlin
interface Context<A> {
  fun A.config(): Config
}
```

```kotlin
package prod

extension object ProdContext: Context<Service> {
  fun Service.config(): Config = ProdConfig
}
```

```kotlin
package test

extension object TestContext: Context<Service> {
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

Typeclasses allow us to workaround `inline` `reified` generics and their limitations and express those as typeclasses instead:

```kotlin
interface Reified<A> {
    val selfClass: KClass<A>
}
```

Now a function that was doing something like:

```kotlin
inline fun <reified A> foo() { .... A::class ... }
```

can be replaced with:

```kotlin
fun <A> fooTC(): Klass<A> given Reified<A> { .... T.selfClass ... }
```

This allows us to obtain generics info without the need to declare the functions `inline` or `reified` overcoming the current limitations of inline reified functions that can't be invoked unless made concrete from non reified contexts.

```kotlin
class Foo<A> {
   val someKlazz = foo<A>() //won't compile because class disallow reified type args.
}
```

```kotlin
class Foo<A> {
   val someKlazz = fooTC<A>() //works and has no reflection runtime overhead
}
```

## Composition and chain of evidences

Type class instances and declarations can encode further constrains in their generic args so they can be composed nicely:

```kotlin
extension class OptionMonoid<A> : Monoid<Option<A>> given Monoid<A> {

  fun empty(): Option<A> = None

  fun Option.combine(ob: Option<A>): Option<A> =
    when (this) {
      is Some<A> -> when (ob) {
                      is Some<A> -> Some(this.value.combine(b.value)) //works because there is evidence of a Monoid<A>
                      is None -> ob
                    }
      is None -> this
  }
    
}
```

The above instance declares a `Monoid<Option<A>>` as long as there is a `Monoid<A>` in scope.

```kotlin
Option(1).combine(Option(1)) // Option(2)
Option("a").combine(Option("b")) // does not compile. Found `Monoid<Option<A>>` instance providing `combine` but no `Monoid<String>` instance was in scope
```

We believe the above proposed encoding fits nicely with Kotlin's philosophy of extensions and will reduce the boilerplate compared to other langs that also support typeclasses such as Scala where this is done via implicits.

## Typeclasses over type constructors

We recommend if this proposal is accepted that a lightweight version of higher kinds support is included to unveil the true power of typeclasses through the extensions mechanisms

A syntax that would allow for higher kinds in these definitions may look like this:

```kotlin
interface FunctionK<F<_>, G<_>> {
  fun <A> invoke(fa: F<A>): G<A>
}

object Option2List : FunctionK<Option, List> {
  fun <A> invoke(fa: Option<A>): List<A> =
    fa.fold({ emptyList() }, { listOf(it) })
}
``` 

Here `F<_>` refers to a type that has a hole on it such as `Option`, `List`, etc. 

A use of this declaration in a polymorphic function would look like:

```kotlin
fun <F<_>, A, B> transform(fa: F<A>, f: (A) -> B): F<B> given Functor<F> = F.map(fa, f)

transform(Option(1), { it + 1 }) // Option(2)
transform("", { it + "b" }) // Does not compile: `String` is not type constructor with shape F<_>
transform(listOf(1), { it + 1 }) // does not compile: No `Functor<List>` instance defined in scope.
```

Some of this examples where originally proposed by Roman Elizarov and the Kategory contributors where these features where originally discussed https://github.com/Kotlin/KEEP/pull/87
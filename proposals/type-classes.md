# Type classes

* **Type**: Design proposal
* **Author**: Raul Raja
* **Status**: New
* **Prototype**: -

## Summary

The goal of this proposal is to enable `type classes` and lightweight `Higher Kinded Types` in Kotlin to enable ad-hoc polymorphism and better extension syntax.
Type classes is the most important feature that Kotlin lacks in order to support a broader range of FP idioms.
Kotlin already has an excellent extension mechanism where this proposal fits nicely. As a side effect `Type classes as extensions` also allows for compile time
dependency injection which will improve the current landscape where trivial applications rely on heavy frameworks based on runtime Dependency Injection.
Furthermore introduction of type classes improves usages for `reified` generic functions with a more robust approach that does not require those to be `inline` or `reified`.

## Motivation

* Support Type class evidence compile time verification.
* Support a broader range of Typed FP patterns.
* Enable multiple extension functions groups for type declarations.
* Enable compile time DI through the use of the Type class pattern.
* Enable better compile reified generics without the need for explicit inlining.
* Enable definition of polymorphic functions whose constrains can be verified at compile time in call sites.

## Description

We propose to use the existing `interface` semantics allowing for generic definition of type classes and their instances with the same style interfaces are defined

```kotlin
typeclass Monoid<A> {
    fun A.combine(b: A): A
    val empty: A
}
```

The above declaration can serve as target for implementations for any arbitrary data type.
In the implementation below we provide evidence that there is a `Monoid<Int>` instance that enables `combine` and `empty` on `Int`

```kotlin
package intext

instance object IntMonoid : Monoid<Int> {
    fun Int.combine(b: Int): Int = this + b
    val empty: Int = 0
}
```

```
import intext.IntMonoid

1.combine(2) // 3
Int.empty // 0
```

Because of this constrain where we are stating that there is a `Monoid` constrain for a given type `A` we can also encode polymorphic definitions based on those constrains:

```kotlin
import intext.IntMonoid

fun <A> add(a: A, b: A): A given Monoid<A> = a.combine(b)
add(1, 1) // compiles
add("a", "b") // does not compile: No `String: Monoid` instance defined in scope
```

## Compile Time Dependency Injection

On top of the value this brings to typed FP in Kotlin it also helps in OOP contexts where dependencies can be provided at compile time:

```kotlin
typeclass Context<A> {
  fun A.config(): Config
}
```

```kotlin
package prod

instance object ProdContext: Context<Service> {
  fun Service.config(): Config = ProdConfig
}
```

```kotlin
package test

instance object TestContext: Context<Service> {
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

Type classes allow us to workaround `inline` `reified` generics and their limitations and express those as type classes instead:

```kotlin
typeclass Reified<A> {
    val selfClass: KClass<A>
}
```

Now a function that was doing something like:

```kotlin
inline fun <reified A> foo() { .... A::class ... }
```

can be replaced with:

```kotlin
fun <A> fooTC(): Klass<A> given Reified<A> { .... A.selfClass ... }
```

This allows us to obtain generics info without the need to declare the functions `inline` or `reified` overcoming the current limitations of inline reified functions that can't be invoked unless made concrete from non reified contexts.

Not this does not remove the need to use `inline reified` where one tries to instrospect generic type information at runtime with reflection. This particular case is only relevant for those cases where you know the types you want `Reified` ahead of time and you need to access to their class value.

```kotlin
instance class Foo<A> {
   val someKlazz = foo<A>() //won't compile because class disallow reified type args.
}
```

```kotlin
instance class Foo<A> {
   val someKlazz = fooTC<A>() //works anddoes not requires to be inside an `inline reified` context.
}
```

## Composition and chain of evidences

Type class instances and declarations can encode further constrains in their generic args so they can be composed nicely:

```kotlin
package optionext

instance class OptionMonoid<A> : Monoid<Option<A>> given Monoid<A> {

  val empty: Option<A> = None

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
import optionext.OptionMonoid
import intext.IntMonoid

Option(1).combine(Option(1)) // Option(2)
Option("a").combine(Option("b")) // does not compile. Found `Monoid<Option<A>>` instance providing `combine` but no `Monoid<String>` instance was in scope
```

We believe the above proposed encoding fits nicely with Kotlin's philosophy of extensions and will reduce the boilerplate compared to other langs that also support typeclasses such as Scala where this is done via implicits.

## Typeclasses over type constructors

We recommend if this proposal is accepted that a lightweight version of higher kinds support is included to unveil the true power of typeclasses through the extensions mechanisms

A syntax that would allow for higher kinds in these definitions may look like this:

```kotlin
typeclass FunctionK<F<_>, G<_>> {
  fun <A> invoke(fa: F<A>): G<A>
}

instance object Option2List : FunctionK<Option, List> {
  fun <A> invoke(fa: Option<A>): List<A> =
    fa.fold({ emptyList() }, { listOf(it) })
}
``` 

Here `F<_>` refers to a type constructor meaning a type that has a hole on it such as `Option`, `List`, etc. 

A use of this declaration in a polymorphic function would look like:

```kotlin
fun <F<_>, A, B> transform(fa: F<A>, f: (A) -> B): F<B> given Functor<F> = F.map(fa, f)

transform(Option(1), { it + 1 }) // Option(2)
transform("", { it + "b" }) // Does not compile: `String` is not type constructor with shape F<_>
transform(listOf(1), { it + 1 }) // does not compile: No `Functor<List>` instance defined in scope.
```

## Language Changes

- Add `given` to require instances evidences in both function and interface/class declarations as demonstrated by previous and below examples:
```kotlin
instance class OptionMonoid<A> : Monoid<Option<A>> given Monoid<A> //class position

fun <A> add(a: A, b: A): A given Monoid<A> = a.combine(b) //function position
```

The below alternative approach to `given` using parameters and the special keyword `instance` was also proposed but discarded since `given`
was more inline with other similar usages such as `where` that users are already used to and did not require to name the instances to activate extension syntax.

```kotlin
instance class OptionMonoid<A>(instance MA: Monoid<A>) : Monoid<Option<A>> //class position

fun <A> add(a: A, b: A, instance MA: Monoid<A>): A = a.combine(b) //function position
```

## Compiler Changes

- The type checker will declare the below definition as valid since the `given` clause provides evidence that call sites won't be able to compile calls to this function unless a `Monoid<A>` is in scope.
```kotlin
fun <A> add(a: A, b: A): A given Monoid<A> = a.combine(b) //compiles
```
- The type checker will declare the below definition as invalid since there is no `Monoid<Int>` in scope.
```kotlin
add(1, 2)
```
- The type checker will declare the below definition as valid since there is a `Monoid<Int>` in scope.
```kotlin
import intext.IntMonoid
add(1, 2)
```
- The type checker will declare the below definition as valid since there is a `Monoid<Int>` in scope.
```kotlin
fun addInts(a: Int, b: Int): Int given Monoid<Int> = add(a, b)
```
- The type checker will declare the below definition as valid since there is a `with` block around the concrete `IntMonoid` in scope.
```kotlin
fun addInts(a: Int, b: Int): Int = with(IntMonoid) { add(a, b) }
```

## Compile resolution rules

When the compiler finds a call site invoking a function that has type class instances constrains declared with `given` as in the example below:

Declaration: 
```kotlin
fun <A> add(a: A, b: A): A given Monoid<A> = a.combine(b) 
```
Call site:
```kotlin
instance class AddingInts {
  fun addInts(): Int = add(1, 2)
}
```
The compiler may choose the following order for resolving the evidence that a `Monoid<Int>` exists in scope.

1. Look in the most immediate scope for declarations of `given Monoid<Int>` in this case the function `addInts`

This will compile because the responsibility of providing `Monoid<Int>` is passed unto the callers of `addInts()`:
```kotlin
instance class AddingInts {
  fun addInts(): Int given Monoid<Int> = add(1, 2)
}
```

2. Look in the immediately outher class/interface scope for declarations of `given Monoid<Int>` in this case the class `AddingInts`:
```kotlin
instance class AddingInts given Monoid<Int> {
  fun addInts(): Int = add(1, 2)
}
```
This will compile because the responsibility of providing `Monoid<Int>` is passed unto the callers of `AddingInts()`

3. Look in the import declarations for an explicitly imported instance that satisfies the constrain `Monoid<Int>`:
```kotlin
import intext.IntMonoid
instance class AddingInts {
  fun addInts(): Int = add(1, 2)
}
```
This will compile because the responsibility of providing `Monoid<Int>` is satisfied by `import intext.IntMonoid`

4. Fail to compile if neither outer scopes nor explicit imports fail to provide evidence that there is a constrain satisfied by an instance in scope.
```kotlin
import intext.IntMonoid
instance class AddingInts {
  fun addInts(): Int = add(1, 2)
}
```
Fails to compile lacking evidence that you can invoke `add(1,2)` since `add` is a polymorphic function that requires a `Monoid<Int>` inferred by `1` and `2` being of type `Int`.:



Some of these examples where originally proposed by Roman Elizarov and the Kategory contributors where these features where originally discussed https://github.com/Kotlin/KEEP/pull/87

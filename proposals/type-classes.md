# Type Classes

* **Type**: Design proposal
* **Author**: Raul Raja
* **Contributors**: Francesco Vasco, Claire Neveu
* **Status**: New
* **Prototype**: -

## Summary

The goal of this proposal is to enable `type classes` and lightweight `Higher Kinded Types` in Kotlin to enable ad-hoc polymorphism and better extension syntax.

Type classes are a form of interface that provide a greater degree of polymorphism than classical interfaces. Typeclasses can also improve code-reuse in contrast to classical interfaces if done correctly.

Introduction of type classes improves usages for `reified` generic functions with a more robust approach that does not require those to be `inline` or `reified`.

## Motivation

* Support type class evidence compile time verification.
* Support a broader range of Typed FP patterns.
* Enable multiple extension functions groups for type declarations.
* Enable better compile reified generics without the need for explicit inlining.
* Enable definition of polymorphic functions whose constraints can be verified at compile time in call sites.

## Description

We propose to use the existing `interface` semantics allowing for generic definition of type classes and their instances with the same style interfaces are defined

```kotlin
extension interface Monoid<A> {
    fun A.combine(b: A): A
    val empty: A
}
```

The above declaration can serve as target for implementations for any arbitrary data type.
In the implementation below we provide evidence that there is a `Monoid<Int>` instance that enables `combine` and `empty` on `Int`

```kotlin
package intext

extension object : Monoid<Int> {
    fun Int.combine(b: Int): Int = this + b
    val empty: Int = 0
}
```

Type class implementations can be given a name for Java interop.
```kotlin
package intext

extension object IntMonoid : Monoid<Int> {
    fun Int.combine(b: Int): Int = this + b
    val empty: Int = 0
}
```

```kotlin

1.combine(2) // 3
Monoid<Int>.empty() // 0
```

Because of this constraint where we are stating that there is a `Monoid` constraint for a given type `A` we can also encode polymorphic definitions based on those constraints:

```kotlin
fun <A> add(a: A, b: A, with Monoid<A>): A = a.combine(b)
add(1, 1) // compiles
add("a", "b") // does not compile: No `String: Monoid` instance defined in scope
```

## Overcoming `inline` + `reified` limitations

Type classes allow us to workaround `inline` `reified` generics and their limitations and express those as type classes instead:

```kotlin
extension interface Reified<A> {
    val A.selfClass: KClass<A>
}
```

Now a function that was doing something like:

```kotlin
inline fun <reified A> foo() { .... A::class ... }
```

can be replaced with:

```kotlin
fun <A> fooTC(with Reified<A>): Klass<A> { .... A.selfClass ... }
```

This allows us to obtain generics info without the need to declare the functions `inline` or `reified` overcoming the current limitations of inline reified functions that can't be invoked unless made concrete from non reified contexts.

Not this does not remove the need to use `inline reified` where one tries to instrospect generic type information at runtime with reflection. This particular case is only relevant for those cases where you know the types you want `Reified` ahead of time and you need to access to their class value.

```kotlin
extension class Foo<A> {
   val someKlazz = foo<A>() //won't compile because class disallow reified type args.
}
```

```kotlin
extension class Foo<A> {
   val someKlazz = fooTC<A>() //works and does not requires to be inside an `inline reified` context.
}
```

## Composition and chain of evidences

Type class instances and declarations can encode further constraints in their generic args so they can be composed nicely:

```kotlin
package optionext

extension class OptionMonoid<A>(with Monoid<A>): Monoid<Option<A>> {

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
Option(1).combine(Option(1)) // Option(2)
Option("a").combine(Option("b")) // does not compile. Found `Monoid<Option<A>>` instance providing `combine` but no `Monoid<String>` instance was in scope
```

We believe the above proposed encoding fits nicely with Kotlin's philosophy of extensions and will reduce the boilerplate compared to other langs that also support typeclasses such as Scala where this is done via implicits.

## Typeclasses over type constructors

We recommend if this proposal is accepted that a lightweight version of higher kinds support is included to unveil the true power of typeclasses through the extensions mechanisms

A syntax that would allow for higher kinds in these definitions may look like this:

```kotlin
extension interface FunctionK<F<_>, G<_>> {
  fun <A> invoke(fa: F<A>): G<A>
}

extension object : FunctionK<Option, List> {
  fun <A> invoke(fa: Option<A>): List<A> =
    fa.fold({ emptyList() }, { listOf(it) })
}
``` 

Here `F<_>` refers to a type constructor meaning a type that has a hole on it such as `Option`, `List`, etc. 

A use of this declaration in a polymorphic function would look like:

```kotlin
fun <F<_>, A, B> transform(fa: F<A>, f: (A) -> B, with Functor<F>): F<B> = F.map(fa, f)

transform(Option(1), { it + 1 }) // Option(2)
transform("", { it + "b" }) // Does not compile: `String` is not type constructor with shape F<_>
transform(listOf(1), { it + 1 }) // does not compile: No `Functor<List>` instance defined in scope.
```

## Language Changes

- Add `with` to require instances evidences in both function and class/object declarations as demonstrated by previous and below examples:
```kotlin
extension class OptionMonoid<A>(with M: Monoid<A>) : Monoid<Option<A>> // class position using parameter "M"
extension class OptionMonoid<A>(with Monoid<A>) : Monoid<Option<A>> // class position using anonymous `Monoid` parameter

fun <A> add(a: A, b: A, with M: Monoid<A>): A = a.combine(b) // function position using parameter "M"
fun <A> add(a: A, b: A, with Monoid<A>): A = a.combine(b) // function position using anonymous `Monoid` parameter
```

## Type Class Instance Rules

Classical interfaces only allow the implementation of interfaces to occur when a type is defined. Type classes typically relax this rule and allow implementations outside of the type definition. When relaxinng this rule it is important to preserve the coherency we take for granted with classical interfaces.

For those reasons type class instances must be declared in one of two places:

1. In the same file as the type class definition (interface-side implementation)
2. In the same file as the type being implemented (type-side implementation)

All other instances are orphan instances and are not allowed. See [Appendix A](#Appendix-A) for a modification to this proposal that allows for orphan instances.

Additionally a type class implementation must not conflict with any other already defined type class implementations; for the purposes of checking this we use the normal resolution rules.

### Interface-Side Implementations

This definition site is simple to implement and requires to rules except that the instances occurs in the same package. E.g. the following implementation is allowed
```kotlin
package foo.collections

extension interface Monoid<A> {
   ...
}
```

```kotlin
package foo.collections

extension object : Monoid<String> {
   ...
}
```

### Type-Side Implementations

This definition site poses additional complications when you consider multi-parameter typeclasses.

```kotlin
package foo.collections

extension interface Isomorphism<A, B> {
   ...
}
```

```kotlin
package data.foo

data class Foo(...)
extension class<A> : Isomorphism<Foo, A> {
   ...
}
```

```kotlin
package data.bar

data class Bar(...)
extension class<A> : Isomorphism<A, Bar> {
   ...
}
```

The above instances are each defined alongside their respective type definitions and yet they clearly conflict with each other. We will also run into quandaries once we consider generic types. We can crib some prior art from Rust<sup>1</sup> to help us out here.

To determine whether a typeclass definition is a valid type-side implementation we perform the following check:

1. A "local type" is any type (but not typealias) defined in the current file (e.g. everything defined in `data.bar` if we're evaluating `data.bar`).
2. A generic type parameter is "covered" by a type if it occurs within that type, e.g. `MyType` covers `T` in `MyType<T>` but not `Pair<T, MyType>`.
3. Write out the parameters to the type class in order.
4. The parameters must include a type defined in this file.
5. Any generic type parameters must occur after the first instance of a local type or be covered by a local type.

If a type class implementation meets these rules it is a valid type-side implementation.
 

## Compile Resolution Rules

When the compiler finds a call site invoking a function that has type class instances constraints declared with `with` as in the example below:

Declaration: 
```kotlin
fun <A> add(a: A, b: A, with Monoid<A>): A = a.combine(b)
```
Call site:
```kotlin
fun addInts(): Int = add(1, 2)
```

1. The compiler first looks at the file the interface is defined in. If it finds exactly one implementation it uses that instance.
2. If it fails to find an implementation in the interface's file, it then looks at the files of the implemented types. For each type class parameter check the file it was defined in. If exactly one implementation is found use that instance.
3. If no matching implementation is found in either of these places fail to compile.
4. If more than one matching implementation is found, fail to compile and indicate that there or conflicting instances.

Some of these examples where originally proposed by Roman Elizarov and the Kategory contributors where these features where originally discussed https://github.com/Kotlin/KEEP/pull/87

## Appendix A: Orphan Implementations

Orphan implementations are a subject of controversy. Combining two libraries, one defining a data type, the other defining an interface, is a feature that many programmers have longed for. However, implementing this feature in a way that doesn't break other features of interfaces is difficult and drastically complicates how the compiler works with those interfaces.

Orphan implementations are the reason that type classes have often been described as "anti-modular" as the most common way of dealing with them is through global coherency checks. This is necessary to ensure that two libraries have not defined incompatible implementations of a type class interface.

Relaxing the orphan rules is a backwards-compatible change. If this proposal is accepted without permitting orphans it is useful to consider how they could be added in the future.

Ideally we want to ban orphan implementations in libraries but not in executables; this allows a programmer to manually deal with coherence in their own code but prevents situations where adding a new library breaks code.

### Package-based Approach to Orphans

A simple way to allow orphan implementations is to replace the file-based restrictions with package-based restrictions. Because there are no restrictions on packages it is posible to do the following.

```kotlin
// In some library foo
package foo.collections

extension class Monoid<A> {
   ...
}
```

```kotlin
// In some application that uses the foo library
package foo.collections

extension object : Monoid<Int> {
   ...
}
```

This approach would not forbid orphan implementations in libraries but it would highly discourage them from providing them since it would involve writing code in the package namespace of another library.

### Internal Modifier-based Approach to Orphans

An alternate approach is to require that orphan implementations be marked `internal`. The full rules would be as follows:

1. All orphan implementations must be marked `internal`
2. All code which closes over an internal implementations must be marked internal. Code closes over a type class instance if it contains a static reference to such an implementation.
3. Internal implementations defined in the same module are in scope for the current module.
4. Internal implementations defined in other modules are not valid for type class resolution.

This approach works well but it has a few problems.

1. It forces applications that use orphan implementations to mark all their code as internal, which is a lot of syntactic noise.
2. It complicates the compiler's resolution mechanism since it's not as easy to enumerate definition sites.

The first  problem can actually leads us to a better solution.

### Java 9 Module-based Approach to Orphans

Currently Kotlin does not make use of Java 9 modules but it is easy to see how they could eventually replace Kotlin's `internal` modifier. The rules for this approach would be the same as the `internal`-based approach; code which uses orphans is not allowed to be exported.

## Footnotes

1. [Little Orphan Impls](http://smallcultfollowing.com/babysteps/blog/2015/01/14/little-orphan-impls/)

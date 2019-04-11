# Compile time dependency resolution

* **Type**: Design proposal
* **Author**: Raul Raja
* **Contributors**: Tomás Ruiz López, Jorge Castillo, Francesco Vasco, Claire Neveu
* **Status**: New
* **Prototype**: [initial implementation](https://github.com/arrow-kt/kotlin/pull/6)

## Summary

The goal of this proposal is to enable **compile time dependency resolution** through extension syntax. Overall, we'd want to enable extension contract interfaces to be defined as function or class arguments and enable compiler to automatically resolve and inject those instances that must be provided evidence for in one of a given set of scopes. In case of not having evidence of any of those required interfaces (program constraints), compiler would fail and provide proper error messages.

## Motivation

* Support extension evidence compile-time verification.
* Enable nested extension resolution.
* Enable multiple extension function groups for type declarations.
* Support compile-time verification of a program correctness given behavioral constraints are raised to the interface types.
* Enable definition of polymorphic functions whose constraints can be verified at compile time in call sites.

## Description

We propose to use the existing `interface` semantics, allowing for generic definition of behaviors and their instances in the same style interfaces are defined.

```kotlin
package com.data

interface Repository<A> {
  fun loadAll(): List<A>
  fun loadById(id: Int): A?
}
```

The above declaration can serve as a target for implementations for any arbitrary type passed for `A`.

In the implementation below we provide evidence that there is a `Repository<User>` extensions available in scope, enabling both methods defined for the given behavior to work over the `User` type. As you can see, we're enabling a new keyword here: `extension`.

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User

extension object UserRepository: Repository<User> {
  override fun loadAll(): List<User> {
    return listOf(User(25, "Bob"))
  }

  override fun loadById(id: Int): User? {
    return if (id == 25) {
      User(25, "Bob")
    } else {
      null
    }
  }
}
```

You can also provide evidence of an extension using classes:

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User

extension class UserRepository: Repository<User> {
  override fun loadAll(): List<User> {
    return listOf(User(25, "Bob"))
  }

  override fun loadById(id: Int): User? {
    return if (id == 25) {
      User(25, "Bob")
    } else {
      null
    }
  }
}
```

**Extensions are named** for now, mostly for supporting Java, but we'd be open to iterate that towards allowing definition through properties and anonymous classes.
We got the contract definition (interface) and the way to provide evidence of an extension, we'd just need to connect both things now. Interfaces can be used to define constraints of a function or a class. We the `with` keyword for that.

```kotlin
fun <A> fetchById(id: String, with repository: Repository<A>): A? {
  return loadById(id) // Repository syntax is automatically activated inside the function scope!
}
```

As you can see, we get the constraint syntax automatically active inside the function scope, so we can call it's functions at will. That's because we consider `Repository` a constraint of our program at this point. In other words, the program cannot work without it, it's a requirement.

On the call site:

```kotlin
fetch<User>("1182938") // compiles since we got evidence of a `Repository<User>` in scope.
fetch<Coin>("1239821") // does not compile: No `Repository<Coin>` evidence defined in scope!
```

## Composition and chain of evidences

Interface declarations and extension evidences can encode further constraints on their type parameters so that they can be composed nicely:

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User
import com.domain.Group

extension class GroupRepository<A>(with val repoA: Repository<A>) : Repository<Group<A>> {
  override fun loadAll(): List<Group<A>> {
    return listOf(Group(userRepository.loadAll()))
  }

  override fun loadById(id: Int): Group<A>? {
    return Group(userRepository.loadById(id))
  }
}
```

The above extension provides evidence of a `Repository<Group<A>>` as long as there is a `Repository<A>` in scope. Call site would be like:

```kotlin
fun fetchGroup<A>(with repo: GroupRepository<A>) = repo.loadAll()

fun main() {
  fetchGroup<User>() // Succeeds! There's evidence of Repository<Group<A>> and Repository<User> provided in scope.
  fetchGroup<Coin>() // Fails! There's evidence of Repository<Group<A>> but no evidence of `Repository<Coin>` available.
}
```

We believe the encoding proposed above fits nicely with Kotlin's philosophy of extensions and will reduce the boilerplate compared to other langs that also support compile time dependency resolution such as Scala where this is done via implicits.

## Language changes

* Add `with` to require evidence of extensions in both function and class/object declarations.
* Add `extension` to provide instance evidence for a given interface.

Usage of these language changes are demonstrated by the previous and below examples:

#### Class constraint

```kotlin
extension class GroupRepository<A>(with R: Repository<A>) : Repository<Group<A>> {
  /* ... */
}
```

#### Function constraint

```kotlin
fun <A> fetch(id: String, with R: Repository<A>): A = loadById(id) // function position using parameter "R"
```

#### Extension evidence using an Object

```kotlin
extension object UserRepository: Repository<User> {
  override fun loadAll(): List<User> {
    return listOf(User(25, "Bob"))
  }

  override fun loadById(id: Int): User? {
    return if (id == 25) {
      User(25, "Bob")
    } else {
      null
    }
  }
}
```

#### Extension evidence using a Class

```kotlin
extension class UserRepository: Repository<User> {
  override fun loadAll(): List<User> {
    return listOf(User(25, "Bob"))
  }

  override fun loadById(id: Int): User? {
    return if (id == 25) {
      User(25, "Bob")
    } else {
      null
    }
  }
}
```

## Extension resolution order

Classical interfaces only permit their implementation at the site of a type definition. Compiler extension resolution pattern typically relax this rule and **allow extension evidences be declared outside of the type definition**. When relaxing this rule it is important to preserve the coherency we take for granted with classical interfaces.

For those reasons constraint interfaces must be declared in one of the following places (in strict resolution order):

1. Scope of the caller function.
2. Companion object for the target type (User).
3. Companion object for the constraint interface we're looking for (Repository).
4. Subpackages of the package where the target type (User) to resolve is defined.
5. Subpackages of the package where the constraint interface (Repository) is defined.

All other instances are considered orphan instances and are not allowed. See [Appendix A](#Appendix-A) for a modification to this proposal that allows for orphan instances.

Additionally, a constraint extension must not conflict with any other pre-existing extension for the same constraint interface; for the purposes of checking this we use the normal resolution rules. That's what we refer as compiler "coherence".

### Interface-side implementations

This definition site is simple to implement and requires no rules except that the instances occur in the same package. For example, the following implementation is allowed:

```kotlin
package foo.collections

interface Monoid<A> {
   ...
   companion object {
      extension object IntMonoid : Monoid<Int> { ... }
   }
}
```

```kotlin
package foo.collections.instances

extension object : Monoid<String> {
   ...
}
```

### Type-side implementations

This definition site poses additional complications when you consider multi-parameter type classes.

```kotlin
package foo.collections

interface Isomorphism<A, B> {
   ...
}
```

```kotlin
package data.collections.foo

data class Foo(...)
extension class<A> : Isomorphism<Foo, A> {
   ...
}
```

```kotlin
package data.collections.bar

data class Bar(...)
extension class<A> : Isomorphism<A, Bar> {
   ...
}
```

The above instances are each defined alongside their respective type definitions and yet they clearly conflict with each other. We will also run into quandaries once we consider generic types. We can crib some prior art from Rust<sup>1</sup> to help us out here.

To determine whether a type class definition is a valid type-side implementation we perform the following check:

1. A "local type" is any type (but not type alias) defined in the current file (e.g. everything defined in `data.collections.bar` if we're evaluating `data.collections.bar`).
2. A generic type parameter is "covered" by a type if it occurs within that type (e.g. `MyType` covers `T` in `MyType<T>` but not `Pair<T, MyType>`).
3. Write out the parameters to the type class in order.
4. The parameters must include a type defined in this file.
5. Any generic type parameters must occur after the first instance of a local type or be covered by a local type.

If a type class implementation meets these rules it is a valid type-side implementation.

## Compile resolution rules

When the compiler finds a call site of a function that has type class instance constraints declared with `with`, as in the example below:

Declaration:

```kotlin
fun <A> add(a: A, b: A, with Monoid<A>): A = a.combine(b)
```

Call site:

```kotlin
fun addInts(): Int = add(1, 2)
```

1. The compiler first looks at the function context where the invocation is happening. If a function argument matches the required instance for a type class, it uses that instance; e.g.:

    ```kotlin
    fun <a> duplicate(a : A, with M: Monoid<A>): A = a.combine(a)
    ```

    The invocation `a.combine(a)` requires a `Monoid<A>` and since one is passed as an argument to `duplicate`, it uses that one.

2. In case it fails, it inspects the following places, sequentially, until it is able to find a valid unique instance for the type class:

    * The current package (where the invocation is taking place), as long as the `extension` is `internal`.
    * The companion object of the type parameter(s) in the type class (e.g. in `Monoid<A>`, it looks into `A`'s companion object).
    * The companion object of the type class.
    * The subpackages of the package where the type parameter(s) in the type class is defined.
    * The subpackages of the package where the type class is defined.

3. If no matching implementation is found in either of these places then the code fails to compile.
4. If more than one matching implementation is found, then the code fails to compile and the compiler indicates that there are conflicting instances.

Some of these examples were proposed by Roman Elizarov and the Arrow contributors where these features were originally discussed: https://github.com/Kotlin/KEEP/pull/87

## Appendix A: Orphan implementations

Orphan implementations are a subject of controversy. Combining two libraries - one defining a data type, the other defining an interface - is a feature that many programmers have longed for. However, implementing this feature in a way that doesn't break other features of interfaces is difficult and drastically complicates how the compiler works with those interfaces.

Orphan implementations are the reason that type classes have often been described as "anti-modular", as the most common way of dealing with them is through global coherence checks. This is necessary to ensure that two libraries have not defined incompatible implementations of a type class interface.

Relaxing the orphan rules is a backwards-compatible change. If this proposal is accepted without permitting orphans then it's useful to consider how they could be added in the future.

Ideally we want to ban orphan implementations in libraries but not in executables; this allows a programmer to manually deal with coherence in their own code but prevents situations where adding a new library breaks code.

### Package-based approach to orphans

A simple way to allow orphan implementations is to replace the file-based restrictions with package-based restrictions. Because there are no restrictions on packages, it is possible to do the following.

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

This approach would not forbid orphan implementations in libraries but it would highly discourage libraries from providing them, as this would involve writing code in the package namespace of another library.

### Internal modifier-based approach to orphans

An alternate approach is to require that orphan implementations be marked `internal`. The full rules would be as follows:

1. All orphan implementations must be marked `internal`.
2. All code which closes over an internal implementation must be marked internal. Code closes over a type class instance if it contains a static reference to such an implementation.
3. Internal implementations defined in the same module are in scope for the current module.
4. Internal implementations defined in other modules are not valid for type class resolution.

This approach works well but it has a few problems.

1. It forces applications that use orphan implementations to mark all their code as internal, which is a lot of syntactic noise.
2. It complicates the compiler's resolution mechanism since it's not as easy to enumerate definition sites.

The first problem actually leads us to a better solution.

### Java 9 module-based approach to orphans

Kotlin does not currently make use of Java 9 modules but it is easy to see how they could eventually replace Kotlin's `internal` modifier. The rules for this approach would be the same as the `internal`-based approach; code which uses orphans is not allowed to be exported.

## Footnotes

1. [Little Orphan Impls](http://smallcultfollowing.com/babysteps/blog/2015/01/14/little-orphan-impls/)

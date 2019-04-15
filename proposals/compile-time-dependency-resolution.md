# Compile time dependency resolution

* **Type**: Design proposal
* **Author**: Raul Raja
* **Contributors**: Tomás Ruiz López, Jorge Castillo, Francesco Vasco, Claire Neveu
* **Status**: New
* **Prototype**: [initial implementation](https://github.com/arrow-kt/kotlin/pull/6)

## Summary

The goal of this proposal is to enable **compile time dependency resolution** through extension syntax. Overall, we'd want to enable extension contract interfaces to be defined as function or class constructor arguments and enable compiler to automatically resolve and inject those instances that must be provided evidence for in one of a given set of scopes. In case of not having evidence of any of those required interfaces (program constraints), compiler would fail and provide proper error messages.

This would bring **first-class named extensions families** to Kotlin. Extension families allow us to guarantee a given data type (class, interface, etc.) satisfies behaviors (group of functions) that are decoupled from the type's inheritance hierarchy.

Extension families favor horizontal composition based on compile-time resolution between types and their extensions vs the traditional subtype style composition where users are forced to extend and implement classes and interfaces.

## Motivation

* Support compile-time verification of program dependencies (extensions).
* Enable nested extension resolution.
* Support compile-time verification of a program correctness given behavioral constraints are raised to the interface types.
* Enable definition of polymorphic functions whose constraints can be verified at compile time in call sites.

## Description

We propose to use the existing `interface` semantics, allowing for generic definition of behaviors and their instances in the same style interfaces are defined.

```kotlin
package com.data

interface Repository<A> {
  fun loadAll(): Map<Int, A>
  fun loadById(id: Int): A?
  fun A.save(): Unit
}
```

The above declaration can serve as a target for implementations for any arbitrary type passed for `A`.

In the implementation below we provide evidence that there is a `Repository<User>` extension available in scope, enabling both methods defined for the given behavior to work over the `User` type. As you can see, we're enabling a new keyword here: `extension`.

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User

extension object UserRepository : Repository<User> {

  val storedUsers: MutableMap<Int, User> = mutableMapOf() // e.g. users stored in a DB

  override fun loadAll(): Map<Int, User> {
    return storedUsers
  }

  override fun loadById(id: Int): User? {
    return storedUsers[id]
  }

  override fun User.save() {
    storedUsers[this.id] = this
  }
}
```

You can also provide evidence of an extension using classes:

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User

extension class UserRepository: Repository<User> {

  val storedUsers: MutableMap<Int, User> = mutableMapOf() // e.g. users stored in a DB

  override fun loadAll(): Map<Int, User> {
    return storedUsers
  }

  override fun loadById(id: Int): User? {
    return storedUsers[id]
  }

  override fun User.save() {
    storedUsers[this.id] = this
  }
}
```

In the KEEP as it's coded now, **extensions are named**. That's mostly with the purpose of supporting Java. We'd be fine with this narrower approach we're providing, but we'd be open to iterate that towards allowing definition through properties and anonymous classes, if there's a need for it.

Now we've got the constraint definition (interface) and a way to provide evidence of an extension for it, we'd just need to connect the dots. Interfaces can be used to define constraints of a function or a class constructor. We use the `with` keyword for that.

```kotlin
fun <A> fetchById(id: Int, with repository: Repository<A>): A? {
  return loadById(id) // Repository syntax is automatically activated inside the function scope!
}
```

As you can see, we get the constraint syntax automatically active inside the function scope, so we can call it's functions at will. That's because we consider `Repository` a constraint of our program at this point. In other words, the program cannot work without it, it's a requirement. That means the following two functions would be equivalent:

```kotlin
// Kotlin + KEEP-87
fun <A> fetchById(id: Int, with repository: Repository<A>): A? {
  return loadById(id)
}

// Regular Kotlin
fun <A> fetchById(id: Int, repository: Repository<A>): A? =
  with (repository) {
    return loadById(id)
  }
```

On the call site, we could use it like:

```kotlin
fetchById<User>(11829) // compiles since we got evidence of a `Repository<User>` in scope.
fetchById<Coin>(12398) // does not compile: No `Repository<Coin>` evidence defined in scope!
```

Functions with extension parameters can be passed all values, or extension ones can be omitted and let the compiler resolve the suitable extensions for them. That makes the approach really similar to how default arguments work in Kotlin.

```kotlin
fetchById<User>(11829) // compiles since we got evidence of a `Repository<User>` in scope.
fetchById<User>(11829, UserRepository()) // you can provide it manually.
```

When `with` is used in class constructors, it is important to **add val to extension class fields** to make sure they are accessible in the scope of the class. Here, the `with` keyword adds the value to the scope of every method in the class. To showcase that, let's say we have a `Validator<A>`, like: 

```kotlin
interface Validator<A> {
  fun A.isValid(): Boolean
}
```

In this scenario, the following classes would be equivalent:

```kotlin
data class Group<A>(val values: List<A>)

// Kotlin + KEEP-87
extension class ValidatedRepository<A>(with val V: Validator<A>) : Repository<A> {

    val storedUsers: MutableMap<Int, A> = mutableMapOf() // e.g. users stored in a DB

    override fun loadAll(): Map<Int, A> {
        return storedUsers.filter { it.value.isValid() }
    }

    override fun loadById(id: Int): A? {
        return storedUsers[id]?.let { if (it.isValid()) it else null }
    }

    override fun A.save() {
        storedUsers[generateKey(this)] = this
    }
}

// Regular Kotlin
class ValidatedRepository<A>(val V: Validator<A>) : Repository<A> {

    val storedUsers: MutableMap<Int, A> = mutableMapOf() // e.g. users stored in a DB

    override fun loadAll(): Map<Int, A> {
        with (V) {
            return storedUsers.filter { it.value.isValid() }
        }
    }

    override fun loadById(id: Int): A? {
        with (V) {
            return storedUsers[id]?.let { if (it.isValid()) it else null }
        }
    }

    override fun A.save() {
        storedUsers[generateKey(this)] = this
    }
}
```

As you can see on the first example, `A.isValid()` becomes automatically available inside the methods scope. The equivalence for that without the KEEP-87 would be to manually use `with (V)` inside each one of them, as you can see in the second example.

## Composition and chain of evidences

Constraint interface declarations and extension evidences can encode further constraints on their type parameters so that they can be composed nicely:

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User
import com.domain.Group

extension class GroupRepository<A>(with val repoA: Repository<A>) : Repository<Group<A>> {
  override fun loadAll(): Map<Int, Group<A>> {
    return repoA.loadAll().mapValues { Group(it.value) }
  }

  override fun loadById(id: Int): Group<A>? {
    return repoA.loadById(id)?.let { Group(it) }
  }

  override fun Group<A>.save() {
    this.items.map { repoA.run { it.save() } }
  }
}
```

The above extension provides evidence of a `Repository<Group<A>>` as long as there is a `Repository<A>` in scope. Call site would be like:

```kotlin
fun <A> fetchGroup(with repo: GroupRepository<A>) = loadAll()

fun main() {
  fetchGroup<User>() // Succeeds! There's evidence of Repository<Group<A>> and Repository<User> provided in scope.
  fetchGroup<Coin>() // Fails! There's evidence of Repository<Group<A>> but no evidence of `Repository<Coin>` available.
}
```

We believe the encoding proposed above fits nicely with Kotlin's philosophy of extensions and will reduce the boilerplate compared to other langs that also support compile time dependency resolution.

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
extension object UserRepository : Repository<User> {

  val storedUsers: MutableMap<Int, User> = mutableMapOf() // e.g. users stored in a DB

  override fun loadAll(): Map<Int, User> {
    return storedUsers
  }

  override fun loadById(id: Int): User? {
    return storedUsers[id]
  }

  override fun User.save() {
    storedUsers[this.id] = this
  }
}
```

#### Extension evidence using a Class

```kotlin
extension class UserRepository: Repository<User> {

  val storedUsers: MutableMap<Int, User> = mutableMapOf() // e.g. users stored in a DB

  override fun loadAll(): Map<Int, User> {
    return storedUsers
  }

  override fun loadById(id: Int): User? {
    return storedUsers[id]
  }

  override fun User.save() {
    storedUsers[this.id] = this
  }
}
```

## Extension resolution order

Classical interfaces only permit their implementation at the site of a type definition. Compiler extension resolution pattern typically relax this rule and **allow extension evidences be declared outside of the type definition**. When relaxing this rule it is important to preserve the coherency we take for granted with classical interfaces.

For those reasons constraint interfaces must be provided in one of the following scopes (in strict resolution order):

1. Arguments of the caller function.
2. Companion object for the target type (User).
3. Companion object for the constraint interface we're looking for (Repository).
4. Subpackages of the package where the target type (User) to resolve is defined.
5. Subpackages of the package where the constraint interface (Repository) is defined.

All other instances are considered orphan instances and are not allowed. See [Appendix A](#Appendix-A) for a modification to this proposal that allows for orphan instances.

Additionally, a constraint extension must not conflict with any other pre-existing extension for the same constraint interface; for the purposes of checking this we use the normal resolution rules. That's what we refer as compiler "coherence".

#### 1. Arguments of the caller function

It looks into the caller function argument list for an evidence of the required extension. Here, `bothValid()` gets a `Validator<A>` passed in so whenever it needs to resolve it for the inner calls to `validate()`, it'll be able to retrieve it from its own argument list.
```kotlin
fun <A> validate(a: A, with validator: Validator<A>): Boolean = a.isValid()

fun <A> bothValid(x: A, y: A, with validator: Validator<A>): Boolean = validate(x) && validate(y)
```

#### 2. Companion object for the target type

In case there's no evidence at the caller function level, we'll look into the companion of the target type. Let's say we have an extension of `Validator<User>`:
```kotlin
package com.domain

data class User(val id: Int, val name: String) {
  companion object {
    extension class UserValidator(): Validator<User> {
      override fun User.isValid(): Boolean {
        return id > 0 && name.length > 0
      }
    }
  }
}
```
That'll be enough for resolving the extension.

#### 3. Companion object for the constraint interface we're looking for

In case there's neither evidence in the companion of the target type, we'll look in the companion of the constraint interface:

```kotlin
interface Validator<A> {
  fun A.isValid(): Boolean

  companion object {
    extension class GroupValidator<A>(with val userValidator: Validator<User>) : Validator<Group> {
      override fun Group.isValid(): Boolean {
        for (x in users) {
          if (!x.isValid()) return false
        }
        return true
      }
    }
  }
}
```

#### 4. Subpackages of the package where the target type is defined

The next step would be to look into the subpackages of the package where the target type (`User`) is declared.

```kotlin
package com.domain.repository

import com.domain.User

extension object UserRepository : Repository<User> {

  val storedUsers: MutableMap<Int, User> = mutableMapOf() // e.g. users stored in a DB

  override fun loadAll(): Map<Int, User> {
    return storedUsers
  }

  override fun loadById(id: Int): User? {
    return storedUsers[id]
  }

  override fun User.save() {
    storedUsers[this.id] = this
  }
}
```

Here we got a `Repository<User>` defined in a subpackage of `com.domain`, where the `User` type is defined.

#### 5. Subpackages of the package where the constraint interface is defined

Last place to look at would be subpackages of the package where the constraint interface is defined.

```kotlin
package com.data.instances

import com.data.Repository
import com.domain.User

extension object UserRepository : Repository<User> {

  val storedUsers: MutableMap<Int, User> = mutableMapOf() // e.g. users stored in a DB

  override fun loadAll(): Map<Int, User> {
    return storedUsers
  }

  override fun loadById(id: Int): User? {
    return storedUsers[id]
  }

  override fun User.save() {
    storedUsers[this.id] = this
  }
}
```

Here, we are resolving it from `com.data.instances`, which a subpackage of `com.data`, where our constraint `Repository` is defined.

## Error reporting

We've got a `CallChecker` in place to report inlined errors using the context trace. That allows us to report as many errors as possible in a single compiler pass. Also provide them in two different formats:

#### Inline errors while coding (using inspections and red underline)

Whenever you're coding the checker is running and proper unresolvable extension errors can be reported within IDEA inspections.

![Idea Inspections](https://user-images.githubusercontent.com/6547526/56020688-fea6a880-5d07-11e9-906a-9d085565eee2.png)

#### Errors once you hit the "compile" button:

Once you hit the "compile" button or run any compile command you'll also get those errors reported.

![Idea Inspections](https://user-images.githubusercontent.com/6547526/56020690-00706c00-5d08-11e9-8cbd-ba4b852b9105.png)

## How to try KEEP-87?

We've got the Keep 87 deployed to our own Idea plugin repository over Amazon s3. To use it:

- Download the latest version of IntelliJ IDEA 2018.2.4 from JetBrains
- Go to `preferences` -> `plugins` section.
- Click on "Manage Plugin Repositories".
![InstallKeepFromRepository1](https://user-images.githubusercontent.com/6547526/55884351-38609d80-5ba8-11e9-8855-3c570ee8a1af.png)
- Add our Amazon s3 plugin repository as in the image.
![InstallKeepFromRepository2](https://user-images.githubusercontent.com/6547526/55884427-562e0280-5ba8-11e9-98e8-8811e8e3e8b0.png)
- Now browse for "keep87" plugin.
![InstallKeepFromRepository3](https://user-images.githubusercontent.com/6547526/55884468-67770f00-5ba8-11e9-92d6-e9cc8cc3f572.png)
- Install it.
![InstallKeepFromRepository4](https://user-images.githubusercontent.com/6547526/55884479-6b0a9600-5ba8-11e9-8a19-0eec53187fc5.png)
- Download and run [The Keep87Sample project](https://github.com/47deg/KEEP/files/3079552/Keep87Sample.zip) on that IntellIJ instance.

## How to try KEEP-87? (Alternative approach)

- Clone [Our Kotlin fork](https://github.com/arrow-kt/kotlin) and checkout the **keep-87** branch.
- Follow the instructions on the [README](https://github.com/arrow-kt/kotlin/blob/master/ReadMe.md#build-environment-requirements) configure the necessary JVMs.
- Follow the instructions on the [README](https://github.com/arrow-kt/kotlin/blob/master/ReadMe.md#-working-with-the-project-in-intellij-idea) to open the project in IntelliJ IDEA.
- Once you have everything working, you can run a new instance of IntelliJ IDEA with the new modifications to the language by executing `./gradlew runIde`. There is also a pre-configured run configuration titled **IDEA** that does this.
- It will open a new instance of the IDE where you can create a new project and experiment with the new features of the language. You can also download [The Keep87Sample project](https://github.com/47deg/KEEP/files/3079552/Keep87Sample.zip)
) with some sample code that you can try out.


## What's still to be done?

### Instance resolution based on inheritance

Some scenarios are not covered yet given some knowledge we are lacking about how subtyping resolution rules are coded in Kotlin compiler. The different scenarios would be required for a fully working compile time extension resolution feature, and [are described in detail here](https://github.com/arrow-kt/kotlin/issues/15).

### Using extensions in inlined lambdas

Inlined functions get into trouble when it comes to capture resolved extensions.[The problem is described here](https://github.com/arrow-kt/kotlin/issues/14).

### Function and property extension providers

Ideally we'd enable users to provide extensions also using `val` and `fun`. They'd look similar to:

```kotlin
// Simple fun extension provisioning
extension fun userRepository(): Repository<User> = object : Repository<User>() {
	/* ... */
}

// Chained fun extension provisioning (would require both to resolve).
extension fun userValidator(): Validator<User> = UserValidator()
extension fun userRepository(with validator: Validator<User>) : Repository<User> = UserRepository(validator)

// Simple extension provisioning
extension val userRepository: Repository<User> = UserRepository()

// Chained extension provisioning (would require both to resolve).
extension val userValidator: Validator<User> = UserValidator()
extension val userRepository: Repository<User> = UserRepository(userValidator)
```

In chained extension provisioning, all chained extensions would be required from the caller scope, but not necessarily all provided in the same resolution scope. E.g: `Repository<User>` could be provided in a different resolution scope than `Validator<User>`, and the program would still compile successfully as long as both are available.

### Type-side implementations

We have additional complications when you consider multi-parameter constraint interfaces.

```kotlin
package foo.repo

// I stands for the index type, A for the stored type.
interface Repository<I, A> {
   ...
}
```

```kotlin
package data.foo

data class Id(...)
extension class RepoIndexedById<A> : Repository<Id, A> {
   ...
}
```

```kotlin
package data.foo.user

data class User(...)
extension class UserRepository<I> : Repository<I, User> {
   ...
}
```

The above instances are each defined alongside their respective type definitions and yet they clearly conflict with each other. We will also run into quandaries once we consider generic types. We can crib some prior art from Rust<sup>1</sup> to help us out here.

To determine whether an extension definition is a valid type-side implementation we'd need to perform the following check:

1. A "local type" is any type (but not type alias) defined in the current file (e.g. everything defined in `data.foo.user` if we're evaluating `data.foo.user`).
2. A generic type parameter is "covered" by a type if it occurs within that type (e.g. `MyType` covers `T` in `MyType<T>` but not `Pair<T, MyType>`).
3. Write out the parameters to the constraint interface in order.
4. The parameters must include a type defined in this file.
5. Any generic type parameters must occur after the first instance of a local type or be covered by a local type.

**If an extension meets these rules it is a valid type-side implementation.**

## Appendix A: Orphan implementations

Orphan implementations are a subject of controversy. Combining two libraries - one defining a target type (`User`), the other defining an interface (`Repository`) - is a feature that many programmers have longed for. However, implementing this feature in a way that doesn't break other features of interfaces is difficult and drastically complicates how the compiler works with those interfaces.

Orphan implementations are the reason that other implementations of this approach have often been described as "anti-modular", as the most common way of dealing with them is through global coherence checks. This is necessary to ensure that two libraries have not defined incompatible extensions of a given constraint interface.

Relaxing the orphan rules is a backwards-compatible change. If this proposal is accepted without permitting orphans then it's useful to consider how they could be added in the future.

Ideally we want to ban orphan implementations in libraries but not in executables; this allows a programmer to manually deal with coherence in their own code but prevents situations where adding a new library breaks code.

### Package-based approach to orphans

A simple way to allow orphan extenions is to replace the file-based restrictions with package-based restrictions. Because there are no restrictions on packages, it is possible to do the following.

```kotlin
// In some library foo
package foo.collections

extension class Repository<A> {
   ...
}
```

```kotlin
// In some application that uses the foo library
package foo.collections

extension object : Repository<Int> {
   ...
}
```

This approach would not forbid orphan extensions in libraries but it would highly discourage libraries from providing them, as this would involve writing code in the package namespace of another library.

### Internal modifier-based approach to orphans

An alternate approach is to require that orphan extensions be marked `internal`. The full rules would be as follows:

1. All orphan extensions must be marked `internal`.
2. All code which closes over an internal extension must be marked internal. Code closes over a constraint interface extension if it contains a static reference to such an extension.
3. Internal extensions defined in the same module are in scope for the current module.
4. Internal extensions defined in other modules are not valid for constraint interface resolution.

This approach works well but it has a few problems.

1. It forces applications that use orphan extensions to mark all their code as internal, which is a lot of syntactic noise.
2. It complicates the compiler's resolution mechanism since it's not as easy to enumerate definition sites.

The first problem actually leads us to a better solution.

### Java 9 module-based approach to orphans

Kotlin does not currently make use of Java 9 modules but it is easy to see how they could eventually replace Kotlin's `internal` modifier. The rules for this approach would be the same as the `internal`-based approach; code which uses orphans is not allowed to be exported.

## Footnotes

1. [Little Orphan Impls](http://smallcultfollowing.com/babysteps/blog/2015/01/14/little-orphan-impls/)

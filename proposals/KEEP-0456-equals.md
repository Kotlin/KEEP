# More specific `equals` operator

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Dmitry Khalanskiy, Pavel Kunyavskiy, Nikolay Lunyak
* **Discussion**: [KEEP-476](https://github.com/Kotlin/KEEP/discussions/476)
* **Status**: In progress
* **Related YouTrack issues**: [KT-83683](https://youtrack.jetbrains.com/issue/KT-83683)

## Abstract

We propose a way to override the `equals` function from `Any` with a more
specific type, which in turn unlock additional use site diagnostics.
This proposal respects the current compilation strategy for `equals`.

### TL;DR

* You may define a single `equals` operator per classifier with a more
  restricted type, although still in the same type hierarchy.
* Compiler-generated `equals` become operators with a restricted type.
* The type of the right-hand side of an (un)equality expression is checked
  for compatibility with this more restricted type.
* The operator is compiled as an `equals(other: Any?)` method, with additional
  checks (and contracts).

## Table of contents

* [Abstract](#abstract)
  * [TL;DR](#tldr)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Equality bound](#equality-bound)
  * [Other design decisions](#other-design-decisions)
* [Proposal](#proposal)
  * [Declaration site](#declaration-site)
  * [Code generation](#code-generation)
  * [Multiplatform](#multiplatform)
  * [Use site warnings](#use-site-warnings)
  * [Standard library](#standard-library)
  * [IDE support](#ide-support)
* [Alternative models](#alternative-models)
  * [`Equatable<T>` interface](#equatablet-interface)
  * [Type class](#type-class)

## Motivation

The goal is to introduce additional type safety on comparisons.
Currently Kotlin inherits the `equals` function taking `Any?` as an argument
from its JVM background. In many cases it is desirable to restrict such
equalities, because we know upfront that a certain type is only equal to others
of the same (or related) type.

Having said so, we need to acknowledge that for some types in Kotlin a 
content-based equality makes the most sense. For example, it should be possible 
to still compare two lists for their contents using `==`, even though they have
different underlying representations.

Our goal is a system that **integrates** both notions: as much type safety as
desired, but still allowing equality based on contents.
In fact, there are two different elements for this feature:
* **Declaration site**: being able to define `equals` as taking only the same type.
* **Use site**: identify more potentially wrong cases at compile time.

### Equality bound

First of all, we need to understand what is the correct definition for a notion
of _strict equality_, given our constraints. The following is a first attempt at
that definition,

```
e1.equals(e2)  ==>  e2 is type(e1)
```

However, this is too strong when we consider type arguments. Imagine the following class:

```kotlin
class A<T>(val value: T) {
  fun equals(other: Any?) = other is A && this.value == other.value
}
```

We would like for this equality to be considered strict. On the other hand, 
we'd still want to allow comparisons between `A<MutableList<Int>>` and 
`A<PersistentList<Int>>`, since both kinds of lists may be compared with each
other. However, it is not the case that any of those values is a subtype of the 
other. For that reason, we define **strict equality** in a laxer way, in which 
type arguments are not involved (this definition does not take inheritance into 
account yet).

```
type(e1) is A && e1.equals(e2)  ==>  e2 is A
// conversely
type(e1) is A && e2 !is A       ==>  !e1.equals(e2)
```

**Inheritance.** We have two options for the relation between inheritance and 
strict equality. The first one is to mandate every inheritor of a type defining 
strict equality to also define strict equality. This makes sense for sealed
hierarchies with disjoint types. In this category we find `Result`-like types,

```kotlin
sealed interface State {
  data object Loading : State
  data class Ok(val info: List<String>) : State
}
```

On the other hand, we have hierarchies in which equality is strict, but only at 
the **level of a parent class**. This is the case of `List` in the standard
library: equality is possible between `MutableList` and `PersistentList`, but
not between a `MutableList` and a `MutableSet`.

**Summary.** In summary, the key points of our design are:

- Type arguments (generics) play no role in the definition of strict equality.
- We want the definition to be flexible to different hierarchies, so the
  developer can specify up to which point the strict equality guarantee is valid.

Our solution is to introduce a notion of **equality bound**, that declares from
which supertype the equality is defined. For example, for both `MutableList` and
`PersistentList` such equality bound is `List`. We get then to the final
definition of **strict equality**:

```
type(e1) is A && e1.equals(e2)  ==>  e2 is equality-bound(A)
```

### Other design decisions

**No multiversal equality.** One important design choice in this proposal is to
restrict overrides of equals to the type hierarchy in which the type is already
in. Other languages, like 
[Scala 3](https://docs.scala-lang.org/scala3/reference/contextual/multiversal-equality.html), 
do not pose any restrictions.
However, we think this does not fit Kotlin for several reasons:

- The potential for mistakes is much bigger. In particular, with multiversal
  equality it's much easier to break important invariants of equality like 
  symmetry or transitivity.
- The current specification of Kotlin requires `equals` to eventually resolve to
  an overload with `Any?`. We want to keep this behavior, but it's not possible 
  with multiversal equality, since it requires resolving the equality operator 
  in its entirety.

**Co-existence with other `equals`.**
There's a non-trivial amount of code that defines `equals` on a more limited
type ([GitHub Search](https://github.com/search?q=lang%3AKotlin+%2Ffun+equals%5C%28%5B%5E%3A%5D%2B%5Cs*%3A%5Cs*%5B%5EA%5D%5Cw*%5C%29%2F&type=code)
throws around 1.4K results). 

Bear in mind that those `equals` would **not** be picked during resolution
of `==`. In fact, `e1 == e2` is not always equivalent to `e1.equals(e2)`:
the former always picks the information from the `equals`
operator, whereas the latter undergoes overload resolution as usual.

```kotlin
data class WrongEquals(val x: Int) {
  fun equals(e: WrongEquals): Boolean {
    println("Hello")
    return true
  }
}

fun main() {
  val w1 = WrongEquals(1)
  val w2 = WrongEquals(2)
  println(w1 == w2)
  println(w1.equals(w2))
}

// prints
// > false
// > Hello
// > true
```

Our foremost goal is to remain source compatible: the same overload should
be chosen before and after this proposal if no changes are made to the code.
However, we also want a good migration story for those cases in which
`equals` was defined over a more restrictive type, and you want to make
that the only possible one for `==`.

## Proposal

**Conceptual model.** One way to understand the propose design is as the
`equals` operator actually corresponding to declaring an override for the
`equals(other: Any?)` function, with additional checks and contracts.

### Declaration site

**Equals operator.** A classifier may define a **single** `equals` member
operator – we say that such a classifier defines a strict equality.
This operator:

- Must have no type arguments,
- Must have no extension receiver nor context parameters,
- Must return a Boolean value,
- Must not capture any type arguments from outer scopes,
  - In practice, that means every generic type must be star-projected,
  - This also sidesteps any problem requiring `@UnsafeVariance`,
- Must have a single parameter whose type must satisfy:
  - Being equal or a superclassifier of the classifier defining it,
  - Being equal or a subclassifier of the declared equality bound of every parent
    classifier,
  - Not having a default value,
- Must be public,
- Must not have the `override` modifier (even if it always actually overrides),
- Must not be marked with any other annotation or modifier, other than those
  described above.

In a grammar-esque definition, this `equals` member must look as follows:

```
[public] [abstract | open | final]
operator fun equals(other: EqualityBound): Boolean
```

The type of the single parameter of the `equals` operator is called the
**declared equality bound** of the enclosing classifier.

```kotlin
class Name {
  operator fun equals(other: Name) = first == other.first && last == other.last
}
```

**Compiler-generated equals.** All the `equals` written by the compiler are
considered operators from now on. The declared equality bound is the
star-projected version of the declaring class.

- In particular, that means that every data, enum, and value class, and every
  object that does not override `equals` should be considered as defining a 
  strict equality.

**Overriding.** An `equals` operator always overrides the following:
- Any `equals` operator defined in a parent class, regardless of the 
  type of its argument,
- The `equals` overload defined on `Any`, that is, the overload with
  `Any?` as the argument type (which also corresponds to the overload
  coming from `Object` in the JVM).

We could see this as creating two overriding hierarchies: one for `operator
equals` (bound by its own rules) and other for the rest of `equals` overloads
(bound by the usual overriding rules). The `equals` from `Any` is part of
both hierarchies.

**Interfaces**. The JVM forbids overriding members of `Object` (Kotlin's `Any`)
by default methods in interfaces. As a result, in the JVM the `equals` operator
can only be defined as _abstract_ (since this doesn't affect compilation, as
described in the _Code generation_ section).

Even though we could keep this restriction to the JVM, we propose to apply it
for any piece of Kotlin code. This ensures that code can easily be re-targeted
to other platforms.

**Examples.** The restrictions above allow defining both `Result`-type
hierarchies and `List`-type hierarchies, depending on whether the type you
choose for your operator.

```kotlin
sealed interface Either<out L, out R> {
  abstract operator fun equals(other: Either<*, *>)

  data class Right<out R>(val value: R) : Either<Nothing, R> {
    // automatically generated strict equality
    // operator fun equals(other: Right<*>) = this.value == other.value
  }
}

interface List<out L> {
  abstract operator fun equals(other: List<*>)
}


class ArrayList<L> : List<L> {
  operator fun equals(other: List<*>) = ... // uses supertype for equals
}
```

**Inline value classes.** Value classes marked with the `@JvmInline` annotation,
or any other platform-specific annotation that erases the "box" during compile
time, may not define their own `equals` operator nor inherit one from
superclasses. Due to the erasure, the runtime cannot guarantee that the correct 
implementation is called otherwise.

Note that these restrictions are already in place, since the `equals` member 
is reserved for value classes. Lifting this restriction is out of the scope
of this proposal, but this proposal does **not** block such move.
If this restriction was lifted,
(for example, [KT-24874](https://youtrack.jetbrains.com/issue/KT-24874)),
then it should also work for this new `equals` operator.

**Inherited equals**. If there's no explicit `equals` operator, and the
compiler doesn't generate one for us, then the constraints over the `equals`
operator must be checked on the inherited one.

Note that in some cases the compiler cannot create a single inherited version,
and the user must define the `equals` operator themself. This may happen, for
example, when implementing two unrelated interfaces.

```kotlin
interface One {
  abstract operator fun equals(other: One)
}

interface Two {
  abstract operator fun equals(other: Two)
}

class Three : One, Two {
  // this operator is required for compilation
  operator fun equals(other: Three) { ... }
}
```

This imposes a small restriction, because `Three` cannot define an equality to
values which are either `One` or `Two`, it must define strict equality with
`Three` as bound. However, we think that this scenario is extremely uncommon,
and in most cases you really want to define equality only over `Three`.

> [!NOTE]
> See the _Undeclared equality bound_ section for additional information
> about how importing and inheriting from non-Kotlin types work.

**Inner classes.** We remark that inner classes implicitly capture type
arguments from the enclosing class. As a result, it may be required to state
the star-projected version of such enclosing class.

```kotlin
class A<T> {
  inner class B<R> {
    // wrong! implicitly captures <T>
    // operator fun equals(other: B<*>) { ... }

    // use star-projected enclosing class
    operator fun equals(other: A<*>.B<*>) { ... }
  }
}
```

**Other `equals` members.** It is allowed to declare other members (or
extensions) named `equals` and not marked as an operator. These members
may not be defined as taking a single parameter with a type less specific
than the declared equality bound (note than if they take zero or more
than one parameter, there's no restriction). Since before this proposal the 
declared equality bound is always `Any?`,
and there's no type less specific than `Any?`, all code remains compatible.

This restriction alleviates potential surprises. Consider the following
two classes:

```kotlin
class A

class B {
  operator fun equals(other: B) { ... } // declared equality bound
  fun equals(other: A) { ... }
}
```

If we now write an expression `e1 == e2` with `e1 : B`:
- If `e2 : B`, then `e1 == e2` is equivalent to `e1.equals(e2)`;
- If `e2 : A`, then `e1 == e2` gives you a diagnostic (as described in the
  _Use site warnings_ section).
  This is consistent with the current resolution behavior, 
  and helps eliminate a potential footgun in the language. 
  The developer may still write `e1.equals(e2)`,
  and in that case the compiler picks the right overload.

### Code generation

**Single or multiple equals?** One important design choice is whether the code 
generated for this new `equals` operator keeps the same declared signature, or 
turns into a version with `Any?`. In our design we go with the second route, 
since it has several advantages:

- Better integration with existing code – both in multiplatform code that is
  actualized to Java classes, and in terms of binary interface of existing
  classes,
- Generates the least amount of code – in the first case we would need to 
  override all `equals` operators of the parent classes.

**Strategy.** The `equals` operator turns into two elements in the code:

1. An override of the `equals(other: Any?)` operator whose body is the same as
   the `equals` operator in the code, but with a **preliminary check** for the type 
   being the one declared in the operator, and a **contract** describing this
   check.
   - This override should not be present when the `equals` operator is abstract.
2. Some metadata describing the declared equality bound.

The example in the previous section leads to the following generated code 
(described using Java syntax). The metadata is shown as annotations, but does 
not preclude other ways to implement it.

```java
@StrictEquality(Either::class)
interface Either<L, R> {
  // no abstract equals

  @StrictEquality(Right::class)
  class Right<R> implements Either<Nothing, R> {
    @Contract("returns(true) implies (obj is Right)")
    @Override boolean equals(obj: Object) {
      if (!(obj instanceof Right)) return false;
      final var other = (Right)obj;
      return (this.value.equals(other.value));
    }
  }
}

@StrictEquality(List::class)
interface List<out L> {
  // no abstract equals
}

@StrictEquality(List::class)
class ArrayList<L> : List<L> {
  @Contract("returns(true) implies (obj is List)")
  @Override boolean equals(obj: Object) {
    if (!(obj instanceof List)) return false;
    // actual implementation
  }
}
```

### Multiplatform

The interaction with different underlying platforms is an important component
of Kotlin. There are two different scenarios to consider: implicit facades
from directly importing classes (mostly from JVM), and explicit `expect`/
`actual` matching.

**Undeclared equality bound.** If metadata about the declared equality bound
is missing for a type, then the compiler should assume that the declared
equality bound is the intersection of the equality bounds of all its parents.
This requires inspecting the whole hierarchy once per imported type.

Since those classes are not bound to the rules about the `equals` operator,
there's a possibility for that intersection to be empty. The compiler may
choose to report such inconsistency during import, or wait until use site.
In the second case, the rules described below would lead to a diagnostic.

**Operator matching.**
For many operators the Kotlin compiler tries to turn a matching overload of
a function with the right name into an operator. This should not happen with
the `equals` operator.

```kotlin
// Some.java
public interface Some {
  boolean equals(A other) { return false; }
}

// Else.kt
abstract class A

class B : A(), Some {
  // the declared equality bound is still 'Any'
  // the 'equals' in 'Some' is not taken as an operator
}
```

The consequence is that all declared equality bound must stem from a Kotlin
classifier.

**Multiplatform.** Whenever we want to `actual`ize an `expect` class we first
need to check whether the platform class or any of its parents declares
an equality bound.

If there is an equality bound, then it should **coincide** with the equality
bound declared by the `actual` class.

If no equality bound is found in the whole hierarchy, then no additional
checks are done.
The `equals` overload taking `Any?` as an argument becomes the implementation
of the `equals` operator.
In this case the strict equality invariant is not checked, 
but becomes an **implicit contract** that the `actual class` should abide by.

> [!WARNING]
> If you break the contact in your `actual` class, smart cast may fail at runtime.
>
> ```kotlin
> // common
> expect class A {
>   operator fun equals(other: A)
>   fun foo() 
> }
>
> fun test(x: A, y: Any?) {
>   if (x == y) {
>     y.foo()  // ok, smart cast
>   }
> }
>
> // platform
> actual class A {
>   fun equals(other: Any?) = true  // breaks contract
> }
>
> fun main() {
>   test(A(), "1234")  // ClassCastException at runtime
> }
> ```

**No breakage on JVM.** In particular, this means we can introduce a more 
specific `equals` operator in Kotlin without breaking usages in the JVM platform:

- The binary interface does not change, since we still generate the 
  `equals(obj: Object)` overload,
- We can keep the same `actual` declarations, and they will pick up the 
  underlying `equals` from JVM's `Object` as the implementation.
- For those classes with built-in equality – like records or future value 
  classes – the platform can still do their optimizations.

### Use site warnings

Implementation-wise, these warnings are an extension of the 
[current diagnostics](https://youtrack.jetbrains.com/issue/KT-57779).

**Nullability.** Note that strictly nullable types can never define a strict 
equality, since any two nullable types always share `null` as value. However, 
if we are sure that the other value is not `null`, we can still report the 
same diagnostics.

- Implementations are free to also report cases in which both sides are nullable,
  but would trigger an error if this was not the case. This discourages people 
  from writing code that replies on implicit `null`, in favor of explicit `null`
   checking.

**Equality bound.** For each type we compute an upper bound for the types it can
be compared against, which we call the _equality bound_. We write `eb(A)` for 
the equality bound of `A`.

- For classifiers, the upper bound is the declared equality bound.
  - In particular, data, enum, and value classes, and objects with 
    compiler-generated `equals` take the star-projected version of the
    classifier itself.
  - For the case of undeclared equality bounds, check the _Multiplarform_
    section above.
  - If there's no definition of the `equals` operator, `Any` Is the declared 
    equality bound.
- If the type is nullable `T?`, we take the nullable version of the equality 
  bound of `T`.
- For intersection types, we take the intersection of the equality bounds.
- For flexible types, we take the equality bound of the upper bound.
- For captured types and type parameters, we take the intersection of the
  equality bounds of their upper bounds.
- Otherwise, we take `Any?` as the equality bound.

**Equality checks.** For every expression of the form `e1 == e2`, or `e1 != e2`,
we check the compatibility of the equality bound of the type of `e1` and the 
type of `e2`, as defined by the 
[RULES1 definition](https://youtrack.jetbrains.com/issue/KT-57779#rules1).

If the previous "problematic equals" diagnostic is not triggered, then an
additional smart cast is introduced: `e2` is now known to be instance of
the equality bound of the type of `e1`.

**Contracts**. Effectively, the checks work as if the following
contracts were present (where `A` represents the type of `e1`).

```kotlin
// For e1 == e2
returns(true) implies (e2 is eb(A))
(e2 !is eb(A)) implies returns(false)

// For e1 != e2
returns(false) implies (e2 is eb(A))
(e2 !is eb(A)) implies returns(true)
```

Note that the second contract is not currently expressible in Kotlin
(since `returns` may only appear as antecedent of `implies`).

**Behavior on explicit `equals` calls.** Note that the diagnostics are described
only for expressions of the form `e1 == e2` and `e1 != e2`. It bears the
question of what happens if you write `e1.equals(e2)` instead, and we resolve
to the operator.

Thanks to the _Code generation_ strategy defined in the previous section, 
you still get the smart casting behavior. On the other hand, no checks are
performed on `e2`, since `equals` still has `Any?` as argument.

**(Lack of) symmetry.** Unfortunately, the definition above is not symmetric:
if `e1` defines strict equality but `e2` does not, then `e1 == e2` leads to an 
error, but `e2 == e1` does not. This ultimately boils down to the fact that one 
is translated as `e1.equals(e2)` and the other as `e2.equals(e1)`.

### Standard library

This proposal would automatically bring strict equality to those types defined
as data classes, enumerations, or objects in the standard library.
We also propose to turn some of the implicit guarantees in the documentation
into proper `equals` operators, including collection types such as
[List](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-list/),
[Set](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-set/),
and [Map](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-map/).

### IDE support

This section defines potential diagnostics and hints than an IDE could provide,
related to the transition to strict equality.

**Turn into `equals` operator.** If the IDE determines that the 
implementation of `equals` starts with a check for the enclosing type, then it 
should suggest turning it into an operator, and drop the initial check.

**Strict equality for sealed hierarchies.** If the IDE detects that all
inheritors of a sealed hierarchy define a strict equality (either explicitly, 
or using compiler-generated methods), it should suggest defining an abstract 
`equals` operator in the common parent.

## Alternative models

In this section we discuss alternative models for typed equality. Note that in 
all cases an important disadvantage is that we would need to change what `==` 
means, since we wouldn't be reusing the `equals` method from `Any`. In turn,
this implies interoperability with older code, and with Java becomes more difficult.

### `Equatable<T>` interface

Kotlin's (and in fact, JVM's) standard library defines a Comparable interface.
In most cases, this interface is used with a self type argument.

```kotlin
data class Name: Comparable<Name> {
  override fun compareTo(other: Name): Int
}
```

We could take a similar approach and define `Equatable<T>` – in fact, in theory 
it should be a parent of `Comparable<T>`, since defining `compareTo` also
defines equality.

The main caveat here is that we would not be able to refine the type argument
on inheritance, something that the proposed model does.

```kotlin
interface Entity: Equatable<Entity>

// [INCONSISTENT_TYPE_PARAMETER_VALUES]
// Type parameter 'T' of 'interface Equatable<T : Equatable<T>> : Any'
// has inconsistent values: Entity, User.
data class User(val id: Int): Entity, Equatable<User> {
  ...
}
```

We may solve this problem with _self types_, but that brings much more
complications to the type system. At this point, we think that a proposal
targeted only on `equals` fits Kotlin much better.

**Why is this fine for Comparable?** The first remark is that `Comparable` 
suffers from these problems, too. A quick look at the inheritors of 
[Comparable](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Comparable.html) 
shows no generic types. The second remark is that it makes sense to order values
of different types – for example, all `Either.Left` values go "before" 
`Either.Right` values. That means that the problem of refining the type argument 
on inheritance is not as pressing in this case.

### Type class

In [Scala 3](https://github.com/scala/scala3/issues/1247), 
[Rust](https://doc.rust-lang.org/std/cmp/trait.Eq.html), 
[Haskell](https://hackage-content.haskell.org/package/base-4.22.0.0/docs/Data-Eq.html), 
and similar languages, equality is defined by means of a type class (trait, given).
This fits the model since equality is almost always thought of as structural in 
those languages. In those languages, equality over lists is defined only if you also have equality over the elements. 

But as discussed above, this doesn't hold for our lists:
* Instances of different lists types may be equal,
* Even if the list type is equal, we may have a complex equality for elements.

# Equality bounds

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Dmitry Khalanskiy, Pavel Kunyavskiy, Nikolay Lunyak, Grigorii Solnyshkin, Oleg Yukhnevich, Denis Zharkov
* **Discussion**: [KEEP-476](https://github.com/Kotlin/KEEP/discussions/476)
* **Status**: 
  * Phase 1: experimental in 2.5.0, under `-Xstrict-equality-for-structural-classes`
  * Phase 2: in progress
* **Related YouTrack issues**: [KT-83683](https://youtrack.jetbrains.com/issue/KT-83683)

## Abstract

We propose a way to declare that equality checks only make sense for a restricted
subset of values, by stating or assuming an _equality bound_ on the `equals` function
inherited from `Any`. This in turns unlocks additional use site diagnostics.

### TL;DR

* The feature is going to be implemented in two phases.
  In the first one equality bounds are assumed for _structural_ classes
  (data, value, and enumeration classes),
  whereas in the second one developers may state custom equality bounds.
* The _equality bound_ defines those types for which `equals`
  produces a meaningful result (as opposed to immediate `false`).
* On (in)equality comparisons, the type of the right-hand side is checked
  for compatibility with the equality bound of the left-hand side.

## Table of contents

* [Abstract](#abstract)
  * [TL;DR](#tldr)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Equality bound](#equality-bound)
  * [Reusing `equals(other: Any?)`](#reusing-equalsother-any)
  * [No code generation](#no-code-generation)
  * [Phased development](#phased-development)
  * [Other design decisions](#other-design-decisions)
* [Phase 1: strict equality for structural classes](#phase-1-strict-equality-for-structural-classes)
  * [Use site warnings](#use-site-warnings)
* [Phase 2: custom equality bounds](#phase-2-custom-equality-bounds)
  * [Declaration site](#declaration-site)
  * [Use site warnings](#use-site-warnings-1)
  * [Standard library](#standard-library)
  * [Platform-specific considerations](#platform-specific-considerations)
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
* **Declaration site**: being able to define `equals` as being only sensible
  to compare with a subset of types.
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
  override fun equals(other: Any?) = other is A && this.value == other.value
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

### Reusing `equals(other: Any?)`

Another important design decision is to keep using the well-known `equals`
overload that takes `Any?` as parameter type, inherited from `Any`.
There are several advantages to this choice.

First of all, we do not need to change our code generation strategy, 
`x == y` keeps resolving to that `equals` overload in all cases.

This choice also leads to better integration with existing code – both in
multiplatform code that is actualized to Java classes, and in terms of binary
interface of existing classes. In particular, you can add equality bounds to
existing classes without breaking binary compatibility.

### No code generation

In previous iterations of this proposal, adding an equality bound would generate
a small "preamble" that would already check that the given argument was of
the right type, and return `false` otherwise. Although this was initially seen
as a nice help to developers, it turns out that it brings additional
complications:

- It is unclear how that preamble should interact with inherited equality
  bounds, especially if the origin of that bound had an abstract `equals`
  (as it is the case with `List` or `Set`).
- It brings some noise to already existing `equals` implementations,
  especially if they already introduce the same checks that the preamble
  would do.

Since strict equality is no longer satisfied by design, that means we can use
the equality bound as a source of warnings, but places for which correctness
is necessary (like smart casting) are no longer part of the design.

> [!NOTE]
> We expect tooling (including IntelliJ IDEA) to perform data flow analysis
> on custom `equals` implementations, and report those cases in which the
> strict equality constraint is violated.

On the bright side, since we no longer depend on any code generation strategy,
we can just assume that the implicit contract holds for structural classes.

### Phased development

Instead of releasing this feature in its full form, we introduce two _phases_.
The difference is that only in the second phase developers may state custom
equality bounds for their types. In the first phase the bound is assumed for
_structural_ classes — roughly, all those for which the compiler generates
the implementation of `equals` or the runtime provides it.

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
  with multiversal equality, since it requires resolving the `equals` overload 
  in its entirety.

**Co-existence with other `equals`.**
There's a non-trivial amount of code that defines `equals` on a more limited
type ([GitHub Search](https://github.com/search?q=lang%3AKotlin+%2Ffun+equals%5C%28%5B%5E%3A%5D%2B%5Cs*%3A%5Cs*%5B%5EA%5D%5Cw*%5C%29%2F&type=code)
throws around 1.4K results). 

Bear in mind that those `equals` would **not** be picked during resolution
of `==`. In fact, `e1 == e2` is not always equivalent to `e1.equals(e2)`:
the former always picks the information from the `equals(Any?)` overload,
whereas the latter undergoes overload resolution as usual.

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
that the only possible one for `==`. In the case above, this means turning
`equals` into an override with a declared equality bound.

```kotlin
@EqualityBound(WrongEquals::class)
data class WrongEquals(val x: Int) {
  override fun equals(e: Any?): Boolean {
    println("Hello")
    return true
  }
}
```

## Phase 1: strict equality for structural classes

**Structural class.** The following kinds of classifiers are considered
_structural_.
- Data classes and data objects,
- Final value classes,
- Enumeration classes,
- Java records.

Structural classes roughly contains those classes for which the compiler
either generates an `equals` implementation, or it is given by the underlying
platform.

**Assumed equality bound.** For structural class the compiler should
assume that the equality bound is the (raw) class itself.
If the class overrides `equals`, it is up to the developer of that class
to ensure that the strict equality contract is satisfied.

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

**Equality type bound.** For each type we compute an upper bound for the types
it can be compared against, which we call the _equality bound type_.
We write `ebt(A)` for the equality type bound of `A`.

- For classifiers, the equality bound type is the star-projected version of
  the equality bound (assumed, declared or inherited, see below) of the classifier.
- If the type is nullable `T?`, we take the nullable version of the equality 
  bound type of `T`.
- For intersection types, we take the intersection of the equality bound types.
- For flexible types, we take the equality bound type of the upper bound.
- For captured types and type parameters, we take the intersection of the
  equality bound types of their upper bounds.
- Otherwise, we take `Any?` as the equality bound.

> [!NOTE]
> We stress the fact that if a classifier does not declare an equality bound
> (because the binary was created before this feature was available, or comes
> from interoperability with other languages like Java), the _inherited_
> equality bound should be used instead. That allows retroactively adding 
> equality bounds to superclasses without having to recompile the child classes.

By abuse of language, we say that `T` is the equality type bound of the
expression `e` when `T` is the equality type bound of the type of `e`.

**Definitely wrong case.** 
For every expression of the form `e1 == e2`, or `e1 != e2`, we check
the compatibility (as defined in [RULES1](https://youtrack.jetbrains.com/issue/KT-57779#rules1))
between:

1. The equality bound type of `e1` and the type of `e2`,
2. The type of `e1` and the equality bound type of `e2`.

If any of those fail, a warning "This condition is always false."
(respectively "true" for inequalities) should be reported.

*Example.* If we check `e == p`, where `e` is of type `Either<String, Int>`,
and `p` is an instance of a data class `Point`, we can see that the two
types are incompatible.

**Behavior on explicit `equals` calls.** Note that the diagnostics are described
only for expressions of the form `e1 == e2` and `e1 != e2`. It bears the
question of what happens if you write `e1.equals(e2)` instead, and we resolve
to the `equals(Any?)` overload.

1. The compiler should resolve the call following the usual rules,
2. If the call is ultimately resolved to the `equals(Any?)` overload, the
   checks described in this section should be performed.

## Phase 2: custom equality bounds

### Declaration site

**`EqualityBound` annotation.** The following class is introduced in the
standard library,

```kotlin
@Target(AnnotationTarget.CLASS)
annotation class Bound(val bound: KClass<*>)
```

**Equality bound(s)**. The following three definitions describe how to compute
the equality bound of a classifier.

- The classifier may be annotated with `@EqualityBound(Class::class)`,
  or have an assumed equality bound (as defined in the previous phase).
  This defines the _explicitly declared_ equality bound.
- The _inherited_ equality bound is define as the intersection of the 
  equality bounds of the parent classifiers.
- The _equality bound_ is defined as the explicitly declared equality bound,
  if present, or the inherited equality bound otherwise.

**Constraints on equality bounds**. The equality bound must satisfy the
following restrictions:

* It must be equal or a superclassifier of the classifier defining it,
* It must be equal or a subclassifier of the inherited equality bound,
* It must be a denotable classifier.

This implies that the user needs to declare an equality bound if the inherited
equality bound cannot be simplified to a single type.

**Example 1.** Sealed hierarchy in which each subclass is only equal to itself.

```kotlin
@EqualityBound(Either::class)
sealed interface Either<out L, out R> {
  // assumed @EqualityBound(Right::class)
  data class Right<out R>(val value: R) : Either<Nothing, R>
}
```

**Example 2.** `Collection`-like hierarchy, in which all subclasses are
comparable amongst themselves.

```kotlin
@EqualityBound(List::class)
interface List<out L> { ...  }

// inherited @EqualityBound(List::class)
class ArrayList<L> : List<L> { ... }
```

**Example 3.** Required explicitly declared equality bound because of a
non-denotable inherited equality bound.

```kotlin
@EqualityBound(One::class)
interface One { ... }

@EqualityBound(Two::class)
interface Two { ... }

// explicitly declared equality bound is required for compilation
// inherited equality bound is 'One & Two' (non-denotable)
@EqualityBound(Three::class)
class Three : One, Two { ... }
```

**Example 4.** An equality bound on an object that extends `Any` directly
needs no override for `equals` since the default reference comparison is correct.
That means that you can easily declare its equality bound.

```kotlin
@EqualityBound(O::class)
object O
```

**Inline value classes.** Value classes marked with the `@JvmInline` annotation,
or any other platform-specific annotation that erases the "box" during compile
time, may not define their own `equals` override nor inherit one from
superclasses. Due to the erasure, the runtime cannot guarantee that the correct 
implementation is called otherwise.

Note that these restrictions are already in place, since the `equals` member 
is reserved for value classes. Lifting this restriction is out of the scope
of this proposal, but this proposal does **not** block such move.
If this restriction was lifted,
(for example, [KT-24874](https://youtrack.jetbrains.com/issue/KT-24874)),
then it should also work for this new `equals` override.

**Multiplatform.** Whenever we want to `actual`ize an `expect` classifier we need
to check that equality bounds are compatible,
- If the classifier is final, then the equality bound of the `actual` classifier must
  be equal or more specific than that of the `expect` classifier,
- Otherwise, the equality bound must coincide between `expect` and `actual`.
  
Note that the equality bounds (of both `expect` or `actual` classifiers) may be inherited.

To preserve source compatibility, whenever the actualization is done via either
[Kotlin-to-Java direct actualization](https://github.com/Kotlin/KEEP/blob/kotlin-to-java-direct-actualization/proposals/kmp-kotlin-to-java-direct-actualization.md),
or a type alias to a class outside the current module,
the errors mentioned in the previous paragraph should turn into warnings.

### Use site warnings

**The need for the other case.** The _definitely wrong_ check is too strict.
For example, it does not cover the case in which we check `list == set`.
The reason is that `List` and `Set` are interfaces, so you can always define a
class implementing both:

```kotlin
class ListAndSet<out A> : List<A>, Set<A> { ... }
```

In practice, such comparisons are almost always unintended. Henceforth we
introduce another check. This may have false positive, since the following
function will trigger a warning, even though we could call it with 
`ListAndSet` instances.

```kotlin
fun test(list: List<Int>, set: Set<Int>) {
  if (list == set) { ... }  // warning
}
```

This is fine, however, since the fix is simply a instance check away,

```kotlin
fun test(list: List<Int>, set: Set<Int>) {
  if (set is List && list == set) { ... }  // no warning
}
```

**The two checks.** For every expression of the form `e1 == e2`, or `e1 != e2`,
we check for two cases: the _definitely wrong_ case, and the _potentially
wrong_ case. Their naming shows how much confidence we have in the raised
issues. Note this is just for understanding, in compiler terms both are
warnings.

**Potentially wrong case.**
For every expression of the form `e1 == e2`, or `e1 != e2`, we check:

1. Whether the type of `e2` is a subtype of the equality bound type of `e1`,
2. Whether the type of `e1` is a subtype of the equality bound type of `e2`.

If _both_ are false, we report
"This condition seems to always be false. Introducing a smart cast may clarify the intent."
(respectively "true" for inequalities).

_Example #1._ `list == set`, where `list : List<Int>` and `set : Set<Int>`.
Both types have themselves as their equality bound type. 

First of all, the types are not incompatible, so the "definitely wrong case"
is not reported. The "potentially wrong case" checks are:

1. Is `List` a subtype of `Set`?
2. Is `Set` a subtype of `List`?

Both are false, so we issue a warning.

_Example #2._ `list == coll`, where `list : List<Int>` and `coll : Collection<Int>`
The equality bound type of `Collection` is `Any`. This is a case where we would
like to _not_ report a problem, since `Collection` is a supertype of `List`.

As in the previous example, the types are not incompatible. The second check
amounts to answering:

1. Is `List` a subtype of `Any` (equality bound of `Collection`)?
2. Is `Collection` a subtype of `List`?

The first one is true, so we do not report anything.

### Standard library

This proposal would automatically bring strict equality to those types defined
as data classes, enumerations, or objects in the standard library.
We also propose to turn some of the implicit guarantees in the documentation
into proper equality bounds, including collection types such as
[List](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-list/),
[Set](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-set/),
and [Map](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-map/).

### Platform-specific considerations

**No breakage on JVM.** In particular, this means we can introduce
equality bounds in Kotlin without breaking usages in the JVM platform:

- The binary interface does not change, since we still use the 
  `equals(obj: Object)` overload,
- We can keep the same `actual` declarations, and they will pick up the 
  underlying `equals` from JVM's `Object` as the implementation.
- For those classes with built-in equality – like records or future value 
  classes – the platform can still do their optimizations.

**Swift interoperability.** Types imported from Swift that (implicitly or
explicitly) implement the [`Equatable` protocol](https://developer.apple.com/documentation/swift/equatable)
should be assumed to have an equality bound equal to the type itself.

### IDE support

This section defines potential diagnostics and hints than an IDE could provide,
related to the transition to strict equality.

**Introduce an equality bound.** If the IDE determines that the 
implementation of `equals` starts with a check for the enclosing type, then it 
should suggest adding `@EqualityBound` to the class.

**Correctness of `equals`.** The IDE should perform a data flow analysis
to figure out if the implementation of `equals` violates the contract of
its equality bound, and report when that is the case.

**Strict equality for sealed hierarchies.** If the IDE detects that all
inheritors of a sealed hierarchy define an equality bound (either explicitly, 
or using compiler-generated methods), it should suggest defining one also
in the common parent.

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

# Design Notes: Local Lifetimes for Kotlin

* **Type**: Design Notes
* **Author**: Ross Tate
* **Contributors**: Komi Golova, Mikhail Zarechenskiy, Alejandro Serrano Mena, Marat Akhin
* **Discussion**: [#485](https://github.com/Kotlin/KEEP/discussions/485)

# Abstract

This document summarizes ongoing research on local lifetimes, a feature that enables programmers to safely take advantage of objects and operations that are only safe to use for a limited time.

# Disclaimer

This work is still research in progress.
We are sharing it early because we want to collect feedback (hopes, concerns, suggestions, and so on) from the community before determining whether to develop it into a formal proposal.

# Table of Contents

<!-- TOC -->
- [Design Notes: Local Lifetimes for Kotlin](#design-notes-local-lifetimes-for-kotlin)
- [Abstract](#abstract)
- [Disclaimer](#disclaimer)
- [Table of Contents](#table-of-contents)
- [Design](#design)
   * [Overview](#overview)
   * [Tracking Lifetimes](#tracking-lifetimes)
   * [Localizing Interfaces](#localizing-interfaces)
   * [Localizing Classes](#localizing-classes)
      + [Inheritance](#inheritance)
   * [Preventing Escapes](#preventing-escapes)
   * [Backwards Compatibility](#backwards-compatibility)
   * [Type-Checker Feedback](#type-checker-feedback)
   * [Compile-Time Implementation](#compile-time-implementation)
   * [Run-Time Implementation](#run-time-implementation)
      + [Lexical Aborts](#lexical-aborts)
      + [Lexical Suspends](#lexical-suspends)
   * [Advanced Locality Polymorphism](#advanced-locality-polymorphism)
   * [Advanced Reference Equality](#advanced-reference-equality)
- [Localizing the Standard Library](#localizing-the-standard-library)
   * [Breaking Changes](#breaking-changes)
      + [`...All` methods](#all-methods)
      + [Builders](#builders)
   * [The Change to Consider Making](#the-change-to-consider-making)
   * [In-Depth Example](#in-depth-example)
   * [Interesting Examples](#interesting-examples)
      + [Reducing `crossinline`](#reducing-crossinline)
      + [Constructors](#constructors)
      + [Delegates](#delegates)
      + [Cleanup](#cleanup)
      + [Views](#views)
      + [Strings](#strings)
      + [Restricting Type Parameters](#restricting-type-parameters)
      + [Variadic arguments](#variadic-arguments)
- [Scala Capture Checking](#scala-capture-checking)
   * [Backwards Incompatibility](#backwards-incompatibility)
   * [Pseudotypes](#pseudotypes)
   * [Conclusion](#conclusion)
<!-- TOC -->

# Design

This design first and foremost contributes the `local` keyword as a means for restricting functions to use a parameter in only a "local" manner so that callers can be guaranteed their corresponding arguments do not "escape" the call, thereby both providing useful software-engineering compositionality guarantees and safely enabling arguments to have more advanced functionality.

This design is a follow-up to the existing `callsInPlace` experimental feature.
Whereas `callsInPlace` is a CFG analysis, local lifetimes are directly incorporated into the type system, which makes it more robust.
For example, the following program is accepted using local lifetimes but rejected using CFG analyses:
```
fun <T> Sequence<T>.sum(local convert: (T) -> Int): Int
    = map(convert).sum()
```
One issue is that CFG analyses are intraprocedural (except for `inline` functions), and so have to treat `map` as a black box that could potentially leak `convert`.
Another issue is that—even if we were to inline `map`—`map` returns a sequence that *lazily* generates its values, and that *object* repeatedly calls `convert`, which looks to the `callsInPlace` analysis like the function is being leaked onto the heap.
By integrating locality into the type system, we can restrict the lifetime of that object, and then later confirm that it is only used within its restricted lifetime.

Note that there is a KEEP in development for adding syntax for directly integrating `callsInPlace` via `local` parameters.
That KEEP still uses a CFG analysis, but its syntax is designed to be forwards compatible with this proposal.

## Overview

The following is an example of a function using this feature and conforming to its restrictions:
```
fun <E, R> local Iterator<E>.fold(init: R, local folder: (R, E) -> R): R {
    var result = init
    for (element in this)
        result = folder(result, element)
    return result
}
```
This function uses `local` twice.
The first use indicates that the `this` parameter is used only locally.
The second use indicates that the `folder` parameter is used only locally.
Indeed, we can see that neither `this` nor `folder` flow to anything that could use these values after a call to this function completes; that is, neither "escapes" the call.

On the other hand, notice that it would be incorrect to label `init` with `local`.
The `init` reference flows into `result`, which is then passed to `folder`.
While `fold` is required to use `folder` only locally, this does not restrict how `folder` uses its own arguments.
In particular, `folder` could store its argument in some global, which here means `init` could be accessed through that global after the call to `fold` completes.

The guarantee provided by `local` enables callers to such functions to safely use arguments with more advanced functionality, such as in the following example:
```
fun local Iterator<Int?>.sum(): Int?
    = fold(0) { result, element -> result + (element ?: return null) }
```
Here `sum` calls `fold` with a `folder` function that is only safe to execute while the call to `sum` is still alive.
In particular, this function will cause `sum` to return if ever it is given a `null` element.
Because `folder` is a `local` parameter to `fold`, we know `fold` will not cause this function to be accessible after the call, which here means the `return` is guaranteed to only ever execute while the call to `fold` (inside `sum`) is still alive.
Kotlin already provides similar functionality with `inline`, but `local` enables this functionality in a more modular and compositional manner.

## Tracking Lifetimes

At present, Kotlin objects all have infinite—or `global`—lifetimes.
Yes, they get garbage-collected, but that's more of a resource-usage optimization than it is a part of the core semantics of the language.
After all, if you had infinite memory (and no finalizers), you could never tell if/when an object is garbage-collected.

`local` forces the type-checker to make sure the function is safe *without* assuming the corresponding parameter has a global lifetime; the type-checker is only allowed to assume the lifetime of the object at least exceeds the lifetime of the call.
Because the default lifetime is global, this means the type-checker needs to ensure the parameter does not flow into anything expecting a "standard" object.
This means that the lifetimes of `local` parameters effectively become tracked by the type-checker.

This lifetime perspective/guarantee enables us to use things like `return` inside functions to `local` parameters.
`return` effectively captures a reference to the continuation of the current call.
Unlike instances of Kotlin's `Continuation` from the `kotlin.coroutine` libraries, the continuation of the current call has a limited lifetime (which is critical to using a stack of call frames).
This means that `return` must have a limited lifetime, and consequently the lambda using it must as well.
But the use of `local` ensures the callee respects this limited lifetime.

We can also use this lifetime perspective/guarantee to expand upon the functionality of this feature.
For example, supporting the following function might be useful:
```
fun <A, B> local Iterator<A>.map(
    local transform: (A) -> B
): Iterator<B>_{this&transform} = object Iterator {
    override fun hasNext() = this@map.hasNext()
    override fun next() = transform(this@map.next())
    override fun remove() = this@map.remove()
}
```
This extension method returns an `Iterator` whose method implementations capture the `this` and `transform` parameters.
As such, the methods of this `Iterator` are only safe to invoke within the lifetimes of those parameters.
The type modifier `_{this&transform}` indicates that, rather than having the default global lifetime, the returned `Iterator`'s lifetime is restricted by that of `this` and `transform`.

So `local` is, more precisely, an indicator that a lifetime should be tracked instead of assumed to be global.
Because the default lifetime is global, that will prevent such parameters from flowing arbitrarily out of the function.
But a user can modify types to explicitly allow flows to select locations, and the type-checker can use those modifications to ensure the more advanced flows still respect the limited lifetimes safely.
Taken together, this design enables library designers to opt-in to locality in order to enforce correct usage and support more advanced usage patterns without breaking prior (correct) usage patterns.

## Localizing Interfaces

Above we defined an extension method for `Iterator`, so now consider the following extension method for `Iterable`:
```
fun <A,B> local Iterable<A>.map(
    local transform: (A) -> B
): Iterable<B>_{this&transform} = object Iterable {
    override fun iterator() = this@map.iterator().map(transform)
}
```
At first, this seems perfectly safe, but realize that `Iterable.iterator()` expects to return an `Iterator` with a `global` lifetime, whereas the `Iterator` constructed here must have its lifetime restricted to within the lifetimes of `this` and `transform`.
For methods like `toString`, it makes sense for the returned object to have a global lifetime.
But for methods like `Iterable.iterator`, the expectation is that the returned object is a view of the `Iterable` object and as such should only be valid while that object is valid.

We can reflect this by localizing the signature of `Iterable` to propagate lifetime constraints:
```
interface Iterable<E> {
    fun iterator(): Iterator<E>_{this}
}
```
Here we have restricted the lifetime of the `Iterator` returned by `iterator` to within that of the receiver.
This modification in turn makes our extension method above valid.

As a technical note, the `this` lifetime must only be used contravariantly in an interface's signature.
The `_{-}` operator is itself contravariant, so this restriction means that `_{this}` can only be applied to types at covariant positions in the signature.
A major advantage of this restriction is that all existing classes and interfaces implementing/extending an interface will continue to be valid implementations/extensions even after localizing the interface!
That is, localizing interfaces enables more implementations rather than rejects implementations.

## Localizing Classes

Localizing an interface enables more implementations of that interface.
Localizing a class enables instances of that class to use local values.

When a constructor specifies a parameter is `local`, that means the parameter is not allowed to escape the lifetime of the call to the *constructor*.
This is perfect for the builder pattern, since the building function is never used after the construction of the object is completed.
However, it does not work for mapping iterators and iterables because the mapping function is used after construction; in particular, it is used by the object returned by the construction.
Thus, the lifetime of the returned object needs to be restricted to that of the parameter.

As a first step, we allow one to declare a class to be local.
Whereas objects of current classes all have global lifetimes, each object of a `local class` can have its own lifetime.
That is, a `local class` is implicitly parameterized by a `this` lifetime, representing the lifetime of the object, *and* the lifetime of its `this` reference is restricted to the `this` lifetime.
This makes it possible for each object of a `local class` to safely access resources with limited lifetimes *provided* those resources have at least the lifetime of the object.

The following illustrates how we can apply this to mapping iterators/iterables:
```
local class MappingIterator<A, out B>(
    private val iterator: Iterator<A>_{this},
    private val transform: (A) ->_{this} B
) : Iterator<B> {
    override fun hasNext() = iterator.hasNext()
    override fun next() = transform(iterator.next())
    override fun remove() = iterator.remove()
}

local class MappingIterable<A, out B>(
    private val iterable: Iterable<A>_{this},
    private val transform: (A) ->_{this} B
) : Iterable<B> {
    override fun iterator()
        = MappingIterator(iterable.iterator(), transform)
}
```
Notice that we have added `local` before `class` to indicate that the lifetime of their objects may vary.
This also means that the `this` lifetime of these objects is effectively a locality parameter to their construction.
This locality parameter is implicit, so when we construct an object of these classes, the algorithm will automatically determine what lifetime the object should have for the constructors arguments to be valid.
In this case, we've added `_{this}` to some parameters of the constructor to indicate that the lifetime of those parameters must exceed that of `this` object so that they can be safely accessed by its methods.
This has the effect of restricting the object's lifetime to be within the lifetimes of the relevant parameters.

### Inheritance

A non-`local` class has no restrictions on its `this` reference.
As such, if we were to allow a `local` class to inherit a non-`local` class, even just the constructor of that class could leak the `this` reference to some global, violating its local lifetime.
For this reason, a `local` class can only inherit `local` classes.

Because all classes inherit `Any`, this means we must make `Any` be a `local` class.
It is fine for non-`local` classes to inherit `local` classes, so this change is backwards compatible.
(The method implementations provided by the `Any` class—namely of `equals`, `hashCode`, and `toString`—do not leak the `this` reference, making it safe for `Any` to be a `local class`.)

More generally, making an existing class be `local` is a backwards-compatible change (provided its superclasses have already been localized).
All classes inheriting it will still be valid, and it has no effect on how instances of that class can be used by others.

## Preventing Escapes

As a convenience for enabling users to proactively prevent certain bugs, we could allow `local val x = ...` as a means for artificially limiting the perceived lifetime of some value.
That is, the type of `x` would be the same as the initializing expression *except* with its lifetime (further) restricted to the lexical scope of `x`.
When `...` is a construction of some `local class`, this combination of features would guarantee that the instantiated object does not escape the containing function call.
In addition to enforcing certain useful software-engineering guarantees, this enables the object to be safely allocated on the stack rather than the heap for better performance.

## Backwards Compatibility

Much of the design seems to be surprisingly backwards compatible and easy for users to adopt gradually.
The key incompatibility is that `Any?` will no longer be the top type because it has a global lifetime.
Of course, this is also an issue for many other potential extensions, so this might just be increasing pressure to address that need for change.

## Type-Checker Feedback

Experience so far is that this design allows libraries to incorporate local lifetimes into their signatures and have that added information automatically flow through application code without change.
One might wonder how this impacts feedback from the type-checker while developing application code.
In particular, is feedback going to be cluttered by lifetime information everywhere?

Local lifetimes are essentially orthogonal to Kotlin's existing types.
That means that, when a typing error is found, it will be either due to an error that would arise using Kotlin's existing types (e.g. using a `String` where an `Int` is expected) or due to something escaping its lifetime (e.g. a `local` parameter flowing into a global variable).
So, in the former case, the error message can look the same as it does now, eliding lifetime information because it is irrelevant to the error.
And, in the latter case, the error message can focus specifically on localities, showing the leak while eliding the class/interface information because it is irrelevant to the error.

On the other hand, when a programmer hovers over something to find out what its type is, the IDE does not know what aspect of the type is relevant to the programmer's inquisition.
Nonetheless, the IDE could still use formatting (e.g. color coding) to make it easy for the programmer to separate class/interface vs. lifetime information and focus on whichever aspect they are interested in.

## Compile-Time Implementation

We validate functions with `local` parameters by treating them as lifetime-polymorphic functions.
For each `local` parameter, we associate a corresponding lifetime parameter.
Note that when we call a function with a parameter that itself is a function with a `local` parameter, we will have to use a technique for supporting nested generic methods.
We have already developed such a technique, so I won't repeat it here.

We validate objects with limited lifetimes by using a type modifier `_{lifetime}`.
This modifier is applied to the type of each `local` parameter (using its associated lifetime parameter).

Subtyping judgements are modified to look like `type <: type' | lifetimes`, which means `type` is a subtype of `type'` assuming their values are used only within all of the lifetimes listed by `lifetimes`.
A subtyping of the form `type <: type'_{lifetime} | lifetimes` holds if and only if `type <: type' | lifetimes, lifetime` holds.
A subtyping of the form `type_{lifetime} <: Foo<types'> | lifetimes` holds if and only if `type <: Foo<types'> | lifetimes` *and* `lifetimes <: lifetime` hold, where the latter is a judgement about solely sub-lifetimes.

Note that this judgement about sub-lifetimes allows multiple lifetimes on the left, and as such it is amenable to supporting lifetime parameters with multiple upper bounds (but only one lower bound).
This is important because various points in a function body will have an associated abstract lifetime (representing how long things like accesses to local variables or uses of local control operations will be safe), and that abstract lifetime needs to be upper-bounded by the lifetime parameter of each `local` parameter and by the abstract lifetime of any outer points that the current point is nested within.

Fortunately it seems that, at least algorithmically speaking, this feature is a relatively straightforward extension of the existing techniques we have for outference.
That said, the technique for supporting for nested generic methods was originally for hypothetical extensions, so its pragmatics have not been discussed or explored much.

## Run-Time Implementation

This feature enables new forms of control.
For example, a `local` parameter can `break` out of the loop of some calling function, or can `suspend` some containing coroutine.
The former is a lexical abort (rather than a dynamic abort, like throwing an exception), and the latter is a lexical suspend.

### Lexical Aborts

There are two key ways we can implement lexical aborts, though some backends will only be amenable to one of these.
One important consideration is the interaction with `finally`.
That is, if the abort happens from somewhere "inside" a `try` and redirects control to somewhere "outside" that `try`, then we should execute the corresponding `finally` block in the interim.

The easiest way to do this is to piggyback on dynamic aborts.
That is, one throws a special exception that's ignored by (user-level) `catch` but still executes `finally` blocks as the stack is unwound.
The special exception is created with some identifier of the relevant call frame, and it bubbles up until it reaches that frame, at which point control jumps to the relevant point in the calling function.

Another way is to track where the most recent `finally` block is on the stack and jump straight to it, handing it the lexical abort to continue performing afterwards.

Each technique has its own performance and interoperability tradeoffs, and both have circumstances where they are much better suited than the other.

### Lexical Suspends

There are two key ways we can implement lexical suspends, though some backends will only be amenable to one of these.
The key issue is that we can no longer always implement `suspend` using conversion to automata.

One way is to adopt first-class stacks (a.k.a. lightweight threads or green threads).
Every (non-automata-convertible) coroutine would have its own call stack, and would keep track of whom to transer control to when `suspend`ed.

Another way is to make functions that can potentially suspend (do to being given a `suspend` or `local` parameter) instead return something like a promise.
That is, the function returns either a value of the expected type or something that a (unique) callback can be registered on to be called with the value when the suspended computation is resumed.
The caller then checks which case occurred and either continues with the value immediately or registers the callback for doing so later.
(Alternatively, one could commit to always use continuation-passing style rather than try to optimize for the case where no suspension happens.)

Each technique has its own performance and interoperability tradeoffs, and both have circumstances where they are much better suited than the other.

## Advanced Locality Polymorphism

As mentioned, `local` parameters are type-checked by encoding them as locality polymorphism.
In rare advanced cases (possibly only within the standard library), it is useful to write locality-polymorphic signatures directly.
In fact, the only cases I know of arise only when we add support for stacks; nonetheless, it seems make more sense to speak about the feature here rather than when introducing stacks.

Just as generic classes/interfaces/methods/functions can have type parameters, so can they have locality parameters.
Such parameters can be specified using `local x` (rather than just `X` for a type parameter).
So, for example, we could make the signature for `Iterable.map` slightly more expressive:
```
fun <A, B, local this, local transform> Iterator<A>_{this}.map(
    transform_{transform}: (A) -> B
): Iterator<B>_{this&transform} = object Iterator {
    override fun hasNext() = this@map.hasNext()
    override fun next() = transform(this@map.next())
    override fun remove() = this@map.remove()
}
```

How is this more expressive than before?
Well, unlike the previous localized signature, neither the `iterator` nor the `transform` lifetime need to be accessible *during* the call to `map`.
That is, explicitly declared locality parameters have *no* upper or lower bound (by default).
One can optionally impose such bounds by changing the declaration to be `local_{lower}^{upper} x`; one can also specify only a lower bound or only an upper bound, though if both are specified than the lower bound needs to be a sub-lifetime of the upper bound.
(The type syntax `Foo_{lifetime}` is related because it means the lifetime of the object is lower-bounded by `lifetime`.)

Also, on occasion it can be useful for a function/method to refer to the lifetime of the current call.
For this, we can reuse the keyword `local` itself as a lifetime.
That is, all functions/methods are implicitly parameterized by the locality of the current call, and `local` is the way to refer to that implicit parameter.

As such, we can desugar `local` function parameters into explicit locality polymorphism:
```
fun <E, R> local Iterator<E>.fold(
    init: R,
    local folder: (R, E) -> R
): R
```
is shorthand for
```
fun <E, R, local_{local} this, local_{local} folder> Iterator<E>_{this}.fold(
    init: R,
    folder: (R, E) ->_{folder} R
): R
```
This signature is itself equivalent to
```
fun <E, R> Iterator<E>_{local}.fold(
    init: R,
    folder: (R, E) ->_{local} R
): R
```
The compiler can take advantage of such simplifying equivalences to speed up type-checking.
Similarly, given this shorthand, it makes sense to allow `local^{upper}` when declaring a function parameter (provided `upper` is a super-lifetime of the implicitly lower-bounding `local` lifetime).
It might also make sense to allow overriding the default lower bound, including allowing `_{}` to indicate there should be no lower bound.
This would allow one to express the more expressive signature for `map` without significantly changing it like we had to above:
```
fun <A, B> local_{} Iterator<A>.map(
    local_{} transform: (A) -> B
): Iterator<B>_{this&transform} = object Iterator {
    override fun hasNext() = this@map.hasNext()
    override fun next() = transform(this@map.next())
    override fun remove() = this@map.remove()
}
```

Finally, by default `fun` declarations create functions that either have the `global` lifetime (when they are declaraing top-level functions and extension methods or methods of non-`local` classes) or the `local` lifetime (when they are declaring methods of interfaces or `local` classes).
The lifetime of a function is the upper bound of its `local` lifetime parameter.
On rare occasion, it can be useful to override this default behavior.
One does so by using the syntax `fun_{lifetime}`.
For example, if an interface has a method for creating child objects, often that method could be declared `fun_{global}` because the allocation does not actually access the object; it just creates a new child object and hands it a reference to the parent object.

## Advanced Reference Equality

Performing reference equality needs access to the identifier/address of the objects being compared.
By default, this is only accessible for an object when it is live, which is important for being able to stack allocate it.
However, it can be useful for an object's identifier/address to be accessible even outside its lifetime.
To permit this for objects of a particular class/interface, one can declare the class/interface using `class_{global}` or `interface_{global}` (even if the class is `local`).

# Localizing the Standard Library

In order to assess how complete this type system is as well as what incorporating it into the Kotlin ecosystem looks like, I have been localizing the standard library for Kotlin.
While there is still more to do, the collection library has been completely localized.
The following are a few highlights from that process.
(Note: some of the localization further requires the [stacks library](https://github.com/Kotlin/KEEP/blob/main/notes/0008-stacks.md), but here we just consider the aspects relevant to local lifetimes more broadly.)

## Breaking Changes

There were only two types of breaking change we found appropriate.

### `...All` methods

We made the parameter for each of the following methods of the `MutableCollection<E>` interface into `local` parameter:
```
@IgnorableReturnValue
public fun addAll(local elements: Collection<E>): Boolean
@IgnorableReturnValue
public fun removeAll(local elements: Collection<E>): Boolean
@IgnorableReturnValue
public fun retainAll(local elements: Collection<E>): Boolean
```
In theory, this could break existing implementations of this interface.
However, given that the widely used `AbstractMutableCollection` implements each of these methods (and respects the newly imposed locality), it seems unlikely this will break any code except possibly a few obscure cases.

Similar changes were made to `MutableList` and `MutableMap` with similar reasoning.

### Builders

We made the mutable parameter given to the action of builders `local`:
```
public inline fun <K, V> buildMap(
    once builderAction: local MutableMap<K, V>.() -> Unit
): Map<K, V>
```
In theory, this could break existing builder actions.
However, that would require explicitly referencing the `this` receiver, which on its own is rare, and then leaking it from the action.
It seems unlikely this will break any code except possibly a few obscure cases.

## The Change to Consider Making

It seems likely that the following are the *correct* localized signatures for methods in `Any`, `Comparable<in T>`, and `Comparator<T>`:
```
public open operator fun equals(local other: Any?): Boolean
public operator fun compareTo(local other: T): Int
public fun compare(local a: T, local b: T): Int
```
However, even though existing implementations of these methods are likely to respect the newly imposed `local` restriction on their parameters, the files would need to be updated to at least declare them as `local`.
Surprisingly, as of yet this change has not *yet* been needed to localize the standard library, but it seems likely that cases will arise that require this change.

## In-Depth Example

The following is a more-involved example of what the process for localizing a library looks like.
We start the example with the relevant outward-facing function provided by the library.
Note that this example uses the `Sequence<out T>` interface.
Like `Iterable<out T>`, this interface is localized by making its `iterator()` method return an `Iterator<T>_{this}`, reflecting the fact that the iterator typically accesses the same local resources the sequence has access to.

In this example, the function we want to localize originally looks like the following:
```
public fun <T> Sequence<Sequence<T>>.flatten(
): Sequence<T>
    = flatten { it.iterator() }
```
We first want to localize it to allow the sequence being flattened to use local resources.
That involves marking it as `local` *and* correspondingly restricting the lifetime of the returned sequence, since that sequence is lazily generated and so holds onto the input sequence:
```
public fun <T> local Sequence<Sequence<T>>.flatten(
): Sequence<T>_{this}
    = flatten { it.iterator() }
```
But we can take this further.
The sequence generates sequences, and we could allow those sequences to themselves use local resources.
We cannot do `local Sequence<local Sequence<T>>` because `local` is not a type modifier—it is a function-parameter modifier.
Instead, we can make the signature polymorphic with respect to some non-global lifetime that these nested sequences have.
We could do so by adding a new explicit locality parameter, or we can simply reusing the existing locality parameter `this` that we get by making the receiver a `local` parameter.
In the latter case, the algorithm will automatically determine a suitable common lifetime of both the receiver sequence and the nested sequences:
```
public fun <T> local Sequence<Sequence<T>_{this}>.flatten(
): Sequence<T>_{this}
    = flatten { it.iterator() }
```
Lastly, we can make the signature slightly more general by observing that none of these sequences are actually accessed during the call to `flatten`.
By default, all `local` parameters to a function must have a lifetime that is at least accessible during the function call, but we can drop that implicit lower bound by using `local_{}`:
```
public fun <T> local_{} Sequence<Sequence<T>_{this}>.flatten(
): Sequence<T>_{this}
    = flatten { it.iterator() }
```
Now we have a very general localization of our original signature, one that is particularly advanced due to the original signature's use of nesting and the implementation's use of laziness.

With that localized signature in hand, next let us see what it takes to show that the implementation satisfies this more restrictive signature.
In particular, this implementation uses the following private extension method, which we can localize as follows:
```
private fun <T, R> local_{} Sequence<T>.flatten(
    iterator: (T) ->_{this} Iterator<R>_{this}
): Sequence<R>_{this} {
    if (this is TransformingSequence) {
        return flatten(iterator)
    }
    return FlatteningSequence(this, { it }, iterator)
}
```
The key steps here are the same as before:
1. Make the receiver sequence `local` and restrict the lifetime of the returned sequence
2. Reuse the `this` locality to restrict the lifetimes of relevant components in the named parameter
3. Drop the implicit lower bound on the `this` lifetime

After making those similar changes to the signature, we then move on to the implementation.
In this case we need to update both `TransformingSequence` (whose specialized `flatten` method is invoked inside the `if`) and `FlatteningSequence`.
This means we move from localizing extension methods to localizing classes.
(Technical note: the downcast `this` reference still has a restricted lifetime. In general, downcasts change the interface portion of a type but do not change the lifetime portion of a type.)

The localized `TransformingSequence` class looks like the following:
```
internal local class TransformingSequence<T, out R> constructor(
    private val sequence: Sequence<T>_{this},
    private val transformer: (T) ->_{this} R
) : Sequence<R> {
    override fun iterator(): Iterator<R>_{this} = object : Iterator<R> {
        val iterator = sequence.iterator()
        override fun next(): R {
            return transformer(iterator.next())
        }
        override fun hasNext(): Boolean {
            return iterator.hasNext()
        }
    }

    internal fun <E> flatten(
        local_{} iterator: (R) -> Iterator<E>_{iterator}
    ): Sequence<E>_{this&iterator} {
        return FlatteningSequence<T, R, E>(sequence, transformer, iterator)
    }
}
```
The changes we made are as follows:
1. We made the class `local` so that its objects each have their own `this` lifetime.
2. We allowed the constructor parameters to each have only `this` lifetime rather than global lifetimes. When constructing an object using arguments with restricted lifetimes, the algorithm will automatically determine a common lifetime for the constructed object. Because all methods of the object are (by default) assumed to execute within `this` lifetime, they are allowed to access these parameters, though they are also prevented from leaking them to global vairables or the like.
3. The return type of `iterator()` is restricted to have the `this` lifetime so the return iterator can likewise access these constructor parameters. This is permitted because `Sequence` was localized to allow the returned iterator to have a restricted lifetime. (Note that the algorithm automatically determines that the `object` has the `this` lifetime; there is never a need to explicitly declare the lifetime of an `object`.)
4. The `flatten` method is updated to allow the given `iterator` to have a non-global lifetime, and the return type is restricted to reflect the fact that the returned sequence accesses the constructor parameters and the given iterator generator. 

Lastly, both the implementation of `Sequence.flatten` and the implementation of `TransformingSequence.flatten` rely on the following localization of `FlatteningSequence`.
```
internal local class FlatteningSequence<T, R, E>
constructor(
    private val sequence: Sequence<T>_{this},
    private val transformer: (T) ->_{this} R,
    private val iterator: (R) ->_{this} Iterator<E>_{this}
) : Sequence<E> {
    override fun iterator(): Iterator<E>_{this} = object : AbstractIterator<E> {
        val iterator = sequence.iterator()
        var itemIterator: Iterator<E>_{this}? = null
        
        override fun computeNext() {
            itemIterator?.let {
                if (it.hasNext()) {
                    setNext(it.next())
                    return
                }
            }
            itemIterator = null
            while (iterator.hasNext()) {
                val element = iterator.next()
                val nextItemIterator = iterator(transformer(element))
                if (nextItemIterator.hasNext()) {
                    itemIterator = nextItemIterator
                    setNext(nextItemIterator.next())
                    return
                }
            }
            done()
        }
    }
}
```
Here the changes are a combination of the above changes to `flatten` and to `TransformingSequence`.
The only outlier is that the type of the `itemIterator` field is restricted to have the `this` lifetime so that it can store the most recent object generated by the `iterator` constructor parameter.
The one invisible detail is that this localization relies on the fact that `AbstractIterator` was localized (which required nothing more than to declare it as a `local class`) so that the `object` can have a non-global lifetime.

That completes one of the most involved changes to the standard collection library.
Nonetheless, note that no *implementations* were changed!
All we did was update *signatures* to propagate lifetime information.

## Interesting Examples

The *vast* majority of localizations of functions in the standard library simply involved marking most of the parameters as `local` and sometimes restricting the returned lifetime to the intersection of these local parameters' lifetimes.
Similarly, localizations of classes (and interfaces) involved mostly marking the class as `local`, restricting the lifetimes of a few constructor parameters to the `this` lifetime, and restricting the lifetimes of some fields and method returns to the `this` lifetime.
But there were a few cases that were more interesting, some of which we highlight here.

### Reducing `crossinline`

There were a few parameters of `inline` functions that were labelled as `crossinline` that we were instead able to make `local`.
For example, the following is the original signature of `MutableList.sortBy`:
```
public inline fun <T, R : Comparable<R>> MutableList<T>.sortBy(crossinline selector: (T) -> R?): Unit
```
We were able to improve this to the following:
```
public inline fun <T, R : Comparable<R>> local MutableList<T>.sortBy(
    local selector: (T) -> R?
): Unit {
    if (size > 1) sortWith(compareBy(selector))
}
```

This change built on top of two other (standard-looking) localizations we were able to make:
```
public inline fun <T> compareBy(
    local selector: (T) -> Comparable<*>?
): Comparator<T>_{selector} =
    Comparator { a, b -> compareValuesBy(a, b, selector) }
    
public expect fun <T> local MutableList<T>.sortWith(
    local comparator: Comparator<in T>
): Unit
```

The key was being able to recognize that, although `compareBy` does not call its given `selector` *in place*, it only leaks it through the returned object.
Thus, since `sortBy` only hands that returned object to a function that only uses it locally, we know that `selector` is only accessed during the call to `sortBy`.

### Constructors

Often constructor parameters end up being stored inside the object.
We considered declaring such parameters as `local`, but this caused two problems.
One problem was that often these parameters are also fields (i.e. `val parameter: Type`), so what is the type of such a field given that `local` is not a type modifier?
By using `val parameter: Type_{this}`, it's clear what that type is.
But another problem is that sometimes the parameter *is* only used during the constructor and, as such, has no effect on the lifetime of the object.
If `local` constructor parameters were used for "capturing" parameters, then what syntax would we use for the non-capturing-but-local-to-the-constructor parameters?
These considerations are how we landed on our design for capturing parameters, and the following are a few examples of important constructors with non-capturing local parameters.

The constructor for `Array<T>` is
```
public inline constructor(size: Int, local init: (Int) -> T)
```

A constructor for `ArrayList<E>` is
```
public constructor(local elements: Collection<E>)
```

### Delegates

Kotlin supports delegated properties, where a field is accessed and/or mutated through some delegate object rather than directly.
Interestingly, because the delegated properties of an object are (by default) only accessible within that object's lifetime, it is safe for the delegate object to be restricted to have only that lifetime.
As such, because we can update the signature of `lazy` as follows, a `local` class can use `val foo: Foo by lazy {...}` where `...` executes within the `this` lifetime.
```
public expect fun <T> lazy(local initializer: () -> T): Lazy<T>_{initializer}
```
Similarly, local delegated properties in functions can use delegate objects with just the `local` lifetime, so the `...` in `val x: Int = lazy {...}` is free to access all `local` parameters and even do things like `return` from the function.

### Cleanup

A key pattern for ensuring resource cleanup in Kotlin is:
```
AutoCloseable {
   ...
}.use {
   ...
}
```

We are able to localize the signature for `AutoCloseable` as follows:
```
public expect inline fun AutoCloseable(local closeAction: () -> Unit): AutoCloseable_{closeAction}
```

As such, the code in `AutoCloseable {...}` is allowed to access `local` parameters and perform local control.
For example, it could return an `error` value if something were to go wrong while closing the resource.

### Views

A number of interfaces have properties/methods that provide restricted views of the value.
Some examples (in addition to `Iterable/Sequence.iterator`) are
* `CharSequence.subSequence`
* `List.subList`
* `Map.keys`
* `Map.values`
* `Map.entries`

In these cases, the type of the property/method was restricted to have the `this` lifetime.

### Strings

One important localization is the following:
```
public expect fun println(local message: Any?)
```
Among other things, making `message` be `local` allows developers to use `println`-debugging on limited-lifetime values.
While this is very useful, it is worth noting that it has one important implication: `String` objects cannot be allocated with a limited lifetime.
This is because `println` uses `Any.toString()`, which returns a `String` with a global lifetime, and `String` objects implement `toString()` by simply returning themselves.

This observation does not require any changes on the design; it simply means that `String` can never be declared as a `local class`.
It does, however, highlight that it is critical that methods of non-local classes be able to "leak" themselves.

### Restricting Type Parameters

Lifetime restrictions `_{...}` can be imposed on any type.
This includes type parameters.
(The theory for supporting this is achieved by modeling Kotlin-level types as higher-kinded low-level types with a contravariant locality parameter!)
The most common way this functionality is used by the standard library is by functions for performing an operation into a destination that is then returned, such as the following:
```
public fun <K, V, M : MutableMap<in K, in V>> local Array<out Pair<K, V>>.toMap(
    local destination: M
): M_{destination} =
    destination.apply { putAll(this@toMap) }
```
Here we want the return type to be the same as the (precise) type of the `destination` argument.
We achieve this by using a type parameter `M` as both the type of `destination` and the return type.
We also need to know that `destination` is at least an appropriate `MutableMap` in order to invoke `putAll` on it.
This is done by placing a bound on that type parameter: `M : MutableMap<in K, in V>`.
But that upper bound has a global lifetime, which is overly restrictive since we're performing the `putAll` in place.
So to incorporate locality, we make `destination` into a `local` parameter, and we restrict the return type to have the same lifetime as `destination`.
This trick ensures the return type has the precise type as the `destination` argument without limiting what lifetime that argument has (so long as it is at least within the `local` lifetime of the call).

### Variadic arguments

Variadic arguments, i.e. `vararg`s, are implemented in Kotlin using an array.
Currently this array is allocated on the heap, but often we can ensure that the array is only used locally.
In these cases, the array can be allocated on the stack instead.
The following eliminates the heap allocation from the varargs of `mapOf` (which itself allocates yet another data structure to then transfer all those values into):
```
public fun <K, V> mapOf(local vararg pairs: Pair<K, V>): Map<K, V> =
    if (pairs.size > 0) pairs.toMap(LinkedHashMap(mapCapacity(pairs.size))) else emptyMap()
```
(Note that this uses the above localization of `toMap`, taking advantage of the fact that even the receiver `Array` was marked as a `local` parameter.)

# Scala Capture Checking

Scala's experimental [capture-checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html) feature is closely related to local lifetimes.
However, there are some critical differences.
While some of these are rather technical, here we cover a couple with clear widespread implications for developers.

## Backwards Incompatibility

One difference is backwards compatibility.
Local lifetimes are designed so that, not only is all existing code still valid, but libraries can even be updated to integrate local lifetimes without breaking any existing clients. (Of course, libraries can also make incompatible changes if they want to force clients to use local lifetimes to get stronger guarantees.)
On the other hand, Scala capture-checking is designed to break existing code in an effort to rein in unintended side effects.
As an example, the following code is currently valid but becomes invalid with capture checking because the existing `=>` notation for functions becomes the notation for "impure" functions, whereas the existing notation for other type constructors becomes the notation for "pure" objects:
```
class Function[-I,+O](val apply: (I) => O)
def function[I,O](f: (I) => O): Function[I,O] = Function(f)
```

## Pseudotypes

Another difference is pseudotypes.
The design for local lifetimes emphasize that `local` modifies *function parameters* whereas `_{...}` modifies *types*.
As an example, the `local` keyword is placed before the function-parameter name rather than after so that it does not look like it is part of the parameter's type.
Scala, on the other hand, makes heavy use of pseudotypes, meaning notations that look like types but are not actually types.
One example is the `=>` notation.
This is described as denoting a function with "any" capabilities.
However, if that were really true, such a function could never be used because there is no execution environment that simultenously has access to all capabilities. (Consider, for example, the capability to return from a function call further up the stack, which is only accessible while within that stack before that call returns.)
As such, what "any" means depends on the context.
This causes code like the following to be rejected even though it would be clearly accepted if `=>` were a true type constructor:
```
var fun: () => Unit = () => ()
def setFun(f: () => Unit) = fun = f
```
The issue is that the use of `=>` in the declaration `fun` interprets "any" as all top-level capabilities, whereas the use of `=>` in the declaration of `f` in `setFun` interprets "any" as all capabilities accessible at the current call site to `setFun`.
The latter denotes a larger set of capabilities than the former, so the definition of `setFun` is rejected.

## Conclusion

These two issues alone seem to make Scala's capture-checking at odds with Kotlin's design and evolution norms.
While the former issue is arguably a matter of conventions that we could make a different choice on, the latter issue seems fundamental because the entire design is centered around the widespread use of the context-dependent set of "any" capabilities.

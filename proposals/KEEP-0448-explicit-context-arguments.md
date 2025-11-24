# Explicit context arguments

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Nikita Bobko, Kirill Rakhman
* **Discussion**: [#465](https://github.com/Kotlin/KEEP/discussions/465)
* **Status**: Experimental in 2.4

## Abstract

We propose an extension to calls to functions with 
[context parameters](./KEEP-0367-context-parameters.md),
so that context arguments can be given explicitly.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
* [Proposal](#proposal)
* [Alternative design choices](#alternative-design-choices)

## Motivation

In the original proposal for [context parameters](./KEEP-0367-context-parameters.md)
the only way to provide the values for context arguments is to do so
**implicitly**, via the new 
[context resolution](./KEEP-0367-context-parameters.md#extended-resolution-algorithm)
phase that happens as part of 
[overload resolution](https://kotlinlang.org/spec/overload-resolution.html#description).
In practical terms, this means that context arguments either comes from
context parameters declared in the signature, or from a call to `context`
(or a similar function) that introduces new implicit values in the context.

This schema has proven too rigid in practical scenarios. The first issue is
that even giving a single context arguments requires some **nesting**, because
of the call to `context`.

The second issue is the lack of **disambiguation** procedures between different
overloads of the same function. We remark here that the problem is not per se 
that we mark calls as ambiguous, but rather the developer has no easy recourse.

Take the following example,

```kotlin
context(theA: A) fun foo() { ... }
context(theB: B) fun foo() { ... }

context(oneA: A, oneB: B) fun bar() {
    foo()  // resolution ambiguity
}
```

The way to solve the ambiguity in that examples is to create an additional
(useless) function that fixes one of the overloads.

```kotlin
context(oneA: A) fun fooFromA() {
    foo()  // only the first one is applicable
}

context(oneA: A, oneB: B) fun bar() {
    fooFromA()
}
```

However, such a procedure does not scale, and there are situation in which
applying it becomes much harder (for example, if there are intermediate values,
they need to be captured).

## Proposal

We propose to alleviate those problems by introducing **explicit context
arguments**. In short, we can use named parameter syntax to explicitly bind
a value to a context parameter.

```kotlin
context(oneA: A, oneB: B) fun bar() {
    foo(theA = oneA)
}
```

This explicit syntax immediately helps with the nesting issue. Furthermore,
the name of the context parameter provides an additional source for
disambiguation.

**Resolution stage.** Explicit context arguments are resolved at the same
time as other named arguments to the function, _not_ during the context
resolution phase. This choice has consequences for type inference and overload
resolution, that may now use additional information from those arguments.

**Context parameters naming.** 
Context parameters whose name is declared as `_` may not be given as explicit
context argument. We strongly recommend to give names to context parameters,
and make them _unique_ among similar overloads, so the name can be used for
disambiguation purposes.

**More specific candidate.** The 
[most specific candidate rule](./KEEP-0367-context-parameters.md#extended-resolution-algorithm)
is updated. In contrast to implicitly-resolved context arguments,
explicit context arguments **do** participate in the selection of the most
specific candidate. This aligns with how the
[algorithm](https://kotlinlang.org/spec/overload-resolution.html#algorithm-of-msc-selection)
treats optional parameters, that is, they are only accounted for when
explicitly given.

Specifically, when we do step 1 of the
[most specific candidate selection algorithm](https://kotlinlang.org/spec/overload-resolution.html#algorithm-of-msc-selection), 

> For every non-default argument of the call and their corresponding
> declaration-site parameter types...

now handles not only regular value parameters, but also context parameters and
their declaration-site types whenever they are passed explicitly.

For example,

```kotlin
open class Parent
class Child : Parent()

context(x: Parent) fun foo() { ... }  // (1)
context(x: Child)  fun foo() { ... }  // (2)

fun test() {
    context(Child()) { 
        foo()  // ambiguity between (1) and (2)
    }

    foo(x = Child())  // resolves to (2)
}
```

Note that explicit context arguments may "compete" with regular value
arguments during most specific candidate selection.

```kotlin
open class Parent
class Child : Parent()

fun foo(x: Parent) { ... }  // (1)
context(x: Child) fun foo() { ... }  // (2)

fun test() {
    context(Child()) { 
        foo()  // resolves to (2) -- we miss arguments for (1)
    }

    foo(x = Child())  // resolves to (2) -- 'Child' is more specific than 'Parent'
}
```

**Contextual properties.** This proposal does not introduce syntax for
explicitly providing context arguments to contextual properties.

```kotlin
context(a: A) val message: String get() = ...

context(A()) { message }  // accepted
message(a = A())          // unresolved
```

We remark that the nesting in this case is required. If we used a property
reference instead, `context(A(), ::message)`, then the context argument is
eagerly resolved as explained in the
[callable references](./KEEP-0367-context-parameters.md#callable-references)
section of the original proposal.

**DSL marker.** Explicit context arguments are _not_ affected by the
applicability restrictions of the `DslMarker` annotation. This aligns with
the behavior for receivers, which also may overcome the restrictions by
explicitly giving them.

**Interaction with [named-only parameters](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0439-named-only-parameters.md).**
Although still in a proposal stage, Kotlin may introduce requirements for
using a parameter only in named form.

```kotlin
fun foo(named a: A) { ... }
```

Alas, this means that the usual disambiguation technique (using positional
arguments) may no longer be used here. There's no proposed solution at this
point, other than recommending library authors to not provide both versions.

## Alternative design choices

Some alternative choices were considered during the design.

**All-or-nothing.** The current proposal allows to given any amount of
explicit context arguments, with the rest of them being resolved in the
usual way. An alternative is to only allow this syntax when all of the
context arguments are given explicitly.

This model, however, makes it difficult to introduce new context parameters
into existing functions. What should be a simple additional `context` wrapper
or context parameter in the signature ends up requiring changes in every
place in which the context arguments are given explicitly.

Being more flexible also means the proposal covers an additional use case,
namely _slightly adjusting_ the context for a function, but without requiring
explicitly repeating all the others.

```kotlin
context(logger: Logger, service: ThingService)
fun doSomething() { ... }

context(logger: Logger, service: ThingService)
fun doSomethingBigger() {
    doSomething(logger = OnlyCritical(logger))
    // instead of requiring
    doSomething(logger = OnlyCritical(logger), service = service)
}
```

Finally, we think that a call in which only some context arguments are given
explicitly is not more difficult to understand than one in which all of them
are explicitly given. The intuitive model (those not explicitly given are
implicitly resolved) is still simple to understand.

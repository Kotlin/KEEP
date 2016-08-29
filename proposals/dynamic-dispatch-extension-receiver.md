# Dynamic Dispatch Extension Receiver

* **Type**: Design proposal
* **Author**: Johannes Neubauer
* **Status**: Shepherded
* **Shepherd**: [@dnpetrov](https://github.com/dnpetrov)
* **Prototype**: *not yet*

## Feedback 

Discussion has been started in [this pull request](https://github.com/Kotlin/KEEP/pull/35) regarding type-checking of reified type parameters so far. Further discussion should be done in [the pull request for this proposal](https://github.com/Kotlin/KEEP/pull/46). An issue in the KEEP project has to be opened.

## Summary

Support dynamic dispatch for the extension receiver of extension methods via a new keyword `dispatch`.

## Motivation

Extension methods are statically dispatched. Currently, dispatching has to be done manually (if necessary), which is boiler-plate and error-prone. Often this is the intended behavior and it is much faster than dynamic dispatch. Hence, adding dynamic dispatch as standard behavior is not an option, especially as the implicit extension receiver is used for dynamic dispatch of extension functions, if the extension method is defined on a class. Additionally, this would break a lot of existing code.

Instead a new keyword `dispatch` is proposed to be introduced, which can be added to the receiver of extension functions/methods. This proposal is intended to be the first in a row (iff successful) for adding dynamic dispatch (where explicitly defined) to function parameters, too.

## Description

The following example shows a possible syntax for dynamic dispatch for (explicit) extension receiver in action (for method overload):

```kotlin
interface A
interface B: A

// the parantheses are necessary because a future proposal could introduce the dispatch keyword for the complete function (including the parameter `a`) and this should not introduce source-breaking changes... 
fun (dispatch A).copyTo(a: A) {
  // do the copy stuff
}

fun (dispatch B).copyTo(b: B) {
  // should call `A.copyTo(A)`
  super.copyTo(b)
  // do the copy stuff for `B`
}

// more concrete implementations...

inline fun <reified T> T.copy(): T {
  val copy = T::class.java.getConstructor().newInstance()
  // this call is dispatched, because of the `dispatch` keyword in the implementations. 
  copyTo(copy)
  return copy
}

val b: A = B()
// Variable `bCopy` will be of type `A` as this is the type for which the inline function has been called.
// But the instance assigned to `bCopy` at runtime will be of type `B` because of dynamic dispatch.
val bCopy = b.copy()
```

If the repitition of the dispatch keyword is not preferable this could be done with a separate declaration:

```kotlin
// declaration of extension receiver dispatching for all extension methods named copyTo for type A and its subtypes
fun (dispatch A).copyTo
// such a declaration could also specify for which overloads it adds dynamic dispatch (although the dispatching is done for the receiver only).
fun <T: A> (dispatch T).copyTo(a: T)
// and of course you can add it to all extension methods of a type in a given scope (using modifiers private, ...).
fun (dispatch A).*

fun A.copyTo(a: A) {
  // do the copy stuff
}

fun B.copyTo(b: B) {
  // should call `A.copyTo(A)`
  super.copyTo(b)
  // do the copy stuff for `B`
}

// more concrete implementations...
// ...
```

## Open Questions

*Not yet.*

# DslMarker

* **Type**: Design specification
* **Author**: Alejandro Serrano
* **Contributors**: 

## Abstract

This document provides a specification of the behavior of the
[`DslMarker`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-dsl-marker/)
annotation.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Usage](#usage)
* [Marking](#marking)
* [Scope control](#scope-control)
* [Sources](#sources)

## Usage

`DslMarker` is an annotation used for
[scope control](https://kotlinlang.org/docs/type-safe-builders.html#scope-control-dslmarker).
In particular, it helps in scenarios in which lots of implicit values are
available, and choosing the wrong one may lead to mistakes. One example
is the [`kotlinx.html`](https://github.com/Kotlin/kotlinx.html) library,
in which nesting in source code represents the nesting structure of a HTML
document. The "current" node is represented as an implicit receivers, so
choosing any other than the innermost leads to ill-formed HTML.

```kotlin
body {
    div {
        a("https://kotlinlang.org") {
            target = ATarget.blank
            +"Main site"
        }
    }
}
```

`DslMarker` is a **meta-annotation**. That means that it is an annotation
which you apply to other annotations, each of them representing a different
**DSL marker**.

```kotlin
@DslMarker annotation class HtmlTagMarker
```

Different DSL markers do not interact with each other; scope control and
propagation is done for each one independently.

## Marking

In order to understand how scope control works with `DslMarker`, we first need
to understand how we decide whether a particular implicit value (implicit
receiver or context parameter) is **marked** with a DSL marker `M`. There are
four potential **sources** of markers.

**Marking through classifier declaration**. If an implicit value has type `T`,
an that type or any of its supertypes is annotated with the DSL marker `M`,
then the implicit value is marked with `M`.

```kotlin
@HtmlTagMarker open class Tag { ... }
open class HTMLTag(...) : Tag()
class HTML(...) : HTMLTag()
class BODY(...) : HTMLTag()

fun HTML.body(block : BODY.() -> Unit = {}) {
    // 'this@body' is marked with 'HtmlTagMarker'
    // because its supertype 'Tag' is annotated with it
}

fun HTML.userDetails(...) {
    // 'this@userDetails' is marked with 'HtmlTagMarker'
    // because its supertype 'Tag' is annotated with it
    body {
        // 'this@body' is marked with 'HtmlTagMarker'
        // because its supertype 'Tag' is annotated with it
    }
}
```

**Marking through type alias**. If an implicit value has type `T`, and that
type is a type alias annotated with the DSL marker `M`, then the implicit value
is marked with `M`.

```kotlin
@DslMarker annotation class AliasMarker

class A
@AliasMarker typealias B = A
typealias C = B

fun A.usesA() {
    // 'this@usesA' is not marked with 'AliasMarker'
}

fun B.usesB() {
    // 'this@usesB' is marked with 'AliasMarker'
}

fun C.usesC() {
    // 'this@usesC' is marked with 'AliasMarker'
    // since 'C' expands to a type alias with the marker
}
```

**Marking through type annotation**. If the type of the implicit value is
annotated with the DSL marker `M`, then the implicit value is marked with `M`.
This annotation may happen everywhere an annotation of a type is allowed,
including (context) parameter declarations or type arguments.

```kotlin
@DslMarker annotation class ExampleMarker

class A
class B
class C

fun foo(block: (@ExampleMarker A).() -> Unit) = ...

fun example1() = foo {
    // 'this@example1' is marked with 'ExampleMarker'
    // because it's directly annotated
}

context(a: @ExampleMarker A, b: B)
fun (@ExampleMarker C).example2() {
    // 'a' is marked with 'ExampleMarker'
    // 'b' has no DSL markers
    // 'this@example2' is marked with 'ExampleMarker'
}

fun example3() = with<@ExampleMarker A, _>(A()) {
    // 'this@with' is marked with 'ExampleMarker'
}
```

**Propagation through function types**. When the annotation is applied to
a function type, we propagate the DSL marker to all the implicit values
(context parameters and receivers).

```kotlin
fun bar(block: @ExampleMarker context(A) B.() -> Unit) = ...

fun example3() = bar {
    // context parameter of type 'A' is marked with 'ExampleMarker'
    // 'this@bar' is marked with 'ExampleMarker'
    // by propagation from the type of 'block'
}
```

Note the potential for confusion between annotating the function type and
the receiver type.

```kotlin
// only receiver is marked
fun quux1(block: context(A) (@ExampleMarker B).() -> Unit) = ...

// context parameter and receiver are marked
fun quux2(block: @ExampleMarker context(A) B.() -> Unit) = ...
// equivalent to
fun quux2(block: context(@ExampleMarker A) (@ExampleMarker B).() -> Unit) = ...
```

In fact, given the _no duplicate markers_ rule described below, introducing
more than one implicit value with the same marker makes the parameter almost
impossible to call.

## Scope control

`DslMarker` only affects **implicit binding**, that is, those scenarios in
which an implicit value is **not** directly specified by the developer, but
rather "chosen" by the compiler. Those scenarios include choosing an 
[implicit receiver](https://kotlinlang.org/spec/overload-resolution.html#call-without-an-explicit-receiver)
and [context parameter resolution](https://github.com/Kotlin/KEEP/blob/master/proposals/context-parameters.md#extended-resolution-algorithm).

> [!NOTE]
> Calls with an explicit receiver `e.f(...)` may involve implicit binding
> if the function `f` has both a dispatch and an extension receiver,
> or declares context parameters.

**The general rule**. Whenever an implicit value with DSL marker `M` is bound, 
there must not be another implicit value with the same DSL marker in the same 
or a closer scope.

We remark the **lazy** nature of the scope control mechanism of `DslMarker`.
If you never perform an operation which requires implicit binding, then **no**
error is raised. In particular,
explicit usages of implicit values are **not** covered by this restriction.
Those explicit usages include
[`this@label` expressions](https://kotlinlang.org/spec/expressions.html#this-expressions),
and access to [context parameters](https://github.com/Kotlin/KEEP/blob/master/proposals/context-parameters.md#declarations-with-context-parameters)
by their name.

From this general rule we can derive two main consequences.
To understand them, we use examples referencing the following declarations:

```kotlin
@DslMarker annotation class Marker

@Marker class A
@Marker class B
/* no @Marker! */ class C

fun A.callAReceiver() { }
fun B.callBReceiver() { }

context(a: A) fun callAContext() { }
context(b: B) fun callBContext() { }

context(a: A, b: B) fun callABContext() {}
```

**No binding to outer scopes**. Consider the innermost scope in which an
implicit value with DSL marker `M` lives. It is **not** allowed to bind
implicit values stemming from outer scopes.

In the example below only access to `B()` is granted; access to `A()` is
restricted because of the presence of `B()`. Note that the additional receiver
of type `C` does not play any role here, since it is not marked with a DSL
marker.

```kotlin
fun example() {
    with(A()) {
        with(B()) {
            with(C()) {
                callAReceiver()  // error
                callBReceiver()  // ok
                callAContext()   // error
                callBContext()   // ok
                callABContext()  // error
            }
        }
    }
}
```

The kind of implicit value (context parameter or receiver) does not play
a role in this rule. In the example below access to the receiver of type `A`
is restricted because of a context parameter with the same DSL marker.

```kotlin
fun example() {
    with(A()) {
        context(B()) {
            with(C()) {
                callAReceiver()  // error
                callAContext()   // error
            }
        }
    }
}
```

**No duplicate markers on the same scope**. If we implicitly bind a value
with marker `M`, and in the same scope there is another implicit value
marked with the same marker `M`, then we report a DSL violation.

In the examples below we introduce two implicit values with the same DSL
marker in the same scope: in the first one as two context parameters,
in the second one as a context parameter and a receiver. Note how any call
with implicit binding is forbidden, even if only one of the implicit values
are bound. The cases in which we explicitly give a receiver are accepted.

```kotlin
context(a: A, b: B) fun example1() {
    callAContext()   // error
    callBContext()   // error
    callABContext()  // error
}

context(a: A) fun B.example2() {
    callAContext()        // error
    callBReceiver()       // error
    a.callAReceiver()     // ok
    this.callBReceiver()  // ok
    callABContext()       // error
}
```

Note that before the introduction of context parameters there was no way to
reach this situation, since implicit receivers are always introduce once at
a time, and are completely ordered as a consequence.

**No further search**. If any of the potential candidates in a given scope
are rejected due to DSL scope violation, the search for candidates does
**not** proceed to outer scopes. In particular, if none of the candidate is
applicable, an error is reported.

This is especially visible in mixed receiver - context parameter scenarios,
since extensions and members take precedence over top-level functions.
In the example below `foo` is resolved to the extension to `A`, which violates
scope control because it binds a value in an outer scope than `B`. However,
search also stops there, so we report an error instead of attempting
resolution with `foo` where `B` is a context parameter.

```kotlin
fun A.foo() { }
context(b: B) fun foo() { }

fun A.bar() = context(B()) { 
    foo()  // error
}
```

## Sources

- Documentation about [`DslMarker`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-dsl-marker/)
- Documentation about [type-safe builders](https://kotlinlang.org/docs/type-safe-builders.html#scope-control-dslmarker)
- Source code for `CheckDslScopeViolation` ([snapshot for 2.2.0](https://github.com/JetBrains/kotlin/blob/2.2.0/compiler/fir/resolve/src/org/jetbrains/kotlin/fir/resolve/calls/stages/ResolutionStages.kt#L384))
- Tests for `DslMarker` ([snapshot for 2.2.0](https://github.com/JetBrains/kotlin/tree/2.2.0/compiler/testData/diagnostics/tests/resolve/dslMarker))
# Scope control for implicit receivers

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Contributors**: Ilya Ryzhenkov, Denis Zharkov
* **Status**: Implemented in 1.1-M03
* **Discussion**: [KEEP-57](https://github.com/Kotlin/KEEP/issues/57)

## Problem description

When we nest builders of different types into one another, members of the outermost implicit receiver are available in the innermost lambda (unless they are shadowed by something in inner lambdas):

``` kotlin
table {
    tr {
        tr {} // PROBLEM: Table.tr() is available here, 
              // because the implicit receiever of `table {}` is in scope
    }
}
```

Sometimes this effect is useful (the outermost receiver may provide some "global" services relevant to all necessary scopes, e.g. some context-like data), but often times it's problematic.

## References

- [KT-11551](https://youtrack.jetbrains.com/issue/KT-11551) limited scope for dsl writers

<!-- Pre-1.0 Design meeting notes: https://jetbrains.quip.com/ZG36ArNQbysO -->

## Available workarounds

Currently, the only way to mitigate this is to define a shadowing function for the inner receivers and mark it `@Deprecated(level = ERROR)`:
 
``` kotlin
@Deprecated("Wrong scope", level = DeprecationLevel.ERROR)
fun Tr.tr(body: Tr.() -> Unit) { }


table {
    tr {
        tr { } // Error: Using 'tr(Tr.() -> Unit): Unit' is an error. Wrong scope
    }
}
```

This approach has the following major disadvantages:
- it requires a lot of boilerplate to define all the deprecated functions from all possible outer levels,
- one can't anticipate all cases of DSL composition, so there's no way to know the complete set of functions to screen against,
- there's no way to know in advance all possible extensions defined for outer receivers, so screening for them is also impossible.  

## Proposed solutions

### DslMarker (the one currently implemented)

We introduce `DslMarker` annotation with the following definition:
```kotlin
@Target(ANNOTATION_CLASS)
@Retention(RUNTIME)
@MustBeDocumented
annotation class DslMarker
```

An annotation class `Ann` is called *a DSL marker* if it is annotated with the `@DslMarker` annotation.

The general rule:
* an implicit receiver may *belong to a DSL `@Ann`* if it's marked with a corresponding DSL marker annotation
* two implicit receivers of the same DSL are not accessible in the same scope
    * the closest one wins
    * other available receivers are resolved as usual, but if the resulting resolved call binds to such a receiver, it's a compilation error

Marking rules: an implicit receiver is considered marked with `@Ann` if
* its type is marked, or
* its type's classifier is marked
    * or any of its superclasses/superinterfaces

Additional notes
* Receivers can be accessed through `this@label` regardless of their marking
* Multiple markers on the same receivers:
    * If receivers tower looks like (2, 1+2, 1), we consider both 2 and 1+2 unavailable

### ScreenFrom (obsolete)

We propose to control availability of outer receivers inside a builder lambda through an annotation on the lambda parameter:

``` kotlin
fun Table.tr(@ScreenFrom(HtmlTag::class) body: Tr.() -> Unit) { 
    // ...
}
```

The `@ScreenFrom` annotation (name to be discussed) marks any lambda (even a plain lambda with no receiver), and its argument regulates which receivers to screen from:

``` kotlin
annotation class ScreenFrom(vararg val screenedReceivers: KClass<*>)
```

The `screenedReceivers` parameter contains a set of classes (should better be types, but we don't have a representation for those in an annotation ATM), and outer receivers of those classes (and their subclasses) are not available inside the annotated parameter. 

> **Question**: should this work uniformly for all implicit receivers (including ones defined by class, function or companion object), or be specialized only for receivers defined by lambdas?

An empty set of receivers may mean either
- screen from all (equivalent to `@ScreenFrom(Any::class)`)
- screen from nothing.

#### Terminology and Naming

The proposed technique requires a name. Possible options include:
- scope control for implicit receivers
- receiver/DSL isolation
- scope screening
- \<more options wanted\> 

Possible names for the annotation:
- ScreenFrom
- ScreenReceivers
- Isolated
- IsolatedScope
- IsolateReceiver
- IsolateFrom

#### Open questions

- Classes vs types in the annotation arguments: classes may be too imprecise. E.g. when all receivers are of type `Tag<something>`, `something` is the only thing that we can differentiate for, and classes can't express that.

> This is an important question, actually. We may well support types as arguments to annotations at some point, e.g. `@Ann(Foo<Bar>)` (syntax is questionable). So, if we implement this through classes now, we should have a clear transition path for introducing types later.
 
#### Arguments against this proposal
 
- Adding an annotation that affects name resolution severely (special syntax/modifier may be considered as an alternative)
  - We can consider implementing this as a "language extension", e.g. a compiler plugin 
- It doesn't cover cases like "screen from the immediate outer, but not the rest of the receivers"

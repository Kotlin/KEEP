# Inline for property accessors

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Status**: Under consideration
* **Prototype**: In progress

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/25).

## Summary

Support `inline` modifier for accessors of properties that don't have a backing field.

## Description

We propose to allow the `inline` modifier on properties that do not have a backing field.

It may be used on the sole accessor of a `val`:

``` kotlin
val foo: Foo
    inline get() = Foo()
```

Or one or both accessors of a `var`:

``` kotlin
var bar: Bar
    inline get() = ...
    inline set(v) { ... }
```

At the call site the accessors are inlined as normal functions.
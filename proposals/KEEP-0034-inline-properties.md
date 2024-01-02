# Inline for property accessors

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Status**: Accepted
* **Prototype**: Implemented

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/34).

## Summary

Support `inline` modifier for properties that don't have a backing field and accessors of such properties.

## Motivation

* Enable `@InlineOnly` properties whose signatures can be changed without affecting the binary compatiblity of a library
* Make reified type parameters available for property accessors

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

The `inline` modifier may also be used on the property itself:

``` kotlin
inline var bar: Bar
    get() = ...
    set(v) { ... }
```

In such a case, all accessors are marked `inline` automatically.

Applying `inline` to a property that has a backing field, or its accessor, results in a compile-time error.

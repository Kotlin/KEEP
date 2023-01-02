# Remove `name`, `ordinal`, and `compareTo` from external enums

* **Type**: Design proposal
* **Author**: Artem Kobzar
* **Status**: Under consideration 
* **Prototype**: Implemented
* **Related issues**: [KT-30810](https://youtrack.jetbrains.com/issue/KT-30810)

This proposal describes an attempt to fix `external enum` interop runtime error issues.

## Motivation

Kotlin has the ability to declare an [`external`](https://kotlinlang.org/docs/js-interop.html#external-modifier) enumeration which acts like a regular one but is supposed to be declared outside the Kotlin source code.

But, Kotlin enums are specific, because they contain synthetically generated fields (`name` and `ordinal`), and methods (`values` and `valueOf`). Also, every `enum` is a child class of the abstract `Enum` class (not attainable outside Kotlin code), which contains its implementation of the `compareTo` method based on the generated `ordinal` field.

As a result, developers are faced with the problem when they try to use objects defined in JS code (as an example) that act like an `enum` as an `external enum` and they got a runtime error when they use them with `enumValueOf` or they use external enum entries in some kind of sorted collections.


## Proposal

The proposal is simple: remove everything from external enums that which compiler can't generate, and keep and generate everything that it can:

* On the frontend side, interpret external enums as child classes of another abstract class called `ExternalEnum`, which doesn't contain `abstract` fields such as `name` and `ordinal`, and, also, doesn't implement `Comparable` interface.
* Overload `enumValues` and `enumValueOf` stdlib functions with a generic implementation based on reflection (for JS target).
* On the backend side, replace every `values` and `valueOf` static method call with the call of the overloaded version of `enumValues` and `enumValueOf` functions.
* Do not add `Enum#entries` to external enums on the frontend side.
* Provide an ability to declare explicitly that `external enum` should be interpreted as an `Enum` subclass for backward compatibility

### Interpret external enums as child classes of `ExternalEnum`

We can add another abstract class called `ExternalEnum` (it could be `external` too), which will be a new superclass for all declared external enums on the frontend side:

```
// Somewhere in stdlib
external abstract class ExternalEnum


// foo.kt
external enum class Foo // -> exterlan class [kind: enum] Foo: ExternalEnum()
```

Also, there should be the same frontend diagnostics as for `Enum` class, such as:
* User can't inherite the `ExternalEnum` class directly
* User can't declare a super class for `external enum class`

### Overload `enumValues` and `enumValueOf` functions

To save an abilitty to use `enumValues` and `enumValueOf` functions, we can generate them or declare a generic version of it (it is discussable).
For now [only for JS], I propose to declare inside the JS version of stdlib generic implementations like this:

```
inline fun <reified T: ExternalEnum> enumValueOf(name: String): T {
  val externalEnum = T::class.js
  return if (hasCustomValueOfImplementation(externalEnum)) {
      externalEnum.valueOf(name)
  } else {
      externalEnum[name]
  }
}

inline fun <reified T: ExternalEnum> enumValues(): Array<T> {
  val externalEnum: dynamic = T::class.js
  return if (hasCustomValuesImplementation(externalEnum)) {
      externalEnum.values()
  } else {
      js("Object.values(externalEnum)")
  }
}
```


### Replace `values` and `valueOf`

On the backend side, in the duration of the lowering phase, we can replace all `valueOf` and `values` synthetic methods calls with the described above `enumValueOf` and `enumValues`

### Explicit way to declare an `external enum` as an `Enum` subclass

Also, I think should be a way to save current behavior for backward compatibility or if an user declared the fields and methods on the JS side and want to have an `external enum` that acts like a Kotlin `enum`. There are a lot of ways to do it (it is discussable), but, in my implementation, I just made an exception for current diagnostics, and, gave the ability to declare `external enum` with `Enum` class in the hierarchy class list:
```
external enum class Foo            // -> exterlan class [kind: enum] Foo: ExternalEnum()
external enum class Foo: Enum<Foo> // -> exterlan class [kind: enum] Foo: Enum<Foo>()
```


## Risks and assumptions

The proposal has two main risks:

The first is broken backward compatibility because with this safe way to understand `external enums` users will get compilation errors in places that were valid for the compiler in previous versions.

The second risk is the education disturbance because there is a new confusing thing like `external enum` actually is not `Enum` anymore.

## Alternative solution

There is a more radical solution, just to ban the usage of `external` modifier with `enum` classes, and ask users to declare those "acts like enum" JS-objects as an `external` objects with fields they want to use in Kotlin.

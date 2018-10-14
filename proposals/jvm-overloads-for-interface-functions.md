# @JvmOverloads for interface functions

* **Type**: Design proposal
* **Author**: Adam Arold
* **Status**: Implemented in Kotlin 1.3
* **Discussion**: [KEEP-162](https://github.com/Kotlin/KEEP/issues/162)

 ## Summary
 Allow to use '@JvmOverloads' annotation on interface functions.
 
 ## Description
 Kotlin supports having default values for parameters in functions right now:
 
```kotlin
interface Drawable {

   fun drawOnto(surface: DrawSurface, position: Position = Position.zero())

}
```

This way the function can be called with either just the first, or both parameters supplied.

The problem is that Java doesn't support default values so it looks like a good idea to allow having `@JvmOverloads` on functions like this to improve interoperability with Java:

```kotiln
interface Drawable {

    @JvmOverloads
    fun drawOnto(surface: DrawSurface, position: Position = Position.zero())
}
```

Right now if I do this I get the following error:

> '@JvmOverloads' annotation cannot be used on interface methods

There is a workaround, but it involves some manual work:

```kotlin
interface Drawable {

    fun drawOnto(surface: DrawSurface) {
        drawOnto(surface, Position.defaultPosition())
    }

    fun drawOnto(surface: DrawSurface, position: Position)
}
```

I suggest allowing the usage of `@JvmOverloads` for interface functions.

 ## Related issues
 * [KT-12224](https://youtrack.jetbrains.com/issue/KT-12224) @JvmOverloads does not work in interfaces, causing an exception in compiler

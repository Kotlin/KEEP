# Opt-in requirements

* **Type**: Design proposal
* **Authors**: Alexander Udalov, Mikhail Glukhikh
* **Previously known as**: Experimental API support
* **Discussion and feedback**: [KEEP-95](https://github.com/Kotlin/KEEP/issues/95)

This proposal describes a mechanism that will allow library authors to provide API that requires explicit opt-in from their clients. The proposed mechanism makes it possible to declare that the API requires opt-in, and to opt in to that API at the call site. Without such explicit consent from the user, a warning or an error is reported on usages.

An example use case is experimental API which, although publicly released as a part of the library, may break at any moment and its usages will need to be recompiled. The ultimate goal is to allow library authors to release APIs earlier and more frequently without fear of the necessity to support an incorrectly designed API for a long time because of source and/or binary compatibility.

## Use cases

* **In the Kotlin standard library**
  * Annotations for type inference (`@Exact`, `@OnlyInputTypes`, etc.): we'd like to publish them, but don't want to commit to never changing semantics (see   [KT-13138](https://youtrack.jetbrains.com/issue/KT-13138), [KT-13198](https://youtrack.jetbrains.com/issue/KT-13198))
  * Bitwise operations for small integer types.
  * Certain features for kotlin-reflect, that we're not sure about (see [KT-15987](https://youtrack.jetbrains.com/issue/KT-15987), [KT-15992](https://youtrack.jetbrains.com/issue/KT-15992))
  * New functions that get added to the standard library (`@ExperimentalStdlibApi`) for public preview.
* **Other libraries**
  * Experimental declarations for public preview.
  * Internal declarations that should not be used outside of the library, but are `public` for some other reason.
  * Fragile or delicate APIs that need a lot of expertise to use and thus require an explicit opt-in.  

## Goals

* Clear **opt-in** required for all users whose code may be broken by changes to the API.
* **Granularity**: one should be able to mark a small part of an API so that only the users of that part are required to opt in.
* **Smooth graduation**: if the API doesn't need to be changed as soon as it *graduates* (i.e. no longer requires opt-in), just unmark it, and all clients remain binary compatible.
* **Garbage collection upon graduation**: when the API graduates, the opt-in flags/markers are highlighted as warnings (and subsequently, errors) so that clients know to remove them.

## Initial observations

* One solution for the experimental API use case would be to use whole packages for experimental declarations, e.g. `kotlin.experimental`, `kotlin.jvm.experimental`, ... However, this is not granular enough and smooth graduation is not possible, because even if the API was completely fine from the start, it's going to be moved to a non-experimental package eventually, which will break binary clients.

> This solution was originally used for coroutines, originally placing them into `kotlin.coroutines.experimental` package and is still used 
> for some bitwise operators in `kotlin.experimental` package. 

* A more natural solution would involve some explicit binary-retained annotation on each declaration. This way, the compiler can check each call and each class usage, and report a warning/error if that symbol requires opt-in but no consent to using it has been given by the user. However, just one annotation isn't enough, because we'd like to force the user to opt in to each API (= group of declarations) separately. This is why we propose a design below where each API declares its own marker annotation, which must be used at call sites.
    * We've explored the possibility of using a “string tag” argument instead of custom marker annotations (e.g. `@RequiresOptIn(“kotlin.ExperimentalTypeInference”) ...`) but discarded it because it's not as clean, involves more typing and reading, requires not to make typos, and complicates the implementation, especially in the IDE.
* There's a number of ways to express the opt-in to use an API, but a source-retained annotation (“local” opt-in) and a command line argument (“global” opt-in) would seem the most natural choices.

## Proposal

We propose to add the following declarations to the standard Kotlin library:

```kotlin
package kotlin

@Target(ANNOTATION_CLASS)
@Retention(BINARY)
annotation class RequiresOptIn(
    val message: String = "",
    val level: Level = Level.ERROR
) {
    enum class Level { WARNING, ERROR }
}

@Target(CLASS, PROPERTY, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, FUNCTION,
        PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION, FILE, TYPEALIAS)
@Retention(SOURCE)
annotation class OptIn(
    vararg val markerClass: KClass<out Annotation>
)
```

The `RequiresOptIn` annotation is applied to an annotation class, and it makes that class an **opt-in requirement marker**. There are two sets of use cases where markers are used:

1. If a declaration is annotated with the marker, it requires opt-in to that marker and can use other API *with that same marker* in its body.
2. If a declaration or an expression is annotated with `@OptIn(Marker::class)`, it can use other declarations that use the selected marker, but it does not require opt-in itself (its clients will not *have* to opt in).

The first option of usage of opt-in requirement markers is called a **propagating opt-in** (the annotation effectively causes propagation of the requirement), and the second — a **non-propagating opt-in**. The user is free to choose whichever option is preferable in each scenario.

Example:

```kotlin
// Library code:

@RequiresOptIn
annotation class ShinyNewAPI

// Class Foo requires opt-in with marker ShinyNewAPI
@ShinyNewAPI
class Foo { ... }

// Function bar requires opt-in with marker ShinyNewAPI
@ShinyNewAPI
fun Foo.bar() = ...

// Usage:

// Function shinyNewFeature() uses API marked with ShinyNewAPI
// and thus is also, conceptually, a part of ShinyNewAPI and so it is marked as such.
@ShinyNewAPI
fun shinyNewFeature() = Foo().bar()

// Function doSomething uses API marked with ShinyNewAPI and is also
// required to use the OptIn marker annotation. 
// Here, we choose a non-propagating opt-in, because the
// API is used in the function body and it should not concern our clients
@OptIn(ShinyNewAPI::class)
fun doSomething() {
    val foo = Foo()
    foo.bar()
}
```

Note that by opting into the API with the propagating opt-in, `shinyNewFeature` effectively requires opt-in itself (with the same marker annotation). 
In theory, we could distinguish initial introduction of the API and its propagating usages, but it would complicate the proposal a bit and there doesn't seem to be much value in doing that.

Both opt-in mechanisms allow to use the API for the selected markers anywhere in the parse tree lexically under the annotated element.

Using `OptIn` with annotations that are *not* opt-in requirement markers has no effect and yields a compilation warning. (Note that this must not be an error because user code should not break once an annotation is no longer an opt-in requirement marker.) Using `OptIn` with no arguments has no effect and yields a warning as well.

## Opt-in requirement message

When using an API that requires opt-in without the said opt-in, the compiler reports a warning or an error depending on the specified level. It's possible to specify a custom message in `RequiresOptIn`, that will be reported by the compiler. If no message is given (i.e. if `message` is empty), the compiler will report that the API is *experimental*:

```
test.kt:22:9: error: this declaration requires an explicit opt-in annotation marker to be used. Please read and understand the caveats in the documentation of `ShinyNewAPI` first.
If you are willing to use it, mark the usage with '@ShinyNewAPI' to propagate opt-in marker requirement to further users or with '@OptIn(ShinyNewAPI::class)' to hide this usage as an implementation detail.'
    foo.bar()
        ^
```

## Opt-in for whole modules

Annotating every usage of some API might quickly become annoying, especially for application modules, where the developer does not care about the clients of the code simply because application modules have no clients. In such cases, it'd be useful to have a module-wide switch to opt in to the API.

We introduce a new CLI argument to kotlinc, `-opt-in=org.foo.Ann`, where `org.foo.Ann` is a fully qualified name of the opt-in requirement marker, which enables the corresponding API for the module. It's as if the *whole module* was annotated with `@OptIn(org.foo.Ann::class)`.

Since it's not easy to encode arbitrary Kotlin expressions in the CLI arguments, and because opt-in requirement markers are used in the `-opt-in` argument, we **require all marker annotations to have no parameters**. The compiler will report an error otherwise.

The compiler will check the value of `-opt-in` in the same way it checks the argument of the `@OptIn` annotation. In particular, if any of the annotations mentioned in the `-opt-in` are deprecated, the compiler is going to report a warning or error, depending on the deprecation level.

In a previous version of this proposal, we discussed the possibility of introducing another argument, `-Xexperimental=org.foo.Ann`, to use the propagating opt-in on the whole module (i.e. mark the whole module as "experimental" in terms of that proposal). The implementation of that feature turned out to be unexpectedly complicated, and it wasn't widely used, so we've decided not to add it at this point.

## Opt-in requirement of RequiresOptIn/OptIn themselves

Annotations `RequiresOptIn` and `OptIn` are proposed to be added to the Kotlin standard library. Since we're not yet sure that this design is optimal, we would like to test it first, and see if we can finalize it. Therefore, we would like to keep this whole feature experimental itself, in the sense that we may change something incompatibly, and the client code must be aware of it.

Therefore, we will **require** each user of `RequiresOptIn` to provide at least one `-opt-in` compiler argument, which would mean that the user understands the risks of using this experimental functionality. It can be either `-opt-in=...` with any opt-in requirement marker, or the magic predefined argument `-opt-in=kotlin.RequiresOptIn` which doesn't allow using any API by itself, yet merely allows using `RequiresOptIn` and `OptIn` in the source code. Unless one of these arguments is provided, the compiler will report a warning on each usage of `RequiresOptIn` or `OptIn` (but not on usages of the markers!).

Besides, we will also prohibit any usages of `RequiresOptIn`, `OptIn` and markers that do not aim to make use of the functionality declared in this proposal. The goal is to minimize the number of binary compatibility problems of user-compiled code if we decide to change something incompatibly. For example, you won't be able to use these classes as types:

```kotlin
// Error! RequiresOptIn cannot be used as a type
fun get(e: RequiresOptIn) = ...
```

In particular, this means that:

1. `RequiresOptIn` and `OptIn` may only be used as annotations (but not as arguments to other annotations), as references in the import statement, or as qualifiers (to be able to access nested classes, e.g. `RequiresOptIn.Level`)
2. Markers may only be used as annotations, as references in the import statement, or as a left-hand side to `::class` literal in `OptIn` or `WasExperimental` (see below) arguments

## RequiresOptIn, experimental declarations in the standard library and SinceKotlin

For declarations in the standard library, as soon as a declaration is released, it'll have to be annotated with `@SinceKotlin(X)`, where X is the earliest version, since which there have been no incompatible changes to the declaration. However, the `-api-version` compatibility argument will have no knowledge of how that declaration looked before it was released, i.e. the declaration will not be visible with `-api-version Y` for `Y < X`, even if it was present in the version Y and the opt-in was given by the user.

We don't intend to solve this problem completely because this would require us to know how the declaration looked in each release before it finally graduated (remember that experimental declarations can undergo binary-incompatible changes). To fix this at least partially, we'll add an **internal** standard library annotation `WasExperimental`:

```kotlin
package kotlin

@Target(CLASS, PROPERTY, CONSTRUCTOR, FUNCTION, TYPEALIAS)
@Retention(BINARY)
internal annotation class WasExperimental(
    vararg val markerClass: KClass<out Annotation>
)
```

Usages of declarations annotated with `WasExperimental` are allowed even if the API version requirement is not satisfied, provided that the opt-in to all mentioned markers is given.

This feature allows us to release new standard library API in patch releases, further graduating it in a minor release. For example, suppose a function `foo` appears in the standard library as experimental in Kotlin 1.4.30. Since it's not yet graduated, it's **not** annotated with `SinceKotlin`:

```kotlin
// kotlin-stdlib 1.4.30

@ExperimentalStdlibAPI
fun foo(s: String) {}
```

where [`ExperimentalStdlibAPI`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-experimental-stdlib-api/index.html) is an opt-in requirement marker for experimental API in the standard library, introduced in Kotlin 1.3.40:

```kotlin
@RequiresOptIn
annotation class ExperimentalStdlibAPI
```

In Kotlin 1.5, the function is graduated (hence `SinceKotlin(“1.5”)`) and therefore is no longer annotated with `ExperimentalStdlibAPI`. To allow users to opt in to it on 1.4.30 however, we also annotate it with `WasExperimental` so that for example the CLI argument `-opt-in=ExperimentalStdlibAPI` would work. (Of course, it also makes it possible to use it on 1.4.0...1.4.29, where there was no such function and linkage errors would arise, but we explicitly decide not to solve this problem.)

```kotlin
// kotlin-stdlib 1.5

@WasExperimental(ExperimentalStdlibAPI::class)
@SinceKotlin("1.5")
fun foo(s: String) {}
```

## OptIn markers and overridden declarations

As a general rule, marker annotation should present either at base and overridden declarations together,
or at none of them. Using marker on overridden declaration only is forbidden (see `bar` example below)
because compiler usually cannot guarantee that exactly overridden function is called (see `base.bar` call).
Using marker on base declaration only provokes error or warning on overridden declaration, depending on marker `RequiresOptIn.level`.

```kotlin
open class Base {
    @ShinyNewAPI
    open fun foo() {}

    @ShinyNewAPI
    open fun foooo() {}

    open fun bar() {}

    @ShinyNewAPI
    open fun baz() {}
}

class Derived {
    @ShinyNewAPI
    override fun foo() {} // OK!

    @OptIn(ShinyNewAPI::class)
    override fun foooo() {} // OK!

    @ShinyNewAPI
    override fun bar() {} // ERROR! Base declaration isn't annotated

    // Overriding experimental declaration
    override fun baz() {} // ERROR/WARNING (depending on ShinyNewAPI level)
}

fun use(base: Base) {
    base.bar() // Is it experimental or not?
}
```

## OptIn marker contagiousness

In Kotlin 1.5.30 we introduced contagiousness rules based on type usages.
As a rule of thumb, all places which break if some experimental type disappear
from a library should receive opt-in usage warning/error, 
even if this experimental type is used implicitly.

Some class-based type is considered "requiring opt-in annotation marker" if its class requires opt-in marker,
and/or any of its type arguments requires opt-in marker.
For example, using `MyClass<MyArg>` requires opt-in marker if either `MyClass` or `MyArg` require this marker.
So class with opt-in marker annotation makes all types using it "requiring opt-in marker", 
as well as all types using its nested/inner classes.

Consider the following example

```Kotlin
@ShinyNewAPI
class Shiny

@OptIn(ShinyNewAPI::class)
fun foo(): Shiny = Shiny()

@OptIn(ShinyNewAPI::class)
fun bar(arg: Shiny = Shiny()) {}

@OptIn(ShinyNewAPI::class)
fun Shiny?.baz() {}

fun use() {
    // Three implicit usages of 'Shiny' type
    val s = foo()
    bar()
    null.baz()
}
```

All three calls `foo()`, `bar()` and `baz()` inside `fun use()` use `Shiny` type implicitly,
thus all three should receive opt-in usage warning/error. We can negate it using either `@ShinyNewAPI`
or `@OptIn(ShinyNewAPI::class)` at `fun use()` itself. 
Pay attention that `@OptIn(ShinyNewAPI::class)` at `foo`, `bar`, `baz` declarations does not help here.
To understand this better, rewrite your code like
```Kotlin
fun use() {
    val s: Shiny = foo()
    bar(Shiny())
    (null as Shiny?).baz()
}
```

## Limitations for marker annotations

* Targets `EXPRESSION`, `FILE` and `TYPE` are not possible for marker annotations, because these annotations operate on the declaration level, and these targets aren't declarations in Kotlin. The compiler will report an error on the marker annotation if it declares one of these targets.
* Target `TYPE_PARAMETER` is also forbidden for marker annotations. Type parameter can be used only in scope of owner class/function/property, so it makes no sense to declare it experimental.
* Although targets `VALUE_PARAMETER`, `LOCAL_VARIABLE` and `FIELD` aren't forbidden directly when a marker annotation is declared, they are forbidden at use-sites. Marker annotation can't be applied to local variable or value parameter because they can be used in local scope only. Also they can't be applied to property fields or getters, please apply them to owner property instead. However, property setter can be annotated separately to point out that writing to (but not reading from) this property requires opt-in. 
* Marker annotations must have `BINARY` or `RUNTIME` retention, otherwise the compiler will report an error. `SOURCE` retention is not enough because it wouldn't allow the compiler to read annotations from compiled code, and `RUNTIME` retention is not necessary because the fact that a declaration requires opt-in should not have any effect on how that declaration is visible at runtime through reflection.
* As mentioned earlier, marker annotations must have no parameters, otherwise the compiler will report an error.

## Known issues

* Once the API has been released, its call sites are still using the marker annotation, which means that the annotation class will need to go through the deprecation cycle, which is somewhat inconvenient.

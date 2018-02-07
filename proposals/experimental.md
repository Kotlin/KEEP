# Experimental API support

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/95).

This proposal describes a mechanism that will allow library authors to provide experimental (that is, unstable or unfinished) API to their clients. An API is called experimental if, although publicly released as a part of the library, it may break at any moment and its usages will need to be recompiled. The proposed mechanism makes it possible to declare that the API is experimental, and to say “I understand that I'm using an experimental declaration that may break at any time” at the call site. Without such explicit consent from the user, a warning or an error is reported on usages of experimental APIs. The ultimate goal is to allow library authors to release APIs earlier and more frequently without fear of the necessity to support an incorrectly designed API for a long time because of source and/or binary compatibility.

## Use cases in the standard Kotlin library

* Annotations for type inference (@Exact, @OnlyInputTypes, etc.): we'd like to publish them, but don't want to commit to never changing semantics (see [KT-13138](https://youtrack.jetbrains.com/issue/KT-13138), [KT-13198](https://youtrack.jetbrains.com/issue/KT-13198))
* Bitwise operations for integers
* Coroutines-related APIs, where we're still experimenting a lot
* Certain features for kotlin-reflect, that we're not sure about (see [KT-15987](https://youtrack.jetbrains.com/issue/KT-15987), [KT-15992](https://youtrack.jetbrains.com/issue/KT-15992))

## Goals

* Clear **opt-in** required for all users whose code may be broken by changes to the experimental API
* **Granularity**: one should be able to mark a small part of an API so that only the users of that part are required to opt-in
* **Smooth graduation**: if the API doesn't need to be changed as soon as it's released, just unmark it, and all clients remain binary compatible
* **Garbage collection upon graduation**: when the API graduates, the opt-in flags/markers are highlighted as warnings (and subsequently, errors) so that clients know to remove them

## Initial observations

* One solution would be to use whole packages for experimental declarations, e.g. `kotlin.experimental`, `kotlin.jvm.experimental`, ... However, this is not granular enough and smooth graduation is not possible, because even if the API was completely fine from the start, it's going to be moved to a non-experimental package eventually, which will break binary clients.
* A more natural solution would involve some explicit binary-retained annotation on each experimental declaration. This way, the compiler can check each call and each class usage, and report a warning/error if that symbol is experimental but no consent to using it has been given by the user. However, just one annotation isn't enough, because we'd like to force the user to opt-in to each experimental API (= group of declarations) separately. This is why we propose a design below where each experimental API declares its own marker annotation, which must be used at call sites.
    * We've explored the possibility of using a “string tag” argument instead of custom marker annotations (e.g. `@Experimental(“kotlin.ExperimentalTypeInference”) ...`) but discarded it because it's not as clean, involves more typing and reading, requires not to make typos, and complicates the implementation, especially in the IDE.
* There's a number of ways to express the opt-in to use an experimental API, but a source-retained annotation (“local” opt-in) and a command line argument (“global” opt-in) would seem the most natural choices.
* Most experimental declarations must be “poisonous” in the sense that the requirement of the consent to use them must propagate up the call chain, because even indirect users of an experimental API should be aware of the fact that they're using something that may break at any moment. There are certain “compile-only” declarations though, such as annotations, type aliases, inline-only functions, for which it's safe to not require propagation in the executable code (such as function bodies). We'd like to differentiate these two types of experimental APIs.

## Proposal

We propose to add the following declarations to the standard Kotlin library:

```kotlin
package kotlin

@Target(ANNOTATION_CLASS)
@Target(BINARY)
annotation class Experimental(
    val level: Level = Level.ERROR,
    val changesMayBreak: Array<Impact> = [Impact.COMPILATION, Impact.LINKAGE, Impact.RUNTIME]
) {
    enum class Level { WARNING, ERROR }
    enum class Impact { COMPILATION, LINKAGE, RUNTIME }
}

@Target(CLASS, PROPERTY, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, FUNCTION,
        PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION, FILE)
@Retention(SOURCE)
annotation class UseExperimental(
    vararg val markerClass: KClass<out Annotation>
)
```

The `Experimental` annotation is applied to an annotation class and it makes that class an **experimental API marker**. That marker can be further applied to the relevant API to make it experimental, i.e. to require the user to opt-in to the experimental behavior at the call site, and propagate that requirement further.

The `UseExperimental` annotation allows the user to **opt-in** to the experimental API **without propagating** it to its call sites. `UseExperimental` can only be used in limited circumstances and is discussed later in this proposal.

Example:

```kotlin
// Library code:

@Experimental
annotation class ShinyNewAPI

@ShinyNewAPI
class Foo { ... }

@ShinyNewAPI
fun Foo.bar() = ...

// Usage:

@ShinyNewAPI
fun useFoo(foo: Foo) {
    foo.bar()
}
```

Here `useFoo` uses experimental API (marked with `@ShinyNewAPI`) and thus must explicitly opt-in to that usage, allowing the author of that API to change it bypassing the standard deprecation cycle. Because `ShinyNewAPI` may break at runtime (`changesMayBreak` contains `RUNTIME`), `useFoo` must **propagate** the requirement to opt-in further to its clients. Therefore `useFoo` is annotated with `@ShinyNewAPI` itself.

Note that by using the “poisoned” experimental API, `useFoo` effectively becomes experimental API itself (with the same marker). In theory, we could distinguish initial introduction of the experimental API and its propagating usages, but it would complicate the proposal a bit and there doesn't seem to be much value in doing that.

## Signature and body usages

It's important to distinguish two types of usages of experimental API, which propagate differently according to the impact of the marker annotation.

* A **signature usage** is a usage in the publicly accessible declaration signature: parameter types, return types, supertypes, type parameter bounds, annotations on any of those elements, etc.
* A **body usage** is a usage in the executable code, i.e. in the function/constructor body or in the property initializer

Examples:

```kotlin
@ShinyNewAPI
open class Foo { ... }

fun useFoo(foo: Foo) {         // signature usage
    Foo()                      // body usage
    fun local(): Foo? = null   // body usage
}

class UseFoo<T : Foo>(         // signature usage
    val foo: List<Foo>,        // signature usage
    val obj: Any? = Foo()      // body usage (!)
) : Foo() {                    // signature usage
    ...
}
```

More specifically, a body usage is one of these (and signature usages are all except these):

* in the function/constructor/property accessor body
* in the property initializer or delegate expression
* in the class initializer (the `init` block)
* in the default argument value expression
* in the argument to the supertype
* in the expression for inheritance by delegation

## Same module exemption

There's a notable exception from the requirement on experimental API to poison all its indirect usages: **a body usage in the same module does not require propagation**. Example:

```kotlin
// Library code:

@ShinyNewAPI
fun foo() = ...

// Body usage in the same module: it's OK that bar is not annotated with @ShinyNewAPI
fun bar() {
    foo()
}
```

The rationale is that since the usage is compiled by the same author as the declaration, and even as a part of the same build, the author can make sure that any changes to `foo` that may break its clients will be handled properly in `foo`'s module. The fact that `bar` uses `foo` internally is its implementation detail, and whether `bar` is experimental itself is completely independent of the fact that `foo` is experimental.

If `foo` is used *outside* the module where the experimental API is declared, its body usages will require propagation:

```kotlin
// Usage:

@ShinyNewAPI
fun useFoo() {
    // Experimental API from other module is used,
    // thus "useFoo" is annotated with @ShinyNewAPI
    foo()
}

@ShinyNewAPI
fun useIndirectly() {
    // Even though "useFoo" is declared in the same module,
    // its experimental aspect comes with ShinyNewAPI from another module
    // and therefore "useIndirectly" must also be annotated with @ShinyNewAPI
    useFoo()
}
```

There's an exception for this rule: a usage in the same module **requires propagation, if it's inside an effectively public inline function body**:

```kotlin
// Library code:

@ShinyNewAPI
fun foo() = ...

// Body usage in the same module, but bar is inline! Propagation is required
@ShinyNewAPI
inline fun bar() {
    foo()
}
```

Otherwise it would be possible to break binary clients from other modules, who did not opt-in to use the experimental API and who still ended up using it because the usage of that API has been inlined into their code.

## Compile-time experimental API and @UseExperimental

There are certain declarations which must not require propagation across the entire call chain, for they're “compile-time features” and thus the code using them will not break even if their runtime properties change incompatibly. These include:

* Annotations, as long as they're used to annotate something, not as types
* Type aliases
* @InlineOnly functions

Example:

```kotlin
// Library code:

@Experimental
annotation class EnhancedCollections

@EnhancedCollections
annotation class NonEmpty

@EnhancedCollections
fun <T> getList(...): List<@NonEmpty T> = ...

// Usage:

fun useNonEmpty() {
    val list = getList<String>(...)  // error: experimental API is used
}
```

Here, `useNonEmpty` calls `getList`, which is using the experimental `NonEmpty` annotation, marked by `@EnhancedCollections`. Therefore, `useNonEmpty` must opt-in to use that experimental API. However, if `NonEmpty` is always used as an annotation, any incompatible changes in it can only affect the compile time, and not the runtime. Thus, it wouldn't be wise to require to propagate the requirement to opt-in to using this API to clients of `useNonEmpty`, who wouldn't want to have anything to do with the fact that `useNonEmpty`'s implementation uses a declaration which may break its compilation (but not the behavior!) in the future.

To mitigate this, we allow to declare the impact of the experimental API marker to be `COMPILATION`, making it a **compile-time experimental API**. Body usages of compile-time experimental API do not require propagation of the experimental marker up the call chain. However, they still require an explicit consent from the user. To express that consent, we use `UseExperimental`, passing the related marker classes as `::class` literals:

```kotlin
// Library code:

// EnhancedCollections now marks a compile-time experimental API
@Experimental(changesMayBreak = [Experimental.Impact.COMPILATION])
annotation class EnhancedCollections

...

// Usage:

@UseExperimental(EnhancedCollections::class)
fun useNonEmpty() {
    val list = getList<String>(...)  // OK
}
```

`UseExperimental` allows to use the API for the selected markers anywhere lexically below the parse tree.

Using `UseExperimental` with annotations that are *not* experimental API markers has no effect and yields a compilation warning. Using `UseExperimental` with no arguments has no effect and yields a warning as well.

If one of the markers used in `UseExperimental` does not denote a compile-time experimental API, this is a compilation error.

## Linkage and runtime impact

Besides `COMPILATION`, changes to the experimental API can impact `LINKAGE` and `RUNTIME`. These two mean different things in general:

* `LINKAGE` means that changes may cause exceptions related to the fact that the client code was not recompiled after the changes. Note that `LINKAGE` does not imply `COMPILATION` in general: for example, adding a function parameter with a default value will not (mostly) break compilation, but will break with linkage error at runtime if the code is not recompiled on JVM.
* `RUNTIME` means that the contract or the observed runtime behavior of the experimental API may change, including the possibility of throwing exceptions.

In the prototype, these two impacts are checked exactly in the same way in the compiler, i.e. they require propagation according to the rules specified in this proposal. We may provide additional diagnostics in the future for these two different cases.

## Propagation and opt-in for whole modules

Annotating every usage of an experimental API might quickly become annoying, especially for application modules, where the developer does not care about the clients of the code simply because application modules have no clients. In such cases, it'd be useful to have a module-wide switch to turn on (and propagate, if needed) the used experimental API.

We introduce two CLI arguments to kotlinc, which mirror the two ways to enable the experimental API:

1. `-Xexperimental=org.foo.Ann`, where `org.foo.Ann` is a fully qualified name of the experimental API marker, enables and propagates the corresponding API for the module. It's as if the *whole module* was annotated with `@org.foo.Ann`
2. `-Xuse-experimental=org.foo.Ann` enables the corresponding compile-time experimental API for the module. It's as if the *whole module* was annotated with `@UseExperimental(org.foo.Ann::class)`

We recognize that a better way to handle this would be to introduce some kind of module annotations in the language, however the design of that feature is far from obvious and until it's done, we're proposing this quick intermediate solution instead.

Since it's not easy to encode arbitrary Kotlin expressions in the CLI arguments, and because experimental API markers are used in the `-Xexperimental` argument, we **require all marker annotations to have no parameters**. The compiler will report an error otherwise.

Note that using `-Xexperimental=org.foo.Ann` during the compilation will make the whole module “annotated” with the given experimental marker, i.e. all declarations in that module are going to be considered experimental with that marker. On JVM, this information is going to be stored in the `.kotlin_module` file. Upon checking call sites for experimental API usage, the Kotlin compiler is going to look not only at annotations on the declaration and its containing declarations, but also at the `.kotlin_module` of the containing module.

If any of the annotations mentioned in the `-Xexperimental`/`-Xuse-experimental` are deprecated, the compiler is going to report a warning or error, depending on the deprecation level.

The compiler will check the value of `-Xuse-experimental` in the same way it checks the argument of the `@UseExperimental` annotation.

## Specific propagation rules

* If an open/abstract declaration is experimental (annotated with a marker), its overrides must also be experimental with that marker.
* If the right-hand side of a type alias includes an experimental type, the type alias declaration itself must also be experimental.
* If a data class property is experimental, the corresponding `componentN()` function is also implicitly considered experimental.
* ... (TODO)

## Other observations


Certain limitations for marker annotations arise:

* Targets `EXPRESSION` and `FILE` are not possible for marker annotations, because these annotations operate on the declaration level, and neither expressions nor files are declarations in Kotlin. The compiler will report an error on the marker annotation if it declares one of these targets.
* Marker annotations must have `BINARY` retention, otherwise the compiler will report an error. `SOURCE` retention is not enough because it wouldn't allow the compiler to read annotations from compiled code, and `RUNTIME` retention is not necessary because the fact that a declaration is experimental should not have any effect on how that declaration is visible at runtime through reflection.
* As mentioned earlier, marker annotations must have no parameters, otherwise the compiler will report an error.

Annotations `Experimental` and `UseExperimental` and related enums are proposed to be added to the standard library in Kotlin 1.3. Since we're not yet sure that this design is optimal, we would like to test it first, and see if we can finalize it. Therefore, we would like to keep this whole feature experimental itself, in the sense that we may change something incompatibly, and the client code must be aware of it. We've tried to think of the possibility to annotate the new declarations with `@Experimental` themselves, but, however entertaining that is, it suffers from an endless recursion, the ways of breaking which are not very elegant.

Thus, all new declarations are simply going to be annotated with `@SinceKotlin(“1.3”)`. To accept their instability, a library author should switch to an unstable language version. If we're not sure that the design is optimal at the time of release of 1.3, we're going to simply advance the version to `@SinceKotlin(“1.4”)`, and come back to it in the 1.4 timeframe.

## Known issues

* Once the API has been released, its call sites are still using the marker annotation, which means that the annotation class will need to go through the deprecation cycle, which is somewhat inconvenient.
* For declarations in the standard library, as soon as a declaration is released, it'll have to be annotated with `@SinceKotlin(X)`, where X is the earliest version, since which there have been no incompatible changes to the declaration. However, the `-api-version` compatibility argument will have no knowledge of how that declaration looked before it was released, i.e. the declaration will not be visible with `-api-version Y` for `Y < X`, even if it was present in the version Y. This is the first case when the `-api-version` option cannot provide perfect compatibility with the selected library version.





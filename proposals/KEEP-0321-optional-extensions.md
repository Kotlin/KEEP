# `java.util.Optional` Extensions

* **Type**: Standard Library API proposal
* **Author**: Kevin Bierhoff
* **Contributors**: David Baker, Jeffrey van Gogh
* **Status**: Experimental since Kotlin 1.7.0
* **Related issues**: [KT-50484](https://youtrack.jetbrains.com/issue/KT-50484)
* **Prototype**: https://github.com/JetBrains/kotlin/pull/4737
* **Discussion**: [KEEP-321](https://github.com/Kotlin/KEEP/issues/321)

## Summary

Convenience functions for working with `java.util.Optional` to simplify Kotlin-Java interop.

## Use cases

Main use case for this proposal is Kotlin code that interops with Java code that uses `Optional`s, which includes the following scenarios:

1. Kotlin code calling functions defined in Java that return an `Optional` or expect `Optional`-typed method parameters,
2. overriding such functions in Kotlin,
3. Kotlin code dealing with `Optional`-typed fields,

Between these scenarios it can be necessary to *construct*, *unwrap*, *convert*, or *transform* `Optional` objects in Kotlin.
We assume that the first scenario will be by far the most common one in most code bases.
Moreover, `Optional` use in return values will typically be more common than in method parameters.

It follows that **in Kotlin**:

* **Unwrapping** will likely be the most common operation to be perfomed,
  typically to handle return values from methods defined in Java.
* **Conversions** of the `Optional` into other data structures, and in particular empty or singleton collections,
  will be a related common need.
* **Constructing** `Optional`s can be common as well, but less common than unwrapping.
* **Transformations** of one `Optional` into another will be comparatively uncommon.

Commonly, Java APIs use `Optional` to avoid reliance on `null` values.
This is most obviously done in method returns (which is the use case called out in the class's documentation), for instance:

```java
public Optional<User> getLoggedInUser() {...}
```

The same can be done for method and constructor parameters, chiefly to indicate their optionality:

```java
public void createUser(String name, Optional<Image> logo) {...}
```

Note a single method can have both `Optional` parameters and an `Optional` return.
Also note that its documentation asks that `Optional`-typed variables never be `null`,
and while it's technically possible, it's indeed an exceedingly rare practice to see `Optional`-typed `null` values.

There are JDK classes that return `Optional`s, such as streams, but the ones that exist to date are rare to be used from Kotlin.
Thus the need for handling `Optional`s in Kotlin can vary widely between code bases, depending on the Java code being used.

In Kotlin, it's idiomatic to use nullable types (`User?`) instead of `Optional<User>` etc.
This means that when shuffling values between logic implemented in Java and in Kotlin,
it'll typically be necessary to wrap and unwrap `Optional`s as needed at boundaries.

## Proposed API

Based on this analysis, we propose the following extensions be added to the JDK8 portion of the Kotlin standard library.
They're aimed at facilitating common use cases discussed above to start; possible future enhancements are discussed afterwards.

### Unwrapping

* `Optional<T>.getOrNull(): T?`: directly unwraps an `Optional<T>` to `T?`
* `Optional<T>.getOrDefault(x: T): T`: returns the `Optional`'s value or the given value
* `Optional<T>.getOrElse { ... }: T`: returns the `Optional`'s value or the result of the given closure

These follow the Kotlin standard library's conventions for naming and available alternatives.

With these, we can conveniently use the `getLoggedInUser()` function from the previous section
from Kotlin (also relying on property syntax in this case):

```kotlin
loggedInUser.getOrNull()?.let {...}
loggedInUser.getOrDefault(GUEST)
loggedInUser.getOrElse { loginRedirect() }
loggedInUser.getOrElse { throw IllegalStateException() }
```

`getOrNull` in particular makes Kotlin's existing features for handling nullable values reusable:
`?.`, `?:`, and common idioms such as `?.let {...}` and `takeIf {...}` become directly applicable as shown.

### Conversions

We propose to include the following conversions to collections containing at most 1 non-`null` element:

* `Optional<T>.toList(): List<T>`
* `Optional<T>.toSet(): Set<T>`
* `Optional<T>.asSequence(): Sequence<T>`

These allow reusing Kotlin's excellent support for collections with `Optional` values.
Note that comparable logic written by hand, while reasonably short, would still be repetitive and hard to read.

For instance, assuming a Java method `Optional<User> findUser(String email)`, this allows:

```kotlin
val found = findUser(primaryEmail).toSet() + findUser(backupEmail).toSet()
found.filter { it.username != loggedInUser.getOrNull()?.username }.forEach {...}
```

As in the example above, these conversions enable using many of the operators defined for Kotlin collections,
such as `+` in the example above, without defining operators for `Optional`s themselves.

In a sense, these functions are similar to
[`listOfNotNull`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/list-of-not-null.html).
They also facilitate treating `Optional`s as collections without doing so directly
as Scala's [Option](https://www.scala-lang.org/api/2.13.6/scala/Option.html) does (details below).

### Construction

> **Update:** after a discussion, this section has been excluded from this proposal.

For completeness, we propose including the following alternatives to `Optional`'s factory methods:

* `optionalOf<T>(x)`: Creates `Optional` for the given value
  * Separate overloads for non-null and nullable arguments.
* `optionalOf<T>()`: Creates an empty `Optional`.

These functions for instance allow calling `createUser` from above:

```kotlin
createUser(username, optionalOf(logoOrNull))
createUser(username, optionalOf())
```

These are more consistent with Kotlin's conventions than `Optional.[of,ofNullable,empty]`.
Constructing optionals, as discussed above, is also a common operation that can benefit from brevity.
Finally, by providing multiple overloads for `optionalOf`, we can automatically choose between `Optional.of()`,
which only accepts non-`null` values, `Optional.ofNullable()`, and `Optional.empty()`,
alleviating users from having to choose between `Optional`'s 3 static methods for each use.

Note: We avoid the alternative framing as extension function, `x.toOptional()`, as it would pollute the global namespace.
It would also allow the probably unwanted `x?.toOptional()`.

## Similar API review

[`java.util.Optional`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html)
can be most directly compared to nullable references in Kotlin and other nullness-aware languages.
In fact, that's what it would likely compile to if `Optional` was a value type, and indeed what
it is [in Swift](https://developer.apple.com/documentation/swift/optional).
Other similar datatypes include:

* Scala's [Option](https://www.scala-lang.org/api/2.13.6/scala/Option.html)
* Guava's [`com.google.common.base.Optional`](https://guava.dev/releases/23.0/api/docs/com/google/common/base/Optional.html)

Besides comparing them to nullable references, another way of looking at `Optional`s is by likening them to
immutable collections--and/or tuples--containing at most 1 element of non-`null` type.
Scala's [Option](https://www.scala-lang.org/api/2.13.6/scala/Option.html) implements interfaces for both
(i.e., iteration and tuple access) and provides many functions usually defined for collections, such as
`contains`, `map`, `fold`, and many more.

One could also think of `Optional`'s as 1-tuples (or 1-element lists) of nullable type.
Despite Scala's precedence, none of these associations appear to be widely held among Java developers,
not least since neither `java.util`'s nor Guava's `Optional`s appeal to them.

## Alternatives

### Do nothing: relying on `java.util.Optional`'s own API

`java.util.Optional` comes with a variety of static and member functions that can be used from Kotlin,
but using them has drawbacks in several areas:

* Unwrapping: most commonly, Kotlin users would resort to `optional.orElse(null)?....`,
  which is verbose, includes a boilerplate value (`null`), and has platform type,
  meaning kotlinc won't force a null check on the result.

  There are also `orElseGet()` and `orElseThrow()` which accept closures to compute an
  alternative value or exception to throw, respectively. In Kotlin, `orElseThrow`
  is unnecessary, and the overhead of allocating closures for `orElseGet` is avoidable.

* Creating optionals: while `Optional`'s static functions are straightforward to use,
  they don't fit into the Kotlin standard library's pattern for constructing containers
  (`listOf`, etc.). `kolinc` makes sure `Optional.of` isn't called with `null`;
  however, it's mildly unfortunate that `Optional.ofNullable` can be called with
  values the compiler knows to be non-`null` values.

* Testing optionals: `Optional`'s `isPresent()` and `isEmpty()` functions can be invoked
  using property syntax in Kotlin. Given Kotlin's first-class treatment of nullable values,
  it'll usually be preferable to unwrap optionals instead of testing them, however.

* Transforming optionals: `java.util.Optional` defines collection-like transformations
  including `ifPresent`, `map`, `flatMap`, and `filter`. The first one is evaluated for
  its side effect; the remaining ones return `Optional`s. As such, these seem rarely useful
  in Kotlin, though they do work (with the overhead of allocating closures).

* Streaming optionals: JDK9 introduced a `stream()` function that can be coerced to a Kotlin
  `Sequence` using the existing `stream.asSequence()` extension, but is verbose and inefficient
  (and requires JDK9+).

The extensions proposed above address these shortcomings.

### Compiler-managed coercions

The compiler could automatically treat `Optional<T>` as `T?`, wrapping and unwrapping as needed.
We rejected this approach for a number of reasons:

* Obfuscating: this approach would hide function calls and allocations that will happen at runtime.

* `null` `Optional`s: While [discouraged](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html),
  `Optional` references can themselves be `null`.
  The compiler would have to conservatively handle this case, which would presumably make `null` and `Optional.empty()` indistinguishable,
  or allow the possibility of NPEs.

* Only sometimes needed: `Optional`s aren't as ubiquitously used in Java as other features
  which enjoy special treatment in `kotlinc` (e.g., getters and setters).

* It would be hard to generalize this approach to Guava's `Optional` or other similar library classes.

### Compiler plugin

A compiler plugin could generate bridge methods with systematically derived names that replace `Optional<T>` with `T?` parameters
and return types wherever Kotlin code calls such methods.
This avoids some of the issues with direct compiler support, but creates others:

* Tooling complexity: projects/repos would have to opt into using the plugin, and build systems would have to provide a way of doing so.
  The plugin would also have to be released as a separate artifact, ideally included in `kotlinc` distributions.

* Depending on the details, users may need to be aware of the mechanics and explicitly
  reference the generated methods, e.g., with a special name suffix.
  This could also be needed so that the original methods can also still be invoked if desired.

* Arguably creates a Kotlin "dialect": when reading Kotlin, users need to be aware whether the plugin is in use.

* Doesn't help in some scenarios, notably, when needing to override/implement methods defined in Java.

While this alternative has a lot of appeal, it has a lot more moving parts that don't seem
to provide commensurate additional benefit.

### `kotlin.Optional`

A built-in `kotlin.Optional` would be redundant to nullable types.
Hypothetically, a Swift-like approach that treats nullable types as a shorthand for optionality appears possible;
however in Kotlin/JVM we'd then still need coercions to and from `java.util.Optional`.
Note that if `java.util.Optional` ever becomes a value type, the extensions proposed for creating and unwrapping optionals become no-ops.

## Dependencies

What are the dependencies of the proposed API:

* `java.util.Optional` was introduced in JDK8 and received modest additions in subsequent JDK versions.

## Placement

* JDK8 standard library (which already contains extensions for streams).
* Package: `kotlin.jvm.optionals`

## Reference implementation

* See https://github.com/JetBrains/kotlin/pull/4737

## Unresolved questions

* Is it too clever to have two `optionalOf` overloads corresponding to `Optional.of,ofNullable`?
  * `of` can fail if `null` flows into it at runtime despite non-null argument type
  * Alternative would be a single overload corresponding to `ofNullable`

## Future advancements

* It would be nice to forbid nonsensical nullable type arguments for `Optional`, such as `Optional<String?>`, in Kotlin,
  and ideally consider `Optional`'s type parameter `out` ([KT-49210](https://youtrack.jetbrains.com/issue/KT-49210)).
  Nullable type arguments can for instance sneak in when typing return values from Java methods,
  as well as when declaring generic methods such as the ones proposed here (`fun <T : Any> Optional<T>...`),
  where it's easy to forget `: Any`.
  
  Note: nullable type arguments are already effectively prevented by `kotlinc` when _constructing_ `Optional`s.

* Additional extension functions and operators could be added, e.g., if there is sufficient demand.
  While there are others, this proposal has alluded to a few possibilities:

  * Operators: similar to Scala's example, a number of operator extensions could conceivably be defined that would allow treating
    `Optional`s directly as collections (e.g., with iteration, `in`, and `+/-` operators) and/or tuples (specifically, for destructuring).
    We're not doing that to start, not least because the resulting code could be confusing, and we get nearly the same
    expressiveness through the proposed `toList` and `toSet` conversions with more clarity.

  * Transformations: It would be possible to define replacements for `ifPresent`, `map`, etc. defined by `java.util.Optional` itself,
    but as `inline` functions so there's no need to allocate closures, and possibly more idiomatically defined for use in Kotlin
    (e.g., allow `ifPresent` equivalent to return a value). Currently we don't believe these would be used commonly enough.

* `Optional{Long,Int,Double}` primitive wrappers: extensions similar to what's proposed here
  could also be defined for primitive wrappers.
  Currently we don't believe these would be used commonly enough.


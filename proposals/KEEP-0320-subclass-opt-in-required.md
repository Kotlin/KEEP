# Opt-in into API implementation

* **Type**: Design proposal
* **Author**: Vsevolod Tolstopyatov
* **Contributors**: Mikhail Glukhikh, Vsevolod Tolstopyatov, Roman Elizarov, Ilya Gorbunov
* **Status**: Implemented in Kotlin 1.8.0 as experimental
* **Discussion**: [KEEP-320](https://github.com/Kotlin/KEEP/issues/320) 

This proposal describes a mechanism that will allow Kotlin library authors to provide extensible API (interfaces, abstract classes) that is stable for use
but requires an explicit opt-in for direct extension and implementation, clearly separating stability concerns.

## Motivation and use-cases

Since the introduction of [`@OptIn`](opt-in.md) feature, library authors received the means to mark their API in a way that requires an explicit opt-in 
from its users, along with a way to specify the reasons why the API requires opt-in -- experimentality, binary instability, 
overall complexity and delicateness of the API (i.e. the necessity to read the documentation), or just the ability to leave API effectively public, 
but mark it with a strict warning that it is intended solely for internal purposes.

After the initial adoption, over and over again we have been finding the same patterns that require more granular opt-in control for users and other library 
authors. These patterns can be broken down into following API categories:

- **Stable to use but unstable to implement.** Typically it is a family of interfaces or abstract classes where 
  authors expect to add new abstract methods without a default implementation. Such API is stable for use, but unstable to implement
  because it may require 3rd-party authors to re-compile their code in order to be compatible with the original API.
- **Stable to use but closed for 3rd-party implementations.** Typically it is an API that is supposed to be sealed, 
  but cannot for technical reasons (e.g. being spread over multiple modules). In such scenarios, authors may
  rely on implementation details of their API (i.e. downcasting for performance reasons), but use-sites of APIs are stable.
  - It also includes API that can be technically sealed, but due to limitations of the language, authors would like to let it be
    open in order to avoid future compatibility issues with their own extensions of the API and 3rd-party exhaustiveness checks.
- **Stable to use but delicate to implement.** It is an API with very non-trivial API contracts, where individual methods
  (potentially over multiple interfaces) should behave in a coordinated manner or where it is known that implementation by delegation
   produces [unwanted result](https://youtrack.jetbrains.com/issue/KT-18324).
- **Stable to use but with a contract that may be weakened in the future**. For example, an API may change its _input_ parameter type `T` to `T?`.
  Such change won't break existing users as they previously had no means to pass `null`, but will break existing implementations that did not expect `null`. 

These patterns imply that **most of the uses** in the wild are stable and the cost of marking original declarations with `OptIn` 
will create unnecessary inconvenience for such uses. On the other hand, leaving the API as is will create a false sense of
security for users and library authors that extend it.

## Goals

* Clear **implementation opt-in** is required for all users whose code may be broken by changes in the API implementation contract.
* **Consistency** with existing `OptIn` API in a way that users already familiar with `OptIn` will also be familiar with the proposed solution.
* **Robustness**. It should be possible to apply the solution only to extensible parts of API.
* **Smooth graduation**: if the API doesn't need to be changed as soon as it *graduates* (i.e. no longer requires opt-in), just unmark it, and all clients remain binary compatible.
* **Garbage collection upon graduation**: when the API graduates, the opt-in flags/markers are highlighted as warnings (and subsequently, errors) so that clients know to remove them.


## Proposal

We propose to add the following declaration to the standard library:

```kotlin
package kotlin

@Target(CLASS)
@Retention(BINARY)
@SinceKotlin("1.8")
@ExperimentalSubclassOptIn
public annotation class SubclassOptInRequired(
    val markerClass: KClass<out Annotation>
)
```

The `SubclassOptInRequired` accepts only opt-in markers (annotations that are explicitly marked with `RequiresOptIn`) and can be 
applied only to declarations open for extensions. Any attempt to extend such declaration will result in the corresponding opt-in marker warning or error:

```kotlin
// API declaration 

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Interfaces in this library are experimental"
)
annotation class UnstableApi()

@SubclassOptInRequired(UnstableApi::class)
interface CoreLibraryApi

// API use-site

// Warning: requires opt-in, reason: Interfaces in this library are experimental for implementation
interface SomeImplementation : CoreLibraryApi 
```

There are three ways to opt-in into a requirement:

* Opt-in explicitly with `@OptIn(UnstableApi::class)`, similarly to regular opt-in API.
* Mark the API as requiring opt-in itself with `@UnstableApi`
* Mark declaration with `@SubclassOptInRequired(UnstableApi::class)` to propagate opt-in requirement further.

### Naming alternatives

Naming for this feature is particularly hard and controversial as Kotlin does not have 
a single well-known term for both implementing an interface and extending a class,
while the feature itself is a mechanism to require opt-in for implementing an interface or extending a class.

The compiler itself has a notion of "type classifiers", but this notion is not well-known and unlikely to be recognizable.

Other alternatives are:

 * `RequireInheritanceOptiIn`
 * `SubclassesRequireOptIn`
 * Various attempts to leverage the notion of `sealed` and `open`: 
    * `SemiOpen` and `SemiSealed`
    * `OptInToOpen` and `OptInToSubclass`

`SubclassOptInRequired` was chosen as the most appropriate and likely the most familiar for developers 
to grasp from at first glance.
  
### Restrictions and limitations

The following annotation targets are explicitly prohibited by Kotlin:

* Sealed classes and interfaces
* Final classes
* Open local classes
* Kotlin `object` and `enum` classes
* `fun` interfaces
  * There are no technical limitations to allow it on `fun` interfaces, but we have not found compelling use-cases for
    such feature

Additionally, `@SubclassOptInRequired(UnstableApi::class)` does not make the declaration itself opted-in
into `UnstableApi` that
may be used within its body or signatures (`UnstableApi` types or overridden methods) to provide a clear distinction
between
opting-in into extension and opting-in into overall uses.

### Status and timeline

The feature is available since Kotlin 1.8.0 as experimental (it itself requires an opt-in
into `ExperimentalSubclassOptIn`)
and is expected to be promoted to stable in Kotlin 1.9.0.

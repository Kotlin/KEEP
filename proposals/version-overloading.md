# Version overloading

* **Type**: Design proposal
* **Author**: Faiz Ilham
* **Contributors**: Alejandro Serrano, Michail Zarečenskij, Marat Akhin, Nikita Bobko, Alexander Udalov
* **Discussion**: [#431](https://github.com/Kotlin/KEEP/discussions/431)

## Abstract

This proposal presents a new annotation `@IntroducedAt(version: String)` to indicate at which version an optional parameter is added.
A function that has parameter(s) annotated with `@IntroducedAt` is automatically overloaded with its previous versions' optional parameters.
This is useful for maintaining binary compatibility of functions with optional parameters.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivating example](#motivating-example)
  * [Constructor versioning](#constructor-versioning)
* [Technical details](#technical-details)
  * [Version string format and semantics](#version-string-format-and-semantics)
  * [Generating the overloads](#generating-the-overloads)
  * [Validation](#validation)
  * [Relabeling and removing annotations](#relabeling-and-removing-annotations)
* [Alternative designs](#alternative-designs)
  * [Maven comparable version string](#maven-comparable-version-string)
  * [User-defined version class](#user-defined-version-class)

## Motivating example

Currently, when library maintainers want to add new optional parameters to a published API,
they need to manually overload the function with the older signature in order to maintain binary compatibility.
Consider the following example:

```kotlin
// oldest version, v1.0
@Deprecated("Deprecated", level=DeprecationLevel.HIDDEN)
fun Button(
    label: String = "",
    color: Color = DefaultColor,
    onClick: () -> Unit
) = Button(label, color, DefautBorderColor, DefautBorderStyle, 1, onClick)

// a past version, v1.1: Added borderColor 
@Deprecated("Deprecated", level=DeprecationLevel.HIDDEN)
fun Button(
    label: String = "",
    color: Color = DefaultColor,
    borderColor: Color = DefautColor,       // new in v1.1 
    onClick: () -> Unit
) = Button(label, color, borderColor, DefautBorderStyle, 1, onClick)

// current version, v1.2: Added borderStyle and borderWidth.
fun Button(
    label: String = "",
    color: Color = DefaultColor,
    borderColor: Color = DefautBorderColor,
    borderStyle: Style = DefautBorderStyle, // new in v1.2
    borderWidth: Int = 1,                   // new in v1.2
    onClick: () -> Unit
) { /* body */ }
```

This is often done because JVM currently does not have support for optional arguments and any function calls that skip the optional arguments are desugared to a call with complete arguments.
Consequently, each time library maintainers introduced optional parameters to `Button` function, they need to maintain the older signature as a hidden deprecated function.
Otherwise, any old binary compiled with the older version of the library will fail to run as JVM could not find the overload with the correct number of parameters.

This means that the library source code is often polluted with the older function overloads that do not have meaningful differences with the current one.
This kind of (anti) pattern appears frequently in the Android Jetpack Compose library.
To fix this, we introduce a cleaner way to declare these version-based overloads.
Instead of manually declaring the hidden overloads, library maintainers may write the following:

```kotlin
fun Button (
    label: String = "",
    color: Color  = DefaultColor,
    @IntroducedAt("1.1") borderColor: Color = DefautBorderColor,
    @IntroducedAt("1.2") borderStyle: Style = DefautBorderStyle,
    @IntroducedAt("1.2") borderWidth: Int   = 1,
    onClick: () -> Unit
) { /* body */ }
```

Each time library maintainers add new optional parameters to the function, they only need to annotate the parameters with `@IntroducedAt` to indicate at what version of the library (or any notion of version number) the parameters are introduced.
In this case, the compiler will generate two `@Deprecated` hidden overloads of `fun Button` equivalent to the previous example.

### Constructor versioning

Parameters of a constructor may also be annotated with `@IntroducedAt`.
In case of a primary constructor of a data class, the compiler also generates the hidden overloads of the `.copy()` method.

```kotlin
data class Container(
    val label: String = "",
    @IntroducedAt("1.1") val width: Int = 1,
    @IntroducedAt("1.1") val color: Color = DefaultColor,
    @IntroducedAt("1.2") val style: Style = DefaultStyle,
)

// generated hidden ctor: Container(String), Container(String, Int, Color)
// generated hidden copy: copy(String), copy(String, Int, Color)
```

While this does not fully solve binary incompatibility issues of data classes since it cannot overload the `componentN()` methods, it solves the issue for most use cases.
This is especially the case if the new fields are only added at the tail position, since the existing `componentN()` methods never change and the data class would be fully compatible.

## Technical details

The `@IntroducedAt` annotation is defined as follows.
Using the annotation requires an opt-in of `ExperimentalVersionOverloading`.

```kotlin
@ExperimentalVersionOverloading
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class IntroducedAt(val version: String)
```

### Version string format and semantics

The version string is a series of non-negative integers seperated by periods, corresponding to the regex pattern `\d+(\.\d+)*`.
Version strings are numerically compared from the leftmost component to rightmost component, for example `0.1` < `1` < `1.1.0.1` < `1.1.1`.
Simple non-negative integer strings, such as `1` or `2`, are allowed.
Any leading zeroes within the integer parts are ignored, such that `1.02` == `1.2`.
Version strings that do not match this format are reported as an error.

Any parameters lacking version annotations are considered to have the "empty" version, which is always the oldest version.
For the overload generating purpose, a parameter's version string are only related to the other parameters' version strings in the same function.
As such, one may use a version numbering scheme that is different to the library's version numbers, such as simple non-negative integer strings:

```kotlin
// Equivalent to the previous example which uses "1.1" and "1.2"
fun Button (
    label: String = "",
    color: Color  = DefaultColor,
    @IntroducedAt("10") borderColor: Color = DefautBorderColor,  // added in lib v1.1
    @IntroducedAt("20") borderStyle: Style = DefautBorderStyle,  // added in lib v1.2
    @IntroducedAt("20") borderWidth: Int   = 1,                  // added in lib v1.2
    onClick: () -> Unit
) { /* body */ }
```
For the ease of documentation and readability, however, it is most advisable to have a consistent version numbering scheme across the library.

### Generating the overloads

Version overloading generates N-1 extra hidden overloads, given N unique version numbers including the empty version.
This number does not include the synthetic `$default` overloads, which are generated for any function that has default parameters.
If we count all overloads in the compiled binary, including the `$default` overloads and the actual function itself, there are 2N overloads for each version-overloaded function.
This is also the case if the function is manually overloaded like in the first example.

The extra overloads retain the default values to ensure compatibility with Kotlin binary calling the older API.
This is because a call to a function `foo` that does not provide the optional arguments is compiled to a call to the corresponding `foo$default` instead.
If we erase the default values, the generated overloads will not have the corresponding `$default` function, breaking binary compatibility.

As an example of how the overloads are generated, consider the following code.

```kotlin
@OptIn(ExperimentalVersionOverloading::class)

fun Button(
  label: String = "",
  color: Color = DefaultColor,
  @IntroducedAt("1.1") borderColor: Color = DefautBorderColor,
  @IntroducedAt("1.2") borderStyle: Style = DefautBorderStyle,
  @IntroducedAt("1.2") borderWidth: Int = 1,
  onClick: () -> Unit
) { /* body */ }

// generates 2 hidden overloads of Button, excluding $default: 
// - Button(label="", color=..., onClick)                    base, "empty" version < v1.1
// - Button(label="", color=..., borderColor=..., onClick)   v1.1

@Suppress("NON_ASCENDING_VERSION_ANNOTATION")
fun Box(
  label: String = "",
  width: Int = 1,
  height: Int = 1,
  @IntroducedAt("2") depth: Int = 1,
  @IntroducedAt("1") colorId: Int = 0,
  @IntroducedAt("3") border: Border = DefaultBorder
) { /* body */ }

// generates 3 hidden overloads of Box, excluding $default:
// - Box(label="", width=1, height=1)                        base version, < v1
// - Box(label="", width=1, height=1, colorId=0)             v1
// - Box(label="", width=1, height=1, depth=1, colorId=0)    v2
```

Notice that if the version numbers appear not in ascending order, it might lead to source incompatibility.
For example, the call `Box("a", 1, 2, 3)` is ambiguous when it uses the v1 library (setting `colorId` to 3) or the v2 library (setting `depth` to 3, `colorId` to the default value 0).
This problem can be prevented by providing the parameters by name, such as `Box(label="a", width=1, height=2, colorId=3)`.
Therefore, it is advisable to avoid adding new optional parameters in the middle of old ones, or encourage for providing the arguments by names.

### Validation

The compiler validates the following conditions for each instance of `@IntroducedAt`:
1. Only optional parameter can be annotated with `@IntroducedAt`.
2. The version string conforms to the [defined format](#version-string-format-and-semantics).
3. Non-final functions and functions with `@JvmOverloads` annotation may not have version annotated parameter.
4. A version annotated parameter's default value may not refer to an optional parameter annotated with a version later than itself.
5. Version annotated optional parameter may not appear before non-optional parameters except for a trailing lambda parameter.
6. Optional parameters, including the ones with empty version, appear in ascending order by the version numbers (warning only), 
   or must be forced-named parameters (currently unchecked).


As we mentioned previously, there will be source incompatibility issue if new optional parameters are added in the middle of the old parameters.
We currently warn users when this happened, and users may choose to suppress the warning if they want.
A possible extension in the future is the [forced-named parameter requirement](https://youtrack.jetbrains.com/issue/KT-14934) for non-ordered versioned parameters.
As a future-proof, we require the versioned optional parameters to appear in the correct order, which is after any non-optional, non-trailing-lambda parameters.
This is why we require the validation rules (5) and (6).

### Relabeling and removing annotations

A concern that may arise is about what happened if library authors relabel the version strings or remove the annotations altogether.
To illustrate this problem, let's start with an example.
Suppose that a library with three published versions (v1, v2, v3) has the following annotated function:

```kotlin
fun xyz(
  a: A = A(),                       // base version, v1
  @IntroducedAt("2") b: B = B(),    // added in v2
  @IntroducedAt("2") c: C = C(),    // added in v2
  @IntroducedAt("3") d: D = D(),    // added in v3
  @IntroducedAt("3") e: E = E()    // added in v3
) // 3 overloads, excluding $default:
  //   xyz(A, B, C, D, E), @Deprecated xyz(A, B, C), @Deprecated xyz(A) 
```

While in most cases we do not recommend it, relabeling is safe as long as the new strings has the same version number order with the old ones:

```kotlin
// Relabeling with the same ordering is OK
fun xyz(
  a: A = A(),
  @IntroducedAt("8") b: B = B(),
  @IntroducedAt("8") c: C = C(),
  @IntroducedAt("13") d: D = D(),
  @IntroducedAt("13") e: E = E() 
) // has same 3 overloads with the original one

// Relabeling with a different ordering breaks compatibility!
fun xyz(
  a: A = A(),
  @IntroducedAt("30") b: B = B(),
  @IntroducedAt("30") c: C = C(),
  @IntroducedAt("20") d: D = D(),
  @IntroducedAt("20") e: E = E() 
) // has the overload xyz(A, D, E) instead of xyz(A, B, C)
  // breaks binary compatibility!
```

Removing annotations breaks binary compatibility, since the generated overloads will be different.
In practice, this can be safe to do if done carefully.
For example, if the library authors are sure that no users depend on the library older than v2, they may choose to remove all `@IntroducedAt("2")` annotations:

```kotlin
fun xyz(
  a: A = A(),  // base version, v2
  b: B = B(),  // base version, v2
  c: C = C(),  // base version, v2
  @IntroducedAt("3") d: D = D(),
  @IntroducedAt("3") e: E = E()
) // 2 overloads: xyz(A, B, C, D, E), @Deprecated xyz(A, B, C)
  // breaks compatibility with binaries using v1, but not v2 and v3
```

We leave the decision whether to indefinitely keep or gradually remove the annotations up to the library authors.

## Alternative designs

In this section we discuss several alternative designs that we did not pick instead of the one presented in this proposal.
First, we decide to not support function parameter changes other than addition of optional parameters, such as deletions, changing parameter types, and reordering.
This is because they are not as common as additions, there are no simple way to indicate them using annotations, and users can still manually write overloads as an escape hatch.

The design alternatives are therefore similar to the main design in that they support only parameter additions.
Otherwise, they vary in how to represent the version numbers.

### Maven comparable version string

One alternative is to use the [Apache Maven comparable version string](https://maven.apache.org/ref/3.5.2/maven-artifact/apidocs/org/apache/maven/artifact/versioning/ComparableVersion.html),
which is [already implemented in the Kotlin compiler](https://github.com/JetBrains/kotlin/blob/d2966040c414579bb393c3fbcd517eb27f040efb/compiler/util/src/org/jetbrains/kotlin/config/MavenComparableVersion.java).
We decided against using this format since it is too flexible (all strings are valid Maven version strings) and the ordering might be confusing if the string contains non-standard qualifiers.
We also do not found a good case where library maintainers want to label an `IntroducedAt` annotation with a development version string, such as `1.0.3-beta`,
instead of just using the release version string `1.0.3`.

### User-defined version class
Another alternative design is by using a meta annotation to allow user-defined version class instead of using version number string, as shown in the example below.
We decided against it since it requires more boilerplate to write and version number string covers most cases.

```kotlin
// provided by the compiler
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class IntroducedAtMetaAnnotation

// user code
@IntroducedAtMetaAnnotation
annotation class IntroducedAt(val version: MyVersion)

enum class MyVersion { One, Two }

fun myFun(
  oldestOpt: Int = 2,
  @IntroducedAt(MyVersion.One) oldOpt: Int = 1, 
  @IntroducedAt(MyVersion.Two) newOpt: Int = 0
) {  }
```

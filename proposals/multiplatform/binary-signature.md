# Binary Signature Name

* **Type**: Design proposal
* **Authors**: Roman Elizarov
* **Contributors**: Mikhail Glukhikh, Dmitriy Novozhilov, Denis Zharkov, Simon Ogorodnik, Ilya Gorbunov, Zalim Bashorov, Svyatoslav Scherbina, Sergey Bogolepov
* **Status**: Proposed
* **Discussion**: [KEEP-302](https://github.com/Kotlin/KEEP/issues/302)

**Table of contents**

<!--- TOC -->

* [Introduction and use-cases](#introduction-and-use-cases)
  * [Kotlin compilation basics](#kotlin-compilation-basics)
  * [Use-case: Binary-compatible library migration](#use-case-binary-compatible-library-migration)
  * [Use-case: Objective-C interoperability](#use-case-objective-c-interoperability)
* [Detailed design](#detailed-design)
  * [Call resolution signature](#call-resolution-signature)
  * [Override matching signature](#override-matching-signature)
  * [Binary signature](#binary-signature)
  * [Platform interoperability and platform names](#platform-interoperability-and-platform-names)
  * [Conflicting overloads](#conflicting-overloads)
    * [Override matching check](#override-matching-check)
    * [Call resolution check](#call-resolution-check)
    * [SinceKotlin/DeprecatedSinceKotlin](#sincekotlindeprecatedsincekotlin)
  * [Override matching](#override-matching)
    * [Overrides and properties](#overrides-and-properties)
    * [Objective-C overrides in Kotlin/Native](#objective-c-overrides-in-kotlinnative)
  * [Expect/actual matching](#expectactual-matching)
  * [Declaring functions that differ only in parameter names](#declaring-functions-that-differ-only-in-parameter-names)
  * [Constructors](#constructors)
  * [Details on BinarySignatureName annotation](#details-on-binarysignaturename-annotation)
  * [Details on ObjCSignature annotation](#details-on-objcsignature-annotation)
  * [Conflicting fake overrides](#conflicting-fake-overrides)
* [Open issues](#open-issues)
  * [Conflicting fake overrides impact](#conflicting-fake-overrides-impact)
* [Alternatives](#alternatives)

<!--- END -->

## Introduction and use-cases

This proposal introduces `@BinarySignatureName` annotation that serves as a cross-platform variant of `@JvmName` 
annotation that is used in Kotlin/JVM and is designed to solve various library migration and Object-C interoperability use-cases. 
This annotation affects overload matching and linking of cross-platform Kotlin libraries.

For convenience of Object-C interoperability, a helper `@ObjCSignature` annotation is introduced that makes Objective-C 
interoperability more straightforward as shown later. 

This proposal is designed to address the following open issues:
* [KT-31420](https://youtrack.jetbrains.com/issue/KT-31420) Support `@JvmName` on interface or provide other interface-evolution mechanism.
* [KT-20068](https://youtrack.jetbrains.com/issue/KT-20068) Support get/set targeted `JvmName` annotation on interface properties.
* [KT-44312](https://youtrack.jetbrains.com/issue/KT-44312) Forbid methods with clashing signatures in case of generic override.

### Kotlin compilation basics

Let's look at simplified picture of what happens when one builds an application using Kotlin libraries.
The following steps are taken.

**Compilation**: Kotlin compiler takes the _Kotlin source files_ and produces a _binary library_. 
  In this binary all the references from the sources are already _resolved_. The format of this binary is platform-dependent:
* **Kotlin/JVM**: uses JAR file format as the binary artifact produced by the compiler.
* Other Kotlin platforms (**Kotlin/JS** and **Kotlin/Native**) use Klib file format as the binary artifact produced by the compiler.

A library publishes its binary artefact for its clients. 
The final clients of the library load compiled dependencies and perform the next step.
   
**Linking**: The binaries are linked together to produce the final executable format for the specific platform.
* **Kotlin/JVM**: links its JAR binaries during runtime.
* **Kotlin/JS**: produces JS files that can be executed by a JavaScript runtime.
* **Kotlin/Native**: produces various platform-specific executables and library formats (e.g. DLLs, SOs, Objective-C frameworks, etc)

> Linking is also performed as a part of compilation of other libraries that depends on a library, 
> and the result is a  Kotlin library binary (JAR or Klib) again.

Changes to the library that break compilation phase for its clients are called _source-incompatible changes_.
The Kotlin language has complex call resolution and type inference rules that, in general, make any
change in the library declarations source-incompatible for some client source code. So, in Kotlin, the
only changes that are definitely _source-compatible_ are those that affect the implementation only.

However, once the library is compiled, all the resolution and type-inference has been already performed, so the 
resulting binary artefact is more robust with respect to changes. The changes to the library that break linking phase 
for its previously compiled clients are called _binary-incompatible changes_. These changes are very important for 
library authors to avoid, especially if the library is being widely used in the ecosystem, as it is impossible to 
expect that all its clients will get recompiled at once. So library authors need tools to evolve their
libraries while keeping them _binary-compatible_ with previously compiled clients. These tools has
to work consistently accross all Kotlin platforms.

### Use-case: Binary-compatible library migration

Consider the following library code in the 1st version of some library:

```kotlin
sealed class Base {
    abstract fun doSomething()
}

class Derived : Base() {
    override fun doSomething() { /* ... */ }
}
```

There is a sealed class with `doSomething` function that does not return anything. 
Now, in the second version of the library we’d like to change this function to return a string:

```kotlin
sealed class Base {
    abstract fun doSomething(): String
}

class Derived : Base() { 
    override fun doSomething(): String { /* ... */ }
}
```

This change is mostly source compatible (it will not break a typical user), but it is **not binary compatible**
on any Kotlin platform.  It means that if a library does this change, then it will cease to link to with previously compiled users. 
To keep compatibility, both old and new declarations must be kept. 
However, this creates a "conflicting overloads" problem as well as the problem with subsequent attempt to 
override the corresponding declaration:

```kotlin
sealed class Base { // ERROR: Conflicting overloads
    abstract fun doSomething() 
    abstract fun doSomething(): String
}

class Derived : Base { // ERROR: Return type mismatch on override
    override fun doSomething() { /* ... */ }
    override fun doSomething(): String { /* ... */ }
}
```

> In other migration scenarios, one can also experience a "platform declaration clash" error when different declarations 
> (from Kotlin point of view) get mapped to the same declaration on JVM platform due to type erasure.

Currently, there is no way to work around this problem. For top-level functions on Kotiln/JVM it is customary to use 
`@JvmName` for such a migration, but for other platforms or for open/abstract members no solution currently exists.

The newly introduced `@BinarySignatureName` annotation can be used here with the following procedure for 
all kinds of binary-compatible library migration scenarios:

* The old function is marked with `@Deprecated(level = DeprecationLevel.HIDDEN)`, thus keeping its binary signature 
  and supporting all previously compiled clients for this library on all platforms.
* The new function is marked with `@BinarySignatureName("...")` giving a new name for the binary signature of this function,  
  thus avoiding conflicting overloads, platform declaration clashes, and allowing to specify matching declaration for override.

In the above example, the resulting code looks like this:

```kotlin
sealed class Base {
    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    abstract fun doSomething()
    
    @BinarySignatureName("doSomethingString")
    abstract fun doSomething(): String
}

class Derived : Base() { 
     @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
     override fun doSomething()
     
     @BinarySignatureName("doSomethingString")
     override fun doSomething(): String { /* ... */ }
}
```

> The requirement to specify `@BinarySignatureName` on override is not convenient, so this solution is clearly not 
> designed for everyday mass usage. Yet, it does solve J2K conversion problem while maintaining binary compatibility 
> and avoids the problem of figuring out the platform name in the presence of multiple inheritance.

### Use-case: Objective-C interoperability

Objective-C uses _selectors_ to identify method calls. An Objective-C selector includes the method name and argument labels. 
Objective-C can have methods in the same scope that differ only in the argument labels. 
For example, consider the snippet from the following Objective-C protocol:

```objective-c
@protocol UITableViewDataSource
- (nullable NSString *)tableView:(UITableView *)tableView titleForHeaderInSection:(NSInteger)section;
- (nullable NSString *)tableView:(UITableView *)tableView titleForFooterInSection:(NSInteger)section;
@end
```

In Kotlin this protocol is represented with the following interface:

```kotlin
interface UITableViewDataSource { // ERROR: Conflicting overloads
    fun tableView(tableView: UITableView, titleForHeaderInSection: Int): String?
    fun tableView(tableView: UITableView, titleForFooterInSection: Int): String? 
}
```

Normally, such overloads, that are different only in parameter names, are not allowed in Kotlin, so a special
Objective-C specific rules are currently used for Objective-C interoperability. However, these Objective-C-specific 
rules do not scale well to the general Kotlin multiplatform scenarios and post various implementation challenges 
when linking libraries. Here, the `@BinarySignatureName` annotation can be used to disambiguate these functions using 
the corresponding Objective-C selector of those functions and to avoid conflicting overloads error in Kotlin:

```kotlin
interface UITableViewDataSource {
    @BinarySignatureName("objc:tableView:titleForHeaderInSection:")
    fun tableView(tableView: UITableView, titleForHeaderInSection: Int): String?
    @BinarySignatureName("objc:tableView:titleForFooterInSection:")
    fun tableView(tableView: UITableView, titleForFooterInSection: Int): String? 
}
```

The same `@BinarySignatureName("...")` annotations will have to be placed on the overloads of those functions in any of 
the implementing classes. This makes it pretty inconvenient to implement such interfaces coming from Objective-C, 
as the names themselves are boilerplate, which could be automatically derived from the method name and parameter names.

That is where `@ObjCSignature` annotation comes into play. It has the same effect as manually adding 
`@BinarySignatureName("...")` annotation with Objective-C selector:

```kotlin
interface UITableViewDataSource {
    @ObjCSignature
    fun tableView(tableView: UITableView, titleForHeaderInSection: Int): String?
    @ObjCSignature
    fun tableView(tableView: UITableView, titleForFooterInSection: Int): String? 
}
```

Now, the overriding/implementing methods in Kotlin will only have to be annotated with `@ObjCSignature` if they don't
change parameter names on override.

## Detailed design

This section describe the detailed design for signatures of Kotlin callables (functions and properties). 
Let’s start with going deep into different kinds of signature that Kotlin uses for different purposes. 
In general, the **signature** is a combination of various callable’s properties (such as its name, parameters types, etc) 
that uniquely identifies a given callable for various purposes. 
Kotlin uses different signatures in different stages of compilation process:

* [Call resolution signature](#call-resolution-signature)
* [Override matchine signature](#override-matching-signature)
* [Binary signature](#binary-signature)

### Call resolution signature

This signature is used when compiling Kotlin code to determine what declaration the given call resolves to. 
This signature primarily affects **source compatibility** of the Kotlin source code. We can describe it in the following way:

* It includes all Kotlin type information, including generics parameters and detailed parameter types.
* It includes parameter names.
* It does NOT include a return type.
* It uses the name of the callable declared in the source.

This design proposal does not affect the call resolution procedure in any way. It continues to work as before.

### Override matching signature

This signature is used when `override` in the derived class or interface needs to be matched with inherited class or interface. 
We can describe it in the following way:

* It includes all Kotlin type information, including generics parameters and detailed parameter types.
* It does NOT include parameter names. That’s a difference from call resolution signature. It allows parameter names to be changed on override.
* It does NOT include a return type. It allows covariant overrides with narrower return types.
* It uses BOTH the name of the callable declared in the source and the `@BinarySignatureName`, if present, as an additional discriminator.

This last item is the new change in how the override matching signature works in Kotlin. That’s one of the key changes 
that enables all the use-cases. Using the name specified in `@BinarySignatureName` annotation as discriminator allows 
override functions that would be otherwise indistinguishable (e.g. if they differ only in the return type or only in parameter names) 
by giving them different binary signature names.

### Binary signature

This signature is used to link compiled artifacts. During the compilation, the call resolution signature is used to
resolve the declaration the call refers to, but the resulting compiled artifact includes binary signature of the resolved callable. 
This binary signature is later used by a platform-specific linking process that combines all library binaries together
and produces an executable artifact or executes it right away. 
This signature primarily affects **binary compatibility** of the compiled Kotlin libraries.

The binary signature is platform-specific: 

* On **Kotlin/JVM** the JVM method descriptor works as the binary signature.
* On other Kotlin platforms, the binary signature is written in Klib format for compiled Kotlin binaries.

We can describe it in the following way:

* It includes all Kotlin type information, including generics parameters and detailed parameter types.  
  However, on Kotlin/JVM the types are erased and approximated to the types representable on JVM.
* It does NOT include parameter names. That makes it different from the call resolution signature. 
  The change of parameter names is binary compatible in Kotlin, but not, in general, source compatible.
* It includes a return type. That makes it different from the call resolution signature, too. 
  While the change of the return type to a more specific type can be mostly source compatible, it is not a binary compatible change.
* It use the name specified in `@BinarySignatureName`, 
  if present, falling back to the name of the callable declared in the source.
  On Kotlin/JVM, the name specified in `@JvmName` takes precedence when determining the actual name that is written to the binary.

This last item is the change in how the binary signature works in Kotlin. The value specified in `@BinarySignatureName` annotation 
effectively works as a cross-platform analogue for `@JvmName` annotation.

It is a "platform signature clash" error to have two declarations with the same binary signatures in the same scope. 
The check depends on the platform the code compiles, too. On Kotlin/JVM the check is stricter, 
since the JVM signature includes less information.
 
### Platform interoperability and platform names

The specified signature name affects linking of the resulting binary and thus has the following platform-specific effects:

* **Kotlin/JVM**: uses JAR file format as the binary artifact produced by the compiler, so the signature name is used as 
  a default value of `@JvmName` to ensure that it has the desired effect on the resulting binary signature.  
  It means, that a side-effect of specifying a signature name of the JVM platform is that it also affects the name that
  Java platform sees as the name of the corresponding declaration.
 
* Other Kotlin platforms (**Kotlin/JS** and **Kotlin/Native**) use Klib file format as the binary artifact produced by the compiler. 
  Klib stores the specified signature and uses it for linking purposes. The platform interoperability uses different
  mechanisms and is not affected by the specified signature name. The exported name of declaration for 
  JavaScript, C/C++, Objective-C, and Swift is still derived from the source name of declaration and could be customized, 
  if needed, by separate platform-specific annotations.

### Conflicting overloads

There are changes in how "Conflicting overloads" are going to be detected and reported. 
See ["Conflicting overloads" section in Kotlin spec](https://kotlinlang.org/spec/overload-resolution.html#conflicting-overloads) for details. 
The conflicting overload algorithm is changed and will consist of two separate checks.

* [Override matching check](#override-matching-signature)
* [Call resolution check](#call-resolution-signature)

#### Override matching check

Declarations are grouped by BOTH a source name and a signature name to detect conflicting 
overloads using the current Kotlin rules. That is, in each, group pairs of declarations are checked w.r.t. their 
applicability for a phantom call site with a fully specified argument list (i.e., with no used default arguments) and  
without parameter names. It means, that the following pair of declarations are still considered conflicting as they 
are in the same group and differ only in parameter names:

```kotlin
// ERROR: Conflicting overloads
fun tableView(tableView: UITableView, titleForHeaderInSection: Int): String?
fun tableView(tableView: UITableView, titleForFooterInSection: Int): String?
```

While these declarations will not conflict anymore, because they have different signature names:


```kotlin
// OK
@BinarySignatureName("tableView:titleForHeaderInSection:")
fun tableView(tableView: UITableView, titleForHeaderInSection: Int): String?
@BinarySignatureName("tableView:titleForFooterInSection:")
fun tableView(tableView: UITableView, titleForFooterInSection: Int): String?
```

This check ensures that override matching (that takes signature names into account) can always property 
distinguish declarations. 

#### Call resolution check

Declarations are grouped by the source name only and checked using the current Kotlin rules BUT including all the parameter 
names in the check and excluding all the declarations marked with `@Deprecated(level = DeprecationLevel.HIDDEN)` or an 
equivalent `@SinceKotlin`/`@DeprecatedSinceKotlin` (see blow). That is, in each, group pairs of declarations are checked 
w.r.t. their applicability for a phantom call site with a fully specified argument list and argument names.

This check ensures that the call resolution (that takes source names into account and ignores hidden declarations) can 
properly distinguish declarations in a call with fully specified names. This check will forbid the following pair of 
declarations that are totally identical and differ only the signature name, as they cannot be distinguished by a 
call even with parameter names:

```kotlin
// ERROR: Conflicting overloads
fun doSomething(param: Int)
@BinarySignatureName("doSomethingString")
fun doSomething(param: Int): String { /* ... */ }
```

However, the same pair of declarations compiles if the first one is hidden:

```kotlin
// OK
@Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
fun doSomething(param: Int)
@BinarySignatureName("doSomethingString")
fun doSomething(param: Int): String { /* ... */ }
```

#### SinceKotlin/DeprecatedSinceKotlin

The Kotlin standard library uses `@SinceKotlin("...")` and `@DeprecatedSinceKotlin(hiddenSince = "...")` to hide 
declarations conditionally, based on the current API version in use (the value of the `-api-version` argument 
when compiling the module where the usage is located). For the purposes of **call resolution** check, 
they will taken into account, too:

* Declarations with `@SinceKotlin(version)`, when the current API version is below the specified version, are excluded from the check.
* Declarations with `@DeprecatedSinceKotlin(hiddenSince = version)`, when the current API version is the same or more recent as the version, are excluded from the check.

### Override matching

Override matching is performed using [**override matching signature**](#override-matching-signature). 
It has to match between the override and the overridden method, which means the the source name, the binary signature name, 
and all the parameter names must match or there will be a compilation error. In particular, this example is an error 
(the source name does not match):

```kotlin
open class Base { 
    open fun foo()
}

class Derived : Base() {
    @SingatureName("foo")
    override fun bar() // ERROR: 'bar' does not override anything
}
```

And this example is an error (the signature name does not match):

```kotlin
open class Base { 
    open fun foo()
}

class Derived : Base() {
    @SingatureName("bar")
    override fun foo() // ERROR: 'foo' does not override anything
}
```

#### Overrides and properties

Properties can have signature names on their getters and/or setters.

* When overriding a read-only (`val`) property, the signature name for the getter must match.
* When overriding a mutable (`var`) property, the signature name for BOTH the getter and the setter must match.

#### Objective-C overrides in Kotlin/Native

Objective-C has relaxed rules on overrides. In particular, it is possible to change the parameter types in Object-C on override. 
These relaxed checks are implemented only in Kotlin/Native for Kotlin code that is produced as a result of ObjC-interop tooling and 
their actual specifications are out of the scope of this KEEP.

### Expect/actual matching

Expect/actual matching checks both the source name of the declaration and the value of `@BinarySignatureName` 
annotation just like the [override matching](#override-matching) does.

### Declaring functions that differ only in parameter names

In general, it is not in Kotlin style to have functions that different only in parameter name like it is customary
in Objective-C and Swift, for example. In Kotlin you'd give these functions different names. However, it is conceivable
that some domain-specific DSLs might want to have this kind of feature. The following code does not compile:

```kotlin
// ERROR: Conflicting overloads
fun addSection(header: String) { /* ... */ }
fun addSection(footer: String) { /* ... */ }
```

However, it becomes possible, if these function are given different binary signature names:

```kotlin
@BinarySginatureName("addSectionHeader")
fun addSection(header: String) { /* ... */ }
@BinarySginatureName("addSectionFooter")
fun addSection(footer: String) { /* ... */ }
```

> In fact, only them is required to have `@BinarySginatureName` name annotation to compile this code.

This approach is quite verbose by design, since it is not something that is intended for a wide-spread usage.
A recommended approach for Kotlin is to have a separate `addSectionHeader` and `addSectionFooter` functions.

If you try to call these functions with `addSection("foo")` there will be an overload resolution ambiguity on the
call-site, but it is possible to disambiguate between them with `addSection(header = "foo")` and
`addSection(footer = "foo")`.

> Callable reference like `::addSection` will not work and there is no way to disambiguate it.

### Constructors

The `@BinarySignatureName` and `@ObjCSignature` annotations can also target constructors. Constructors in Kotlin do not 
have their own name and cannot be overridden, so the binary signature name has a limited role there:

* Constructor binary signature name is used for [conflicting overloads](#conflicting-overloads) 
  checking among constructors according to the general rules.  
  Both [override matching check](#override-matching-check) and [call resolution check](#call-resolution-check) are performed.
* Constructor binary signature name is stored in Klib as a part of the **binary signature** for the purpose of linking.  
  However, on Kotlin/JVM, constructors are anonymous and signature is not stored as a part of the constructor’s JVM signature at all.

The latter means, that the following code will not compile on Kotlin/JVM:

```kotlin
class Foo { // ERROR: Platform declaration clash
   @BinarySignatureName("initWithA")
   constructor(a: Int) { }
   @BinarySignatureName("initWithB")
   constructor(b: Int) { } 
}
```

### Details on BinarySignatureName annotation

```kotlin
package kotlin

@Target(FUNCTION, CONSTRUCTOR, PROPERTY_GETTER, PROPERTY_SETTER)
@Retention(BINARY)
public annotation class BinarySignatureName(val name: String)
```

The `@BinarySignatureName` annotation affects the resolution algorithm in the compiler on a deep level, so this annotation, 
by itself, has to be resolved before everything else. Hence, there is an additional restrictions on usage of this annotation:

* `@BinarySignatureName` cannot be used via a typealias.
* `@BinarySignatureName(name = "...")` argument must be a literal string. It cannot use a string expression that is using some constant strings or compile-time functions.

It is Ok to use `@JvmName` and `@BinarySignatureName` on the same declaration, given that `@JvmName` restrictions are not violated:

* `@JvmName` is not allowed on open declarations, so there is no effect on override matching.
* The expect/actual matching will be based on `@BinarySignatureName`.
* The binary name in KLibs will be based on `@BinarySignatureName`.
* The binary name on JVM will be based on `@JvmName`.

### Details on ObjCSignature annotation

```kotlin
package kotlin.native

@Target(FUNCTION, CONSTRUCTOR)
@Retention(BINARY)
public annotation class ObjCSignature()
```

The `@ObjCSignature` annotation effectively causes generation of `@BinarySignatureName` annotation on the annotated declaration.
The value of this automatically generated `@BinarySignatureName` is equal to the `"objc:"` string followed by the Objective-C selector 
for the corresponding function, which is based on Kotlin names of the parameters. It means, that when Kotlin/Native ObjC-interop 
tools perform any kind of parameter name mangling during translation from Objective-C to Kotlin, 
the resulting signature name will be using the mangled names.

* It is an error to annotate a function or constructor both with `@ObjCSignature` and `@BinarySignatureName` annotations.
* `@ObjCSignature` cannot be used via a typealias.

The `@ObjCSignature` is available only in Kotlin/Native. It cannot be used in code targeted for other platforms.

### Conflicting fake overrides

Consider the code from [KT-44312](https://youtrack.jetbrains.com/issue/KT-44312) Forbid methods with clashing signatures in case of generic override:

```kotlin
open class Base<T> {
    fun foo(a: T): Int = 1
    fun foo(a: String): Int = 2
}

class Derived: Base<String>()
```

If derived class were to override both overloads of `foo` from the `Base` class, then it would have to provide
two functions with identical signature of `fun foo(a: String)`. Writing such an override explicitly is impossible,
yet Kotlin compiler internally generates _fake overrides_ in this case, which are currently allowed to exist.

The proposal is to apply the same rules that are spelled out in [Override matching](#override-matching) section
to the fake overrides and give the compilation error for the class `Derived`. 
**This is a breaking change**.

For this particular example with the final methods in class `Base` there is no workaround to write the class `Derived`. 
However, if `Base` was declared with `open` functions, then the following code will continue to compile:

```kotlin
open class Base<T> {
    open fun foo(a: T): Int = 1
    open fun foo(a: String): Int = 2
}

class Derived: Base<String>() {
    // overrides BOTH foo methods from Base
    override fun foo(a: String) = 3
}
```

## Open issues

This section lists open issues with this design proposal.

### Conflicting fake overrides impact

The prohibiting of [Conflicting fake overrides](#conflicting-fake-overrides) is a breaking change. 
Its impact has to be studied before making the final decision.

## Alternatives

The whole design has been revolving around representation of Kotlin binary signatures in Klib format and
the very concept of signature in Kotlin. A number of alternatives were considered. They are shortly 
summarized below.

* **Using JVM-like signature in Klibs** was considered. The upside would have been the utmost compatibility of 
  Kotlin multiplatform with Kotlin/JVM. However, the are downsides. The key downside is that JVM binary descriptors erase
  a great deal of type information that is otherwise available in Kotlin. It means, that a valid change during separate
  compilation of different libraries can produce a pair of libraries that link together successfully, yet are not 
  consistent from the Kotlin's type-system point of view. Trying to build those after linking into a platform binary
  would either crash the compiler or produce a broken executable. To avoid that, we would need to specify an implement
  an JVM-like verifier, which was deemed too much an extra effort. Taking this path would also cement JVM legacy in 
  Kotlin multiplatform which is not desirable for Kotlin multiplatform's future evolution.
  
* **A key-value map with additional parameters** was considered as a discriminator for binary signatures. The upside
  is that it could also be use to keep all the platform-specific information (like JVM-name, Objective-C selector, Swift name, etc) 
  in one place. However, it adds extra boilerplate for simple tasks we had in mind and, in fact, does not add benefits
  from a platform-specific point of view. For example, changing the exported Swift name of a declaration should not affect
  the ability to link with this declaration from other compiled Kotlin code.

# Streamline ambiguous KDoc links

* **Type**: Proposal
* **Authors**: Vadim Mishenev, Oleg Makeev
* **Contributors**: Marco Pennekamp, Yan Zhulanow, Oleg Yukhnevich, Azat Abdullin
* **Status**: In progress
* **Issues**: [dokka/#3451](https://github.com/Kotlin/dokka/issues/3451), [dokka/#3179](https://github.com/Kotlin/dokka/issues/3179), [dokka/#3334](https://github.com/Kotlin/dokka/issues/3334) 
* **Discussion**: [#389](https://github.com/Kotlin/KEEP/issues/389)

# Summary

This document introduces clarifications and improvements to the existing implementation of [KDoc](https://kotlinlang.org/docs/kotlin-doc.html) regarding resolving ambiguous KDoc links.
It addresses the range of issues ([dokka/#3451](https://github.com/Kotlin/dokka/issues/3451), [dokka/#3179](https://github.com/Kotlin/dokka/issues/3179), [dokka/#3334](https://github.com/Kotlin/dokka/issues/3334)) that Dokka and the IntelliJ Plugin faced during the migration to the K2 resolver from **Kotlin Analysis API**.

# Table of contents
* [Motivation](#motivation)
  * [Introduction](#introduction)
  * [How does the current K1 / K2 implementation prevent ambiguity?](#how-does-the-current-k1--k2-implementation-prevent-ambiguity)
    * [Context declarations](#context-declarations)
    * [Disambiguation by declaration kinds and by locality of scopes](#disambiguation-by-declaration-kinds-and-by-locality-of-scopes)
      * [K1 implementation](#k1-implementation)
      * [K2 implementation](#k2-implementation)
    * [Disambiguation by overloads](#disambiguation-by-overloads)
  * [Additional notes: K1 / K2 inconsistencies](#additional-notes-k1--k2-inconsistencies)
  * [Issues with the existing K1 implementation](#issues-with-the-existing-k1-implementation)
  * [Shared issues](#shared-issues)
* [Proposal](#proposal)
  * [Context of KDoc](#context-of-kdoc)
  * [Resolution strategy](#resolution-strategy)
  * [Resolution algorithm](#resolution-algorithm)
    * [Step 1. Perform context declaration search](#step-1-perform-context-declaration-search)
    * [Step 2. Perform scope traversal](#step-2-perform-scope-traversal)
      * [Short names treatment](#short-names-treatment)
      * [Multi-segment names treatment](#multi-segment-names-treatment)
        * [Scope reduction algorithm](#scope-reduction-algorithm)
        * [Relative name resolution](#relative-name-resolution)
        * [Global name resolution](#global-name-resolution)
    * [Step 3. Perform package search](#step-3-perform-package-search)
    * [Result of the resolution](#result-of-the-resolution)
  * [Resolution of tag sections subjects](#resolution-of-tag-sections-subjects)
  * [Handling resolved declarations on the use-site](#handling-resolved-declarations-on-the-use-site)
* [Appendix](#appendix)
  * [Other considered approaches and ideas](#other-considered-approaches-and-ideas)
    * [Special resolution context inside tag sections](#special-resolution-context-inside-tag-sections)
    * [Restrictions of multi-segment name resolution](#restrictions-of-multi-segment-name-resolution)
  * [Other languages](#other-languages)

# Motivation
 
## Introduction

[KDoc](https://kotlinlang.org/docs/kotlin-doc.html) is a language used to write Kotlin documentation.
It's an equivalent of Javadoc from Java.
As well as Javadoc, it allows embedding navigatable links to code declarations
right in the documentation using square brackets `[]`.

There are two types of KDoc links to a declaration:
-   Fully qualified ones, for example `[com.example.classA]`, starting with a full package name
-   Relative ones (also known as short names), for example `[memberProperty]` / `[ClassA.myObject.method]`.

Besides regular top-level declarations, KDoc also allows referring to:
-   Functional parameters, context parameters, or type parameters
-   Extension receiver via `[this]`
-   Packages

Here is an example for understating:
```kotlin
package com.example
/**  
* [com.example.classA], [com.example.classA.member] - fully qualified links
* [classA], [member], [extension] - relative link
*/
class classA {
    object B {
        class C
    }

    /**
     * [B.C] -- a relative link
    * [classA], [member], [extension] - relative link
    */
    val member = 0
}
  
/**  
 * [com.example.extension] - fully qualified link 
 * [classA], [extension] - relative links in the current scope
 * [classA.extension], [com.example.classA.extension] - out of the scope of the current document
 * [this] - receiver 
 * [p] - parameter 
 * 
 * @param p is also a link  
 */
 fun classA.extension(p: Int) = 0
```

### Visibility
KDoc ignores visibility, i.e., all declarations are treated as visible from the KDoc position.
However, whether resolving KDoc links should take visibility into account is an open question,
which is out of the scope of this proposal.

On the opposite, Javadoc can take visibility into account for particular cases (not specified),
but for most declarations it works just like KDoc.

```java
/**  
 * {@link JavaC} is resolved despite `private` and displayed as plain text 
 */
public class JavaB {  
    private class JavaC {}
    void f() {}  
}
/**  
 * {@link JavaC} is unresolved
 * since JavaB.JavaC is private
 * but {@link #f} is resolved and displayed as plain text 
 */
 public class JavaA extends JavaB {
 }
```

```kotlin
val a = 0
internal fun somethingInternal() = 0

/** [somethingInternal] leads to the internal fun */
fun usage() {}

class A {
    private val somethingPrivate = 0
    
    class B {
        /** [somethingPrivate] leads to the private property in A */
        fun usage() {}
    }
}
```


### What is an ambiguity?

An ambiguity in KDoc name resolution is a case when there are multiple declarations available for the same KDoc link.

Consider the following example:
```kotlin
package A.A

import foo.bar.A

class A

object Something {
    class A

    fun <A> foo() {
        val A = 5

        /**
         * [A]
         */
    }
}

fun A(x: Int) {}
fun A(x: String) {}
```

It contains various declarations with the same name `A`: imports, packages, classes, variables, functions, etc.
To which declaration should the KDoc name `A` resolve to?
That's why KDoc resolution requires a set of strict rules and priorities,
which can help to create a consistent and disambiguating resolution strategy.

## How does the current K1 / K2 implementation prevent ambiguity?

There can be various ambiguous cases,
which both K1 and K2 handle using similar mechanisms and rules to deal with them.

### Context declarations

A context declaration is the documented declaration itself or some component of its signature.
Both Javadoc and KDoc give documented declarations the highest priority among all other declarations with the same name.
If no suitable context declaration with a matching name was found, the search proceeds to other declarations.

This mechanism is widely used in the various libraries (e.g. Kotlinx) because of its visual consistency and simplicity.

Note that the K1 implementation doesn't prioritize the documented declaration itself, but only its components.
See [this section](#inconsistency-with-context-declarations) for more details on this issue.

```kotlin
fun foo() {}
val x = 0
class T

/**
 * [foo] (only in K2), [x], [T] - point to declarations from the context declaration,
 * i.e., `foo` function, `x` value parameter and `T` type parameter.
 * 
 * All the other declarations are ignored, as they have lower priority.
 */
fun <T> foo(x: Int) {}
```

Their principle is quite natural and convenient in use, as they cannot be broken by introducing some other declaration.

### Disambiguation by declaration kinds and by locality of scopes

When reasoning about existing resolution strategies, it's important to note
that the KDoc resolution was never a part of the Kotlin specification.
So the current K1 and K2 implementations differ from each other.
We will take a look at both of them while considering the K1 implementation
to be the default and "classic" one. 
This KEEP will mainly refer to the K1 implementation unless specified otherwise.

Also, keep in mind that both of the implementations are ad hoc and the following
descriptions will just outline general approaches without covering all possible cases.

#### K1 implementation

When there are no suitable [context declarations](#context-declarations) found,
the current resolution strategy handles declaration priorities based on two properties:
- Declaration kind
- Scope of origin

There are four main kinds of declarations (from higher priority to lower):
-  Class
-  Package
-  Function
-  Property

For some name `x`, the resolver iterates through these kinds and considers them one-by-one.
For each declaration kind (except for packages, as their search is global) the resolver tries to find 
an instance of this declaration kind with the given name `x` in every scope that is visible from the KDoc position.
It starts from local scopes (function body scope, type scope, etc.) 
and proceeds to global scopes (package scope, explicit import scopes, star import scopes, scopes of other packages).
The result of such an approach is a single most local declaration of the highest priority kind possible. 

This strategy will be then referred to as the **kind-first approach**.

Some examples:
```kotlin
val x = 0
fun x() = 0

/** Here [x] refers to the function, as it has higher priority */
fun usage() = 0
```

```kotlin
class Something

object A {
    fun Something() {}
     
    /** Here [Something] refers to the class, as classes have higher priority than functions */
    fun usage() = 0
}
```

```kotlin
class Something

object A {
    class Something
     
    /** 
     * Here [Something] refers to the nested class, 
     * as the nested class comes from a more local scope.
     */
    fun usage() = 0
}
```


Please note again that this only applies to cases when no [context declarations](#context-declarations) were found:

```kotlin
class Something

object A {
    /** 
     * Here [Something] refers to the function, as it is a context declaration.
     */
    fun Something() {}
}
```

So the general priority order can be described as following:
- [Context declarations](#context-declarations)
- Class (from local to global)
- Packages (global search)
- Functions (from local to global)
- Properties (from local to global)

#### K2 implementation

The K2 implementation mostly ignores declaration kinds and focuses on retrieving 
declarations by scopes.

Let's consider each step:
1. Firstly the resolver checks whether the link is `[this]` and retrieves the extension receiver
   from the documented extension callable.
2. Then the resolver considers the [lexical scope](https://en.wikipedia.org/wiki/Scope_(computer_science)#Lexical_scope_vs._dynamic_scope_2)
   of the documented declaration, i.e., member scope of every outer declaration in the order of their
   locality. 
   These scopes also contain context/type/value parameters of these outer declarations.
   Then, in each scope, the resolver tries to acquire matching declarations contained in this member scope.
   That's also the point where [context declarations](#context-declarations) are handled.
3. After the search through the lexical scope, the resolver moves on to other scopes: 
   * Explicit importing scope
   * Package scope
   * Default importing scope
   * Explicit star importing scope
   * Default star importing scope
4. After the local search, the algorithm retrieves global packages by the given name.
5. If no symbols were found on the previous steps, the link is considered to be fully qualified.
   The last step for the resolver is to search for non-imported declarations from other packages.

After the search is done, the resolver sorts the list of all the collected declarations prioritizing classes,
which is the only handling of declaration kinds.

### Disambiguation by overloads
In the case of overload functions, a KDoc link leads to the first occurrence in code. 
For example,
```kotlin
fun x(p: int) = 0
fun x() = 0

/** here [x] refers to the function x(p: int) */
fun usage() = 0
```
If such functions are in the same package in different files, it refers to an overload from the first file by order.
```kotlin
// FILE: a.kt
fun x(p: int) = 0
// FILE: b.kt
fun x() = 0

/** here [x] refers to the function x(p: int) */
fun usage() = 0
```

## Additional notes: K1 / K2 inconsistencies

The resolution behavior in the following cases is inconsistent between K1 and the current K2 implementations.

### Inconsistency with context declarations

K1 and K2 differ in the way they prioritize context declarations.

While preferring signature components (parameters, properties, etc.) of the documented declaration 
over other symbols, the K1 implementation doesn't prioritize the documented declaration itself:
```kotlin
/** 
 * [A] - K1/K2: Leads to the class
 */
class A

/** 
 * [A] - K1: leads to the class, K2: leads to the documented function
 */
fun A() = A()

/**
 * [A] - K1/K2: leads to the parameter
 */
fun foo(A: Int) = A()
```

The same problem applies to all possible pairs of declaration kinds in K1
(function and function, also known as the overload problem; property and class...).

During the resolution, constructors have the name as the corresponding class.
When KDoc is attached to some constructor, the K1 implementation still prefers the class:
```kotlin
class A    {
    /** 
    * [A] - K1: leads to the class A, K2: leads to the constructor 
    */
    constructor(s: String)
}
```

This can also be applied to nested classes with the same name as the enclosing class:
```kotlin
 /**
 *  [A] In K1, it leads to the nested class A.A. In K2 - to the outer class A
 */
class A {
    class A
}
```
The case above can be unpopular
since this construction is banned in such languages as Java and C#.

### Related problem: Availability/Visibility of nested classes from base classes
At the moment, the resolution of links to "inherited" nested classes varies between the two implementations,
as it's not supported by K1:
```kotlin
open class DateBased {
    class DayBased
}

/**
* [DayBased] K2 and Javadoc lead to [DateBased.DayBased], but in K1, it is unresolved
*/
class MonthBased : DateBased()
```
However, inherited members are still seen and resolved in K1.

### Ambiguity with links to constructor parameters

When properties are declared in primary constructors, there are
actually two declarations, even though they have the same code location.
One of them is the class property, and another one is the parameter of this constructor.

When a link points to such a property, the K1 implementation prefers the property,
while in K2 it's resolved to the corresponding constructor parameter.
```kotlin
/**
* [abc] K1 refers to the property `abc`, K2 - to the parameter
*/
class A(val abc: String)
```
However, this does not matter for the IDE tooling, as they both have the same PSI.
The link `[abc]` leads to the same position in a source file independently of whether the `abc` is a parameter or property.

#### Related problem: Availability/Visibility of constructor parameters

The K1 implementation considers primary constructor parameters as unavailable in class bodies.
In K2, such parameters are available in all the nested scopes, even in scope of nested classes.

```kotlin
class A(a: Int) {  
    /**  
    * [a] - K1: Unresolved, K2: resolved to the parameter
    */  
    fun usage() {}
    
    class B {
        /**  
        * [a] - K1: Unresolved, K2: resolved to the parameter
        */  
        fun usage() {}
    }
}
```

## Issues with the existing K1 implementation

### Global declarations breaking the local resolution

The main concern is the order of priorities in the **kind-first** approach.
With the current approach, global context can easily break the local resolution.

Take a look at the following example:
```kotlin
class bar // Breaks the resolution

fun foo() {
    fun bar() {}
    /**
     * [bar] -- Points to the local function until 
     * some class visible from this position is introduced.
     * No way to refer to the function afterward
     */
}
```

An introduction of a global declaration with a higher priority makes
all local links with the same name point to it.

And it's not always that easy to track it down. 
Accidental introduction of an import with the same name also poses the same danger:
```kotlin
import foo.bar // Breaks the resolution

fun foo() {
    fun bar() {}
    /**
     * [bar] -- Points to the local function until some class import is introduced.
     * No way to refer to the function afterward
     */
}
```

The same question can be applied to packages having the second-highest priority. 
This local shadowing issue is even harder to track down, as now it's not limited to just a single file. 

```kotlin
val io = 5

/** [io] -- points to `val io` until some `io` package is introduced
 *  in the current module or in some of its dependencies
 */
fun foo() {}
```

The **kind-first** approach is inconsistent by itself, 
as it initially assigns context declarations (i.e., local resolution) with the highest priority 
but then proceeds to prefer global declarations to local ones:
```kotlin 
class foo

object Something {
    /**
     * [foo] - points to the function
     */
    fun foo() {}

    /**
     * [foo] - points to the class
     */
    fun bar() {}
}
```

### Inconsistent multi-segment name resolution

Consider the following example:
```kotlin
class A {
    class B
}

class Foo {
    class A

    /** 
     * [A.B] - `A` is resolved to the nested class `Foo.A`, 
     *          but `A.B` is resolved to the nested class
     *          in the global class `A`
     */
    fun foo() {}
}
```

Here we have a two segment name `A.B`.
Each segment prefix of a multi-segment name can be viewed as a separate navigatable link.
Here we actually have two links that need to be resolved: `A` and `A.B`.
Ideally, the full name (`A.B` in this case) should be resolved first,
so that all segment prefixes of this link (`A` in this case) reference the corresponding parent
of the `A.B` declaration. So if `A.B` is resolved to some nested class `B`, then `A` should obviously
reference its outer class.

However, the current K1 implementation resolves the first segment of multi-segment names separately.
It treats it as a short link and tries to find it in the local scope.
That's why in the example above, the link `[A.B]` is correctly resolved to the nested class in the global `A`,
while its prefix link `[A]` is resolved to the nested class in `Foo.A`.

## Shared issues

### Misuses of tag sections
[Tag sections](https://kotlinlang.org/docs/kotlin-doc.html#block-tags) (or block tags) allow breaking the
KDoc into smaller sections for documenting various specific details of the declarations.

Some KDoc tags might require a subject link. Such tags are:
* `@throws`
* `@exception`
* `@param`
* `@property`
* `@see`
* `@sample`

A tag section requiring a subject considers the first word on the line to be a reference:
```kotlin
/**
 * The following examples are equivalent.
 * @param x is my Int parameter
 * @param [x] is my Int parameter
 */
fun foo(x: Int) {}
```

And while some of them are generic and can be used for all kinds of declarations, e.g., `@see`,
most of them are clearly supposed to accept specific kinds of declarations.
* `@param` should be used for function / constructor parameters / context parameters.
* `@property` should be used for links to class properties: 
both from the primary constructor and from the class body. .
* `@exception` / `@throws` should be used for `Throwable`s.

However, a lot of users are not familiar with block tags.
Additionally, the difference between parameter/property can easily be confusing in practice,
so there are a lot of, e.g, links to parameters that are put in `@property` sections.

The most obvious solution for this issue is to restrict the use of tag sections to their corresponding declaration kinds.
In fact, the K1 implementation already does this, however, just for the `@param` tag.

```kotlin
/**
 * K1:
 * @param x - unresolved, there are no parameters named `x`
 * K2:
 * @param x - resolved
 */
fun x() {}
```

### Issues with local context resolution

Both K1 and K2 fail to resolve links to local declarations inside function bodies and lambdas.

```kotlin
fun foo() {
    val x = 0

    /**
     * [x] - unresolved in K1/K2
     */
    val usage = 0
}
```

```kotlin
class MyClass {
    init {
        fun x() {}

        /**
         * [x] - unresolved in K1/K2
         */
        val y = 0
    }
}
```

```kotlin
fun foo() {
    listOf(1, 2, 3).map {
        /**
         * [it] - unresolved in K1/K2
         */
    }
}
```

However, if the lambda parameter is explicit, links to it are resolved:

```kotlin
fun foo() {
    listOf(1, 2, 3).map { it -> // explicit lambda parameter
        /**
         * [it] -- resolved in K1/K2
         */
    }
}
```

### KDoc names pointing to a single declaration
Currently, KDoc links can only be resolved to a single symbol.
Even though K1/K2 resolvers return multiple candidates for the same link,
the IDE only shows the first one.
However, the rest of the candidates are still highlighted as used in the code.

Since some declarations might have overloads,
KDoc names being able to point just to a single declaration feel too restricting.
Sometimes it’s crucial to support this behavior
(in the case with Dokka and other documentation-generating tools).
However, we could enhance the user experience by showing a drop-down list with all the found overloads in the IDE.
For use-cases when just a single declaration is required,
users of the KDoc resolver could pick the first element from the resulting collection,
which preserves the behavior.

# Proposal

This document proposes that existing KDoc links should point to only one
(or multiple in case of overloads and some ambiguities) specific location in the same way 
the Kotlin language does for name resolution in the code.

Meanwhile, the ambiguity of KDoc links is still inevitable.
Some might suggest providing an IDE warning informing users that their link is ambiguous, i.e.,
that there are multiple declarations available for the given name.
However, such an inspection would be useless and annoying, as there is no disambiguator at this time.
Therefore, the proposed approach is to prioritize candidates for KDoc links 
to deterministically choose a subset of all suitable declarations.

To provide this disambiguation mechanism, the following questions should be covered:
 - Which names are available from the current KDoc?
 - If there is more than one candidate, what should be prioritized?
 - If there are candidates that have the same priority, which should be chosen?

The following sections address these three questions.

## Context of KDoc

The KDoc context is the set of all declarations that are resolvable at a specific, 
imaginary place in the code of the documented property by the compiler.
Hence, KDoc resolution rules derive from the language's resolution rules.

For example, the KDoc context of a class is derived from the first line of a fake function body as such:
```kotlin
/**
 * [p] is contained in the KDoc context
 * [param] is not
 */
class A(param: Int) {
    companion object {
        val p = 0
    }
}
```
Here `[p]` is resolved since it is available on the first line of a fake function:
```kotlin
/**
 * [p] is contained in the KDoc context
 * [param] is not
 */
class A(param: Int) {
    fun fake() {
        p // is available here
        param // ERROR: unresolved
    }
    
    companion object {
        val p = 0
    }
}
```

We can see that the constructor parameter `param` is not accessible in this context.
It's not called "unresolved" as this link is a context declaration, so it's handled separately.

Declarations don't have to be resolvable in the code as-is.
It's contained in the KDoc context when there is a case this declaration is correctly referenced in the code.
It might be a direct call, might be a reference, 
but there must be a case when this declaration could be resolved in the code.
```kotlin
class A {
    fun myFun() {}

    /**
     * [myFun] is visible and resolved
     */
    class B {
        fun myFakeFun(x: A) {
            x.myFun() // Valid
            A::myFun // Valid
        }
    }
}
```

When KDoc is not attached to any declarations, an imaginary one can be inserted right below:
```kotlin
fun foo() {
    val bar = 1

    /**
     * [bar] is visible and resolved
     */
    // val myFakeVal = bar // Valid
}
```

Similarly, the KDoc context of functions/constructors corresponds to the beginning of their bodies, 
the KDoc context of properties corresponds to the beginning of property initializers, etc.

## Resolution strategy

We would like to make the resolution of KDoc names closer to what the compiler does when resolving references in code.
This approach is more natural, clear, and convenient to users,
as they are used to the way the compiler resolves references. 
The base logic here is that some KDoc link `[X]` should be resolved to declarations from its [context](#context-of-kdoc)
with the same name `X`.
If there are multiple declarations available, the resolver should pick a set of them which are the closest semantically
to the position of this KDoc.

Similarly to K1, the compiler prioritizes declarations during resolution 
based on their kinds and scopes of origin. 
However, the priorities are switched: locality of scopes has a higher priority than declaration kinds.
These declaration priorities are not that strict, the compiler derives additional information 
from the use-site of the reference (i.e., type/value parameters, implicit receivers, etc.),
while KDoc links are resolved without any additional information, just based on the link name.

Take a look at various examples of how call-site information affects the compiler resolution:
```kotlin
fun foo() {}

class foo(val x: Int)

fun main() {
    foo() // call to the function
}
```

```kotlin
val foo = 9

fun main() {
    class foo()
    
    foo // variable access
}
```

```kotlin
fun <T> foo() {}

class foo()

fun main() {
    foo<Int>() // call to the function
}
```

```kotlin
class foo()

fun main() {
    fun foo() {}
    
    foo() // call to the function
}
```

Since the only information the KDoc resolver has is the name of the declaration it's looking for,
prioritization of declaration kinds during the resolution is vital. 
However, locality of scopes should have higher priority than declaration kinds during the KDoc resolution.

The main point of this priority switch is to truly and always prefer local to global 
so that local links are not broken by the introduction of some global declarations.
This can be achieved by iterating through scopes from local to global and then looking for various declarations 
(w.r.t. their declaration kinds) just inside the current scope. 
This approach will be referred to as the **scope-first approach**.

More info on the compiler resolution can be found on the following resources:
* [Name Resolution | Compiler Specification Docs](https://github.com/JetBrains/kotlin/blob/4c3bbc5d4b8d8ada9f8738504b53c44019843d3b/spec-docs/NameResolution.adoc) (can be outdated)
* [Linked scopes | Kotlin Specification](https://kotlinlang.org/spec/scopes-and-identifiers.html#linked-scopes)
* [Overload resolution | Kotlin Specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution)

Now another problem arises: how can we deal with the global package search?
The K1 implementation prioritizes kinds over locality, so the algorithm in pseude code looks something like this:
```kotlin
for (scope in scopes) {
    searchForClasses(name)
}
searchForPackages(name)
for (scope in scopes) {
    searchForFunctions(name)
}
for (scope in scopes) {
    searchForProperties(name)
}
```

Since we are switching the priorities to prioritize locality over kinds, the new algorithm looks like this:
```kotlin
// searchForPackages(name)
for (scope in scopes) {
    searchForClasses(name)
    searchForFunctions(name)
    searchForProperties(name)
}
// searchForPackages(name)
```

Now we cannot integrate the global package search into this local scope traversal.
So this search should either go first (have the highest priority) or go last.
The first option obviously contradicts the idea of preferring local declarations as much as possible. 
The proposed idea is to assign it with the lowest priority.
It makes it impossible to refer to packages in some cases, 
but it's inevitable when there is no any disambiguating syntax:
```kotlin
package io

val io = 5

/** [io] -- points to `val io`, [io.io] - points to `val io` */
fun foo() {}
```

That was an informal description of the mindset behind this change.
Let's systemize this information in the following chapters:

## Resolution algorithm

All names should be treated according to the following three-step algorithm:

1. [Perform context declaration search](#step-1-perform-context-declaration-search)
2. [Perform scope traversal](#step-2-perform-scope-traversal)
3. [Perform package search](#step-3-perform-package-search)

### Step 1. Perform context declaration search

When the given name is short (i.e., contains just one segment),
the resolution should start by looking for declarations with the same name in the context declaration.
The order of these context declarations is the following:

When the context declaration is a class:
1. The class itself
2. Class type parameters
3. Primary constructor
4. Primary constructor properties
5. Primary constructor value parameters

Note that when the context declaration is a class,
the KDoc documents both the class and its primary constructor (since they have the same location).
So it's necessary to retrieve declarations from both of them.

When the context declaration is a function, property, or secondary constructor:
1. The declaration itself
2. Value parameters
3. Context parameters
4. Type parameters

Otherwise, when the context declaration is none of the above,
only the context declaration itself should be considered.

The resolver should also handle `[this]` receiver links in a proper way.
If the documented declaration is an extension callable, then `[this]` should be resolved
to the extension receiver of the current declaration.
In Kotlin, `this` is a special hard keyword, no declarations are allowed to be named this way,
so there won't be any ambiguity and this step can be performed at any moment.

### Step 2. Perform scope traversal

#### Short names treatment

If the name is short, i.e., contains just one segment, and the context declarations search did not find any suitable declarations,
the resolution should start by looking for declarations in all the scopes that are visible from the current position.

Firstly, the resolver should retrieve a list of scopes for the current position.
These scopes are:

1. **Local scope**. 
    These are the most local declarations,
    like variables declared inside functions when the KDoc is placed in their bodies. 
2. **Scopes of outer classifiers**.
    These scopes contain both non-static and static members of outer classes, including inherited members.
3. **Explicit importing scopes**. Declarations from explicit imports (`import A.B.myName`).
4. **Current package scope**. Global declarations from the current package.
5. **Explicit star importing scopes**. Declarations from star imports (`import A.B.*`).
6. **Default importing scope**. Default imports from the Kotlin language.
7. **Default star importing scope**. Default star imports from the Kotlin language.

```kotlin
// Package scope
package myPackage

import foo.bar.A // Explicit regular importing scope
import something.* // Explicit star importing scope

class MyClass {
    // Type scope

    companion object {
        // Static type scope
        fun staticFoo() {}
    }

    fun <T> foo() {
        // Local scope
    }
}
```

Then it should iterate through all the scopes 
and gather declarations with the given name, according to the following priorities:
1. Class
2. Function
3. Property

If there are any declarations of the same kind found in the current scope,
all these declarations should be immediately returned.

#### Multi-segment names treatment

The handling of multi-segment names here is a bit trickier.
A multi-segment name can be either a relative name or a global FQN.
And there is no way to tell which one we are looking at before the resolution is performed.
Such links are first considered as relative links,
and then they are treated as global links.

1. [Relative name resolution](#relative-name-resolution)
2. [Global name resolution](#global-name-resolution)

Here it's crucial to understand the following algorithm that helps us to
transform multi-segment name search into a single-segment name search:

##### Scope reduction algorithm

This algorithm will be actively used in the following sections.
It helps us to apply the same logic as we used for short names to the multi-segment name search.

Imagine that we have some relative name, e.g. `A.B.C` in the following case:
```kotlin
class Global {
    class A {
        class B {
            class C {
                companion object {
                    /**
                     * [A.B.C]
                     */
                    fun foo() {}
                }
            }
        }
    }
}
```

A relative name is a multi-segment name that is relative to some position visible from the current context.
In this case, the relative name is `A.B.C` and it's relative to the `Global` class.

The resolver has already calculated the list of visible scopes 
when searching for short names in [Short names treatment](#short-names-treatment).
If a relative link is resolvable, then it must be relative to any of these visible scopes.

To reduce this multi-segment search to a single-segment one, 
all these scopes have to be slightly modified.

`A.B.C` implies that somewhere there is a chain of two nested classifiers `A` and `B` with `B`
containing some member `C`.
The resolver should try to find a classifier `A` in each of these scopes and, if found,
replace the original scope with the member scope of this class.
If no suitable classes are found in some scope, this scope should be discarded.
Then the resolver should take all the modified scopes and repeat the search for nested classifiers (`B` in this case)
until just the short name `C` is left.
Then it's just a single-segment name search in the constructed list of scopes.

Note that currently it's prohibited to have a variable as a link segment.
That's why each non-final segment of the name should be resolved to a classifier.
```kotlin
/**
 * [name.length] - unresolved, prohibited
 */
fun foo(name: String) {}
```
Whether such links should be resolvable is out of the scope of this proposal.

##### Relative name resolution

```kotlin
object Something {
    class Foo {
        companion object {
            class Bar
        }
        
        /**
         * [Foo.Companion.Bar] -- Multi-segment, but not global FQN
         */
        fun foo() {
            Foo.Companion.Bar()
        }
    }
}
```
To handle such cases, we can use [Scope reduction algorithm](#scope-reduction-algorithm) 
and then process the same way as for short names.

##### Global name resolution

A multi-segment name can also be global, i.e., start with a package name.

If the search for relative names fails to resolve the link as a relative one, the resolver
should fall back to the global search.

If we have some name `A.B.C.D.foo`, then we have to find the longest existing package,
which name is a segment prefix of this link (search for `A.B.C.D`, then for `A.B.C` and so on).
Let's imagine that the search stopped on some package `A.B`.
The only thing left to do is to resolve `C.D.foo` inside this package scope.
Now we should again use the [Scope reduction algorithm](#scope-reduction-algorithm) 
and then just search for the short name `foo` in the resulting sequence.

```kotlin
package A.B

class C {
    object D {
        fun foo() {}
    }
}
```

### Step 3. Perform package search

If no declarations were found on any of the previous steps, a global package search should be performed.
Note that the real package name should fully match the name specified in the link.
If a link is just `bar`, then it cannot be resolved to some package called `foo.bar`,
as it matches only the last segment but not the full package name.

### Result of the resolution

The result of the resolution is the set of all declarations found on the first successful algorithm step out of three.
It's important to preserve the priority of declarations in the resulting collection for purposes
specified in [Handling resolved declarations on the use-site](#handling-resolved-declarations-on-the-use-site).

Unfortunately, there is no disambiguation syntax
that would allow explicitly specifying the kind or other details of the declaration the current link should point to:
```kotlin
/**
 * [Foo] - leads to the interface,
 * no way to point to the function.
 */
class Usage

interface Foo 

fun Foo(): Foo = object : Foo {}
```
Whether the resolver should return all declarations from some scope or just declarations of a single kind from this scope
is still a question.

## Resolution of tag sections subjects

When the link is a subject of a tag section, the resolver
should only look for declarations relevant for the current tag section.

- `@param x` must only be resolvable to value / type / context parameters of the documented declaration.
  When the context declaration is a class, property parameters of its primary constructor are also considered
  as parameters (`class Foo(val x: Int)`). Additionally, it's important to prohibit referencing extension receivers
  using `@param this`, as there is a dedicated `@receiver` tag for such cases.
- `@property x` must only be applicable to class documentation and must be resolvable to all properties
  of the documented class (both properties of the primary constructor and body properties).
- `@exception` / `@throws` must only be resolvable to `Throwable`s.

## Handling resolved declarations on the use-site

There are a number of various logic pieces that heavily rely on the KDoc name resolver:
* Import optimizer
* Usage finder (that also highights declarations that the link points to)
* Navigation actions
* Documentation renderers (Dokka, rendered HTMLs in the IDE)

We would like all these usages to be as consistent as possible with each other.

Currently, most usages handle all the declarations that are returned by the resolver (i.e., import optimizer).
However, navigation actions that are also provided to users only navigate to a single declaration from the returned set
(the first one to be precise).
This might be quite confusing to users, as in the following code both functions are highlighted as *used*,
but *Go To Declaration* action takes users just to the first found declaration:

```kotlin
fun foo() {}
fun foo(x: Int) {}

/**
 * [foo]
 */
fun usage() {}
```

The IDE should show a popup drop-down menu the same way it does with other ambiguous links:
![example](https://i.ibb.co/dKQkshh/image.png?)

However, tools that require just a single resolved declaration, e.g.,
for HTML rendering purposes, should be able to pick just the first declaration from the resulting collection.
That's why it's important to construct this collection in such a way 
that more local declarations with higher priority go first.
It makes the result consistent and reliable.

# Appendix

## Other considered approaches and ideas

Here are some other ideas that were considered during the development of this proposal
but didn't work out as well as we expected.

### Special resolution context inside tag sections

The following KDoc tag sections should introduce their new scope in the documentation:
`@constructor`, `@param`, `@property`. 
Previously, these tag sections never affected the resolution of links placed inside them. 
All other tag sections must not affect the resolution in any sense.

#### @constructor section

The tag `@constructor` can be applied to classes
and has context where parameters of the primary constructor and the constructor itself are available and prioritized.
These declarations should not be accessible in the regular documentation section.

The motivation behind this is rather simple: the class documentation should
describe the data the class represents and not how this class is constructed.

```kotlin
/**
 * [p] ERROR: unresolved
 * @constructor [p] is resolved
 */
class A(p: Int, val abc: String)
```

There is another example for `@constructor`:
```kotlin
/** 
 * [A] - to the class, [abc] - to the property 
 * @constructor [A] - to the constructor, [abc] - to the parameter 
 */ 
class A(var abc: String)
```

The constructor from the example above can be easily refactored to be a secondary constructor.
Here we clearly see that the `abc` parameter only belongs to this constructor is not seen from the class
(i.e., from the first line of a fake function).
However, the property is fully accessible.

```kotlin
/**  
 * [A] - to the class, [abc] - to the property 
 */
class A private constructor() {  
    /**  
    * [A] - to the constructor, [abc] - to the parameter 
    */  
    constructor (abc: String): this()  
    lateinit var abc: String  
}
```

Note that the constructor symbol is prioritized over parameter symbols:
```kotlin
/**
 * [abc] - to the class
 * @constructor [abc] - to the constructor
 */ 
class abc(var abc: String)
```

#### @param section

`@param` section can be applied to classes and functions.
When applied to classes, it also makes the constructor and its parameters available the same way `@constructor` does;
however, parameters have higher priority.

The motivation for this is the same as for the `@constructor` section:
the class documentation should describe the data the class and not some construction details.

```kotlin
/**
 * [A] - to the class, [abc] - to the property 
 * @param [A] - to the constructor, [abc] - to the parameter 
 */ 
class A(var abc: String)
```

```kotlin
/** 
 * [abc] - to the class
 * @param [abc] - to the parameter
 */ 
class abc(var abc: String)
```

In the case of functions, all the parameters are available from a regular KDoc.
`@param` section can be used to refer to the parameter instead of the function in case of name clashes.
```kotlin
/** 
 * [abc] - to the function
 * @param [abc] - to the parameter
 */ 
fun abc(var abc: String) {}
```

#### @property section

`@property` section can be applied to classes to prioritize properties from this class.
Note that class properties are accessible from other tag sections as well.

```kotlin
/**
 * [abc] - to the class
 * @property [abc] - to the property
 */ 
class abc(var abc: String)
```

A small summary example:

```kotlin
/** 
* [abc] - to the class
*
 * @constructor [abc] - to the constructor
 * @param [abc] - to the parameter
 * @property [abc] - to the property
 */ 
class abc(var abc: String)
```

#### Why this idea was rejected

The main pain point of this approach was the inability to reference primary constructor parameters
from the regular class documentation section.
It's not that critical for primary constructors to be unavailable from the regular documentation section
as the constructor and the class have the same PSI, so the navigation is not affected.
However, unavailable primary constructor parameters
introduce a number of problems for documentation writers:

* A lot of people still prefer writing plain documentation instead of using structured tags.
  This change would force developers to use tag sections even if it's unnecessary in some cases.
* `@param` and `@property` sections require a subject, i.e., the first word after the tag section
  is always considered to be a link (`@param x` is equivalent to `@param [x]`).
  So if some parameter is not the declaration that is currently being documented
  and just has to be placed in the middle of some other sentence, 
  then it's impossible to reference it.
    ```kotlin
    /**
     * Accepts [x] and processes it somehow.
     * -- Unresolved, impossible to reference [x], needs `@param` section
     * 
     * @param x - represent some data
     * -- Resolved, intended way to reference [x]
     * 
     * @throws MyException if [x] is negative 
     * -- Unresolved, impossible to reference [x], needs `@param` section
     */
    class ClassAcceptingInt(x: Int)
    ```
    ```kotlin
    /**
     * [x] -- points to the property
     * @param x -- points to the constructor parameter
     */
    class SomeClass(x: Int) {
        private var x = doProcessAndCheck(x)
    }
    ```
* It's unintuitive to users because classes are always merged with their primary constructors,
  so the class documentation should be used to describe both the class and its primary constructor.
  It would be seen as a bug if constructor parameters are not available 
  from the regular documentation section.

### Restrictions of multi-segment name resolution

We would like our KDoc resolution to be as close as possible 
to the way Kotlin resolves names in the code.
So we should only resolve multi-segment names when the language does so.

Imagine that we have some name `A.B.C` that we would like to point to.
The location of this declaration relatively to the given position is not important.
For the sake of simplicity, let's put `A.B.C` in some other package
just that there are no conflicting overloads within the same package.
Now take a look at the following examples:
```kotlin
// FILE: A.B/C.kt
package A.B

class C

// FILE: Usage.kt
package usage

/**
 * [A.B.C] - should be resolved
 */
fun usage() {
    A.B.C() // RESOLVED
}
```

The reference is correctly resolved.
But take a look at how it changes after introducing other declarations in the same package:
```kotlin
// FILE: Usage.kt

fun A() = 5 // Function

/**
 * [A.B.C] - should be resolved
 */
fun usage() {
    A.B.C() // RESOLVED
}
```

```kotlin
// FILE: Usage.kt

val A = 5 // Property

/**
 * [A.B.C] - should be unresolved
 */
fun usage() {
    A.B.C() // UNRESOLVED
}
```

```kotlin
// FILE: Usage.kt

class A // Class

/**
 * [A.B.C] - should be unresolved
 */
fun usage() {
    A.B.C() // UNRESOLVED
}
```

```kotlin
// FILE: Usage.kt

class Something

fun Something.A() {} // Extension

/**
 * [A.B.C] - should be resolved
 */
fun Something.usage() {
    A.B.C() // RESOLVED
}
```

```kotlin
// FILE: Usage.kt

class Something

val Something.A: Int // Extension
    get() = 5

/**
 * [A.B.C] - should be unresolved
 */
fun Something.usage() {
    A.B.C() // UNRESOLVED
}
```

As we can see, the compiler only resolves this name when there are
no other non-function (class or property) more local declarations that are resolvable by some segment prefix of the given name.

Otherwise, some segment prefix is resolved to this declaration and the rest of the chain is unresolved,
as the resolved declaration doesn't have any suitable nested symbols.

This should be also taken into account when resolving links in KDoc:
Before performing a [scope reduction](#scope-reduction-algorithm) for relative names, the resolver has to make sure that there are no
non-function declarations matching some segment prefix of the given name in a more local scope.
If such a declaration is found in some scope, then there is no need to process all further scopes,
as the compiler would resolve this segment prefix to the declaration in this scope.

```kotlin
interface A {
    companion object {
        val B: Int = 0
    }
}

class Usage {
    val A: Int = 0

    /**
     * [A.B] // Should be unresolved
     */
    fun usage() {
        A.B // Unresolved, A is resolved to a more local property
    }
}
```

#### Why this idea was rejected

The main problem is that KDoc resolution has a larger scope than the language does.
That's because KDoc links ignore all visibility / accessibility modifiers during the search. 

Take a look at the example below:
```kotlin
interface A {
    companion object {
        val B: Int = 1
    }
}

class Usage1 {
    val A: Int = 1
    
    class Nested { // Not inner
        /**
         * [A.B]
         */
        fun foo() {
            A.B // Resolved, Usage1.A is not visible
        }
    }
}


class Usage2 {
    val A: Int = 1

    inner class Nested { // Inner
        /**
         * [A.B]
         */
        fun foo() {
            A.B // Unresolved, Usage2.A is visible
        }
    }
}
```

`Usage1` and `Usage2` both have the same property `A` and the same nested class `Nested`.
However, `Usage2.Nested` is inner while `Usage1.Nested` is not.
Since `Usage1.A` is not visible from `Usage1.Nested`, `A.B` in `Usage1.Nested` is resolved in the code.
However, `Usage2.A` is accessible from `Usage2.Nested`, so `A.B` in `Usage2.Nested` is unresolved,
as the first segment `A` is actually resolved to `Usage2.A` property.

But KDoc links ignore declaration visibility, so this `A` property is visible from 
both KDoc comments. If we stop the resolution pipeline after encountering `val A`, 
the `A.B` link in `Usage1` will be unresolved, even though it's a valid link from
the language perspective.

Additionaly, the language resolution doesn't always apply these restrictions when resolving multi-segment names.
Take a look at the following example:
```kotlin
class A {
    class B
}

class Usage {
    class A
    
    fun foo() {
        val x: A.B = A.B()
    }
}
```
Here the type specification `x: A.B` is resolved, however, the actual constructor call `A.B()` is not.
That's because the language only applies these restrictions to multi-segment calls resolution.
However, when resolving types from type specifications, the compiler goes through all the scopes until it finds
a successful chain.

Since we would like the KDoc resolution to be a superset of the language resolution and not a subset,
we have to reject this idea.

## Other languages

All considered languages can be divided into two groups by the way they deal with ambigous links:
 * Ambiguous links are allowed (Java);
 * Ambiguous links are disallowed and cause a warning. In this case, a language provides a mechanism to disambiguate them. (Swift, *C#*, Rust, *Golang*)

### Javadoc

[Javadoc Documentation Comment Specification: References](https://docs.oracle.com/en/java/javase/22/docs/specs/javadoc/doc-comment-spec.html#references) describes the specification of Javadoc references a little:
> the parameter types and parentheses can be omitted if the method or constructor is not overloaded and the name is not also that of a field or enum member in the same class or interface.

Homewer,
[Javadoc's style guide](https://www.oracle.com/technical-resources/articles/java/javadoc-tool.html#styleguide) allows
omitting parentheses for the general form of methods and constructors.
In this case, an ambiguous reference will lead to:
 - a field if it exists
 - otherwise, to the first occurrence of overload in the code. 
For example, 
```java
/**  
 * {@link #f} references f(int p)
 */
 public class JavaClassA {  
    public void f(int p) {}
    /**  
    * {@link #f} references f(int p)
    */ 
    public void f() {}  
}
```
Also, by the specification, `#` may be omitted for members:
```java
/**  
 * {@link f} references the field f
 */
 public class JavaClassB {  
    public void f(int p) {}  
    public void f() {}  
    public int f = 0;
}
```

Meanwhile, a class always has a priority:
```java
/**  
 * {@link JavaClassA2} leads to the class  
 * {@link #JavaClassA2} leads to the field  
 */
 public class JavaClassA2 {  
    public void JavaClassA2(int p) {}  
    public JavaClassA2() {}  
    public int JavaClassA2 = 0;  
}
```
Also, Javadoc does not provide a way to reference function parameters. 
    

### JavaScript (JSDoc)

JSDoc does not have such problems with relative links
since it has a unique identifier like a fully qualified path in Kotlin.
For `@link` tag (https://jsdoc.app/tags-inline-link) there is a namepath. 
```js  
/**
 * See {@link MyClass} and [MyClass's foo]{@link MyClass#foo} that just opens MyClass.html#foo
 */
function usage() {}

/**
 * MyClass
 * {@link foo} or {@link #foo} are unresolved in JSDoc, but resolved in IDE with a popup
 * {@link MyClass#foo} is resolved
 */
class MyClass {
    /**
    * foo function
    * {@link MyClass#foo} is resolved
    * {@link foo} or {@link #foo} are unresolved
    */
    foo() {}
    
    /**
    * foo field
    */
    foo = "John";
}
```
A namepath provides a way to do so and disambiguate between instance members, static members, and inner variables.
See [https://jsdoc.app/about-namepaths](https://jsdoc.app/about-namepaths)
```js
/**
{@link Person#say}  // the instance method
{@link Person.say}  // the static method 
{@link Person~say}  // the inner method
*/
Person = function() {
    this.say = function() {
        return "I'm an instance.";
    }

    function say() {
        return "I'm inner.";
    }
}
Person.say = function() {
    return "I'm static.";
}
```

Also, it does not allow having a class and a function with the same name in a single scope.

### Python (Sphinx)

Python allows having a cross-reference via the markup 
(see [Cross-referencing Python objects](https://www.sphinx-doc.org/en/master/usage/domains/python.html#cross-referencing-python-objects)).  
There are some roles:  `:py:class` `:py:func`  `:py:meth:` `:py:attr:` and so on.

> Normally, names in these roles are searched first without any further qualification, then with the current module name prepended, then with the current module and class name (if any) prepended.

> If you prefix the name with a dot, this order is reversed. For example, in the documentation of Python’s [codecs](https://docs.python.org/3/library/codecs.html#module-codecs) module, :py:func:`open` always refers to the built-in function, while :py:func:`.open` refers to [codecs.open()](https://docs.python.org/3/library/codecs.html#codecs.open).

> Also, if the name is prefixed with a dot, and no exact match is found, the target is taken as a suffix and all object names with that suffix are searched. For example, :py:meth:`.TarFile.close` references the tarfile.TarFile.close() function, even if the current module is not tarfile. Since this can get ambiguous, if there is more than one possible match, you will get a warning from Sphinx.

```python
# FILE:: tets.py
def main():  
    """The reference :func:`.foo`"""

# FILE:: tets2.py
def foo():
	pass
	
# FILE:: tets3.py
def foo():
	pass
```
This code causes the warning `more than one target found for cross-reference 'foo': test2.foo, test3.foo`, but the link leads to the first occurence `test2.foo`

Python does not support method overloading.

### Swift

Swift does not allow ambiguous links, although the IDE suggests fixing them.
If a reference is ambiguous, it is unresolved and will be displayed as plain text.

![example](https://i.ibb.co/9s3pmyp/Screenshot-2024-07-13-at-12-38-09-AM.png)

 
Swift also provides mechanisms for the links disambiguation.
See [Navigate to a symbol](https://www.swift.org/documentation/docc/linking-to-symbols-and-other-content#Navigate-to-a-Symbol)

#### 1. Suffixes of overloads

```swift
///  ``update(_:)-6woox``
///  ``update(_:)-6wqkp``
///  ``update(_:)`` is unresolved, a warning
func usage() {}

///  ``update(_:)`` is resolved to itself
func update(_ power: String) {}
func update(_ energyLevel: Int) {}
```

#### 2. Suffixes of symbol types

```swift
///  ``Color-property``
///  ``Color-class`` or ``Color-swift.class``
class  FF {
	///  ``Color`` is resolved to itself
	///  ``Color-class`` is unresolved
	let Color = 0;
	public  struct  Color {}
}
```

### C#

It has [XML documentation](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/documentation-comments).
A generated XML file then passes to a documentation generator, e.g., Sandcastle.

The `cref` attribute is used to provide a reference to a code element. 
The C# documentation does not describe the cases of overloads and ambiguous links.
The support of such links depends on the chosen documentation generator.

The documentation generator must respect namespace visibility according to using statements appearing within the source code.

```csharp
///  <seealso  cref="Foo(int)"/>
///  <seealso  cref="Foo()"/>
///  <seealso  cref="Utils.Foo()"/>
public  class  Utils {
	///  <seealso  cref="Foo"/> Here is a warning (ambiguous) in VSCode
	static  void  Foo() { }
	static  void  Foo(int  a) { }
}
```
Similar to Java, C# does not allow having a nested class with the same name as enclosing class.

### Rust

In case of ambiguity, rustdoc will warn about the ambiguity and suggest a disambiguator.
See [Disambiguators](https://doc.rust-lang.org/rustdoc/write-documentation/linking-to-items-by-name.html#namespaces-and-disambiguators)

```rust
/** 
[fn@Foo] or [Foo()]
[struct@Foo]  
[Foo] is unresolved. Here is a warning (ambiguous link)
*/  
struct Foo {}  
fn Foo() {}
```
There are no overloads in Rust.

### Golang

See [Go Doc Comments: Links](https://tip.golang.org/doc/comment#doclinks)
> If different source files in a package import different packages using the same name, then the shorthand is ambiguous and cannot be used.

Godoc generally prohibits any ambiguity in links.
Unfortunately, there is no detailed documentation on how exactly it handles various cases.  
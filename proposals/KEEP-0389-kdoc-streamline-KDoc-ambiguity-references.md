# Streamline KDoc ambiguity links

* **Type**: Proposal
* **Author**: Vadim Mishenev
* **Status**: Submitted
* **Issues**: [dokka/#3451](https://github.com/Kotlin/dokka/issues/3451), [dokka/#3179](https://github.com/Kotlin/dokka/issues/3179), [dokka/#3334](https://github.com/Kotlin/dokka/issues/3334) 
* **Discussion**: [#389](https://github.com/Kotlin/KEEP/issues/389)

# Summary

This document introduces clarifications and improvements to the existing implementation of [KDoc](https://kotlinlang.org/docs/kotlin-doc.html) regarding resolving ambiguous KDoc links.
It addresses the range of issues ([dokka/#3451](https://github.com/Kotlin/dokka/issues/3451), [dokka/#3179](https://github.com/Kotlin/dokka/issues/3179), [dokka/#3334](https://github.com/Kotlin/dokka/issues/3334)) that Dokka and the IntelliJ Plugin faced during the migration to the K2 resolver from **Kotlin Analysis API**.
 
# Motivation
 
## Introduction

[KDoc](https://kotlinlang.org/docs/kotlin-doc.html) is a language used to write Kotlin documentation.
It's an equivalent of Javadoc from Java.
As well as Javadoc, it allows embedding navigatable references to code declarations right in the documentation using square brackets `[]`.

There are two types of KDoc links to a declaration:
-   Fully qualified ones, for example `[com.example.classA]`, starting with a full package name
-   Relative ones (also known as short names), for example `[memberProperty]` / `[classA.myObject.method]`.

Also, KDoc allows referring to:
-   Functional parameters `[p]` or type parameters  `[T]`.  They can be not only in `@param [p]` or `@param p`
-   A receiver via `[this]`
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

### What is an ambiguity?

An ambiguity in KDoc name resolution is a case when there are multiple symbols available for the same KDoc reference.

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

It contains various declarations with the same name `A`: imports, packages, classifiers, variables, functions, etc.
To which symbol should the KDoc name `A` resolve to?
This is why KDoc resolution requires a set of strict rules and priorities,
which can help to create a consistent and disambiguating resolution strategy.

## How does the current K1 / K2 implementation prevent ambiguity? 

There can be various ambiguous cases,
which both K1 and K2 handle using the same mechanisms and rules to deal with them.

### Self-links
Javadoc and KDoc allow having links to the elements from the context declaration, it is a quite popular practice.
Such links are called `self-links`.
Such links are widely used in the various libraries (e.g. Kotlinx) because of their visual consistency and simplicity.

```kotlin
fun foo() {}
val x = 0
class T

/**
 * [foo], [x], [T] - self-links, point to symbols from the context declaration,
 * i.e. `foo` function
 */
fun <T> foo(x: Int) {}
```

Such context symbols have the highest priority among all other symbols with the same name.
Their principle is quite natural and convenient in use, as they cannot be broken by introducing some other declaration.

For example,
```kotlin
/** [A] - to val A */
val A = 0
```
After adding a function, the link is left the same.
```kotlin
fun A() = 0
/** [A] - to val A */
val A = 0
```


### Disambiguation by a kind of declaration and by order of scopes

When there are no suitable self-links found,
the current resolution strategy handles symbol priorities based on two properties:
- Declaration kind
- Scope of origin

There are four main kinds of declarations (from higher priority to lower):
-  Classifier
-  Package
-  Function
-  Property

For some name `x`, the resolver iterates through these kinds and considers them one-by-one.
For each declaration kind (except for packages, as their search is global) the resolver tries to find 
an instance of this declaration kind with the given name `x` in every scope that is visible from the KDoc position.
It starts from local scopes (function body scope, type scope, etc.) 
and proceeds to global scopes (package scope, explicit import scopes, star import scopes, scopes of other packages).
The result of such an approach is a single most local symbol of the highest priority kind possible. 

This strategy will be then referred to as the **declaration-kind-first approach**.

Some examples:
```kotlin
val x = 0
fun x() = 0

/** here [x] refers to the function, as it has higher priority */
fun usage() = 0
```

```kotlin
class Something

object A {
    fun Something() {}
     
    /** here [Something] refers to the class, as classes have higher priority than functions */
    fun usage() = 0
}
```

```kotlin
class Something

object A {
    class Something
     
    /** Here [Something] refers to the nested class, 
     * as the nested class is from a local scope, which is seen from this position
     */
    fun usage() = 0
}
```


Please note again that this only applies to cases when no `self-links` (mentioned above) were found:

```kotlin
class Something

object A {
    /** Here [Something] refers to the function, as it is our context declaration.
     * This link is considered to be a so-called self-link
     */
    fun Something() {}
}
```

So the general priority order can be described as following:
- Self-links (symbols from the context declaration)
- Classifier (from local to global)
- Packages (global search)
- Functions (from local to global)
- Properties (from local to global)

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

### 1. Inconsistency with self-links

```kotlin
 /**
 *  [A] In K1, it leads to the nested class A.A. In K2 - to the outer class A
 */
class A {
    class A
}
```
The case (a nested class with the same name as the enclosing class) can be unpopular since it is banned in Java, C#.

There is a more practical case in Kotlin with a factory function :
```kotlin
/** [A] */
class A
/** [A] */
fun A(p: Int) = A()
```
In K1, both links lead to the class A. In K2, each of them leads to its context declaration.
This case can be applied to all possible pairs of declaration kinds
(function and function, also known as the overload problem; property and class...).

Also note that constructors have the name of the corresponding class during the resolution.
```kotlin
class A    {
    /** 
    * [A] In K1, it leads to the class A. In K2 - to the constructor 
    */
    constructor(s: String)
}
```

### Related problem: Availability/Visibility of nested classes from base classes
At the moment, the resolution of references to "inherited" nested classes is different between two implementations,
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
K1 implementation
failing to determine what declarations the current context of KDoc contains leads to unresolved references.
However, inherited members are still seen and resolved.

### 2. Ambiguity with links to constructor parameters

In a primary constructor, the same link can refer to parameters and properties. 
This does not matter for the IDE.
Opposite, Dokka treats parameters and properties differently.
```kotlin
/**
* [abc] K1 refers to the property `abc`, K2 - to the parameter
*/
class A(val abc: String)
```
From the point of IDE view,
the link `[abc]` leads to the same position in a source file independently of whether the `abc` is a parameter or property.

### Related problem: Availability/Visibility of constructor parameters
The availability of parameters inside a scope can result in ambiguous links (in case of other symbols with the same name inside the type scope).
```kotlin
class A(a: Int) {  
    /**  
    * [a] is unresolved in K1. In K2, it is resolved
    */  
    fun usage() = 0
}
```

# Issues with the existing K2 implementation

## Global declarations breaking the local resolution

The main concern is the order of priorities in the **declaration-kind-first** approach.
With the current approach, global context can easily break the local resolution.

Take a look at the following example:
```kotlin
class bar

fun foo() {
    fun bar() {}
    /**
     * [bar] -- Points to the local function until a global class is introduced.
     * No way to refer to the function afterward
     */
}
```

An introduction of a global declaration with a higher priority makes
all local references with the same name point to it.

And it's not always that easy to track it down. 
Accidental introduction of an import with the same name also poses the same danger:
```kotlin
import foo.bar

fun foo() {
    fun bar() {}
    /**
     * [bar] -- Points to the local function until a global class is introduced.
     * No way to refer to the function afterward
     */
}
```

The same question can be applied to packages having the second-highest priority. 
This local shadowing issue is even harder to track down, as now it's not limited to just a single file. 

```kotlin
val io = 5

/** [io] -- points to `val io` until some `io` package is introduced somewhere else */
fun foo() {}
```

The **declaration-scope-first** approach is inconsistent by itself, 
as it initially assigns self-links (i.e., local resolution) with the highest priority 
but then proceeds to prefer global symbols to local ones:
```kotlin 
class foo

object Something {
    /**
     * [foo] - POINTS TO THE FUNCTION
     */
    fun foo() {}

    /**
     * [foo] - POINTS TO THE CLASS
     */
    fun bar() {}
}
```

## KDoc names pointing to a single symbol
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
(or multiple in case of overloads) specific location in the same way 
as the compiler does for name resolution.

Meanwhile, the ambiguity of KDoc links is inevitable.
Just a warning about ambiguity does not make sense as there is no disambiguator at this time.
Therefore, the proposed approach is to prioritize candidates for KDoc links 
to deterministically choose a subset of all suitable declarations.

To provide this disambiguation mechanism, the following questions should be covered:
 - Which names are available from the current KDoc?
 - If there is more than one candidate, what should be prioritized?
 - If there are candidates that have the same priority, which should be chosen?

The following sections addresses these three questions.

## Context of KDoc

Here context is the set of all name bindings available by its short names in a current KDoc comment ([scope vs context](https://en.wikipedia.org/wiki/Scope_(computer_science))). 
For example, the KDoc context of a class should correspond to the first line of a fake function.

```kotlin
/**
 * [p] is resolved
 * [param] ERROR: unresolved
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
 * [p] is resolved
 * [param] ERROR: unresolved
 */
class A {
    fun fake() {
        p // is available here
        param // ERROR: unresolved
    }
    companion object {
        val p = 0
    }
}
```
That means that the KDoc link should have the set of available names as the first line of a fake function.
Note the rule ignores the visibility of declarations.
Similarly, the KDoc context of functions/constructors corresponds to the beginning of the first line of a body, 
and the KDoc context of properties corresponds to the beginning of property initializers.
 
### KDoc tag sections
The KDoc tag sections (`@constructor`, `@param`, `@property`) should introduce their new scope in the documentation.
Previously, they've never affected the resolution. 
All other tag sections must not affect the resolution in any sense.

#### @constructor section

The tag `@constructor` can be applied to classes and secondary constructors
and has context where parameters of the context constructor and the constructor itself are available.
 
```kotlin
/**
 * [p] ERROR: unresolved
 * @constructor [p] is resolved
 */
class A(p: Int, val abc: String) {
    /**
    * [p] ERROR: unresolved
    */
    fun f() {
        p // ERROR: unresolved
    }
	
    /**
    * [p] is resolved
    */
    val prop = p
}
```

There is another example for `@constructor`:
```kotlin
/** 
 * [A] - to the class, [abc] - to the property 
 * @constructor [A] - to the constructor, [abc] - to the parameter 
 */ 
class A(var abc: String)
```

The idea behind this resolution is rather simple. 
The constructor from the example above can be easily refactored to be a secondary constructor.
Here we clearly see that the `abc` parameter only belongs to this constructor and not seen from the class
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


```kotlin
/**
* [A] - to the class, [a] - unresolved
* @constructor [A] - to the current constructor, [a] - to the parameter
*/
class A(a: Int) {
    
    /**
    * [A] - to the current constructor, [a] - unresolved
    * @constructor [A] - to the current constructor, [a] - to the current parameter
    */
    constructor(a: String) : this(a.length)
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
Also `@param` doesn't affect the resolution for secondary constructors, as it doesn't make any sense to do so.

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

## Resolution Strategy

We would like to make the resolution of KDoc names closer to what the compiler does when resolving references in code.
This approach is more natural, clear to the user, and convenient, as users don't have to 
The base logic here is that if some name `X` is resolved to declaration `Y` in context `C` during the compilation,
then the same KDoc reference `X` located in some KDoc in the same context `C` should be resolved to `Y`.

Generally speaking,
the compiler also prioritizes symbols during resolution based on declaration kinds and scopes of origin.
However, the priorities are switched: locality of scopes has a higher priority than declaration kinds.
The compiler also doesn't have a strict order of declaration kinds,
as it has more information about a required symbol from the use-site
(not just name as KDoc does).
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

Since the only information the KDoc resolver has is the name of the symbol it's looking for,
prioritization of declaration kinds is vital.
But the main point of this switch is to truly and always prefer local over global 
by iterating through scopes from local to global and then looking for various symbols 
(w.r.t. their declaration kinds) just inside the current scope. 
This approach will be referred to as the **scope-first approach**.

More info on the compiler resolution can be found on the following resources:
* [Name Resolution | Compiler Specification Docs](https://github.com/JetBrains/kotlin/blob/4c3bbc5d4b8d8ada9f8738504b53c44019843d3b/spec-docs/NameResolution.adoc) (can be outdated)
* [Linked scopes | Kotlin Specification](https://kotlinlang.org/spec/scopes-and-identifiers.html#linked-scopes)
* [Overload resolution | Kotlin Specification](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution)

But now we have to deal with the global package search, as we cannot integrate it into this local scope traversal.
So this search should either go first (have the highest priority) or go last.
The first option obviously contradicts the idea of preferring local declarations as much as possible. 
So the proposed idea is to assign it with the lowest priority.
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

## Implementation details

All names should be treated according to the following three-step algorithm:

1. [Perform self-link search](#step-1-perform-self-link-search)
2. [Perform scope traversal](#step-2-perform-scope-traversal)
3. [Perform package search](#step-3-perform-package-search)

### Step 1. Perform self-link search
When the given name is short, the resolution should start by looking for suitable symbols in the context declaration.
It's vital to consider thew tag section the KDoc is contained in.

It's also important to handle `[this]` receiver links in a proper way. 

### Step 2. Perform scope traversal

#### Short names treatment
The resolver gathers all the scopes that are visible from the current declaration, 
which can be done using `ContextCollector` from Kotlin Analysis API.
`ContextCollector` already provides scopes in the order the compiler processes them during the name resolution.
All kinds of provided scopes can be found in `KaScopeKind`.

A general variety of scopes can be described as the following:
1. Type parameter scope
2. Local scope
3. Type scope
4. Static member scope
5. Explicit importing scope
6. Package scope
7. Explicit star importing scope
8. Default importing scope
9. Default star importing scope

```kotlin
// Package scope
package myPackage

import foo.bar.A // Explicit regular importing scope
import something.* // Explicit star importing scope

class MyClass {
    // Type scope

    companion object {
        // Static member scope
        fun staticFoo() {}
    }

    fun <T> foo() { // Type parameter scope
        // Local scope
    }
}
```

Then it iterates through all the scopes and gathers suitable symbols according to the following priorities:
1. Classifier
2. Function
3. Property

If any symbols are found in the current scope,
all these symbols should be immediately returned in the order of their priorities.

#### Multi-segment names treatment
The handling of multi-segment names here is a bit trickier.
A multi-segment name can be either a relative name or a global FQN.
And there is no way to tell which one we are looking at.

* [Relative names](#relative-names)
* [Global names](#global-names)

##### Relative names
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
To handle such cases, the scopes retrieved for the traversal should be slightly transformed.
If a given name has several segments, then it should be accessible by that name from some of these outer scopes.
In fact, the resolver should only pick and process just a single scope,
see the reasoning behind this in the [Restrictions of multi-segment names resolution](#restrictions-of-multi-segment-names-resolution).

Imagine that we have a name `A.B.foo`.
We assume that this link is relative, so in each scope we have to find a class-like (i.e., a declaration container) `A`,
then if `A` is found, we should start looking for class-like `B` in its class scope and so on.
Then the rest of the logic is quite simple: retrieve symbols from this scope by the short name (`foo`).

The resolver should iterate through scopes from local to global 
and look for a chain of nested classifiers starting in the current scope.
If this chain is found, 
the resolver just picks this classifier member scope and looks for symbols with the given short name.
However, if, at some point of this symbols-by-segment search, 
the resolver comes across some non-function symbol from which
it's impossible to continue the chain, the resolver should stop and consider this link as unresolved. 
Examples of such cases are classes with no nested classifiers matching the next segment and properties.
Again, see [following chapter](#restrictions-of-multi-segment-names-resolution) for more info.

##### Global names
A multi-segment name can also be global, i.e.,
located in some other package visible from the current one and not imported in the current file.
Then the containing scope of this declaration is not directly visible from the current position
(when using `ContextCollector` from AA), so this case should be handled manually.

If we have some name `A.B.C.D.foo`, then we have to find the longest existing package,
which name is a segment prefix of this link (search for `A.B.C.D`, then for `A.B.C` and so on).
Let's imagine that some package with `A.B` name is found.
The only thing left to do is to resolve `C.D.foo` inside this package scope.
Now it's quite similar to the logic we had for relative and short names.
Firstly, the resolver should find all nested class-likes on its way to the short name (`C.D` in this case).
If this chain of nested classifiers is found, finding the shourt name `foo` in this classifier scope is trivial.

```kotlin
package A.B

class C {
    object D {
        fun foo() {}
    }
}
```

This global name search should not be performed when there are conflicting non-function declarations
found in any of the local scopes, 
see [Restrictions of multi-segment names resolution](#restrictions-of-multi-segment-names-resolution) for more info.

##### Restrictions of multi-segment names resolution
It's important to remind that we would like this resolution to be as close as possible to the compiler.
So we should only resolve multi-segment names when the compiler does so.
Imagine that we have some name `A.B.C` that we would like to point to.
The location of this declaration relatively to the given position is not important.

Here we will put a declaration in some other package
just that there are no conflicting overloads within the same package.
```kotlin
// FILE: A.B/C.kt
package A.B

class C

// FILE: Usage.kt

fun usage() {
    A.B.C() // RESOLVED
}
```

The reference is correctly resolved.
But then there might be other declarations in the usage file.
```kotlin
// FILE: Usage.kt

fun A() = 5

fun usage() {
    A.B.C() // RESOLVED
}
```

```kotlin
// FILE: Usage.kt

val A = 5

fun usage() {
    A.B.C() // UNRESOLVED
}
```

```kotlin
// FILE: Usage.kt

class A

fun usage() {
    A.B.C() // UNRESOLVED
}
```

```kotlin
// FILE: Usage.kt

class Something

fun Something.A() {}

fun Something.usage() {
    A.B.C() // RESOLVED
}
```

```kotlin
// FILE: Usage.kt

class Something

val Something.A: Int
    get() = 5

fun Something.usage() {
    A.B.C() // UNRESOLVED
}
```

As we can see, the compiler only resolves this name when there are 
no other non-function more local declarations that are resolvable by some prefix of the given name.
Otherwise, some prefix is resolved to this declaration and the rest of the chain is unresolved,
as this declaration doesn't have any suitable nested symbols.

This should be also taken into account when resolving links in KDoc:
* Before performing a scope transformation for relative names, the resolver has to make sure that there are no
non-function declarations matching some prefix of the given name in a more local scope.
If such a declaration is found in some scope, then there is no need to process all further scopes,
as the compiler would resolve this prefix to the declaration in this scope.

* The same is applied to the global name search: if such a conflicting declaration is already found in some local scope,
the resolver should not proceed to the global retrieval.

### Step 3. Perform package search
If no symbols were found on any of the previous steps, a global package search should be performed.
Note that the real package name should fully match the name specified in the link.
If a link is just `bar`, then it cannot be resolved to some package called `foo.bar`,
as it matches only the last segment but not the full package name.


### Result of the resolution
The result of the resolution is the set of all symbols found in self-links (for short names)
plus symbols from the first non-empty scope sorted by their priorities.

Unfortunately, there is no disambiguation syntax
that would allow explicitly specifying the kind of the declaration the current link should point to.
To overcome this issue at the current moment, 
the resolver should return all symbols that the compiler could resolve to the given name from the given position.

```kotlin
/**
 * @see Foo() function. - leads to the interface,
 * no way to point to the function.
 */
interface Foo 

fun Foo(): Foo = object : Foo {}
```

It's important to preserve the priority of symbols in the resulting collection for purposes
specified in [Handling resolved symbols on the use-site](#handling-resolved-symbols-on-the-use-site).

## Handling resolved symbols on the use-site
There are a number of various logic pieces that heavily rely on the KDoc name resolver:
* Import optimizer
* Usage finder (that also highights declarations that the link points to)
* Navigation actions
* Documentation renderers (Dokka, rendered HTMLs in the IDE)

We would like all these usages to be as consistent as possible with each other.

Currently, most usages handle all the symbols that are returned by the resolver (i.e., import optimizer).
However, navigation actions that are also provided to users only navigate to a single declaration from the returned set
(the first one to be precise).
This might be quite confusing to users, as in the following code both functions are highlighted as *used*,
but *Go To Declaration* action takes users just to the first declaration:

```kotlin
fun foo() {}
fun foo(x: Int) {}

/**
 * [foo]
 */
fun usage() {}
```

The IDE should show a popup drop-down menu the same way it does with other ambiguous references:
![example](https://i.ibb.co/dKQkshh/image.png?)


However, tools that require just a single resolved symbol, e.g.,
for HTML rendering purposes, should be able to pick just the first symbol from the resulting collection.
That's why it's important to construct this collection in such a way 
that more local symbols with higher priority go first.
It makes the result consistent and reliable.

# Appendix

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
 * {@link #f} references to f(int p)
 */
 public class JavaClassA {  
    public void f(int p) {}
    /**  
    * {@link #f} references to f(int p)
    */ 
    public void f() {}  
}
```
Also, by the specification, `#` may be omitted for members:
```java
/**  
 * {@link f} references to the field f
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

JSDoc does not have such problems with relative references
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
A generated XML file then passes to a Documentation generator, e.g., Sandcastle.

The `cref` attribute is used to provide a reference to a code element. 
The C# documentation does not describe the cases of overloads and ambiguous references.
The support of such references depends on a Documentation generator.

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
In case of ambiguity, the rustdoc will warn about the ambiguity and suggest a disambiguator.
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

However, the Godoc does not support links very well to check.

## Visibility 
KDoc ignores visibility, i.e., all declarations are public for KDoc references.
Whether resolving KDoc references should take visibility into account is an open question.

Javadoc can take visibility into account for particular cases (not specified), but for most cases it works just like KDoc.

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
internal fun a() = 0
/** [a] leads to the internal fun and will be displayed in Dokka as plain text*/
```

# Streamline KDoc ambiguity links

* **Type**: KDoc proposal
* **Author**: Vadim Mishenev
* **Status**: Submitted
* **Issues**: [dokka/#3451](https://github.com/Kotlin/dokka/issues/3451), [dokka/#3179](https://github.com/Kotlin/dokka/issues/3179), [dokka/#3334](https://github.com/Kotlin/dokka/issues/3334) 
* **Discussion**: [#TBD](https://github.com/Kotlin/KEEP/issues/0)

# Summary

This document introduces clarifications and improvements to the existing implementation of [KDoc](https://kotlinlang.org/docs/kotlin-doc.html) regarding resolving ambiguous KDoc links. It addresses the range of issues ([dokka/#3451](https://github.com/Kotlin/dokka/issues/3451), [dokka/#3179](https://github.com/Kotlin/dokka/issues/3179), [dokka/#3334](https://github.com/Kotlin/dokka/issues/3334) )  that Dokka and the IntelliJ Plugin faced during the migration to K2 - Analysis API.
 
 # Motivation
 
 ## Introduction
 
There are two types of KDoc links to a declaration:
-   Fully qualified ones, for example `[com.example.classA]`, starting with a full package name
-   Relative ones (also known as short names), for example `[memberProperty]`

Also, KDoc allows to refer to:
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
    /**  
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
*Note: This document does not consider the case of extensions (links to extensions)  for the sake of simplicity.  It is a non-goal and deserves another dedicated document.*


## Problem

There are cases when KDoc links are ambiguous meaning there is more than one possible candidate from the users point of view. These cases were discovered by the migration of Dokka to K2 and behave differently in K1 and K2. 

### 1. Self-links

Javadoc and KDoc allow to have links to itself. It is a quite spread practice.  For example, self-links are widely used in the Kotlinx libraries because of visual consistency.
However, it can also lead to ambiguous links:
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
In K1, both links lead to the class A. In K2, they lead to itself.
This case can be applied to all possible pairs of declaration kinds (function and function also known as overloads problem; property and class...).

Also, a constructor has the name of a class.
```kotlin
class A    {
    /** 
    * [A] In K1, it leads to the class A. In K2 - to the constructor 
    */
    constructor(s: String)
}
```
For Javadoc, see the section `Other languages`.

### 2. links to constructor parameters

This case seems valid.
```kotlin
val abc: String = ""
/**
* [abc] to the parameter in K1 and K2.
* For the property, a fully qualified link can be used.
*/
fun f(abc: String) = 0
```


However, in a primary constructor, the same link can refer to parameters and properties. It does not matter for IDE. Opposite,  Dokka has different locations for parameters and properties.
```kotlin
/**
* [abc] K1 refers to the property `abc`, K2 - to the parameter
*/
class A(val abc: String)
```
From the point of IDE view, the link `[abc]` leads to the same position in a source file independently of whether the `abc` is a parameter or property.

#### Related problem: Availability/Visibility of constructor parameters
The availability of parameters inside a scope can result in ambiguous links.
```kotlin
class A(a: Int) {  
    /**  
    * [a] is unresolved in K1. In K2, it is resolved
    */  
    fun usage() = 0
}
```

## Ambiguity in other cases

Also, there are other cases that can be considered as ambiguous, but their behavior is consistent in K1 and K2.
 ### By a kind of a declaration
For some trivial cases (ambiguous links are inside a single scope) there are the predefined priorities of KDoc candidates in the Dokka and IDE K1 implementations:  
-  Class
-  Package
-  Function
-  Property

For example,
```kotlin
val x = 0
fun x() = 0

/** here [x] refers to the function */
fun usage() = 0
```
These priorities allow Dokka to avoid ambiguity in some links except the case `Self-links` above.

### By overloads
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

### Order of scopes

Currently, an inner scope already has a priority over outer scopes.
Let's consider the following general example to understand the current resolve of KDoc link :
```kotlin
class B

/** [B] - K1, Javadoc and K2 refer to the nested class A.B */
class A {
    class B
}
```
The search for the declaration is initially done in the members. Therefore, K1 (IDE and Dokka), K2, and Javadoc refer to a nested class B. 
For example, Swift has the opposite behavior.

Here is another example:
```kotlin
val a = 0
fun a() = 0

/** [a] K1 and K2 refer to the parameter */
fun f(a: Int) = 0
```


#### Related problem: Availability/Visibility of  nested classes from base classes
However, "inherited" nested classes are in question:
```kotlin
open class DateBased {
    class DayBased
}

/**
* [DayBased] K2 and Javadoc lead to [DateBased.DayBased], but in K1, it is unresolved
*/
class MonthBased : DateBased()
```
This causes inconsistent behaviour. In K1, the link `[B]`  is unresolved, but it resolves inherited members.

It is a problem of determining what declarations the current context of KDoc contains.  Together with that, an undefined name resolution for a referred declaration causes ambiguous links.  
 


# Proposal

This document proposes that existing KDoc links should point to only one specific location in the same way as the compiler does for name resolution. Meanwhile, as stated above, the ambiguity of KDoc links is inevitable   

Just a warning about ambiguity does not make sense as there is no disambiguator at this time. Therefore, the proposed approach is to prioritize candidates for KDoc links to choose the only one.

To have unambiguous  links and resolve the origin problem, the following questions should be covered:
 - Which names are available in a current KDoc
 - If there is more than one candidate, what priorities there are
 - If there are candidates that have the same priority, how choose the only one.

The following sections address these 3  questions.

## Context of KDoc

Here context is the set of all name bindings available by its short names in a current KDoc comment ([scope vs context](https://en.wikipedia.org/wiki/Scope_(computer_science))). 
For example, the KDoc context of a class should correspond to the first line of a fake function.

```kotlin
/**
 * [prop] is resolved
 * [param] ERROR: unresolved
 */
class A(param: Int) {
    companion object {
        val prop = 0
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
That means that the context of the KDoc link should contain all available names on the first line of a fake function. Note the rule ignores the visibility of declarations.
Similarly, the KDoc context of functions/constructors corresponds to the beginning of the first line of a body and the KDoc context of properties corresponds to the beginning of property initializers.
 
 ### KDoc tag sections
The KDoc tag sections (`@constructor`, `@param`, `@property`) introduce their new scope in the documentation.

The tag `@constructor`  has context where parameters of a primary constructor are available.
 
```kotlin
/**
 * [p] ERROR: unresolved
 * @constructor [p] is resolved
 * @property abc [p] is resolved
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
Also, an illustrative example of the concept behind a dedicated scope for `@constructor` is provided in the next section.


## Priorities

In order to resolve the ambiguity of KDoc links,  rules of priorities can be introduced. The pivot point is to give the highest priority to self-links and reuse already existing priorities. 

Ultimately, the logic of disambiguation can be formulated as follows:

1.  Treat as a short name link the following priorities from highest to lowest:
-  Self-link
-  Class. Also, it includes annotation classes, interfaces and objects.
-  Package
-  Function
-  Property
```kotlin
/** [A] - to class A */
class A
/** [A] - to fun A(a: Int) */
fun A(a: Int)

/** 
 * [A] leads to the class A 
 * since the priority of a class is higher than a function 
 */
 fun usage() = 0
```

```kotlin
/** [b] - to val b */
val b = 0
/** [b] - to fun b() */
fun b() = 0

/** [b] - to fun b() */
fun usage() = 0
```
Here is an example with a nested class:
```kotlin
/** 
 * [A] - to the top-level class A 
 * [A.A] - to the nested class A
 */
class A {
    /** [A] - to the nested class A */
    class A
    /** [A] - to fun A(p: Int) */
    fun A(p: Int) { 
        return A()
    }
}
```
In the case of overload ambiguity, the previous behaviour (to the first occurrence) is left unchanged.
```kotlin
/** [b] - to fun c(a: String) */
val c(a: String) = 0
/** [c] - to fun c() */
fun c() = 0

/** [c] - to fun c(a: String) */
fun usage() = 0
```

2.  Otherwise, if no short  name link is found, treat it as a fully qualified link with the priorities from highest to lowest:
-  Class. Also, it includes annotation classes, interfaces and objects.
-  Package
-  Function
-  Property

```kotlin
package com
/** [com.A] - to class A */
class A
/** [com.A] - to class A */
fun A(a: Int)

/** 
 * [com.A] leads to the class A 
 * since the priority of a class is higher than a function 
 */
 fun usage() = 0
```
Note that self links are not prioritized for fully qualified names so it allows referring to a returning class from a factory function. 


### Self-links

The idea behind giving high priority to self-links is that they do not depend on context and are more consistent.

For example,
```kotlin
/** [A] - to val A */
val A = 0
```
after adding a function, the link is left the same. Otherwise, a function has a higher priority than a property.
```kotlin
fun A() = 0
/** [A] - to val A */
val A = 0
```

There is another example for `@constructor`:
```kotlin
/** 
 * [A] - to the class, [abc] - to the property 
 * @constructor [A] - to the constructor, [abc] - to the parameter 
 */ 
class A(var abc: String)
```
That can be refactored to
```kotlin
/**  
 * [A] - to the class, [abc] - to the property 
 * Note [A.A] is unresolved
 */
class A() {  
    /**  
    * [A] - to the constructor, [abc] - to the parameter 
    */  
    constructor (abc: String): this() {}  
    lateinit var abc: String  
}
```

Additionally, he highest priority of self links can be applied within KDoc sections such as `@property` and `@param`.

```kotlin
/** 
 * [Abc] - to the class 
 * @property [Abc] - to the property 
 */ 
class Abc(val Abc: Int) 

/** 
 * [Abc] - to the function 
 * @param [Abc] - to the param 
 */ 
fun Abc(Abc: Int)
```


## Order of scopes

However, there may be more than one declaration with the same priority if they come from different scopes.

For example,
```kotlin
// FILE: a. kt
package com.example.pkg1

class A(p: Int)

// FILE: b. kt
package com.example.pkg2
import com.example.pkg1.A

class A(p: String)

/**
 * [com.example.pkg1.A] and [com.example.pkg2.A] are both classes    
 * available here by short names according to the current proposal
 */
fun usage() {
    A(1) // resolved to com.example.pkg1.A
    A("") // resolved to com.example.pkg2.A
}
```

For declarations with the same priority and the same name, selecting a target declaration should be based on the order of scopes as implemented in the compiler.

Thus, the order of declarations inside a single group of priorities is completely determined by the implementation of the compiler. The implemented order is described [here](https://github.com/JetBrains/kotlin/blob/4c3bbc5d4b8d8ada9f8738504b53c44019843d3b/spec-docs/NameResolution.adoc) (can be outdated).

Also, the order is defined by the Kotlin Language Specification: [Linked scopes](https://kotlinlang.org/spec/scopes-and-identifiers.html#linked-scopes), [Overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution) and other sections.


For example,
```kotlin
// FILE: a. kt
package com.example.pkg1

val a = 0 

// FILE: b. kt
package com.example.pkg2
import com.example.pkg1.a // (1)
import com.example.pkg1.* // (3)

val a = 0 // (2) 

/* [a] leads to the imported [com.example.pkg1.a] (1) */
fun usage() {
    a // resolved to the imported com.example.pkg1.a (1)
}

/* [a] leads to the imported [com.example.pkg1.a] (1) */
val usage = a // resolved to the imported com.example.pkg1.a (1)
```
In the example,  top-level declarations are prioritized in the following order:
 - (1) Explicit imports 
 - (2) Functions in the same package   _Such function may be located in the other files in the same package._
 - (3) Star-imports
 - (4) Implicitly imported functions (from stdlib)


Here is yet another example.
```kotlin
fun ff() {} // (4)  
open class B {  
    fun ff() {} // (2)  
}  
class A : B() {  
    public inner class C : B() {  
        fun ff() {} // (1)  
		
        /** [ff] refers to (1)*/  
        fun usage() {}
    }  
  
    companion object {  
        fun ff() {} // (3)  
    }  
}
```
In this example, member declarations are ordered by scopes as follows:
 - (1) Members directly declared in the same class
 - (2) Inherited members
 - (3) Members from companion objects
 - (4) Outer scope

## Alternative
The problem of ambiguous KDoc links can be solved by tooling (Dokka and IDE).
Dokka can show all possible candidates *via a popup with an interactive list* in the same way as IDE does it for ambiguous resolving in Javadoc etc.  

![example](https://i.ibb.co/dKQkshh/image.png?)
It was rejected in favour of the current proposal.

# Appendix

## Other languages

All considered languages can be divided into two groups:
 * ambiguous links are allowed (Java);
 * ambiguous links are disallowed and cause a warning. In this case, a language provides a mechanism to disambiguate them. (Swift, C#, Rust, Golang)

### Javadoc
[JavaDoc Documentation Comment Specification: References](https://docs.oracle.com/en/java/javase/22/docs/specs/javadoc/doc-comment-spec.html#references) describes the specification of Javadoc references a little:
> the parameter types and parentheses can be omitted if the method or constructor is not overloaded and the name is not also that of a field or enum member in the same class or interface.

Homewer, [Javadoc's style guide](https://www.oracle.com/technical-resources/articles/java/javadoc-tool.html#styleguide) allows to omit parentheses for the general form of methods and constructors. In this case, an ambiguous reference will lead to:
 - a field if it exists
 - otherwise,  to the first occurrence of overload in the code. 
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
Also, Javadoc does not have references to function parameters. 
    

### JavaScript (JSDoc)

JSDoc does not have such problems with relative references since it has a unique identifier like a fully qualified path in Kotlin.
For `@link` tag ( https://jsdoc.app/tags-inline-link ) there is a namepath. 
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
A namepath provides a way to do so and disambiguate between instance members, static members and inner variables. See [https://jsdoc.app/about-namepaths](https://jsdoc.app/about-namepaths)
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

Also, it does not allow to have a class and a function with the same name in a single scope.

### Python (Sphinx)

The Python allows to have a cross-reference via the markup see [https://www.sphinx-doc.org/en/master/usage/domains/python.html#cross-referencing-python-objects](https://www.sphinx-doc.org/en/master/usage/domains/python.html#cross-referencing-python-objects)  
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

Swift does not allow ambiguous links, although IDE suggests fixing them. If a reference is ambiguous, it is unresolved and will be displayed as plain text.

![example](https://i.ibb.co/9s3pmyp/Screenshot-2024-07-13-at-12-38-09-AM.png)

 
Swift has some approaches to disambiguate links.
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

It has  [XML documentation](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/documentation-comments).  A generated XML file  then passes to a Documentation generator, e. g. Sandcastle.

The `cref` attribute is used to provide a reference to a code element. The C# documentation does not describe the cases of overloads and ambiguous references. The support of such references depends on a Documentation generator.

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
As Java, C# does not allow to have a nested class with the same name as enclosing class

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
KDoc ignores visibility, i.e. all declarations are public for KDoc references.
Whether resolving KDoc references should take visibility into account is an open question.

Javadoc can take visibility into account for particular cases (not specified), but for most cases it works like KDoc.

```java
/**  
 * {@link JavaD} is resolved despite `private` and displayed as plain text 
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

# Name-based Destructuring

* **Type**: Design proposal
* **Author**: Anastasiia Pikalova, Mikhail Zarechenskii
* **Contributors**: Marat Akhin, Nikita Bobko, Alejandro Serrano, Roman Efremov, Aleksandra Arsenteva, Roman Venediktov
* **Discussion**: 

## Abstract
This proposal outlines the issues with auto-generated `componentN` functions in data classes and introduces a new type of destructuring that eliminates these issues.

## Motivation
Operator `componentN` functions are automatically generated for constructor parameters of data classes. Therefore, every property in the primary constructor has a `componentN` function where `N` corresponds to the property's position.

```kotlin
data class UserData(val name: String, val lastName: String)

// will be generated:
data class UserData(val name: String, val lastName: String){
operator fun component1(): String = this.name
operator fun component2(): String = this.lastName
}
```

Auto-generated `componentN` functions create a mapping between property names and their position, which results in an ***implicit*** contract that data classes must follow.

```kotlin
val userData = UserData("name", "lastName")
val nameFromProperty = userData.name
val nameFromComponent = userData.component1()
```

This leads to several issues covered in the next section.

##### 0. Loss of semantic clarity
Existing destructuring in Kotlin is based solely on the position of a property within the primary constructor of data class. Even using explicit property names during destructuring doesn’t ensure that the properties are mapped by their actual names.

```kotlin
data class UserData(val name: String, val lastName: String) 

// 'lastName' refers to 'name', 'name' refers to 'lastName'
val (lastName, name) = UserData("name", "lastName")
```

It’s really hard to spot issues in this code without checking the declaration. This makes the code hard to maintain and read.

##### 1. Error-prone changes, accidental forward compatibility
Adding a new property to a data class can result in errors and unforeseen challenges with maintaining forward compatibility.
```kotlin
// Before:
data class Point(val x: Int, val y: Int) // let's add 'z'

fun foo(point: Point) {
    val (x, y) = point
}

// After:
data class Point(val x: Int, val z: Int, val y: Int)

fun foo(point: Point) {
    // now, 'y' refers to 'z'
    val (x, y) = point
}
```
In Kotlin, the number of elements in a destructuring declaration doesn’t have to match the number of parameters in the primary constructor. This introduces additional "potentially unsafe" scenarios where changes in the order or number of properties may not cause a compile-time error but could lead to logical errors in the code.

##### 2. Binary compatibility issue
The generation of `componentN` functions depends on the order of data class constructor properties. To maintain binary compatibility, it’s necessary to preserve the order of the properties. 

Using a secondary constructor doesn't change how the `componentN` functions are generated in data classes because they're based on the primary constructor.

However, for regular classes, it's possible to add a secondary constructor to maintain binary compatibility. This is because the compiler does not generate `componentN` functions for regular classes.

```kotlin
// The 'age' property added in the middle
data class UserData(val name: String, val age: Int?, val lastName: String) {
// Add a secondary constructor
constructor(name: String, lastName: String) : this(name, null, lastName)

// Will be generated:
// Automatically references and orders properties based on their declaration in 
// the primary constructor.
operator fun component1() = this.name
operator fun component2() = this.age
operator fun component3() = this.lastName
}
```
Thus, adding a secondary constructor is not a workaround for preserving binary compatibility for data classes. 

Note. The same issue occurs with auto-generated copy functions in data classes.
Source: https://jakewharton.com/public-api-challenges-in-kotlin/#destructuring-functions

##### 3. Data class with private properties
Destructuring assignment can't be used for private properties. However, the properties following the private property in a constructor, even if they are public, cannot be destructured. This is because destructuring requires access to all properties in order.
```kotlin
data class Point(private val x: Int, val y: Int)

fun foo(p: Point) {
// destructure will result in an error due to 'x' being private
val (x1, y1) = p // error: INVISIBLE_MEMBER

// trying to skip the private property also doesn't work
val (_, y2) = p // still error: INVISIBLE_MEMBER
}
```
Skipping private properties using an underscore is possible in principle; however, it undermines the principle of private API. Private properties shouldn’t have any references at the call site because they can be modified or removed at any time.

```kotlin
// The removal of 'x' should be safe because 'x' is a private property...
data class Point(val y: Int)

fun foo(p: Point) {
// ...but this change leads to an error at call sites
val (_, y2) = p 
}
```

#### Issue сategories
The issues described above can be divided into three categories:

***Problems with data class maintenance***. Changes to the primary constructor of a data class auto-generate `componentN` functions, potentially breaking binary and source compatibility. The lack of control over this auto-generation process presents significant challenges for maintaining compatibility.

***Call site confusion***. The reliance on positional binding and the absence of name-based binding in destructuring can cause confusion at the call site.

***Unclear destructuring rules for private/public properties***. The current approach of data class destructuring, based solely on the order in the primary constructor, does not consider cases where a private property precedes a public property. This leads to inconsistent and unclear behavior.

## Proposal
Name-based destructuring provides an alternative that avoids positional mapping issues and offers a more reliable destructuring mechanism.

#### Syntax
Name-based destructuring introduces a syntax where properties are bound by their names.
As a result, changing the order of properties does not cause call-site confusion, as properties are bound by their names. 

```kotlin
data class Cat(val name: String, val color: String)

// positional destructuring
val (component1, component2) = cat

// name based destructuring
(val name, val color) = cat
// or
(val color, val name) = cat

// this syntax can also be used in lambda expressions or for-loops:
catList.map{ (val name, val color) -> ...}
```

One of the essential parts of destructuring is the ability to use a different name from the original. To make this possible, the original name can be specified in the destructuring in the following way.
```kotlin
(val someColor = color, val someName = name) = cat
```

The keyword `val` makes it clear which names come from the data class (RHS) and which are just local variables created during destructuring (LHS). This makes the code easier to read and understand because `val` is already familiar in Kotlin and clearly separates class properties from local variable names.

Additionally, it's possible to specify an explicit type for a property in destructuring, just as it is in positional destructuring.
```kotlin
(val color: String, val name) = cat
// or
(val someColor: String = color, val someName = name) = cat
```

One of the strengths of this syntax is that it is visually close to positional destructuring, while at the same time being easy to distinguish between positional destructuring and name-based destructuring from the call site.

#### Grammar
```kotlin
  propertyDeclaration:
-   [modifiers]
-   ('val' | 'var')
-   [{NL} typeParameters]
-   [{NL} receiverType {NL} '.']
-   ({NL} (multiVariableDeclaration | variableDeclaration))
-   [{NL} typeConstraints]
+   (singleKeywordPropertyPrefix | namedBasedDeclaration)
+   [{NL} (('=' {NL} expression) | propertyDelegate)]
+   [{NL} ';']
+   {NL}
+   (([getter] [{NL} [semi] setter]) | ([setter] [{NL} [semi] getter]))

+ singleKeywordPropertyPrefix:
+   [modifiers]
+   ('val' | 'var')
+   [{NL} typeParameters]
+   [{NL} receiverType {NL} '.']
+   ({NL} (multiVariableDeclaration | variableDeclaration))
+   [{NL} typeConstraints]



  lambdaParameter:
    variableDeclaration
-   | (multiVariableDeclaration [{NL} ':' {NL} type])
+   | ((multiVariableDeclaration | namedBasedDeclaration) [{NL} ':' {NL} type])

+ namedBasedDeclaration:
+   '('
+   {NL} namedBasedComponent
+   {{NL} ',' {NL} namedBasedComponent}
+   ')'

+ namedBasedComponent:
+   [modifiers]
+   ('val'| 'var')
+   {NL} variableDeclaration
+   [{NL} '=' {NL} simpleIdentifier]
```

#### Compiler Behavior
Name-based destructuring acts as syntactic sugar. It translates to simple property access.
```kotlin
(val color, var name) = cat

// Could be seen as
val local$cat = cat
val color = local$cat.color
va name = local$cat.name
```

This transformation follows standard property access patterns and does not guarantee thread safety, leaving it to the developers to ensure proper synchronization when necessary. Access is made in the order of destructured variables.

#### Name-Based Destructuring for Regular Classes
Since name-based destructuring is purely syntactic sugar, there is no blocker preventing its use for all types of classes. We don’t have a strong motivation or existing problems that require name-based destructuring for regular classes. However, this feature could improve the user experience, especially considering that regular classes already support positional destructuring.

It would be particularly useful because regular classes often include primitive components for exposing public APIs and providing shorthand access.

```kotlin
class Foo(val prop: String) {
  operator fun componen1(): String = prop
}
```


***Note***. *There is a risk that name-based destructuring could lead to code clutter. To mitigate this risk, we can introduce a compiler flag that allows users to opt-in to name-based destructuring for regular classes.*

#### Feature Scope
Name-based destructuring works with ***any property that is available in the current scope*** via a simple accessor.

*For example:*
- *Member properties*
- *Extension properties*
- *Private properties if they are available in the current scope*

#### Relation to Pattern Matching
The proposed syntax could be a step toward supporting pattern matching in the future, which is why it’s important to mention it in the proposal.

Currently, we *don’t have a clear plan to implement pattern matching*, as Kotlin’s smart casting already handles many common cases.

However, the new syntax is **compatible** with potential pattern matching features. It works not only for primary constructors but for any accessible properties.

Here’s an example of how pattern matching could look in the future:

```kotlin
when (something) {
    // Name-based destructuring
    is Cat(val name == "Jerry") -> println("It's Jerry the Cat!")
    
    // Positional destructuring
    is Pair("first", "second") -> println("It's a matching Pair!")
    
    else -> println("No match found.")
}
```

This example shows how both name-based and positional destructuring could fit naturally into pattern matching.

#### Risks
1. One of the risks of introducing name-based destructuring is scope pollution. Allowing an easy way to retrieve properties from a class in a single line could clutter the code and decrease readability. However, we cannot prohibit developers from using the language as they see fit.

    We strongly recommend *not destructuring more than three properties in one line and avoiding destructuring variables with long names*, preferring to use common property assignment instead.


2. The chosen syntax does not allow the IDE to suggest property names in destructuring as it is writing from left to right. However, declaring the destructured property first and then the destructuring variable doesn't align with the way variables are typically written in Kotlin.

    To mitigate this risk, IDE should offer autocompletion that builds a destructuring template, prompting users to write the destructured property first, followed by the destructuring variable names.
    ```kotlin
    (val ) = cat<caret> // sample of IDE autocompletion suggestion
    ```

### Q&A
- *How can the outlined problem for data classes be solved by name-based destructuring?*

    At the current point of view, name-based destructuring would not solve the issues with positional destructuring directly. However, it opens the door for future enhancements to data classes and could potentially become the new default for data class destructuring in the future.

- *Does it mean that positional destructuring is deprecated?*
    No. Positional destructuring is still desirable in certain use cases. For example, data classes such as `Pair` and `Triple` make sense only with positional destructuring since these classes don’t contain any name-specific information, relying solely on the position.

    Positional destructuring is also preferable when the call-site readability is not a concern, and the developer wants to write more concise code. Name-based destructuring is longer than simple property assignment and can clutter the code, potentially reducing readability in some cases. See the following example:

    ```kotlin
    class Point(val x: Int, val y: Iny)

    // Positional destructuring
    val (x1, y1) = point1
    val (x2, y2) = point2

    // Name-based destructuring
    (val x1 = x, val y1 = y) = point1
    (val x2 = x, val y2 = y) = point2
    ```


- *What will happen with autogenerated `componentX` functions?*
Autogenerated componentX functions will remain unchanged in the foreseeable future. This means that forward-compatible data class issues will still exist. However, developers can avoid name confusion at the call site by using name-based destructuring, even if the library introduces non-forward-compatible changes.

### Alternative Design
#### Deprecate autogenerated `componentX` functions
***Pros:***
- Solves all the outlined problems.

***Cons:***
- Requires a large migration.
- Deprecates a feature without introducing a clear alternative.



#### Reuse current syntax at the call-site and declare the type of destructuring in the data class declaration

```kotlin
@Positional
data class Point(val x: Int, val y: Int)

// Name-based destructuring by default
data class Cat(val name: String, color: String)


// positional destructuring only for Point
val (x, y) = point

// name-based destructuring always by default
val (name, color) = cat
```

***Pros:***
- Reuses syntax that developers are already familiar with.

***Cons:***
- Creates more confusion at the call site since it’s not clear what type of destructuring is being used without knowing the data class declaration.
- Requires migration.

#### Use ‘mixed destructuring’ or force developers to always bind the actual data class properties names in positional destructuring

***Pros:***
- Reuses familiar syntax for developers.

***Cons:***
- Creates confusion at the call site, as it’s not clear what type of destructuring is being used without knowing the data class declaration. For classes like Pair and Triple, forcing users to use the actual names doesn’t make sense.
- Requires migration.

### Syntax alternatives
- `val {name, color} = cat` – could cause confusion with lambdas.
- `val [name, color] = cat` – could cause confusion with potential future collection literal implementation.
- `Cat(val name, val color) = cat` – looks like a constructor.
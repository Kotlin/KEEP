# Name-based Destructuring

* **Type**: Design proposal
* **Author**: Anastasiia Pikalova, Mikhail Zarechenskii
* **Contributors**: Marat Akhin, Nikita Bobko, Alejandro Serrano, Roman Efremov, Aleksandra Arsenteva, Roman Venediktov
* **Discussion**: 

## Abstract
This proposal outlines the issues with auto-generated `componentN` functions in data classes and introduces a new type of destructuring - name-based destructuring - in addition to the already existing positional destructuring. This new approach eliminates issues caused by positional mapping.

### Destructuring Approaches
Before introducing the proposal, it's important to define the key terms used in this document.

Kotlin currently supports ***positional destructuring***, which assigns values based on their order in a structure. This approach is commonly used for collections and fixed-size tuples like `Pair` and `Triple`. Regardless of the variable names used at the call site, binding happens purely ***by position***.

An alternative approach is ***name-based destructuring***, where values are assigned based on ***property names*** rather than their position. This method is more suitable for structured objects where property names carry meaningful information.

## Motivation
### What are `componentN` functions?
Destructuring in Kotlin is currently based on positional destructuring, which relies on `componentN` operator functions. A regular class can ***explicitly*** declare `componentN`  functions to ***enable*** positional destructuring.

```kotlin
class Person(val name: String, val age: Int) {
    operator fun component1(): String = name
    operator fun component2(): Int = age
}
```

In `componentN`, the number `N` represents the position of the property in a destructuring declaration. This allows multiple variables to be assigned at once:

```kotlin
val (name, age) = person

// compiles to:
val name = person.component1()
val age = person.component2()
```

### Issues with Positional Destructuring
In data classes, `componentN` operator functions are ***automatically generated*** for primary constructor parameters. This means that every property in the primary constructor gets a corresponding `componentN` function, where `N` represents its position.

```kotlin
data class UserData(val name: String, val lastName: String)

// will be generated:
data class UserData(val name: String, val lastName: String){
    operator fun component1(): String = this.name
    operator fun component2(): String = this.lastName
}
```

Auto-generated `componentN` functions create a mapping between property names and their position, which results in an ***implicit*** contract that data classes must follow. Unlike regular classes, where `componentN` functions are ***explicitly defined***, data classes generate them automatically, making property order ***fragile***.
```kotlin
val userData = UserData("name", "lastName")
val nameFromProperty = userData.name
val nameFromComponent = userData.component1()
```

This leads to several issues covered in the next section.

##### 0. Loss of semantic clarity
Existing destructuring in Kotlin is ***based solely on the position*** of a property within the primary constructor of data class. Even using explicit property names during destructuring doesn’t ensure that the properties are mapped by their actual names.

```kotlin
data class UserData(val name: String, val lastName: String) 

// 'lastName' refers to 'name', 'name' refers to 'lastName'
val (lastName, name) = UserData("name", "lastName")
```

It’s really hard to spot issues in this code without checking the declaration. This makes the code hard to maintain and read.
IntelliJ IDEA already has an inspection that detects such cases, proving that this is a real problem developers face.

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
The number of elements in a destructuring declaration doesn’t have to match the number of parameters in the primary constructor. This introduces additional ***potentially unsafe*** scenarios where changes in the order or number of properties may ***not cause a compile-time*** error but could lead to ***logical errors*** in the code.

**Note**. Some languages ***require*** explicitly skipping properties (e.g., with `...`), preventing silent behavior changes. In Kotlin, destructuring allows omitting properties without ***any explicit marker***. This makes Kotlin positional destructuring more fragile.

##### 2. Binary compatibility issue
The generation of `componentN` functions in data classes depends on the order of constructor properties, making any changes to this order ***a binary-breaking change***. Since `componentN` functions are tied to the primary constructor, they cannot be redefined or adjusted without breaking compatibility.

Using a secondary constructor doesn't change how the `componentN` functions are generated in data classes because they're based on the primary constructor.

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

**Note**. The same issue occurs with auto-generated copy functions in data classes.
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

##### Why We Need an Alternative
Positional destructuring relies on property order, which leads to ***compatibility issues, accidental errors, and restrictions with private properties***.

Simply removing `componentN` functions isn’t an option - this would break too much existing code. In our projects alone, it would cause:
- *Kotlin repository: 292 errors*
- *IntelliJ IDEA repository: 1149 errors*

Instead, we need an alternative that works by name, making destructuring safer and more predictable.

## Proposal
Name-based destructuring provides ***an alternative*** to positional destructuring, avoiding issues with property order and making destructuring safer and more readable.

### High-Level Proposal
With name-based destructuring, properties are assigned based on their ***names*** rather than their position. This eliminates errors caused by property order changes and makes destructuring more predictable.

In positional destructuring, values are assigned based on the order of `componentN` functions:

```kotlin
data class Cat(val name: String, val color: String)

// Positional destructuring
val (component1, component2) = cat  // tied to order, not names
```

With name-based destructuring, properties are assigned by name, making the order irrelevant:

```kotlin
// Properties are assigned by name, not position
(val name, val color) = cat
(val color, val name) = cat  // order does not matter
```

Under the hood, this translates to simple property access:
```kotlin
val local$cat = cat
val name = local$cat.name
val color = local$cat.color
```

This syntax can also be used in ***lambda*** expressions and ***for-loops***:

```kotlin
// positional destructuring
catList.map { (name, color) -> ... }
for ((name, color) in catList) { ... }

// name-based destructuring
catList.map { (val name, val color) -> ... }
for ((val name, val color) in catList) { ... }
```

Name-based destructuring allows renaming properties during destructuring, making variable names more meaningful:

```kotlin
(val someColor = color, val someName = name) = cat
```

Here, `someColor` and `someName` are local variables, while `color` and `name` come from the class.

Unlike positional destructuring, name-based destructuring allows a mix of mutable (`var`) and immutable (`val`) variables.

```kotlin
(val name, var color) = cat
```

This expands to:

```kotlin
val local$cat = cat
val name = local$cat.name
var color = local$cat.color
```

Just like positional destructuring, name-based destructuring supports specifying types:

```kotlin
(val color: String, val name) = cat
(val someColor: String = color, val someName = name) = cat
```

By making destructuring explicit and order-independent, name-based destructuring provides a safer and more readable alternative to positional destructuring.

#### How Name-Based Destructuring Addresses Existing Problems
**Loss of Semantic Clarity**. Binds variables by *name*, preventing incorrect assignments due to positional mapping.
**Error-Prone Changes**. Adding or reordering properties does not affect destructuring.
**Binary Compatibility**. Avoids `componentN` functions, preventing binary-breaking changes. Provides an *alternative* to positional destructuring and lays the groundwork for future defaults.
**Private Properties**. Allows destructuring of public properties even if private ones come first in the constructor.

#### Future of Positional Destructuring
This proposal ***does not deprecate*** positional destructuring. Instead, name-based destructuring is introduced as an ***alternative*** for cases where property names matter. Positional destructuring is still desirable in certain use cases. For example, data classes such as `Pair` and `Triple` make sense only with positional destructuring since these classes don’t contain any name-specific information, relying solely on the position.

Positional destructuring is also preferable when the call-site confusion is not a concern, and the developer wants to write more concise code. Name-based destructuring is longer than simple property assignment and can clutter the code, potentially reducing readability in some cases. See the following example:

```kotlin
class Point(val x: Int, val y: Iny)

// Positional destructuring
val (x1, y1) = point1
val (x2, y2) = point2

// Name-based destructuring
(val x1 = x, val y1 = y) = point1
(val x2 = x, val y2 = y) = point2
```

### Technical Details
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
val name = local$cat.name
```

This transformation follows standard property access patterns and ***does not guarantee*** atomicity, leaving it to the developers to ensure proper synchronization when necessary. Access is made in the order of destructured variables.

#### Feature Scope
Name-based destructuring applies ***only*** to properties that are ***directly accessible*** on the right-hand side (RHS) of an assignment. It does not rely on `componentN` functions and works only with values that can be accessed ***without calling functions, using reflection, or navigating through nested properties***.

It supports:
- *Member properties*. Regular `val` and `var` inside a class.
- *Extension properties*. Properties defined via extensions.
- *Private properties*. Only if they are accessible in the current scope (e.g., within the same class).

#### Name-Based Destructuring for Regular Classes
Since name-based destructuring is purely syntactic sugar, there is no blocker preventing its use for all types of classes. Name-based destructuring feature **will be supported** for regular classes. We don’t have a strong motivation or existing problems that require name-based destructuring for regular classes. However, this feature could improve the user experience, especially considering that regular classes already support positional destructuring.

It would be particularly useful because regular classes often include primitive components for exposing public APIs and providing shorthand access.

```kotlin
class Foo(val prop: String) {
    operator fun component1(): String = prop
}
```

**Note**. *There is a risk that name-based destructuring could lead to code clutter.*


#### Relation to Pattern Matching
The proposed syntax could be a step toward supporting pattern matching in the future, which is why it’s important to mention it in the proposal.

Currently, we ***don’t have a clear plan*** to implement pattern matching, as Kotlin’s smart casting already handles many common cases.

However, the new syntax is **compatible** with potential pattern matching features. It works not only for primary constructors but for any accessible properties.

Here’s an example of how pattern matching could look in the future:

```kotlin
when (something) {
    // Name-based destructuring
    is Cat(val name == "Jerry") -> println("It's Jerry the Cat!")
    is Cat(val name) if name == "Jerry" -> println("It's Jerry the Cat!")
    
    // Positional destructuring
    is Pair("first", "second") -> println("It's a matching Pair!")
    is Pair(a, b) if a == "first" && b == "second" -> println("It's a matching Pair!")
    
    else -> println("No match found.")
}
```

This example shows how both name-based and positional destructuring could fit naturally into pattern matching.

#### Risks
1. The chosen syntax does not allow the IDE to suggest property names in destructuring as it is writing from left to right. However, declaring the destructured property first and then the destructuring variable doesn't align with the way variables are typically written in Kotlin.

    To mitigate this risk, IDE should offer autocompletion that builds a destructuring template, prompting users to write the destructured property first, followed by the destructuring variable names.
    ```kotlin
    (val <caret>) = cat // sample of IDE autocompletion suggestion
    ```

2. The syntax for single-property destructuring can be visually confusing:
    ```kotlin
    val (name) = cat.name
    (val name) = cat
    ```
    Although these look similar, they work differently. This could cause misinterpretation, especially when reading code quickly.

### Q&A
- *How can the outlined problem for data classes be solved by name-based destructuring?*

    At the current point of view, name-based destructuring would not solve the issues with positional destructuring directly. However, it opens the door for future enhancements to data classes and could potentially become the new default for data class destructuring in the future.

- *What will happen with autogenerated `componentX` functions?*
Autogenerated componentX functions will remain unchanged in the foreseeable future. This means that forward-compatible data class issues will still exist. However, developers can avoid name confusion at the call site by using name-based destructuring, even if the library introduces non-forward-compatible changes.

- *Can `(val x = a.b.c) = d` or other complex acessors be allowed?*
Right now, there is no strong motivation or use case for this. Expanding the syntax to allow complex expressions should be considered separately from the destructuring problems.

### Future Migration
We are exploring the possibility of making name-based destructuring **the default** for data classes in the future. While there are no concrete plans yet, this change could be introduced gradually, starting as an experimental feature. We are also considering how it interacts with other auto-generated functions (e.g. `copy`) in data classes. Any migration path will be carefully designed to minimize impact.

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
- `cat is Cat(val name, val color) или cat is (val name, val color)` – breaks Kotlin assignment rules; LHS must be a variable, RHS must be an accessor.
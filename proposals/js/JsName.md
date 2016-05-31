# Ability to customize generated name in Kotlin JS

* **Type**: Kotlin JS design proposal
* **Author**: Zalim Bashorov
* **Status**: Submitted
* **Related issue**: [KT-2752](https://youtrack.jetbrains.com/issue/KT-2752)

## Use cases / motivation

- Get better names in API (without mangling).
- Resolve clashing of names (e.g. for overloads).
- Use better name for native declarations in Kotlin side (e.g. declare some function as operator).

## Proposed solution

Introduce `JsName` annotation (like in JVM):

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(/* ... */)
annotation class JsName(name: String)
```

Use **binary retention** since it should be available from deserialized descriptors.

**Allow the annotation for following targets:**

+ `FUNCTION` (constructors are not included)

+ `PROPERTY`
<br/>
New name provided by the annotation should be used for generating name of extension properties too which generated as functions.

+ `CLASS` (class, interface or object, annotation class is also included)
<br/>
It's useful for native declarations, e.g. when name in Kotlin looks ugly or name already used somewhere.
<br/>
*__Question:__ should be prohibited for non native declarations?*

+ `CONSTRUCTOR` (primary or secondary constructor)
<br/>
To generate better name for secondary constructors.
<br/>
*__Frontend:__ The annotation should be prohibited for primary constructors, because applying it to the primary constructor will have the some effect as applying to the class.*


**Prohibit the annotation for following targets:**
- `ANNOTATION_CLASS` (Annotation class only)
- `TYPE_PARAMETER` (Generic type parameter)
- `FIELD` (Field, including property's backing field)
- `LOCAL_VARIABLE`
- `TYPE` (Type usage)
- `EXPRESSION`
- `FILE`

**Questionable targets:**
+ `VALUE_PARAMETER` (parameter of a function or a constructor)
<br/>
Can be useful to provide better interop with some frameworks, e.g. angularjs 1.x, where name of parameters used for injection and some frequently used dependencies contains `$`.
<br/>
**Notes:**
    - this technic (using name of function parameters) is not so popular in JS world, at least I know only about angularjs 1.x where it used;
    - it can be simply break by minifiers;
    - angular 2 uses another way for DI;
    - but it's simple to implement.

*__Question:__ how it should work for overrides?*
* allow only on final
* prohibit on overrides and reuse names from overridden declaration
    * how, then, resolve conflicts?

+ `PROPERTY_GETTER` and `PROPERTY_SETTER`
<br/>
Can be useful for native declarations to provide better api in Kotlin.
Additionally it can be used to force using functions for accessors to avoid problems with some minifiers
(e.g. closure-compiler) which treat access to properties as side effect free.
<br/>
    *__Frontend:__ the annotation can not be applied to only one of accessors.*
<br/>
    *__Frontend:__ the annotation can not be simultaneously applied to the property and its accessors.*
<br/>
    *__Question:__ should it be prohibited for non-native declarations?*
<br/>
    *__Backend:__ properties whose accessors has this annotation no longer are treated as JS properties,
    instead they are interpreted as bunch of accessors (like in JVM), so backend:*
    - *doesn't generate JS property for them;*
    - *translates accessors as functions using name from the annotation;*
    - *translates an accessing to such property as call to accessor function using name from the annotation.*


## How it should work for overrides?

Actually, the problems actual even we prohibit using `JsName` on overrides,
because the compiler doesn't mangle the name of native declarations,
so we can simply get name clash when to inherit from two natives.

And doesn't make sense to prohibit to use this annotation on non-final declarations.

So propose to **prohibit using JsName on overrides**, but **report about name clashes** on non-native declarations.


**Case 1: declarations with different names from different parents with the same requested name (by JsName)**
    _(map many names to the one)_


_Note: A, B, C can be native._

Example 1:
```kotlin
interface A {
    @JsName("boo")
    fun bar()
}


interface B {
    @JsName("boo")
    fun baz()
}

class C : A, B {
    override fun bar() {}
    override fun baz() {}
}
```

Example 2:
```kotlin
interface A {
    @JsName("boo2")
    fun bar2() {}
}


interface B {
    @JsName("boo2")
    fun baz2() {}
}

class C : A, B
```

Proposed solution: allow name clashes on native declarations and prohibit on non-native declarations.

**Case 2: declarations with the same name from different parents with different requested name (by JsName):**
    _(map the one name to many)_

_Note: A, B, C can be native._

Example 1:
```kotlin
interface A {
    @JsName("fooA")
    fun foo()
}


interface B {
    @JsName("fooB")
    fun foo()
}

class C : A, B {
    override fun foo() {}
}
```

Example 2:
```kotlin
interface A {
    @JsName("fooA")
    fun foo2() {}
}


interface B {
    @JsName("fooB")
    fun foo2()
}

class C : A, B
```

Proposed solution: prohibit to have more than one name candidate for all declarations including native,
                   otherwise, we can't choose the right name on call site.

Another possible solutions:
* allow to have more than one name candidate for all or only native declarations and report an error on call site when having many name candidates to call.
<br/>
In this case generated code should contain all name candidates which referenced to the same function to be compatible with all implemented interfaces.


## Common

*__Frontend:__ the annotation can not be combined with override.*

*__Frontend:__ consider new names when process clashes.*

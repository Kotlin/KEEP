# Use JavaScript modules from Kotlin

* **Type**: Kotlin JS design proposal
* **Author**: Zalim Bashorov
* **Contributors**: Andrey Breslav, Alexey Andreev
* **Status**: Submitted

## Goal

Provide the way to add information about related JS module when write native declarations in Kotlin
and use this information to generate dependencies.

## Intro

In general case JS module can be:
  - object with a bunch of declarations (in terms of Kotlin it can be package or object)
  - class
  - function
  - variable

Additionally, we should keep in mind that module import string should not be a valid identifier.


## Proposed solution

Propose to add:

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.FILE)
annotation class JsModule(val import: String)
```

It should have **binary retention** to be available from binary data.

Annotation **allowed on classes, properties and functions** according to what can be exported from JS modules.

_**Frontend:** prohibit to use the annotation on `var`_
<br/>
_It doesn't make sense on module systems except CommonJS (node.js)
and to use it with CommonJS we should call `require` on every call sites to get the current value._
<br/>
_Since it very rare case propose to prohibit it now._

In JS world objects often used as namespace/package so will be nice to allow to map such objects to Kotlin packages.

Why just not using objects in all cases?
1. Some IDE features works better with package level declarations (e.g. auto import)
2. Accessing to package must not have side effects, so in some cases we can generate better code (alternative solution is add marker annotation)

So to achieve that files allowed as the target of the annotation.
But it's not enough, additionally, we should provide the way to specify custom qualifier for native declarations in the file.

It can be achieved by adding yet another parameter to the annotation or add a new annotation, like:

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FILE)
annotation class JsPackage(val path: String)
```
[TODO] think up a better name.

The new annotation prefered because it can be reused in two cases:
1. _Major:_ for native declarations inside nested namespaces / packages.
2. _Minor:_ when we want to have package with long path in Kotlin,
but don't want to have a long path in generated JS, e.g. for JS public API.
Of course, this problem can be fixed by adding another file "facade" with a short qualifier.


**Parameter of `JsModule` annotation:**
- `import` -- string which will be used to import related module.

## How mark declarations which available with module systems and without module systems?

Possible solutions:
1. Add additional parameter to `JsModule` annotation
Pros:
- minor: simpler to discover
Cons:
- it makes harder to reuse `JsModule` annotation for non-external declaration in the future
- maybe it'll strange to see a parameter about declaration in the annotation about module

2. Add separate annotation
Pros:
- minor: simpler to evolve
Cons:
- more verbose
- yet another annotation
Note:
- the annotation allowed only on native declarations
- the annotation w/o `JsModule` doesn't change anything

Another problem is to think up a good name for that.

Some name candidates:
- JsSimpleModule
- JsNonModule
- JsPlainModule
- JsPlainModule
- JsPlain
- existsInNonModuleMode
- availableInNonModuleMode
- existsOutsideOfModule
- availableOutsideOfModule


**NOTE: In following code fragments temporary used `JsNonModule` annotation.**

## Use cases (based on TypeScript declarations)

**Simple module declaration**

Code in TypeScript:
```typescript
declare module "MyExternalModule" {
    export function foo();
    export var bar;
    export namespace baz {
        function boo();
    }
}
```

In Koltin it can be written like:
```kotlin
// file1.kt
@file:JsModule("MyExternalModule")
package MyExternalModule

@native fun foo() {}
@native var bar: Any = noImpl;
```
```kotlin
// file2.kt
@file:JsModule("MyExternalModule")
@file:JsPackage("baz")
package MyExternalModule.baz

@native fun boo() {}
```

**Export by assignment**

In TypeScript:
```typescript
declare module "MyExternalModule" {
    export = function foo();
}
```

In Kotlin:
```kotlin
package MyExternalModule

@JsModule("MyExternalModule")
@native fun foo() {}
```

**Export by assignment from toplevel**

In TypeScript:
```typescript
declare var prop: MyClass;
export = prop;
```

In Kotlin:
```kotlin
package SomeModule

@JsModule("SomeModule")
@native var prop: MyClass = noImpl
```

**Export by assignment the declaration decelerated outside of module**

In TypeScript:
```typescript
declare var prop: MyClass;

declare module "MyExternalModule" {
    export = prop;
}
```

In Kotlin:
```kotlin
package SomeModule

@JsModule("MyExternalModule")
@JsNonModule
@native var prop: MyClass = noImpl
```

## Implementation details

### Backend

Let's consider the following files:

**declarations1.kt**
```kotlin
@file:JsModule("first-module")

var a: Int = noImpl
```

**declarations2.kt**
```kotlin
@JsModule("second-module")
var b: Int = noImpl
```

**declarations3.kt**
```kotlin
@file: JsModule("thirdModule")

var c: Int = noImpl
```

**declarations4.kt**
```kotlin
@JsModule("fourthModule")
var d: Int = noImpl
```

**usage.kt**
```kotlin
fun test() {
    println(a)
    println(b)
    println(c)
    println(d)
}
```

Use import value as is to declare dependencies when translate them with **module kind COMMON_JS or AMD**.
<br/>E.g. for CommonJS generate following:
```javascript
var first_module = require("first-module");
var b = require("second-module");
var thirdModule = require("thirdModule");
var d = require("fourthModule");
//...
```

When **module kind is SIMPLE**
- If import value is valid JS identifier use it as is as name of identifier;
- Otherwise, try to get from `this` using import value as is (as string).

```javascript
(function(first_module, b, thirdModule, d) {
// ...
   println(first_module.a, b, thirdModule.c, d);
// ...
}(this["first-module"], this["second-module"], thirdModule, fourthModule));
```

And for **module kind is UMD** compiler should use relevant rule for each block.

### Frontend
- Prohibit to apply `JsModule` annotation to non-native declarations, except files.<br/>
It can be relaxed later e.g. to reuse this annotation to allow translate a file to separate JS module,
it can be useful to interop with some frameworks (see [KT-12093](https://youtrack.jetbrains.com/issue/KT-12093))

### IDE
- Add inspection for the case when some declarations with the same fq-name
  Consider next cases:
  - function overloads
  - package and functions

## Open questions
1. Can we introduce default value for `import` parameter of `JsModule` and use the name of declaration as import string when argument not provided?<br/>
If so, how it should work when the annotation used on file?

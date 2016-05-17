# Use JavaScript modules from Kotlin

* **Type**: Kotlin JS design proposal
* **Author**: Zalim Bashorov
* **Contributors**: Andrey Breslav, Alexey Andreev
* **Status**: Submitted
* **Prototype**:

## Goal

Provide the way to add information about related JS module when write native declarations in Kotlin
and use this information to generate dependencies.

## Intro

In general case JS module can be:
  - object with a bunch of declarations (in terms of kotlin it can be package or object)
  - class
  - function
  - variable

Additionally we should keep in mind that module import string should not be valid identifier.


## Proposed solution

Propose to add:

```kotlin
@Repeatable
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.FILE)
annotation class JsModule(
    val import: String,
    vararg val kind: JsModuleKind = arrayOf(JsModuleKind.AMD, JsModuleKind.COMMON_JS, JsModuleKind.UMD)
)

enum class JsModuleKind {
    SIMPLE,
    AMD,
    COMMON_JS, // CJS?
    UMD
}
```

It should be **repeatable** to allow to specialize settings for concrete JsModuleKind.

It should have **binary retention** to be available from binary data.

Annotation **allowed on classes, properties and functions** according to what can be exported from JS modules.

In JS world objects often used as namespace/package so will be nice to allow to map such objects to Kotlin packages.

Why just not using objects in all cases?
1. Some IDE features works better with package level declarations (e.g. auto import)
2. Accessing to package must not have side effects, so in some cases we can generate better code (alternative solution is add marker annotation)

So to achieve that files allowed as target of the annotation.
But it's not enough, additionally we should provide the way to specify custom qualifier for native declarations in the file.

It can be achieved by adding yet another parameter to the annotation or add a new annotation, like:

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FILE)
annotation class JsPackage(val path: String)
```
[TODO] think up a better name.

The new annotation can be reused in case when we want to have package with long path in Kotlin,
but don't want to have long path in generated JS, e.g. for public API.
Of course, this problem can be fixed by adding another file "facade" with short qualifier.


Parameters:
- `import` -- string which will be used to import related module.
- `kind` -- shows for which kind of modules this role should be applied.


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

**Export by assignment form toplevel**

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
@JsModule("MyExternalModule", kind = JsModuleKind.SIMPLE)
@native var prop: MyClass = noImpl
```
Second role means that when translate with SIMPLE module kind for this module
compiler should generate import through variable `MyExternalModule`


Another way:
```kotlin
package SomeModule

@JsModule("MyExternalModule")
@JsModule("this", kind = JsModuleKind.SIMPLE)
@native var prop: MyClass = noImpl
```
And now when translate with SIMPLE module kind for this module
compiler should generate import through variable `this` (usually it's Global Object)


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

Use import value as is to declare dependencies when translate them with **any module kind except SIMPLE**.
<br/>E.g. for CommonJS generate following:
```javascript
var first_module = require("first-module");
var b = require("second-module");
var thirdModule = require("thirdModule");
var d = require("fourthModule");
//...
```

When **module kind is SIMPLE**
- If import value is valid JS identifier or `this` then use it as is as name of identifier;
- Otherwise, try to get from `this` using import value as is (as string).

```javascript
(function(first_module, b, thirdModule, d) {
// ...
}(this["first-module"], this["second-module"], thirdModule, fourthModule));
```

### Frontend
- Report error when try to use native declaration which has `JsModule` annotations, but no one specify rule for current module kind.
- Prohibit to have many annotations which explicitly provide the same module kind.
- Prohibit to apply `JsModule` annotation to non-native declarations, except files.<br/>
It can be relaxed later e.g. to reuse this annotation to allow translate a file to separate JS module, it can be useful to interop with some frameworks (see [KT-12093](https://youtrack.jetbrains.com/issue/KT-12093))

### IDE
- Add inspection for case when some declarations with the same fq-name have different JsModule annotations?
  Consider next cases:
  - function overloads
  - package and functions

## Open questions
1. Can we introduce default value for `import` parameter of `JsModule` and use the name of declaration as import string when argument not provided?<br/>
If so, how it should work when the annotation used on file?

2. What should be default value of `kind` parameter of `JsModule`?
    1. all kinds
    2. all kinds except SIMPLE

    In TypeScript (external) modules can be used only when compiler ran with module kind (in our terms it's all except SIMPLE).
    So, should second be default to generate simpler code from TS declarations?

3. Actually right now we needs to know only is `kind === SIMPLE` or not, so should we simplify API?

4. Unfortunately we can't use constants for `kind` parameter to make API better. Can we fix it somehow?

5. Will be nice to have the way to say that all declarations in this file is native. But how it fit with idea to replace `@native` with `external`?

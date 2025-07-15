# Named-only parameters

* **Type**: Design proposal
* **Author**: Roman Efremov
* **Contributors**: Alejandro Serrano Mena, Denis Zharkov, Dmitriy Novozhilov, Faiz Ilham Muhammad, Marat Akhin, Mikhail Zarečenskij, Nikita Bobko, Pavel Kunyavskiy, Roman Venediktov
* **Discussion**: [#442](https://github.com/Kotlin/KEEP/discussions/442)
* **Status**: KEEP discussion
* **Related YouTrack issue**: [KT-14934](https://youtrack.jetbrains.com/issue/KT-14934)

## Abstract

We introduce a new modifier `named` on function value parameters that obliges callers of this function to specify the name of this parameter.
This enhances code readability and reduces errors caused by value associated with the wrong parameter when passed in positional form.

Here's an example:

```kotlin
fun String.reformat(named normalizeCase: Boolean, named upperCaseFirstLetter: Boolean): String { /* body */ }

str.reformat(false, true) // ❌ ERR
str.reformat(normalizeCase = false, upperCaseFirstLetter = true) // ✅ OK
```


## Table of contents

- [Motivation](#motivation)
  - [Examples](#examples)
  - [Multiple lambda arguments](#multiple-lambda-arguments)
  - [Need in language-level support](#need-in-language-level-support)
- [Design](#design)
  - [Basics](#basics)
  - [Overload resolution](#overload-resolution)
  - [Method overrides](#method-overrides)
  - [Expect/actual matching](#expectactual-matching)
  - [Interoperability](#interoperability)
  - [Migration cycle](#migration-cycle)
  - [Compilation](#compilation)
  - [Data class copy() migration](#data-class-copy-migration)
  - [Tooling support](#tooling-support)
- [Alternatives](#alternatives)
  - ["Fence" approach](#fence-approach)
  - [Annotation or modifier?](#annotation-or-modifier)

## Motivation

There are two usual ways of [passing arguments to functions](https://kotlinlang.org/docs/functions.html#function-usage) in Kotlin: positionally or by name (there are more ways, like default arguments or infix functions, but we're not looking at them in this paragraph).

Sometimes passing an argument in positional form is not desirable because of the resulting ambiguity.
For example, consider the following function:

```kotlin
fun reformat(
    str: String,
    normalizeCase: Boolean = true,
    upperCaseFirstLetter: Boolean = true,
    divideByCamelHumps: Boolean = false,
    wordSeparator: Char = ' ',
) { /*...*/ }
```

When it is called without argument names, for example `reformat(myStr, true, false, true, ' ')`, it's difficult to say for the reader what the meaning of its arguments is.

That's why developers always try to remember to specify the argument names in such cases.
[Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html#named-arguments) also recommend to use named arguments syntax when a method takes multiple parameters of the same primitive type, or for parameters of `Boolean` type.

In Java, it's also a common practice to specify argument names in the comments, e.g. `reformat(myStr, /* normalizeCase = */ true)`.
This is a clear sign that argument names can sometimes play a critical role in code readability.

### Examples

Here are several examples of functions that have one or several arguments meant to be passed only in a named form:

| Function                                                                                                                        | Named-only argument(s)                                                                                     |
|---------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `fun parentsOf(element: PsiElement, withSelf: Boolean)`                                                                         | `withSelf`                                                                                                 |
| `fun CharSequence.startsWith(char: Char, ignoreCase: Boolean = false)`                                                          | `ignoreCase`                                                                                               |
| `fun <T> assertEquals(expected: T, actual: T)`                                                                                  | `expected`, optionally `actual`                                                                            |
| `fun <T> copy(src: Array<T>, dst: Array<T>)`                                                                                    | `src`, optionally `dst`                                                                                    |
| `fun <T> Array<out T>.joinToString(separator: CharSequence = ", ", prefix: CharSequence = "", postfix: CharSequence = "")`      | all                                                                                                        |
| `fun Modifier.padding(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp)`                                     | all                                                                                                        |
| `class RetrySettings(val maxRetries: Int, val delayMillis: Long, val exponentialBackoff: Boolean, val retryOnTimeout: Boolean)` | all                                                                                                        |
| `class User(val userId: Long, val nickname: String, val metaInfo: UserMeta?)`                                                   | `metaInfo` – type is pretty self-descriptive, however, name still might be desirable when `null` is passed |
| `copy()` functions of data classes                                                                                              | all, because usually only a few properties are changed when copying                                        |

If there was a way to enforce a named form of arguments, it would increase the readability for calls of such functions and eliminate mistakes caused by ambiguity in arguments passed positionally.

### Multiple lambda arguments

When the last function argument is of a functional type, it's also possible to use a special [trailing lambda](https://kotlinlang.org/docs/lambdas.html#passing-trailing-lambdas) syntax, in which a corresponding argument can be placed outside the parentheses.

When a function is called with multiple lambda arguments, the correspondence of arguments to parameters can also be ambiguous.
The most notable examples are:

* `fun Result.process(onSuccess: () -> Unit, onError: () -> Unit)`
* `fun <T, K, V> Array<out T>.groupBy(keySelector: (T) -> K, valueTransform: (T) -> V)`
* `fun <C> Either<C>.fold(ifLeft: (left: A) -> C, ifRight: (right: B) -> C): C`

For these functions, we'd like to forbid a positional form of arguments, e.g. `result.process({}, {})`.
In addition to that, we'd like to forbid the last lambda argument to be passed in trailing form, e.g. `result.process(onSuccess = {}) { println("or error") }`.

### Need in language-level support

It's possible to enforce the named form of function arguments by static code analysis tools ([example](https://detekt.dev/docs/next/rules/potential-bugs/#unnamedparameteruse)), by an IDE inspection ([example](https://www.jetbrains.com/help/inspectopedia/BooleanLiteralArgument.html)) or by implementing a compiler plugin.

However, such tools don't solve the problem completely because they require preliminary setup.
Users must adjust their build setup themselves to make it work.
This is not a practical approach in the case of libraries that would like to enforce this rule on consumers of their APIs.
Thus, this proposal aims to provide language-level support for this feature.

## Design

### Basics

It’s proposed to introduce a notion of named-only value parameters, which is indicated by a new soft keyword modifier `named` put on them.
When this modifier is applied, the compiler ensures that the argument is passed either with name or omitted (in the case of an argument with a default value).
Otherwise, an error is produced.

Here's an example to illustrate the behavior:

```kotlin
fun CharSequence.startsWith(char: Char, named ignoreCase: Boolean = false): Boolean { /* ... */ }

cs.startsWith('a') // OK
cs.startsWith('a', ignoreCase = false) // OK
cs.startsWith('a', false) // ERROR
```

In the case of lambda parameters, this means that they can't be passed in a trailing form when `named` modifier is applied.

Modifier `named` is applicable for class constructor value parameters.

`named` modifier is not applicable to:

* context parameters
* parameter of property setter
* parameters of function types

### Overload resolution

The presence of the `named` modifier doesn't affect overload resolution.
The check of whether a mandatory name of an argument is provided happens after resolution.
Here's an example that demonstrates this:

```kotlin
fun foo(named p: Int) {} // (1)
fun foo(p: Any) {} // (2)

fun main() {
    foo(1) // resolved to (1) and reports an error about a missing argument name
           // despite that (2) is an applicable candidate 
}
```

Why not do the opposite – affect overload resolution? One of the reasons is that it would lead to unwanted resolution change.

Consider the following example:

```kotlin
class MyOptimizedString : CharSequence { /* ... */ }

fun MyOptimizedString.indexOf(string: String, startIndex: Int = 0, ignoreCase: Boolean = false): Int = TODO()

fun test(s: CharSequence, substr: String) {
     if (s is MyOptimizedString) {
       val i = s.indexOf(substr, 0, true)
    }
}
```

Here, we define a class `MyOptimizedString` optimized for substring search operations.
We also define a specific override of a `indexOf` function with a signature similar to standard `CharSequence#indexOf`.

Suppose that we decided to adopt a new feature and put `named` modifier on the parameters `startIndex` and `ignoreCase` of our function.
What we expect to happen after a change is that all places in the code where names of respective arguments are not specified will be reported as errors.
What will happen instead, however, is that all incorrect `indexOf` calls, like the one in `test` function, will silently change their resolve to more general overload `CharSequence#indexOf` because its parameters don't require names.

Another drawback when `named` parameters affect resolution is that skipping argument becomes a way to resolve overload ambiguity.
But it's absurd to assume that an argument name is omitted not because of a mistake, but because a code writer intentionally wants to choose a specific overload.

### Method overrides
 
It's a warning when the presence of `named` modifier is different on overriding and overridden method.

Whenever a callable with the same signature is inherited from more than one superclass (sometimes known as an _intersection override_),
the parameter in intersection gets a `named` modifier if all the respective parameters from overridden functions are `named` **and** have the same name.

```kotlin
interface A {
    fun foo(named p: Int)
    fun bar(named p: Int)
    fun baz(named p: Int)
}

interface B {
    fun foo(named p: Int)
    fun bar(named other: Int)
    fun baz(p: Int)
}

interface C : A, B {
    // intersection overrides
    // fun foo(named p: Int)
    // fun bar(<ambiguous>: Int)
    // fun baz(p: Int)
}
```

### Expect/actual matching

Presence of `named` modifier on parameter must strictly match on the `expect` and `actual` declarations, otherwise it's an error.
For `actual` declarations coming from Java this means that matching is not possible, when `expect` declaration has `named` parameter.

The main motivation for this is the issues with the current Kotlin Multiplatform compilation model.
The problem is, in certain scenarios common code can see platform declarations ([KT-66205](https://youtrack.jetbrains.com/issue/KT-66205)).
If we allowed a Kotlin `expect` function with `named` parameter to actualize to Java declaration, such a function can't even be called from the common code of a dependent project.
This is because it will see named-only parameter in one compilation, and Java (effectively positional-only) parameter in another, which contradict each other. 
Thus, it was decided to start with the most restrictive rules to have less confusing behavior. 

### Interoperability

Presence of `named` on the arguments doesn't affect the way Kotlin functions are exported and called in other languages.
For example, in case of Java, `named` arguments of Kotlin function will be passed in positional form.

### Migration cycle

Library authors might want to integrate this feature into already existing APIs.
To make the migration process smooth and give authors full control of when warnings will be turned into errors, it's proposed to introduce a new annotation in Kotlin standard library:

```kotlin
@Target(FUNCTION, CONSTRUCTOR, FILE)
@Retention(BINARY)
annotation class SoftNamedOnlyParametersCheck(named val ideOnlyDiagnostic: Boolean = false)
```

When this annotation is set on a function or constructor, it will soften all errors caused by the missing name of the argument to warnings.
Here's an example:

```kotlin
@SoftNamedOnlyParameterCheck
fun CharSequence.startsWith(char: Char, named ignoreCase: Boolean = false): Boolean { /* ... */ }

cs.startsWith('a', false) // WARNING
```

When the annotation is applied to a data class constructor, it's applied to a generated `copy()` function as well. 
When the annotation is applied to a file, it applies to all top-level functions in this file.

> [!NOTE]
> `VALUE_PARAMETER` target is not in the list of annotation targets, as it's hard to imagine a case when one parameter of a function must be a warning while another one is error.

In the case of particularly popular libraries (including the Kotlin standard library and kotlinx.* libraries), there easily could be hundreds of calls only within one project.
Even this solution may seem too harsh, as it may clutter the compiler build log with warnings.
That's why we introduce an additional `ideOnlyDiagnostic` parameter, which leaves warnings only in IDE and disables the diagnostic everywhere else.

### Compilation

The fact of presence of `named` modifier is saved in the metadata.
Other than that, the modifier doesn't affect the output artifact of the compiler.

### Reflection

Interface `kotlin.reflect.KParameter` should be extended with a new property `isNamedOnly: Boolean`.

### Data class copy() migration

As previously mentioned in "Examples" section, `copy()` function of data classes is another place where all the parameters are meant to be named-only.
This observation is also reflected in the [IntelliJ IDEA inspection](https://www.jetbrains.com/help/inspectopedia/CopyWithoutNamedArguments.html) that reports soft warnings in case `copy()` function is called without named arguments.

It's proposed to make all the parameters of `copy()` functions of data classes named-only.
However, as this is a breaking change, it's proposed to split the migration into two phases:

**Phase 1**

* When compiling data classes from source files, mark all the parameters of `copy()` functions with `named` modifier.
* When reading data classes produced by older versions of the compiler, treat them as if `named` modifier were set.
* Soften errors caused by missing names in `copy()` calls to warnings.
* Provide a compiler flag to fast-forward to Phase 2 (e.g. `-Xnamed-only-parameters-in-data-class-copy`). 

**Phase 2**

* Don't soften errors caused by the missing name in `copy()` calls anymore (unless `@SoftNamedOnlyParameterCheck` annotation is applied to data class constructor)

> [!NOTE]
> The phases are not tied to specific compiler versions or timeline.

### Tooling support

IDE should suggest a quick fix, which would specify parameter names explicitly when the diagnostic about missing name is reported.

## Alternatives

### "Fence" approach

In current proposal `named` modifier is set on each parameter separately.
Another option is to apply what can be called "fence" approach where named-only parameters are fenced off from the rest of the parameters by a special symbol or another syntactic element.
This approach can be seen in languages like Python or Julia.
In the case of Kotlin, here's what it could look like:

```kotlin
// All arguments after asterisk are named-only
fun CharSequence.startsWith(char: Char, *, ignoreCase: Boolean = false)
fun Modifier.padding(*, start: Dp = 0.dp, top: Dp = 0.dp,
                     end: Dp = 0.dp, bottom: Dp = 0.dp)
```

This is especially beneficial for functions with a big number of parameters (consider, for example, [TextStyle](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextStyle) from Jetpack Compose with 25 parameters).

However, we decided not to go this way.
The problem is that this approach is based on the assumption that named-only parameters should always go together and reside at the end of the argument list.
In the meantime, `named` modifier provides more flexibility and supports some important use cases, that do not meet this assumption, namely:

1. In DSLs it's often the case that a group of named-only parameters is followed up by a trailing lambda parameter.
2. Sometimes the name of the first argument could serve as a continuation of the function name, where right now it leaks into a name of the function.

As an example for a second case, let's take a look at transformation functions from `kotlin.collections`.
Many of them are available in two variants with names `<transformationname>` and `<transformationname>To`.
Consider these `fold` and `foldTo` functions:

```kotlin
fun <T, K, R> Grouping<T, K>.fold(
  initialValue: R, 
  operation: (accumulator: R, element: T) -> R
): Map<K, R>

fun <T, K, R, M : MutableMap<in K, R>> Grouping<T, K>.foldTo(
  destination: M, 
  initialValue: R, 
  operation: (accumulator: R, element: T) -> R
): M
```

With new feature we could rewrite `foldTo` as an override of `fold` with first parameter being named-only in the following way:

```kotlin
fun <T, K, R, M : MutableMap<in K, R>> Grouping<T, K>.fold(
  named to: M, 
  initialValue: R, 
  operation: (accumulator: R, element: T) -> R
): M
```

This way, we reduce the number of function variants while maintaining clarity because the parameter name will always be present.

It's worth stipulating that this doesn't mean we're promoting this style as the better replacement for the previous one.
It's still up to API authors' preferences.
The only thing that is important in this example is just that the chosen syntax approach with `named` modifier allows writing in both styles.

### Annotation or modifier?

It's a common question for many language features whether a new modifier should instead be a new annotation. 
In the case of named-only parameters, the choice was made in favor of modifier for the following reasons:

* It looks cleaner in code
* Easily distinguishable from user-defined annotations
* The feature aims to be widely used and is not tied to a specific domain or use case (unlike, for example, `DslMarker` or `BuilderInference`)

### Make named-only parameters a new default

We can't go past a possibility to make all parameters named-only by default, which is the way it works in Swift.
Among named-only, positional-only and flexible naming parameters, the latter option seems to be the most commonly desired.
That's why we believe the current default is good, and it's not our goal to migrate a whole world.
Moreover, migration for such a core functionality would be quite onerous.

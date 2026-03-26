# Power-Assert Explanation

* **Type**: Design proposal
* **Author**: Brian Norman
* **Contributors**: Mikhail Zarechenskii, Marat Akhin
* **Status**: Prototype available in 2.4.0-Beta2.
* **Discussion**: TODO
* **YouTrack Issue**:
  [KT-66807](https://youtrack.jetbrains.com/issue/KT-66807),
  [KT-66806](https://youtrack.jetbrains.com/issue/KT-66806),
  [KT-66808](https://youtrack.jetbrains.com/issue/KT-66808)

# Abstract

Power-Assert is a Kotlin compiler plugin which enables transforming function calls to include detailed information about
the call-site. This information is currently in the form of a compile-time generated String passed as an argument to the
function. We will introduce a new annotation to automatically trigger transformation of function calls, new data
structures to represent call-site information, and a way to access call-site information from an annotated function.

# Table of contents

<!-- TOC -->
* [Power-Assert Explanation](#power-assert-explanation)
* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Introduction](#introduction)
  * [Background](#background)
  * [Key Problems](#key-problems)
  * [Goals](#goals)
  * [Non-Goals](#non-goals)
* [Proposal](#proposal)
  * [Call-Site](#call-site)
  * [Declaration](#declaration)
  * [Maintained Behavior](#maintained-behavior)
* [API Overview](#api-overview)
  * [`@PowerAssert`](#powerassert)
  * [`CallExplanation`](#callexplanation)
  * [`Expression`](#expression)
  * [`CallExplanation.Argument`](#callexplanationargument)
  * [`Explanation`](#explanation)
  * [Functions](#functions)
  * [Source and Offsets](#source-and-offsets)
* [Transformations](#transformations)
  * [Function Declarations](#function-declarations)
  * [Function Calls](#function-calls)
  * [String Message Calls](#string-message-calls)
* [Advanced](#advanced)
  * [Runtime Dependency](#runtime-dependency)
  * [Backwards Compatibility](#backwards-compatibility)
  * [Security](#security)
  * [Interactions](#interactions)
* [Use Cases](#use-cases)
  * [IntelliJ Integration](#intellij-integration)
  * [Soft/Fluent Assertions](#softfluent-assertions)
  * [Custom Diagrams](#custom-diagrams)
* [Feedback](#feedback)
  * ["Why are there so few interesting `Expression` subclasses?"](#why-are-there-so-few-interesting-expression-subclasses)
  * ["Why the `Explanation` base class?"](#why-the-explanation-base-class)
  * ["Can you add XYZ to `Explanation`/`Expression`?"](#can-you-add-xyz-to-explanationexpression)
  * ["Why an expression List and not a tree?"](#why-an-expression-list-and-not-a-tree)
<!-- TOC -->

# Introduction

In general, this KEEP expects some familiarity with using Power-Assert as a Kotlin compiler plugin. There is
[documentation available on the Kotlin website](https://kotlinlang.org/docs/power-assert.html) and a
[talk from KotlinCont 2024](https://www.youtube.com/watch?v=N8u-6d0iCiE) if you want more background than what is
provided in this proposal.

## Background

Power-Assert transforms each function call that matches a set of fully-qualified function names. By default, it is
applied to the `kotlin.assert` function, which will transform the following call.

```kotlin
data class Mascot(val name: String)

assert(mascot.name == "Kodee")
```

Into something similar to the following.

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = tmp2 == "Kodee"
assert(tmp3, { "assert(mascot.name == \"Kodee\")\n       |      |    |\n       |      |    $tmp3\n       |      $tmp2\n       $tmp1\n" })
```

The diagram is generated at compile time and produces an output like the following.

```text
assert(mascot.name == "Kodee")
       |      |    |
       |      |    false
       |      Unknown
       Mascot(name=Unknown)
```

This transformation is not limited to only `kotlin.assert`, but may be applied to any function that can take a `String`
or `() -> String` as the ***last argument***. Functions like `kotlin.test.assertTrue` and `kotlin.test.assertEquals` are
good candidates for Power-Assert transformation.

Unfortunately, specifying these functions is required for every compilation. And there is no way for an assertion
library to specify which functions support Power-Assert to the compiler plugin for automatic transformation. This means
that every Gradle project must configure these functions.

## Key Problems

From this overview, we can extract three key problems with Power-Assert:

1. Verbose build-system configuration that complicates onboarding.
2. Confusing function parameter requirements that rely on convention.
3. Static diagram generation that limits tooling integration.

## Goals

In this proposal, we will outline changes which attempt to tackle all of these problems.

1. Power-Assert capable functions should be discoverable rather than needing to be configured. This avoids the complex
build-system configuration needed for the compiler plugin.
2. Power-Assert capable functions should not rely on argument convention but transformation by the compiler plugin
instead. This removes confusing function parameter requirements and enables easier integration for adopting libraries.
3. Power-Assert capable functions should be provided with detailed call-site information. This improves diagram render
by making it more dynamic and enables better tooling integration.

## Non-Goals

While these goals are important, there are also directions we explicitly do not intend to pursue with Power-Assert at
this time.

1. Power-Assert is ***not*** a macro or dynamic code execution system. The compiler plugin must remain focused on code
descriptions and not metaprogramming use cases.
2. Power-Assert is ***not*** a replacement for an assertion library. The compiler plugin must help enhance existing
assertion libraries.

# Proposal

Introduce a set of data structures which can represent call-site information for function calls. Also introduce a new
annotation to mark function declarations that support Power-Assert transformation.

Here's a quick example:

```kotlin
import kotlin.powerassert.PowerAssert
import kotlin.powerassert.CallExplanation
import kotlin.powerassert.toDefaultMessage

@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null) {
    if (!condition) {
        val explanation: CallExplanation? = PowerAssert.explanation // Intrinsic property provided by compiler plugin.
        throw AssertionError(buildString {
            append("Assertion failed:")
            if (message != null) append(" ").append(message)
            if (explanation != null) appendLine().appendLine(explanation.toDefaultMessage())
        })
    }
}
```

These annotations and data structures help fix the key problems with Power-Assert!
1. The `@PowerAssert` annotation makes functions discoverable by the compiler plugin at call-sites.
2. The `PowerAssert.explanation` intrinsic property provided by the compiler plugin means no more confusing function
parameter requirements.
3. The `CallExplanation` data structure can help build a dynamic diagram message and adjust assertion exception.

## Call-Site

A keen observer may notice that none of the above impacts the call-site of a `@PowerAssert` annotated function. The
Power-Assert transformation is quite invisible to the end user of such a function. And since the annotation helps
automatically discover supported functions, this means the fully-qualified function name configuration is not required
for annotated functions!

This also means that if an assertion library were to adopt use of `@PowerAssert`, support for providing a diagram of the
call-site would be seamless and transparent to the end user. All the end user would need to do is apply the
compiler plugin to their project. If you are the user of such an assertion library, you should reach out to the author
and encourage them to provide feedback on this KEEP!

## Declaration

A keen observer may also notice that this means the Power-Assert compiler plugin now needs to be applied to function
declarations as well. To provide a `CallExplanation` to the `@PowerAssert` annotated function, the Power-Assert compiler
plugin must generate a synthetic copy of the annotated function which has an additional parameter of type
`() -> CallExplanation`. This allows Power-Assert to transform the call-site and provide a `CallExplanation` instance.

Details on this `CallExplanation` [transformation by the Power-Assert compiler plugin](#function-declarations) will be
explored later in this proposal.

## Maintained Behavior

If you are worried that the ability for the Power-Assert compiler plugin to support functions which take a `String` or
`() -> String` diagram is being removed, worry not! The compiler plugin will continue to accept fully-qualified function
names as configuration for functions which are not annotated with `@PowerAssert`. In fact, this behavior is being
improved as well: by using `CallExplanation` to generate a diagram at runtime, we can improve the diagram rendering
dramatically by changing the layout of the diagram based on the results of each intermediate expression.

Details on this new `String` diagram [transformation by the Power-Assert compiler plugin](#string-message-calls) will be
explored later in this proposal.

# API Overview

This section will give a ***brief overview*** of some classes from the new Power-Assert runtime library. We encourage
those who want a more in-depth and up-to-date look at the classes to read the
[documentation in the source code][power-assert-runtime].

## `@PowerAssert`

A new annotation will be introduced to mark functions which support being
[transformed by the Power-Assert compiler plugin](#function-declarations). This annotation also provides access to a
compiler plugin intrinsic `CallExplanation` property which can be used to access call-site information.

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class PowerAssert {
    public companion object {
        @JvmStatic
        public val explanation: CallExplanation? // Implemented as compiler plugin intrinsic.
    }
}
```

If the compiler plugin is not applied when compiling access of the `explanation` property, access will result in a
runtime error. The compiler plugin will also limit property access to within functions annotated with `@PowerAssert` at
compile-time.

The `explanation` property will return `null` in cases when a `@PowerAssert` annotated function is called without
call-site information, including:
 * The call-site was compiled without the compiler plugin applied.
 * When called from non-Kotlin code. For example, Java.
 * When called via reflection or method reference.

## `CallExplanation`

The `CallExplanation` class describes each argument provided to a function call individually, while providing the source
code information for the entire call.

```kotlin
public class CallExplanation(
    override val offset: Int,
    override val source: String,
    public val arguments: List<Argument?>,
) : Explanation() {
    override val expressions: List<Expression>
        get() = arguments.sortedBy { it?.startOffset }.flatMap { it?.expressions.orEmpty() }

    public class Argument(
        public val startOffset: Int,
        public val endOffset: Int,
        public val kind: Kind,
        public val expressions: List<Expression>,
    ) {
        public enum class Kind {
            DISPATCH,
            CONTEXT,
            EXTENSION,
            VALUE,
        }
    }
}
```

Details about `source` and offset properties will be [discussed later](#source-and-offsets), but for now, let's focus on
two key classes at work within the `CallExplanation`: `Expression` and `Argument`.

## `Expression`

An `Expression` represents a value calculated at runtime. This could be the result of a function call, variable access,
language operator, or anything else that results in a value.

```kotlin
public abstract class Expression internal constructor(
    public val startOffset: Int,
    public val endOffset: Int,
    public val displayOffset: Int,
    public val value: Any?,
)
```

Expressions are organized within Power-Assert as `List`s and include all intermediate expressions. Lists of expressions
will always be provided in evaluation order. For example, code like `1 + 2` will be represented as a `List` with three
`Expression` elements in the following order:
1. An expression with a `value` of `1`.
2. An expression with a `value` of `2`.
3. An expression with a `value` of `3`, the result of `1 + 2`.

There are currently only three implementations of `Expression`:
* `ValueExpression` - Holds a value calculated at runtime.
* `LiteralExpression` - Holds a literal value, and excluded by default from diagrams.
* `EqualityExpression` - Holds the result of an equality check, and includes the left-hand and right-hand side values.

For more information on these classes, see their [documentation in the source code][power-assert-runtime].

## `CallExplanation.Argument`

All arguments to the function call are provided as a `List` in ***parameter*** order. Arguments of different kinds are
always provided in the same order as the `Kind` enum.

For example, consider a call to the following `example` function:

```kotlin
class Dispatch {
    context(context1: Context1, context2: Context2)
    fun Extension.example(param1: Param1, param2: Param2)
}
```

The explanation will always have six arguments specified in the following order:
1. An argument with `Kind.DISPATCH` for the `Dispatch` receiver.
2. An argument with `Kind.CONTEXT` for the `context1` context parameter.
3. An argument with `Kind.CONTEXT` for the `context2` context parameter.
4. An argument with `Kind.EXTENSION` for the `Extension` receiver.
5. An argument with `Kind.VALUE` for the `param1` parameter.
6. An argument with `Kind.VALUE` for the `param2` parameter.

This order ***will not change*** no matter how the function is called. Even if argument order is changed with named
parameters, the `arguments` list remains in the same order. If an argument to the function is omitted due to a default
value or is implicitly provided (as is common with context parameters) then the corresponding argument in the
`CallExplanation` will be `null`.

In cases when information about an argument is never used, it might be useful to exclude this information using the
`@PowerAssert.Ignore` annotation.

```kotlin
public annotation class PowerAssert {
    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    public annotation class Ignore
}
```

This annotation can be applied directly to a function parameter so that the `Argument` is always `null` in the
`CallExplanation`. It can also be applied to a class, and all parameters of that type will be automatically ignored.
This helps during [function call transformation](#function-calls), as the compiler plugin will not need to generate
temporary variables for the argument subexpressions, thus potentially saving on compiler performance and runtime
overhead. 

## `Explanation`

`CallExplanation` is a subclass of `Explanation` that represents a generic explanation of source code. It is currently
the only implementation of `Explanation`, but there are [ideas for other subclasses](#why-the-explanation-base-class).

```kotlin
public abstract class Explanation internal constructor() {
    public abstract val offset: Int
    public abstract val source: String
    public abstract val expressions: List<Expression>
}
```

## Functions

A top-level function will be included as well, to provide Power-Assert style rendering of an `Explanation`. This
function will be used to replace the current compile-time generated `String` for overload style Power-Assert
transformation.

```kotlin
public fun Explanation.toDefaultMessage(
    render: (Expression) -> String? /* = <default> */,
): String
```

While any project can use this function to generate a Power-Assert style message for exceptions, it is recommended
that assertion libraries implement their own rendering logic. This avoids needing to wait for the compiler plugin to be
updated for improvements and will also help match any existing reporting style.

## Source and Offsets

There are a lot of "offsets" in the Power-Assert runtime library classes, and it may not be clear what they mean.
* `Explanation.offset` is the character offset of `Explanation.source` within the containing file.
* `Expression.startOffset` (inclusive), `Expression.endOffset` (exclusive), and `Expression.displayOffset` are character
offsets ***within the explanation*** for the expression source.

Thus, given an explanation and an expression, a user of these classes can get the source for a particular expression by
taking a substring of `source` starting at `startOffset` and ending at `endOffset`. The `displayOffset` will always be
within the range of `startOffset` and `endOffset`, and is useful for indicating the location of the expression's
`value` in a Power-Assert style diagram.

The `source` provided by an `Explanation` will always be a ***block*** of text, and contain all leading
whitespace of the original code. Text not part of the source code range will be redacted with spaces. Even single-line
function calls will have leading spaces preserved. For example, an explanation of the call to the `powerAssert`
function:

```kotlin
fun test() {
    /* leading comment */ powerAssert(
        mascot.name == "Kodee"
    ) // trailing comment
}
```

Will have the following for a `source` block.

```text
                          powerAssert(
        mascot.name == "Kodee"
    )
```

Notice the preserved leading spaces and redacted comments. Always representing the source code as a block of text is
useful for rendering Power-Assert style diagrams, as a `trimIndent()` can be used to remove the leading whitespace after
everything has been rendered.

Offsets for the four expressions contained within the explanation will be as follows:

```text
                          powerAssert(
        s      d   e - start, display, and end offsets for `mastcot.name`
        |      |   |
        |      |   |   s,d    e - start, display, and end offsets for `"Kodee"`
        |      |   |   |      |
        mascot.name == "Kodee"
        |     |     |         |
        s     |     d         e - start, display, and end offsets for `mascot.name == "Kodee"`
        |     |
        s,d   e - start, display, and end offsets for `mascot`
    )
```

# Transformations

There are a number of transformations now performed by Power-Assert. Function declarations and call-sites for functions
with and without the `@PowerAssert` annotation are transformed in different ways.

## Function Declarations

When `@PowerAssert` is added to a function, the compiler plugin will generate a synthetic copy of the function. This
synthetic copy adds a parameter of type `() -> CallExplanation`. Both functions are then transformed to replace any
calls to `PowerAssert.explanation`, with either `null` in the case of the original function, or the additional parameter
in the case of the synthetic copy.

For example, given the following function declaration:

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean) {
    if (!condition) {
        val explanation = PowerAssert.explanation
        // ...
    }
}
```

Transformation by Power-Assert will result in two functions: the original and the synthetic copy.

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean) {
    if (!condition) {
        val explanation = null // PowerAssert.explanation replaced with null.
        // ...
    }
}

// @PowerAssert annotation removed.
@JvmSynthetic // Explicitly added.
// All other annotations copied from the original function.
fun `powerAssert$powerassert`(condition: Boolean, `$explanation`: () -> CallExplanation) {
    if (!condition) {
        val explanation = `$explanation`.invoke() // PowerAssert.explanation replaced with parameter.
        // ...
    }
}
```

## Function Calls

If a function is annotated with `@PowerAssert`, and has itself been transformed by the compiler plugin, this will result
in any call to said function being automatically transformed by the compiler plugin to include a `CallExplanation`
parameter.

For example:

```kotlin
powerAssert(mascot.name == "Kodee")
```

Will result in:

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = "Kodee"
val tmp4 = tmp2 == tmp3
`powerAssert$powerassert`(tmp4, { CallExplanation(...) })
```

## String Message Calls

The combination of introduced classes and functions allows improving the behavior of the existing string message style
Power-Assert. Instead of generating the diagram at compile-time, a `CallExplanation` can be constructed, and
`toDefaultMessage()` used to generate the diagram at runtime. For example, the original example can be transformed into
the following.

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = "Kodee"
val tmp4 = tmp2 == tmp3
assert(tmp3, { CallExplanation(...).toDefaultMessage() })
```

This means that existing users of Power-Assert will see an improvement to diagram rendering even if the called function
is not annotated with `@PowerAssert`.

# Advanced

## Runtime Dependency

When the Gradle plugin for Power-Assert is applied to a project, the Power-Assert runtime library [will automatically be
added as an `implementation` dependency](https://youtrack.jetbrains.com/issue/KT-85250) to all source sets that
Power-Assert is enabled for.

If a library wants to support Power-Assert but doesn't want to include the runtime library transitively, automatic
adding of the runtime library dependency can be disabled by setting the Gradle `powerAssert.addRuntimeDependency`
property to `false`. This would allow libraries to add the runtime library as a `compileOnly` dependency so it is not
included transitively.

```kotlin
plugins {
    kotlin("jvm") version "2.4.0-Beta2"
    kotlin("plugin.power-assert") version "2.4.0-Beta2"
}

dependencies {
    // (1) Explicitly add the runtime dependency as a compile-only dependency.
    compileOnly(kotlin("power-assert-runtime"))
}

powerAssert {
    // (2) Don't automatically add the runtime dependency.
    addRuntimeDependency = false
}
```

If a library decides to not include the Power-Assert runtime library transitively, it should be careful of where
functions annotated with `@PowerAssert` are used. While the `PowerAssert.explanation` property call is replaced with
`null`, references to Power-Assert related classes are not removed. This could lead to errors at runtime or during
linking. For example, on the JVM, this could lead to `NoClassDefFoundError` exceptions at runtime.

## Backwards Compatibility

As a whole, the API for the Power-Assert runtime library is considered unstable. However, in most cases, classes are
considered stable for use but unstable for implementation. We'll be doing our best to keep the API stable, but as we
start to support more use cases, things may need to change in incompatible ways. We expect most new use cases will be
supported through additional subclasses of `Explanation` and `Expression`.

Offset values within the `Explanation` and `Expression` classes are not guaranteed to be stable across different
versions of the compiler plugin. While the semantics of the values will not change, we may make adjustments to the
values at any time to render better Power-Assert style diagrams.

## Security

Given the nature of the Power-Assert transformation, it is possible for a library to gain access to source code and
expressions not explicitly provided by the user. This provides a potential security risk that must be carefully
considered by users of the Power-Assert compiler plugin. However, given that Power-Assert is a compiler plugin that must
be explicitly added by the user, and the fact that it is not enabled in `main` source sets by default, this sufficiently
reduces the risk of accidental exposure.

As a user of Power-Assert, it is important to be aware of all call sites that are transformed by the compiler plugin.
And when a new version of a library is available, consider how this might change what call sites are transformed.
We're working on ways to make this transformation more obvious at the call site, but we unfortunately have nothing to
share yet.

## Interactions

The `@PowerAssert` annotation may be applied to all sorts of functions. This means that the compiler plugin must be able
to interact correctly with existing Kotlin features, including default arguments, `inline`, `abstract`/`open`,
`expect`/`actual`, etc.  

Some combinations work without any special configuration: like default arguments, `inline`, or `suspend`. In the case of
`abstract`/`open` functions, the `@PowerAssert` annotation must be applied to the base function declaration of a
hierarchy and is automatically inherited by all `override` functions. It may be repeated on `override` functions if
desired for, but it is not required. For `expect`/`actual` functions, the annotation must be present on both functions.

When combining Power-Assert with other compiler plugins, the behavior is not well-defined. For example, a `@Composable`
and `@PowerAssert` function is possible if the compiler plugins run in the same order at both the function declaration
and the function call-site. However, such a function does not make logical sense, as it violates many `@Composable`
guarantees by providing access to non-parameter expressions. As such, at this time, it is strongly discouraged to have
both `@PowerAssert` and `@Composable` on the same function. Combinations of Power-Assert with other compiler plugins
should follow similarly and avoid combining behaviors on the same function.

# Use Cases

We're excited to share that everything you've read up to this point is ready for experimentation in the upcoming Kotlin
2.4.0-Beta2 release! Some things may change before the final 2.4.0 release, but if you want to try this new version of
Power-Assert today, all you need to do is add the new runtime library dependency to your project.

```kotlin
plugins {
    kotlin("jvm") version "2.4.0-Beta2"
    kotlin("plugin.power-assert") version "2.4.0-Beta2"
}

dependencies {
    // (1) For now, the Gradle plugin does not automatically add the runtime library.
    implementation(kotlin("power-assert-runtime"))
}

powerAssert {
    // (2) Gradle configuration to include all source sets by default.
    includedSourceSets = provider { kotlin.sourceSets.map { it.name } }
}
```

Adding the above to your `build.gradle.kts` file will enable the Power-Assert compiler plugin for all source sets. The
important part is the addition of the runtime library, so you can experiment with the new `PowerAssert` annotation and
`CallExplanation`. The presence of the runtime library will also enable the `CallExplanation(...).toDefaultMessage()`
style messages for existing function calls, so you can experience the improved diagrams. Eventually, the runtime library
will be [added automatically](#runtime-dependency), but this behavior is
[not yet supported](https://youtrack.jetbrains.com/issue/KT-85250).

If you want to experiment with Power-Assert before 2.4.0-Beta2 is released, check out 
this [Github project][power-assert-examples], which is built against a development version of Kotlin.

Here are some examples of what you could build!

## IntelliJ Integration

Tired of not getting great IntelliJ integration with Power-Assert? In this example, we can use the presence of failed
`EqualityExpression`s to construct the necessary assertion errors so "click to see difference" is shown in IntelliJ!

<details>
<summary>Definition of `powerAssert` function.</summary>

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null) {
    contract { returns() implies condition } // Support smart-casts from the condition!

    if (!condition) {
        // If Power-Assert is not applied, fallback to using a simple message.
        val explanation = PowerAssert.explanation
            ?: throw AssertionFailedError(message)

        // Find all equality expressions that failed. 
        val equalityErrors = buildList {
            for (expression in explanation.expressions) {
                if (expression is EqualityExpression && expression.value == false) {
                    add(expression)
                }
            }
        }

        // Provide an OpenTest4J-compatible error message. 
        val failureMessage = buildString {
            // OpenTest4J likes to trim messages. Use zero-width space (U+200B) characters to preserve newlines.
            appendLine(message?.takeIf { it.isNotBlank() } ?: "\u200B")
            append(explanation.toDefaultMessage())
            append("\u200B")
        }

        // Based on the number of failed equality expressions, throw the appropriate error.
        throw when (equalityErrors.size) {
            0 -> AssertionFailedError(failureMessage)

            1 -> {
                val error = equalityErrors[0]
                AssertionFailedError(failureMessage, error.rhs, error.lhs)
            }

            else -> {
                MultipleFailuresError(
                    failureMessage,
                    equalityErrors.map { EqualityError(it) },
                )
            }
        }
    }
}

private class EqualityError(
    expression: EqualityExpression
) : AssertionFailedError(
    "Expected <${expression.rhs}>, actual <${expression.lhs}>",
    expression.rhs, expression.lhs,
) {
    override fun fillInStackTrace(): Throwable = this // Stack trace is unnecessary.
}
```

</details>

<details>
<summary>Example use of `powerAssert` function.</summary>

```kotlin
class JunitTests {
    @Test
    fun simpleNone() {
        val mascot: Any? = "Kodee"
        powerAssert(mascot is Int)
    }

    @Test
    fun simpleSingle() {
        val mascot: Any? = "Kodee"
        powerAssert(mascot == "Kodee" && mascot == "Duke")
    }

    @Test
    fun simpleMultiple() {
        val mascot: Any? = "Kodee"
        powerAssert(mascot == "Duke" || mascot == "Ferris")
    }
}
```

</details>

<details>
<summary>Example output.</summary>

```text
powerAssert(mascot is Int)
            |      |
            Kodee  false


powerAssert(mascot == "Kodee" && mascot == "Duke")
            |      |             |      |
            |      true          |      false
            "Kodee"              "Kodee"

Expected :Duke
Actual   :Kodee
<Click to see difference>


powerAssert(mascot == "Duke" || mascot == "Ferris")
            |      |            |      |
            |      false        |      false
            "Kodee"             "Kodee"
            
Expected <Duke>, actual <Kodee>
Expected :Duke
Actual   :Kodee
<Click to see difference>

Expected <Ferris>, actual <Kodee>
Expected :Ferris
Actual   :Kodee
<Click to see difference>
```

</details>

## Soft/Fluent Assertions

Prefer a fluent or soft-assert style of writing assertions? Power-Assert can help you achieve this as well! By combining
multiple `CallExplanation`s into a single explanation, we can write a DSL which provides information on both the subject
and the asserted conditions.

<details>
<summary>Definition of `assertThat`, `hasLength`, and `startsWith` functions.</summary>

```kotlin
@PowerAssert.Ignore // Always exclude the assertion scope from Power-Assert explanations. 
interface AssertScope<out T> {
    val subject: T
    fun collectFailure(message: String?, explanation: Explanation?)
}

@PowerAssert
fun <T> assertThat(subject: T, block: AssertScope<T>.() -> Unit) {
    val primary = PowerAssert.explanation ?: error("power-assert compiler plugin is required")
    
    val failures = mutableListOf<Pair<String?, List<Expression>>>()
    val scope = object : AssertScope<T> {
        override val subject: T get() = subject
        override fun collectFailure(message: String?, explanation: Explanation?) {
            // Adjust the offset of the expressions to be relative to the primary explanation.
            val adjusted = explanation?.expressions?.map { it.copy(explanation.offset - primary.offset) }.orEmpty()
            failures.add(message to adjusted)
        }
    }

    scope.block()
    if (failures.isNotEmpty()) {
        val expressions = failures.flatMap { it.second }
            // Attempt to reduce duplication of similar expressions by comparing source code and value.
            .distinctBy { Pair(primary.source.substring(it.startOffset, it.endOffset), it.value) }

        // Create a fake argument to combine failures with primary explanation.
        val synthetic = Argument(-1, -1, Argument.Kind.VALUE, expressions)
        val combined = CallExplanation(primary.offset, primary.source, primary.arguments + synthetic)

        // Craft a message that includes all failure messages and diagram of entire assertion scope.
        throw AssertionError(buildString {
            appendLine("Assertion failed:")
            for ((msg, _) in failures) {
                if (msg != null) appendLine(" * $msg")
            }
            append(combined.toDefaultMessage())
        })
    }
}

@PowerAssert
fun AssertScope<String>.hasLength(length: Int) {
    // Adding custom assertions is as easy as writing the condition and failure message!
    if (subject.length != length) {
        collectFailure("String \"${subject}\" does not have length '${length}'.", PowerAssert.explanation)
    }
}

@PowerAssert
fun AssertScope<String>.startsWith(prefix: String, ignoreCase: Boolean = false) {
    if (!subject.startsWith(prefix, ignoreCase)) {
        collectFailure(
            "String \"${subject}\" does not start with \"${prefix}\"${if (ignoreCase) " (ignoring case)" else ""}.",
            PowerAssert.explanation,
        )
    }
}
```

</details>

<details>
<summary>Example use of `assertThat`, `hasLength`, and `startsWith` functions.</summary>

```kotlin
class FluentTests {
    @Test
    fun simpleTest() {
        val subject = "Unknown"
        assertThat(subject) {
            hasLength("Kodee".length)
            startsWith("Kodee".substring(0, 1))
        }
    }
}
```

</details>

<details>
<summary>Example output.</summary>

```text
Assertion failed:
 * String "Unknown" does not have length '5'.
 * String "Unknown" does not start with "K".
assertThat(subject) {
           |
           "Unknown"

    hasLength("Kodee".length)
                      |
                      5

    startsWith("Kodee".substring(0, 1))
                       |
                       "K"

}
```

</details>

## Custom Diagrams

Why stop at assertions? Power-Assert can be used to provide expression information for all sorts of use cases. With a
little bit of creativity, we can create a custom Power-Assert based pretty-print!

<details>
<summary>Definition of `pprintln` function.</summary>

```kotlin
@PowerAssert
fun pprintln(message: String) {
    val explanation = PowerAssert.explanation
    if (explanation == null) {
        println(message)
        return
    }

    println(buildString {
        val source = explanation.source
        var start = 0
        for (expression in explanation.expressions) {
            val prefix = source.getOrNull(expression.startOffset - 1)
            val suffix = source.getOrNull(expression.endOffset)
            if (prefix == '$') {
                append(source.substring(start, expression.startOffset))
                append("{") // Add surrounding braces.
                append(source.substring(expression.startOffset, expression.endOffset))
                append(" = ")
                append(expression.value)
                append("}")
                start = expression.endOffset
            } else if (prefix == '{' && suffix == '}') {
                append(source.substring(start, expression.startOffset))
                append(source.substring(expression.startOffset, expression.endOffset))
                append(" = ")
                append(expression.value)
                start = expression.endOffset
            }
        }
        append(source.substring(start))
    }.trimIndent())
}
```

</details>

<details>
<summary>Example use of `pprintln` function.</summary>

```kotlin
fun main() {
    val name = "World"
    pprintln("""
        Hello, $name!
        My name is ${Random.nextInt()}.
    """.trimIndent())
}
```
</details>

<details>
<summary>Example output.</summary>

```text
pprintln("""
    Hello, ${name = World}!
    My name is ${Random.nextInt() = -780043044}.
""".trimIndent())
```

</details>

# Feedback

We look forward to your feedback!

There are a few questions we expect, so here are some additional details in those areas to help guide your feedback.

## "Why are there so few interesting `Expression` subclasses?"

The `LiteralExpression` class exists so that a `CallExplanation` can always be generated for a function call, yet
values which are explicit in the source code can be excluded from the default Power-Assert diagram.

The `EqualityExpression` class was specifically designed with IntelliJ "click to see difference" functionality in mind.
This expression can be used to provide an expected and actual value for a variety of exceptions that IntelliJ supports.

In the future, more `Expression` subclasses may be added to cover more use cases. For example, a
`StringTemplateExpression` could be introduced to help describe String concatenation and each of the arguments provided.
If you have additional use case ideas ***that align with the [goals](#goals) and [non-goals](#non-goals)***, we would
love to hear them!

## "Why the `Explanation` base class?"

We are exploring additional subclasses of `Explanation`, including explanations of local variables. The initializer of a
local variable can be explained just like function call arguments are explained. This would allow including the
explanation of local variables along with a `CallExplanation`, enriching the data about the call-site.

There are a number of concerns with this idea:

1. ***Security*** – Even more information about the call-site is sent to the called function, which increases the security
   risk of leaking sensitive information.
2. ***Performance*** – Excessive call-site transformation may result in slower code, which may not be needed if the
   explanation is never used.
3. ***Syntax*** – Determining what local variables are transformed and how their information is propagated should be
   controlled by the code author without much additional burden.

We will continue to explore the potential of this idea.

## "Can you add XYZ to `Explanation`/`Expression`?"

We'd love to hear your ideas! But the scope of additional features for Kotlin 2.4.0 is extremely limited. However, we
want to hear your feedback so we can adjust the current API to better support your use cases in the future. While we may
not be able to add your idea now, we could adjust the API to better support adding it in the future!

## "Why an expression List and not a tree?"

The design for `Expression` means that an expression like `1 + 2 + 3` will be represented as a ***list*** of expressions
and not a ***tree***. This is an intentional design decision. A list greatly simplifies the data structure needed to
represent complex expressions, while a tree brings along many additional design decisions on how exactly some
expressions should be represented.

This decision is also heavily influenced by the current implementation of the Power-Assert compiler plugin. During the
call-site transformation, the compiler plugin does not maintain the tree structure of the expression and only stores a
list of the temporary variables used in the expression. To support a tree-based representation, significant parts of the
compiler plugin would need to be rewritten.

It is important to note that these designs are not mutually exclusive! In the future, as we explore additional use
cases, we may switch to represent expressions as a tree. We should be able to migrate to a tree representation without 
breaking existing code, as the existing list properties could be converted to a DFS walk of the expression tree.

[power-assert-runtime]: https://github.com/JetBrains/kotlin/tree/master/plugins/power-assert/power-assert-runtime/src/commonMain/kotlin/kotlin/powerassert
[power-assert-examples]: https://github.com/bnorm/power-assert-examples

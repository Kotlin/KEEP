# Power-Assert Explanation

* **Type**: Design proposal
* **Author**: Brian Norman
* **Contributors**: Mikhail Zarechenskii, Marat Akhin
* **Status**: Experimental in 2.4
* **Prototype**: Implemented
* **Discussion**: TODO
* **Status**: Public Discussion
* **YouTrack Issue**:
  [KT-66807](https://youtrack.jetbrains.com/issue/KT-66807),
  [KT-66806](https://youtrack.jetbrains.com/issue/KT-66806),
  [KT-66808](https://youtrack.jetbrains.com/issue/KT-66808)

# Abstract

The Power-Assert compiler-plugin allows transforming function calls to include detailed information about the call-site.
This information is currently in the form of a compile-time generated String passed as an argument to the function.
We will introduce a new annotation to automatically trigger transformation of function calls, new data structures to
represent call-site information, and a way to access call-site information from an annotated function.

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
  * [Existing Behavior](#existing-behavior)
* [API Overview](#api-overview)
  * [`@PowerAssert`](#powerassert)
  * [`CallExplanation`](#callexplanation)
  * [`Explanation`](#explanation)
  * [`Expression`](#expression)
  * [Functions](#functions)
  * [Source and Offsets](#source-and-offsets)
* [Transformations](#transformations)
  * [Function Declarations](#function-declarations)
  * [Function Calls](#function-calls)
  * [String Message Calls](#string-message-calls)
* [Use Cases](#use-cases)
  * [JUnit Integration](#junit-integration)
  * [Soft Assertions](#soft-assertions)
* [Advanced](#advanced)
  * [Runtime Dependency](#runtime-dependency)
  * [Backwards Compatibility](#backwards-compatibility)
  * [Security](#security)
  * [Interactions](#interactions)
* [Feedback](#feedback)
  * ["Why is there only one interesting `Expression` subclass?"](#why-is-there-only-one-interesting-expression-subclass)
  * ["Why the `Explanation` base class?"](#why-the-explanation-base-class)
  * ["Why an expression List and not a tree?"](#why-an-expression-list-and-not-a-tree)
<!-- TOC -->

# Introduction

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
assert(tmp3, { "assert(mascot.name == "Kodee")\n       |      |    |\n       |      |    $tmp3\n       |      $tmp2\n       $tmp1\n" })
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
or `() -> String` as the **last argument**. Functions like `kotlin.test.assertTrue` and `kotlin.test.assertEquals` are
good candidates for Power-Assert transformation.

Unfortunately, specifying these functions is required for every compilation. And there is no way for an assertion
library to specify which functions support Power-Assert to the compiler-plugin for automatic transformation. This means
that every Gradle project must configure these functions.

## Key Problems

From this overview, we can extract three key problems with Power-Assert:

1. Verbose configuration that complicates onboarding.
2. Brittle function parameter requirements that confuse adopters.
3. Static diagram generation that limits tooling integration.

## Goals

In this proposal, we will outline changes which attempt to tackle all of these problems.

1. Power-Assert capable functions should be discoverable rather than needing to be configured. This avoids complex build
configuration needed for the compiler-plugin.
2. Power-Assert capable functions should not rely on argument convention but transformation by the compiler-plugin
instead. This removes confusing function parameter requirements and enables easier integration for adopting libraries.
3. Power-Assert capable functions should be provided with detailed call-site information. This improves diagram render
by making it more dynamic and enables better tooling integration.

## Non-Goals

While these goals are important, there are also directions we explicitly do not intend to pursue with Power-Assert.

1. Power-Assert is ***not*** a macro or dynamic code execution system. The compiler-plugin needs to remain simple and
focused.
2. Power-Assert is ***not*** a replacement for an assertion library. The compiler-plugin needs to help enhance existing
assertion libraries.

[//]: # (TODO add a third thing here to maintain the rule of 3)

# Proposal

Introduce a set of data structures which can represent call-site information for function calls. Also introduce a new
annotation to mark function declarations that support Power-Assert transformation.

Here's a quick example:

```kotlin
import kotlinx.powerassert.PowerAssert
import kotlinx.powerassert.CallExplanation
import kotlinx.powerassert.toDefaultMessage

@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null) {
    if (!condition) {
        val explanation: CallExplanation? = PowerAssert.explanation // Intrinsic property provided by compiler-plugin.
        throw AssertionError(buildString {
            append("Assertion failed:")
            if (message != null) append(" ").append(message)
            if (explanation != null) appendLine().appendLine(explanation.toDefaultMessage())
        })
    }
}
```

These annotations and data structures help fix the key problems with Power-Assert!
1. The `@PowerAssert` annotation makes functions discoverable by the compiler-plugin at call-sites.
2. The `PowerAssert.explanation` intrinsic property provided by the compiler-plugin means no more confusing function
parameter requirements.
3. The `CallExplanation` data structure can help build a dynamic diagram message and adjust assertion exception.

## Call-Site

A keen observer may notice that none of the above impacts the call-site of a `@PowerAssert` annotated function. The
Power-Assert transformation is quite invisible to the end user of such a function. And since the annotation helps
automatically discover supported functions, this means the current configuration for fully-qualified function names is
no longer required!

This also means that if an assertion library were to adopt use of `@PowerAssert`, support for providing a diagram of the
call-site would be seamless and transparent to the end user. All the end user would need to do is apply the
compiler-plugin to their project. If you are the user of such a project, you should reach out to the author and
encourage them to provide feedback on this KEEP!

## Declaration

A keen observer may also notice that this means the Power-Assert compiler-plugin now needs to be applied to function
declarations as well. To provide the `CallExplanation` from the call-site to the `@PowerAssert` annotated function, the
Power-Assert compiler-plugin must generate a synthetic copy of the annotated function which has an additional parameter
of type `() -> CallExplanation`. This allows Power-Assert to transform the call-site and provide this data structure.

Details on this `CallExplanation` [transformation by the Power-Assert compiler-plugin](#function-declarations) will be
explored later in this proposal.

## Existing Behavior

If you are worried that the ability for the Power-Assert compiler-plugin to generate a compile-time `String` diagram is
being removed, worry not! The compiler-plugin will continue to accept fully-qualified function names as configuration
for functions which are not annotated with `@PowerAssert`. In fact, this behavior is being improved as well: by using
`CallExplanation` to generate a diagram at runtime, we can improve the diagram rendering dramatically by changing the
layout of the diagram based on the results of each intermediate expression.

Details on this new `String` diagram [transformation by the Power-Assert compiler-plugin](#string-message-calls) will be
explored later in this proposal.

# API Overview

This section will give a _**brief overview**_ of some classes from the new Power-Assert runtime library. We encourage
those who want a more in-depth look at the classes to read the [documentation in the source code]().

[//]: # (TODO include link to runtime source code)

## `@PowerAssert`

A new annotation will be introduced to mark functions which support being
[transformed by the Power-Assert compiler-plugin](#transformation). This annotation also provides access to a compiler-plugin
intrinsic `CallExplanation` property which can be used to access call-site information.

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class PowerAssert {
    companion object {
        val explanation: CallExplanation? // Implemented as compiler-plugin intrinsic.
    }
}
```

If the compiler-plugin is not applied when compiling access of the `explanation` property, access will result in a
runtime error. The compiler-plugin will also limit property access to within functions annotated with `@PowerAssert` at
compile-time.

The `explanation` property will return `null` in cases when a `@PowerAssert` annotated function is called without
call-site information, including:
 * The call-site was compiled without the compiler-plugin applied.
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
        get() = arguments.sortedBy { it.startOffset }.flatMap { it.expressions }

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

All arguments to the function call are provided as a `List` in **parameter** order. Arguments of different kinds are
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

This order **will not change** no matter how the function is called. Even if argument order is changed with named
parameters, the `arguments` list remains in the same order. If an argument to the function is omitted due to a default
value or is implicitly provided (as is common with context parameters) then the corresponding argument in the
`CallExplanation` will be `null`.

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

There are currently only two implementations of `Expression`: `ValueExpression` and `EqualityExpression`. For more
information on these classes, see their [documentation in the source code]().

[//]: # (TODO include link to runtime source code)

## Functions

A few top-level functions will be included as well, to provide Power-Assert style rendering of an `Explanation`. These
functions will be used to replace the current compile-time generated `String` for overload style Power-Assert
transformation.

```kotlin
public fun Explanation.toDefaultMessage(
    render: (Expression) -> String? /* = <default> */,
): String = toDiagram(render)

public fun Explanation.toDiagram(
    render: (Expression) -> String? /* = <default> */,
): String
```

While any project can use these functions to generate a Power-Assert style message for exceptions, it is recommended
that assertion libraries implement their own rendering logic. This avoids needing to wait for the compiler-plugin to be
updated for improvements and will also help match any existing reporting style.

## Source and Offsets

There are a lot of "offsets" in the Power-Assert runtime library classes, and it may not be clear what they mean.
* `Explanation.offset` is the character offset of `Explanation.source` within the containing file.
* `Expression.startOffset`, `Expression.endOffset`, and `Expression.displayOffset` are character offsets
**within the explanation** for the expression source.

Thus, given an explanation and an expression, a user of these classes can get the source for a particular expression by
taking a substring of `source` starting at `startOffset` and ending at `endOffset`. The `displayOffset` will always be
within the range of `startOffset` and `endOffset`, and is useful for indicating the location of the expression's
`value` in a Power-Assert style diagram.

The `source` provided by an `Explanation` will always be a **block** of text, and contain all leading
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
        s      d  e - start, display, and end offsets for `mastcot.name`
        |      |  |
        |      |  |    s,d   e - start, display, and end offsets for `"Kodee"`
        |      |  |    |     |
        mascot.name == "Kodee"
        |    |      |        |
        s    |      d        e - start, display, and end offsets for `mascot.name == "Kodee"`
        |    |
        s,d  e - start, display, and end offsets for `mascot`
    )
```

Putting everything together for the above example - assuming the sample is the complete file - a `CallExplanation` like
the following will be generated:

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = "Kodee"
val tmp4 = tmp2 == tmp3
CallExplanation(
    offset = 13,
    source = "                          powerAssert(\n        mascot.name == \"Kodee\"\n    )",
    arguments = listOf(
        Argument(
            startOffset = 48,
            endOffset = 69,
            kind = Kind.VALUE,
            expressions = listOf(
                ValueExpression(
                    startOffset = 47,
                    endOffset = 53,
                    displayOffset = 47,
                    value = tmp1,
                ),
                ValueExpression(
                    startOffset = 47,
                    endOffset = 58,
                    displayOffset = 54,
                    value = tmp2,
                ),
                ValueExpression(
                    startOffset = 62,
                    endOffset = 69,
                    displayOffset = 62,
                    value = tmp3,
                ),
                ValueExpression(
                    startOffset = 48,
                    endOffset = 69,
                    displayOffset = 59,
                    value = tmp4,
                ),
            ),
        ),
    ),
)
```

# Transformations

There are a number of transformations now performed by Power-Assert. Function declarations and call-sites for functions
with and without the `@PowerAssert` annotation are transformed in different ways.

## Function Declarations

When `@PowerAssert` is added to a function, the compiler-plugin will generate a synthetic copy of the function. This
synthetic copy adds a parameter of type `() -> CallExplanation`. Both functions are then transformed to replace any
calls to `PowerAssert.explanation`, with either `null` in the case of the original function, or the additional parameter
in the case of the synthetic copy.

For example, the following function declaration:

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean) {
    if (!condition) {
        val explanation = PowerAssert.explanation
        // ...
    }
}
```

Will result in the following two functions, the original and the synthetic copy:

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

This means that the original function can be used without a runtime dependency on any Power-Assert related classes!

## Function Calls

If a function is annotated with `@PowerAssert`, and has itself been transformed by the compiler-plugin, this will result
in any call to said function being automatically transformed by the compiler-plugin to include a `CallExplanation`
parameter.

For example:

```kotlin
powerAssert(mascot.name == "Kodee")
```

Will result in:

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = tmp2 == "Kodee"
`powerAssert$powerassert`(tmp3, { CallExplanation(...) })
```

## String Message Calls

The combination of introduced classes and functions allows improving the behavior of the existing string message style
Power-Assert. Instead of generating the diagram at compile-time, a `CallExplanation` can be constructed, and
`toDefaultMessage()` used to generate the diagram at runtime. For example, the original example can be transformed into
the following.

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = tmp2 == "Kodee"
assert(tmp3, { CallExplanation(...).toDefaultMessage() })
```

This means that existing users of Power-Assert will see an improvement to diagram rendering even if the called function
is not annotated with `@PowerAssert`.

# Use Cases

[//]: # (TODO provide a description for this section?)

## JUnit Integration

[//]: # (TODO provide an example description)
[//]: # (TODO document example)

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null) {
    contract { returns() implies condition }
    if (!condition) {
        val explanation = PowerAssert.explanation
            ?: throw AssertionFailedError(message)

        val equalityErrors = buildList {
            for (expression in explanation.expressions) {
                if (expression is EqualityExpression && expression.value == false) {
                    add(expression)
                }
            }
        }

        val failureMessage = buildString {
            // OpenTest4J likes to trim messages. Use zero-width space (U+200B) characters to preserve newlines.
            appendLine(message?.takeIf { it.isNotBlank() } ?: "\u200B")
            append(explanation.toDefaultMessage())
            append("\u200B")
        }

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

## Soft Assertions

[//]: # (TODO provide an example description)
[//]: # (TODO document example)

```kotlin
@PowerAssert.Ignore
interface AssertScope<T> {
    val subject: T
    fun collectFailure(message: String?, explanation: Explanation?)
}

@PowerAssert
fun <T> assertThat(subject: T, block: AssertScope<T>.() -> Unit) {
    val primary = PowerAssert.explanation ?: error("power-assert compiler-plugin is required")
    val failures = mutableListOf<Pair<String?, List<Expression>>>()
    object : AssertScope<T> {
        override val subject: T get() = subject
        override fun collectFailure(message: String?, explanation: Explanation?) {
            val adjusted = explanation?.expressions?.map { it.copy(explanation.offset - primary.offset) }.orEmpty()
            failures.add(message to adjusted)
        }
    }.block()

    if (failures.isNotEmpty()) {
        val expressions = failures
            .flatMap { it.second }
            // attempt to reduce duplication of similar expressions.
            .distinctBy { Pair(primary.source.substring(it.startOffset, it.endOffset), it.value) }
        val synthetic = Argument(-1, -1, Argument.Kind.VALUE, expressions)
        val combined = CallExplanation(primary.offset, primary.source, primary.arguments + synthetic)
        val message = buildString {
            appendLine("Assertion failed:")
            for ((msg, _) in failures) {
                if (msg != null) appendLine(" * $msg")
            }
            appendLine(combined.toDefaultMessage())
        }
        throw AssertionError(message)
    }
}

@PowerAssert
fun AssertScope<String>.hasLength(length: Int) {
    if (subject?.length != length) {
        collectFailure("String `${subject}` does not have length `${length}`.", PowerAssert.explanation)
    }
}

@PowerAssert
fun AssertScope<String>.startsWith(prefix: String, ignoreCase: Boolean = false) {
    if (subject?.startsWith(prefix, ignoreCase) != true) {
        collectFailure(
            "String `${subject}` does not start with `${prefix}`${if (ignoreCase) " (ignoring case)" else ""}.",
            PowerAssert.explanation,
        )
    }
}
```

# Advanced

## Runtime Dependency

[//]: # (
TODO details about runtime dependency in Gradle
 * compileOnly for non-call-site transformed source sets?
 * implementation for call-site transformed source sets?
)

## Backwards Compatibility

[//]: # (
TODO add a backwards compatibility section
 * what gaurentees are provided today?
 * be explicit about what might change in the future?
)

## Security

[//]: # (
TODO add a security section
)

## Interactions

[//]: # (
TODO is there an interaction section
 * Compose
 * inline
 * default arguments
 * @JvmName
 * open and abstract
 * expect and actual
)

# Feedback

We look forward to your feedback!

There are a few expected questions, so here are some additional details in those areas to help guide your feedback. 

## "Why is there only one interesting `Expression` subclass?"

The `EqualityExpression` class was specifically designed with IntelliJ "click to see difference" functionality in mind.
This expression can be used to provide an expected and actual value for a variety of exceptions that IntelliJ supports.

In the future, more `Expression` subclasses may be added to cover more use cases. For example, a
`StringTemplateExpression` could be introduced to help describe String concatenation and each of the arguments provided.
If you have additional use case ideas that do not violate a non-goals, we would love to hear them!

## "Why the `Explanation` base class?"

We are exploring additional subclasses of `Explanation`, including explanations of local variables. The initializer of a
local variable can be explained just like function call arguments are explained. This would allow including the
explanation of local variables along with a `CallExplanation`, enriching the data about the call-site.

There are a number of concerns with this idea:

1. **Security** – Even more information about the call-site is sent to the called function, which increases the security
   risk of leaking sensitive information.
2. **Performance** – Excessive call-site transformation may result in slower code, which may not be needed if the
   explanation is never used.
3. **Syntax** – Determining what local variables are transformed and how their information is propagated should be
   determined by the code author without much additional burden.

We will continue to explore the potential of this idea.

And again, if you have additional use case ideas that do not violate a non-goals, we would love to hear them!

## "Why an expression List and not a tree?"

The design for `Expression` means that a complex expression like `1 + 2 + 3` will be represented as a _**list**_ of
expressions and not a _**tree**_. This is an intentional design decision. A list greatly simplifies the data structure
needed to represent complex expressions, while a tree brings along many additional design decisions on how exactly some
expressions should be represented.

This decision is also heavily influenced by the current implementation of the Power-Assert compiler-plugin. During the
call-site transformation, the compiler-plugin does not maintain the tree structure of the expression and only stores a
list of the temporary variables used in the expression. To support a tree-based representation, significant parts of the
compiler-plugin would need to be rewritten.

It is important to note that these designs are not mutually exclusive! In the future, as we explore additional use
cases, we may switch to represent expressions as a tree. We should be able to migrate to a tree representation without 
breaking existing code, as the existing list properties could be converted to a DFS walk of the tree.

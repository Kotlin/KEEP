# Power-Assert Explanation

* **Type**: Design proposal
* **Author**: Brian Norman
* **Contributors**: Mikhail Zarechenskii, Marat Akhin
* **Status**: Experimental expected for 2.4
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

* [Motivation](#motivation)
    * [Background](#background)
    * [Key Problems](#key-problems)
* [Proposal](#proposal)
    * [Annotations](#annotations)
        * [`PowerAssert`](#powerassert)
        * [`PowerAssert.Ignore`](#powerassertignore)
    * [Classes](#classes)
        * [`Explanation`](#explanation)
        * [`CallExplanation`](#callexplanation)
        * [`Expression`](#expression)
        * [`EqualityExpression`](#equalityexpression)
    * [Functions](#functions)
    * [Transformation](#transformation)
        * [Declaration](#declaration)
        * [Calls](#calls)
        * [Overload Calls](#overload-calls)
* [Future](#future)
    * [Expression Types](#expression-types)
    * [Explanation Types](#explanation-types)

# Motivation

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
assert(tmp3, { "assert(mascot.name == \"Kodee\")\n[...generated diagram...]" })
```

The diagram is generated at compile time and produces an output like the following.

```text
assert(mascot.name == "Kodee")
       |      |    |
       |      |    false
       |      Unknown
       Mascot(name=Unknown)
```

This transformation is not limited to only `kotlin.assert`, but may be applied to any function that can takes a `String`
or `() -> String` as the **last argument**. Functions like `kotlin.test.assertTrue` and `kotlin.test.assertEquals` are
good candidates for Power-Assert transformation.

Unfortunately, specifying these functions is required for every compilation. And there is no way for an assertion
library to specify which functions support Power-Assert to the compiler-plugin for automatic transformation. This means
that every Gradle project must configure these functions.

## Key Problems

From this overview, we can extract three key problems with Power-Assert:

1. Brittle function requirements that confuse adopters.
2. Verbose configuration that complicates onboarding.
3. Static diagram generation that limits tooling integration.

In this proposal, we will outline changes which attempt to tackle all of these problems.

# Proposal

Introduce a set of data structures which can represent call-site information for function calls. Also introduce a new
annotation to mark function declarations that support Power-Assert transformation.

Here's a quick example:

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null) {
    if (!condition) {
        val explanation: CallExplanation? = PowerAssert.explanation
        throw AssertionError(buildString {
            append("Assertion failed:")
            if (message != null) append(" ").append(message)
            if (explanation != null) appendLine().appendLine(explanation.toDefaultMessage())
        })
    }
}
```

## Annotations

### `PowerAssert`

A new annotation, `PowerAssert`, will be introduced to mark functions which support being
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
 * The call-site was compiled without the compiler-plugin applied,
 * When called from non-Kotlin code, for example, Java,
 * When called via reflection or method reference.

### `PowerAssert.Ignore`

Another annotation will be introduced to mark parameters which should not be included in call-site information. This
could be for any number of reasons, and it helps the compiler-plugin avoid doing work that is unnecessary.

```kotlin
annotation class PowerAssert {
    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class Ignore
}
```

For example,

```kotlin
@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null)
``` 

## Classes

The two base classes used to describe call-site information for `@PowerAssert` annotated functions are `Explanation`
and `Expression`.

### `Expression`

An `Expression` represents a value calculated at runtime. This could be the result of a function call, variable access,
language operator, or anything else that results in a value.

```kotlin
public abstract class Expression internal constructor(
    public val startOffset: Int,
    public val endOffset: Int,
    public val displayOffset: Int,
    public val value: Any?,
) {
    public abstract fun copy(deltaOffset: Int): Expression
}
```

There is a default implementation for `Expression`, simply called `ValueExpression`.

```kotlin
public class ValueExpression(
    startOffset: Int,
    endOffset: Int,
    displayOffset: Int,
    value: Any?,
) : Expression(startOffset, endOffset, displayOffset, value) {
    override fun copy(deltaOffset: Int): ValueExpression {
        return ValueExpression(
            startOffset = startOffset + deltaOffset,
            endOffset = endOffset + deltaOffset,
            displayOffset = displayOffset + deltaOffset,
            value = value,
        )
    }
}
```

#### `EqualityExpression`

For now, the only subclass provided to describe a specific kind of expression is `EqualityExpression`. This can be used
to know if an expression like `1 == 2` was evaluated. The class not only provides the result of the evaluation, but also
the left-hand and right-hand side values of the expression. Libraries may use these values, for example, to calculate
diagnostic information about equalities that result in a `false` value.

```kotlin
public class EqualityExpression(
    startOffset: Int,
    endOffset: Int,
    displayOffset: Int,
    value: Any?,
    public val lhs: Any?,
    public val rhs: Any?,
) : Expression(startOffset, endOffset, displayOffset, value) {
    override fun copy(deltaOffset: Int): EqualityExpression {
        return EqualityExpression(
            startOffset = startOffset + deltaOffset,
            endOffset = endOffset + deltaOffset,
            displayOffset = displayOffset + deltaOffset,
            value = value,
            lhs = lhs,
            rhs = rhs,
        )
    }
}
```

Note that this is strictly _**equality**_ expressions. This expression will not describe identity expressions (`===`),
not-equal expressions (`!=`), nor not-identity expressions (`!==`).

### `Explanation`

An `Explanation` provides information about the source code and expressions evaluated within that code.

```kotlin
public abstract class Explanation internal constructor() {
    public abstract val offset: Int // Always starts at *column* 0 within the file.
    public abstract val source: String // The *block* of source code, redacted with whitespace.
    public abstract val expressions: List<Expression>
}
```

The `source` `String` provided by an `Explanation` will always be a **block** of text, and contain all leading
whitespace of the original code. Text not part of the source code range will be redacted with spaces. For example, an
explanation of the call to the `powerAssert` function:

```kotlin
fun test() {
    /* leading comment */ powerAssert(
        mascot.name == "Kodee"
    ) // trailing comment
}
```

Will have the following for a `source` block. Notice the preserved leading spaces and redacted comments.

```text
                          powerAssert(
        mascot.name == "Kodee"
    )
```

The `offset` of the `Explanation` is the offset within the **file** where the `source` text begins.

The `expressions` `List` of the `Explanation` represents **every** `Expression` contained within the explanation. These
expressions are always provided in evaluation order. So an explanation of `1 == 2` might contain 3 expressions in the
following order: `1`, `2`, and `false`.

[//]: # (TODO expression list will not contain constants, though)

#### `CallExplanation`

The `CallExplanation` class - as provided by `@PowerAssert` - describes each argument provided to a function call
individually, while providing the source code information for the entire call.

```kotlin
public class CallExplanation(
    override val offset: Int,
    override val source: String,
    public val arguments: List<Argument>,
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

All arguments to the function call are provided as a single `List` in **parameter** order. Arguments of different kinds
are always provided in the same order as the `Kind` enum.

For example, a call to the following `example` function:

```kotlin
class Dispatch {
    context(context1: Context1, context2: Context2)
    fun Extension.example(param1: Param1, param2: Param2)
}
```

The explanation will have six arguments specified in the following order:
1. An argument with `Kind.DISPATCH` for the `Dispatch` receiver.
2. An argument with `Kind.CONTEXT` for the `context1` context parameter.
3. An argument with `Kind.CONTEXT` for the `context2` context parameter.
4. An argument with `Kind.EXTENSION` for the `Extension` receiver.
5. An argument with `Kind.VALUE` for the `param1` parameter.
6. An argument with `Kind.VALUE` for the `param2` parameter.

This order **will not change** no matter how the function is called. Even if argument order is changed with named
parameters, the `arguments` list remains in the same order.

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

## Transformation

To support passing of a `CallExplanation` from the call-site to the function, both the function declaration and any
calls to the function need to be transformed by the compiler-plugin.

### Function Declarations

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

### Function Calls

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

### Overload Calls

The combination of introduced classes and functions allows improving the behavior of the existing overload function
style Power-Assert. Instead of generating the diagram at compile-time, a `CallExplanation` can be constructed, and
`toDefaultMessage()` used to generate the diagram at runtime. For example, the original example can be transformed into
the following.

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = tmp2 == "Kodee"
assert(tmp3, { CallExplanation(...).toDefaultMessage() })
```

# Future

## Additional Subclasses

### Expression

In the future, more `Expression` subclasses may be added to cover more use cases. For example, a
`StringTemplateExpression` could be introduced to help describe String concatenation and each of the arguments provided.
As libraries start adopting this compiler-plugin, we hope use case ideas will be reported via YouTrack.

### Explanation

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

We will continue to explore the potential of this idea. Other use case ideas are welcome to be reported via YouTrack.

## Expression List vs AST

The design for `Expression` means that a complex expression like `1 + 2 + 3` will be represented as a _**list**_ of
expressions and not a _**tree**_. This is an intentional design decision. A list greatly simplifies the data structure
needed to represent complex expressions, while a tree brings along many additional design decisions on how exactly some
expressions should be represented.

It is important to note that these designs are not mutually exclusive! In the future, as we explore additional use
cases, we may choose to represent expressions as a tree. We should be able to migrate to a tree representation without 
breaking existing code, as the existing list properties could be converted to a DFS walk of the tree.

[//]: # (TODO this next paragraph seems a little disrespectful...)

We look forward to hearing your feedback on this design decision! When providing feedback on this decision, please
include specific use cases whenever possible. Feedback such as "I think it should be an AST" is not helpful unless it
also answers the question "Why?".

## Additional Use-Cases

[//]: # (TODO add https://github.com/JakeWharton/cite)

## Work Towards Language Feature

[//]: # (TODO add some more info here about naming decisions and ideas for syntax?)

This work is only the beginning! We hope that with these compiler-plugin improvements, users will continue to use
Power-Assert and provide feedback. The ultimate goal is to make Power-Assert a generic language feature.

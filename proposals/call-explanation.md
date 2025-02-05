# Call Explanation

* **Type**: Design proposal
* **Author**: Brian Norman
* **Contributors**: Mikhail Zarechenskii, Marat Akhin
* **Status**: Experimental expected for 2.2
* **Prototype**: In progress
* **Discussion**: TODO
* **YouTrack Issue**: TODO

## Abstract

The Power-Assert compiler-plugin allows transforming function calls to include detailed information about the call-site.
Introduce data structures to represent call-site information, an annotation to trigger automatic transformation of
function calls, and a way to access call-site information from an annotated function.

## Table of contents

[//]: # (TODO)

## Motivation

### Power-Assert

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

This functionality is not limited to only `kotlin.assert`, but may be applied to any function that can take a `String`
or `() -> String` as the **last argument**. Functions like `kotlin.test.assertTrue` and `kotlin.test.assertEquals` are
good targets for Power-Assert transformation.

Unfortunately, specifying these functions is required for every compilation. This means that every Gradle project must
configure these functions. And there is no way for an assertion library to specify which functions support Power-Assert
transformation.

### Key Problems

From this overview, we can extract three key problems with Power-Assert:

1. Brittle function requirements that confuse adopters.
2. Verbose configuration that complicates onboarding.
3. Static diagram generation that limits tooling integration.

In this proposal, we will outline changes which attempt to tackle all of these problems.

## Proposal

[//]: # (TODO)

### Annotations

#### `ExplainCall`

A new annotation, `ExplainCall`, will be introduced to mark functions which support being explained. This annotation
also provides access to a `CallExplanation` instance (detailed below) which can be used to access call-site information.

```kotlin
annotation class ExplainCall {
    companion object {
        val explanation: CallExplanation?
    }
}
```

The `explanation` property is a compiler-plugin intrinsic, and access will always result in a runtime error unless the
compiler-plugin is applied. The compiler-plugin will also limit property access to within functions annotated with
`ExplainCall` at compile time. The `explanation` property will return `null` in cases when the function is called
without call-site information. This includes when the call-site was compiled without the compiler-plugin or when called
from non-Kotlin code, for example, Java.

#### `ExplainIgnore`

Another annotation will be introduced to mark parameters which should not be included in call-site information. This
could be for any number of reasons, and it helps the compiler-plugin avoid doing work that is unnecessary.

```kotlin
annotation class ExplainIgnore
```

For example,

```kotlin
@ExplainCall
fun powerAssert(condition: Boolean, @ExplainIgnore message: String? = null)
``` 

### Classes

 The two base classes used to describe call-site information for `@ExplainCall` annotated functions are `Explanation`
 and `Expression`.
 
It is important to note that neither of these base classes are `sealed`, so that new subclasses may be added without
breaking source compatibility. 

#### `Explanation`

An `Explanation` provides information about the source code and expressions evaluated within that code.

[//]: # (TODO finalize data structure)

```kotlin
abstract class Explanation {
    abstract val offset: Int
    abstract val source: String
    abstract val expressions: List<Expression>
}
```

The `CallExplanation` class - as provided by `@ExplainCall` - describes each argument provided to a function call
individually, while providing the source code information for the entire call.

[//]: # (TODO finalize data structure)

```kotlin
class CallExplanation(
    override val offset: Int,
    override val source: String,
    val dispatchReceiver: Receiver?,
    val contextArguments: Map<String, Argument>,
    val extensionReceiver: Receiver?,
    val valueArguments: Map<String, Argument>,
) : Explanation() {
    override val expressions get() = buildList {
        dispatchReceiver?.let { addAll(it.expressions) }
        contextArguments.values.forEach { addAll(it.expressions) }
        extensionReceiver?.let { addAll(it.expressions) }
        valueArguments.values.forEach { addAll(it.expressions) }
    }
    
    abstract class Argument {
        abstract val startOffset: Int
        abstract val endOffset: Int
        abstract val expressions: List<Expression>
    }

    class Receiver(
        override val startOffset: Int,
        override val endOffset: Int,
        override val expressions: List<Expression>,
        val isImplicit: Boolean,
    ) : Argument()
}
```

#### `Expression`

An `Expression` represents a value calculated at runtime. This could be the result of a function call, variable access,
language operator, or anything else that results in a value. 

[//]: # (TODO finalize data structure)

```kotlin
abstract class Expression(
    val startOffset: Int,
    val endOffset: Int,
    val displayOffset: Int,
    val value: Any?,
)
```

For now, the only subclass provided to describe a specific kind of expression is `EqualityExpression`. This can be used
to know if an expression like `1 == 2` was evaluated. The class not only provides the result of the evaluation, but also
the left-hand and right-hand side values of the expression. Libraries may use these values to calculate diagnostic
information about equalities that result in a `false` value.

[//]: # (TODO finalize data structure)

```kotlin
class EqualityExpression(
    startOffset: Int,
    endOffset: Int,
    displayOffset: Int,
    value: Any?,
    val lhs: Any?,
    val rhs: Any?,
) : Expression(startOffset, endOffset, displayOffset, value)
```

### Functions

A few top-level functions will be included as well, to provide Power-Assert style rendering of an `Explanation`. These
functions will be used to replace the current compile-time generated String for overload style Power-Assert
transformation.

[//]: # (TODO finalize function signature)

```kotlin
fun Explanation.toDefaultMessage(
    render: Expression.() -> String? = Expression::render,
): String
```

[//]: # (TODO finalize function signature)

```kotlin
fun Expression.render(): String
```

### Transformation

To support passing of a `CallExplanation` from the call-site to the function, both the function declaration and any
calls to the function need to be transformed by the compiler-plugin.

#### Declaration

When `ExplainCall` is added to a function, the compiler-plugin generates a synthetic copy of the function. This
synthetic copy takes an additional parameter of type `() -> CallExplanation`. Both functions are then transformed to
replace any calls to `ExplainCall.explanation`, with either `null` in the case of the original function, or the
additional parameter in the case of the synthetic copy.

For example,

```kotlin
@ExplainCall
fun powerAssert(condition: Boolean) {
    if (!condition) {
        val explanation = ExplainCall.explanation
        // ...
    }
}
```

Will result in,

```kotlin
@ExplainCall
fun powerAssert(condition: Boolean) {
    if (!condition) {
        val explanation = null
        // ...
    }
}

@ExplainCall
fun `powerAssert$explained`(condition: Boolean, `$explanation`: () -> CallExplanation) {
    if (!condition) {
        val explanation = `$explanation`.invoke()
        // ...
    }
}
```

#### Calls

If a function is annotated with `@ExplainCall`, and has itself been transformed by the compiler-plugin, this will result
in any call to said function being automatically transformed by the compiler-plugin to include a `CallExplanation`
parameter.

For example, 

```kotlin
powerAssert(mascot.name == "Kodee")
```

Will result in,

```kotlin
val tmp1 = mascot
val tmp2 = tmp1.name
val tmp3 = tmp2 == "Kodee"
`powerAssert$explained`(tmp3, { CallExplanation(...) })
```

## Extension

### Expression Types

In the future, more `Expression` subclasses may be added to cover more use cases. For example, a
`StringTemplateExpression` could be introduced to help describe a templated String and each of the arguments provided.
As libraries start adopting this compiler-plugin, we hope use case ideas will be reported via YouTrack.

### Local Variables

[//]: # (TODO should we even have this section? seems like it might imply this will happen... which it may not?)

We are exploring addition types of `Explanation`, including explanations of local variables. The initializer of a local
variable can be explained just like function call arguments are explained. This would allow including the explanation of
local variables along with a `CallExplanation`, enriching the data about the call-site.

There are a number of concerns with this idea:

1. **Security** - Even more information about the call-site is sent to the called function, which increases the security
   risk of leaking sensitive information.
2. **Performance** - Excessive call-site transformation may result in slower code, which may not be needed if the
   explanation is never used.
3. **Syntax** - Determining what local variables are transformed and how their information is propagated should be
   determined by the code author without much additional burden.

We will continue to explore the potential of this idea. Other use case ideas are welcome to be reported via YouTrack.

## Concerns

### Automatic Call-Site Transformation

[//]: # (TODO)

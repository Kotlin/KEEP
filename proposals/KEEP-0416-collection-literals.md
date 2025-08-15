# Collection Literals

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**:
  Alejandro Serrano Mena,
  Denis Zharkov,
  Ivan “CLOVIS” Canet,
  Marat Akhin,
  Mikhail Zarechenskii
* **Issue:** [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871)
* **Status:** In progress
* **Prototype:** https://github.com/JetBrains/kotlin/tree/bobko/collection-literals
* **Discussion**: [KEEP-416](https://github.com/Kotlin/KEEP/issues/416)

Collection literal is a well-known feature among modern programming languages.
It's a syntactic sugar built-in into the language that allows creating collection instances more concisely and effortlessly.
In the simplest form, if users want to create a collection, instead of writing `val x = listOf(1, 2, 3)`, they could write `val x = [1, 2, 3]`.

## Table of contents

- [Motivation](#motivation)
- [Proposal](#proposal)
  - [Collection literals in annotations](#collection-literals-in-annotations)
- [Concerns](#concerns)
- [Overload resolution motivation](#overload-resolution-motivation)
  - [Overload resolution and type inference](#overload-resolution-and-type-inference)
  - [Operator function `of` restrictions](#operator-function-of-restrictions)
  - [Operator function `of` allowances](#operator-function-of-allowances)
- [Fallback rule. What if `Companion.of` doesn't exist](#fallback-rule-what-if-companionof-doesnt-exist)
- ["Contains" optimization](#contains-optimization)
- [Similarities with `@OverloadResolutionByLambdaReturnType`](#similarities-with-overloadresolutionbylambdareturntype)
- [Feature interactions](#feature-interactions)
  - [Feature interaction with `@OverloadResolutionByLambdaReturnType`](#feature-interaction-with-overloadresolutionbylambdareturntype)
  - [Feature interaction with flexible types](#feature-interaction-with-flexible-types)
  - [Feature interaction with intersection types](#feature-interaction-with-intersection-types)
- [Similar features in other languages](#similar-features-in-other-languages)
- [Interop with Java ecosystem](#interop-with-the-Java-ecosystem)
- [Tuples](#tuples)
- [Performance](#performance)
  - [Performance. Companion object allocation](#performance-companion-object-allocation)
- [IDE support](#ide-support)
- [`listOf` deprecation](#listof-deprecation)
- [Change to stdlib](#change-to-stdlib)
  - [Semantic differences between Kotlin and Java factory methods](#semantic-differences-between-kotlin-and-java-factory-methods)
- [Empty collection literal](#empty-collection-literal)
- [Future evolution](#future-evolution)
  - [Theoretical possibility to support List vs Set overloads in the future](#theoretical-possibility-to-support-list-vs-set-overloads-in-the-future)
  - [Map literals](#map-literals)
- [Rejected proposals](#rejected-proposals)
  - [Rejected proposal: always infer collection literals to `List`](#rejected-proposal-always-infer-collection-literals-to-list)
  - [Rejected proposal: more granular operators](#rejected-proposal-more-granular-operators)
  - [Rejected proposal: more positions with the definite expected type](#rejected-proposal-more-positions-with-the-definite-expected-type)
  - [Rejected proposal: self-sufficient collection literals with defined type](#rejected-proposal-self-sufficient-collection-literals-with-defined-type)
  - [Rejected proposal: use improved overload resolution algorithm only to refine non-empty overload candidates set on the fixated tower level](#rejected-proposal-use-improved-overload-resolution-algorithm-only-to-refine-non-empty-overload-candidates-set-on-the-fixated-tower-level)

## Motivation

1.  Collections (not literals) are very widely used in programming.
    They deserve a separate literal.
    A special syntax for collection literals makes them instantly stand out from the rest of the program, making code easier to read.
2.  Simplify migration from other languages / Friendliness to newcomers.
    Collection literals is a widely understood concept with more or less the same syntax across different languages.
    And new users have the right to naively believe that Kotlin supports it.
    The presence of this feature makes a good first impression on the language.
3.  Clear intent.
    A special syntax for collection literals makes it clear that a new instance consisting of the supplied elements is created.
    For example, `val x = listOf(10)` is potentially confusing, because some readers might think that a new collection with the capacity of 10 is created.
    Compare it to `val x = [10]`.
4.  Close the design gap between annotations and regular expressions.
    The existing Kotlin versions [already allow collection literals in annotation arguments](#collection-literals-in-annotations),
    but the very same collection literals are not allowed in regular expressions.
    This discrepancy exists due to the way annotation arguments are processed at compile time.
    The proposal strives to close this gap.
5.  Special syntax for collection literals helps to resolve the `emptyList`/`listOf` hassle.
    Whenever the argument list in `listOf` reduces down to zero, some might prefer to clean up the code to change `listOf` to `emptyList`.
    And vice versa, whenever the argument list in `emptyList` needs to grow above zero, the programmer needs to replace `emptyList` with `listOf`.
    It creates a small hassle of `listOf` to `emptyList` back and forth replacement.
    It's by no means a big problem, but it is just a small annoyance, which is nice to see to be resolved by the introduction of collection literals.

The feature brings more value to newcomers rather than to experienced Kotlin users and should target the newcomers primarily.

Since the biggest feature value is aesthetics, ergonomics and readability,
all of which are hard to measure and subjective, it makes sense to see before/after code examples to feel the feature better:
```kotlin
// before 1
    if (readlnOrNull() in listOf("y", "Y", "yes", "Yes", null)) {
        // ...
    }
// after 1
    if (readlnOrNull() in ["y", "Y", "yes", "Yes", null]) {
        // ...
    }


// before 2
    val defaultCompilationArguments = listOf(
        "-no-reflect",
        "-no-stdlib",
        "-d", outputDirectory.absolutePathString(),
        "-cp", dependencyFiles.joinToString(File.pathSeparator),
        "-module-name", moduleName,
    )
// after 2
    val defaultCompilationArguments = [
        "-no-reflect",
        "-no-stdlib",
        "-d", outputDirectory.absolutePathString(),
        "-cp", dependencyFiles.joinToString(File.pathSeparator),
        "-module-name", moduleName,
    ]


// before 3
    projectTest(
        parallel = true,
        defineJDKEnvVariables = listOf(JdkMajorVersion.JDK_1_8, JdkMajorVersion.JDK_11_0, JdkMajorVersion.JDK_17_0)
    ) { /* ... */ }
// after 3
    projectTest(
        parallel = true,
        defineJDKEnvVariables = [JdkMajorVersion.JDK_1_8, JdkMajorVersion.JDK_11_0, JdkMajorVersion.JDK_17_0]
    ) { /* ... */ }


// before 4
    val toReplace = mutableListOf<String>()
    val visited = mutableSetOf<String>()
// after 4
    val toReplace: MutableList<String> = []
    val visited: MutableSet<String> = []


// before 5
    it[AnalysisFlags.optIn] = optInList + listOf("kotlin.ExperimentalUnsignedTypes")
// after 5
    it[AnalysisFlags.optIn] = optInList + ["kotlin.ExperimentalUnsignedTypes"]


// before 6
    for (key in listOf(argument.value, argument.shortName, argument.deprecatedName)) {
        if (key.isNotEmpty()) put(key, argumentField)
    }
// after 6
    for (key in [argument.value, argument.shortName, argument.deprecatedName]) {
        if (key.isNotEmpty()) put(key, argumentField)
    }


// before 7
    override fun getMimeTypes(): List<String> = listOf("text/x-kotlin")
// after 7
    override fun getMimeTypes(): List<String> = ["text/x-kotlin"]


// before 8
    fun <D> MutableMap<String, MutableSet<D>>.initAndAdd(key: String, value: D) {
        this.compute(key) { _, maybeValues -> (maybeValues ?: mutableSetOf()).apply { add(value) } }
    }
// after 8
    fun <D> MutableMap<String, MutableSet<D>>.initAndAdd(key: String, value: D) {
        this.compute(key) { _, maybeValues -> (maybeValues ?: []).apply { add(value) } }
    }


// before 9
    modules.mapNotNullTo(hashSetOf()) { environment.findLocalFile(it.getOutputDirectory()) }
    sourcesByModuleName.getOrPut(moduleName) { mutableSetOf() }.add(sourceFile)
// after 9
    // Can't be expressed via collection literals


// before 10
    if (asterisk[row] != emptyList<Int>()) { /* ... */ }
// after 10
    // Can't be expressed via collection literals
```

## Proposal

**Informally**, the proposal strives to make it possible for users to use collection literals syntax to express user-defined types `val foo: MyCustomList<Int> = [1, 2, 3]`.
And when the *expected type* (See the definition below) is unspecified, expression must fall back to the `kotlin.List` type: `val foo = [1, 2, 3] // List<Int>`.

It's proposed to use square brackets because the syntax is already used in Kotlin for array literals inside annotation constructor arguments.
It's the syntax a lot of programmers are already familiar with coming from other programming languages,
and because it honors mathematical notation for matrices.

```kotlin
class MyCustomList<T> {
    companion object { operator fun <T> of(vararg elements: T): MyCustomList<T> = TODO() }
}

fun main() {
    val list: MyCustomList<Int> = [1, 2] // is equivalent to:
    val list: MyCustomList<Int> = MyCustomList.of(1, 2)

    val list1 = [1, 2] // is equivalent to:
    val list1 = List.of(1, 2)
}
```

**Informal definition.**
Internally, Kotlin has a notion of *expected type*.
Expressions have some type, which we call *the actual type* or just *the type*.
Those expressions fill in the *holes*.
And the holes only accept expressions of a particular type.
The particular type accepted by the hole is called *the expected type*.

For example:
```kotlin
fun outerCall(a: CharSequence) = Unit

fun foo(a: Short) = Unit
fun foo(a: Any) = Unit

fun main() {
    val foo: Number = 1 // The "actual type" is Int, but the "expected type" is Number
    outerCall("Kotlin is the best") // The "actual type" is String, but the "expected type" is CharSequence
    val bar = 1 // The "actual type" is Int and the "expected type" is Int.

    // Sometimes the "expected type" can affect the "actual type".
    // Unfortunately for us (language implementors), the actual type isn't inferred independently in the air, but is affected by the "expected type"
    val baz: Short = 1 // The "actual type" is Short, and the "expected type" is Short

    // The "actual type" affects overload resolution, which in turn affects what type the "expected type" is going to materialize into,
    // which means that transitively "actual type" affects the "expected type"
    // type inference, unfortunately, is bi-directional in Kotlin
    foo(1) // The "actual type" is Short, the "expected type" is Short
}
```

Another KEEP proposal that heavily uses the notion of expected type is [Improve resolution using expected type](https://github.com/Kotlin/KEEP/blob/improved-resolution-expected-type/proposals/improved-resolution-expected-type.md).
But in the KEEP, more positions were considered to have the expected type.
Unfortunately, [we can't do that](#rejected-proposal-more-positions-with-the-definite-expected-type) for the collection literals proposal.

**Definition.**
`Type` *static scope* is the set that contains member callables (functions and properties) of `Type.Companion` type (`Type.Companion` is a companion object),
or static members of the type if the type is declared in Java.
(Extension on `Type.Companion` are excluded on purpose)

Before the collection literal could be used at the use-site, an appropriate type needs to declare `operator fun of` function in its static scope.
The `operator fun of` functions must adhere to [the restrictions](#operator-function-of-restrictions).

Once a proper `operator fun of` is declared, the collection literal can be used at the use-site.
1.  When the collection literal is used in the position of arguments, similarly to lambdas and callable references, collection literal affects the overload resolution of the *outer call*.
    See the section dedicated to [overload resolution](#overload-resolution-motivation).
2.  When the collection literal is used in the position with definite expected type, the collection literal is literally desugared to `Type.of(expr1, expr2, expr3)`,
    where `Type` is the definite expected type.

    The following positions are considered positions with the definite expected type:
    - Explicit `return`, single-expression functions (if type is specified), and last expression of lambdas
    - Assignments and initializations
3.  In all other cases, it's proposed to desugar collection literal to `List.of(expr1, expr2, expr3)`.
    The precise fallback rule is described in a [separate section](#fallback-rule-what-if-companionof-doesnt-exist).

Some examples:
```kotlin
class MyCustomList<T> {
    companion object { operator fun <T> of(vararg t: T): MyCustomList<T> = TODO() }
}
fun main() {
    val foo: MyCustomList<Int> = [1, 2, 3]
    val bar: MyCustomList<String> = ["foo", "bar"]
    val baz = [1, 2, 3] // List<Int>
    var mutable: Set<Int> = [1, 2, 3] // Set<Int>
    mutable = [4, 5] // Set<Int>
}

fun foo(): MyCustomList<Int> = [1, 2, 3]
```

Please note that it is not necessary for the type to extend any predefined type in the Kotlin stdlib (needed to support `kotlin.Array<T>` type),
nor it is necessary for the user-defined type to declare mandatory generic type parameters (needed to support specialized arrays like `kotlin.IntArray`, `kotlin.LongArray`, etc.).

### Collection literals in annotations

The proposal makes sure that all the existing code that already uses collection literals in annotations will remain green:

```kotlin
annotation class Ann(val array: IntArray)

@Ann([1]) // Collection literals are already possible in annotations in the existing versions of Kotlin
fun main() {}
```

## Concerns

The following concerns were raised during the KEEP review:

**1. Ambiguity with Java's `new int[10]` syntax.**
It's an unfortunate collision with Java's syntax.
It becomes even "worse"
if we later introduce [self-sufficient collection literals](#rejected-proposal-self-sufficient-collection-literals-with-defined-type) like `List [10]`,
because Java developers might think that it's an array of Lists of length 10.

The proposal doesn't address this collision in any way.
We hope that it won't be a problem given that arrays are more rarely used than lists.
After all, it's not the first place in Kotlin where we match Java's syntax,
but the semantics is different – the syntax of Kotlin lambdas is a good example.

## Overload resolution motivation

The reasoning behind [the restrictions](#operator-function-of-restrictions) on `operator fun of` are closely related to overload resolution and type inference.
Consider the following real-world example:
```kotlin
@JvmName("flushFilesString") fun flushFiles(set: List<String>): Unit = TODO("not implemented") // (1)
@JvmName("flushFiles")       fun flushFiles(set: List<File>): Unit = TODO("not implemented")      // (2)

fun main() {
    flushFiles(["foo.txt", "bar.txt"]) // Should resolve to (1)
}

// another example
@JvmName("sumInt")    fun sum(set: List<Int>): Int = TODO("not implemented")
@JvmName("sumDouble") fun sum(set: List<Double>): Double = TODO("not implemented")
```

We want to make the overload resolution work for such examples.
We have conducted an analysis to see what kinds of overloads are out there [KT-71574](https://youtrack.jetbrains.com/issue/KT-71574) (private link).
In short, there are all kinds of overloads.
The restrictions and the overload resolution algorithm suggested further will help us to make the resolution algorithm distinguish `List<String>` vs `List<File>` kinds of overloads.

> [!NOTE]
> When we say `List` vs `Set` kinds of overloads, we mean all sorts of overloads where the *outer type* is different.
> When we say `List<String>` vs `List<File>` kinds of overloads, we mean all sorts of overloads where the *inner type* is different.

-   `List<String>` vs `Set<String>` typically emerges when one of the overloads is the *main overload*, and another one is just a convenience overload that delegates to the main overload.
    Such overloads won't be supported because it's generally not possible to know which of the overloads is the main overload,
    and because collection literal syntax doesn't make it possible to syntactically distinguish `List` vs `Set`.
    But if we ever change our mind, [it's possible to support `List` vs `Set` kind of overloads](#theoretical-possibility-to-support-List-vs-Set-overloads-in-the-future) in the language in backwards compatible way in the future.
-   `List<String>` vs `List<File>` is self-explanatory (for example, consider the `flushFiles` example above).
    Such overloads should be and will be supported.
-   `List<String>` vs `Set<File>`. Both inner and outer types are different.
    This pattern is less clear and generally much rarer.
    The pattern may emerge accidentally when different overloads come from different packages.
    Or when users don't know about `@JvmName`, so they use different outer types to circumvent `CONFLICTING_JVM_DECLARATIONS`.
    This pattern will be supported just because of the general approach we are taking that distinguishes inner types.

### Overload resolution and type inference

> Readers of this section are encouraged to familiarize themselves with how [type inference](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md) and
> [overload resolution](https://kotlinlang.org/spec/overload-resolution.html#overload-resolution) already work in Kotlin.

All the problems with overload resolution and collection literals come down to examples when collection literals are used at the argument position.
```kotlin
fun <K> materialize(): K = null!!
fun outerCall(a: List<Int>) = Unit
fun outerCall(a: Set<Double>) = Unit

fun test() {
    outerCall([1, 2, materialize()])
}
```

Conceptually, the overload resolution algorithm consists of two stages:
1.  Filter out all the overload candidates that certainly don't fit based on types of the arguments.
    (it's important to understand that we don't keep the candidates that fit, but we filter out those that don't)
    https://kotlinlang.org/spec/overload-resolution.html#determining-function-applicability-for-a-specific-call
2.  Of the remaining candidates, we keep the most specific ones by comparing every two distinct overload candidates.
    https://kotlinlang.org/spec/overload-resolution.html#choosing-the-most-specific-candidate-from-the-overload-candidate-set

For our case to distinguish `List<String>` vs `List<File>` kinds of overloads (see the [Overload resolution motivation](#overload-resolution-motivation) section), the first stage of overload resolution is the most appropriate one.
That's exactly what we want to do – to filter out definitely inapplicable `outerCall` overloads.

Given the following example: `outerCall([expr1, [expr2], expr3, { a: Int -> }, ::x], expr4, expr5)`,
similar to lambdas and callable references, collection literal expression type inference is postponed.
Contrary, elements of the collection literal are analyzed in the way similar to how other arguments of `outerCall` are analyzed, which means:
1.  If collection literal elements are lambdas, or callable references, their analysis is postponed.
    Only the number of lambda parameters and lambda parameter types (if specified) are considered for overload resolution of `outerCall`.
2.  If collection literal elements are collection literals themselves, then we descend into those literals and recursively apply the same rules.
3.  All other collection literal elements are *plain arguments/elements*, and they are analyzed in [so-called *dependent mode*](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md).

For every overload candidate, when a collection literal maps to its appropriate `ParameterType`:
1.  We find `ParameterType.Companion.of(vararg)` function.
    [operator fun of restrictions](#operator-function-of-restrictions) either guarantee us that the `of` function exists and unique,
    or [fallback rule](#fallback-rule-what-if-companionof-doesnt-exist) kicks in.
2.  We remember the parameter of the single `vararg` parameter.
    We will call it *CLET* (collection literal element type).
    We also remember the return type of that `ParameterType.of(vararg)` function.
    We will call it *CLT* (collection literal type).
3.  At the first stage of overload resolution of `outerCall` (when we filter out inapplicable candidates), we add the following constraints to the constraint system of `outerCall` candidate:
    1. For each collection literal element `e`, we add `type(e) <: CLET` constraint.
    2. We also add the following constraint: `CLT <: ParameterType`.

Once all plain arguments are analyzed (their types are inferred in dependent mode), and all recursive plain elements of collection literals are analyzed,
we proceed to filtering overload candidates for `outerCall`.

Please note that constraints described above are only added to the constraint system of the `outerCall` and not to constraint system of the `of` function themselves.
(overload resolution for which will be performed later, once the overload resolution for `outerCall` is done)

Example:
```kotlin
class List<T> { companion object {
    operator fun <T> of(vararg t: T): List<T> = TODO("not implemented")
} }

fun <K> materialize(): K = null!!
fun outerCall(a: List<Int>) = Unit // (1)
fun outerCall(a: List<String>) = Unit // (2)

fun test() {
    // The initial constraint system for candidate (1) looks like:
    //                type(1) <: T
    //                type(2) <: T
    // type(materialize<K>()) <: T
    //                List<T> <: List<Int>
    // The constraint system is sound => the candidate is not filtered out

    // The initial constraint system for candidate (2) looks like:
    //                type(1) <: T
    //                type(2) <: T
    // type(materialize<K>()) <: T
    //               List<T> <: List<String>
    // The constraint system is unsound => the candidate is filtered out
    outerCall([1, 2, materialize()]) // We resolve to the candidate (1)
}
```

It's important to understand that until we pick the `outerCall` overload, we don't start the full-blown overload resolution process for nested `of` calls.
If we did so, it'd lead to exponential complexity of `outerCall` overload resolution.
Consider the `outerCall([[[1, 2, 3]]])` example.

Once the particular overload for `outerCall` is chosen, we know what definite expected type collection literal maps to.
We desugar collection literal to `DefiniteExpectedType.of(expr1, expr2, expr3)`, and we proceed to resolve overloads of `DefiniteExpectedType.of` according to regular Kotlin overload resolution rules.

### Operator function `of` restrictions

**The overall goal of the restrictions:**
Make it possible to extract type restrictions for the elements of the collection literal without the necessity of the full-blown real overload resolution for `operator fun of` function.
Given only the outer type `List<Int>`/`IntArray`/`Foo`, we should be able to infer collection literal *element types*.
We need to know the types for the constraint system of the `outerCall` like in the following example:
```
@JvmName("outerCallFiles")   fun outerCall(set: List<File>): Unit = TODO("not implemented") // (1)
@JvmName("outerCallStrings") fun outerCall(set: List<String>): Unit = TODO("not implemented")  // (2)

fun main() {
    outerCall(["foo.txt", "bar.txt"]) // Should resolve to (2)
}
```

**Restriction 1.**
Extension `operator fun of` functions are forbidden.
All `operator fun of` functions must be declared as member functions of the target type Companion object.
```kotlin
class Foo { companion object }
operator fun Foo.Companion.of(vararg x: Int) = Foo() // Forbidden
```

It's a technical restriction driven by the fact that, in Kotlin, extensions can win over members if members are not applicable.
The restriction avoids the need to consider imported `of`s and the need to check their restrictions.
The presence of the extensions makes it impossible to check all further restrictions on the `of` function declaration side.

One could argue that it's already possible for all other `operator`s in Kotlin to be declared as extensions rather than members,
and it feels limiting not being possible to do the same for `operator fun of`.
Formally, `operator fun of` is different.
All `operator`s in Kotlin operate on the existing expression of the target type, while `operator fun of` doesn't have access to the expression of its target type, `operator fun of` is the constructor of its target type.
We could even replace `operator` keyword with another keyword to clarify that `operator fun of` is not a regular operator, but we don't see a lot of practical value in it.

> Inability to declare an extension might feel limiting for interop with Java.
> See [Interop with the Java ecosystem section](#interop-with-the-java-ecosystem).

**Restriction 2.**
One and only one overload must declare a `vararg` parameter.
The `vararg` parameter is the latest parameter of its containing `of` function.
All parameters that come in front of the `vararg` parameter must all have the same type as the `vararg` parameter,
or there could be zero parameters in front of the `vararg` parameter (which will be the most popular case).

The overload is considered the main overload, and it's the overload we use to extract type constraints from for the means of `outerCall` overload resolution.
Please remember that we treat a collection literal argument as a postponed argument (similar to lambdas and callable references).
First, We do the overload resolution for the `outerCall` and only once a single applicable candidate is found, we use its appropriate parameter type as an expected type for the collection literal argument expression.

Valid examples:
```kotlin
class Foo {
    companion object {
        operator fun of(vararg x: Int): Foo = Foo()
        operator fun of(x: Int): Foo = Foo()
        operator fun of(x: Int, y: Int): Foo = Foo()
    }
}

class NonEmptyList {
    companion object {
        operator fun of(first: Int, vararg rest: Int): Foo = Foo()
        operator fun of(first: Int): Foo = Foo()
    }
}
```

Invalid examples:
```kotlin
class Foo {
    companion object {
        operator fun of(vararg x: Int): Foo = Foo()
        operator fun of(vararg x: String): Foo = Foo() // Error: Duplicated `vararg` overload
    }
}

class Pair {
    companion object {
        operator fun of(x: Int, y: Int): Pair = Pair() // Error: `vararg` overload is not declared
    }
}

class BrokenNonEmptyList {
    companion object {
        operator fun of(first: Int, second: String, vararg rest: Int): Foo = Foo() // Error: types of parameters `first`, `second` and `rest` must be the same
    }
}
```

**Restriction 3.**
All `of` overloads must have non-nullable return type equal by *ClassId* to the type in which static scope the overload is declared in.

The *ClassId* of a type is its typed fully qualified name.
It's a list of *typed* tokens, where every token represents either a name of the package or a name of the class.
*Typed* means that the package named "foo" doesn't equal to the class named "foo".

The reasoning for that restriction is that we use the expected type for searching the `of` function,
so we need to ensure that the choice matches the type of the collection literal expression.

Supportive example:
```kotlin
class Foo { companion object {
    operator fun of(vararg x: Int): String = ""
} }

fun main() {
    val x: Foo = [1] // [TYPE_MISMATCH] expected: Foo, got: String
}
```

**Restriction 4.**
All `of` overloads must have the same return type.
If the return type is generic, the constraints on type parameters must coincide.

Supportive example:
```kotlin
class MyList<T> { companion object {
    operator fun of(vararg x: Int): MyList<Int> = MyList()
    operator fun of(x: Int, y: Int): MyList<String> = MyList() // not allowed
} }

fun outerCall(a: MyList<Int>) = Unit // (1)
fun outerCall(b: MyList<String>) = Unit // (2)

fun main() {
    // Since the restrictions for `outerCall` overload resolution are taken from the `vararg` overload,
    // we fixate `outerCall` to (1), then we desugar collection literal to `MyList.of(1, 2)`, type of `MyList.of(1, 2)` is `MyList<String>` => We report `TYPE_MISMATCH`
    outerCall([1, 2])
    // But the following call (which is just a desugar of the previous one) resolves to (2) and works flawlessly
    outerCall(MyList.of(1, 2))
}
```

**Restriction 5.**
All `of` overloads must have the same visibility.

**Restriction 6.**
The `of` overloads may only differ in the number of parameters (`vararg` is considered an infinite number of params).

> Technically, restriction 6 is a superset of restrictions 4 and 5, but we still prefer to mention them separately.

Which means that the types of the parameters must be the same, and all type parameters with their bounds must be the same.

**Restriction 7.**
All `of` overloads must have no extension/context parameters/receivers.

We forbid them to keep the mental model simpler, and since we didn't find major use cases.
Since all those implicit receivers affect availability of the `of` function, it'd complicate `outerCall` overload resolution, if we allowed implicit receivers.

**Restriction 8.**
`operator fun of` functions are not allowed to return nullable types.
The supportive example is the same as in restriction 3.
The nullability restriction is also important for [feature interaction with intersection types](#feature-interaction-with-intersection-types)

### Operator function `of` allowances

We would like to explicitly note the following allowances.

**Allowance 1.**
It's allowed to have reified generics and mark the operator as `inline`.

Use case:
```kotlin
class Array<T> {
    companion object {
        operator fun inline <reified T> of(vararg elements: T): Array<T> /*the implementation is generated*/
    }
}
```

**Allowance 2.**
The operator is allowed to be `suspend`, or `tailrec`.
Because all other operators are allowed being such as well, and we don't see reasons to restrict `operator fun of`.

**Allowance 3.**
It's allowed to have overloads that differ in number of arguments:
```kotlin
class List<T> {
    companion object {
        operator fun <T> of(vararg elements: T): List<T> = TODO()
        operator fun <T> of(element: T): List<T> = TODO()
        operator fun <T> of(): List<T> = TODO()
    }
}
```

**Allowance 4.**
Java static `of` members are perceived as `operator fun of` if they satisfy the above restrictions.

Java static members are inherited only if they come from classes.
Kotlin respects that and behaves in the same way as Java does:

```java
// IBase.java
public interface IBase { public static IBase of(int... x) { return null; } }
// Base.java
public class Base { public static Base of(int... x) { return null; } }
// ImplementsInterface.java
public class ImplementsInterface implements IBase {}
// ExtendsClass.java
public class ExtendsClass extends Base {}
// Usage.java
public class Usage {
    public static void main(String[] args) {
        ExtendsClass.of(); // green
        ImplementsInterface.of(); // red
    }
}
// usage.kt
fun main() {
    ExtendsClass.of() // green
    ImplementsInterface.of() // red
}
```

The rules for static members inheritance should stay the same for collection literals:

```kotlin
fun main() {
    val a: ExtendsClass = [1, 2] // green
    val a: ImplementsInterface = [1, 2] // red
}
```

## Fallback rule. What if `Companion.of` doesn't exist

When the compiler can't find `.Companion.of`, it always falls back to `List.Companion.of`.
If later it turns out that `List` can't be subtype of the expected type, the compiler reports type mismatch as usual.
The fallback rule also affects CLET and CLT during [overload resolution stage](#overload-resolution-and-type-inference).

Examples:
```kotlin
class Foo
fun main() {
    val foo: Foo = [1, 2] // type mismatch error
    val list1: Any = [1, 2] // Green because we fall back to List. The code is desugared to List.of(1, 2)
    val list2: Iterable<Any> = [1, 2] // Green
    val list3: Iterable<Int> = [1, 2] // Green
    val list4: Collection<Int> = [1, 2] // Green
}
```

```kotlin
fun outer(a: Iterable<String>) = Unit // (1)
fun outer(a: Iterable<Int>) = Unit // (2)
fun main() {
    outer([1]) // resolves to (2) thanks to fallback. Note that Iterable won't declare `Iterable.Companion.of`
}
```

```kotlin
fun main() {
    // println overloads:
    // - println(Any?)
    // - println(Int)
    // - println(Short)
    // - etc.
    pritln([1, 2]) // green. Resolves to `println(Any?)`. List is subtype of Any? the fallback is successful
}
```

```kotlin
fun <T : List<String>> outer(a: T) = Unit // (1)
fun <T : List<Int>> outer(a: T) = Unit // (2)

fun outer2(a: List<String>) = Unit // (3)
fun outer2(a: List<Int>) = Unit // (4)

fun <T> id(t: T): T = t

fun main() {
    outer([1]) // resolved to (2)
    outer2([1, 2, 3]) // resolves to (4)
    outer2(id([1, 2, 3])) // overload resolution ambiguity
}
```

**Non-issue 1.**
Note that type parameters are not yet fixated during applicability checking of overload candidates.
It means that unconditional fallback to `List` may prospectively dissatisfy type parameter bounds, which sometimes could be puzzling:

```kotlin
class MyList { companion object { operator fun <T> of(vararg elements: T): MyList = TODO() } }

fun <T : MyList> outer1(a: T) = Unit // (1)
fun <T : List<Int>> outer1(a: T) = Unit // (2)

fun outer2(a: MyList) = Unit // (3)
fun outer2(a: List<Int>) = Unit // (4)

fun <T : MyList> outer3(a: T) = Unit
fun outer4(a: MyList) = Unit

fun <T> id(t: T): T = t

fun main() {
    outer1([1]) // resolves to (2)
    outer2([1]) // overload resolution ambiguity

    outer3([1]) // red. Type mismatch error
    outer4([1]) // green
    outer4(id([1])) // red. Type mismatch error
}
```

In the example, we manage to pick the overload in the `outer1` case but not in the `outer2` case.
We think that such examples are synthetic, and it's more important to have the simple fallback rule.

**Non-issue 2.**
We don't plan to declare `.Companion.of` for `MutableCollection` and `MutableIterable`.
The following example won't work, and we think that it's ok:

```kotlin
fun outer(a: MutableCollection<String>) = Unit

fun main() {
    val foo: MutableCollection<String> = [""] // error. Type mismatch List<String> is not subtype of MutableCollection<String>
}
```

## "Contains" optimization

Collection literals bring a good use case:
```kotlin
if (readlnOrNull() in ["y", "Y", "yes", "Yes", null]) {
    // ...
}
```

Since it's quite a common use case, it'd be neat if the compiled output (bytecode, native binary, JavaScript, etc.) didn't contain unnecessary collection allocations.

The proposal is to generate an output which would be equivalent to:
```kotlin
val tmp = readlnOrNull()
val tmp1 = "y"
val tmp2 = "Y"
val tmp3 = "yes"
val tmp4 = "Yes"
val tmp5 = null
if (tmp == tmp1 || tmp == tmp2 || tmp == tmp3 || tmp == tmp4 || tmp == tmp5) {
    // ...
}
```

For the `x in [y1, y2, y3, ...]` code pattern, the IDEs should also try to detect duplicated elements and issue a warning if there are some.

## Similarities with `@OverloadResolutionByLambdaReturnType`

> The section is added for the sake of mental model clarity and understanding.
> You can skip it.

The suggested algorithm of overload resolution for collection literals shares similarities with [`@OverloadResolutionByLambdaReturnType`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-overload-resolution-by-lambda-return-type/).

Similar to how "the guts" of the lambda (the type of the return expression) are analyzed for the sake of the `outerCall` overload resolution,
the guts of collection literal (elements of the collection literal) are analyzed for the same purpose.
The big difference though is that analysis of the collection literal guts doesn't depend on some *input types* coming from the signature of the particular `outerCall`,
while in the case of lambdas it's different.
You need to know the types of the lambda parameters (the *input types*) to infer the return type of the lambda.

That's why in the case of collection literals, we can jump right into the analysis of its elements, and only postpone the overload resolution of the `operator fun of` function.

Another big difference is what stage the improved overload resolution by lambda return type or by collection literal element type kicks in.

Improved overload resolution by collection literal element type naturally merges itself into the first stage of overload resolution (candidates filtering), where it logically belongs to.
Contrary, `@OverloadResolutionByLambdaReturnType` is a separate stage of overload resolution that kicks in after choosing the most specific candidate.

The fact that `@OverloadResolutionByLambdaReturnType` is just slapped on top of regular overload resolution algorithm can be seen in the following example:
```kotlin
interface Base
object A : Base
object B : Base

@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mySumOf2")
fun mySumOf(body: () -> Base) = Unit // (1)

@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mySumOf3")
fun mySumOf(body: () -> B) = Unit // (2)

fun main() {
    mySumOf({ A }) // Actual: resolve to (2). ARGUMENT_TYPE_MISMATCH. Actual type is A, but B was expected
                   // Expected: resolve to (1). Green code
}
```

Collection literals don't suffer from this problem.

## Feature interactions

### Feature interaction with `@OverloadResolutionByLambdaReturnType`

```kotlin
@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mySumOf1")
fun mySumOf(body: List<() -> Int>) = Unit // (1)

@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mySumOf2")
fun mySumOf(body: List<() -> Long>) = Unit // (2)

fun main() {
    mySumOf([{ 1L }])
}
```

Technically, since collection literal elements are analyzed almost like regular arguments,
`@OverloadResolutionByLambdaReturnType` in the above case could make the `mySumOf` to resolve to (2).

`@OverloadResolutionByLambdaReturnType` is an experimental feature.
To avoid potential future stabilization complications,
we should make sure that the example above either results in `OVERLOAD_RESOLUTION_AMBIGUITY` or is prohibited in some way
(though it's unclear how to prohibit it).

### Feature interaction with flexible types

Kotlin uses a mechanism of [flexible types](https://kotlinlang.org/spec/type-system.html#flexible-types) to interop with other languages.

In practice, there are only four possible cases of flexible types:
- Nullability. `T..T?`
- Arrays variance from Java. `Array<T>..Array<out T>?`
- Mutability. `MutableList..List`
- `dynamic` in Kotlin/JS. `Nothing..Any?`

The question is what bound should we search `.Companion.of` function in?

**For nullability**, it doesn't matter since `T` and `T?` both have the same static scope.

**For Array variance**, as for nullability, it doesn't matter since `Array<T>` and `Array<out T>?` both have the same static scope.

**For mutability**, we think that it's better to choose an immutable type (upper bound).
The arguments are:
1. Kotlin favors immutability over mutability. If users want to pass a mutable list, they can pass it explicitly via `MutableList.of()`.
2. Even if users were to write the code in modern Java, they would use `java.lang.List.of()`, which returns a read-only list.

The only counterargument is that `MutableList` will crash in fewer cases at runtime.
However, we believe that crashing in such cases is acceptable, as it's better to be explicit about mutability.

**For `dynamic`**, there are two options.
Either fall back to `List` or resolve `.Companion.of` at runtime.
We think that resolving `.Companion.of` is too implicit, and it might lead to accidental runtime failures because of that.
In JavaScript, square brackets always return an `Array`.
Following the principle of least astonishment, it's proposed to fall back to `List`.

All those special cases can be generalized to the common rule for the flexible types to behave as if the upper bound was used instead of the flexible type.
For `dynamic`, it's a true statement because we fall back to `Any` and then [the fallback rule](#fallback-rule-what-if-companionof-doesnt-exist) kicks in.

### Feature interaction with intersection types

Given an intersection type `A & B`,
let's consider a case where types `A` and `B` both declare a proper `operator fun of` in their respective `companion object`s.
It doesn't make sense to prefer either of the operators because generally neither of the operators returns the intersection type `A & B`.

It's proposed to report an error when the collection literal's expected type is an intersection type.

During the implementation and testing, we should pay attention to the following special cases:
1. Intersection with `Any` and [definitely non-nullable type](https://kotlinlang.org/docs/generics.html#definitely-non-nullable-types).
2. Intersections like `List<Int>? & List<Int>`, `MutableList<Int>? & List<Int>`, `MutableList<Int> & List<Int>?`

Usually such intersections should automatically collapse to the appropriate types.
Thus, they should work with collection literals out of the box.
It's just that they require testing.

## Similar features in other languages

**Java.**
While there is a limited support for array literals in Java: `String[] a = {"Hello", "World"};`,
Java explicitly voted against collection literals in favor of `of` factory methods.

> However, as is often the case with language features, no feature is as simple or as clean as one might first imagine, and so collection literals will not be appearing in the next version of Java.

[JEP 269: Convenience Factory Methods for Collections](https://openjdk.org/jeps/269).
[JEP 186: Collection Literals](https://openjdk.org/jeps/186).

**Swift.**
The current proposal for collection literals in Kotlin shares a lot of similarities with the way collection literals work in Swift.

```swift
struct MyList: ExpressibleByArrayLiteral {
    init(arrayLiteral: Int...) {}
}

let foo = [1, 2] // List<Int>
let baz: MyList = [1, 2] // MyList
```

Collection literals in Swift have the same syntax.
Collection literals in Swift are polymorphic by the expected type.
It's possible to express user-defined types via collection literals syntax (`ExpressibleByArrayLiteral` and `ExpressibleByDictionaryLiteral`).

**Scala.**
Scala doesn't offer collection literals.
A common pattern in Scala is to use `apply` operator (In Kotlin, the analogical operator is called `operator fun invoke`).
```scala
object MyList {
  def apply[T](elemns: T*): MyList[T] = new MyList()
}
class MyList[T]

def main(): Unit = {
  val list1 = MyList(1, 2, 3)
  val list2 = List(1, 2, 3)
}
```

Recently, Scala [investigated](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990) the idea of collection literals,
but the idea [was rejected](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-collection-literals/6990/186) for now.

**Python.**
In Python, there is a special syntax for lists and dictionaries,
but due to the language being dynamic, it's not possible to express user-defined types via lists and maps.
```python
list = [1, 2, 3]
dict = {'key1': 'value1', 'key2': 'value2'}
```

**C#.**
C# 12 (released in November 2023) introduced collection expressions.
[Link 1](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/operators/collection-expressions).
[Link 2](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-12.0/collection-expressions).

```C#
using System.Collections;
using System.Runtime.CompilerServices;

internal class Program
{
    public static void Main()
    {
        MyList1<int> list1 = [1, 2];
        MyList2<int> list2 = [1, 2];
        Console.WriteLine(list1);
        Console.WriteLine(list2);
    }
}

class MyList1<T> : IEnumerable<T>
{
    public IEnumerator<T> GetEnumerator() => throw new NotImplementedException();
    IEnumerator IEnumerable.GetEnumerator() => throw new NotImplementedException();
    public void Add(T i) {}
}

[CollectionBuilder(typeof(MyList2Factory), "Create")]
struct MyList2<T>
{
    public IEnumerator<T> GetEnumerator() => throw new NotImplementedException();
}
static class MyList2Factory
{
    public static MyList2<T> Create<T>(ReadOnlySpan<T> s) => new MyList2<T>();
}
```

Collection literals in C# are polymorphic by the expected type.

It's possible to express user-defined types via collection literals syntax in several ways.
Users can either implement `System.Collections.IEnumerable`, or add a `[CollectionBuilder(...)]` attribute.

A notable difference from the Kotlin proposal is that collection literals in C# work only if you specify the type explicitly:

```C#
var list = [1, 2]; // error: There is no target type for the collection expression
```

## Interop with the Java ecosystem

The name for the `operator fun of` is specifically chosen such to ensure smooth Java interop.
Given that we don't support extension `operator fun of`, it becomes more important for Java developers to declare `of` members that satisfy the requirements.
We hope that the JVM ecosystem will follow the [convenience factory methods](https://openjdk.org/jeps/269) pattern that Java started.
For example, one can already find convenience factory `of` methods in popular Java libraries such as Guava.

We perceive Java static `of` function as an `operator fun of` function only if it follows the restrictions mentioned above.
All the restrictions are reasonable, and we think that all collection-builder-like `of` functions will naturally follow those restrictions.

## Interop with Swift

Kotlin provides basic interop with Swift.
Currently, Swift interop is yet in the early active development stage.
At this stage, we are only concerned with exporting Kotlin declarations to make them callable from Swift.

In Swift language, collection literals are expressed via [ExpressibleByArrayLiteral](https://developer.apple.com/documentation/swift/expressiblebyarrayliteral) and [ExpressibleByDictionaryLiteral](https://developer.apple.com/documentation/swift/expressiblebydictionaryliteral) protocols.
The target type should conform to these protocols.
The `operator fun of` function that we define in this KEEP is almost\* compatible with Swift's `ExpressibleByArrayLiteral` protocol.
That's why we can expose the majority of `operator fun of` functions as `ExpressibleByArrayLiteral`.

The only part which is incompatible is the possibility to express `NonEmptyList` via Kotlin's collection literals.
Luckily, such declarations are easily detectable in Kotlin.
When detected, we should restrain from exposing such types as `ExpressibleByArrayLiteral`.

## Tuples

With the addition of collection literals, some users might want to use square brackets syntax to express tuples (well, Kotlin doesn't have tuples, but there are `kotlin.Pair` and `kotlin.Triple`).
The restrictions that we put on the `operator fun of` function don't make it possible to express tuples in a type-safe manner (user has to declare an `of(vararg)` overload).

We don't plan to support the tuples use-case in the first version of collection literals.
But in the future, it's yet unclear if we want to make tuples expressible via square brackets or maybe some other syntax.
So for now, we just want to make sure that we don't accidentally make it impossible to re-use square brackets syntax for tuples.

## Performance

We did performance measurements to make sure that we don't miss any obvious problems in Kotlin's `listOf` implementation compared to `java.util.List.of`.
And to compare the performance of the proposal with [the alternative *granular operators* suggestion](#rejected-proposal-more-granular-operators), since the more granular operators proposal came as an idea to improve performance.
Unironically, a more straightforward *single operator* `of` proposal shows better performance than the granular operators proposal in our benchmarks.

For array-backed collections, the approach to prepopulate array and pass the reference to the array is more performant than consecutively calling `.add` for every element.
Our benchmarks show 3x better performance.

Though, it's correct that for non-array-backed collections (like Sets or Maps), consecutive `.add`/`.put` is more performant since the `vararg`-allocated array is redundant.
But the performance boost of the granular operators proposal for Maps and Sets is less significant (only 2x only for Maps and primarily because of `kotlin.Pair`/`Map.Entry` boxing, not because of unnecessary array allocation) compared to the performance degradation it causes to Lists (3x as it's already been mentioned).
Taking into account that List is the most popular collection container, the choice of the operator convention becomes obvious.

It's worth mentioning that the design decision shouldn't be driven purely by the performance.
In our case, a more accurate design proposal just happens to be more performant than the suggested more granular alternative.
It's a double-win situation.

**Unique vararg statement.** Unlike in Java, in pure Kotlin, we can practically assume that `vararg` parameter is a unique array reference.

**Informal proof.** Given the following function declaration: `fun acceptVararg(vararg x: Int) = Unit`, let's consider the following cases.

-   Case 1. Pass elements of the `vararg` as separate arguments. It's clear that a new unique array is created at the call-site.
-   Case 2. Use spread operator to spread the existing array like this: `acceptVararg(*anotherExistingArray)`. Spread operator copies the array on the call-site.
-   Case 3. Bypass through callable reference like this: `::acceptVararg.invoke(intArrayOf(1, 2))`.
    Indeed, in this case `vararg` parameter cannot be guaranteed to be unique, since the argument is not copied on the call-site.

    Luckily for us, we don't consider this example practical, people don't create references to `listOf`, `List.of`, etc. functions.
    The primary usage for those functions is to call them directly with parameters mapped to the `vararg` parameter like this: `listOf(1, 2, 3)`.

So unless the Kotlin declaration that accepts `vararg` is called from Java, we can practically assume that the `vararg` parameter is unique.

Java doesn't assume that the `vararg` array is unique, so they have to copy it.
And to reduce the array copying performance impact, Java declares 10 `List.of` overloads, in which they don't need to do the copy.
Unnecessary array copying can cause more than 2x performance degradation.
Thanks to the unique vararg statement, Kotlin doesn't have to declare 10 `List.of` overloads like Java did.
The proposal is to proceed with just three overloads as we have them right now in Kotlin.
We add overloads for one and zero elements because they return specialized lists.

All the mentioned numbers can be seen in the raw benchmark data, that can be found in the [`resources/collection-literals-benchmark/`](../resources/collection-literals-benchmark) directory of the KEEP repo root.

### Performance. Companion object allocation

There is one more potential performance issue: `Companion` object allocation.
We haven't taken measurements,
but in practice, it may well be the case that we need to release [the statics proposal](./KEEP-0427-static-member-type-extension.md) before making collection literals stable.

In that case, we could ask users to declare `operator fun of` as a static function instead of `companion object` function.

## IDE support

**1.** The IDEs should implement an inspection to replace `listOf`/`setOf`/etc. with collection literals
where it doesn't lead to overload resolution ambiguity.
It's under the question if the inspection for `listOf`/`setOf` should be enabled by default. (most probably not)

**2.** The IDEs should implement an inspection to replace explicit use of operator function `Type.of` with collection literals
where it doesn't lead to overload resolution ambiguity.
The inspection should be enabled by default.

**3.** We should take into account that ctrl-click on a single character is tricky, especially via keyboard shortcuts.
Though there are some suggestions to improve the situation: [KTIJ-28500](https://youtrack.jetbrains.com/issue/KTIJ-28500).

**4.** Developers who are not familiar with the type-guided semantics of the feature might write code like `val foo = [1, 2].toMutableList()`.
The IDE should catch such cases and suggest to rewrite them to `val foo: MutableList<Int> = [1, 2]` or `val foo = MutableList.of(1, 2)`.

## `listOf` deprecation

We **don't** plan to deprecate any of the `smthOf` functions in Kotlin stdlib.
They are too much widespread, even some third party Kotlin libraries follow `smthOf` pattern.

Besides, this proposal doesn't allow to eliminate `smthOf` pattern completely.
`listOfNotNull` isn't going anywhere.

Unfortunately, introduction of `List.of` functions in stdlib makes the situation harder for newcomers,
since they will have more ways to create collections `listOf`, `List.of`, and `[]`.

For the reference, here is the full list of `smthOf` functions that we managed to find in stdlib:

List like
- `listOf()`
- `listOfNotNull()`
- `setOfNotNull()`
- `mutableListOf()`
- `sequenceOf()`
- `arrayListOf()`
- `arrayOf()`
- `intArrayOf()`, `shortArrayOf()`, `byteArrayOf()`, `longArrayOf()`, `ushortArrayOf()` // etc
- `setOf()`
- `mutableSetOf()`
- `hashSetOf()`
- `linkedSetOf()`
- `sortedSetOf()`

Map like
- `mapOf()`
- `mutableMapOf()`
- `hashMapOf()`
- `sortedMapOf()`

Other collections
- `arrayOfNulls()`
- `arrayOf().copyOf()` // vs. spread in collection literals?
- `mutableListOf<String>().copyOf()` // red code UNRESOLVED_REFERENCE. We don't have a function to copy lists in our stdlib

Others, not collections, but things that could be literals of their own
- `lazyOf()`
- `typeOf<String>()` // Defined in kotlin-reflect. returns KType
- `enumValueOf<E>(String)`
- `CharDirectionality.valueOf(Int)` // Int to Enum entry
- `java.lang.Boolean.valueOf(true)` // Java
- `java.lang.Byte.valueOf("")` // Java
- `java.math.BigInteger.valueOf(1l)` // Java

## Change to stdlib

The following API change is proposed to stdlib:
```diff
diff --git a/libraries/stdlib/src/kotlin/Collections.kt b/libraries/stdlib/src/kotlin/Collections.kt
index e692a8c05ede..7509cc55b9c6 100644
--- a/libraries/stdlib/src/kotlin/Collections.kt
+++ b/libraries/stdlib/src/kotlin/Collections.kt
@@ -175,6 +175,13 @@ public expect interface List<out E> : Collection<E> {
      * Structural changes in the base list make the behavior of the view undefined.
      */
     public fun subList(fromIndex: Int, toIndex: Int): List<E>
+
+    public companion object {
+        public operator fun <T> of(vararg elements: T): List<T>
+        public operator fun <T> of(element: T): List<T>
+        public operator fun <T> of(): List<T>
+    }
 }

 /**
```

The analogical API changes are proposed in the following classes:
- `kotlin.collections.ArrayList`
- `kotlin.collections.MutableList`
- `kotlin.collections.Set`
- `kotlin.collections.HashSet`
- `kotlin.collections.LinkedHashSet`
- `kotlin.collections.MutableSet`
- `kotlin.sequences.Sequence`
- `kotlin.Array`, `kotlin.IntArray`, `kotlin.LongArray`, `kotlin.ShortArray`, `kotlin.UIntArray` etc.

Please note that on JVM, `kotlin.collections.List` is a mapped type.
Which means that we cannot change the runtime `java.util.List`.
It's suggested to do mapped `companion object` in the way similar to `Int.Companion` (which maps to `kotlin.jvm.internal.IntCompanionObject`).

### Semantic differences between Kotlin and Java factory methods

- In Java, `java.util.List.of(null)` throws NPE.
- In Kotlin, `listOf(null)` returns `List<Nothing?>`.

Given that Kotlin has nullability built-in, it's proposed for `kotlin.collections.List.of(null)` to work similarly to `listOf(null)`.

- In Java, duplicated elements in `java.util.Set.of(1, 1)` cause `IllegalArgumentException`.
- In Kotlin, `setOf(1, 1)` returns a Set that consists of a single element.

Since it might be very unexpected for expression `Set.of(compute1(), compute2())` to throw an exception, it's proposed for `kotlin.collections.Set.of(1, 1)` to work similarly to `setOf(1, 1)`.

Both Java and Kotlin return unmodifiable collections.
Though, in Kotlin, we found a "problem" that `mapOf(vararg)` and `setOf(vararg)` don't do that.
The reasons for that are yet unknown, it could be an oversight, or it could be a deliberate choice because `Collections.unmodifiableMap` can be slower, since it needs to create `UnmodifiableEntry` for every element.

## Empty collection literal

`emptyList` stdlib function declares `List<T>` as its return type, not `List<Nothing>`.
Similar to `emptyList`, if the expected type doesn't provide enough information for what the collection literal element should be,
Kotlin compiler should issue a compilation error asking for an explicit type specification.

```kotlin
fun main() {
    val list = [] // Compilation error. Can't infer List<T> generic parameter T
}
```

## Future evolution

In the future, Kotlin may provide `@VarargOverloads` (similar to `@JvmOverloads`), or `inline vararg` to eliminate unnecessary array allocations even further.
But it's relevant only for Maps and Sets.

### Theoretical possibility to support List vs. Set overloads in the future

We don't plan to, but if we ever change our mind, it's possible to support `List` vs `Set` kinds of overloads in the way similar to how Kotlin prefers `Int` overload over `Long` overload:
```kotlin
fun foo(a: Int) = Unit // (1)
fun foo(a: Long) = Unit // (2)

fun test() {
    foo(1) // (1)
}
```

On the second stage of overload resolution, `Int` is considered more specific than `Long`, `Short`, `Byte`.
In the similar way, `List` can be theoretically made more specific than any other type that can represent collection literals.

But right now, we **don't plan** to do that, since both `List` and `Set` overloads can equally represent the main overload.

### Map literals

In addition to special syntax for collection literals, it'd be natural to add a special syntax for map literals.

```kotlin
val map = ["key": 1] // Map<String, Int>
```

It's always possible to add a special syntax for maps in future versions of Kotlin.
To start with, we want to concentrate on collections.
That's why we limit the proposal only for collections for now.

We won't even add a `Map.Companion.of` in Kotlin stdlib.
Such code won't work:

```kotlin
val map: Map<String, Int> = ["key" to 1] // type mismatch. List<Pair<String, Int>> is not subtype of Map<String, Int>
```

There are three primary reasons why map literals didn't make it into this proposal.

**1. Problematic interop with Java.**
[Java declares](https://docs.oracle.com/en/java/javase/23/docs/api/java.base/java/util/Map.html#of()) up to 11 `java.util.Map.of(K, V, K, V, ...)` overloads with even number of parameters and one more `ofEntries` overload with `vararg Map.Entry` parameter.
It's hard to come up with reasonable operator convention for such a naming scheme.

**2. Boxing performance problem.**
The ideal solution from the design perspective would be:

```kotlin
class Map<K, V> {
    companion object {
        operator fun <K, V> of(): Map<K, V> = TODO("not implemented")
        operator fun <K, V> of(element: Map.Entry<K, V>): Map<K, V> = TODO("not implemented") // Or `kotlin.Pair` instead of `Map.Entry`
        operator fun <K, V> of(vararg elements: Map.Entry<K, V>): Map<K, V> = TODO("not implemented") // Or `kotlin.Pair` instead of `Map.Entry`
    }
}
```

Unfortunately,
it suffers from unnecessary objects allocations
that are observable in JMH benchmark both from the speed and garbage collection perspectives.

**3. Map.Entry is an interface, not a class.**
The fact that it's an interface, makes it unclear an instance of what type should be constructed on the call site.

```kotlin
val map: Map<Int, String> = [1: "value"] // is desugared to Map.of(/* How to create an instance? */)
```

## Rejected proposals

This section lists some common proposals and ideas that were rejected

### Rejected proposal: always infer collection literals to `List`

To simplify the feature and reduce mental overhead imposed by contextual semantics,
it was suggested to always infer collection literals syntax to `List`.

The proposal was rejected because it's too inflexible.

1.  We won't be able to reuse the syntax for `Set`, `Array`, `IntArray`, etc.
    It's especially important to be able to reuse the syntax for arrays; otherwise, [it will be a breaking change](#collection-literals-in-annotations).
2.  Authors of collection libraries (kotlinx.collections.immutable, Guava, etc.) won't be able to leverage the syntax.
3.  In Kotlin, we keep language features open and composable.
    Just as `suspend` only transforms functions while `async`/`await` is implemented in kotlinx.coroutines library; collection literals adds syntax, leaving implementation to libraries.

Overall, we don't think that the contextual semantics brings serious mental overhead.
Contextual semantics of collection literals has the same mental overhead as the following example:

```kotlin
someFunction(complicatedExpression()) // You don't know what is the return type of `complicatedExpression()`
someFunction { println(it) }          // You don't know what is the type of lambda
someFunction([1, 2])                  // You don't know what is the type of `[1, 2]`
```

### Rejected proposal: more granular operators

In [the proposal](#proposal), we give users possibility to add overloads for the `vararg` operator.
It's clear that we do so to avoid unnecessary array allocations at least for most popular cases when collection sizes are small.
What if instead of a single operator, collections had to declare three operators: `createCollectionBuilder`, `plusAssign`, `freeze` (we even already have the `plusAssign` operator!)?

Please note that we need the third `freeze` operator to make it possible to create collection literals for immutable collections.

Then the use-site could be desugared by the compiler:
```kotlin
fun main() {
    val s = [1, 2, 3]
    // desugared to
    val s = run {
        val tmp = createCollectionBuilder<Int>()
        tmp.plusAssign(1)
        tmp.plusAssign(2)
        tmp.plusAssign(2)
        List.freeze(tmp)
    }
}
```

The declaration site looks like:
```kotlin
class List<T> {
    companion object {
        operator fun createCollectionBuilder<T>(capacity: Int): MutableList<T> = ArrayList(capacity)
        operator fun <T> freeze(a: MutableList<T>): List<T> = a.toList()
    }
}

class MutableList<T> {
    fun add(t: T): Boolean = TODO()

    companion object {
        operator fun createCollectionBuilder<T>(capacity: Int): MutableList<T> = ArrayList(capacity)
        operator fun <T> freeze(a: MutableList<T>): MutableList<T> = this
    }
}

operator fun <T> MutableList<T>.plusAssign(t: T) { add(t) }
```

There are several problems.
1.  The design with three operators is simply more complicated.
    Three operators become more scattered than a single operator, it becomes unclear where the IDEs should navigate on ctrl-click.
    So if possible, we should prefer a more straightforward single operator design.
2.  The type of the collection builder is exposed in public API (return type of `createCollectionBuilder`).
    The type of the builder is an implementation detail, and users might want to change it over time.
3.  It becomes harder or unreasonable to return different collection types/builder types depending on the number of elements in the collection literal.
4.  Performance.
    For array-backed collections, the approach to prepopulate an array and safe the reference to the array is more performant than calling `.add` for every element.
    The most popular collection is List.
    List is array-backed.
    Though it's correct that consecutive `.add` calls is more performant for non-array-backed collections like Map or Set.
    Please see the [Performance](#performance) section.

### Rejected proposal: more positions with the definite expected type

It would be desirable to extend the list of positions with the expected type to include equality checks and conditions of `when` branches with a subject.

```kotlin
fun main() {
    val foo: List<Long> = List.of(1L, 2)
    println(foo == [1, 2]) // Fallback to List<Int> according to the current proposal
    when (foo) {
        [1, 2] -> println("it works!") // Fallback to List<Int> according to the current proposal
        else -> error("oh no :(")
    }
}
```

According to the current proposal, the code above will print `false` and fail at runtime with `oh no :(` message,
but it would be desirable to make it work in the opposite way.

Unfortunately, we can't include those positions to the list of positions with the expected type,
because if you replace the collection literal with an explicit `of` function invocation,
it won't take advantage of the expected type by the current Kotlin rules.
The following code prints `false`, unfortunately:

```kotlin
fun main() {
    val foo: List<Long> = List.of(1L, 2)
    println(foo == List.of(1, 2)) // false
}
```

Or even consider the following example:

```kotlin
fun <T> id(t: T): T = t

fun main() {
    val foo: List<Long> = List.of(1L, 2)
    println(foo == [1, 2]) // "true" if we make equality a position with the expected type
    println(foo == id([1, 2])) // "false" by the current Kotlin rules in any case
}
```

**Open question.**
For now, we may decide to disallow to syntactically write collection literals in equality and when conditions.

### Rejected proposal: self-sufficient collection literals with defined type

The type of collection literals is inferred from the expected type.
Unfortunately, that may lead to overload resolution ambiguity when collection literal is used in the argument position.

```kotlin
fun foo(a: Set<Int>) = Unit
fun foo(a: List<Int>) = Unit

fun test() {
    foo([1, 2]) // overload resolution ambiguity
    // but what if...
    foo(List [1, 2])
}
```

The proposal was to allow writing `List [1, 2]`.
For now, the proposal was rejected because users can anyway desugar `[1, 2]` to `List.of(1, 2)` or `listOf(1, 2)`.

Note that it's possible to abuse `operator fun get` (which could be an extension function) to achieve `List [1, 2]` syntax.
We want to reserve that syntax, that's why it's proposed to forbid to simultaneously declare `operator fun get` and `operator fun of`.
Since `operator fun of` can't be declared as an extension function, it's always possible to detect simultaneous declaration of the operators.

### Rejected proposal: use improved overload resolution algorithm only to refine non-empty overload candidates set on the fixated tower level

**Fact.** `foo` in the following code resolves to (2). Although (1) is a more specific overload, (2) is preferred because it's more local.

```kotlin
fun foo(a: Int) {} // (1)
class Foo {
    fun foo(a: Any) {} // (2)
    fun test() {
        foo(1) // Resolves to (2)
    }
}
```

It was proposed to use an improved overload resolution algorithm for collection literals to refine already non-empty overload candidates set to avoid resolving to functions which are "too far away".

```kotlin
fun foo(a: List<Int>) {} // (1)
class Foo {
    fun foo(a: List<String>) {} // (2)
    fun test() {
        foo([1]) // It's proposed to resolve to (2) and fail with TYPE_MISMATCH error
    }
}
```

The proposal was rejected because the analogical overload resolution for references already allows resolving to functions which are too far away.

```kotlin
fun x(a: Int) = Unit
fun foo(a: (Int) -> Unit) {} // (1)
class Foo {
    fun foo(a: (String) -> Unit) {} // (2)
    fun test() {
        foo(::x) // Resolves to (1)
    }
}
```

We prefer to keep the language consistent rather than "fixing" the behavior, even if the suggested behavior is generally better (which is not clear, if it's really better)

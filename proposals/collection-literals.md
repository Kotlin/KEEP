# Collection Literals

* **Type**: Design proposal
* **Author**: Nikita Bobko
* **Contributors**: Alejandro Serrano Mena, Denis Zharkov, Marat Akhin, Mikhail Zarechenskii
* **Issue:** [KT-43871](https://youtrack.jetbrains.com/issue/KT-43871)
* **Prototype:** https://github.com/JetBrains/kotlin/tree/bobko/collection-literals
* **Discussion**: todo

Collection literal is a well-known feature among modern programming languages.
It's a syntactic sugar built-in into the language that allows creating collection instances more concisely and effortlessly.
In the simplest form, if users want to create a collection, instead of writing `val x = listOf(1, 2, 3)`, they could write `val x = [1, 2, 3]`.

## Table of contents

- [Motivation](#motivation)
- [Proposal](#proposal)
- [Overload resolution motivation](#overload-resolution-motivation)
  - [Overload resolution and type inference](#overload-resolution-and-type-inference)
  - [Operator function `of` restrictions](#operator-function-of-restrictions)
  - [Operator function `of` allowances](#operator-function-of-allowances)
- [Fallback rules. What if `Companion.of` doesn't exist](#fallback-rules-what-if-companionof-doesnt-exist)
  - [Nested collection literals](#nested-collection-literals)
- ["Contains" optimization](#contains-optimization)
- [Similarities with `@OverloadResolutionByLambdaReturnType`](#similarities-with-overloadresolutionbylambdareturntype)
- [Feature interaction with `@OverloadResolutionByLambdaReturnType`](#feature-interaction-with-overloadresolutionbylambdareturntype)
- [Feature interaction with flexible types](#feature-interaction-with-flexible-types)
- [Feature interaction with intersection types](#feature-interaction-with-intersection-types)
- [Feature interaction with PCLA](#feature-interaction-with-pcla)
- [Similar features in other languages](#similar-features-in-other-languages)
- [Interop with Java ecosystem](#interop-with-the-Java-ecosystem)
- [Tuples](#tuples)
- [Performance](#performance)
- [IDE support](#ide-support)
- [listOf deprecation](#listof-deprecation)
- [Change to stdlib](#change-to-stdlib)
  - [Semantic differences between Kotlin and Java factory methods](#semantic-differences-between-kotlin-and-java-factory-methods)
- [Empty collection literal](#empty-collection-literal)
- [Future evolution](#future-evolution)
  - [Theoretical possibility to support List vs Set overloads in the future](#theoretical-possibility-to-support-list-vs-set-overloads-in-the-future)
- [Rejected proposals and ideas](#rejected-proposals-and-ideas)
  - [Rejected proposal: more granular operators](#rejected-proposal-more-granular-operators)
  - [Rejected idea: self-sufficient collection literals with defined type](#rejected-idea-self-sufficient-collection-literals-with-defined-type)
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
4.  Special syntax for collection literals helps to resolve the `emptyList`/`listOf` hassle.
    Whenever the argument list in `listOf` reduces down to zero, some might prefer to clean up the code to change `listOf` to `emptyList`.
    And vice versa, whenever the argument list in `emptyList` needs to grow above zero, the programmer needs to replace `emptyList` with `listOf`.
    It creates a small hussle of `listOf` to `emptyList` back and forth replacement.
    It's by no means a big problem, but it is just a small annoyance, which is nice to see to be resolved by the introduction of collection literals.

The feature brings more value to newcomers rather than to experienced Kotlin users and should target the newcomers primarily.

Since the biggest feature value is "aesthetics", "ergonomics" and "readability",
all of which are hard to measure and subjective, it makes sense to see "before/after" code examples to feel the feature better:
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
    if (asterisk[row] != emptyList<Int>()) { /* ... */ }
// after 5
    if (asterisk[row] != []) { /* ... */ }


// before 6
    it[AnalysisFlags.optIn] = optInList + listOf("kotlin.ExperimentalUnsignedTypes")
// after 6
    it[AnalysisFlags.optIn] = optInList + ["kotlin.ExperimentalUnsignedTypes"]


// before 7
    for (key in listOf(argument.value, argument.shortName, argument.deprecatedName)) {
        if (key.isNotEmpty()) put(key, argumentField)
    }
// after 7
    for (key in [argument.value, argument.shortName, argument.deprecatedName]) {
        if (key.isNotEmpty()) put(key, argumentField)
    }


// before 8
    override fun getMimeTypes(): List<String> = listOf("text/x-kotlin")
// after 8
    override fun getMimeTypes(): List<String> = ["text/x-kotlin"]


// before 9
    fun <D> MutableMap<String, MutableSet<D>>.initAndAdd(key: String, value: D) {
        this.compute(key) { _, maybeValues -> (maybeValues ?: mutableSetOf()).apply { add(value) } }
    }
// after 9
    fun <D> MutableMap<String, MutableSet<D>>.initAndAdd(key: String, value: D) {
        this.compute(key) { _, maybeValues -> (maybeValues ?: []).apply { add(value) } }
    }


// before 10
    modules.mapNotNullTo(hashSetOf()) { environment.findLocalFile(it.getOutputDirectory()) }
    sourcesByModuleName.getOrPut(moduleName) { mutableSetOf() }.add(sourceFile)
// after 10
    // Can't be expressed via collection literals
```

## Proposal

**Informally**, the proposal strives to make it possible for users to use collection literals syntax to express user-defined types `val foo: MyCustomList<Int> = [1, 2, 3]`.
And when the *expected type* (See the definition below) is unspecified, expression must fall back to the `kotlin.List` type: `val foo = [1, 2, 3] // List<Int>`.

It's proposed to use square brackets because the syntax is already used in Kotlin for array literals inside annotation constructor arguments,
because it's the syntax a lot of programmers are already familiar with coming from other programming languages,
and because it honors mathematical notation for matrices.

```kotlin
class MyCustomList<T> {
    companion object { fun <T> of(vararg elements: T): MyCustomList<T> = TODO() }
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
Expressions have some type, which we call "the actual type" or just "the type".
Those expressions fill in the "holes".
And the "holes" only accept expressions of a particular type.
The particular type that is accepted by the hole is called *the expected type*.

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

Another KEEP proposal that heavily uses the notion of *expected type* is [Improve resolution using expected type](./improved-resolution-expected-type.md).

**Definition.**
_`Type` static scope_ is the set that contains member callables (functions and properties) of `Type.Companion` type (`Type.Companion` is a companion object),
or static members of the type if the type is declared in Java.
(Extension on `Type.Companion` are excluded on purpose)

Before the collection literal could be used at the use-site, an appropriate type needs to declare `operator fun of` function in its _static scope_.
The `operator fun of` functions must adhere to [the restrictions](#operator-function-of-restrictions).

Once a proper `operator fun of` is declared, the collection literal can be used at the use-site.
1.  When the collection literal is used in the position of arguments, similarly to lambdas and callable references, collection literal affects the overload resolution of the "outer call".
    See the section dedicated to [overload resolution](#overload-resolution-motivation).
2.  When the collection literal is used in the position with definite *expected type*, the collection literal is literally desugared to `Type.of(expr1, expr2, expr3)`,
    where `Type` is the definite *expected type*.

    The following positions are considered positions with the definite *expected type*:
    - Conditions of `when` expression with a subject
    - Explicit `return`, single-expression functions (if type is specified), and last expression of lambdas
    - Equality checks (`==`, `!=`)
    - Assignments and initializations
3.  In all other cases, it's proposed to desugar collection literal to `List.of(expr1, expr2, expr3)`.
    The precise fallback rules are described in a [separate section](#fallback-rules-what-if-companionof-doesnt-exist).

Some examples:
```kotlin
class MyCustomList<T> {
    companion object { operator fun <T> of(vararg t: T): MyCustomList<T> = TODO() }
}
fun main() {
    val foo: MyCustomList<Int> = [1, 2, 3]
    val bar: MyCustomList<String> = ["foo", "bar"]
    val baz = [1, 2, 3] // List<Int>
    when (foo) {
        [1, 2, 3] -> println("true") // MyCustomList<Int>
        else -> println("false")
    }
    if (foo == [1, 2, 3]) println("true") // MyCustomList<Int>
    var mutable: Set<Int> = [1, 2, 3] // Set<Int>
    mutable = [4, 5] // Set<Int>
}

fun foo(): MyCustomList<Int> = [1, 2, 3]
```

Please note that it is not necessary for the type to extend any predifined type in the Kotlin stdlib (needed to support `kotlin.Array<T>` type),
nor it is necessary for the user-defined type to declare mandatory generic type parameters (needed to support specialized arrays like `kotlin.IntArray`, `kotlin.LongArray`, etc.).

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
> When we say `List` vs `Set` kinds of overloads, we mean all sorts of overloads where the "outer" type is different.
> When we say `List<String>` vs `List<File>` kinds of overloads, we mean all sorts of overloads where the "inner" type is different.

-   `List<String>` vs `Set<String>` typically emerges when one of the overloads is the "main" overload, and another one is just a convenience overload that delegates to the "main" overload.
    Such overloads won't be supported because it's generally not possible to know which of the overloads is the "main" overload,
    and because collection literal syntax doesn't make it possible to syntactically distinguish `List` vs `Set`.
    But if we ever change our mind, [it's possible to support `List` vs `Set` kind of overloads](#theoretical-possibility-to-support-List-vs-Set-overloads-in-the-future) in the language in backwards compatible way in the future.
-   `List<String>` vs `List<File>` is self-explanatory (for example, consider the `flushFiles` example above).
    Such overloads should be and will be supported.
-   `List<String>` vs `Set<File>`. Both "inner" and "outer" types are different.
    This pattern is less clear and generally much rarer.
    The pattern may emerge accidentally when different overloads come from different packages.
    Or when users don't know about `@JvmName`, so they use different "outer" types to circumvent `CONFLICTING_JVM_DECLARATIONS`.
    This pattern will be supported just because of the general approach we are taking that distinguishes "inner" types.

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

Conceptually, overload resolution algorithm consists of two stages:
1.  Filter out all the overload candidates that certainly don't fit based on types of the arguments.
    (it's important to understand that we don't keep the candidates that fit, but we filter out those that don't)
    https://kotlinlang.org/spec/overload-resolution.html#determining-function-applicability-for-a-specific-call
2.  Of the remaining candidates, we keep the most specific ones by comparing every two distinct overload candidates.
    https://kotlinlang.org/spec/overload-resolution.html#choosing-the-most-specific-candidate-from-the-overload-candidate-set

For our case to distinguish `List<String>` vs `List<File>` kinds of overloads (see the [Overload resolution motivation](#overload-resolution-motivation) section), the first stage of overload resolution is the most appropriate one.
That's exactly what we want to do - to filter out definitely inapplicable `outerCall` overloads.

Given the following example: `outerCall([expr1, [expr2], expr3, { a: Int -> }, ::x], expr4, expr5)`,
similar to lambdas and callable references, collection literal expression type inference is postponed.
Contrary, elements of the collection literal are analyzed in the way similar to how other arguments of `outerCall` are analyzed, which means:
1.  If collection literal elements are lambdas, or callable references, their analysis is postponed.
    Only number of lambda parameters and lambda parameter types (if specified) are taken into account of overload resolution of `outerCall`.
2.  If collection literal elements are collection literals themselves, then we descend into those literals and recursively apply the same rules.
3.  All other collection literal elements are *plain arguments*, and they are analyzed in [so-called *dependent* mode](https://github.com/JetBrains/kotlin/blob/master/docs/fir/inference.md).

For every overload candidate, when a collection literal maps to its appropriate `ParameterType`:
1.  We find `ParameterType.Companion.of(vararg)` function.
    [operator fun of restrictions](#operator-function-of-restrictions) either guarantee us that the `of` function exists and unique,
    or [fallback rules](#fallback-rules-what-if-companionof-doesnt-exist) kick in.
2.  We remember the parameter of the single `vararg` parameter.
    We will call it _CLET_ (collection literal element type).
    We also remember the return type of that `ParameterType.of(vararg)` function.
    We will call it _CLT_ (collection literal type).
3.  At the first stage of overload resolution of `outerCall` (when we filter out inapplicable candidates), we add the following constraints to the constraint system of `outerCall` candidate:
    1. For each collection literal element `e`, we add `type(e) <: CLET` constraint.
    2. We also add the following constraint: `CLT <: ParameterType`.

Once all *plain* arguments are analyzed (their types are inferred in *dependent* mode), and all recursive *plain* elements of collection literals are analyzed,
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

Once the particular overload for `outerCall` is chosen, we know what definite *expected type* collection literal maps to.
We desugar collection literal to `DefiniteExpectedType.of(expr1, expr2, expr3)`, and we proceed to resolve overloads of `DefiniteExpectedType.of` according to regular Kotlin overload resolution rules.

### Operator function `of` restrictions

**The overall goal of the restrictions:**
Make it possible to extract type restrictions for the elements of the collection literal without the necessity of the full-blown real overload resolution for `operator fun of` function.
Given only the "outer" type `List<Int>`/`IntArray`/`Foo`, we should be able to infer collection literal element types.
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
No more, no less.

There are 2 permitted cases:
1.  The `vararg` parameter is the single parameter of its containing `of` function
2.  The `vararg` parameter is the latest parameter of its containing `of` function.
    All parameters that come in front of the `vararg` parameter must all have the same type as the `vararg` parameter.

The overload is considered the "main" overload, and it's the overload we use to extract type constraints from for the means of `outerCall` overload resolution.
Please remember that we treat collection literal argument as a "postponed argument" (similar to lambdas and callable references).
First, We do the overload resolution for the `outerCall` and only once a single applicable candidate is found, we use its appropriate parameter type as an *expected type* for the collection literal argument expression.

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
All `of` overloads must have the return type equal by `ClassId` to the type in which _static scope_ the overload is declared in.

The `ClassId` of a type is its typed fully qualified name.
It's a list of typed tokens, where every token represents either a name of the package or a name of the class.
"Typed" here means that package named "foo" doesn't equal to the class named "foo".

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
The only difference that `of` overloads are allowed to have is "number" of parameters (`vararg` is considered an infinite number of params).

> Technically, restriction 5 is a superset of restriction 4, but we still prefer to mention restriction 4 separately.

Which means that the types of the parameters must be the same, and all type parameters with their bounds must be the same.

**Restriction 6.**
All `of` overloads must have no extension/context parameters/receivers.

We forbid them to keep the mental model simpler, and since we didn't find major use cases.
Since all those "implicit receivers" affect availability of the `of` function, it'd complicate `outerCall` overload resolution, if we allowed "implicit receivers".

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
public class IBase { public static IBase of(int... x) { return null; } }
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

Rules for static members inheritance don't change in Java and Kotlin should keep respecting them for collection literals:

```kotlin
fun main() {
    val a: ExtendsClass = [1, 2] // green
    val a: ImplementsInterface = [1, 2] // red
}
```

## Fallback rules. What if `Companion.of` doesn't exist

There are three cases.

**Case 1.**
```kotlin
class Foo
fun main() {
    val foo: Foo = [1, 2]
}
```

A definite *expected type* is known,
but `.Companion.of` function is not declared.
It's a trivial case, it's just a matter of what diagnostic to report.

**Case 2.**
```kotlin
fun outer(a: Iterable<String>) = Unit // (1)
fun outer(a: Iterable<Int>) = Unit // (2)
fun main() {
    outer([1]) // resolves to (2)
}
```

If during overload resolution, we didn't find `Type.Companion.of` function, but `ParameterType` is the supertype of `List<Nothing>`,
we use `List.Companion.of` to extract *CLET* and *CLT* for the means of [overload resolution](#overload-resolution-and-type-inference).

In the above example, `ParameterType == Iterable<String>`.

A considered alternative: Declare `Iterable.Companion.of`, `Collection.Companion.of` functions in stdlib.
Downsides:
**(1)** For every new future supertype of `List` we should also add `of` functions, which we may forget, or it may not make sense.
**(2)** `Any.Companion.of` doesn't make sense.

To maintain consistency, the "supertype of `List<Nothing>`" rule should continue to apply for examples where definite *expected type* is known:

```kotlin
val list1: Any = [1, 2] // Green since Any is supertype of List<Noting>. The code is desugared to List.of(1, 2)
val list2: Iterable<Any> = [1, 2] // Green
val list3: Iterable<Int> = [1, 2] // Green
val list4: Collection<Int> = [1, 2] // Green
```

**Case 3.**
```kotlin
fun <T : List<String>> outer(a: T) = Unit
fun <T : List<Int>> outer(a: T) = Unit

fun outer2(a: List<String>) = Unit // (1)
fun outer2(a: List<Int>) = Unit // (2)

fun <T> id(t: T): T = t

fun main() {
    outer([1]) // overload resolution ambiguity
    outer2([1, 2, 3]) // resolves to (2)
    outer2(id([1, 2, 3])) // overload resolution ambiguity
}
```

It's the worst case.
Unfortunately, during overload resolution type variables are not yet fixated,
which means that it's yet impossible to check whether `Type.Companion.of` exists or not.
It's suggested to just not support such cases.
Overload resolution won't work in such cases, unfortunately.

But it's important to highlight that if the overload resolution successfully completes because there is only 1 applicable candidate,
the type inference should infer types since it has all the necessary information. Examples:

```kotlin
fun <T : List<Int>> outer(a: T) = Unit

fun <T : List<Int>> outer2(a: T) = Unit // (1)
fun outer2(a: List<String>) = Unit // (2)

fun <T : List<String>> outer3(a: T, b: String) = Unit // (3)
fun <T : List<Int>> outer3(a: T, b: Int) = Unit // (4)

fun main() {
    outer([1]) // Only one candidate, green code
    outer2([1]) // Candiate (2) is not applicable, resolve to candidate (1) => green code
    outer3([1], 1) // Successfully resolves to (4) thanks to second parameter
}
```

### Nested collection literals

Given:
1. Very often `operator fun of` will be declared using generic parameter: `operator fun <T> of(vararg t: T): ...`
2. Overload resolution doesn't work when collection literal is substituted to generic parameters (see the previous section)

Because of that it's important to acknowledge that [improved overload resolution](#overload-resolution-and-type-inference) won't work for nested collection literals if the outer type uses generic parameter in its `operator fun of` declaration.

```kotlin
fun outer(a: List<String>) = Unit // (1)
fun outer(a: List<File>) = Unit // (2)

fun outerNested(a: List<List<String>>) = Unit
fun outerNested(a: List<List<File>>) = Unit

fun main() {
    outer([""]) // resolves to (1)
    outerNested([[""]]) // overload resolution ambiguity
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
if (tmp == "y" || tmp == "Y" || tmp == "yes" || tmp == "Yes" || tmp == null) {
    // ...
}
```

For the `x in [y1, y2, y3, ...]` code pattern, the IDE should also try to detect duplicated elements and issue a warning if there are some.

## Similarities with `@OverloadResolutionByLambdaReturnType`

The suggested algorithm of overload resolution for collection literals shares similarities with `@OverloadResolutionByLambdaReturnType`.

Similar to how "the guts" of the lambda (the type of the return expression) are analyzed for the sake of the `outerCall` overload resolution,
"the guts" of collection literal (elements of the collection literal) are analyzed for the same purpose.
The big difference though is that analysis of "the guts" of collection literal doesn't depend on some "input types" coming from the signature of the particular `outerCall`,
while in the case of lambdas it's different.
You need to know the types of the lambda parameters (so-called "input types") to infer the return type of the lambda.

That's why in the case of collection literals, we can jump right into the analysis of its elements, and only postpone the overload resolution of the `operator fun of` function.

Another big difference is what stage the improved overload resolution by lambda return type or by collection literal element type kicks in.

Improved overload resolution by collection literal element type naturally merges itself into the first stage of overload resolution (candidates filtering), where it logically belongs to.
Contrary, `@OverloadResolutionByLambdaReturnType` is a separate stage of overload resolution that kicks in after choosing the most specific candidate.

The fact that `@OverloadResolutionByLambdaReturnType` is just slapped on top of regular overload resolution algorithm can be observed in the following example:
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

> The section is written for the sake of increasing the understanding of the mental model

## Feature interaction with `@OverloadResolutionByLambdaReturnType`

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

Technically, since collection literal elements are analyzed "like regular arguments",
`@OverloadResolutionByLambdaReturnType` in the above case could make the `mySumOf` to resolve to (2).

`@OverloadResolutionByLambdaReturnType` is an experimental feature.
To avoid potentail future stabilization complications,
we should make sure that the example above either results in `OVERLOAD_RESOLUTION_AMBIGUITY` or is prohibited in some way
(though it's unclear how to prohibit it).

## Feature interaction with flexible types

Kotlin uses a mechanism of [flexible types](https://kotlinlang.org/spec/type-system.html#flexible-types) to interop with other languages.

In practice, there are only 3 possible cases of flexible types:
- Nullability. `T..T?`
- Mutability. `MutableList..List`
- `dynamic` in Kotlin/JS. `Nothing..Any?`

The question is what bound should we search `.Companion.of` function in?

**For nullability**, it doesn't matter since `T` and `T?` both have the same static scope.

**For mutability**, we think that it's better to choose an immutable type (upper bound).
The arguments are:
1. Kotlin favors immutability over mutability. If users want to pass a mutable list, they can pass it explicitly via `MutableList.of()`.
2. Even if users were to write the code in modern Java, they would use `java.lang.List.of()`, which returns a read-only list.
There is only a single counterargument: `MutableList` will definitely crash in lower number of cases at runtime.
We think that it's fine to crash at runtime in such cases, it's better to be explicit about mutable lists in such cases.

**For `dynamic`**, there are two options.
Either fall back to `List` or resolve `.Companion.of` at runtime.
We think that resolving `.Companion.of` is too implicit, and it might lead to accidental runtime failures because of that.
In JavaScript, square brackets always return an `Array`.
Following the principle of least astonishment, it's proposed to fall back to `List`.

All these special cases can be generalized to the common rule for the flexible types to behave as if the upper bound was used instead.
For `dynamic` it's a true statement because we fall back to `Any` and then [Any is supertype of `List<Nothing>` fallback kicks in](#fallback-rules-what-if-companionof-doesnt-exist).

## Feature interaction with intersection types

Given an intersection type `A & B`,
let's consider a case where types `A` and `B` both declare a proper `operator fun of` in the respective `companion object` inside of them.
It doesn't make sense to prefer either of the operators because neither of them returns the intersection type `A & B`.

It's proposed to always report an error when the *expected type* of collection literal is an intersection type.

## Feature interaction with PCLA

todo

## Similar features in other languages

**Java.**
Java explicitly voted against collection literals in favor of `of` factory methods.

> However, as is often the case with language features, no feature is as simple or as clean as one might first imagine, and so collection literals will not be appearing in the next version of Java.

[JEP 269: Convenience Factory Methods for Collections](https://openjdk.org/jeps/269).
[JEP 186: Collection Literals](https://openjdk.org/jeps/186).

**Swift.**

Kotlin proposal for collection literals shares a lot of similarities with the way collection literals are done in Swift.

Swift supports collection literals with the similar suggested syntax.
Swift also allows 

todo

## Interop with the Java ecosystem

The name for the `operator fun of` is specifically chosen such to ensure smooth Java interop.
Given that we don't support extension `operator fun of`, it becomes more important for Java developers to declare `of` members that satisfy the requirements.
We hope that the JVM ecosystem will follow the "Convenience factory methods" pattern that Java started.
For example, one can already find convenience factory "of" methods in popular Java libraries such as Guava.

We perceive Java static `of` function as an `operator fun of` function only if it follows the restrictions mentioned above.
All the restrictions are reasonable, and we think that all "collection builder like" of functions will naturally follow those restrictions.

## Tuples

With the addition of collection literals, some users might want to use square brackets syntax to express tuples (well, Kotlin doesn't have tuples, but there are `kotlin.Pair` and `kotlin.Triple`).
The restrictions that we put on the `operator fun of` function don't make it possible to express tuples in a type-safe manner (user has to declare an `of(vararg)` overload).

We don't plan to support the "tuples use-case" in the first version of collection literals.
But in the future, it's yet unclear if we want to make tuples expressible via square brackets or maybe some other syntax.
So for now, we just want to make sure that we don't accidentally make it impossible to re-use square brackets syntax for tuples.

## Performance

We did performance measurements to make sure that we don't miss any obvious problems in Kotlin's `listOf` implementation compared to `java.util.List.of`.
And to compare the performance of the proposal with [the alternative "granular operators" suggestion](#rejected-proposal-more-granular-operators), since the more granular operators proposal came as an idea to improve performance.
Unironically, a more straightforward "single operator of" proposal shows better performance than "granular operators proposal" in our benchmarks.

For array-backed collections, the approach to prepoluate array and pass the reference to the array is more performant than consecutively calling `.add` for every element.
Our benchmarks show 3x better performance.

Though, it's correct that for non-array-backed collections (like Sets or Maps), consecutive `.add`/`.put` are more performant since the `vararg`-allocated array is redundant.
But the performance boost of "granular operators proposal" for Maps and Sets is less significant (only 2x only for Maps and primarily because of `kotlin.Pair`/`Map.Entry` boxing, not because of unnecessary array allocation) compared to the performance degradation it causes to Lists (3x as it's already been mentioned).
Taking into account that List is the most popular collection container, the choice of the operator convention becomes obvious.

It's worth mentioning that the design decision shouldn't be driven purely by performance.
In our case, a more accurate design proposal just happens to be more performant than the suggested more granular alternative.
It's a double-win situation.

**"Unique vararg" statement.** Unlike in Java, in _pure_ Kotlin, we can practically assume that `vararg` parameter is a unique array reference.

**Informal proof.** Given the following function declaration: `fun acceptVararg(vararg x: Int) = Unit`, let's consider the following cases.

-   Case 1. Pass elements of the `vararg` as separate arguments. It's obvious that a new unique array is created at the call-site.
-   Case 2. Use spread operator to "spread" existing array like this: `acceptVararg(*anotherExistingArray)`. Spread operator creates the array on the call-site.
-   Case 3. Bypass through callable reference like this: `::acceptVararg.invoke(intArrayOf(1, 2))`.
    Indeed, in this case `vararg` parameter cannot be guaranteed to be unique, since the argument is not copied on the call-site.

    Luckily for us, we don't consider this example practical, people don't create references to `listOf`, `List.of`, etc. functions.
    The primary usage for those functions is to call them directly with parameters mapped to the `vararg` parameter like this: `listOf(1, 2, 3)`.

So unless the Kotlin declaration that accepts `vararg` is called from Java, we can practically assume that the `vararg` parameter is unique.

Java doesn't assume that the vararg array is unique, so they have to copy it.
And to reduce the array copying performance impact, Java declares 10 `List.of` overloads, in which they don't need to do the copy.
Unnecessary array copying can cause more than 2x performance degradation.
Thanks to _"Unique vararg" statement_, Kotlin doesn't have to declare 10 `List.of` overloads like Java did.
The proposal is to proceed with just three overloads as we have them right now in Kotlin.

All the mentioned numbers can be seen in the raw benchmark data, that can be found in the [`resources/collection-literals-benchmark/`](https://github.com/Kotlin/KEEP/blob/master/resources/collection-literals-benchmark) directory of the KEEP repo root.

## IDE support

The IDE should implement an inspection to replace `listOf`/`setOf`/etc. with collection literals where it doesn't lead to overload resolution ambiguity.
It's under the question if the inspection for `listOf`/`setOf` should be enabled by default. (most probably not)

The IDE should implement an inspection to replace explicit use of operator function `Type.of` with collection literals where it doesn't lead to overload resolution ambiguity.
The inspection should be enabled by default.

We should take into account that ctrl-click on a single character is tricky, especially for via keyboard shortcuts.
Though there are some suggestions to improve the situation: [KTIJ-28500](https://youtrack.jetbrains.com/issue/KTIJ-28500).

## listOf deprecation

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
- `mutableListOf<String>().copyOf()` // red code. We don't have a stdlib function to copy list in Kotlin

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
Similar to `emptyList`, if the *expected type* doesn't provide enough information for what the collection literal element should be,
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

But right now, we **don't plan** to do that, since both `List` and `Set` overloads can equally represent the "main" overload.

## Rejected proposals and ideas

This section lists some common proposals and ideas that were rejected

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
1.  The design with 3 operators is simply more complicated.
    3 operators become more scattered than a single operator, it becomes unclear where the IDE should navigate on ctrl-click.
    So if possible, we should prefer a more straightforward "single operator" design.
2.  The type of the collection builder is exposed in public API (return type of `createCollectionBuilder`).
    The type of the builder is an implementation detail, and users might want to change it over time.
3.  It becomes harder or unreasonable to return different collection types/builder types depending on the number of elements in the collection literal.
4.  Performance.
    For array-backed collections, the approach to prepopulate an array and safe the reference to the array is more performant than calling `.add` for every element.
    The most popular collection is List.
    List is array-backed.
    Though it's correct that consecutive `.add` calls is more performant for non-array-backed collections like Map or Set.
    Please see the [Performance](#performance) section.

### Rejected idea: self-sufficient collection literals with defined type

> todo it's not yet completely rejected since the discussion around Map literals is still in progress

The type of collection literals is inferred from the *expected type*.
Unfortunately, that may lead to overload resolution ambiguity when collection literal is used in argument position.

```kotlin
fun foo(a: Set<Int>) = Unit
fun foo(a: List<Int>) = Unit

fun test() {
    foo([1, 2]) // overload resolution ambiguity
    // but what if...
    foo(List [1, 2])
}
```

The rejected proposal was to allow writing `List [1, 2]`.
The proposal was rejected because users can anyway desugar `[1, 2]` to `List.of(1, 2)` or `listOf(1, 2)`.

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

The proposal was rejected because the analogical "improved overload resolution for references" already allows resolving to functions which are "too far away".

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

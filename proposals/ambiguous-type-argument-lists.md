# Ambiguous type argument lists

* **Type**: Design proposal
* **Author**: Iven Krall
* **Status**: Implemented in [#4860](https://github.com/JetBrains/kotlin/pull/4860)
* **Issue:** [KT-8263](https://youtrack.jetbrains.com/issue/KT-8263)

## Synopsis

Defines how the ambiguous syntax of type argument lists of function calls is to be resolved by the compiler.

## Motivation

In Kotlin the type argument list of a function call is specified after the identifier. This creates a syntax ambiguity, where an expression can be interpreted
both as a nested function call with a type argument list or as two comparisons used as arguments to a function call.

```kotlin
foo(bar<x, y>(1))
// <=>
foo(bar < x, y > (1))
```

In its initial design phase Kotlin opted to embrace this ambiguity, but the concrete semantics of how the ambiguity is to be resolved were never defined in the
[Specification](https://kotlinlang.org/spec).

Currently, the parser already processes the broad majority of cases without problems. However, a number of expressions, that look similar to function calls with
type argument lists, are incorrectly parsed as such, usually leading to one or more syntax errors. Fixing these issues would introduce new green code, that
would completely solidify the so far unspecified semantics. Therefor, it has to be considered as a langauge feature rather than just a fix.

```kotlin
fun <A, B> f(): Boolean = fals
fun any(a: Boolean, b: Boolean) {}

fun test(f: Int, x: Int) {
    any(f<Int, Int>(), false)
    any(f < -1, f > x)        // compiles
    any(f < (-1), f > x)      // error
}
```

## Occurrence

The ambiguity exists between Typed-Call-Expressions (TCE) and Lesser-Greater-Expressions (LGE).

TCE is a function calls with a type argument list.

```kotlin
listOf<String>(set)
buildMap<String, String> {  }
```

LGE is a less-than comparison directly or indirectly followed by a greater-than comparison, where the right-hand-side of the greater-than comparison is a
call-like expression.

```kotlin
val c = a < b > (false)
transpose(x < y, y > (z - x))
any(x < y, empty, y > { })
```

Whether an ambiguity can occur depends on the amount of type arguments and the context the function call appears in.

- TCEs with 0 type arguments are never ambiguous:
  `a <> (z)` is not a valid expression, given that `<>` is not a valid operator in Kotlin. Also, type argument lists are required to contain at
  least one type argument.

- TCEs with 1 type arguments are ambiguous in any context:
  `a < b > (z)` is also a valid LGE, where the comparisons are chained. In this proposal this case is referred to as Chained-Boolean-Comparisons (CBC).

- TCEs with 2 or more type arguments are ambiguous in the context of parameters to a function call:
  `a < b, c > (z)` is also a valid LGE, where each expression between the commas is seen as a parameter to a function call  (`f(a < b, c > z)`). In this proposal
  this case is referred to as Comparative-Function-Calls (CFC).

## Proposal

The proposal is to introduce the following paragraph to the "Syntax and grammar" section of the specification:

If parsing in the context of an expression yields `typeArguments` as part of `callSuffix` or `navigationSuffix`, the tokens immediately after the `>` token
have to be examined. If the tokens can be interpreted as the start of

1. `valueArguments`
2. `annotatedLambda`
3. `navigationSuffix`

then the `typeArguments` are retained as part of `callSuffix` or `navigationSuffix` and any other potential interpretation is discarded. Otherwise, the
`typeArguments` are dropped and not considered as part of `callSuffix` or `navigationSuffix`, even if no other interpretation is possible.

## Impact

Adopting this proposal will mean, that in cases where an expression could either be seen as a valid function call or a comparison, the function call will
always be preferred. The prioritization of function calls over comparisons creates a few unintuitive edge cases. Function calls, however, cannot be written
differently, unlike comparisons (see [Mitigation](#Mitigation)).

```kotlin
fun <T> foo(x: () -> Unit) {}

val foo: Boolean = false
val Int: Boolean = false
operator fun Boolean.compareTo(x: Any?): Int = 1

fun main() {
    println(foo > Int < {}) // comparison
    println(foo < Int > {}) // function call
}
```

A slightly lesser version of the handling described in the section above has already been present in the compiler since before Kotlin 1.0. The introduction of
the paragraph therefor has little impact on the language itself, apart from clearing up the ambiguity in the specification and resolving the issues that result
from the current handling. This will introduce new green code.

Given the following declarations:

```kotlin
fun <A> x(a: Boolean): Boolean = false
val Int: Boolean = false
```

Before:

```kotlin
fun test(x: Int) {
    val v1 = x < -1 > false      // compiles, comparison
    val v2 = x < (-1) > false    // error
    val v3 = x < -1 > (false)    // compiles, comparison
    val v4 = x < (-1) > (false)  // error

    val v5 = x < Int > false     // error
    val v6 = x < (Int) > false   // error
    val v7 = x < Int > (false)   // compiles, function call
    val v8 = x < (Int) > (false) // compiles, function call
}
```

After:

```kotlin
fun test(x: Int) {
    val v1 = x < -1 > false      // compiles, comparison
    val v2 = x < (-1) > false    // new green code, comparison
    val v3 = x < -1 > (false)    // compiles, comparison
    val v4 = x < (-1) > (false)  // new green code, comparison

    val v5 = x < Int > false     // new green code, comparison
    val v6 = x < (Int) > false   // new green code, comparison
    val v7 = x < Int > (false)   // compiles, function call
    val v8 = x < (Int) > (false) // compiles, function call
}
```

The existence and impact of the ambiguity itself has since its inception gone pretty much unnoticed by the Kotlin community. This also applies to the C#
community, where Kotlin got its syntax from in this case (see [Appendix C#](#c)).

## Mitigation

The impact of the ambiguity resolution can easily be mitigated in the cases of both CBCs and CFCs.

### Chained-Boolean-Comparison (CBC)

CBCs consist out of at least one less-than comparisons (`<`) chained with a greater-than comparisons (`>`), though more chained
comparisons are possible.

A less-than comparison always results in a `Boolean`. This means, that starting with the second comparison in chain, any further comparison will always involve
at least one `Boolean` and will also result in a `Boolean`. Such comparisons can usually be more intuitively expressed with other logical operators such as
`==`, `!=` or `!( )`. Alternatively the operands can be swapped and the operator flipped. Parenthesis can also be used.

```kolin
val a = 5 < 10 > (false)
val a = 5 < 10 != false  // substituted
val a = !(5 < 10)        // substituted
val a = 10 > 5 > false   // flipped
val a = (5 < 10) > false // parenthesized
```

### Comparative-Function-Call (CFC)

CFCs can be mitigated by either swapping the operands and flipping the operator or by parenthesising one or more of the comparisons.

```kolin
f(x < y, y > (z + 1))
f(y > x, y > (z + 1))   // flipped
f((x < y), y > (z + 1)) // parenthesized
f(x < y, (y > (z + 1))) // parenthesized
f(x < y, y > z + 1)     // de-parenthesized
```

## Appendix

### Other languages

Other languages were faced with the same problem and took different approaches in solving it.

#### Java

In Java type arguments are specified after the identifier for constructor calls, but before the identifier for function calls.

```java
new ArrayList<String>()
Collections.<String>emptyList()
```

#### Scala

Scala uses brackets (`[]`) instead of angle brackets (`<>`) for its type arguments of function calls. In order to not create a different kind of ambiguity in
combination with array accesses, it uses parenthesis in that place instead.

```scala
new List[String]()
emptyList[String]()
arr(0) // not a[0]
```

#### Rust

Rust employs a syntax known as the "turbofish" (`::<>`) to ensure, that there is no ambiguity with comparisons.

```rust
[1, 2, 3, 4].iter().sum::<u32>()
```

#### C#

Kotlin shares its syntax with C# in this regard. C# defines
in [§6.2.5](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/lexical-structure#625-grammar-ambiguities) of its
Specification, that a type argument list is to be parsed, if it is followed any of the following tokens: `( ) ] : ; , . ? == !=`.

```C#
F(G<A, B>(7)); // function call
F(G<A, B>7);   // comaprison
F(G<A, B>>7);  // comparison
x = F<A> + y;  // comparison
```

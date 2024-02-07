# Guards

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Mikhail Zarechenskii
* **Discussion**: [KEEP-371](https://github.com/Kotlin/KEEP/issues/371)
* **Status**: Experimental expected for 2.1
* **Related YouTrack issue**: [KT-13626](https://youtrack.jetbrains.com/issue/KT-13626)

## Abstract

We propose an extension of branches in `when` expressions with subject, which unlock the ability to perform multiple checks in the condition of the branch.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivating example](#motivating-example)
  * [Exhaustiveness](#exhaustiveness)
  * [Clarity over power](#clarity-over-power)
  * [One little step](#one-little-step)
* [Technical details](#technical-details)
  * [Style guide](#style-guide)
  * [The need for `else`](#the-need-for-else)
  * [Alternative syntax using `&&`](#alternative-syntax-using-)
* [Potential extensions](#potential-extensions)
  * [De-duplication of heads](#de-duplication-of-heads)
  * [Abbreviated access to subject members](#abbreviated-access-to-subject-members)

## Motivating example

Consider the following types, which model the state of a UI application.

```kotlin
enum class Problem {
    CONNECTION, AUTHENTICATION, UNKNOWN
}

sealed interface Status {
    data object Loading: Status
    data class Error(val problem: Problem, val isCritical: Boolean): Status
    data class Ok(val info: List<String>): Status
}
```

Kotlin developers routinely use [`when` expressions](https://kotlinlang.org/docs/control-flow.html#when-expression)
with subject to describe the control flow of the program.

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Error -> "error: ${status.problem}"
    is Status.Ok -> status.info.joinToString()
}
```

Note how [smart casts](https://kotlinlang.org/docs/typecasts.html#smart-casts) interact with
`when` expressions. In each branch we get access to those fields available only on the
corresponding subclass of `Status`.

Using a `when` expression with subject has one important limitation, though: each branch must
depend on a _single condition over the subject_. _Guards_ remove this limitation, so you
can introduce additional conditions, separated with `if` from the subject condition.

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Ok if status.info.isEmpty() -> "no data"
    is Status.Ok -> status.info.joinToString()
    is Status.Error if status.problem == Problem.CONNECTION ->
      "problems with connection"
    is Status.Error if status.problem == Problem.AUTHENTICATION ->
      "could not be authenticated"
    else -> "unknown problem"
}
```

The combination of guards and smart casts gives us the ability to look "more than one layer deep".
For example, after we know that `status` is `Status.Error`, we get access to the `problem` field.

The code above can be rewritten using nested control flow, but "flattening the code" is not the
only advantage of guards. They can also simplify complex control logic. For example, the code
below `render`s both non-critical problems and success with an empty list in the same way.

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Ok if status.info.isNotEmpty() -> status.info.joinToString()
    is Status.Error if status.isCritical -> "critical problem"
    else -> "problem, try again"
}
```

If we rewrite the code above using a subject and nested control flow, we need to
_repeat_ some code. This hurts maintainability since we need to remember to 
keep the two `"problem, try again"` in sync.

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Ok ->
      if (status.info.isNotEmpty()) status.info.joinToString()
      else "problem, try again"
    is Status.Error ->
      if (status.isCritical) "critical problem"
      else "problem, try again"
}
```

It is possible to switch to a `when` expression without subject,
but that obscures the fact that our control flow depends
on the `Status` value.

```kotlin
fun render(status: Status): String = when {
    status == Status.Loading -> "loading"
    status is Status.Ok && status.info.isNotEmpty() -> status.info.joinToString()
    status is Status.Error && status.isCritical -> "critical problem"
    else -> "problem, try again"
}
```

The Kotlin compiler provides more examples of this pattern, like
[this one](https://github.com/JetBrains/kotlin/blob/112473f310b9491e79592d4ba3586e6f5da06d6a/compiler/fir/resolve/src/org/jetbrains/kotlin/fir/resolve/calls/Candidate.kt#L188)
during candidate resolution.

### Exhaustiveness

Given that guards may include any expression, branches including a guard should
be ignored for the purposes of [exhaustiveness](https://kotlinlang.org/spec/expressions.html#exhaustive-when-expressions).
If you need to ensure that you have covered every case when nested properties are
involved, we recommend using nested conditionals instead.

```kotlin
// don't do this
when (status) {
    is Status.Error if status.problem == Problem.CONNECTION -> ...
    is Status.Error if status.problem == Problem.AUTHENTICATION -> ...
    is Status.Error if status.problem == Problem.UNKNOWN -> ...
    // the compiler requires an additional 'is Status.Error' case
}

// better do this
when (status) {
    is Status.Error -> when (status.problem) {
        Problem.CONNECTION -> ...
        Problem.AUTHENTICATION -> ...
        Problem.UNKNOWN -> ...
    }
}
```

### Clarity over power

One important design goal of this proposal is to make code _clear_ to write and read.
For that reason, we have consciously removed some of the potential corner cases.
In addition, we have conducted a survey to ensure that guards are intuitively understood
by a wide range of Kotlin developers.

On that line, it is not allowed to mix several conditions (using `,`) and guards in a single branch.
They can be used in the same `when` expression, though.
Here is one example taken from [JEP-441](https://openjdk.org/jeps/441).

```kotlin
fun testString(response: String?) {
    when (response) {
        null -> { }
        "y", "Y" -> println("You got it")
        "n", "N" -> println("Shame")
        else if response.equals("YES", ignoreCase = true) ->
          println("You got it")
        else if response.equals("NO", ignoreCase = true) ->
          println("Shame")
        else -> println("Sorry")
    }
}
```

This example also shows that the combination of `else` with a guard
-- in other words, a branch that does not inspect the subject --
is done with special `else if` syntax.

The reason behind this restriction is that the following condition

```kotlin
condition1, condition2 if guard
```

is not immediately unambiguous. Is the `guard` checked only when
`condition2` matches, or is it checked in every case?

### One little step

Whereas other languages have introduced full pattern matching
(like Java in [JEP-440](https://openjdk.org/jeps/440) and [JEP-441](https://openjdk.org/jeps/441)),
we find guards to be a much smaller extension to the language,
but covering many of those use cases. This is possible because
we benefit from the powerful data- and control-flow analysis in
the language. If we perform a type test on the subject, then
we can access all the fields corresponding to that type in the guard.

## Technical details

We extend the syntax of `whenEntry` in the following way.

```
whenEntry : whenCondition [{NL} whenEntryAddition] {NL} '->' {NL} controlStructureBody [semi]
          | 'else' [ whenEntryGuard ] {NL} '->' {NL} controlStructureBody [semi]

whenEntryAddition: ',' [ {NL} whenCondition { {NL} ',' {NL} whenCondition} [ {NL} ',' ] ] 
                 | whenEntryGuard

whenEntryGuard: 'if' {NL} expression
```

Entries with a guard may only appear in `when` expressions with a subject.

The behavior of a `when` expression with guards is equivalent to the same expression in which the subject has been inlined in every location, `if` has been replaced by `&&`, the guard parenthesized, and `else` by `true` (or more succinctly, `else` if` is replaced by the expression following it). The first version of the motivating example is equivalent to:

```kotlin
fun render(status: Status): String = when {
    status == Status.Loading -> "loading"
    status is Status.Ok && status.info.isEmpty() -> "no data"
    status is Status.Ok -> status.info.joinToString()
    status is Status.Error && status.problem == Problem.CONNECTION ->
      "problems with connection"
    status is Status.Error && status.problem == Problem.AUTHENTICATION ->
      "could not be authenticated"
    else -> "unknown problem"
}
```

The current rules for smart casting imply that any data- and control-flow information gathered from the left-hand side is available on the right-hand side (for example, when you do `list != null && list.isNotEmpty()`).

### Style guide

Even though `if` delineates the `whenCondition` part from the potential `guard`, there is still
a possibility of confusion with complex Boolean expressions. For example, the code below may
be wrongly as interpreted as taking the branch for both `Ok` status with an empty `info`
or `Error` status; when the reality is that the second part of the disjunction is always false,
since the guard is only checked if the condition (in this case, being `Ok`) is satisfied.

```kotlin
when (status) {
  is Status.Ok if status.info.isEmpty() || status is Status.Error -> ...
}
```

We strongly suggest writing parentheses around Boolean expressions after `if` 
when they consist of more than one term, as a way to clarify the situation.

```kotlin
when (status) {
  is Status.Ok if (status.info.isEmpty() || status is Status.Error) -> ...
}
```

Another option would have been to mandate parentheses in every guard. There are three reasons
why we have chosen the more liberal approach.

1. For small expressions, it removes some clutter; otherwise, you end up with `) -> {` in the branch,
2. Other languages (like Java or C#) do not require them (although most of them use a different keyword than regular conditionals),
3. People can still use parentheses if they want, or mandate them in their style guide. But the converse is not true.

### The need for `else`

The current document proposes using `else if` when there is no matching in the subject, only a side condition.
One seemingly promising avenue dropping the `else` keyword, or even the whole `else if` entirely.
Alas, this creates a problem with the current grammar, which allows _any_ expression to appear as a condition.

```kotlin
fun weird(x: Int) = when (x) {
  0 -> "a"
  if (something()) 1 else 2 -> "b" // `if` here is not a guard, but a value to compare `x` with.
  else -> "c"
}
```

### Alternative syntax using `&&`

We have considered an alternative syntax using `&&` for non-`else` cases.

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Ok && status.info.isNotEmpty() -> status.info.joinToString()
    is Status.Error && status.isCritical -> "critical problem"
    else -> "problem, try again"
}
```

This was considered as the primary syntax in the first iteration of the KEEP,
but we have decided to go with uniform `if`.

The main idea behind that choice was the similarity of a guard
with the conjunction of two conditions in `if` or `when` without a subject.

```kotlin
when (x) { is List if x.isEmpty() -> ... }

// as opposed to

if (x is List && x.isEmpty()) { ... }
when { x is List && x.isEmpty() -> ... }
```

However, that created problems when the guard was a disjunction.
The following is an example of a branch condition that may be
difficult to understand, because `||` usually has lower priority
than `&&` when used in expressions.

```kotlin
is Status.Success && info.isEmpty || status is Status.Error
// would be equivalent to
is Status.Success && (info.isEmpty || status is Status.Error)
```

The solution -- requiring the additional parentheses -- creates
some irregularity in the syntax.

A second problem found was that `&&` created additional ambiguities
when matching over Boolean expressions. In particular, it is not clear
what the result of this expression should be.

```kotlin
when (b) {
  false && false -> "a"
  else -> "b"
}
```

On the one hand, `false && false` could be evaluated to `false` and then
compared with `b`. On the other hand, `b` can be compared with `false` first,
and then check the guard -- so the code is actually unreachable.

## Potential extensions

### De-duplication of heads

The examples used throughout this document sometimes repeat the condition
before the guard. At first sight, it seems possible to de-duplicate some of
those checks, so they are performed only once. In our first example, we
may de-duplicate the check for `Status.Ok`:

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Ok -> when {
        status.info.isEmpty() -> "no data"
        else -> status.info.joinToString()
    }
    is Status.Error if status.problem == Problem.CONNECTION ->
      "problems with connection"
    is Status.Error if status.problem == Problem.AUTHENTICATION ->
      "could not be authenticated"
    else -> "unknown problem"
}
```

Two challenges make this de-duplication harder than it seems at first sight.
First of all, you need to ensure that the program produces exactly the same
result regardless of evaluating the condition once or more than once. While
knowing this is trivial for a subset of expressions (for example, type checks),
in its most general form a condition may contain function calls or side effects.

The second challenge is what to do with cases like `Status.Error` above,
where some conditions fall through to the `else`. Although a sufficiently good
compiler could generate some a "jump" to the `else` to avoid duplicating some
code, the rest of the language would still need to be aware of that possibility
to provide correct code analysis.

### Abbreviated access to subject members

We have considered an extension to provide access to members in the subject within the condition.
For example, you would not need to write `status.info.isNotEmpty()` in the second of the examples.

```kotlin
fun render(status: Status): String = when (status) {
    Status.Loading -> "loading"
    is Status.Ok if info.isNotEmpty() -> status.info.joinToString()
    is Status.Error if isCritical -> "critical problem"
    else -> "problem, try again"
}
```

We have decided to drop this extension for the time being for three reasons:

- It is somehow difficult to explain that you can drop `status.` from the condition, but not in the body of the branch (like the second branch above shows). However, being able to drop `status.` also in the body would be a major, potentially breaking, change for the language.
- It is not clear how to proceed when one member of the subject has the same name as a member in scope (from a local variable or from `this`).
- The simple translation from guards to `when` without subject explained in the _Technical details_ section is no longer possible.

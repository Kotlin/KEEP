# Data flow-based exhaustiveness checking

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Marat Akhin, Nikita Bobko, Nikolay Lunyak, Brian Norman, Roman Venediktov
* **Discussion**:
* **Status**: Implemented in 2.2.20
* **Related YouTrack issue**:
  [KT-8781](https://youtrack.jetbrains.com/issue/KT-8781/Consider-making-smart-casts-smart-enough-to-handle-exhaustive-value-sets),
  [KTIJ-20749](https://youtrack.jetbrains.com/issue/KTIJ-20749/Exhaustive-when-check-does-not-take-into-account-the-values-excluded-by-previous-if-conditions)
  (among others)

## Abstract

Exhaustiveness checking as currently implemented in Kotlin works in a _local_
fashion, taking into account only the branches of the `when` being inspected.
We propose to take a more global approach, by augmenting the current smart
casting framework with _negative_ information.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
* [Proposal](#proposal)
* [Technical details](#technical-details)
  * [Saving negative information](#saving-negative-information)
    * [In specification terms](#in-specification-terms)
    * [In K2 terms](#in-k2-terms)
    * [Merging information from branches](#merging-information-from-branches)
  * [Exhaustiveness checking](#exhaustiveness-checking)
  * [The problems of stability](#the-problems-of-stability)
* [Further improvements](#further-improvements)
  * [Tracking disjunctions](#tracking-disjunctions)
  * [Using negative information to smart cast](#using-negative-information-to-smart-cast)

## Motivation

_Exhaustiveness_ is an important property of a `when` expression. The
[current algorithm](https://kotlinlang.org/spec/expressions.html#exhaustive-when-expressions)
works in a _local_ fashion, by inspecting the branches of each `when`
separately, and comparing it with the (known) type of the subject. Alas,
this analysis fails to consider cases where it is statically known that a
subject may _not_ have a certain form (usually because of an early `return`).

```kotlin
enum class Enum { A, B, C }

fun f(e: Enum): Int {
  if (e == Enum.A) return 1
  return when (e) {
    Enum.B -> 2
    Enum.C -> 3
  }
}
```

In this and other cases (see the related YouTrack issues) the compiler fails
to check what intuitively the developer sees: that in a particular `when`
expression the flow of the program already makes it impossible for some cases
to occur. Our goal with this KEEP is to provide an alternative algorithm for
exhaustiveness which uses that information, by building upon Kotlin's
[smart cast framework](https://kotlinlang.org/spec/type-inference.html#smart-casts).

## Proposal

Currently the compiler keeps track of a set of types which refine type
information for expressions, which we shall refer to from this moment on as the
**positive** information.
The key part of this proposal is to extend this smart cast information held by
the compiler with **negative** information. This new piece of information is
composed of two parts.

1. A set of **types**, which describe those types which an expression definitely
   does not have.
2. A set of **constant values** (either literals or enumeration entries) which
   the expression definitely **does not have**.

Once the negative information is saved, [exhaustiveness checking]
(https://kotlinlang.org/spec/expressions.html#exhaustive-when-expressions)
may also be updated. We do not modify the definition of _exhaustive_ as laid
down by the specification, however the **source** of information should not
be the branches themselves, but rather this new negative information.

Consider the `f` function defined above. If the flow of the program does not
go through the body of the `if`, we know that `Enum.A` is now a constant value
that the expression definitely does not have. This reasoning can be extended
to the point after each branch condition. (The comments between the
`when` branches represent the point in which the previous branch is known
to be false yet the new branch is not yet considered.)

```kotlin
fun f(e: Enum): Int {
  if (e == Enum.A) return 1
  // we know [ e != Enum.A ]
  return when (e) {
    Enum.B -> 2
    // we know [ e != Enum.A, != Enum.B ]
    Enum.C -> 3
    // we know [ e != Enum.A, != Enum.B, != Enum.C ]
  }
}
```

After the last branch the compiler knows that all possible values for the type
of `e` are now out of reach. Thus, that `when` expression is marked as
exhaustive.

In this example we only use the "constant value side" of negative information.
The "types side" is necessary to check exhaustiveness when `is` is involved.

```kotlin
sealed interface Status {
  data object Error : Status
  data class Ok(val data: String) : Status
}

fun g(status: Status): String? = when (status) {
  Status.Error -> null
  // we know [ status != Status.Error ]
  is Status.Ok -> status.data
  // we know [ status != Status.Error, !is Status.Ok ]
}
```

We strongly emphasize that this KEEP only proposes to use the negative
component to check exhaustiveness. Smart casts still depend only of the
positive component.

## Technical details

In this section we describe more technical details about how to store and use
the negative information described in the _Proposal_ section.

### Saving negative information

> [!IMPORTANT]
> This part is described in terms of the specifications. Implementations may
> differ in how smart casts are actually propagated.

#### In specification terms

The proposal is an extension of the already existing [smart cast framework]
(https://kotlinlang.org/spec/type-inference.html#smart-casts), so we shall
only describe the additions.

The smart cast lattice gets a third component: a set of constant values that
the corresponding expression may not have. Order, union, and intersection are
extended naturally by considering the equivalent operations over that third
component.

The transfer functions are updated include negative constant values if
possible,

```
[[assume(x != C)]] = s[x -> s(x) ⨅ (⊤ × ⊤ × { C })], if C is a constant value
[[assume(C != y)]] = s[y -> s(y) ⨅ (⊤ × ⊤ × { C })], if C is a constant value

[[assume(x !== C)]] = s[x -> s(x) ⨅ (⊤ × ⊤ × { C })], if C is a constant value
[[assume(C !== y)]] = s[y -> s(y) ⨅ (⊤ × ⊤ × { C })], if C is a constant value
```

#### In K2 terms

The smart cast framework has been
[completely re-architectured](https://youtrack.jetbrains.com/issue/KT-57516)
for version 2.0 of the Kotlin compiler. In particular, nodes in the control
flow graph store both type information (as in the specification) but also
**implications** that describe the different scenarios. These implications
often refer to the outcome of the expression as a whole, which we shall call
`$result` in the description below.

For example, here is a simplified version of the rule for Boolean negation,
which related the result of the whole expression with the value of the argument.

```
[[ !e ]] = [ $result == true ==> e == false , $result == false ==> e == true ]
```

By recording those implications, `assume` nodes are no longer required.
Instead, implications are checked whenever new information becomes available.
For example, when we are within the body of a `when` branch, we know that the
condition was true.

The transfer functions are updated to include negative information. In the
description below we use `e is T`, `e !is T`, and `e != T` to refer to the
positive, negative types, and negative constant value parts of the smart cast
information, respectively.

```
[[ e is T ]]  = [ $result == true ==> e is T , $result == false ==> e !is T ]
[[ e !is T ]] = [ $result == true ==> e !is T , $result == false ==> e is T ]

// for enumeration values and literals
[[ e == C ]] = [ $result == true ==> e is type(C) , $result == false ==> e != C ]
[[ e != C ]] = [ $result == true ==> e != C , $result == false ==> e is type(C) ]

// for objects
[[ e == O ]] = [ $result == true ==> e is O , $result == false ==> e !is O ]
[[ e != O ]] = [ $result == true ==> e !is O , $result == false ==> e is O ]
```

Note that if the `equals` method has been overridden in the type of `e`
(as discussed [below](#the-problems-of-stability))
in any of the rules for enumeration values, literals, and object, 
then the corresponding positive information (`is type(C)`, `is O`) should
**not** be produced.

#### Merging information from branches

Merging negative information from different branches poses a challenge,
especially with non-flat sealed hierarchies. In particular, trying to compute
the precise joined negative information can easily devolve into an exponential
algorithm. We expect implementations to use a simpler but slightly inaccurate
procedure in this situation; but at the very least flat hierarchies and
enumeration classes should be covered.

### Exhaustiveness checking

As discussed in the _Proposal_ section, the only change about exhaustiveness
checking proper is that the source of information now comes from the negative
information instead of by inspecting the source.

There is one technical detail, which is how to obtain the smart cast information
after the last branch condition, which is not allocated its own node in the
data flow framework.
- If the `when` expression has an `else` branch, the source is equal to the
  smart cast information about the subject at that point. (But in that case
  we do not really need to perform exhaustiveness checking anyway).
- Otherwise, we consider the program point right _before the last_ condition,
  and extend it by considering that condition as false.

### The problems of stability

One difference between how smart cast and exhaustiveness check are described
in the specification is that only the former takes into account whether the
value under consideration is
[stable](https://kotlinlang.org/spec/type-inference.html#smart-cast-sink-stability).
In general we cannot derive smart cast information from values that "may
change" (mutable variables, open getters), since that information may no longer
be true a moment later.

This impacts exhaustiveness checking in two ways. The first one is that,
without further refinement, we would never be able to compute exhaustiveness
of non-stable expressions. For example, no smart cast would be saved in this
case, so the `when` would be marked as non-exhaustive.

```kotlin
when (unstableExpression) {
  Enum.A -> 1
  Enum.B -> 2
  Enum.C -> 3
}
```

A relatively easy solution is to consider that an intermediate stable reference
is always produced in `when`. Intuitively, it is equivalent to always introducing a variable in the subject.

```kotlin
when (val $subject$ = unstableExpression) {
  Enum.A -> 1
  // [ $subject$ -> !Enum.A ]
  Enum.B -> 2
  // [ $subject$ -> !Enum.A, !Enum.B ]
  Enum.C -> 3
  // [ $subject$ -> !Enum.A, !Enum.B, !Enum.C ]
}
```

Note that this does _not_ imply that smart cast on unstable properties
suddenly start working on `when`. If the `unstableExpression` is one such
reference, the compiler should _not_ transfer information from the new
`$subject` into the unstable reference, keeping our casts safe.

The second problem related to stability is that we may only derive smart cast
information from an equality _if `equals` has not been overriden_. To be
completely correct the same requirement should be imposed for negative
information. However, this would reject some code which is now accepted, since
this requirement was not imposed on exhaustiveness checking.

As a compromise, we propose to track whether negative information comes from
such a scenario. We refer to such information as _tainted_. If exhaustiveness
checks use that information in any form, a warning should be reported.
This does not impact (type) safety, as long as we keep smart cast constrainted
to the positive component.

## Further improvements

### Tracking disjunctions

In this proposal we only improve exhaustiveness when it can be described by
the new negative information. This design leaves out cases like the following:

```kotlin
fun f(e: Enum): Int {
  if (e == Enum.B || e == Enum.C) {
    return when (e) {
      Enum.B -> 2
      Enum.C -> 3
    }
  } else {
    return 1
  }
}
```

In order to handle these cases, we would need for our data flow analysis to
track _disjunctive_ information -- right now it only handles implications and
conjunctions. This is quite a big investment, but the payoff is unclear.

### Using negative information to smart cast

A fair question is whether negative information could be used not only for
exhaustiveness checking, but for "regular" smart cast. For example, one may
argue that the function `g` above may be rewritten as follows, and the compiler
has enough information to understand that `status` must then be `Status.Ok`.

```kotlin
fun g(status: Status): String? = when (status) {
  Status.Error -> null
  // we know [ status != Status.Error ]
  else -> status.data
}
```

The main argument against such a feature is _future compatibility_. If in the
future the sealed hierarchy is extended with another element, then the code
is no longer valid. In fact, it may break at runtime with some sort of cast
exception -- exactly the kind of problem Kotlin tries to avoid.

> [!NOTE]
> The compiler currently throws `NoWhenBranchMatchedException` to protect
> for future extensions of hierarchies. However, it is not clear what should
> be done if a final cast is done using negative information instead.

Still, there are some cases in which we are sure that no extension is going
to be performed in the future, such as:

- Error-like types like `Result`, `Either`,
  or the [new proposal for unions for errors](https://youtrack.jetbrains.com/issue/KT-68296);
- Implementation of (linked list) using `Cons` and `Nil`.

For those cases, we may introduce a `@AllowNegativeInformationSmartCast`
annotation which signals this ability.

We think, though, that there is no high demand for such a feature. First of
all, the applicability is very reduced. Furthermore, currently Kotlin does
not perform such smart casts, and people seem happy with the _status quo_
(the only exception being nullability, which is treated especially by K2).
Finally, we need to carefully consider whether negative information is tainted
before smart casting.

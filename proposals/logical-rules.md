# Logical engine

* **Type**: Design proposal
* **Author**: Maxim Kizub
* **Contributors**: Maxim Kizub
* **Status**: Under consideration
* **Prototype**: Implemented in [this fork](https://github.com/mkizub/kotlin/tree/logical-rules)

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/no).

## Summary

There are 3 most known (general-purpose) programming paradigms - _imperative_ (including object-oriented),
_functional_ and _logical_ programming.
Nowadays many languages (including Kotlin) have elements from imperative and functional programming, logical
programming stays aside from this ongoing unification of different paradigms. But adding support of _logical
programming_ could yield as more power, as adding lambdas and closures to imperative languages, or
coroutines and sequences in Kotlin.

The logical paradigm is about _facts_, _rules_, and solution _searching_. Unification with imperative language will
make program's data to be _facts_, program's code to be _rules_. But _searching_ of solutions means that
a compiler support for **backtracking** is needed. _Backtracking_ means that the program can rollback to a previous
state and try an alternate solution. It also means that process of solution searching is iterative - calculation
state is saved when a found hypothesis is yielded from the searching engine, and if the hypothesis is not
satisfactory - continue search for new hypothesis from saved state.

If logical engine will use program's data (as _facts_) and functions (to check conditions) - it have to be
compiled, not interpreted. Thus, the code to search for solutions has to be compiled. And to support
_backtracking_ rules has to be compiled into a _state machine_.

Such _rules_, compiled to _state machines_, will be orthogonal, but fully integrated to the rest of code.
Imperative code will call _rules_ to find solutions, _rules_ will execute arbitrary imperative code to check
whether the hypothesises are satisfactory. But _rules_ will not provide **any notable impact** on
_imperative_ syntax and semantic. So, this proposed addition of logical engine can be done at very little
cost and with minimal changes to current Kotlin's syntax and semantic.

## Motivation / use cases

Integrated logical engine will allow to solve many complex for programming tasks.

Use cases:

- Complex Business logic
- Parsing, DSL
- Iteration over complex data
- Prove or reject some assumptions with complex specifications

## Description

Rule functions are marked with `rule` soft keyword
```
rule fun foo() {
  a, b
; c, d
}
```

Rule functions return an instance of special interface `kotlin.logical.Rule` which extends `Iterator<Boolean>`
and `Iterable<Boolean>`, so it can be used to iterate over found solutions. For each `rule` the compiler
will generate a frame class (that implements interface `kotlin.logical.Rule`) to hold state of the calculation,
to resume solution lookup.

`,` (comma) is an operator **and**, and `;` (semicolon) is an operator **or**, like in Prolog.
These **are not** boolean operators `&&` and `||`. They provide _backtracking_, so that if condition
_b_ will fail, the state machine will backtrack to state _a_ to try another solution.
When _a_ and _b_ will succeed, the result will be yielded to a caller. And if the
caller will want to search for another solution, the control will go back to condition _a_.
When both _a_ and _b_ fail, then the state machine will switch to _c_ and _d_ conditions.

To iterate over program's data, a new data wrapper class `kotlin.logical.PVar` (Planner or Prolog Var) is added
```
class PVar<A>(var value: A?) {
  fun unify(v: A): Boolean {...}
  fun browse(i: Iterator<A>): Boolean {...}
  fun unbind() { value = null }
  ...
}
```

(Planner was the first language that introduced _backtracking_, and Prolog is the most known logical language).

PVar is assumed to be _unbound_ if it's _value_ is `null`, and _bound_ (to particular value) if it's _not_ `null`

Function `unify` will be used as _is-the_ operator `?=` and will:
- bind with the value if the PVar was unbound, and return `true`.
- compare values and return `true` if they equals, and `false` if they differ.

Function `browse` will be use as _is-one-of_ operator `@=` and will:
- bind with the next value from iterator if the PVar was unbound, and return `true`.
- iterate over the iterator to check if it contains the value it's bound to

Example `rule` code may looks like

```
rule fun foo(x: PVar<Int>) {
  x ?= 1
; x @= 2..4
}

fun main() {
  val x: PVar<Int>()
  for (y in foo(x))
    println("next: ${x.value}")
  x.value = 8
  println("can be 8: ${foo(x).next()}")
  x.value = 3
  println("can be 3: ${foo(x).next()}")
}
```
This will print
```
next: 1
next: 2
next: 3
next: 4
can be 8: false
can be 3: true
```

This mean that logical variable **x** will be tried to unify with value **1**. If it was unbound, it will
be bound to value **1* and return `true`. On next iteration it will _backtrack_ over `?=` and
unbind the value, then will switch to browse with `@=` for values in the range, binding and yielding
all values sequentally.

If the **x** was bound to value **8**, the first _unification_ operator `?=`
will fail, but backtracking will not unbind **x** (since it was not bound by `?=` operator), and
control will follow to _browse_ operator `@=` that will compare x with values from
the range and will also fail. If **x** was bound to the value **3** - this value will be found
in the range and the rule returns true.

Rules can also execute arbitrary Kotlin's code to check conditions, can iterate over
others rules, can have local variables (PVar and usual values), and there are additional
control structures (`while` and `when`). There is also **cut** operator `!!` that prevents
backracking over this point. To execute some code on backtracking, operator `?<` may be used.

```
rule fun foo(x: PVar<Int>, max: Int) {
  {
    x ?= 1
  ; x @= 2..4
  },
  x < max
;
  bar(x, max),
  !!true,
  baz(x)
; push(x) ?< pop(x)
}

rule fun bar(x: PVar<Int>, max: Int) {
  var y = max
  
  max < 0, !!false
;
  do while (--y > 0),
  x ?= y
; when(max) {
  10  -> x ?= 999
  20  -> x ?= 1000
  else-> x ?= 0
  }
}

fun baz(x: PVar<Int>) =
  (x.value % 3) == 0
```

Boolean expressions (baz(x)) are checked for returned value, others are just executed once.
To re-check a boolean expression on backtracking, a **do while** expression is used.

Operator *when* is similar to operator `->` in Prolog. It executes only one alternative,
and do not try others on backtracking.

Operator `!!true` continue execution, but cuts backtracking. Operator `!!false` cuts and fails
the rule.

## Grammar/syntax changes

- add operator `,` (comma), actually, _reinvent_ this operator from C/C++
- require to use `,` or `;` in rules to separate expressions
- add operators `?=` (unify), `@=` (browse), `?<` (for backtracking)
- use `!!` as prefix for `true` and `false` (actually, it already is allowed by parser)
- `{ ... }` in rules are blocks, not lambdas

## Current limitations

For logical engine:
- *when* is not implemented yet
- implementation is too raw yet
- flow-control graph for `,` and `;` rules is not analyzed
- unification of two PVar variables is done as value binding
- no unification with complex data (data classes) or lists like in Prolog
- ...

For compiler
- only IR implemented
- only Java backend
- changes in IDEA plugin are not started yet

Actually, there are no problems with these topics, just lack on time and knowelege
of Kotlin's compiler internals.

### Other propositions

- a shorter syntax for PVar variables, maybe `log x: String` instead of `val x: PVar<String>`
  use PVar as a transparent wrapper of it's _value_, automaticaly initialize `log`
  declarations with unbound wrapper instance
- allow to use `while` instead of `do while`, change codegeneration for `while` and `if`
  to use `rule` functions directly, without `for` loop or `next()` call
- immutable data classes for pattern-matching (they can be also used for pattern-matching
  in functional-style code)
- tuples and changes in immutable collections for pattern-matching

### A toy example

Solution for a [Wolf, goat and cabbage problem](https://en.wikipedia.org/wiki/Wolf,_goat_and_cabbage_problem)
```
// A man has a wolf, a goat and a cole.
// He must ferry them using a boat that can carry one more item only.
// State is a class that describes transfer state, where 'true' means ferried,
// and 'false' means not farried
data class State(
    val man: Boolean,
    val wolf: Boolean,
    val goat: Boolean,
    val cole: Boolean)

fun State.forbidden(): Boolean =
    (wolf != man && wolf == goat) // the wolf eats the goat
||  (goat != man && goat == cole) // the goat eats the cole


val goal = State(true,true,true,true)

// ========= Solution Start =========
rule fun solve(seq: MutableList<State>) {
    ferry(seq),
    {   seq.last() == goal // solved!
    ;   solve(seq)         // try more
    }
}

rule fun ferry(seq: MutableList<State>) {
    val cs = seq.last(); // current state
    var ns = PVar<State>(); // new state

    // the man can
    {   // cross alone
        ns ?= cs.copy(man = !cs.man)
    ;   // ferry the wolf
        ns ?= cs.copy(man = !cs.man, wolf = !cs.wolf)
    ;   // ferry the goat
        ns ?= cs.copy(man = !cs.man, goat = !cs.goat)
    ;   // ferry the cole
        ns ?= cs.copy(man = !cs.man, cole = !cs.cole)
    },
    ! (-ns).forbidden(),
    !seq.contains(-ns), // prevent infinit loop
    // add new state, and remove it on backtracking
    seq.push(-ns) ?< seq.pop()
}
// ========= Solution End =========

fun MutableList<State>.last(): State = this[size-1]

fun MutableList<State>.push(s: State) = this.add(s)

fun MutableList<State>.pop() = this.removeAt(size-1)

operator fun PVar<State>.unaryMinus(): State = this.value!! 


fun main() {
    val seq = mutableListOf(State(false,false,false,false))

    for (i in solve(seq)) {
        println("--- Found sequence:")
        for (s in seq)
            println("${s}")
    }
    println("--- No more solutions")
}
```

# Pattern Matching

* **Type**: Design proposal
* **Author**: Nicolas D'Cotta  <!-- * **Contributors**: This could be you! -->
* **Status**: New
<!-- * **Prototype**: A transpiler to vanilla Kotlin in the enar future -->

## Synopsis

Support pattern matching in `when` clauses, using existing `is` syntax and
destructuring semantics.

## Motivation

Almost all languages have mechanisms to test whether an object has a certain
type or structure, and allow extracting information from it depending on the
results of those checks. At the time of writing, Kotlin has the practical
`when` clauses, which combined with [smart
casting](https://kotlinlang.org/docs/reference/typecasts.html#smart-casts)
and occasionally
[destructuring](https://kotlinlang.org/docs/reference/multi-declarations.html),
provide this kind of functionality. We propose further enhancing existing
functionality through full blown pattern matching, which allows to type
check, equality test, and extract information in a single, intuitive
construct already in use in many popular languages.

A clear immediate advantage of this extension is avoiding nested `when`s, for
example.

The syntax proposed below aims to not introduce new keywords and leverage the
existing `when` idiom that the community has already grown used to, but
discussion is encouraged and welcome on how it can be improved. While this possible syntax is extensively discussed, the main focus of the proposal is not any specific pattern matching syntax, but rather the feature itself.

### Simple textbook example

```
data class Customer(val name: String, val age: Int, val email: String)
data class Prospect(val email: String, active: Boolean)
...

when(elem) {
  is Customer(name, _, addr) ->
    Mail.send(addr, "Thanks for choosing us, $name!")

  is Prospect(addr, true) ->
    Mail.send(addr, "Please consider our product...")
}
```

The syntax proposed uses the already existing `is` construct to check the
type of the subject, but adds what semantically looks a lot like a
destructuring delcaration with added equality checks. This approach is
intuitive in that the `componentN()` operator functions are used to
destructure a class.

Then we pass an already defined expression (or it could be restricted to
literals) to further specify the desired match (a `Prospect` wich is
`active`, in the example above). This check can be implemented with
`equals()`.

Additionally, nested patterns could further look at the members of the class
(or whatever `componentN()` might return).

#### Destructuring without a type check

The type name after `is` could be omitted entirely to simply destructure
something that has some `componentN()` functions like so:
```
val list : List<Prospect> = // ...

for (p in list) {
   when (p) {
       is (addr, true) -> ...
   }
}
```
See [design decisions](tuples-syntax) for an alternative syntax for destructuring tuples without a type check.


## Comparisons
Below some examples from existing, open source Kotlin projects are listed,
along with what they would look like if this KEEP was implemented. The aim of
using real-world examples is to show the immediate benefit of adding the
proposal (as it currently looks) to the language.

#### From the [Arrow](https://github.com/arrow-kt/arrow-core/blob/be173c05b60471b02e04a07d246d327c2272b9a3/arrow-core/src/main/kotlin/arrow/core/extensions/option.kt) library:

Without pattern matching:
```
fun <A> Kind<ForOption, A>.eqK(other: Kind<ForOption, A>, EQ: Eq<A>) =
    (this.fix() to other.fix()).let { (a, b) ->
      when (a) {
        is None -> {
          when (b) {
            is None -> true
            is Some -> false
          }
        }
        is Some -> {
          when (b) {
            is None -> false
            is Some -> EQ.run { a.t.eqv(b.t) }
          }
        }
      }
    }
```

With pattern matching:
```
fun <A> Kind<ForOption, A>.eqK(other: Kind<ForOption, A>, EQ: Eq<A>) =
    when(this.fix() to other.fix()) {
        is (Some(a), Some(b)) -> EQ.run { a.eqv(b) }
        is (None, None) -> true
        else -> false
    }
```
#### <a name="coroutines-example"></a> From [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/channels/ConflatedBroadcastChannel.kt):
Without pattern matching:
```
public val value: E get() {
    _state.loop { state ->
        when (state) {
            is Closed -> throw state.valueException
            is State<*> -> {
                if (state.value === UNDEFINED) throw IllegalStateException("No value")
                return state.value as E
            }
            else -> error("Invalid state $state")
        }
    }
}
```
With pattern matching:
```
public val value: E get() {
    _state.loop { state ->
        when (state) {
            is Closed(valueException) -> throw valueException
            is State<*>(== UNDEFINED) -> throw IllegalStateException("No value")
            is State<*>(value) -> return value as E
            else -> error("Invalid state $state)
        }
    }
}
```
Note that here we are testing for equality for an already defined identifier `UNDEFINED`. Refer to [Design decisions](#match-existing-id) for how this could work, if at all.

#### From JetBrains' [Exposed](https://github.com/JetBrains/Exposed/blob/master/exposed-core/src/main/kotlin/org/jetbrains/exposed/sql/Op.kt):
Without pattern matching:
```
infix fun Expression<Boolean>.and(op: Expression<Boolean>): Op<Boolean> = when {
    this is AndOp && op is AndOp -> AndOp(expressions + op.expressions)
    this is AndOp -> AndOp(expressions + op)
    op is AndOp -> AndOp(ArrayList<Expression<Boolean>>(op.expressions.size + 1).also {
        it.add(this)
        it.addAll(op.expressions)
    })
    else -> AndOp(listOf(this, op))
}

```

With pattern matching:
```
infix fun Expression<Boolean>.and(op: Expression<Boolean>): Op<Boolean> = when(this to op) {
  is (AndOp, AndOp(opExpres)) -> AndOp(expressions + opExpres)
  is (AndOp, _) -> AndOp(expressions + op)
  is (_, AndOp(opExpres)) -> AndOp(ArrayList<Expression<Boolean>>(opExpres + 1).also {
        it.add(this)
        it.addAll(opExpres)
    })
  else -> AndOp(listOf(this, op))
}

```

### More textbook comparisons

#### From [Jake Wharton at KotlinConf '19](https://youtu.be/te3OU9fxC8U?t=2528)

```
sealed class Download
data class App(val name: String, val developer: Developer) : Download()
data class Movie(val title: String, val director: Person) : Download()
val download: Download = // ...

```

Without pattern matching:
```
val result = when(download) {
  is App -> {
    val (name, dev) = download
    when(dev) {
      is Person -> 
        if(dev.name == "Alice") "Alice's app $name" else "Not by Alice"
      else -> "Not by Alice"
    }
  }
  is Movie -> {
    val (title, diretor) = download
    if (director.name == "Alice") {
      "Alice's movie $title"
    } else {
      "Not by Alice"
    }
  }
}
```
With pattern matching:
```
val result = when(download) {
  is App(name, Person("Alice", _)) -> "Alice's app $name"
  is Movie(title, Person("Alice", _)) -> "Alice's movie $title"
  is App, Movie -> "Not by Alice"
}
```
Note how the pattern match is exhaustive without an `else` branch, allowing us to benefit as usual from the added compile time checks of using `with` and sealed classes. Alice might write a `Book` in the future, and we would not be able to miss it.

#### From Baeldung on [Binary Trees](https://www.baeldung.com/kotlin-binary-tree):
Without pattern matching:
```
private fun removeNoChildNode(node: Node, parent: Node?) {
  if (parent == null) {
    throw IllegalStateException("Can not remove the root node without child nodes")
  }
  if (node == parent.left) {
    parent.left = null
  } else if (node == parent.right) {
    parent.right = null
  }
}
```
With pattern matching:
```
private fun removeNoChildNode(node: Node, parent: Node?) {
  when (node to parent) {
    is (_, null) ->
      throw IllegalStateException("Can not remove the root node without child nodes")
    is (n, Node(n, _)) -> parent.left = null
    is (n, Node(_, n)) -> parent.right = null
  }
}
```
## Semantics

The semantics of this pattern matching can be defined through some examples, where
`when` gets called on a particular `subject`.

The proposed syntax is to start a new `when` line with `is PATTERN -> RHS`, where `PATTERN` can be:
- `Person` 
  - `instanceof` check, same semantics as vanilla Kotlin.
- `Person(_const)` where `_const` is an expression literal
  -  `is Person` check on the subject
  -  compile time check on whether `Person.component1()` is defined in scope
  -  call to `subject.component1()`
  -  `component1().equals(_const)` check
  -  As with vanilla kotlin, a smart cast of the subject to `Person` happens in `RHS`
- `Person(_const, age)` where `age` is an undefined identifier
  - `is Person` check on the subject
  - compile time check whether both `Person.component[1,2]()` are defined in scope
  - equality check of `_const` as above
  - `age` is defined in `RHS` with value `subject.component2()`
- `Person(name)` where `name` is a __defined__ identifier
  - see [Design decisions](#match-existing-id)
- `Person(_const, PATTERN2)` where `PATTERN2` is a nested pattern
  - `_const` is checked as above, and `PATTERN2` is checked recursively, as if `when(subject.component2()) { is PATTERN2 }` was being called.
- `(PATTERN2, PATTERN3)` 
  - pattern like this without a type check should only be performed when `componentN()` of the subject are in scope (known at compile time).
- `Person(age, age)` where age is an undefined identifier
  - the first `age` should be matched as above
  - the second destructured argument should also call `equals()` on the first destructured argument to enforce an additional equality constraint where both fields of `Person` must be equal
  - A match that should never succeed (maybe because `Person` is defind as `(String, Int)` and `Person(age, age)` was defined) can be reported at compile time as it is likely to be a programmer mistake. Note that this match could succeed anyway in a scenario where two different types do `equals() = true` on each other.

## <a name="design"></a> Design decisions

Some of the semantics of this pattern matching are up to debate in the sense that there is room to decide on behaviour that may or may not be desirable.

### <a name="match-existing-id"></a> Matching existing identifiers
Consider a modified version of Jake Wharton's example:
```
val expected : String = / ...

val result = when(download) {
  is App(name, Person(expected), _)) -> "$expected's app $name"
  is Movie(title, Person(expected, _)) -> "$expected's movie $title"
  is App, Movie -> "Not by $expected"
}
```
It is clear that we wish to match `Person.component1()` with `expected`. But consider:
```
val x = 2
/* use x for something */
...
val result = when(DB.querySomehting()) {
  is Success(x) -> "DB replied $x"
  is Error(errorMsg) -> error(errorMsg)
  else -> error("unexpected query result")
}
```
...where a programmer might want to define `x` as a new match for the content of `Success`, but ends up writing `Success.reply == 2` because they forgot that `x` was a variable in scope. The branch taken would then be the undesired `else`.

Some instances of this scenario can be avoided with IDE hints clever enough to report matches unlikely to ever succeed (like checking equality for different types), and enforcing exhaustive patterns when matching.

Even then, it is possible that the already defined identifier does have the same type as the new match, and that the `else` branch exists. Different languages handle this scenario differently, and there are a few solutions for Kotlin:

#### Shadowing <a name="shadow-match"></a>
This would make:
```
val x = "a string!"
val someMapEntry = "Bob" to 4

when(someMapEntry) {
  is (name, x) -> ... //RHS
}
```
...valid code, but where `x` is not matched against, but redefined in the RHS. Much like shadowing an existing name in an existing scope, this is the approach [Rust](https://doc.rust-lang.org/book/ch18-03-pattern-syntax.html) takes.

#### Allowing matching, implicitly <a name="implicit-match"></a>
This would make
```
val x = 3
val someMapEntry = "Bob" to 4

when(someMapEntry) {
  is (name, x) -> ... //RHS
}
```
...valid code, where we are checking whether the second argument of the pair equals `x` (already defined as being 3).

The compiler would look for an existing `x` in the scope to decide whether we are declaring a new `x` or just matching against an existing one.

 This can lead to the issue described at the beginning of this section, but IDE hinting could be used to indicate the matching attempt. Indicators could be extra colours or a symbol on the left bar (like the one currently in place for suspending functions).

#### Allowing matching, explicitly <a name="implicit-match"></a>
This would require an additional syntactic construct to indicate whether we wish to match the existing variable named `x`, or to extract a new variable named `x`. Such a construct could look like:
```
val x = 3
val someMapEntry = "Bob" to 4

when(someMapEntry) {
  is (name, ==x) -> ... //RHS
}
```
...which makes it clear that we aim to test for equality between `x` and the extracted second parameter of the pair. Scala uses this approach through [stable identifiers](https://www.scala-lang.org/files/archive/spec/2.11/08-pattern-matching.html#stable-identifier-patterns). The syntactic construct presented in the example is rather arbitrary and suggestions on different ones are welcome.

#### Not allowing matching existing identifiers at all <a name="no-match"></a>
This would make
```
val x = 3
val someMapEntry = "Bob" to 4

when(someMapEntry) {
  is (name, x) -> ... //RHS
}
```
...throw a semantic error at compile time, where `x` is defined twice in the same scope and cannot be redefined. This would be the most explicit way of avoiding confusing behaviour but, like shadowing, it would prevent us from matching on non literals.

<br />

Matching existing identifiers **is part of the proposal** (preferably [explicitly](#explicit-match), possibly [implicitly](#implicit-match)), but accidental additional checks are undesired. Therefore this kind of matching can be dropped (in favour of [shadowing](#shadow-match) or [not allowing it at all](#no-match)) if consensus is not reached on its semantics. 

### <a name="tuples-syntax"></a> Destructuring tuples syntax

An alternative to:
```
when(person) {
  is ("Alice", age) -> ...
}
```
was suggested. It would look like:
```
when (person) {
  ("Alice", age) -> ..
}
```
Where `is` is omitted as no actual type check occurs in this scenario. This proposal argues for keeping `is` as a keyword necessary to pattern matching. Consider this example that uses the alternative syntax:
```
import arrow.core.Some
import arrow.core.None

val pairOfOption = 5 to Some(3)
val something = when (pairOfOption) {
  (number1, Some(number2)) -> ...
  (number, None) -> ...
}
```
Here, a full blown pattern match happens where we extract number2 from Arrow's `Option` and do an exhaustive type check on the sealed class for `Some` or `None`. In this scenario, `instanceof` typechecks happen, but no `is` keyword is present. Thus keeping `is` is favourable as it clearly indicates a type check albeit at the price of some verbosity.


<!--
## Implementation 
TODO
-->

## Limitations

### Matching on collections

An idiom in Haskell or Scala is to pattern match on collections. This relies on the matched pattern 'changing' depending on the state of the collection. Because this proposal aims to use `componentN()` for destructuring, such a thing would not be possible in Kotlin as `componentN()` returns the Nth element of the collection (instead of its tail for some `componentN()`).

This limitation is due to the fact that in Haskell, a list is represented more similarly to how sealed classes work in Kotlin (and we can match on those).

Pattern mathcing on collections is **not** the aim of this proposal, but such a thing *could* be achieved through additional extension functions on some interfaces with the sole purpose of matching them:
```
inline fun List<A> destructFst() =
 get(0) to if (size == 1) null else drop(1)

val ls = listOf(1,2,3,4)

fun mySum(l: List<Int>) = when(l.destructFst()) {
  is (head, null) -> head
  is (head, tail) -> head + mySum(tail)
}

// or:

fun List<Int>.mySum2() = when(this.destructFst()) {
  is (head, tail) -> head + tail?.mySum2()?:0
}
```
## Beyond the proposal
The discussion and specification of the actual construct this proposal aims to introduce into the language ends here. But this section covers some possible additions that could be interesting to discuss if they are popular, and are in the spirit of Kotlin's idioms.

### Membership matching <a name="in-match"></a>

Consider:
```
data class Point(val x: Double, val y: Double)
val p: Point = /...
val max = Double.MAX_VALUE
val min = Double.MIN_VALUE
val location = when(p) {
  is (in 0.0..max, in 0.0..max) -> "Top right quadrant of the graph"
  is (in min..0.0, in 0.0..max) -> "Top left"
  is (in min..0.0, in min..0.0) -> "Bottom left"
  is (in 0.0..max, in min..00) -> "Bottom right"
}
```
...where a destructured `componentN()` in `Point` is called as an argument to `in`, using the operator function `contains()`. This would allow to use pattern matching to test for membership of collections, ranges, and anything that might implement `contains()`. Swift has this idiom through the `~=` operator.

### Guards

A guard is an additional boolean constraint on a match, widely used in Haskell or Scala pattern matching. Consider a variation of the initial customers example:
```
when(elem) {
  is Customer(name, age, addr) where age > 18 -> Mail.send(addr, "Thanks for choosing us, $name!")
  is Prospect(addr, true) -> Mail.send(addr, "Please consider our product...")
}
```
...where the additional guard allows us to avoid a nested `if` if we only wish to contact customers that are not underage. It would also cover most cases [membership matching](#in-match) covers, and makes for very readable matching.

## Comparison to other languages

- Java is considering this (see [JEP 375](https://openjdk.java.net/jeps/375))
- [C# supports this](https://docs.microsoft.com/en-us/dotnet/csharp/pattern-matching) with a more verbose syntax through `case` .. `when` ..
- In [Haskell](https://www.haskell.org/tutorial/patterns.html) pattern matching is a core language feature extensively used to traverse data structures and to define functions, mathcing on their arguments
- [Rust](https://doc.rust-lang.org/book/ch18-03-pattern-syntax.html) supports pattern matching through `match` expressions
- [Scala](https://docs.scala-lang.org/tour/pattern-matching.html) supports pattern matching with the addition of guards that allow to further restrict the match with a boolean expression (much like `case` .. `when` in C#)
- Python does not support pattern matching, but like Kotlin it supports destructuring of tuples and collections, though not classes
- [Swift](https://docs.swift.org/swift-book/ReferenceManual/Patterns.html) can match tuples and `Optional`, and allows slightly more flexibility on what is a match through the `~=` operator
- Ruby recently released pattern matching since [2.7](https://www.ruby-lang.org/en/news/2019/12/25/ruby-2-7-0-released/)

The author has experience with only some of these languages so additional comments are welcome.

## References

[Pattern Matching for Java, Gavin Bierman and Brian Goetz, September 2018](https://cr.openjdk.java.net/~briangoetz/amber/pattern-match.html)

[JEP 375](https://openjdk.java.net/jeps/375)

[Scala specification on pattern matching](https://www.scala-lang.org/files/archive/spec/2.11/08-pattern-matching.html)
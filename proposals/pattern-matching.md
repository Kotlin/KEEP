# Pattern Matching

* **Type**: Design proposal
* **Author**: Nicolas D'Cotta
* **Contributors**: Ryan Nett
* **Status**: New
<!-- * **Prototype**: A transpiler to vanilla Kotlin in the near future -->

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

```kotlin
data class Customer(val name: String, val age: Int, val email: String)
data class Prospect(val email: String, active: Boolean)
// ...

when(elem) {
  is Customer(name, age, addr) where { age >= 18 } ->
    Mail.send(addr, "Thanks for choosing us, $name!")

  is Prospect(addr, true) ->
    Mail.send(addr, "Please consider our product...")
}
```

The syntax proposed uses the already existing `is` construct to check the
type of the subject, but adds what semantically looks a lot like a
destructuring declaration with added equality checks. This approach is
intuitive in that the `componentN()` operator functions are used to
destructure a class.

Then we pass a literal to further specify the desired match (a `Prospect`
wich is `active`, in the example above), or a [guard](#guards). A guard
simply allows us to 'guard' against the match through a predicate.

Additionally, nested patterns could look further at the members of the class
(or whatever `componentN()` might return).

#### Destructuring without a type check

The type name after `is` could be omitted entirely to simply destructure
something that has some `componentN()` functions like so:
```kotlin
val list : List<Prospect> = // ...

for (p in list) {
   when (p) {
       is (addr, true) -> //...
   }
}
```
## Comparisons

### Existing code
Below some examples from existing, open source Kotlin projects are listed,
along with what they would look like if this KEEP was implemented. The aim of
using real-world examples is to show the immediate benefit of adding the
proposal (as it currently looks) to the language.

#### From the [Arrow](https://github.com/arrow-kt/arrow-core/blob/be173c05b60471b02e04a07d246d327c2272b9a3/arrow-core/src/main/kotlin/arrow/core/extensions/option.kt) library: <a name="arrow-example"></a>

Without pattern matching:
```kotlin
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

fun Ior<L, R>.eqv(b: Ior<L, R>): Boolean = when (this) {
    is Ior.Left -> when (b) {
      is Ior.Both -> false
      is Ior.Right -> false
      is Ior.Left -> EQL().run { value.eqv(b.value) }
    }
    is Ior.Both -> when (b) {
      is Ior.Left -> false
      is Ior.Both -> EQL().run { leftValue.eqv(b.leftValue) } && EQR().run { rightValue.eqv(b.rightValue) }
      is Ior.Right -> false
    }
    is Ior.Right -> when (b) {
      is Ior.Left -> false
      is Ior.Both -> false
      is Ior.Right -> EQR().run { value.eqv(b.value) }
    }
  }
}
```

With pattern matching:
```kotlin
fun <A> Kind<ForOption, A>.eqK(other: Kind<ForOption, A>, EQ: Eq<A>) =
    when(this.fix() to other.fix()) {
        is (Some(a), Some(b)) -> EQ.run { a.eqv(b) }
        is (None, None) -> true
        else -> false
    }


fun Ior<L, R>.eqv(other: Ior<L, R>): Boolean = when (this to other) {
    is (Ior.Left(a), Ior.Left(b)) -> EQL().run { a.eqv(b) }
    is (Ior.Both(al, ar), Ior.Both(bl, br)) -> EQL().run { al.eqv(bl) } && EQR().run { ar.eqv(br) }
    is (Ior.Right(a), Ior.Right(b)) -> EQR().run { a.eqv(b) }
    else -> false
}
```
#### <a name="coroutines-example"></a> From [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/channels/ConflatedBroadcastChannel.kt):
Without pattern matching:
```kotlin
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
```kotlin
public val value: E get() {
    _state.loop { state ->
        when (state) {
            is Closed(valueException) -> throw valueException
            is State<*>(value) where { value == UNDEFINED } -> throw IllegalStateException("No value")
            is State<*>(value) -> return value as E
            else -> error("Invalid state $state")
        }
    }
}
```
Note that here we are testing for equality for an already defined identifier `UNDEFINED`. Refer to [Design decisions](#match-existing-id) for how this could work, if at all.

#### From JetBrains' [Exposed](https://github.com/JetBrains/Exposed/blob/master/exposed-core/src/main/kotlin/org/jetbrains/exposed/sql/Op.kt):
Without pattern matching:
```kotlin
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
```kotlin
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

#### From [TornadoFX](https://github.com/edvin/tornadofx/blob/facf7e8dd904e66a5516f2283538c12da55a085e/src/main/java/tornadofx/Rest.kt):

(in `fun one()`)

Without pattern matching:
```kotlin
return when (val json = Json.createReader(StringReader(content)).use { it.read() }) {
    is JsonArray -> {
        if (json.isEmpty())
            return Json.createObjectBuilder().build()
        else
            return json.getJsonObject(0)
    }
    is JsonObject -> json
    else -> throw IllegalArgumentException("Unknown json result value")
```

With pattern matching:

```kotlin
return when (val json = Json.createReader(StringReader(content)).use { it.read() }) {
    is JsonArray where { it.isEmpty() } -> Json.createObjectBuilder().build()
    is JsonArray -> json.getJsonObject(0)
    is JsonObject -> json
    else -> throw IllegalArgumentException("Unknown json result value")
```

#### From [dokka](https://github.com/Kotlin/dokka/blob/de2f32d91fb6f564826ddd7644940452356e2080/core/src/main/kotlin/Samples/DefaultSampleProcessingService.kt):

Without pattern matching:

```kotlin
fun processSampleBody(psiElement: PsiElement): String = when (psiElement) {
    is KtDeclarationWithBody -> {
        val bodyExpression = psiElement.bodyExpression
        when (bodyExpression) {
            is KtBlockExpression -> bodyExpression.text.removeSurrounding("{", "}")
            else -> bodyExpression!!.text
        }
    }
    else -> psiElement.text
}
```
With pattern matching:

```kotlin
fun processSampleBody(psiElement: PsiElement): String = when (psiElement) {
    is KtDeclarationWithBody(KtBlockExpression(text)) -> text.removeSurrounding("{", "}")
    is KtDeclarationWithBody(body) -> body!!.text
    else -> psiElement.text
}
````
### More textbook comparisons

#### From [Jake Wharton at KotlinConf '19](https://youtu.be/te3OU9fxC8U?t=2528)

```kotlin
sealed class Download
data class App(val name: String, val developer: Developer) : Download()
data class Movie(val title: String, val director: Person) : Download()
val download: Download = // ...

```

Without pattern matching:
```kotlin
val result = when(download) {
  is App -> {
    val (name, dev) = download
    when(dev) {
      is Person -> 
        if (dev.name == "Alice") "Alice's app $name" else "Not by Alice"
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
```kotlin
val result = when(download) {
  is App(name, Person("Alice", _)) -> "Alice's app $name"
  is Movie(title, Person("Alice", _)) -> "Alice's movie $title"
  is App, Movie -> "Not by Alice"
}
```
Note how the pattern match is exhaustive without an `else` branch, allowing us to benefit as usual from the added compile time checks of using `with` and sealed classes. Alice might write a `Book` in the future, and we would not be able to miss it.

#### From Baeldung on [Binary Trees](https://www.baeldung.com/kotlin-binary-tree):
Without pattern matching:
```kotlin
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
```kotlin
private fun removeNoChildNode(node: Node, parent: Node?) {
  when (node to parent) {
    is (_, null) ->
      throw IllegalStateException("Can not remove the root node without child nodes")
    is (n, Node(n, _)) -> parent.left = null
    is (n, Node(_, n)) -> parent.right = null
  }
}
```
## Semantics <a name="semantics"></a>

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
  - As with vanilla kotlin, a smart cast of the subject to `Person` happens
  in `RHS`
- `Person(_const, age)` where `age` is an undefined identifier
  - `is Person` check on the subject
  - compile time check whether both `Person.component[1,2]()` are defined in scope
  - equality check of `_const` as above
  - `age` is defined in `RHS` with value `subject.component2()`
- `Person(name)` where `name` is a __defined__ identifier
  - a semantic error, as `name` is already defined in scope.
- `Person(_const, PATTERN2)` where `PATTERN2` is a nested pattern
  - `_const` is checked as above, and `PATTERN2` is checked recursively, as
  if `when(subject.component2()) { is PATTERN2 }` was being called.
- `(PATTERN2, PATTERN3)` 
  - pattern like this without a type check should only be performed when
  `componentN()` of the subject are in scope (known at compile time).
- `Person(age, age)` where age is an undefined identifier
  - the first `age` should be matched as above
  - the second destructured argument should also call `equals()` on the first
  destructured argument to enforce an additional equality constraint where
  both fields of `Person` must be equal
  - A match that should never succeed (maybe because `Person` is defind as
  `(String, Int)` and `Person(age, age)` was defined) can be reported at
  compile time as it is likely to be a programmer mistake. Note that this
  match could succeed anyway in a scenario where two different types do
  `equals() = true` on each other.

Additionally, a line may optionally have a guard, which may ultimately make the match fail.

### Guards <a name="guards"></a>

A guard is an additional boolean constraint on a match, widely used in Haskell, Scala, and C# pattern matching. Consider the initial customers example:
```kotlin
when(elem) {
  is Customer(name, age, addr) where { age > 18 } -> Mail.send(addr, "Thanks for choosing us, $name!")
  is Prospect(addr, true) -> Mail.send(addr, "Please consider our product...")
}
```
...where the additional guard allows us to avoid a nested `if` if we only wish to contact customers that are not underage. 

Additionally, a guard predicate is just a Boolean function. 
``` kotlin
val expected : String = // ...
val movieTitleIsValid = { m: Movie -> '%' !in m.title }

val result = when(download) {
  is App(name, Person(author, _)) where { author == expected } -> "$expected's app $name"
  is Movie(title, Person("Alice", _)) where movieTitleIsValid -> "Alice's movie $title"
  is App, Movie -> "Not by $expected"
}
```

A guard predicate is defined as a function `(T) -> Boolean`. Ideally, when
using a lambda literal (`{autor == expected}` in the example) destructured
components are also in scope. This is of course not encoded in the type of
the predicate.

Guards make for very powerful matching, and more possibilities are discussed in the [Component guards](#component-guards) subsection.

## <a name="design"></a> Design decisions

Some of the semantics of this pattern matching are up to debate in the sense
that there is room to decide on behaviour or syntax that may or may not be desirable.

### Restricting match expressions to literals <a name="literals-only"></a>

This proposal argues **in favour** of only allowing literals as match expressions. This would make the following invalid code:
```kotlin
// invalid code
when ("Bob" to 4) {
  is Pair(DB.getBobsName(), num) -> // ...
}
```
... in favour of:
```kotlin
// valid code
val expected = DB.getBobsName()
when ("Bob" to 4) {
  is Pair(name, num) where { name == expected } -> // ...
}
```
This is for the following reasons:
  - This discourages inlining for equality checks in favour of using guards, which makes for more readable matches.
  - It resolves the ambiguouity where a deconstructed type match looks like a class constructor.

### Specifying extraction with `val` <a name="specify-val"></a>

This suggestion would require `val` when a new variable is extracted, and  would lift the [literals only](#literals-only) restriction (along with [`is` for nesting](#use-nested-is)).


For example:
```kotlin
val x = 3
val someMapEntry = "Bob" to 4

when (someMapEntry) {
  is (val name, x) -> // ... RHS.  name is in scope here
}
```
As with the alternative syntax, the new variable `name` has its scope limited to the case's body in the RHS.

Requiring `val` will make highly nested matches a bit more verbose.  Consider:
```kotlin
data class Name(val first: String, val middles: List<String>, val last: String)
data class Address(val streetAddress: String, val secondLine: String, val country: String, val state: String, val city: String, val zip: String)
data class Person(val name: Name, val address: Address, val age: Int)

val p: Person = // ...

when(p){
    is Person(
        Name(val first, _, val last),
        Address(val streetAddress, val secondLine, val country, val state, val city, val zip),
        _
    ) -> // ...
}
```
...vs:
```kotlin
when(p){
    is Person(
        Name(first, _, last),
        Address(streetAddress, secondLine, country, state, city, zip),
        _
    ) -> // ...
}
```

### Nested patterns using `is` <a name="use-nested-is"></a>

This suggestion involves re-using the keyword `is` every time a nested pattern matched is performed. Potentially, it could allow simply recursing on `when` conditions in general:

Consider the original proposal syntax:
```kotlin
val result = when(download) {
  is App(name, Person("Alice", _)) -> "Alice's app $name"
  is Movie(title, Person("Alice", _)) -> "Alice's movie $title"
  is App, Movie -> "Not by Alice"
}
```
... as opposed to the alternative:
```kotlin
val result = when(download) {
  is App(name, is Person("Alice", in 0..18)) -> "Alice's app $name"
  is Movie(val title, Person("Alice", _)) -> "Alice's movie $title"
  is App, Movie -> "Not by Alice"
}
```
The argument in favour of this syntax is that the added keywords clarify any
ambiguities. This proposal argues **against** this more verbose syntax for
the following reasons:
 - It lifts the [literals only](#literals-only) restriction, which would
 allow the user to inline things inside a pattern match (making for harder to
 read equality checks)
 - It is much more verbose to write for nested patterns of more than one
 level, or where there is a lot of inspection of destructured elements.
 Consider how using `is` and `val` every time would look for the 
 [Arrow example](#arrow-example), which does not even make use of
more than 1 level of nesting nor guards:
  
```kotlin
fun <A> Kind<ForOption, A>.eqK(other: Kind<ForOption, A>, EQ: Eq<A>) =
    when(this.fix() to other.fix()) {
        is (is Some(val a), is Some(val b)) -> EQ.run { a.eqv(b) }
        is (is None, is None) -> true
        else -> false
    }


fun Ior<L, R>.eqv(other: Ior<L, R>): Boolean = when (this to other) {
    is (is Ior.Left(val a), is Ior.Left(val b)) -> EQL().run { a.eqv(b) }
    is (is Ior.Both(val al, val ar), is Ior.Both(val bl, val br)) ->
      EQL().run { al.eqv(bl) } && EQR().run { ar.eqv(br) }
    is (is Ior.Right(val a), is Ior.Right(val b)) -> EQR().run { a.eqv(b) }
    else -> false
}
```

#### Conclusion

There are two competing alternative syntax possibilites for pattern matching
in Kotlin. While this proposal argues for one that
- is slightly more restrictive (no nested `when` conditions)
- is less verbose
- encourages guards

...using `val` and `is` would make for a pattern matching that
  - is more verbose (but offers some clarity)
  - allows inlining for equality checking
  - allows recursive `when` conditions (like `in 0..18`)

### Restricting matching to data classes only

A possibilty suggested during the conception of this proposal was to restrict pattern matching to data classes. The argument would be to start with a samller size of 'matchable' elements in order to keep the inital proposal and feature as simple as possible, as it could be extended later down the line.

This proposal argues **against** this restriction. Matching anything that implements `componentN()` has the important benefit of being able to match on 3rd party classes or interfaces that are not data classes, and to extend them for the sole purpose of matching. A notable example is `Map.Entry`, which is a Java interface.

## Limitations

### Matching on collections

An idiom in Haskell or Scala is to pattern match on collections. This relies
on the matched pattern 'changing' depending on the state of the collection.
Because this proposal aims to use `componentN()` for destructuring, such a
thing would not be possible in Kotlin as `componentN()` returns the Nth
element of the collection (instead of its tail for some `componentN()`).

This limitation is due to the fact that in Haskell, a list is represented
more similarly to how sealed classes work in Kotlin (and we can match on
those).

**Pattern mathcing on collections is not the aim of this proposal**, but such
a thing *could* be achieved through additional extension functions on some
interfaces with the sole purpose of matching on them:
```kotlin
inline fun List<A> destructFst() = when(size) {
   0 -> null
   else -> first() to drop(1)
 }

val ls = listOf(1,2,3,4)

fun mySum(l: List<Int>) = when(l.destructFst()) {
  null -> 0
  is (head, tail) -> head + mySum(tail)
}
```
This proposal does not argue for including such extension functions in the standard library.
## Beyond the proposal
The discussion and specification of the actual construct this proposal aims
to introduce into the language ends here. But this section covers some
possible additions that could be interesting to discuss if they are popular,
and are in the spirit of Kotlin's idioms.

#### Component Guards <a name="component-guards"></a>

Guards could be extended to allow guards on destructured components, instead of just the entire case.

An example:
```kotlin
data class Person(val name: String, val age: Int, val contacts: List<Person>)
val p: Person = // ...
when(p) {
    is Person(name where { "-" !in it }, age where {it >= 18}, _) -> // ...
    is Person(_, _, _ where { it.size >= 5 }) -> // ...
}
```

The biggest benefit of this is allowing custom matches on any of the destructured arguments
It also allows for some matching on collections or other types that don't destructure well:
```kotlin
val ls = listOf(1,2,3,4)

when("SomeList" to ls) {
  is (_, list where Collection::isNotEmpty) -> // ... use list with the sweet relief of knowing it is not empty
}
```
A user could define their guards like `is Person(name where isLastNameNotHyphenated, _, _)` through named lambdas or function references, for more complex matching. Because the guards are named, they stay readable.

A large drawback is the verbosity. Large classes with lots of guards quickly become very long.
This could cleaned up some by using a different, shorter syntax, but extra sytax will still always be added to compoment declarations.
However, assuming normal guards are implemented, a guard would be just as verbose, 
it would just cover all checks at once rather than doing the check where the component is declared, which may reduce readability.
Doing checks at the component declaration also allows for checks on non-assigned (`_`) components, as in the last case in one of the examples.


### Named components <a name="named-components"></a>

In the existing proposal, components must be identified through order and accessed using the `componentN` functions.
This is limiting. Ideally, we would be able to identify components by name as well as order, like with function parameters.
Order-based components would be limited to coming before named components, again like function parameters.
Similairly, any unused components wouldn't have to be specified with `_`.

Resolution would simply be based off of the `.` operator. 
In the example below, anything that would resolve for `p.$componentName` would be a valid component name.

The problem is that we have yet to come up with good syntax for this. For example, consider:
```kotlin
data class Person(val name: String, val age: Int)

val p: Person = // ...

when(p) {
    is Person(name: n) -> //... 
}
```

The way we envision it, this would mean there is a variable `n` with the
value of `p.name` in scope for the case's body (similar to Rust's syntax).


However, it could be interpreted in the opposite manner and re-uses `:`,
which is used for a 'is-of-type' relationship.
An arrow-like operator would make this clearer (e.g. `name -> n`), but is
still requires adding a new operator or reusing an existing one.

While this
is a very desirable feature, this proposal lacks good syntax for it, so
contributions are welcome.

## Implementation
> Disclaimer: contributions are welcome as the author has no background on the specifics of the Kotlin compiler, and only some on JVM bytecode.

Ideally, simple matching on _n_ constructors is _O(1)_ and implemented with a
lookup table. In practice this may only be possible on some platforms, as the
JVM for example only permits typechecks using `instanceof`, which would have
to be called on each match.

As discussed in [Semantics](#semantics), there is a `componentN()` call and
either one variable definition or one `equals()` call for each destructured
argument. Therefore complexity for each match is _O(m)_ for _m_ destructured
arguments, assuming all these function calls are O(1). Note this is not a
safe assumption (the calls are user defined) but it should be by far the
common case.

While destructuring and checking for equality (with or without [guards](#guards) or [identifier matching](#named-components)) should be mostly trivial, checking for exhaustiveness in nested patterns is not. The proposal suggests a naive implementation where a table is used for each level of nesting for each destructured element. For example, in order to call `when` on a `Pair` of `Option`s:

```kotlin
when (Some(1) to Some(2)) {
  is (Some(4), Some(y)) -> ...  // case 1
  is (Some(x), Some(y)) -> ...  // case 2
  is (None, Some(3)) -> ...     // case 3
  is (_, None) -> ...           // case 4
}
```
... where `Option` is a sealed class which is either `Some(a)` or `None`.
- In case 1, the right `Some` case has been matched, whereas on the left no
case has been matched
- In case 2, we finally match the left for any `Some`. There are only 2 possible
cases for `Option`, so we are waiting to match `None` for both left and
right.
- In case 3, we can make progress on matching `None` for the left, but not
for the right.
- In case 4, `None` is finally matched for both left and right, so we can
infer that an `else` branch is not necessary.
  
This example uses `Some(1) to Some(2)` for the sake of briefness, but
ideally, the compiler can infer that the matches on `None` can't ever
succeed, because we are matching a `Pair<Some, Some>`.

Note that no progress can be made with `when` clauses that use guards, unless
contracts used by guards are taken into account by the compiler.

## Alternatives

Kotlin could do without pattern matching, as it has so far, and keep solely
relying on accessing properties through smart casting. Hopefully some of the
examples and potential features discussed in this KEEP help show that the
current idiom is limited in that it forces us to write nested constructs
inside `when`s when we want to perform additional checks.

Additionally, pattern matching does not replace smart casting, but rather,
benefits from it and makes it even more useful. Haskell and Scala often need
to access things matched on (aside from the ones they destruct). In order
to overcome this, they have
[as-patterns](http://zvon.org/other/haskell/Outputsyntax/As-patterns_reference.html)
and [pattern binders](https://riptutorial.com/scala/example/12230/pattern-binder----)
respectively. Note that this KEEP does not introduce such a construct because
a pattern can be accessed fine thanks to the smart casting idiom already
widely popular.

## Comparison to other languages

- Java is considering this (see [JEP 375](https://openjdk.java.net/jeps/375) and [JEP draft 8213076](https://openjdk.java.net/jeps/8213076)). Without guards, but the draft JEP mentions plans to add them in the future
- [C# supports this](https://docs.microsoft.com/en-us/dotnet/csharp/pattern-matching) with a more verbose syntax through `case` .. `when` ..
- In [Haskell](https://www.haskell.org/tutorial/patterns.html) pattern matching (along with guards) is a core language feature extensively used to traverse data structures and to define functions, mathcing on their arguments
- [Rust](https://doc.rust-lang.org/book/ch18-03-pattern-syntax.html) supports pattern matching through `match` expressions
- [Scala](https://docs.scala-lang.org/tour/pattern-matching.html) supports pattern matching (along with guards)
- Python does not support pattern matching, but like Kotlin it supports destructuring of tuples and collections, though not classes
- [Swift](https://docs.swift.org/swift-book/ReferenceManual/Patterns.html) can match tuples and `Optional`, and allows slightly more flexibility on what is a match through the `~=` operator. It does not allow class destructuring.
- Ruby recently supports pattern matching, since [2.7](https://www.ruby-lang.org/en/news/2019/12/25/ruby-2-7-0-released/)

The author has experience with only some of these languages so additional comments are welcome.

## References

[Pattern Matching for Java, Gavin Bierman and Brian Goetz, September 2018](https://cr.openjdk.java.net/~briangoetz/amber/pattern-match.html)

[JEP 375](https://openjdk.java.net/jeps/375)

[Scala specification on pattern matching](https://www.scala-lang.org/files/archive/spec/2.11/08-pattern-matching.html)

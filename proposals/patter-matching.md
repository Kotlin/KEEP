# Pattern Matching

* **Type**: Design proposal

## Synopsis

Support pattern matching in `when` clauses, using existing `is` syntax and
destructuring semantics

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
discussion is encouraged and welcome on how it can be improved.

### Simple textbook example

```
data class Prospect(val email: String, active: Boolean)
data class Customer(val name: String, val age: Int, val email: String)
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
destructuring delcaration with an added equality checks. This approach is
intuitive in that the `componentN()` operator functions are used to
destructure a class.

Then we pass an already defined expression (or it could be restricted to a
constant) to further specify the desired match (a `Prospect` wich is
`active`, in the example above). This check can be implemented with
`equals()`.

Additionally, nested patterns could further look at the members of the class
(or whatever `componentN()` might return).

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


Below some examples from existing, open source Kotlin projects are listed,
along with what they would look like if this KEEP was implemented. The aim of
using real-world examples is to show the immediate benefit of adding the
proposal (as it currently looks) to the language.

### Comparisons

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
        else -> false
    }
```

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
  is (_, AndOp(opExpres)) ->
    AndOp(ArrayList<Expression<Boolean>>(opExpres + 1).also {
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
        if(name == "Alice") "Alice's app $name" else "Someone else's
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
    is (n, Parent(n, _)) -> parent.left = null
    is (n, Parent(_, n)) -> parent.right = null
  }
}
```

<!-- ## Implementation TODO-->


## Comparison to other languages

- Java is considering this (see [JEP 375](https://openjdk.java.net/jeps/375))
- [C# supports this](https://docs.microsoft.com/en-us/dotnet/csharp/pattern-matching) with a more verbose syntax through `case` .. `when` ..
- In [Haskell](https://www.haskell.org/tutorial/patterns.html) pattern matching is a core language feature extensively used to traverse data structures and to define functions, mathcing on their arguments.
- [Rust](https://doc.rust-lang.org/book/ch18-03-pattern-syntax.html) supports pattern matching through `match` expressions
- [Scala](https://docs.scala-lang.org/tour/pattern-matching.html) supports pattern matching with the addition of guards that allow to further restrict the match with a boolean expression (much like `case` .. `when` in C#)
- Python does not support pattern matching, but like Kotlin it supports destructuring of tuples and collections, though not classes.
- [Swift](https://docs.swift.org/swift-book/ReferenceManual/Patterns.html) can match tuples and `Optional`, and allows slightly more flexibility on what is a match through the `~=` operator.

I have experience with only some of these languages so please feel free to correct any mistakes.

## References

[Pattern Matching for Java, Gavin Bierman and Brian Goetz, September 2018](https://cr.openjdk.java.net/~briangoetz/amber/pattern-match.html)

[JEP 375](https://openjdk.java.net/jeps/375)

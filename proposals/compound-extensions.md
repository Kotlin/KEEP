# Compound Extensions

* Type: Design proposal
* Author: Chuck Jazdzewski
* Status: proposed
* Prototype: none
* Discussion: tbd

## Summary

Extensions can be used to extend a type with an extension.

## Introduction

### Description

Compound extensions are extensions that introduce an extension onto another type.

A compound extension is declared using dotted list of types. For example,

```kotlin
operator fun Body.String.unaryPlus(s: String) = escape(s)
```

is interpreted as extending `Body` with the extension `String.unaryPlus()` which further
extends `String` with a unary `+` operator. In other words, in the scope of a `Body`
receiver, expressions of type `String` have a unary `+` operator.

### Motivation / use cases

* Context-oriented programming as discussed [here](https://proandroiddev.com/an-introduction-context-oriented-programming-in-kotlin-2e79d316b0a2).

* Contextual extensions ([from](https://youtrack.jetbrains.com/issue/KT-10468))

  In Android it is common to refer to density independent pixels. In Kotlin this can be
  improved by the use of an extension `val` but the calculation requires the current
  `Context`.

  Given this proposal, `View` can be extended with a  `Float` and `Int` extensions to simplify
  usage in a `View`.

  ```kotlin
  val View.Float.dp get() = context.displayMetrics.density * this
  val View.Int.dp get() = this.toFloat().dp
  ```

  which would then be used like,

  ```kotlin
  class SomeView : View {
    val someDimension = 4.dp
  }
  ```

* DSL by extension ([from](https://discuss.kotlinlang.org/t/add-infix-fun-to-a-class-without-extend-the-class/10772))

  Consider the Android type `JSONObject`, the following extensions can be written:

  ```kotlin
  inline operator fun JSONObject.String.invoke(build: JSONObject.() -> Unit) = put(this, JSONObject().build())
  inline infix operator fun JSONObject.String.to(value: Any?) = put(this, value)
  fun json(build: JSONObject.() -> Unit) = JSONObject().build()
  ```

  which could be used like,

  ```kotlin
  val obj = json {
      "key" {
          "key1" to "value"
          "key2" to 3
      }
  }

* Allows a fluent programming style in a receiver scope

  Consider wishing to use `produce` from `CoroutineScope` to implement a filter
  of a `ReceiverChannel`, the developer is faced with three choices,

  1. Create a `filter` that takes `CoroutineScope` and the `ReceiverChannel` as
       such as,

       ```kotlin
       fun <T> filter(
           scope: CoroutineScope,
           channel: ReceiveChannel<T>,
           predicate: (value: T) -> Boolean) = scope.produce<T> {
         for (value in channel) if (predicate) send(value)
       }
       ```

       ```kotlin
       fun main() = runBlocking {
         filter(this, numbersFrom(2)) { it % 2 }.forEach { println(it) }
       }
       ```

  2. Create a `filter` that extends `CoroutineScope` and takes `ReceiverChannel` as
       a parameter such as,

       ```kotlin
       fun <T> CoroutineScope.filter(
          channel: ReceiveChannel<T>,
          predicate: (value: T) -> Boolean) = produce<T> {
          for (value in channel) if (predicate) send(value)
       }
       ```

       ```kotlin
       fun main() = runBlocking {
         filter(numbersFrom(2)) { it % 2 }.forEach { println(it) }
       }
       ```

  3. Create a `filter` that `ReceiverChannel` and takes `CoroutineScope` as a
       parameter such as,

       ```kotlin
       fun <T> RecieverChannel<T>.filter(
           scope: CoroutineScope,
           predicate: (value: T) -> Boolean) = scope.produce<T> {
         for (value in this) if (predicate) send(value)
       }
       ```

       ```kotlin
       fun main() = runBlocking {
         numbersFrom(2).filter(this) { it % 2 }.forEach { println(it) }
       }
       ```

    With this proposal the developer could introduce use a more fluent `filter`,

    ```kotlin
    fun <T> CoroutineScope.ReceiverChannel<T>.filter(
        predicate: (value: T) -> Boolean) = produce<T> {
      for (value in this) if (predicate) send(value)
    }
    ```

    ```kotlin
    fun main() = runBlocking {
        numbersFrom(2).filter { it % 2 }.forEach { println(it) }
    }
    ```

* Generalizing local extensions

  Extensions in local scope are difficult to extract. Consider a simplified HTML DSL
  such as:

    ```kotlin
    open class HtmlDsl() { fun write(s: String) {  } fun escape(s: String) { } }
    class Html(): HtmlDsl() {}
    class Body(): HtmlDsl() { }

    fun html(block: Html.() -> Unit) {
        with (Html()) {
            write("<html>")
            block()
            write("</html>")
        }
    }

    fun Html.body(block: Body.() -> Unit) {
        write("<body>")
        Body().block()
        write("<body>")
    }

    fun Body.div(block: Body.() -> Unit) {
        write("<div>")
        block()
        write("</div>")
    }

    fun Body.span(block: Body.() -> Unit) {
        write("<span>")
        block()
        write("</span>")
    }
    ```

   To emit string content it would be convenient to overload the unary `+` operator to
   mean emit text with html escapes. The developer cannot use extension methods for this
   to be generally available and must add the operator to the correct classes directly
   such as,

   ```kotlin
   class Body(): HtmlDsl() {
       operator fun String.unaryPlus(s: String) = escape(s)
   }
   ```

   In a local scope the developer can declare extension operators for special purposes
   such as introducing a unary `-` operator to underline text,

   ```kotlin
   html {
        body {
            operator fun String.unaryMinus() {
                underline {
                    +this@unaryMinus
                }
            }
            div {
                -"Customer"
                customer(customer)

                -"Orders"
                orders(orders)
            }
        }
    }
    ```

    However, the developer is required to modify `Body` class to allow the unary `-` to be
    used outside its current local scope. This is problematic if the HTML DSL is a third-party
    library.

    Compound extensions allows the original unary `+` operator to be introduced as an extension
    function such as,

    ```kotlin
    operator fun Body.String.unaryPlus() = escape(this)
    ```

    therefore allowing unary `-` to be provided generally by extension of `Body`,

    ```kotlin
    operator fun Body.String.unaryMinus() = underline { +this@unaryMinus }
    ```

## Proposal

### Syntax

No new syntax is required. Extension declarations that are currently invalid are given
a valid interpretation.

For example, in the above examples,

```kotlin
operator fun Body.String.unaryPlus() = escape(this)
```

is syntactically valid but reports that `String` is not valid as there is no nested
`String` class of `Body`.

The type expression syntax is similarly interpreted. For example the type of,

```kotlin
fun A.B.C.someMethod(v: Int): Int = ...
```

would be written, `A.B.C.(Int) -> Int`.

#### Alternate syntax

Syntax is always contentious. The problem with the primary proposal is the ambiguity it has
with namespaces. Other syntaxes to consider are:

##### Using parenthesis
```kotlin
fun (A, B, C).someMethod(v: Int): Int = ...

val a: (A,B,C).(v: Int)->Int = ...
```

The types are unambiguous such that `fun (A.B, C).someMethod(v: Int)`, the `A.B` is
unambiguously a reference to the nested type `B` in `A`.

The type expression syntax would be similarly extended to allow `(A.B, C).() -> Unit`.

However, the type expression syntax is ambiguous at the `(` and it is not until the `.` is
seen after the initial closing `)`.

#### Using brackets
```kotlin
fun [A, B, C].someMethod(v: Int): Int = ...

val a: [A,B,C].(Int)->Int = ...
```

The types are similarly unambiguous without introducing the same syntactic ambiguity. However,
this require introducing a use for `[` in a type expression that might be better reserved by
a feature more widely leveraged such as tuples.

#### Using a pseudo-keyword

```kotlin
extension fun (A, B, C).someMethod(v: Int): Int = ...
val a: extension (A, B, C).(Int)->Int = ...
```

Using a pseudo keyword avoids the ambiguity of the `(` in the type expression. A pseudo-
keyword could also be used with the `[`...`]` syntax to leave a unadorned `[`...`]` to
mean a tuple in the future.

### Matching rules

1. **Create an ordered list of implicit receivers** in scope with types
<code>I<sup>1</sup></code>...<code>I<sup>m</sup></code> where <code>I<sup>1</sup></code>
is the outer most implicit receiver and the <code>I<sup>m</sup></code> is the inner most.

2. **Checking** given an extension function with receivers
<code>T<sup>1</sup></code>...<code>T<sup>n</sup></code> and a receiver scope type of `R`
and implicit receiver scopes in the scope tower of
<code>I<sup>1</sup></code>...<code>I<sup>m</sup></code> an extension is valid if `R` :>
<code>T<sup>n</sup></code> and there exists some permutation of (<code>T<sup>i</sup></code>,
<code>I<sup>j</sup></code>) in `i` `1`...`n-1` and `j` in `1`..`m` where
<code>I<sup>j</sup></code> :> <code>T<sup>i</sup></code> and for each element in the
permutation all `i` and `j` values are in increasing order. If multiple valid permutations are
possible then the last permutation is selected when the permutations are ordered by the values
of `i` and `j`.

3. **Report ambiguous calls** If multiple valid candidates are possible the call is ambiguous
and an error is reported.

#### Implementation note

Finding a last valid permutation can be done efficiently if the permutations are produced by
pairing  <code>T<sup>n-1</sup></code> to the last <code>I<sup>j</sup></code> where
<code>T<sup>n-1</sup></code> :> <code>I<sup>j</sup></code> and then pairing
<code>T<sup>n-2</sup></code> to some <code>I<sup>k</sup></code> where `k` < `j` until a valid
permutation is found. The first valid pairing found will be the last permutation given the
ordering described above. This can be accomplished in worst case *O(nm)* steps.

### Disambiguation of `this`

The `this` keyword refers to the most nested receiver. Reference to an outer receiver can
be disambiguated by using the simple type name (without type parameters). In cases where that is
not possible (such as a function type expression) a type alias with a simple name would need
to be used in the declaration.

For example,

```kotlin
fun A.B.C.doSomething() = this@A.amember(this@B.bmember(this.cmember())
```

### Semantics

A synthetic receiver parameter is introduced for every receiver type in a compound receiver.

For example,

```kotlin
fun A.B.C.doSomething() = this@A.amember(this@B.bmember(this.cmember())
```

would roughly translate into the Java function

```Java
  void doSomething(A receiver2, B receiver1, C receiver0) {
      receiver2.amember(receiver1.bmember(receiver0.cmember()))
  )
```

A call to a compound receiver collects ambient receivers into the synthetic parameter,

```kotlin
var a: A
var b: B
var c: C

with (a) {
    with (b) {
        with (c) {
            doSomething()
        }
    }
}
```

would roughly translate into,

```Java
doSomething(a, b, c)
```
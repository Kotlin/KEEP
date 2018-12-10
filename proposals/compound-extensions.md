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

### Lookup rules

When resolving the an extension, if the type expression following a `.` prior to the member
name is not resolvable in scope of the type to the left, the ambient lookup scope is consulted
and if the expression is resolvable to a type then the extension declaration is interpreted to
extend the type to the left with, the potentially compound, extension of the type resolved.

Type aliases or import aliases can be used to extend types that are obscured by nested types.

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
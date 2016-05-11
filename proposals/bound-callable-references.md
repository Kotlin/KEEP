# Bound Callable References

* **Type**: Design proposal
* **Author**: Alexander Udalov
* **Contributors**: Andrey Breslav, Stanislav Erokhin, Denis Zharkov
* **Status**: Under consideration
* **Prototype**: In progress

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/5).

## Summary

Support "bound callable references" and "bound class literals".

## Motivation / use cases

- It's painful to write lambdas every time
- There's currently no way to reference an object member, which is sort of inconsistent with the fact that it's possible to reference static Java members
- It's present in Java and its absence in Kotlin is rather inconvenient
- >42 votes on [KT-6947](https://youtrack.jetbrains.com/issue/KT-6947): Callable reference with expression on the left hand side

## Description

- Support the following syntax: `<expression>::<member name>` (see semantics below).
- Drop the "callable reference to object/companion member" error. Leave other diagnostics for now.
- Support the following syntax: `<expression>::class` (see semantics below).

### Parsing

Now the LHS of a callable reference expression or a class literal expression (or *double colon expressions*) can mean either an expression or a type, and it's impossible to figure out what it is by looking only at the AST. So we always parse an expression, with optional question marks after it to support the case of nullable types.

```
callableReferenceExpression
    : (expression "?"*)? "::" SimpleName typeArguments?
    ;

classLiteralExpression
    : (expression "?"*)? "::" "class"
    ;
```

Double colon expressions are postfix expressions, so `::`'s priority is maximal and is equal to that of the dot (`.`).
```
    a+b::foo     // parsed as "a+(b::foo)"
    a.b::class   // parsed as "(a.b)::class"
```

Note that now any expression can be followed by question marks (`?`) and then `::`. So, if we see `?` after an expression, we must perform a lookahead until the first non-`?`, and if it's `::`, parse this as a double colon expression.

### Resolution

The LHS may now be interpreted as an expression, or a type, or both.
```
    SimpleName::foo           // expression (variable SimpleName) or type (class SimpleName)
    Qualified.Name::foo       // expression or type

    Nullable?::foo            // type (see section Nullable references below)
    Generic<Arg>::foo         // type

    Generic<Arg>()::foo       // expression
    this::foo                 // expression
    (ParenthesizedName)::foo  // expression

    (ParenNullable)?::foo     // type
```

The semantics are different when the LHS is interpreted as an expression or a type, so we establish a priority of one over another when both interpretations are applicable.
The algorithm is the following:

1. Try interpreting the LHS as an **expression** with the usual resolution algorithm for qualified expressions.
   If the result represents a companion object of some class, continue to p.2.
   Otherwise continue resolution of the member in the scope of the expression's type.
2. Resolve the unbound reference with the existing algorithm.

Examples:
```
class C {
    companion object {
        fun foo() {}
    }

    fun foo() {}
}

fun test() {
    C::foo             // unbound reference to 'foo' in C, type: '(C) -> Unit'
    C()::foo           // bound reference to 'foo' in C, type: '() -> Unit'
    (C)::foo           // bound reference to 'foo' in C.Companion, type: '() -> Unit'
    C.Companion::foo   // bound reference to 'foo' in C.Companion, type: '() -> Unit'
}
```

Note that references to object members will be considered bound by this algorithm
(whether or not it should be possible to obtain an unbound reference for an object/companion member is an open question):
```
object O {
    fun foo() {}
}

fun consume(f: (Any?) -> Unit) {}

fun test() {
    O::foo             // bound reference to 'foo' in O, type: '() -> Unit'
    consume(O::foo)    // error, type mismatch (or maybe allow this)
}
```

Resolution of a LHS of a class literal expression is performed exactly the same,
so that `C::class` means the class of C and `(C)::class` means the class of C.Companion.

It is an error if the LHS expression has nullable type and the resolved member is not an extension to nullable type.
It is an error if the LHS of a bound class literal has nullable type:
```
class C {
    fun foo() {}
}

fun test(c: C?) {
    c::foo             // error
    c::class           // error
    null::class        // error
}
```

#### Nullable references

According to this proposal, in the following syntax
```
Foo?::bar
```
the left-hand side can only be interpreted as a type because `Foo?` is not a valid expression. Therefore this would necessarily be a reference to an extension function named `bar` to the type `Foo?`.

However, at some point we may want to give it the semantics of a reference to an expression `Foo` which is null when the result of that expression is null. In other words, it would be equivalent to
```
Foo?.let { it::bar }
```

To be able to introduce this later without breaking source compatibility, we should **prohibit** double colon expressions of the form `Foo?::bar` where `Foo` may be resolved as an *expression* (which usually means, there's a variable or a property named `Foo` in the scope). Otherwise the behavior would change:
```
class Foo
fun Foo?.bar() {}

fun String.bar() {}

fun test() {
    val Foo: String? = ""
    Foo?::bar   // now resolved to Foo.bar, but will be String.bar
}
```

One particular case is object declarations. An object name is both a variable and a type, so, according to the rule above, references to extensions to nullable object types will be forbidden, to prevent such references from changing the semantics in the future:
```
object Obj
fun Obj?.ext() {}

fun test() {
    Obj?::ext   // forbidden
}
```

### Code generation (JVM)

Putting aside reflection features, generated bytecode for `val res = <expr>::foo` should be similar to the following:
```
val tmp = <expr>
val res = { args -> tmp.foo(args) }
```

Generated bytecode for `<expr>::class` should be similar to `<expr>.javaClass.kotlin`.
An intrinsic for `<expr>::class.java`, meaning `<expr>.javaClass`, would be nice.

### Reflection

A `KFunction` instance for a bound callable reference has no receiver parameter.
Its `parameters` property doesn't have this parameter and the argument should not be passed to `call`.

## Open questions

- API for "unbinding" a reference
    - Information about the original receiver type is absent in the type of a bound reference, so it's unclear what signature will the hypothetical "unbind" function have.
        - One option would be an _unsafe_ "unbind" with a generic parameter which prepends that parameter to function type's parameter types:

          ```
          class O {
              fun foo() {}
              val bound: () -> Unit = this::foo
              val unbound: (O) -> Unit = this::foo.unbind<O>()
          }
          ```
    - If unbound already, throw or return null, or provide both?
- Should there be a way to obtain an unbound reference to an object member?
    - May be covered with the general API for unbinding a reference, or may be approached in a completely different way (with a language feature, or a library function).

## Alternatives

We could try resolve type in LHS first, and only then try expression. This has the following disadvantages:
- Reference to object member would be unbound and so have the additional parameter of object type
  (but this can actually be special-cased like classes with companions above)
- It would be counter to Java
- It would contradict with Kotlin's philosophy of "locals win" (taken in a broader sense)
  in case when a top-level class and a local variable have the same name, which is referenced in LHS

## Possible future advancements

### Empty left-hand side

- For a class member (or an extension), empty LHS of a callable reference (or a class literal) may mean `this`. The rationale is: inside the class `bar()` is equivalent to `this.bar()`, so `::bar` may be equivalent to `this::bar`.

  ```
  class Foo {
      fun bar() {}

      fun test() = ::bar  // equivalent to "this::bar"
  }
  ```

  This can be safely introduced later without breaking source compatibility.

- Another possible use of the empty LHS would be to avoid typing the type of the receiver of the expected function type, when passing an unbound reference as an argument:

  ```
  val maps: List<Map<String, Int>> = ...
  val nonEmpty = maps.filter(::isNotEmpty)   // equivalent to "Map<String, Int>::isNotEmpty"
  ```

  This causes a compatibility issue in the case when there's a declared top level function `isNotEmpty`. To prevent this code from changing its semantics we must consider all members of this "implicit expected receiver" *after* all top level members accessible from the context.

Note that it's possible to implement both ideas, so `::foo` will be resolved first as a bound member of some implicit receiver, then as a top level member, and finally as an unbound member of an "implicit expected receiver" (if the callable reference is an argument to another call).

### Other

- `super::<member>` ([KT-11520](https://youtrack.jetbrains.com/issue/KT-11520))
- Support references to member extensions ([KT-8835](https://youtrack.jetbrains.com/issue/KT-8835))

All these features can be safely introduced later.
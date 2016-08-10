# Bound Callable References

* **Type**: Design proposal
* **Author**: Alexander Udalov
* **Contributors**: Andrey Breslav, Stanislav Erokhin, Denis Zharkov
* **Status**: Under consideration
* **Prototype**: In progress

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/5).

## Summary

Support *bound callable references* (`<expression>::<member name>`) and *bound class literals* (`<expression>::class`).

## Motivation / use cases

- It's painful to write lambdas every time
- There's currently no way to reference an object member, which is sort of inconsistent with the fact that it's possible to reference static Java members
- It's present in Java and its absence in Kotlin is rather inconvenient
- >42 votes on [KT-6947](https://youtrack.jetbrains.com/issue/KT-6947): Callable reference with expression on the left hand side

## Description

*Bound* callable reference is the one which has its receiver "attached" to it. That receiver may be any expression, it is provided to the reference syntactically before `::`, is memoized in the reference after its construction, and is used as the receiver on each call to the underlying referenced function/property/constructor. Example:
```
interface Comparator<T> {
    fun compare(first: T, second: T): Int
}

fun sortedByComparator(strings: List<String>, comparator: Comparator<String>) =
    strings.sortedBy(comparator::compare)
```

The function type of a bound reference differs from the type of the corresponding *unbound* reference in that it has arity lower by 1, and doesn't have the first type argument (the type of the instance or extension receiver parameter).

Both function references and property references can be bound.

Bound class literal is an expression which is evaluated to the runtime representation of the class of an object, similarly provided as an expression before `::`. Its semantics are similar to Java's `java.lang.Object#getClass`, except that its type is `kotlin.reflect.KClass<...>`, not `java.lang.Class<...>`. Example:
```
    val x: Widget = ...
    assert(x is GoodWidget) { "Bad widget: ${x::class.qualifiedName}" }
```

### Parsing

Now the LHS of a callable reference expression or a class literal expression (or *double colon expressions*) can mean either an expression or a type, and it's impossible to figure out what it is by looking only at the AST. So we always parse an expression, with optional question marks after it to support the case of nullable types.

The rules below are simplified for clarity:

```
callableReferenceExpression
    : (expression "?"*)? "::" SimpleName typeArguments?
    ;

classLiteralExpression
    : (expression "?"*)? "::" "class"
    ;
```

Note: type arguments after callable reference expressions are reported as unsupported at the moment, this syntax is reserved for the future.

Double colon expressions are postfix expressions, so `::`'s priority is maximal and is equal to that of the dot (`.`).
```
    a+b::foo     // parsed as "a+(b::foo)"
    a.b::class   // parsed as "(a.b)::class"
```

Note that now any expression can be followed by question marks (`?`) and then `::`. So, if we see `?` after an expression, we must perform a lookahead until the first non-`?`, and if it's `::`, parse this as a double colon expression.

### Resolution: left-hand side of callable reference

The LHS of a callable reference expression may now be interpreted as an expression, or a type, or both.
```
    SimpleName::foo           // expression (variable SimpleName) or type (class SimpleName)
    Qualified.Name::foo       // expression or type

    Nullable?::foo            // type (see section "Nullable references" below)
    Generic<Arg>::foo         // type (see section "Calls to generic properties" below)

    Generic<Arg>()::foo       // expression
    this::foo                 // expression
    (ParenthesizedName)::foo  // expression

    (ParenNullable)?::foo     // type
```

The semantics are different when the LHS is interpreted as an expression or a type, so we introduce the following algorithm to choose the resulting interpretation when both are applicable:

1. Type-check the LHS as an **expression**.
   - If the result represents a _companion object of some class_ specified with the short syntax (via the short/qualified name of the containing class), discard the result and continue to p.2.
   - If the result represents an _object_ (either non-companion, or companion specified with the full syntax: `org.foo.Bar.Companion` or just `Bar.Companion`), remember the result and continue to p.2.
   - Otherwise the resolution of the LHS is complete and the result is the type-checked expression. Note that the resolution of the LHS is finished at this point even if errors were reported.
2. Resolve the LHS as a **type**.
   - If the resulting type refers to the same object that was obtained in the first step, complete the resolution with the result obtained in the first step. In other words, the result is the object type-checked as expression, and the object instance will be bound to the reference.
   - Otherwise the result is the resolved type and the reference is unbound.

> If there were no `object`s or `companion object`s in Kotlin, the algorithm would be very simple: first try resolving as expression, then as type. However, this would not be backwards compatible for a very common case: in an expression like `Obj::foo`, without any changes to the source code an object `Obj` might now win the resolution where previously (in Kotlin 1.0) a completely different class `Obj` had been winning. This is possible when you have a class named `Obj` and an object `Obj` in another package, coming from a star import.

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

It is an error if the LHS expression has nullable type and the resolved member is not an extension to nullable type:
```
class C {
    fun foo() {}
}

fun C?.ext() {}

fun test(c: C?) {
    c::foo    // error
    c::ext    // ok
}
```

### Resolution: class literal

Resolution of the LHS of a class literal expression is performed with the same algorithm.

```
class C {
    companion object
}

fun test() {
    C::class            // class of C
    C()::class          // class of C
    (C)::class          // class of C.Companion
    C.Companion::class  // class of C.Companion
}
```

Once the LHS is type-checked and its type is determined to be `T`, the type of the whole class literal expression is `kotlin.reflect.KClass<T'>` where `T'` is a type obtained by _substituting `T`'s arguments with star projections (`*`)_. Example:
```
fun test() {
    "a"::class               // KClass<String>
    listOf("a")::class       // KClass<List<*>> (not "KClass<List<String>>"!)
    mapOf("a" to 42)::class  // KClass<Map<*, *>>
}
```

> The reason for substitution with star projections is erasure: type arguments are not reified at runtime in Kotlin, so using class literals with seemingly full type information could result in exceptions at runtime. For example, consider the following code, where if we don't substitute arguments with `*`, an exception is thrown:
> ```
> fun test(): String {
>     val kClass: KClass<List<String>> = listOf("")::class
>     val strings: List<String> = kClass.cast(listOf(42))
>     return strings[0]    // ClassCastException!
> }
> ```
> If we do the substitution as proposed above, the type of `kClass` is `KClass<List<*>>` and you must perform a cast to make the code compile.

It is an error if the LHS of a bound class literal has nullable type:
```
fun test(s: String?) {
    s::class       // error
    null::class    // error
}
```

### Restrictions

To give room for some future language features, we might restrict some rare usages of callable references so that it will be possible to implement those features maintaining backwards source compatibility.

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

To be able to introduce this later, we should **prohibit** double colon expressions of the form `Foo?::bar` where `Foo` may be resolved as an *expression*. This usually happens when there's a variable or a property named `Foo` in the scope. `Foo` in this case must be either a simple name or a dot qualified name expression. This restriction is needed to prevent the change of behavior:
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

#### Calls to generic properties

It's possible that one day we will support calling generic properties with explicit type arguments:
```
val <T> id: (T) -> (T)
    get() = { x -> x }

fun test() = buildGraph(widgets, id<Widget>)
```

To be able to implement it later, similarly to nullable references above, we should **prohibit** double colon expressions which might otherwise change behavior in the future. Expressions of the form `Foo<Bar>::baz` (or `pkg.Foo<Bar>::baz`) shall not be allowed if `Foo` is resolvable as an expression.

### Code generation (JVM)

Putting aside reflection features, generated bytecode for `val res = <expr>::foo` should be similar to the following:
```
val tmp = <expr>
val res = { args -> tmp.foo(args) }
```

If a function reference is passed as an argument to an `inline` function, the corresponding call should be inlined and no anonymous class should be generated. The receiver should be calculated once and saved to a temporary variable before inlining the call:
```
class A {
    val strings: List<String> = ...
        get() = field.apply { println("Side effect!") }

    // "Side effect!" should be printed only once
    fun test() = listOf("a", "b", "c").filter(strings::contains)
}
```

Generated bytecode for `<expr>::class` should be similar to `<expr>.javaClass.kotlin`. Note that for expressions of primitive types, the corresponding `KClass` instance at runtime should be backed by the primitive `Class` object, not the wrapper class. For example, `42::class.java` should return the `int` class, not `java.lang.Integer`.

An intrinsic for `<expr>::class.java`, meaning `<expr>.javaClass`, would be useful.

### Reflection

A `KFunction` instance for a bound callable reference has no receiver parameter.
Its `parameters` property doesn't have this parameter and the argument should not be passed to `call`.

## Open questions

- Referencing member extensions, implicitly binding one of the receivers to `this`:
  ```
  class Bar

  class Foo {
      fun Bar.doStuff() {}

      fun test(bar: Bar) {
          bar.apply(Bar::doStuff)
      }
  }
  ```
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
        - Another option is for bound references to have an imaginary type `KBoundFunctionN<T, P1, ..., PN, R>` and synthesize a member function `fun unbind(): KFunctionN<P1, ..., PN, R>` in each `KBoundFunctionN` class in the compiler
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

There are a few possible improvements which are related to this proposal, but do not seem necessary at the moment and thus can be implemented later.

### Empty left-hand side

- For a class member (or an extension), empty LHS of a callable reference (or a class literal) may mean `this`. The rationale is: inside the class `bar()` is equivalent to `this.bar()`, so `::bar` may be equivalent to `this::bar`.

  ```
  class Foo {
      fun bar() {}

      fun test() = ::bar  // equivalent to "this::bar"
  }
  ```

  This can be safely introduced later without breaking source compatibility. Resolution of such members is performed almost exactly the same as resolution of normal calls, it is already supported in Kotlin 1.0 and the algorithm will likely not require any changes. For example, if there's a top level function `bar` declared in the above example, `::bar` inside the class still references `Foo`'s member.

- Another possible use of the empty LHS would be to avoid specifying the type of the receiver of the expected function type, when passing an unbound reference as an argument:

  ```
  val maps: List<Map<String, Int>> = ...
  val nonEmpty = maps.filter(::isNotEmpty)   // equivalent to "Map<String, Int>::isNotEmpty"
  ```

  This causes a compatibility issue in the case when there's a declared top level function `isNotEmpty`. To prevent this code from changing its semantics we must consider all members of this "implicit expected receiver" *after* all top level members accessible from the context.

Note that it's possible to implement both ideas, so `::foo` will be resolved first as a bound member of some implicit receiver, then as a top level member, and finally as an unbound member of an "implicit expected receiver" (if the callable reference is an argument to another call).

### Other

- `super::<member>` ([KT-11520](https://youtrack.jetbrains.com/issue/KT-11520)). Can be safely introduced later.

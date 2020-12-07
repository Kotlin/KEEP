# Simple Collection Literals #

* **Type**: Design proposal
* **Author**: [Hugh Greene](https://github.com/HughG)
* **Contributors**: My ideas are distilled from the
[arrayOf unnatural](https://discuss.kotlinlang.org/t/arrayof-unnatural/1637) thread on the Kotlin discussions forum and
from [KEEP 112](https://github.com/Kotlin/KEEP/pull/112).
* **Status**: Under consideration
* **Prototype**: Not started

Discussion of this proposal is held in [KEEP #TODO](https://github.com/Kotlin/KEEP/issues/TODO).


## Goal ##

I want to balance the following conflicting desires I believe exist in relation to collection literals.

* Since it aims to be concise, Kotlin should have a clear but very terse syntax for common cases of collection
literals, particularly for nested collections such as lists of lists.
* Since it avoids specialising where generality is more useful, Kotlin should not favour one particular statically
declared and/or actual runtime type for collection literals.  Different types are appropriate in multiple
significant use cases; for example, immutable versus mutable, lists versus sets, or ordered versus unordered.

Also I apply the [Principle of Least Astonishment](https://en.wikipedia.org/wiki/Principle_of_least_astonishment) to
aim for a solution which will make it unlikely that people will (a) produce code which appears to build and run
successfully but behaves unexpectedly; or (b) produce code which fails to build or run in a way which is difficult to
understand.


## Feedback ##

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/TODO).

Please note that regrettably I have very little time to respond to feedback, so I offer this proposal mainly as a
draft for others to develop.  


## Description ##

This proposal extends the applicability of the existing array literal syntax (available since Kotlin 1.2 for use in
annotation declarations) to general collection literals and adds an unsurprising syntax for map-like collections. 

Collection literals would be as follows.

```Kotlin
val myCollection = [1, true, "three"]
```

Map-like literals would look like this:

```Kotlin
val myMapLike = ["A": 1, "B": true, "C": "three"]
```

The _semantics_ of these are very simple: they are compiled as if they were written as follows.

```Kotlin
val myCollection = defaultCollectionOf(1, true, "three")
val myMapLike = defaultMapOf("A" to 1, "B" to true, "C" to "three")
```

The names `defaultCollectionOf` and `defaultMapOf` are resolved as normal but there is no definition of these names
in any of the packages which are imported by default into every Kotlin file.  Therefore, in order to have this code
compile correctly, a developer must define or import callable values (functions, or properties with function values,
or objects which override `operator fun invoke()`) into each scope where the literal syntax is used.  They may be
simple top-level definitions but of course they could also be extension functions, local functions, values of
delegated properties, and so on.  In particular, definitions local to a class or method may shadow those from outer
scopes as normal.  The development environment could offer assistance such as easy navigation from the collection
literal start/end markers to the applicable definition.   

This definition explains why a separate syntax is proposed for map literals: if it were not, there would be an
ambiguity if both names were used in the same scope and `defaultCollectionOf` were defined in such a way that it
accepted `Pair <K, V>` as an argument type.  

If no definition is in scope, the compiler and development environment could produce a helpful error message
describing how to define or import these functions, or offer to import definitions available on the classpath, etc.
As suggested below, the standard library might be extended to offer one or more definitions.

There are no particular constraints on the arguments or return types of the functions or function values bound to these
names, provided they can accept the form and number of arguments passed to them.  This allows developers to, for
example, define overloads for different numbers or types of arguments.  It would also allow them to return objects of a
which is like a collection in some sense but not exactly conforming to `Collection`; for example, 3D vectors. 

**NOTE:** If a Shepherd of this proposal feels that such flexibility of return type might be used in a confusing
way, it may make sense to require that the return type be some subtype of `Collection`.  However, that's not necessary
for the above semantics.


## Use Cases ##

TODO

* What if I want to create nested collections of multiple different types at different levels?

  * TODO: Use wrapper or extension functions.  Or, define overloads of the functions.

* What if I want to use the same literal creation functions throughout my module, or throughout a multi-module
application?

  * TODO: Within a module, you could create internal callable bindings in the default package.


## Syntax ##

### Formal grammar ###

This builds on the formal Kotlin grammar in
[the Kotlin language grammar reference](https://kotlinlang.org/docs/reference/grammar.html).


```EBNF
(* Changes to Existing Grammar *)

collectionLiteral
    : sequenceLiteral
    : dictionaryLiteral
    ;


(* Sequence Literals *)

sequenceLiteral
    : '[' expression (',' expression)* '.'? ']'
    : '[' ']'
    ;


(* Dictionary Literals *)

dictionaryLiteral
    : '[' dictionaryItem (',' dictionaryItem)* '.'? ']'
    : '[' ':' ']'
    ;

dictionaryItem
    : expression ":" expression
    ;
```



## Changes to stdlib ##

TODO: Introduce `@CollectionLiteral` annotation?

The Kotlin Standard Library could add one or more definitions of the collection literal function names in namespaces or
objects probably under the `kotlin.collections` package.  It should not define those names directly in that package,
however.


## Impact ##

### Language Impact ###

**Non-breaking**:
 * This proposal introduces some grammar that was previously unused and would not compile.
 * This proposal extends the use of existing grammar that was limited in scope.
 * This proposal does not change the behavior of any existing code nor bytecode.

### Code Impact ###

The names of the collection literal functions were not previously reserved, so they might conflict with existing
code.  However, existing functions will not be used unless they are updated to add the `@CollectionLiteral`
annotation.  I propose that any functions in scope which are not so annotated should result in a compiler warning or
error in any case where collection literal syntax is used.  If it is not appropriate or possible to update such
existing functions they can be renamed on import using the `import some.packaged.defaultCollectionOf as
myDefaultCollectionOf` syntax.

If name clashes exist for a large enough number of existing code bases, the simplest solution is to pick different
names.  A more complex solution would be to provide another annotation which would override the choice of names to be
resolved for collection literals, in a given scope.
  

## Alternatives Considered ##

I initially considered always requiring an annotation to define the names used for each literal syntax.  However, that
would be more verbose than simply using the normal import mechanism.


## Open Questions ##

I haven't thought about whether there are any issues with this proposal when applied other than to the JVM.  I expect
not, since I haven't introduced any new semantics.
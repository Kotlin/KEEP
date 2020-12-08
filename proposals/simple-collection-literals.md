# Simple Collection Literals #

* **Type**: Design proposal
* **Author**: [Hugh Greene](https://github.com/HughG)
* **Contributors**: My ideas come from the
[arrayOf unnatural](https://discuss.kotlinlang.org/t/arrayof-unnatural/1637) thread on the Kotlin discussions forum and
from [KEEP 112](https://github.com/Kotlin/KEEP/pull/112).
* **Status**: Under consideration
* **Prototype**: Not started

Discussion of this proposal is held in [KEEP #TODO](https://github.com/Kotlin/KEEP/issues/TODO).


## Goal ##

I want to balance the following conflicting desires I believe exist in relation to collection literals.

* Since it aims to be concise, Kotlin should have a clear but very terse syntax for common cases of collection
literals, particularly for nested collections such as lists of lists.
* Since it avoids specialising where generality is more useful and equally achievable, Kotlin should not favour one
particular statically declared and/or actual runtime type for collection literals.  Different types are appropriate in
multiple significant use cases; for example, immutable versus mutable, lists versus sets, or ordered versus unordered.

Also I apply the [Principle of Least Astonishment](https://en.wikipedia.org/wiki/Principle_of_least_astonishment) to
aim for a solution which will make it unlikely that people will (a) produce code which appears to build and run
successfully but behaves unexpectedly; or (b) produce code which fails to build or run in a way which is difficult to
understand.


## Feedback ##

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/TODO).

Regrettably I have very little time to respond to feedback, so I offer this proposal mainly as a draft for others to
develop.  


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
val myCollection = literalCollectionOf(1, true, "three")
val myMapLike = literalMapOf("A" to 1, "B" to true, "C" to "three")
```

The only exception is in annotation parameters, where list-like literals always produce arrays and map literals are not
applicable.

The names `literalCollectionOf` and `literalMapOf` are resolved as normal but there is no definition of these names
in any of the packages which are imported by default into every Kotlin file.  Therefore, in order to have this code
compile correctly, a developer must define or import callable values (functions, or properties with function values,
or objects which override `operator fun invoke()`) into each scope where the literal syntax is used.  They may be
simple top-level definitions but of course they could also be extension functions, local functions, values of
delegated properties, and so on.  In particular, definitions local to a class or method may shadow those from outer
scopes as normal.  The development environment could offer assistance such as easy navigation from the collection
literal start/end markers to the applicable definition.   

This definition explains why a separate syntax is proposed for map literals: if it were not, there would be an
ambiguity if both names were used in the same scope and `literalCollectionOf` were defined in such a way that it
accepted `Pair <K, V>` as an argument type.  

If no definition is in scope, the compiler and development environment could produce a helpful error message
describing how to define or import these functions, or offer to import definitions available on the classpath, etc.
As suggested below, the standard library might be extended to offer one or more definitions.

There are no particular constraints on the arguments or return types of the functions or function values bound to these
names, provided they can accept the form and number of arguments passed to them.  This allows developers to, for
example, define overloads for different numbers or types of arguments.  It would also allow them to return objects of a
which is like a collection in some sense but not exactly conforming to `Collection`; for example, 3D vectors. 

### Variations ###

If a Shepherd of this proposal feels that the flexibility of return type might be used in a confusing way, it may make
sense to require that the return type be some subtype of `Collection<T>` or `Map<K, V>`.  However, that's not necessary
for the above semantics to be usable. 

Likewise, the above description does not require the callable value bound to the special names to take a single
`varargs` argument, nor to have the same argument type for all its arguments.  Furthermore, for names bound to function
definitions, it does not require only a single definition, instead allowing for overloads.  Restricting the proposal in
any of those ways would be reasonable.

These restrictions could be removed or relaxed in future if desired.  


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

As discussed in the Open Questions section, it might be necessary to introduce a new `CollectionLiteralFunction`
annotation to mark definitions of these names which are intended for use in this proposal, to avoid unexpected
behaviour if any existing Kotlin code uses the special names in this proposal. 

The Kotlin Standard Library could add one or more definitions of the collection literal function names in namespaces or
objects probably under the `kotlin.collections` package.  It should not define those names directly in that package,
however.


## Impact ##

### Language Impact ###

#### Non-breaking ####

 * This proposal introduces some grammar that was previously unused and would not compile.
 * This proposal extends the use of existing grammar that was limited in scope.
 * This proposal does not change the behavior of any existing code nor bytecode.

### Code Impact ###

#### Breaking ####

The names of the collection literal functions were not previously reserved, so they might conflict with existing
code.  In particular, developers might use a collection literal and unintentionally have it call some function or other
callable their file was already importing.  It is an open question (below) how to avoid this issue. 


## Open Questions ##

OQ:CollectionLiteral.NonJvm: I haven't thought about whether there are any issues with this proposal when applied other
than to the JVM.  I expect not, since I haven't introduced any new semantics.

OQ:CollectionLiteral.ExistingUseOfNames: I'm not sure how to avoid unexpected behaviour in the case where a project
defines or imports existing definitions for the names `literalCollectionOf` and/or `literalMapOf` which happen to match
the required signature.  This isn't hypothetical: [PocketDB](https://github.com/utsmannn/pocketdb) defines a public
function `defaultCollectionOf` which was initially my choice of name for `literalCollectionOf`.  (A web search for the
names in the current proposal returns no results for either but private code bases might use them.)  I suspect it will
be necessary to mark in some way definitions of `literalCollectionOf` and `literalMapOf` intended for use with
collection literals.
* We could use the `operator` keyword which is often used to mark "conventions" such as functions implementing property
delegation.  That would restrict these names to being bound to function defintions, excluding function-valued
properties and to objects whose type overrides `operator invoke()`, but that might be acceptable or even preferable.
* We could add a specific new `CollectionLiteralFunction` annotation to the standard library and require that callable
definitions with those names must be annotated with that.  Any import of one of the special names which did not have
that annotation would result in a compiler warning or error, unless renamed on import.

OQ:CollectionLiteral.ForConst: I think this proposal allows collection literals to be used to initialise `const`
properties, provided the name `literalCollectionOf` resolves to an inlineable call which can be inferred to always
create an array.  The proposal could be changed to say that collection literals in such cases always create arrays but
that might be surprising in the immediate term, and it might lead to a breaking change in future if the language is
ever extended to allow other types to be used in initialising `const` properties.

OQ:CollectionLiteral.PreventListOfPair: I'm not sure whether the proposal needs to be extended to prevent developers
accidentally creating lists of `Pair<K, V>` objects, rather than maps, if they write
`["A" to 1, "B" to true, "C" to "three"]`.  One way to solve that would be to have the compiler issue a warning or
error if the member type inferred for a given call to `literalCollectionOf` is `Pair<K, V>` (or a subclass), unless
there is specifically an overload of `literalCollectionOf` in scope which takes `Pair<K, V>` (or a subclass) in all its
argument positions, and not any supertype.  (I considered having an alternative syntax for map-like literals, e.g.,
`#[ ... ]` to make the difference more obvious but that doesn't prevent the above error.)

OQ:CollectionLiteral.ReturnTypeConstraint.Variance: If it is deemed desirable to restrict the return type of callable
definitions of the special names, I'm not sure if or how the variance of the generic arguments of the return type
ought to be restricted.
 

## Alternatives Considered ##


## Design History ##

Following are the key Design Ideas (DI) and Constraints (DC) which led to my proposal.

DI:CollectionLiteral.ConstructionChoice.ByContext: Provide some way to vary the type created for collection literals by
lexical context.
* Assumption: A solution restricted to a single type for list-like collections and another single type for maps in any
given file or similar lexical context will still cover almost all use cases. 

DC:CollectionLiteral.ConstructionChoice.Discoverable: The choice of type creted in a given context would always be
clear to anyone reading the code: when looking at a collection literal there should be a very small number of other
textual places the reader would have to look to be able to work out the type.

DC:CollectionLiteral.ConstructionChoice.Stable: The choice would have to be fixed at compile time, rather than having
some global dynamic choice.  Otherwise, configuring a different choice in one module might affect the behaviour of
other external modules included, in ways the author of that module could not have anticipated.

DC:CollectionLiteral.ConstructionChoice.ModuleScope: It should be easy to make the same choice for all files in a given
module and, indeed, for all modules in a project. 

DC:CollectionLiteral.NonBreaking: The solution must not:
* break existing code;
* lead to existing code working but with different behaviour; nor
* lead to new code (using collection literals) working with unexpected behaviour. 

Some alternative solution ideas were as follows. 

DI:CollectionLiteral.ConstructionChoice.Annotation: I considered a new annotation which would only apply at file scope,
holding a reference to a function accepting varargs and returning a `Collection` or `Map`, and arranging for the
compiler to call that function whenever it encountered a collection literal in that file.  That doesn't provide an easy
way to configure the same default for an entire module, though, because there is no "module scope" for annotations.

DI:CollectionLiteral.ConstructionChoice.FixedFunctionName: After discovering and reading
[KEEP 112](https://github.com/Kotlin/KEEP/pull/112) I realised that having the collection literal syntax correspond to
calling a function with a fixed name might be a simpler approach.  A reader can then determine how an object is
constructed from a literal in the same ways they would determine the location and behaviour of any function: looking
for definitions within the local and file scope, or for internal module-level definitions.

DI:CollectionLiteral.ConstructionChoice.FixedCallableName: Extending the idea in KEEP 112, I realised that the
mechanism could be even more general in a trivial way if the name were allowed to refer to any object which could be
invoked, not only a function definition: the mechanism could also allow special names to refer to function-valued
properties and to objects whose type overrides `operator invoke()`.

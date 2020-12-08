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

### Common Project-Wide Choice ###

I expect that in many cases developers will want to use the same implementations throughout a module or multi-module
project.  Within a single module, or even for a multi-module application (not library) this could be achieved with an
internal "proxying" definition in the default package as follows.

```Kotlin
import some.other.pkg.literalCollectionOf as otherLiteralCollectionOf 
import some.other.pkg.literalMapOf as otherLiteralMapOf

inline fun <T> literalCollectionOf(vararg ts: T): Set<T> = otherLiteralCollectionOf(*ts)
inline fun <K, V> literalMapOf(vararg pairs : Pair<K, V>) : Map<K, V> = otherLiteralMapOf(*pairs)
```

In the case of multiple modules built for re-use as libraries, it doesn't make sense to put public definitions in the
default pacakge as that could clash when used together with other libraries.  Instead each file would have to import
the same definition, and might do so via "proxying" definitions like the above to reinforce standardisation.

### Static Variation ###

This proposal allows for the construction of literals in a given scope to vary depending on the number or type of the
arguments.  For example, you might want sets which are "sorted if possible", as follows.

```Kotlin
fun <T : Comparable<T>> literalCollectionOf(vararg ts: T): SortedSet<T> = sortedSetOf(*ts)
fun <T> literalCollectionOf(vararg ts: T): Set<T> = setOf(*ts)
fun <T> literalCollectionOf(): Set<T> = emptySet()
``` 

### Dynamic Variation ###

It is also possible to have the construction be intentionally variable at runtime.  For example, the following
definitions would allow some or all tests to modify behaviour of production code to use a less space-efficient map
implementation, to have predictable ordering when running tests. 

```Kotlin
@RequiresOptIn(message = "This API is experimental. It may be changed in the future without notice.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
annotation class TestParameter

@TestParameter
val useOrderedMaps = true

@OptIn(TestParameter::class)
fun <K, V> literalMapOf(vararg pairs : Pair<K, V>) : Map<K, V> {
    return if (useOrderedMaps) {
        mapOf(*pairs)
    } else {
        linkedMapOf(*pairs)
    }
}
```

Of course, testing behaviour in a way which differs from production is generally a bad idea, but this is just an
example.  You might want to configure the type created based on integrations with third-party libraries, or performance
considerations in different deployments, etc.

### Nested Variation ###

Although it's not a goal of the proposal, it allows for the same literal syntax to create different types of
collection at different levels of nesting, provided the types at each level can always be inferred based on the
innermost level.  In the following example, the type of `data` would be `List<Set<Any>>`.   

```Kotlin
fun <T, S : Set<T>> literalCollectionOf(vararg ts: S): List<S> = listOf(*ts)
fun <T> literalCollectionOf(vararg ts: T): Set<T> = setOf(*ts)

val data = [
    [1, 2, 3],
    ["one", "two", "three"],
    [1.0, 2.0, 3.0],
]
```

If this is deemed too confusing, it could be avoided by having the compiler require all overloads to accept the same
argument type, even if there are overloads with different numbers of arguments.  In that case, a similar effect could 


### Creating Non-Collection Types ###

Unless further restrictions are added to the proposal, it allows creating objects which are not collections; for
example, data class instances for use in test data.

```Kotlin
data class Employee(val name: String, val id: UInt)
data class Department(val name: String, val manager: Employee, val members: List<Employee>)
data class Company(val name: String, val units: List<Department>)

fun literalCollectionOf(name: String, id: UInt) = Employee(name, id)
fun literalCollectionOf(name: String, manager: Employee, members: List<Employee>) =
    Department(name, manager, members)
fun literalCollectionOf(name: String, units: List<Department>) =
    Company(name, units)
fun <T> literalCollectionOf(vararg ts: T) = listOf(*ts)

val data = ["MyCorp", [
    ["Sales",
        ["Noriko", 44u],
        [
            ["Barb", 21u],
            ["Jim", 34u],
        ]
    ],
    ["Dev",
        ["Angus", 104u],
        [
            ["Paolo", 2u],
            ["Mika", 99u]
        ]
    ]
]]
```

I found it confusing to construct this example, so I imagine it could be confusing in use.  A simpler approach which
would be almost as terse would be to define local properties with very short names, bound to constructor references.   

```Kotlin
val E = ::Employee
val D = ::Department
val C = ::Company
val data2 = C("MyCorp", [
    D("Sales",
        E("Noriko", 44u),
        [
            E("Barb", 21u),
            E("Jim", 34u),
        ]
    ),
    D("Dev",
        E("Angus", 104u),
        [
            E("Paolo", 2u),
            E("Mika", 99u),
        ]
    )
])
```

It is an Open Question (below) as to whether the compiler should prevent this.  It still has some uses which might be
very helpful in some domains, for example for 3D vector literals in tests, if they are represented by a data class. 

## Syntax ##

### Formal grammar ###

This builds on the formal Kotlin grammar in
[the Kotlin language grammar reference](https://kotlinlang.org/docs/reference/grammar.html).


```EBNF
(* Changes to Existing Grammar *)

collectionLiteral
    : listLikeLiteral
    : mapLikeLiteral
    ;


(* List-like Literals *)

listLikeLiteral
    : '[' expression (',' expression)* '.'? ']'
    : '[' ']'
    ;


(* Map-like Literals *)

mapLikeLiteral
    : '[' mapItem (',' mapItem)* '.'? ']'
    : '[' ':' ']'
    ;

mapItem
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

OQ:CollectionLiteral.PreventMixedTypeOverloads: As discussed above, it might be desirable to have the compiler issue
a warning or error if either special name is bound to something with overloads which have different argument types, as
opposed to just different numbers of arguments.  

OQ:CollectionLiteral.PreventMixedParamTypes: As discussed above, it might be desirable to have the compiler issue a
warning or error if any function value or overload bound to either special name has different types for different
arguments within a single function value or overload.  

OQ:CollectionLiteral.ReturnTypeConstraint: As discussed above, it might be desirable to have the compiler issue a
warning or error if any function value or overload bound to either special name returns a type which is not a
collection type.

OQ:CollectionLiteral.ReturnTypeConstraint.Variance: If it is deemed desirable to restrict the return type of callable
definitions of the special names, I'm not sure if or how the variance of the generic arguments of the return type
ought to be restricted.

OQ:CollectionLiteral.EnforceImportPackage: To enforce use of the same definitions across a project, it might be
desirable to have a compiler option to produce a warning or error if these special names are imported from or defined
outside a given package hierarchy prefix.  This could be particularly useful in the context of IntelliJ, where its
ability to automatically add imports for unresolved imports sometimes results in importing the symbol from an
unintended source.  That might furthermore need some option (perhaps an annotation) to escape from the restriction in
individual files, for example.
 

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

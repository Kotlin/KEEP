# Type aliases

## Goals

* Avoid repetition of types in source code without a cost of introducing extra classes.
    * Function types
    * Collections (and other complex generics)
    * Nested classes
    * ...

## Restrictions

Java has no notion of "type aliases" and can't see them in class member signatures.

For the same reason we can't enforce "newtype"
(type alias with limited assignment-compatibility with its underlying type)
in Java.
However, this would be possible with value types.

## Type alias declarations

Type aliases are declared using `typealias` keyword:
```
typeAlias
    : modifiers 'typealias' SimpleName typeParameters? '='
        type typeConstraints
```
Type aliases can be top-level declarations, member declarations, or local declarations.
```
toplevelObject
    : ...
    : typeAlias
    ;

memberDeclaration
    : ...
    : typeAlias
    ;

declaration
    : ...
    : typeAlias
    ;
```

Examples:
* Simple type aliases
```
typealias Int8 = Byte
typealias FilesTable = Map<String, MutableList<File>>
```
* Generic type aliases
```
typealias Predicate<in T> = (T) -> Boolean
typealias NodeBuilder<T : Any> = T.() -> DocumentationNode
typealias Array2D<T> = Array<Array<T>>
```
* Nested type aliases
```
class MyMap<K, out V> : Map<K, V> {
  typealias EntryCollection = Collection<Map.Entry<K, V>>
  private typealias KVPairs = Array<Pair<K, V>>
  // ...
}
```

## Type alias expansion

Type is *abbreviated* if it contains type aliases.
Type alias is *unabbreviated* if it doesn't contain type aliases.

Type alias can expand to a class, interface, or object:
```
typealias Int8 = Byte               // class
typealias Dict<V> = Map<String, V>  // interface
typealias Pred<T> = (T) -> Boolean  // interface (functional interface for (T) -> Boolean)
typealias IntC = Int.Companion      // object
typealias Id<T> = T                 // Error: type alias expands to a type parameter
```

> Type aliases expanding to a type parameter require special treatment in resolution.
> In fact, since generic type alias should use all of its type parameters,
> only one such type alias is possible:
>```
>typealias Type<T> = T
>```
> All its legal uses `Type<T>` can be replaced with just `T`.

Type aliases can't be recursive (including indirect recursion).
```
typealias R = R             // Error: recursive type alias

typealias T = List<T>       // Error: recursive type alias

typealias R1 = (Int) -> R2    // Error: recursive type alias
typealias R2 = (R1) -> Int    // Error: recursive type alias
```
For each abbreviated type there is a single unabbreviated type with all type aliases repeatedly eliminated.

Expansion for a type alias should be a well-formed type.
```
typealias IntIntList = List<Int, Int>       // Error: wrong number of type arguments

interface I<T : Any>
typealias NI = I<String?>                   // Error: upper bound violated

typealias Array2D<T> = Array<Array<T>>
typealias Illegal = Array2D<Nothing>        // Error: type 'Array<Nothing>' is illegal
```

Underlying type of generic type alias should use all of the declared type parameters:
```
typealias Encoded<E> = ByteArray         // Error: generic type parameter E is not used in underlying type
```
> Since type aliases are equivalent to underlying types,
> if a type alias `TA` with generic type parameters `G1, ..., Gn` doesn't depend on a type parameter `Gi`,
> well-formed instances of `TA` are equivalent regardless of type argument for `Gi`.
> E.g.:
>```
> interface Encoding
> object Utf8 : Encoding
> object Iso : Encoding
>
> typealias Encoded<E : Encoding> = ByteArray
>
> fun process(message: Encoded<Utf8>) { ... }    // (1)
> fun process(message: Encoded<Iso>) { ... }     // (2)
>```
> Here both `Encoded<Utf8>` and `Encoded<Iso>` are equivalent to `ByteArray`,
> so (1) and (2) are conflicting overloads.
>
> Note that "phantom types" can be implemented using value types (with minor boilerplate)
> or with "newtype" equivalent (which we can't enforce in Java).

Generic type aliases can't expand to a subclass of `kotlin.Throwable`.
> This implies from "no phantom types" restriction, since subclasses of `kotlin.Throwable` can't be generic.

### Error reporting

Diagnostic messages should preserve type aliases.


### ???

* Should we infer variance and/or bounds for generic type parameters of type aliases?
```
typealias Predicate<T> = (T) -> Boolean
// equivalent to 'typealias Predicate<in T> = (T) -> Boolean
```

## Type alias declaration conflicts and type alias constructors

Type alias declaration conflicts with another type alias declaration or a class (interface, object) declaration
with the same name (regardless of generic parameters).
```
class A             // Error: class A is conflicting with type alias A
typealias A = Any   // Error: type alias A is conflicting with class A
```

Type alias declaration conflicts with a property declaration with the same name.
```
typealias EmptyList = List<Nothing>     // Error: type alias EmptyList is conflicting with val EmptyList
val EmptyList = emptyList()             // Error: val EmptyList is conflicting with type alias EmptyList
```
> We consider classes and interfaces as possible hosts for companion objects,
> so the following is redeclaration:
>```
>class A        // Error
>val A          // Error
>```
> Same should be true for type aliases.

If a type alias `TA` expands to a top-level or nested (but not inner) class `C`,
for each (primary or secondary) constructor of `C` with substituted signature `<T1, ..., Tn> (P1, ..., Pm)`
a corresponding type alias constructor function `<T1, ..., Tn> TA(P1, ..., Pm)`
is introduced in the corresponding surrounding scope,
which can conflict with other functions with name `TA` declared in that scope.
```
class A                 // constructor A()
typealias B = A         // Error: type alias constructor B() is conflicting with fun B()
fun B() {}              // Error: fun B() is conflicting with type alias constructor B()
```
```
class V<T>(val x: T)                // constructor V<T>()
typealias ListV<T> = V<List<T>>     // Error: type alias constructor <T> ListV(List<T>) is conflicting with fun <T> ListV(List<T>)
fun <T> ListV(x: List<T>) {}        // Error: fun <T> ListV(List<T>) is conflicting with type alias constructor <T> ListV(List<T>)
```

If a type alias `TA` expands to an inner class `C1.(...).Cn.Inner`,
for each (primary or secondary) constructor of `Inner` with substituted signature `<T1, ..., Tn> (P1, ..., Pm)`
a corresponding type alias constructor extension function `<T1, ..., Tn> C1'.(...).Cn'.TA(P1, ..., Pm)`
(where `Ci'` is a `Ci` with substituted generic parameters)
is introduced in the corresponding surrounding scope.
```
class Outer { inner class Inner }
typealias OI = Outer.Inner  // Error: type alias constructor Outer.OI() is conflicting with fun Outer.OI()
fun Outer.OI() {}           // Error: fun Outer.OI() is conflicting with type alias constructor Outer.OI()
```
```
class G<T> { inner class Inner }
typealias SGI = G<String>.Inner     // Error: type alias constructor G<String>.SGI() is conflicting with fun G<String>.SGI()
fun G<String>.SGI() {}              // Error: fun G<String>.SGI() is conflicting with type alias constructor G<String>.SGI()
```
> NB inner classes can't have non-inner nested classes.

### ???
* Do we want type alias constructors to be visible from Java? E.g.:
```
// in file C.kt
class C
typealias T = C
```
```
// in Java
    C c = CKt.T(); // Returns new C()
```
* Do we want type alias (companion) objects to be visible from Java?
```
// in file Obj.kt:
object Obj
typealias T = Obj
```
```
// in Java
    Obj obj = ObjKt.getT(); // Retuns Obj.INSTANCE
```
> One objection that comes to mind is implied method count cost.
> Possibly we need special annotations for that
> (something like `@JvmConsctuctors`, `@JvmCompanionGetter`).

## Nested type aliases

Type aliases declared in classes, objects, or interfaces are nested.

Nested type aliases are inherited in child classes (objects, interfaces),
with corresponding underlying types substituted accordingly.
```
interface Map<K, V> {
  typealias KeySet = Set<K>
}

interface Dictionary<V> : Map<String, V> {
  // inherited typealias KeySet = Set<String>
}
```

Type alias declared in a child class (object, interface) shadows type alias declared in a super class (interface).
```
interface UndirectedVertexGraph<V> {
  typealias Edge = UnorderedPair<V>
}

interface DirectedVertexGraph<V> : UndirectedVertexGraph<V> {
  typealias Edge = Pair<V>      // shadows UndirectedVertexGraph<V>::Edge
}
```

## Type aliases and visibility

Type aliases can have the same visibility modifiers as other members of the corresponding scope:
* type aliases declared in packages can be `public`, `internal`, or `private` (public by default);
* type aliases declared in classes can be `public`, `internal`, `protected`, or `private` (public by default);
* type aliases declared in interfaces can be `public`, or `internal` (public by default);
* type aliases declared in objects can be `public`, `internal`, or `private` (public by default);
* block-level type aliases are local to a given block.

Type aliases can't be declared in annotation classes, since annotation classes can't have bodies.

Type aliases can't expose package or class members with more restricted visibility.
```
class C {
  protected class Nested { ... }
  typealias N = Nested // Error: typealias N exposes class A which is protected in C
}
```
```
internal class Private { ... }
typealias P = Private // Error: typealias P exposes class Private which is internal in module M
```

### ???

* Should type aliases themselves be subject to effective visibility restrictions?
```
private typealias A = () -> Unit
typealias B = A // Error: typealias B exposes typealias A which is private in file
```
> Most likely yes, to prevent leaking abstractions.

## Type aliases and resolution

Type aliases are treated as classifiers by resolution.
* When used as type, type alias represents corresponding unabbreviated type.
* When used as value, as function, or as qualifier in a qualified expression,
  type alias represents corresponding classifier.
> We need type aliases to work as underlying classes
> to prevent leaking abstraction for non-type positions.

### Example for type aliases used as corresponding classes (vs as types only)

https://github.com/orangy/komplex/blob/master/core/src/model/buildGraph.kt
```
typealias ProducersMap = HashMap<ArtifactDesc, BuildGraphNode>
typealias ConsumersMap = HashMap<ArtifactDesc, ArrayList<BuildGraphNode>>

@JvmName("producers_map_contains")
internal fun ProducersMap.contains(artifact: ArtifactDesc, scenario: Scenarios): Boolean = ...
         // typealias used as type
         // HashMap<ArtifactDesc, BuildGraphNode>.contains(...) = ...

@JvmName("consumers_map_contains")
internal fun ConsumersMap.contains(artifact: ArtifactDesc, scenario: Scenarios): Boolean = ...
         // typealias used as type
         // HashMap<ArtifactDesc, ArrayList<BuildGraphNode>>.contains(...) = ...

class BuildGraph() {
    // ...

    val producers = ProducersMap()
               // typelias used as a class constructor
               // : HashMap<ArtifactDesc, BuildGraphNode> = hashMapOf()

    val consumers = ConsumersMap()
               // typelias used as a class constructor
               // : HashMap<ArtifactDesc, ArrayList<BuildGraphNode>>() = hashMapOf()

    // ...
}
```

## Type aliases in signatures

Type aliases in type positions (function and property signatures, inheritance lists, etc)
are equivalent to the corresponding underlying types.
Thus, the following are errors:
```
typealias Str = String
typealias NStr = Str?
typealias StrSet = Set<Str>

class Error1 : Str                  // Error: final supertype
class Error2 : NStr                 // Error: nullable supertype
class Error3 : Set<Int>, StringSet  // Error: supertype appears twice

fun foo(s: StringSet) {}            // Error: conflicting overloads
fun foo(s: Set<String>) {}          // Error: conflicting overloads
```

If a function return type is `Nothing`, it should be specified explicitly in function declaration.
Type alias expanding to `Nothing` in a position of function return type is an error.
```
typealias Empty = Nothing

fun throws(): Empty = ...       // Error: return type Nothing should be specified explicitly
```

## Type aliases and reflection

TODO

### Type aliases and annotations

TODO

## Extensions in stdlib

TODO
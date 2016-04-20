# Type aliases

* **Type**: Design proposal
* **Author**: Dmitry Petrov
* **Contributors**: Andrey Breslav, Stanislav Erokhin
* **Status**: Under consideration
* **Prototype**: In progress

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/4).

## Use cases

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
    : modifiers 'typealias' SimpleName (typeParameters typeConstraints)? '='
        type
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
typealias Second<T1, T2> = T2       // Error: type alias expands to a type parameter
```

> Type aliases expanding to a type parameter require special treatment in resolution and are prohibited.

Type aliases can't be recursive (including indirect recursion).
```
typealias R = R                 // Error: recursive type alias

typealias L = List<L>           // Error: recursive type alias
typealias A<T> = List<A<T>>     // Error: recursive type alias

typealias R1 = (Int) -> R2      // Error: recursive type alias
typealias R2 = (R1) -> Int      // Error: recursive type alias
```
For each abbreviated type there is a single unabbreviated type with all type aliases repeatedly eliminated.

Expansion for a type alias should be a well-formed type.
```
typealias IntIntList = List<Int, Int>       // Error: wrong number of type arguments

interface I<T : Any>
typealias NI = I<String?>                   // Error: upper bound violated
typealias IT<T> = I<T>                      // Error: upper bound violated

typealias Array2D<T> = Array<Array<T>>
typealias Illegal = Array2D<Nothing>        // Error: type 'Array<Nothing>' is illegal
```

If an underlying type of generic type alias does not use an type parameter, it is a warning.
```
typealias Encoded<E> = ByteArray         // Warning: generic type parameter E is not used in underlying type
```
> We can't support phantom types, since type aliases are equivalent to underlying types.
>
>```
> interface Encoding
> typealias Encoded<E : Encoding> = ByteArray   // Warning
> object Utf8 : Encoding
> object Iso : Encoding
>
> fun processUtf8Encoded(data: Encoded<Utf8>) { ... }
>
> fun processIsoEncoded(data: Encoded<Iso>) {
>   // Both 'Encoded<Iso>' and 'Encoded<Utf8>' are expanded to 'ByteArray'.
>   // So, regardless of the design intent, the following call is ok:
>   processUtf8Encoded(data)
> }
>```
>
> However, if we prohibit such type aliases, it creates unnecessary long-term commitment for generic type aliases.
> Since it is not an error for generic classes,

## Type aliases and visibility

Type aliases can have the same visibility modifiers as other members of the corresponding scope:
* type aliases declared in packages can be `public`, `internal`, or `private` (public by default);
* type aliases declared in classes can be `public`, `internal`, `protected`, or `private` (public by default);
* type aliases declared in interfaces can be `public`, `internal`, or `private` (public by default);
* type aliases declared in objects can be `public`, `internal`, or `private` (public by default);
* block-level type aliases are local to the block.

> NB private type aliases declared in interfaces are visible inside private methods of the corresponding interface only.

Type aliases can't be declared in annotation classes, since annotation classes can't have bodies.

Type aliases can't expose package or class members (including other type aliases) with more restricted visibility.
```
class C {
  protected class Nested { ... }
  typealias N = Nested      // Error: typealias N exposes class A which is protected in C
}
```

```
internal class Hidden { ... }
typealias P = Hidden        // Error: typealias P exposes class Hidden which is internal in module M
```

```
class C
private typealias A = C     // C is public, but A is private in file
typealias AA = A            // Error: typealias AA exposes typealias A which is private in file
```

Type aliases can't be exposed by other class or package members with more permissive visibility.
```
class C
private typealias A = C     // C is public, but A is private in file

val x: A = ...              // Error: val x exposes typealias A which is private in file
fun foo(): A = ...          // Error: fun foo exposes typealias A which is private in file
```

## Type aliases and resolution
Type aliases are treated as classifiers by resolution.
* When used as type, type alias represents corresponding unabbreviated type.
* When used as value, as function, or as qualifier in a qualified expression,
  type alias represents corresponding classifier.
> We need type aliases to work as underlying classes to prevent leaking abstractions.

Type alias declaration conflicts with another type alias declaration or a class (interface, object) declaration
with the same name (regardless of generic parameters).
```
class A             // Error: class A is conflicting with type alias A
typealias A = Any   // Error: type alias A is conflicting with class A
```

### Type aliases as types

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

### Type aliases as values
Type alias as value represents the companion object of an underlying class or interface,
or an underlying object.
```
object MySingleton
typealias MS = MySingleton
val ms = MS         // OK, == MySingleton
```

```
class A
typealias TA = A
val ta = TA         // Error: type alias TA has no companion object
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

### Type aliases as constructors
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

> Instances of inner classes can be created only for an instance of the corresponding outer class:
>```
> class Outer { inner class Inner }
> val oi = Outer().Inner()
>```
> Thus, type alias constructors for type aliases expanding to inner classes are useful
> only as extension functions for an outer class.

```
class Outer { inner class Inner }
typealias OI = Outer.Inner
fun foo(): OI = Outer().OI()        // OK, == Outer().Inner()
```

```
class G<T> { inner class Inner }
typealias SGI = G<String>.Inner     // Error: type alias constructor G<String>.SGI() is conflicting with fun G<String>.SGI()
fun G<String>.SGI() {}              // Error: fun G<String>.SGI() is conflicting with type alias constructor G<String>.SGI()
```

## Nested type aliases

Type aliases declared in classes, objects, or interfaces are called nested.

Nested type aliases are inherited in child classes (objects, interfaces),
with corresponding underlying types substituted accordingly.
```
interface Map<K, V> {
  typealias KeySet = Set<K>
}

interface Dictionary<V> : Map<String, V> {
  fun keys(): KeySet = ...      // OK, KeySet == Set<String>
}
```

Type alias declared in a child class (object, interface) shadows type alias declared in a super class (interface).
```
interface UndirectedVertexGraph<V> {
  typealias Edge = UnorderedPair<V>
}

interface DirectedVertexGraph<V> : UndirectedVertexGraph<V> {
  typealias Edge = OrderedPair<V>      // shadows UndirectedVertexGraph<V>::Edge
}
```

Like nested classes, it is not possible to reference inherited nested type alias by a child class.
```
interface Base {
  typealias Nested = ...
}

interface Derived : Base {
  fun foo(): Nested = ...               // OK
}

fun test(): Derived.Nested = ...        // Error
```

## Type aliases and tooling

IDE and compiler should be fully aware of type aliases:
* Diagnostic messages
* Descriptor rendering in IDE (completion, structure view, etc)
* ...

## Type aliases and reflection

TODO

### Type aliases and annotations

TODO

## Extensions in stdlib

TODO

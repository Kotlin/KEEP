# Type aliases

* **Type**: Design proposal
* **Author**: Dmitry Petrov
* **Contributors**: Andrey Breslav, Stanislav Erokhin, Vladimir Reshetnikov
* **Status**: Under consideration
* **Prototype**: In progress

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/4).

## Use cases

Type aliases provide alternative names for existing types, for example:
* Function types
```
typealias MyHandler = (Int, String, Any) -> Unit

typealias HtmlBuilderAction = HtmlBuilder.() -> Unit

typealias Predicate<T> = (T) -> Boolean
```
* Collections (and other generics)
```
typealias NodeSet = Set<Network.Node>

typealias FilesTable<K> = MutableMap<K, MutableList<File>>
```
* Nested classes
```
class Outer {
  class Nested {
    inner class Inner
  }
}

typealias Something = Outer.Nested.Inner
```
and so on.

Type aliases do not introduce new types.
Instead, they are fully expanded, and are equivalent to the corresponding underlying types.

Use **value classes** to define new types (which are not assignment-compatible with the corresponding underlying types,
but do not introduce overhead related to additional heap allocations),
Value classes will be supported closer to [Project Valhalla](http://openjdk.java.net/projects/valhalla/) release.

**NB** Java has no concept of "type aliases" and can't see them in class member signatures.

## Type aliases and tooling

IDE and compiler should be fully aware of type aliases:
* Diagnostic messages
* Descriptor rendering in IDE (completion, structure view, etc)
* ...

## Type alias declarations

Type aliases are declared using `typealias` keyword:
```
typeAlias
    : modifiers 'typealias' SimpleName (typeParameters)? '=' type
    ;
```
Variance and constraints for type parameters of the generic type aliases are not allowed.
> Type alias can't introduce additional constraints for generic type parameters.
> Repeating constraints of the underlying types would be a boilerplate.
> If there is a constraint violation error during type alias expansion,
> it will be reported with additional details about type alias expansion context.

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
typealias Predicate<T> = (T) -> Boolean
typealias NodeBuilder<T> = T.() -> DocumentationNode
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
typealias Dyn = dynamic             // Error: type alias expands to 'dynamic'
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
typealias IT<T> = I<T>                      // Ok, but see below
typealias NIT = IT<String?>                 // Error: upper bound violated
                                            // (with detailed information on type alias expansion context)

typealias Array2D<T> = Array<Array<T>>
typealias Illegal = Array2D<Nothing>        // Error: type 'Array<Nothing>' is illegal
                                            // (with detailed information on type alias expansion context)
```

If an underlying type of generic type alias does not use an type parameter, it is a warning.
```
typealias Encoded<E> = ByteArray         // Warning: generic type parameter E is not used in underlying type
```
> Type aliases are equivalent to underlying types.
> So, in the example above, instances of `Encoded<E>` with different type arguments will be equivalent types:
>
>```
> interface Encoding
> typealias Encoded<E : Encoding> = ByteArray   // Warning
> object Utf8 : Encoding
> object Iso : Encoding
>
> fun processUtf8Encoded(data: Encoded<Utf8>) {}    // fun (data: ByteArray)
>
> fun processIsoEncoded(data: Encoded<Iso>) {       // fun (data: ByteArray)
>   processUtf8Encoded(data)                        // Ok
> }
>```
>
> However, if we prohibit such type aliases, it creates unnecessary long-term commitment for generic type aliases.
> NB this is not an error for generic classes.

Type arguments of a generic type alias should be well-formed types (type projections).
```
interface NN<T : Any>
typealias Predicate<T> = (T) -> Boolean

typealias E1 = Predicate<NN<Int, Int>>          // Error: wrong number of type arguments
typealias E2 = Predicate<NN<Int?>>              // Error: upper bound violated
```

Type arguments of generic type aliases are substituted syntactically.
```
typealias Dictionary<V> = Map<String, V>
typealias Predicate<T> = (T) -> Boolean     // = Function1<T, Boolean>
typealias TMap<T> = Map<T, T>
typealias Array2D<T> = Array<Array<T>>

typealias T1 = Dictionary<*>                // Map<String, *>

typealias T2 = Predicate<*>                 // Ok (but not very useful):
                                            // Function1<*, Boolean> = Function1<Nothing, Boolean>

typealias T3 = TMap<*>                      // Map<*, *>

typealias T4 = Array2D<out Number>          // Array<Array<out Number>>
```
> Type aliases are not types, but rather "macros" on types.
> Type alias behavior for projection arguments can be counter-intuitive.
> E.g.:
>```
> typealias Array2D<T> = Array<Array<T>>
>
> fun foo(a: Array2D<out Number>) {}    // fun (a: Array<Array<out Number>>)
>
> fun bar(a: Array2D<Int>) {            // fun (a: Array<Array<Int>>)
>   foo(a)                              // Error: type mismatch
> }
>```
>
> **NB** `Array2D<T>` above is an example of bad usage of type aliases:
> it exposes representation of some high-level concept ("two-dimensional array").
> which causes leaking abstractions.
> Make `Array2D<T>` a class (or, better, an interface) to capture abstractions properly.
>
>```
> typealias TMap<T> = Map<T, T>
>
> fun <T> foo(m: TMap<T>) {}         // fun <T> (m: Map<T, T>)
>
> fun bar(m: TMap<*>) {              // fun     (m: Map<*, *>)
>     foo(m)                         // Error: cannot infer type parameter T
> }
>```
>
> In this example, information that both parameters of `Map<T, T>` are the same type is lost.
> It could be preserved by introducing existential types (`TMap<*> = exists T : Any?. Map<T, T>`),
> but this would require full existential types support in the type system.

## Type aliases and visibility

Type aliases can have the same visibility modifiers as other members of the corresponding scope:
* type aliases declared in packages can be `public`, `internal`, or `private` (public by default);
* type aliases declared in classes can be `public`, `internal`, `protected`, or `private` (public by default);
* type aliases declared in interfaces can be `public`, `internal`, or `private` (public by default);
* type aliases declared in objects can be `public`, `internal`, or `private` (public by default);
* block-level type aliases are local to the block.

Type aliases can't be declared in annotation classes, since annotation classes can't have bodies.

Type aliases can't expose package or class members (excluding other type aliases) with more restricted visibility.
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
private typealias A = C     // C is public, A is private in file
typealias AA = A            // Expanded type for AA is C, thus, no error.
```

## Resolution

Type aliases are treated as classifiers by resolution.
* When used as type, type alias represents corresponding unabbreviated type.
* When used as value or as function, type alias represents corresponding classifier.
* When used as a qualifier in a qualified expression, type alias represents corresponding classifier
with the following restriction:
nested classifiers can't be referenced via type alias qualifier.

> We need type aliases to work as underlying classes to prevent leaking abstractions.
>
> Resolution should treat type aliases and classifiers similarly.
>
> See [Nested type aliases](#nested-type-aliases) section for related issues & discussion.

Type alias declaration conflicts with another type alias declaration or a class (interface, object) declaration
with the same name (regardless of generic parameters).
```
class A             // Error: class A is conflicting with type alias A
typealias A = Any   // Error: type alias A is conflicting with class A
```

### Type aliases as types

Type aliases in type positions (function and property signatures, inheritance lists, etc)
are expanded to the corresponding unabbreviated types.
```
typealias Str = String

val hello: Str = "Hello, world!"    // 'hello' has type 'kotlin.String' denoted as 'Str'
```

All relevant restrictions are checked for unabbreviated types. Thus, the following are errors:
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
Type alias expanding to `Nothing` in a position of function return type or property type is an error.
```
typealias Empty = Nothing

fun throws(): Empty = ...       // Error: return type Nothing should be specified explicitly
val alsoThrows: Empty =
```

### Type alias companion object

Type alias as value represents the companion object of an underlying class or interface,
or an underlying object.
```
object MySingleton
typealias MS = MySingleton
val ms = MS                 // OK, == MySingleton
```

```
class A
typealias TA = A
val ta = TA                 // Error: type alias TA has no companion object
```

If a generic type alias expands to a class with companion object, type arguments should be omitted.
```
class GenericWithCompanion<T> {
  companion object {
    val magic = 42
  }
}

typealias G<T> = GenericWithCompanion<T>
val magic = G.magic        // OK, = 42
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

### Type alias constructors

If a type alias `TA` expands to a top-level or nested (but not inner) class `C`,
for each (primary or secondary) constructor of `C` with substituted signature `<T1, ..., Tn> (P1, ..., Pm)`
a corresponding type alias constructor function `<T1, ..., Tn> TA(P1, ..., Pm)`
is introduced in the corresponding surrounding scope,
which can conflict with other functions with name `TA` declared in that scope.
```
class A                 // constructor A()
typealias B = A         // Error: type alias constructor B() is conflicting with fun B()
fun B() {}              // Error: fun B() is conflicting with type alias constructor B()

class V<T>(val x: T)                // constructor V<T>()
typealias ListV<T> = V<List<T>>     // Error: type alias constructor <T> ListV(List<T>) is conflicting with fun <T> ListV(List<T>)
fun <T> ListV(x: List<T>) {}        // Error: fun <T> ListV(List<T>) is conflicting with type alias constructor <T> ListV(List<T>)
```

### Type alias constructors for inner classes

> Question: how should we better deal with the type arguments?
> Example:
>
>```
> class Outer<T1> {
>   inner class Inner<T2>
> }
> typealias OI<T1, T2> = Outer<T1>.Inner<T2>
> val outer = Outer<Int>()
> val inner = outer.OI< ... >()     // <Int, Int>? Something else?
>```
> NB: different combinations of type parameter substitutions are possible.
> Most likely we'll have to constrain type alias constructors for inner classes somehow.

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

### Type aliases and Java static members

Scope for type alias expanding to the Java class in a qualifier position is a static member scope of the corresponding class.
```
typealias CompletableFuture<T> = java.util.concurrent.CompletableFuture<T>

fun test() {
    CompletableFuture.runAsync { /* ... */ }
}
```

### Type aliases and super qualifiers

Type alias in super qualifier is resolved to the corresponding class or interface.
Type arguments are ignored.
This class should be a parent class or interface of the current class or interface.

```
interface IFoo1<in T> {
    fun foo(x: T) {
        println("IFoo1")
    }
}

interface IFoo2<in T> {
    fun foo(x: T) {
        println("IFoo2")
    }
}

typealias T1<T> = IFoo1<T>
typealias T2<T> = IFoo2<T>

class C<T>: T1<T>, T2<T> {
    override fun foo(x: T) {
        super<T1>.foo(x)        // super<IFoo1>.foo(x)
        super<T2>.foo(x)        // super<IFoo2>.foo(x)
        super<T1String>.foo(x)  // Not an error; super<IFoo1>.foo(x)
    }
}
```

> NB type arguments can't be specified in super qualifier for classes and interfaces.

## Nested type aliases

Type aliases declared in classes, objects, or interfaces are called nested.
Same resolution rules are applied for nested type aliases as for other nested classifiers.

> This causes problems with nested type aliases in interfaces, see:
>
> * [KT-13247 Type aliases inherited from interfaces](https://youtrack.jetbrains.com/issue/KT-13247)
>
> Main issue is about to companion objects hierarchy and related static scopes:
> ```
> interface Base<T> {
>     typealias List = List<T> 
> }
>
> abstract class X {
>     companion object : Base<String>
> }
>
> absctract class Y : X(), Base<Int>
>
> class Z : Y() {
>     val z: List
>     // Can be resolved as 'Base<Int>.List' (from Y : Base<Int>)
>     // or as 'Base<String>.List' (from X.Companion : Base<String>)
> }
> ```
>
> Note that unlike Scala, Kotlin doesn't have linearization rules for inherited members so far.

## Type aliases in binary metadata

Abbreviated types should be present in the serialized descriptors.

> Note that additional check is required: if a type alias expansion gives a different type,
> we can't use corresponding abbreviated form (due to incompatible changes).
> We would still be able to compile against such binaries, just without abbreviated types in diagnostics.
> Probably this should be a warning.
>
> Example:
> ```
> // file: a.kt
> typealias A = Int
> ```
> ```
> // file: b.kt
> val x: A = 0
> ```
> Now suppose `a.kt` is changed, and type alias `A` is now defined like:
> ```
> typealias A = Number
> ```
> and, for some reason, `b.kt` is not recompiled.
> Then `x` in `b.kt` has type `kotlin.Int`, but its abbreviated form `A` expands to `kotlin.Number`.
> We still can use `x` as `kotlin.Int`, but a warning should be reported
> to indicate that there's something wrong with the dependencies.

## Type aliases and reflection

TODO do we need "type alias literals"?

>```
>typealias MyAlias = MyClass
> 
>... println(MyAlias::typealias.simpleName) ...
>```

### Reflection API for type aliases

> Most likely something like:
>
> in kotlin.reflect.KType:
>```
>   /**
>    * Type abbreviation used to denote this type in the source code definition.
>    * May contain type aliases.
>    *
>    * `null` if this type is represented as is.
>    */
>   val abbreviatedType: KType?
>```   
>
>```
>public interface KTypeAlias<T : Any> : KAnnotatedElement, KClassifier {
>    /**
>     * Simple name of the type alias as declared in the source code.
>     */
>    public val simpleName: String
>
>    /**
>     * Fully-qualified dot-separated name of the type alias,
>     * or 'null' if type alias is local or a member of a local class.
>     */
>    public val qualifiedName: String?
>
>    /**
>     * Right-hand side of the type alias definition.
>     * May contain type aliases and type parameters.
>     */
>    public val underlyingType: KType
>
>    /**
>     * Fully expanded type corresponding to this type alias.
>     * May contain type parameters of the corresponding type alias.
>     * May not contain type aliases.
>     */
>    public val expandedType: KType
>
>    /**
>     * Class corresponding to this type alias.
>     */
>    public val correspondingClass: KClass<T>
>}
>
>```

### Type aliases and annotations

Special annotation target for annotations applicable to type aliases is required:
In kotlin.annotation.AnnotationTarget:
```
    /** Type alias */
    TYPEALIAS,
```

In JVM, annotations for type aliases are written on a special synthetic method (similar to annotations on properties).

Standard language annotations for declarations (`kotlin.Deprecated`, `kotlin.SinceKotlin`, `kotlin.Suppress`, etc.) are applicable to type aliases:
```
@Deprecated("For some good reason")
typealias Str = String

@Suppress("UNUSED_TYPEALIAS_PARAMETER")
typealias Repr<T> = IntArray
```
# Collection Literals #

* **Type**: Design proposal
* **Author**: [Ben Leggiero](https://github.com/BenLeggiero)
* **Contributors**: [alanfo](https://github.com/alanfo), [Andrey Mischenko](https://github.com/gildor), and many others
  on [the GitHub Gist](https://gist.github.com/BenLeggiero/1582a959592cadcfee2a0beba3820084) and in the
  [#language-proposals](https://kotlinlang.slack.com/messages/language-proposals/) Slack channel
* **Status**: Under consideration
* **Prototype**: Not started
* **Discussion**: [KEEP #112](https://github.com/Kotlin/KEEP/issues/112)



| Table of Contents |
| :---------------- |
| <ol><li>[Goal](#goal)</li><li>[Feedback](#feedback)</li><li>[Introduction](#introduction)</li><li>[Motivation](#motivation)</li><li>[Syntax](#syntax)<ol type=a><li>[Formal grammar](#formal-grammar)</li><li>[Sequence Literals](#sequence-literals)</li><li>[Dictionary Literals](#dictionary-literals)</li></ol></li><li>[Changes to stdlib](#changes-to-stdlib)</li><li>[Language Impact](#language-impact)</li><li>[Alternatives Considered](#alternatives-considered)</li></ol> |



## Goal ##

To make collections easier to write, more expressive, and better able to take advantage of the typing system in Kotlin
by adding collection literals to the language.



## Feedback ##

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/112).



## Introduction ##

This proposal introduces two new syntaxes: A literal for sequences and a literal for dictionaries. These both would be
customizable via two new operator functions, and the creation of them in code would thus have the same
type-ambiguity-resolution as if said functions were called as themselves rather than as operators for these new
literals.

Sequence literals would look like this:

```Kotlin
val myList = [1, true, "three"]
val myArray: Array<List<Int>> = [[1, 2], [], [3, 4, 5]]
val myEmptySet: Set<String> = []
```

Dictionary literals would look like this:

```Kotlin
val myMap = ["A": 1, "B": true, "C": "three"]
val myHashMap: HashMap<String, List<String>> = ["Foo": ["Bar", "Baz"]]
val myEmptyMutableMap: MutableMap<String> = [:]
```

See [Syntax](#syntax) below for elaboration on what these mean. Note that dictionaries could have an
[alternative syntax](#alternative-syntax) if the above syntax cannot be used.



## Motivation ##

* The current approach (stdlib global factory methods) is very wordy
* The current approach does not leverage the powerful type inference features of Kotlin
* Other modern languages (Python, Groovy, Swift, ECMAScript, etc.) have collection literals similar to this proposal
* Kotlin currently supports identical sequence literal syntax in the arguments of annotations
* This would provide Kotlin library writers a standard, more expressive way to allow their users to instantiate custom
  collection types



## Syntax ##

### Formal grammar ###

This builds atop the formal Kotlin grammar as laid out in [the Kotlin language grammar reference](https://kotlinlang.org/docs/reference/grammar.html).



```EBNF
(* Changes to Existing Grammar *)

collectionLiteral
    : sequenceLiteral
    : dictionaryLiteral
    ;



(* Shared Amongst All Collection Literals *)

collectionItem
    : atomicExpression
    ;



(* Sequence Literals *)

sequenceLiteral
    : emptySequenceLiteral
    : sequenceLiteralWithOneItem
    : sequenceLiteralWithMultipleItems
    ;

emptySequenceLiteral
    : "[" "]"
    ;

sequenceLiteralWithOneItem
    : "[" collectionItem "]"
    ;

sequenceLiteralWithMultipleItems
    : "[" collectionItem{","} ","? "]"
    ;



(* Dictionary Literals *)

dictionaryLiteral
    : emptyDictionaryLiteral
    : dictionaryLiteralWithOneItem
    : dictionaryLiteralWithMultipleItems
    ;

emptyDictionaryLiteral
    : "[" ":" "]"
    ;

dictionaryLiteralWithOneItem
    : "[" dictionaryItem "]"
    ;

dictionaryLiteralWithMultipleItems
    : "[" dictionaryItem{","} ","? "]"
    ;

dictionaryItem
    : collectionItem ":" collectionItem
    ;
```

<sup>Dictionaries may have an [alternative syntax](#alternative-syntax) if the above syntax cannot be used.</sup>



### Implementation ###

### Typing ###

This leverages the powerful type inference of Kotlin to determine which type the literal represents. If none can be
inferred, sequence literals are assumed to be `List`s, and dictionary literals are assumed to be `Map`s. The exact
underlying implementation types of these are unimportant, and the creation of them can be implemented in stdlib as
global operator functions.



### Sequence Literals ###

Sequence literals represent linear collections.

> * Empty sequence: `[]`
> * 1 element: `[ a ]`
> * Multiple elements: `[ a, b, c ]`

When no type is specified, the default type of `List` is used, and its generic element type is inferred from the
literal's contents, just like the `listOf` functions do today:

```Kotlin
val a1 = ["a", "b", "c"] // List<String>
val a2 = [1, 2, 3] // List<Int>
val a3 = [true, false] // List<Boolean>
val a4 = ["a", 2, false, null] // List<Any?>
val a5 = [] // Compiler error: type cannot be inferred (just like `listOf()`). See below
val a6 = [[1], [2, 3], [4, 5, 6]] // List<List<Int>>
```

When a type is specified, and that type implements the proper operator function [(see below)](#custom-sequence-types),
that type is used:

```Kotlin
val b1: Array<String> = ["a", "b", "c"] // Array<String>
val b2 = [1, 2, 3] as MutableList<Int> // MutableList<Int>
val b3: Set<Boolean> = [true, false, true] // Set<Boolean> with size 2
val b4: Array<Any> = ["a", "b", "c"] // Array<Any>
val b5: List<Set<Byte>> = [] // empty List<List<Any>>
val b6: Array<String> = ["a", 2, false] // Compiler error: `2` and `false` are not `Strings`s
val b7: MyCustomSequence = [foo, bar, baz] // MyCustomSequence (see below)
val b8: IntArray = [1, 2, 3] // IntArray
```

Of course, this means that the type can be implied at the call-site when using functions which can accept a sequence
literal, or when returning from a function with an explicit return type, etc.:

```Kotlin
takesStringArray(["a", "b", "c"])
takes2DList([["d", "e"], []])
takesStringArray(["a", 2, false]) // Compiler error: `2` and `false` are not `String`s
takesCustomSequence([foo, bar, baz])
takesAmbiguousType(["1", true, 3] as Set<Any>) // The `as` keyword is one way to resolve compile-time ambiguity
```


#### Custom Sequence Types ####

Any type `Type` can be represented by this syntax by implementing the `operator fun sequenceLiteral`. This operator
function can take zero, one, multiple, or variable number of arguments of type `Element`, and must return `Type`, where
`Element` is the type of the sequence's elements (this can be generic). This must be implemented via a top-level
function. `Type` does not necessarily have to implement the `Collection` interface.

[This proposal recommends](#changes-to-stdlib) that the stdlib include many of these in order to make it feel more natural.

For example:

```Kotlin
enum class EntryKey {
    foo,
    bar,
    baz
}

class MyCustomSequence(val serial: String)

operator fun sequenceLiteral(vararg elements: EntryKey)
    = MyCustomSequence(serial = if (elements.isEmpty()) "" else elements.map { it.ordinal }.joinToString(""))

// Later...

fun takeCustomSequence(sequence: MyCustomSequence) {
    println(sequence.serial)
}


takeCustomSequence([EntryKey.foo, EntryKey.baz]) // prints "02"
```

As stated before, these are also valid sequence literal operator functions:

```Kotlin
// Called for all `[]` whose type is inferred to be `MyCustomSequence`
operator fun sequenceLiteral() = MyCustomSequence("")

// Called for all `[ a ]` where `a` is an `EntryKey`
operator fun sequenceLiteral(onlyItem: EntryKey) = MyCustomSequence("${onlyItem.ordinal}")

// Called for all `[ a, b ]` where `a` and `b` are `EntryKey`s
operator fun sequenceLiteral(a: EntryKey, b: EntryKey)
    = MyCustomSequence("${a.ordinal}${b.ordinal}")

// For all other sequence literals, the previously-defined one which takes `vararg`s is called.
// If there were no such function taking `vararg`s, then only sequences of these sizes would be allowed.
```

These are allowed to co-exist in the same codebase, just as the functions would be without the `operator` keyword, to
grant the library writer the ability to optimize for certain scenarios, or to restrict the number of elements allowed
in a sequence.

As another example, if one made a non-empty-list type, it might use an operator function like this:

```Kotlin
// Allows `[ a ]` and `[ a, b, c ]`, but not `[]`
operator fun sequenceLiteral(first: Element, vararg rest: Element)
    = NonEmptyList<Element>(first = first, rest = *rest)
```



### Dictionary Literals ###

Dictionary literals represent collections of key-value pairs.

> * Empty dictionary: `[:]`
> * 1 pair: `[ a : b ]`
> * Multiple pairs: `[ a : b, c : d, e : f ]`

<sup>(Note that there is [an alternate syntax](#alternative-syntax) if this cannot be achieved)</sup>

This has very similar semantics to sequence literals. The default type if none is specified is `Map`, just like the
`mapOf` function today, where its generic types are inferred.

```Kotlin
val d1 = ["a" : "b", "c" : "d"] // Map<String, String>
val d2 = [1 : 2, 3 : 4] // Map<Int, Int>
val d3 = [true : false] // Map<Boolean, Boolean>
val d4 = ["a" : 2, "b" : false, "c" : null] // Map<String, Any?>
val d5 = [:] // Compiler error: type cannot be inferred (just like `mapOf()`). See below
```

When a type is specified, and that type implements the proper operator function [(see below)](#custom-dictionary-types), that type is used:

```Kotlin
val e1: MutableMap<String, String> = ["a" : "b", "c" : "d"] // MutableMap<String, String>
val e2 = [1 : 2, 3 : 4] as Map<Int, Any> // Map<Int, Any>
val e3: MutableMap<String, Any?> = ["a" : 2, "b" : false, "c" : null] // MutableMap<String, Any?>
val e4: HashMap<String, String> = [:] // empty HashMap<String, String>
val e7: MyCustomDictionary = [foo : bar, baz : qux] // MyCustomDictionary (see below)
```

Of course, this means that the type can be implied at the call-site when using functions which can accept a sequence literal:

```Kotlin
takesMutableMapOfStringToString(["a" : "b", "c" : "d"])
takesMapOfIntToAny([1 : 2, 3 : 4])
takesHashMap([:])
takesCustomDictionary([foo : bar, baz : qux])
takesAmbiguousType(["1" : true, "3" : "blue"] as HashMap<String : Any>) // The `as` keyword is one way to resolve compile-time ambiguity
```


#### Custom Dictionary Types ####

Any type `Type` can be represented by this syntax by implementing the `operator fun dictionaryLiteral`. This operator
function can take zero, one, multiple, or a variable number of arguments of type `Pair<Key, Value>`, where `Key` is the
type of the dictionary's keys, and `Value` is the type of its values (these can be generics). This must be implemented
via a top-level function. `Type` does not necessarily have to implement the `Map` nor `Collection` interface.

[This proposal recommends](#changes-to-stdlib) that the stdlib include many of these in order to make it feel more natural.

For example:

```Kotlin
enum class EntryKey {
    foo,
    bar,
    baz
}

data class MyEntry<Value>(val key: EntryKey, val value: Value)

class MyCustomDictionary(val entries: List<MyEntry<*>>)

operator fun <CommonValueBase> dictionaryLiteral(vararg elements: Pair<EntryKey, CommonValueBase>)
    = MyCustomDictionary(entries = elements.map { MyEntry<CommonValueBase>(it.first, it.second) })

// Later...

fun takeCustomDictionary(dictionary: MyCustomDictionary) {
    println(dictionary.entries.map { "${it.key}${it.value}" })
}


takeCustomDictionary([EntryKey.foo : "a", EntryKey.baz: false]) // prints "[fooa, bazfalse]"
```

As stated before, these are also valid dictionary literal operator functions:

```Kotlin
// Called for all `[:]` whose type is inferred to be `MyCustomDictionary`
operator fun dictionaryLiteral() = MyCustomDictionary(emptyList())

// Called for all `[ a : b ]` where `a` is an `EntryKey`
operator fun dictionaryLiteral(onlyItem: Pair<EntryKey, *>)
    = MyCustomDictionary(listOf(MyEntry(onlyItem.first, onlyItem.second)))

// Called for all `[ a : b, c : d ]` where `a` and `c` are `EntryKey`s
operator fun dictionaryLiteral(ab: Pair<EntryKey, *>, cd: Pair<EntryKey, *>)
    = MyCustomDictionary(listOf(MyEntry(ab.first, ab.second), MyEntry(cd.first, cd.second)))

// For all other dictionary literals, the previously-defined one which takes `vararg`s is called.
// If there were no such function taking `vararg`s, then only dictionaries of these sizes would be allowed.
```

These are allowed to co-exist in the same codebase, just as the functions would be without the `operator` keyword, to
grant the library writer the ability to optimize for certain scenarios, or to restrict the number of pairs allowed in a
dictionary.

As another example, if one made a non-empty-map type, it might use an operator function like this:

```Kotlin
// Allows `[ a : b ]` and `[ a : b, c : d ]`, but not `[:]`
operator fun <Key, Value> dictionaryLiteral(first: Pair<Key, Value>, vararg rest: Pair<Key, Value>)
    = NonEmptyMap<Key, Value>(first = first, rest = *rest)
```


#### Alternative Syntax ####

In the event that the `:` cannot be used to separate a key form a value for any reason, `=` is
acceptable. That said, this would diverge from the syntax of dictionary literals in all of Kotlin's contemporaries.

```Kotlin
val myMap = ["A" = 1, "B" = true, "C" = "three"]
val myHashMap: HashMap<String, List<String>> = ["Foo" = ["Bar", "Baz"]]
val myEmptyMutableMap: MutableMap<String> = [=]
```



## Changes to stdlib ##

The Kotlin Standard Library should add sequence literal operator functions which perform the same function as the
following (or simply call the following). This proposal also recommends that these existing functions be marked as
deprecated (in which case the following should call the new ones):

 * `emptyList()` and `listOf()`
 * `listOf(vararg elements: T)`
 * `listOf(element: T)`
 * `mutableListOf()`
 * `arrayListOf()`
 * `mutableListOf(vararg elements: T)`

 * `arrayListOf(vararg elements: T)`

 * `emptyArray()`
 * `arrayOf(vararg elements: T)`
 * `doubleArrayOf(vararg elements: Double)`
 * `floatArrayOf(vararg elements: Float)`
 * `longArrayOf(vararg elements: Long)`
 * `intArrayOf(vararg elements: Int)`
 * `charArrayOf(vararg elements: Char)`
 * `shortArrayOf(vararg elements: Short)`
 * `byteArrayOf(vararg elements: Byte)`
 * `booleanArrayOf(vararg elements: Boolean)`


 * `emptyMap()` and `mapOf()`
 * `mapOf(vararg pairs: Pair<K, V>)`
 * `mapOf(pair: Pair<K, V>)`
 * `mutableMapOf()`
 * `mutableMapOf(vararg pairs: Pair<K, V>)`
 * `hashMapOf()`
 * `hashMapOf(vararg pairs: Pair<K, V>)`
 * `linkedMapOf()`
 * `linkedMapOf(vararg pairs: Pair<K, V>)`

 And any other functions which would be more naturally expressed as a sequence literal.



## Language Impact ##

**Non-breaking**:
 * This proposal introduces some grammar that was previously unused and would not compile.
 * This proposal extends the use of existing grammar that was limited in scope.
 * This proposal does not change the behavior of any existing code nor bytecode.


## Alternatives Considered ##

The following alternative syntaxes were also mentioned in various discussions:

 * JavaScript style dictionaries, like `{ a : b, c : d }`
   * Inconsistent with the current association of square brackets with collections, where curly
     braces are associated with scopes
 * Prefix dictionaries with a `#` symbol, like `#[ a : b, c : d ]`
   * Seems to add unnecessary code with no apparent benefit
 * Make the default type for sequences an `Array` instead of a `List`
   * Would clash with existing Kotlin paradigms and stdlib functions
 * Use `to` instead of `:` in dictionary literals
   * No natural way to distinguish a literal for a `List<Pair<*, *>>` from a `Map<*, *>`
 * Allow a builder pattern as an argument to the sequence- and dictionary-literal operator functions
   * A good idea, but not fleshed-out. Acceptance and implementation of this proposal does not
     prevent this feature from being added in the future.

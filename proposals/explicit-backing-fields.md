# Explicit Backing Fields

- **Type**: Design Proposal
- **Author**: Nikolay Lunyak, Roman Elizarov
- **Contributors**: Svetlana Isakova, Kirill Rakhman, Dmitry Petrov, Roman Elizarov, Ben Leggiero, Matej Drobnič, Mikhail Glukhikh, Nikolay Lunyak
- **Status**: Prototype implemented in FIR
- **Initial YouTrack Issue**: [KT-14663](https://youtrack.jetbrains.com/issue/KT-14663)
- **Initial Proposal**: [private_public_property_types#122](https://github.com/Kotlin/KEEP/pull/122)

## Summary

Sometimes, Kotlin programmers need to declare two properties which are conceptually the same,
but one is part of a public API and another is an implementation detail. 
This pattern is known as [backing properties](https://kotlinlang.org/docs/properties.html#backing-properties):

```kotlin
class C {
    private val _elementList = mutableListOf<Element>()

    val elementList: List<Element>
        get() = _elementList
}
```

With the proposed syntax in mind, the above code snippet could be rewritten as follows:

```kotlin
class C {
    val elementList: List<Element>
        field = mutableListOf()
}
```

## Table of contents

<!--- TOC -->

* [Use Cases](#use-cases)
  * [Expose read-only subtype](#expose-read-only-subtype)
  * [Decouple storage type from external representation](#decouple-storage-type-from-external-representation)
  * [Expose read-only view](#expose-read-only-view)
  * [Access field from outside of getter and setter](#access-field-from-outside-of-getter-and-setter)
* [Design](#design)
  * [Explicit Backing Fields](#explicit-backing-fields)
    * [Restrictions](#restrictions)
    * [Accessors](#accessors)
    * [Visibility](#visibility)
    * [Lateinit](#lateinit)
  * [Smart Type Narrowing](#smart-type-narrowing)
* [Alternatives](#alternatives)
  * [Initial Proposal](#initial-proposal)
* [Future Enhancements](#future-enhancements)
  * [Direct Backing Field Access](#direct-backing-field-access)
  * [Protected Fields](#protected-fields)
  * [Mutable Fields for Read-only Properties](#mutable-fields-for-read-only-properties)

<!--- END -->

## Use Cases

This proposal caters to a variety of use-cases that are currently met via a backing property pattern.

### Expose read-only subtype

We often do not want our data structures to be modified from outside. It is customary in Kotlin to have 
a read-only (e.g. `List`) and a mutable (e.g. `MutableList`) interface to the same data structure. 

```kotlin
internal val _items = mutableListOf<Item>()
val item : List<Item> by _items
```

And the new syntax allows us to write:

```kotlin
val items: List<Item>
    internal field = mutableListOf()
```

This use-case is also widely applicable to architecture of reactive applications:
 
* Android `LiveData` has a `MutableLiveData` counterpart.
* Rx `Observable` has a mutable `Subject` counterpart.
* Kotlin coroutines `SharedFlow` has a `MutableSharedFlow`, etc.

For example, sample code [from an Android app](https://github.com/elpassion/crweather/blob/9c3e3cb803b7e4fffbb010ff085ac56645c9774d/app/src/main/java/com/elpassion/crweather/MainModel.kt#L14):

```kotlin
private val mutableCity = MutableLiveData<String>().apply { value = "" }
private val mutableCharts = MutableLiveData<List<Chart>>().apply { value = emptyList() }
private val mutableLoading = MutableLiveData<Boolean>().apply { value = false }
private val mutableMessage = MutableLiveData<String>()

val city: LiveData<String> = mutableCity
val charts: LiveData<List<Chart>> = mutableCharts
val loading: LiveData<Boolean> = mutableLoading
val message: LiveData<String> = mutableMessage
```

Becomes:

```kotlin
val city: LiveData<String>
    field = MutableLiveData().apply { value = "" }
val charts: LiveData<List<Chart>>
    field = MutableLiveData().apply { value = emptyList() }
val loading: LiveData<Boolean>
    field = MutableLiveData().apply { value = false }
val message: LiveData<String>
    field = MutableLiveData()
```
        
> `private` is a default access for a `field` declaration.

In this use-case, an read access to the field from inside the corresponding classes/modules 
(where the field is visible) automatically gives access to mutable type, which we call [Smart Type Narrowing](#smart-type-narrowing), 
but is seen as a property with read-only type to the outside code.

### Decouple storage type from external representation

Sometimes a property must be internally represented by a different type for storage-efficiency or architectural reason,
while having a different outside type. For example, API requirements might dictate that the property type
is `String`, but if we know that it always represents a decimal integer, then it can be efficiently stored as such 
with custom getter and custom setter.

```kotlin
var number: String
    field: Int = 0
    get() = field.toString()
    set(value) { field = value.toInt() }
```

### Expose read-only view

In some application it is desirable to expose not just a read-only subtype, but a specially constructed read-only
view that protects the data structure from casting into mutable type. For example a `MutableStateFlow` has
[`asStateFlow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/as-state-flow.html)
extension for that purpose.

With the proposed syntax it is possible to declare a custom getter:

```kotlin
val state: StateFlow<State> 
    get() = field.asStateFlow()
    field = MutableStateFlow(State.INITIAL)
```

For this use-case, the TBD syntax of [Direct Backing Field Access](#direct-backing-field-access) will need to be added.

### Access field from outside of getter and setter

Kotlin allows property field to be accessed from the property's getter and setter using a `field` variable.
This proposal is designed with an idea to provide an explicit syntax to access a property's field from anywhere
inside the corresponding class when the field is declared explicitly:

```kotlin
class Component {
    var status: Status
        field // explicit field declaration
        set(value) {
            field = value
            notifyStatusChanged()
        }
}
```

This way, all the code inside the class can change the field of the property directly, without invoking the setter. 
However, the actual access syntax of such [Direct Backing Field Access](#direct-backing-field-access) is TBD. 

## Design

The proposed design consists of two new ideas: explicit backing fields and smart type narrowing.

### Explicit Backing Fields

The grammar for `propertyDeclaration` is extended to support an optional _explicit backing field declaration_ in addition
to the optional `getter` and `setter` (in any order between them). 

```
propertyDeclaration ::=
    modifiers? ('val' | 'var') typeParameters?
    (receiverType '.')?
    (multiVariableDeclaration | variableDeclaration)
    typeConstraints? (('=' expression) | propertyDelegate)? ';'?
     ( (getter? (semi? setter)? (semi? field)?) 
     | (setter? (semi? getter)? (semi? field)?)
     | (getter? (semi? field)? (semi? setter)?) 
     | (setter? (semi? field)? (semi? getter)?)
     | (field? (semi? getter)? (semi? setter)?) 
     | (field? (semi? setter)? (semi? getter)?)

getter ::=
    modifiers? 'get' ('(' ')' (':' type)? functionBody)?
  
setter ::=
    modifiers? 'set' ('(' functionValueParameterWithOptionalType ','? ')' (':' type)? functionBody)?
    
field ::=
    modifiers? 'field` (':' type)? ('=' expression)?       
```

Explicit backing field declaration has an optional visibility, an optional type, and an optional 
initialization expression. 

#### Restrictions

There are the following additional semantic restrictions on the property declaration grammar:

* A property with an explicit field declaration cannot have its own initializer.
  A property with an explicit field must be initialized with the initialization expression for its
  field to clarify the fact, that property initialization goes directly to the field and does not
  call property's setter. The property without field initialization expression is considered
  uninitialized and is allowed only when it is `lateinit` (see [Lateinit](#lateinit) section for details).
* A property with an explicit backing field must always explicitly specify the type of the property itself.
* Explicit backing field declaration is not allowed for interface properties, for `abstract` properties, and for delegated properties.

A backing field type is not required to be explicitly specified: 

* If both backing field type and initialization expression are not specified, then the field type is the same as the property type.
* If backing field type is not specified, but there is an initialization expression, then  
  the field type is inferred from the type of its backing field initialization expression. 
* When both field type and initialization expression are specified, then the type of the former must be 
  assignable to the latter.

Backing field assignability is the same as it is now for `field` references in getters and setters:

* `var` properties have mutable backing fields.
* `val` properties have read-only backing fields. That is, assignment to `field` inside a `val` property getter results in an error.

#### Accessors
                                                          
When explicit backing field with type `F` for a property with type `P` is declared explicitly, 
then compiler can derive getter and, for `var` properties, setter implementation automatically:

* If `P :> F`, the compiler can provide a default getter.
* If `P <: F`, the compiler can provide a default setter.

If the compiler can not provide a getter, the user must declare it explicitly. 
The same applies to setters in case of `var` properties.

```kotlin
public val flow: SharedFlow<String>
    field: MutableSharedFlow? = null
    get() { // It is an error if getter is not explicitly specified here
        return field ?: run { ... }
    }
```

#### Visibility

Only the `private` and `internal` visibilities are allowed for explicit backing fields. 
The default field visibility is `private`.

```kotlin
val mutableWithinModule: List<Item>
    internal field = mutableListOf()
```

The special syntax to explicitly access the backing field from outside the code of property accessors is TBD, 
but the field can be implicitly access when it is visible via the [Smart Type Narrowing](#smart-type-narrowing).

#### Lateinit

If a property has an explicit backing field declaration, and it needs to be `lateinit`, the modifier must be placed at the `field` declaration.

```kotlin
var someStrangeExample: Int
    lateinit field: String
    get() = field.length
    set(value) {
        field = value.toString()
    }
```

### Smart Type Narrowing

The idea behind smart type narrowing is to implicitly access the underlying field, as opposed to the property,
when the field is in scope and when it is safe to do so. For example, expanding on 
[Expose read-only subtype](#expose-read-only-subtype) use-case, one can write:

```kotlin
class Component {
  val items: List<Item>
      field = mutableListOf()
  
  fun addItem(item: Item) {
      items += item // works
  }
}

// outside code
component.items += item // does not compile; cannot add to List<Item>
```

The code above works, because `items` there implicitly refers to the field with type `MutableList<Item>`.

Smart type narrowing works when trying to read the value of the property and all the following conditions are met:

* The backing field is visible from the point of access.
* The property is final (that is, it is not open `open`).
* The property does not have an explicit getter.

> The last rule automatically guarantees that the getter was automatically generated. Together with 
> the requirement that the field is not `open`, it means that the compiler knows that the field stores the same 
> instance as returned by the getter and that the type of the field is narrower than the type of 
> the property (see [Accessors](#accessors) section).

In this case, the type of property read expression is narrowed by the compiler from the type of the property 
to the type of its field.

## Alternatives

### Initial Proposal

Initially, the following syntax was suggested:

```kotlin
private val items = mutableListOf<Item>()
    public get(): List<Item>
```

> In above example `items` is `MutableList<Item>` when accessed privately inside class and read-only `List<String>` when accessed from outside class.

In fact, the above syntax brings in an incorrect mental model: it says _'There is a private property `items` with some *part* that declares its public behavior'_.

Attempt to add support for the above syntax led to multiple redundant complications (see the [problems section](https://github.com/matejdro/KEEP/blob/private_public_property_types/proposals/private_public_property_types.md#questions-to-consider) and unclear override mechanics).

## Future Enhancements

### Direct Backing Field Access
                                                                     
We plan to add support for a syntax to explicitly access the property's backing field when the field was 
explicitly declared and is accessible via some TBD syntax.  

###  Protected Fields
                     
The set of visibilities for an explicitly declared field can be extended to include `protected`
(in addition to `private` and `internal`). This way, subclasses can explicitly or implicitly
(via the [smart type narrowing](#smart-type-narrowing)) reference the field.

### Mutable Fields for Read-only Properties

Letting a `val` property have a mutable backing field may be useful. 
Consider the following snippet from the 
[implementation of `lazy` in the Kotlin standard library](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/src/kotlin/util/LazyJVM.kt#L57):

```kotlin
// backing field pattern
private var _value: Any? = UNINITIALIZED_VALUE

override val value: T
    get() {
       // initializes _value backing field on the first access
    }
```

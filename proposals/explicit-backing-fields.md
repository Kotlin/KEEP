# Explicit Backing Fields

- **Type**: Design Proposal
- **Authors**: Nikolay Lunyak, Roman Elizarov, Roman Efremov
- **Contributors**: Mikhail Zarechenskiy, Marat Akhin, Alejandro Serrano Mena, Anastasia Nekrasova, Svetlana Isakova, Kirill Rakhman, Dmitry Petrov, Ben Leggiero, Matej Drobnič, Mikhail Glukhikh
- **Status**: Design discussion
- **Discussion**: [KEEP-278](https://github.com/Kotlin/KEEP/issues/278)
- **Initial YouTrack Issue**: [KT-14663](https://youtrack.jetbrains.com/issue/KT-14663)
- **Previous Proposal**: [explicit-backing-fields.md, commit cc38eca](https://github.com/Kotlin/KEEP/blob/cc38eca4ec04a6864319fd3ed7a4cb7da0d66e26/proposals/explicit-backing-fields.md)

## Summary

Sometimes, Kotlin programmers need to declare two properties which are conceptually the same,
but one is part of a public API and another is an implementation detail. 
This pattern is known as [backing properties](https://kotlinlang.org/docs/properties.html#backing-properties):

```kotlin
class SomeViewModel : ViewModel() {
  private val _city = MutableLiveData<String>()
  val city: LiveData<String> get() = _city
}
```

With the proposed syntax in mind, the above code snippet could be rewritten as follows:

```kotlin
class SomeViewModel : ViewModel() {
    val city: LiveData<String>
        field = MutableLiveData<String>()
}
```

## Table of contents

<!--- TOC -->

* [Use cases](#use-cases)
  * [Use cases targeted by the explicit backing field feature](#use-cases-targeted-by-the-explicit-backing-field-feature)
    * [Expose read-only supertype](#expose-read-only-supertype)
    * [Expose different object](#expose-different-object)
  * [Use cases not supported by the explicit backing field feature](#use-cases-not-supported-by-the-explicit-backing-field-feature)
    * [`var` backing property](#var-backing-property)
    * [`lateinit` backing property](#lateinit-backing-property)
    * [Delegation](#delegation)
    * [Non-private visibility of property](#non-private-visibility-of-property)
* [Design](#design)
  * [Explicit Backing Fields](#explicit-backing-fields)
  * [Visibility](#visibility)
  * [Resolution](#resolution)
  * [Accessors](#accessors)
  * [Explicit backing field in combination with property initializer](#explicit-backing-field-in-combination-with-property-initializer)
  * [Accessing field inside accessors and initializers](#accessing-field-inside-accessors-and-initializers)
  * [Type inference](#type-inference)
  * [Other restrictions](#other-restrictions)
* [Technical details](#technical-details)
  * [Grammar changes](#grammar-changes)
  * [Java interoperability](#java-interoperability)
  * [Reflection](#reflection)
* [Risks](#risks)
  * [Unintentionally exposing a field as a return value](#unintentionally-exposing-a-field-as-a-return-value)
* [Future enhancements](#future-enhancements)
  * [Underscore operator in type parameters](#underscore-operator-in-type-parameters)

<!--- END -->

## Use cases

This proposal caters to a variety of use-cases that are currently met via a
[backing property pattern](https://kotlinlang.org/docs/properties.html#backing-properties).

To better understand pattern usage and to help in decision-making,
for each pattern occurrence statistics were collected based on open repositories on GitHub.

### Use cases targeted by the explicit backing field feature

#### Expose read-only supertype

We often do not want our data structures to be modified from outside. It is customary in Kotlin to have
a read-only (e.g. `List`) and a mutable (e.g. `MutableList`) interface to the same data structure. Here is an example:

```kotlin
private val _items = mutableListOf<Item>()
val item: List<Item> get() = _items
```

This use-case is also widely applicable to architecture of reactive applications:

* [Android `LiveData`](https://developer.android.com/topic/libraries/architecture/livedata) has a `MutableLiveData` counterpart ([code example](https://github.com/elpassion/crweather/blob/9c3e3cb803b7e4fffbb010ff085ac56645c9774d/app/src/main/java/com/elpassion/crweather/MainModel.kt#L14-L22))
* [Kotlin coroutines `SharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) has a `MutableSharedFlow`, `StateFlow` has a `MutableStateFlow`, etc.
* [Rx `Observable`](https://github.com/ReactiveX/RxJava/wiki/Subject) has a mutable `Subject` counterpart ([example](https://github.com/gojek/courier-android/blob/40a161cf881bd918cf6580bd5b62b51240295394/chuck-mqtt/src/main/java/com/gojek/chuckmqtt/internal/presentation/base/fragment/FoodMviBaseFragment.kt#L18-L19)).

_Statistics_: this pattern found in more than 100k files in open repositories on GitHub.

#### Expose different object

In this pattern, the primary property does not directly return the object stored in the backup property.
Instead, the value of the backing property is transformed in some way.

There are two options here. The value of primary property can be evaluated once and stored, or computed on every call.

```kotlin
private val _exampleWithStored = ...
val exampleWithStored = _exampleWithStored.someTransformation()

private val _exampleWithComputed = ...
val exampleWithComputed get() = _exampleWithComputed.someTransformation()
```

_Statistics_: found in 70k files for stored property and 12k for computed.

Below are some use cases for this pattern.

##### Returning read-only view

In some application it is desirable to expose not just a read-only subtype, but a specially constructed read-only
view that protects the data structure from casting into mutable type. For example, a `MutableStateFlow` has
[`asStateFlow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/as-state-flow.html)
extension for that purpose.

```kotlin
private val _city = MutableStateFlow("")
val city: StateFlow<String> = _city.asStateFlow()
```

It is usually more desirable for the primary property to be stored, as in the code snippet above or [this example](https://github.com/wikimedia/apps-android-wikipedia/blob/604007f38e834667f037c475b05f362b92a5575c/app/src/main/java/org/wikipedia/talk/template/TalkTemplatesViewModel.kt#L26-L30).
However, computed property pattern is also quite popular ([example](https://github.com/jellyfin/jellyfin-androidtv/blob/b46f1acc99fc848abb9ef896c9bb2941e9c6e3ff/playback/core/src/main/kotlin/PlayerState.kt#L75-L88)).

##### Convenient type cast without spelling the full type

Also, conversion method like `asStateFlow` is used when we want to cast backing property to supertype,
but we don't want to explicitly spell the full type (which is also was [one of the reasons of introducing `asStateFlow`](https://github.com/Kotlin/kotlinx.coroutines/issues/1973#issuecomment-621660861)):

```kotlin
private val _item = MutableStateFlow(ExtremelyLongTypeName.DefaultValue)
val item = _city.asStateFlow()
// shorter than
val item: StateFlow<ExtremelyLongTypeName> = _city
// or
val item = _city as StateFlow<ExtremelyLongTypeName>
```

Apart from rewriting it using explicit backing fields, this use case could be reduced to the simpler form of [previous use-case](#expose-read-only-supertype),
if [underscore operator in type parameters](#underscore-operator-in-type-parameters) was supported.

##### Hide complex value storage logic

Sometimes we want to provide a public API for getting the immediate value of a variable,
and hide complex storage logic inside the implementation.
For example, we may store `AtomicInt` and provide api for obtaining instant `Int` value.

```kotlin
private val _itemCount = atomic(0)
val itemCount: Int get() = _itemCount.value
```

It is also possible with other containers:

- Other atomic classes ([example](https://github.com/Sorapointa/Sorapointa/blob/31578cb9e460bb4516b51fcef4f0c4af5600caa9/sorapointa-core/src/main/kotlin/org/sorapointa/game/PlayerAvatarComp.kt#L42-L82))
- `StateFlow`, `LiveData`, etc ([example](https://github.com/c5inco/Compose-Modifiers-Playground/blob/2c7e8c55eeaab15e38696397a84b57475a84a6a3/ideaPlugin/src/main/kotlin/intellij/SwingColors.kt#L44-L52))
- `ThreadLocal` ([example](https://github.com/corda/corda/blob/a95b854b1eda8a1e9284c0db8ff43b78ea0eb290/core/src/main/kotlin/net/corda/core/serialization/SerializationAPI.kt#L56))
- `WeakReference` ([example](https://github.com/expo/expo/blob/a502f90d6d9345648157aceb16a78f044669842e/packages/expo-dev-menu/android/src/main/java/expo/modules/devmenu/devtools/DevMenuDevToolsDelegate.kt#L21-L32))

##### Access backing property inside

It is worth emphasizing that in most cases the primary property is only for public use and is not
referenced within the class.
There is no point in doing so, since the backing property usually provides
all the same functionality, as a primary property and even more.

### Use cases not supported by the explicit backing field feature

Use cases listed below are not supported to be rewritten with a new feature.
However, they are left in the list to provide a more complete picture of pattern use in the wild,
as well as to illustrate statistic-driven rationale for decision making.

#### `var` backing property

It's possible that we want mutable backing property:

```kotlin
private var _prop: T? = null
val prop: T get() = _prop!!
```

While in some cases such code can be rewritten with one `lateinit var` + `private set`,
in the following situations we have to use the pattern above:

1. `T` is primitive type (because `lateinit` on primitive types is not allowed).
2. `prop` must be `open` (because `private set` is not allowed in `open` properties).
3. We want a custom getter or setter.
For example, we want to return some default value in getter while backing property is initialized.
4. Eventually value must be set back to `null` again. For example, [Fragment view bindings in Android](https://developer.android.com/topic/libraries/view-binding#fragments),
   which must be initialized in `onCreate()` and set to `null` in `onDestroy()`.
5. We want more permissive visibility of setter (e.g. protected property, internal setter).

_Statistics_: found in 236k files (mostly case 4).

#### `lateinit` backing property

If for some of the mentioned above reasons it's needed to use `var` backing property,
but it's expected that property is initialized before first use and is always non-null,
`lateinit var` backing property could be used
([example](https://github.com/ProtonMail/proton-mail-android/blob/215edbf4f9a80efc2f683005a4df36f511d272a9/app/src/main/java/ch/protonmail/android/ui/adapter/ClickableAdapter.kt#L79-L85)).

_Statistics_: found in 5179 files.

#### Non-private visibility of property

It's possible that backing property has visibility other than `private`. Here are some use-cases:

- protected - providing common functionality base class ([example](https://github.com/Automattic/pocket-casts-android/blob/c59b40d7acd0ba58e6d8266e880bccea3f666705/modules/services/views/src/main/java/au/com/shiftyjelly/pocketcasts/views/multiselect/MultiSelectHelper.kt#L40-L41))
- internal - to be able to mutate in neighbour class ([example](https://github.com/meganz/android/blob/21a8dd8da80dfdf1dfed58ad1734a2c76fadac6f/app/src/main/java/mega/privacy/android/app/presentation/photos/timeline/viewmodel/TimelineViewModel.kt#L111-L112))
- internal - visible for testing ([example](https://github.com/stripe/stripe-android/blob/2f93daa301f57e2690b07d258a872e4541c497e9/identity/src/main/java/com/stripe/android/identity/viewmodel/IdentityViewModel.kt#L179-L180))

_Statistics_: found in 3.7k files

Primary property also might be `protected` or `internal`
([example](https://github.com/meganz/android/blob/21a8dd8da80dfdf1dfed58ad1734a2c76fadac6f/app/src/main/java/mega/privacy/android/app/presentation/logout/LogoutViewModel.kt#L27-L28)).

#### Delegation

It's possible that either backing property ([example](https://github.com/b-lam/Resplash/blob/4b13d31134d1c31bd331e92ffe8d410984529212/app/src/main/java/com/b_lam/resplash/ui/upgrade/UpgradeViewModel.kt#L32-L40))
or primary property ([example](https://github.com/google/accompanist/blob/0d0198d0cab599295f7601cb386d5989d72cc8cd/pager/src/main/java/com/google/accompanist/pager/PagerState.kt#L145))
is delegated.

_Statistics_: backing property delegation found in 2693 files, primary property delegation in 1010 files.

## Design

### Explicit Backing Fields

By analogy with the already existing term ["implicit backing fields"](https://kotlinlang.org/docs/properties.html#backing-properties), it is proposed to introduce a syntax
for declaring "explicit backing fields" of properties.
Syntax consists of a keyword `field`, optional type definition and initialization expression
(more detailed [Grammar changes](#grammar-changes) are in the separate section).

```kotlin
private val _city = MutableLiveData<String>()
val city: LiveData<String> get() = _city
```

Could be rewritten as follows:

```kotlin
val city: LiveData<String>
    field = MutableLiveData()
```

### Visibility

`private` is the default and the only allowed visibility for explicit backing fields
(supporting other visibilities might be considered, see [Future enhancements](#future-enhancements)).

Visibility of property must be more permissive than explicit backing field visibility.

### Resolution

Calls of properties with explicit backing field are resolved to
- the backing field, if property is accessed from the same scope it is declared in
  (actually, follows `private` visibility rules as per [specification](https://kotlinlang.org/spec/declarations.html#declaration-visibility)),
- getter, otherwise.

```kotlin
class SomeViewModel {
    val city: LiveData<String>
        field = MutableLiveData("")
    
    fun updateCity(newCity: String) {
        city.value = newCity // visible as MutableLiveData, calling field
    }
}

fun outside(vm: SomeViewModel) {
    vm.city // visible as LiveData, calling getter
} 
```

There is no possibility to call a getter instead of field 
when the property is accessed from the same scope it is declared in 
(might be reconsidered in [Future enhancements](#future-enhancements)).

### Accessors

Property, which has explicit backing field, can also specify getter and/or setter.

```kotlin
val counter: Int
    field = atomic(0)
    get() = field.value
```

When explicit backing field with type `F` for a property with type `P` is declared,
then compiler can derive getter and, for `var` properties, setter implementation automatically:

* If `F` is a subtype of `P`, the compiler can provide a default getter.
* If `P` is a subtype of `F`, the compiler can provide a default setter.

If the compiler can not provide a getter, the user must declare it explicitly.
The same applies to setters in case of `var` properties.

```kotlin
val city: String
    field = MutableLiveData<String>()
    get() = field.value ?: "-" // It is an error if getter is not explicitly specified here
```

### Explicit backing field in combination with property initializer

It's allowed to have both property initializer and explicit backing field specified.

```kotlin
val city: StateFlow<String> = field.asStateFlow()
    field = MutableStateFlow("")
```

In this case, the property initializer expression (`field.asStateFlow()` in example above) is evaluated once
immediately after the backing field is initialized and then returned every time the getter of property is called.

If property with explicit backing field has initializer, it's prohibited to declare accessors.

> This use case stands out a little from the rest, 
since it implies the simultaneous existence of two different stored values for one property,
which sounds dissonant with the current mental image of properties in Kotlin.
However, this is a case where the desire for consistency gives way to the importance of supporting a popular pattern
(see [Expose different object](#expose-different-object)).

### Accessing field inside accessors and initializers

It's possible to access explicit backing field by calling `field` from inside accessors (like it works now for ordinary
properties) and property initialization expression.

Backing field assignability is the same as it is now for `field` references in getters and setters:

* `var` properties have mutable backing fields.
* `val` properties have read-only backing fields. That is, assignment to `field` inside a `val` property getter results in an error.

The following additional restrictions apply:

* It's prohibited to reference `field` inside explicit backing field initializer expression.
* Explicit backing field must be referenced through `field` in property initializer, setter and getter (to prevent feature abuse).

### Type inference

Explicit type declaration on explicit backing field is optional. If it is not specified, it's inferred from the explicit backing field initialization expression.

Explicit type declaration on property, which has explicit backing field, is optional. If it is not specified, it's inferred from
the property initialization or from getter. If none of them are specified, the property type is equal to the explicit backing field type.

It's a warning if the property type is equal to the explicit backing field type.

### Other restrictions

There are the following additional semantic restrictions on the property declaration.
Property with explicit backing field:

* must be final (otherwise calling a field instead of a getter would be undesirable behavior)
* can't be `const`
* can't be `expect` or `external`
* must be initialized right away. It can't be initialized from `init` block
  (otherwise we can't differentiate whether initializer expected here or not)
* can't be `var` if it has property initializer (to prevent ambiguity of what should be mutable here)
* can't be extension property

Neither property with explicit backing field:
* nor its accessors can be `inline` 
* nor explicit backing field itself can be `lateinit` (added to [Future enhancements](#future-enhancements))
* nor explicit backing field itself can be delegated (added to [Future enhancements](#future-enhancements))

It is not prohibited for a top-level property to have an explicit backing field.

## Technical details

### Grammar changes

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
     | (field? (semi? setter)? (semi? getter)?))

field ::=
    modifiers? 'field' (':' type)? '=' expression     
```

### Java interoperability

For JVM target, property with explicit backing field is compiled into field(-s) and accessors.
Property can be accessed from Java through them in accordance with the following visibility rules:

* accessors are assigned visibility of property
* field is assigned `private` visibility of explicit backing field 

```kotlin
protected val name: LiveData<String>
    field = MutableLiveData<String>()
```

So, the property above is visible in Java as:

```java
private final MutableLiveData<String> name = MutableLiveData();

protected final LiveData<String> getName() {
    return this.name;
}
```

`@JvmField` is prohibited on properties with explicit backing field, because in this case a field type becomes exposed,
which makes use of the feature completely pointless.

#### Property with initializer compilation

Property with explicit backing field and initializer is represented by additional
auxiliary synthetic field which is needed for storing the value from property initializer.
Auxiliary field is given the name `propertyName$init` and is `private` regardless of property visibility.
Accessing this field directly from code is not possible (and is not planned), as it must remain an implementation detail.

```kotlin
val city: StateFlow<String> = field.asStateFlow()
    field = MutableStateFlow("")
```

The example above is compiled into:

```java
private final MutableStateFlow<String> city = MutableStateFlow("");
private /* synthetic */ final StateFlow<String> city$init = this.city.asStateFlow();

public final StateFlow<String> getCity() {
    return this.city$init;
}
```

### Reflection

Callable reference to property with explicit backing field has type `KProperty<V>` (or its subtypes) where `V`
is type of property (not backing field) regardless of wherever it is accessed from.

On JVM backing field can be obtained using [`javaField` extension](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect.jvm/java-field.html).

There is no API in Reflection to check whether property has an explicit backing field or obtain any information about it.
Adding such an API might be considered in [Future enhancements](#future-enhancements).

## Risks

### Unintentionally exposing a field as a return value

The ability to call both a getter and a field with the same name depending on the context of call
introduces a possible error with the unwanted exposing field value (where getter value was meant), 
when property is returned in function, especially when function return type is omitted:

```kotlin
class MyViewModel {
    val city = field.asStateFlow()
        field = MutableStateFlow("")
  
    fun updateAndReturn(newValue: String): StateFlow<String> {
        city.value = newValue
        return city // exposed MutableStateFlow instead of read-only wrapper
    }

    fun updateAndReturn2(newValue: String) /* MutableStateFlow inferred */ =
        city.also { it.value = newValue }
}
```

Although it would be nice to have warnings from the tooling in such cases, 
it is unclear in which cases such behavior is desirable and in which it is not.

## Future enhancements

We strive to keep the design simple and uncluttered, so at this point we put aside functionality, which is rarely used or would complicate language too much. 
Of course, we may revise some decisions in the future based on community feedback.
Here is a list of the most obvious future enhancements for the feature:

1. Make it possible to access getter instead of explicit backing field when the property is accessed from the same scope the property is declared in. This is not supported because usually there is no need for calling getter (see [Access backing property inside](#access-backing-property-inside)).
2. Support other visibilities of explicit backing field (`protected` or `internal`).
3. Support delegation of explicit backing field or property.
4. Support `lateinit` explicit backing field.
5. Support combining mutable explicit backing field and non-mutable property. 
Despite its popularity (see [`var` backing property](#var-backing-property)), this use case is not supported in this proposal, because it is impossible for one property to behave as mutable + nullable and
at the same time non-mutable and non-nullable.
6. Add API in Reflection to retrieve information about explicit backing field.

### Underscore operator in type parameters

It's proposed to make [underscore operator](https://kotlinlang.org/docs/generics.html#underscore-operator-for-type-arguments)
more powerful and support it in type parameters.

This is a separate language feature, yet worth mentioning here,
as it can help rewrite properties with explicit backing field in a better way in some cases
(see [Convenient type cast without spelling the full type](#convenient-type-cast-without-spelling-the-full-type)).

```kotlin
val item = field.asStateFlow() // if we don't need read-only wrapper...
    field = MutableStateFlow(ExtremelyLongTypeName.Default)

// ...we could rewrite it like that without losing conciseness
val item: StateFlow<_> // inferred StateFlow<ExtremelyLongTypeName> 
    field = MutableStateFlow(ExtremelyLongTypeName.Default)
```

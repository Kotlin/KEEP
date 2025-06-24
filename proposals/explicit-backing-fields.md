# Explicit Backing Fields

- **Type**: Design Proposal
- **Author**: Roman Efremov
- **Contributors**: Alejandro Serrano Mena, Anastasiia Pikalova, Denis Zharkov, Dmitriy Novozhilov, Marat Akhin, Mikhail Glukhikh, Mikhail Zarečenskij, Nikita Bobko, Nikolay Lunyak, Pavel Kunyavskiy, Roman Elizarov
- **Status**: Design discussion
- **Discussion**: [#430](https://github.com/Kotlin/KEEP/discussions/430)
- **YouTrack Issue**: [KT-14663](https://youtrack.jetbrains.com/issue/KT-14663)

## Summary

Sometimes, Kotlin programmers need to declare two properties which are conceptually the same,
but one is part of a public API and another is an implementation detail. 
This pattern is known as [backing properties](https://kotlinlang.org/docs/properties.html#backing-properties):

```kotlin
class SomeViewModel : ViewModel() {
    private val _city = MutableLiveData<String>()
    val city: LiveData<String> get() = _city

    fun updateCity(newValue: String) {
        _city.value = newValue
    }
}
```

With the proposed syntax in mind, the above code snippet could be rewritten as follows:

```kotlin
class SomeViewModel : ViewModel() {
    val city: LiveData<String>
        field = MutableLiveData<String>()
  
    fun updateCity(newValue: String) {
        city.value = newValue
    }
}
```

## Table of contents

<!--- TOC -->

* [Motivation](#motivation)
* [Design](#design)
  * [Declaration-site](#declaration-site)
  * [Call-site](#call-site)
  * [Smart cast applicability](#smart-cast-applicability)
  * [Visibility](#visibility)
  * [Expect-actual matching](#expect-actual-matching)
  * [Call from inline functions](#call-from-inline-functions)
  * [Other restrictions](#other-restrictions)
* [Technical design](#technical-design)
  * [Syntax](#syntax)
* [Unsupported use cases](#unsupported-use-cases)
  * [Expose different object](#expose-different-object)
    * [Returning read-only view](#returning-read-only-view)
    * [Hide complex value storage logic](#hide-complex-value-storage-logic)
  * [Lateinit property with early destruction](#lateinit-property-with-early-destruction)
  * [Non-private visibility of property](#non-private-visibility-of-property)
  * [Why these cases aren't supported](#why-these-cases-arent-supported)
* [Naming discussion](#naming-discussion)

<!--- END -->

## Motivation

We often do not want our data structures to be modified from outside. It is customary in Kotlin to have
a read-only (e.g. `List`) and a mutable (e.g. `MutableList`) interface to the same data structure. Here is an example:

```kotlin
private val _items = mutableListOf<Item>()
val items: List<Item> get() = _items
```

This use-case is also widely applicable to architecture of reactive applications:

* [Android `LiveData`](https://developer.android.com/topic/libraries/architecture/livedata) has a `MutableLiveData` counterpart ([code example](https://github.com/elpassion/crweather/blob/9c3e3cb803b7e4fffbb010ff085ac56645c9774d/app/src/main/java/com/elpassion/crweather/MainModel.kt#L14-L22))
* [Kotlin coroutines `SharedFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/) has a `MutableSharedFlow`, `StateFlow` has a `MutableStateFlow`, etc.
* [Rx `Observable`](https://github.com/ReactiveX/RxJava/wiki/Subject) has a mutable `Subject` counterpart ([example](https://github.com/gojek/courier-android/blob/40a161cf881bd918cf6580bd5b62b51240295394/chuck-mqtt/src/main/java/com/gojek/chuckmqtt/internal/presentation/base/fragment/FoodMviBaseFragment.kt#L18-L19)).

> For use cases that are not supported, see the respective [section](#unsupported-use-cases).

## Design

### Declaration-site

> [!NOTE]
> Syntax on the declaration site and feature naming are a subject of discussion (see the [corresponding section](#naming-discussion)).

By analogy with the already existing term ["implicit backing fields"](https://kotlinlang.org/docs/properties.html#backing-properties), it is proposed to introduce a syntax
for declaring "explicit backing fields" of properties.
Syntax consists of a keyword `field`, optional type definition and initialization expression
(more details on [Syntax changes](#syntax) are in the separate section).

```kotlin
private val _city = MutableLiveData<String>()
val city: LiveData<String> get() = _city
```

Could be rewritten as follows:

```kotlin
val city: LiveData<String>
    field = MutableLiveData()
```

The type of the field can also be specified explicitly by writing `field: Type`. 
Otherwise, the compiler infers the type of explicit backing field.

The initialization expression is optional. In case it's omitted, the field type must be specified:

```kotlin
class SomeViewModel : ViewModel() {
    val city: LiveData<String> field: MutableLiveData<String>

    init {
        city = MutableLiveData()
    }
}
```

When a property with an EBF (explicit backing field) is compiled, the backing field gets a type of EBF.

### Call-site

Compiler provides automatic smart cast to the explicit backing field's type within property's declaring scope (i.e. class or top-level).
In other words, automatic smart cast accessibility is the same as accessibility of the private property if you wrote it in the place of property with an explicit backing field.
Note the difference when accessing `city` in the following example:

```kotlin
class SomeViewModel {
    val city: LiveData<String>
        field = MutableLiveData("")

    fun updateCity(newCity: String) {
        city.value = newCity // smart cast to MutableLiveData<String>
    }
}

fun outside(vm: SomeViewModel) {
    vm.city // type is LiveData, no automatic smart cast available
} 
```

The call happens through getter.
In example above `city.value = newCity` would be translated to `(getCity() as MutableLiveData<String>).setValue(newCity)`.
Implementation may optimize this call by using the backing field directly.

### Smart cast applicability

Currently, smart casts need to meet certain rules to be applied.
Compiler enforces these rules for the properties with an explicit backing field (EBF):

* Property with EBF must not have a custom getter. 
* Property with EBF can't be `var`. 
* Property with EBF must have `final` modality.
* Delegated properties can't have EBF.
* The type of the explicit backing field must be a subtype of the property's type.
* ... and other restrictions to make sure smart cast is applicable.

### Visibility

* `private` is the default and the only allowed visibility for explicit backing fields. 
* Visibility of property must be more permissive than explicit backing field visibility.

### Expect-actual matching

* `expect` property can't have an explicit backing field (since it's an implementation detail).
* During expect-actual matching, only property types are considered and explicit backing fields play no role.

### Call from inline functions

Automatic smart cast on properties with EBF is disabled inside `public`, `internal` and `protected` inline functions.

### Other restrictions

* It's prohibited to put `@JvmField` annotation on property with explicit backing field.
* Local property can't have an explicit backing field.
* It's a warning if a property type is equal to EBF type.

## Technical design

### Syntax

The grammar for `propertyDeclaration` is extended to support an optional explicit backing field type declaration.

```diff
propertyDeclaration ::=
    modifiers? ('val' | 'var') typeParameters?
    (receiverType '.')?
    (multiVariableDeclaration | variableDeclaration)
    typeConstraints?
-   (('=' expression) | propertyDelegate)? ';'?
+   ((('field' (':' type)? )? ('=' expression)?) | propertyDelegate)? ';'?
    ((getter? (semi? setter)?) | (setter? (semi? getter)?))     
```

## Unsupported use cases

There are a number of use cases that are also related to the backing property pattern, but not supported by this proposal.

### Expose different object

In this pattern, the primary property applies some transformation to the object stored in the backing property, and as a result may return different object.

The value of primary property can be computed on every call or evaluated once and stored. However, the latter is applicable only if the resulting wrapper object is stateless and always delegates to the original value from backing property.

```kotlin
private val _exampleWithComputed = ...
val exampleWithComputed get() = _exampleWithComputed.someTransformation()

private val _exampleWithStored = ...
val exampleWithStored = _exampleWithStored.someTransformation()
```

Below are some use cases for this pattern.

#### Returning read-only view

In some cases it is desirable to expose not just a read-only subtype, but a specially constructed read-only view that protects the data structure from casting into a mutable type.
For example, a `MutableStateFlow` has [`asStateFlow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/as-state-flow.html) extension for that purpose.

```kotlin
private val _city = MutableStateFlow("")
val city: StateFlow<String> get() = _city.asStateFlow()
```

#### Hide complex value storage logic

Sometimes we want to provide a public API for getting the immediate value of a property and hide complex storage logic inside the implementation.
For example, store `AtomicInt` and provide API for obtaining instant `Int` value.

```kotlin
private val _itemCount = atomic(0)
val itemCount: Int get() = _itemCount.value
```

> [!NOTE]
> In fact, the Kotlin language already has a feature that serves precisely to hide the implementation of a property: these are property delegates.
> However, the key distinction here is that we want to access the hidden type, and thus only backing property pattern is an option here.

It also applies to other containers:

- Other atomic classes ([example](https://github.com/Sorapointa/Sorapointa/blob/31578cb9e460bb4516b51fcef4f0c4af5600caa9/sorapointa-core/src/main/kotlin/org/sorapointa/game/PlayerAvatarComp.kt#L42-L82))
- `StateFlow`, `LiveData`, etc ([example](https://github.com/c5inco/Compose-Modifiers-Playground/blob/2c7e8c55eeaab15e38696397a84b57475a84a6a3/ideaPlugin/src/main/kotlin/intellij/SwingColors.kt#L44-L52))
- `ThreadLocal` ([example](https://github.com/corda/corda/blob/a95b854b1eda8a1e9284c0db8ff43b78ea0eb290/core/src/main/kotlin/net/corda/core/serialization/SerializationAPI.kt#L56))
- `WeakReference` ([example](https://github.com/expo/expo/blob/a502f90d6d9345648157aceb16a78f044669842e/packages/expo-dev-menu/android/src/main/java/expo/modules/devmenu/devtools/DevMenuDevToolsDelegate.kt#L21-L32))

### Lateinit property with early destruction

Same as `lateinit` allows you to set a property later, it is sometimes necessary to release a reference to the created object earlier and prevent it from being used after property's lifecycle has ended.
For example, [Fragment view bindings](https://developer.android.com/topic/libraries/view-binding#fragments) in Android must be initialized in `onCreate()` and reset back to `null` in `onDestroy()`.
Currently, it is impossible to implement such a pattern with a single property without avoiding null checks.

```kotlin
private var _binding: ResultProfileBinding? = null
val binding get() = _binding!!

override fun onCreateView(/* ... */) {
    _binding = ResultProfileBinding.inflate(inflater, container, false)
}

override fun onDestroyView() {
    _binding = null
}
```

### Non-private visibility of property

It's possible that backing property has visibility other than `private`. Here are some use-cases:

- protected - providing common functionality base class ([example](https://github.com/Automattic/pocket-casts-android/blob/c59b40d7acd0ba58e6d8266e880bccea3f666705/modules/services/views/src/main/java/au/com/shiftyjelly/pocketcasts/views/multiselect/MultiSelectHelper.kt#L40-L41))
- internal - to be able to mutate in neighbour class ([example](https://github.com/meganz/android/blob/21a8dd8da80dfdf1dfed58ad1734a2c76fadac6f/app/src/main/java/mega/privacy/android/app/presentation/photos/timeline/viewmodel/TimelineViewModel.kt#L111-L112))
- internal - visible for testing ([example](https://github.com/stripe/stripe-android/blob/2f93daa301f57e2690b07d258a872e4541c497e9/identity/src/main/java/com/stripe/android/identity/viewmodel/IdentityViewModel.kt#L179-L180))

### Why these cases aren't supported

The existing workaround with backing property pattern is intuitive and relatively concise, and therefore it sets a high bar of convenience and simplicity for the new feature's syntax.
That's why, when you're trying to support as many use cases as possible but not overcome "conciseness bar",
you often end up with something too implicit and "magical."

That's why among all the considered alternatives, the current design was chosen – this coverage / simplicity ratio seems to be the best.

## Naming discussion

During discussions, it was noted several times that current syntax and feature naming might not be the best ones.
The feature is not really about making backing fields explicit,
but rather about giving properties more power by revealing more specific type in private scope.
At the same time, it's hard to compete with the fact that the concept of explicit backing fields is very easy to understand as it is based on existing constructs in the language.
So for now, naming remains a subject of discussion.

Feel free to share your ideas for declaration-site syntax and a name for the feature, along with the reasoning, in the comments!

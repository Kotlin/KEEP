# Explicit Backing Fields

- **Type**: Design Proposal
- **Author**: ?
- **Contributors**: Svetlana Isakova, Kirill Rakhman, Dmitry Petrov, Roman Elizarov, Ben Leggiero, Matej Drobnič, Mikhail Glukhikh, Nikolay Lunyak
- **Status**: Implemented in FIR
- **Prototype**: Implemented
- **Initial YouTrack Issue**: [KT-14663](https://youtrack.jetbrains.com/issue/KT-14663)
- **Initial Proposal**: [private_public_property_types#122](https://github.com/Kotlin/KEEP/pull/122)

## Summary

**Note**: initial proposal contents have been partially copied down here for convenience. Despite the different approach, the already shown use cases are still relevant.

Sometimes, Kotlin programmers need to declare two properties which are conceptually the same, but one is part of a public API and another is an implementation detail. This is known as [backing properties](https://kotlinlang.org/docs/properties.html#backing-properties):

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

## Use Cases

### Read-Only from Outside

> We often do not want our data structures to be modified from outside. Unlike Java, this can be easily achieved in Kotlin by just exposing read-only `List`. But as already ilustrated in above example, exposing different type of a property is a bit messy.

```kotlin
internal val _items = mutableListOf<Item>()
val item : List<Item> by _items
```

And the new syntax allows us to write:

```kotlin
val items: List<Item>
    internal field = mutableListOf()
```

### Android Architecture Components

>  Proper way to do Architecture components is to use `MutableLiveData` (`LiveData` implementation that allows caller to change its value) privately inside View Model classes and then only expose read-only `LiveData` objects outside.

Sample code [from an Android app](https://github.com/elpassion/crweather/blob/9c3e3cb803b7e4fffbb010ff085ac56645c9774d/app/src/main/java/com/elpassion/crweather/MainModel.kt#L14):

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

### Common Java Pattern

>  Common pattern in java is having full type accessible as private property and then only exposing required interface in the public getter:
>
>  ```java
>  class MyClass {
>      // Use full type for private access
>      private final ArrayList<String> data = new ArrayList<>();
>  
>      // Only expose what is needed in public getter
>      public List<String> getData() {
>          return data;
>      }
>  }
>  ```
>
>  This pattern allows easy hiding of implementation details and allows for easy clean external interfaces to the classes.
>
>  - There is no clean idiomatic way to do this pattern in Kotlin
>  - Current best approach throws away all benefits of Kotlin properties and forces developer to write Java-like code with separate private field and public getter
>  - Current best approach forces developer to assign two different names to single property (or pad private property, for example adding `_` prefix and then using this prefix everywhere in code)
>  - This is another place where Java pattern could be introduced into Kotlin with less boilerplate

The proposed syntax allows to achieve the same functionality while keeping the same level of simplicity:

```kotlin
class MyClass {
    val data: List<String>
        field = ArrayList()
}
```

### RX Observable and Subjects

> Data can often be pushed into reactive world using subjects. However, exposing `Subject` would allow consumer of the class to push its own data into it. That is why it is good idea to expose all subjects as read-only `Observable`:

```kotlin
class MyClass {
    private val _dataStream = PublishSubject.create<String>()
    val dataStream: Observable<String>
        get() = _dataStream
}
```

Turns into:

```kotlin
class MyClass {
    val dataStream: Observable<String>
        field = PublishSubject.create()
}
```

## Design

The proposed design consists of two new ideas.

### Explicit Backing Fields

```kotlin
val it: P
    [visibility] field[: F] = initializer
```

The above `field` declaration is referred to as an _explicit backing field declaration_.

Explicit backing fields is a FIR-only feature.

#### Accessors

- if `P :> F`, the compiler can provide a default getter
- if `P <: F`, the compiler can provide a default setter

If the compiler can not provide a getter, the user must declare it explicitly. The same applies to setters in case of `var` properties.

```kotlin
public val flow: SharedFlow<String>
    field: MutableSharedFlow? = null
    get() {
        return field ?: run { ... }
    }
```

#### Visibility

Only the `private` and the `internal` visibilities are allowed for explicit backing fields now. The default visibility is `private`.

```kotlin
val mutableWithinModule: List<Item>
    internal field = mutableListOf()
```

Right now, there is no special syntax to explicitly access the backing field from outside the property accessors, but we will see how it can be accessed implicitly via the ["second idea"](#smart-type-narrowing).

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

#### Restrictions

If there is an explicit backing field, the property must not declare an initializer. If the explicit backing field is not `lateinit`, it must have an initializer. For `lateinit` properties, initializers are forbidden.

For now, we assume `val` properties have immutable backing fields, and `var` properties have mutable ones. That is, assignment to `field` inside a `val` property getter results in an error.

Explicit backing fields are not allowed inside interfaces or abstract properties, as well as they are forbidden for delegated properties.

### Smart Type Narrowing

#### Rules

If:

1. the compiler can guarantee the property getter returns the same instance as the one stored in the backing field
2. the type of that instance is compatible with the property type
3. the backing field visibility allows a hypothetical direct access
4. there is no ambiguity in such an access

it can then narrow down the returned instance type at the call site without loss of functionality. This is what is meant by the words _smart type narrowing_.

The formal checks corresponding to the above rules are:

1. the property does not have a custom getter, only the default one
2. `P :> F`
3. accessing:
   1. a private backing field of a class member property within the class
   2. a private backing field of a top-level property within the file
   3. an internal backing field within the module
4. property must be final, otherwise an overridden version would be able to provide a custom getter that may return a different instance

#### Example

```kotlin
class MyClass {
    val items: List<String>
        field = mutableListOf("a", "b")
    
    fun registerItem(item: String) {
        items.add(item) // Viewed as MutableList<String>
    }
}

fun test() {
    val it = MyClass()
    it.items // Viewed as a List<String>
}
```

## Alternatives

### Initial Proposal

Initially, the following syntax was suggested:

```kotlin
private val items = mutableListOf<Item>()
    public get(): List<Item>
```

> In above example `items` is `MutableList<Item>` when accessed privately inside class and read-only `List<String>` when accessed from outside class.

In fact, the above syntax brings in an incorrect mental model: it says _'There is a private property `it` with some *part* that declares its public behavior'_.

Attempt to add support for the above syntax led to multiple redundant complications (see the [problems section](https://github.com/matejdro/KEEP/blob/private_public_property_types/proposals/private_public_property_types.md#questions-to-consider) + unclear override mechanics).

## Future Enhancements

### Direct Backing Field Access

It might be handy to allow the direct access to the property backing field via syntax like `myProperty#field` (just a hypothetical syntax). 

###  Allow `protected field`

This way, smart type narrowing would become possible inside subclasses as well.

### Use of `field` Before Property Type Is Known

Right now, any attempt to use `field` inside a property accessor in cases when the property type is not yet known (depends on the getter return value) will result into `UNRESOLVED_REFERENCE`. 

Since we now can have explicit backing field declarations, we may use them to find out the type for backing fields:

```kotlin
var thing
    field = SomeThing()
    get() {
        return convertSomehow(field)
    }
    set(value) {
        field = convertTheOtherWayAround(value)
    }
```

### Custom Getters

Hypothetically, we could perform more analysis to find out whether smart type narrowing is possible for the given getter. 

### Mutability

Letting a `val` property have a mutable backing field may be useful. Consider the [following example](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/src/kotlin/util/LazyJVM.kt#L57):

```kotlin
private class SynchronizedLazyImpl<out T>(
    initializer: () -> T, 
    lock: Any? = null
) : Lazy<T>, Serializable {
    private var initializer: (() -> T)? = initializer
    @Volatile private var _value: Any? = UNINITIALIZED_VALUE
    // final field is required to enable safe publication of constructed instance
    private val lock = lock ?: this

    override val value: T
        get() {
            val _v1 = _value
            if (_v1 !== UNINITIALIZED_VALUE) {
                @Suppress("UNCHECKED_CAST")
                return _v1 as T
            }

            return synchronized(lock) {
                val _v2 = _value
                if (_v2 !== UNINITIALIZED_VALUE) {
                    @Suppress("UNCHECKED_CAST") (_v2 as T)
                } else {
                    val typedValue = initializer!!()
                    _value = typedValue
                    initializer = null
                    typedValue
                }
            }
        }
    
    ...
}
```


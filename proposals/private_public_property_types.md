# Separate types for private and public property access

* **Type**: Design proposal
* **Author**: Matej Drobnič, but I'm basically just compiling the discussion into KEEP proposal
* **Contributors**: Svetlana Isakova, Kirill Rakhman, Dmitry Petrov, Roman Elizarov, Ben Leggiero
* **Status**: Under consideration
* **Prototype**: Not Started

Original discussion of this proposal is held in [in this issue](https://youtrack.jetbrains.com/issue/KT-14663).

## Introduction

Common pattern in java is having full type accessible as private property and then only exposing required interface in the public getter:

```java
class MyClass {
	// Use full type for private access
	private final ArrayList<String> data = new ArrayList<>();
	
	// Only expose what is needed in public getter
	public List<String> getData() {
		return data;
	}
}
```

This pattern allows easy hiding of implementation details and allows for easy clean external interfaces to the classes. But there is no idiomatic Kotlin way to achieve this pattern. Best thing you can do is define two separate properties to mimic how Java does it:

```kotlin
class MyClass {
    private val _data = ArrayList<String>()
    val data: List<String>
        get() = _data
}
```

## Motivation

* There is no clean idiomatic way to do this pattern in Kotlin
* Current best approach throws away all benefits of Kotlin properties and forces developer to write Java-like code with separate private field and public getter
* Current best approach forces developer to assign two different names to single property (or pad private property, for example adding `_` prefix and then using this prefix everywhere in code)
* This is another place where Java pattern could be introduced into Kotlin with less boilerplate

## Example use cases

### Exposing read-only list outside class, but using writable list privately

We often do not want our data structures to be modified from outside. Unlike Java, this can be easily achieved in Kotlin by just exposing read-only `List`. But as already ilustrated in above example, exposing different type of a property is a bit messy.

### Android apps that use Architecture Components (`LiveData` in particular)

Proper way to do Architecture components is to use `MutableLiveData` (`LiveData` implementation that allows caller to change its value) privately inside View Model classes and then only expose read-only `LiveData` objects outside.

Example snippet [from Android app](https://github.com/elpassion/crweather/blob/9c3e3cb803b7e4fffbb010ff085ac56645c9774d/app/src/main/java/com/elpassion/crweather/MainModel.kt#L14):

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

As you can see, this pattern requires a lot of messy boilerplate with current best approach.


### RX Observable and Subjects

Data can often be pushed into reactive world using subjects. However, exposing `Subject` would allow consumer of the class to push its own data into it. That is why it is good idea to expose all subjects as read-only `Observable`:

```kotlin
class MyClass {
    private val _dataStream = PublishSubject.create<String>()
    val dataStream: Observable<String>
        get() = _dataStream
}
```

## Proposed language changes

* Add ability to specify public type when defining getters:

```kotlin
    val items = mutableListOf<Item>()
        get(): List<Item>
```

In above example `items` is `MutableList<Item>` when accessed privately inside class and read-only `List<String>` when accessed from outside class.

## Questions to consider

### How do Kotlin's multiple layers of visibility come into play? 

Would it be beneficial and feasible to also support something like this?

```kotlin
    val items = ArrayList<Item>()
        protected get()
        internal get() : MutableList<Item>
        public get(): List<Item>
```

In above example `items` would be full `ArrayList<Item>` when accessed from defining class or any subclasses (as defined by `protected` modifier), more general `MutableList<String>` when accessed from inside same module (as defined by `internal` modifier) and read-only `List<String>` when accessed from outside the module.

### What about setters?

Obviously just appending the type to the default setter would not work, since you cannot safely assign subclass or an interface to concrete type variable. But allowing different types for setters with declared body could be useful:

```kotlin
var items = mutableListOf<Item>()
        get(): List<Item>
	set(value : List<Item>) {
		field = value.toMutableList()
	}
```

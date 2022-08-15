# Data objects

* **Type**: Design proposal
* **Authors**: Alexander Udalov, Roman Elizarov, Pavel Mikhailovskii
* **Status**: Experimental since 1.7.20, stable since 1.8.0
* **Discussion and feedback**: [#317](https://github.com/Kotlin/KEEP/issues/317)


## Introduction

When using algebraic data types, it’s annoying to declare singleton values because there’s no way to get automatic toString similar to data classes. E.g. on JVM:
```kotlin
sealed class MyList<T> {
    data class Cons<T>(val value: T) : MyList<T>()
    object Nil : MyList<Nothing>()
}

fun main() {
    println(MyList.Cons(42))   // Cons(value=42)
    println(MyList.Nil)        // test.MyList$Nil@1d251891
}
```

A workaround for the user is either to declare `toString `manually, which leads to boilerplate, or use data class with a placeholder default argument, which seems bizarre.

## Other aspects

* Default toString implementation differs on JVM, JS, Native
    * In JS, toString returns [object Object]
    * Native behaves similarly to JVM, but inner classes are delimited with ., i.e. test.List*.*Nil@1d251891 in the example above
* The @hash part is meaningless even for non-ADT objects, because object is a singleton
* Can’t declare an empty @JvmRecord class in Kotlin (KT-48155 (https://youtrack.jetbrains.com/issue/KT-48155/JvmRecord-does-not-allow-one-to-define-no-parameters))
    * Probably not a problem

### toString for all objects

One possible solution is to generate toString for all objects unconditionally, which returns the simple (unqualified) name of the object.

The advantage is that it’s more uniform, and the least surprising for the users.

There are some issues though:

* It’s likely a major breaking change
* It leads to extra bytecode which is often unnecessary
* Simple name instead of FQ name might be confusing, especially for cases like companion object

### Data object

Another possible solution is to allow the data modifier on objects, which will lead to toString being generated as for data classes.

Other data class methods should not be generated:

* equals and hashCode work fine already since it’s a singleton
* componentN makes no sense because it doesn’t have a public constructor
* copy makes no sense because it’s a singleton
    * Or should it be generated and always return this?

Question: data companion object

Question: could Unit be redefined as data object?

## The Proposed Solution

* Support `data object` syntax in all frontends & backends
* Generate equals, hashCode, toString for data objects
  * `equals` should only check whether `this` and the other object are of the same type
  * `hashCode` returns the value of the hash code of the qualified class name
  * `toString` returns the simple name of the object
* On JVM: if data object is serializable, generate `readResolve` which returns the value of the INSTANCE field
* Prohibit to declare or inherit custom `equals`/`hashCode` (but not `toString`!) in data objects
  * It’s OK if there’s a non-final implementation of `equals`/`hashCode` in a superclass of a data object, since it’s overridden anyway
* Prohibit `data companion object` syntax
* After kotlin-stdlib is compiled with language version 1.8, make `Unit` and `EmptyCoroutineContext` data objects
* IDE (KTIJ-22087 (https://youtrack.jetbrains.com/issue/KTIJ-22087/Support-IDE-inspections-for-upcoming-data-objects))
  * Existing inspection that suggests to add data to a sealed subclass should also do it for objects now
  * Add an inspection to recommend to add data to a serializable object


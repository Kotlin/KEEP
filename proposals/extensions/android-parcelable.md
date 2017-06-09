# Compiler Extension to Support `android.os.Parcelable`

* **Type**: Design proposal
* **Status**: Under consideration
* **Prototype**: In progress

## Overview

The `android.os.Parcelable` API requires substantial boilerplate for each parcelable class. In this document, we investigate the possible ways of mitigating this in Kotlin via a compiler extension.

### General difficulties with `Parcelable`

The `android.os.Parcelable` API requires some boilerplate code to be implemented (see [here](https://developer.android.com/reference/android/os/Parcelable.html)):

```java
 public class MyParcelable implements Parcelable {
     private int mData;

     public int describeContents() {
         return 0;
     }

     public void writeToParcel(Parcel out, int flags) {
         out.writeInt(mData);
     }

     public static final Parcelable.Creator<MyParcelable> CREATOR
             = new Parcelable.Creator<MyParcelable>() {
         public MyParcelable createFromParcel(Parcel in) {
             return new MyParcelable(in);
         }

         public MyParcelable[] newArray(int size) {
             return new MyParcelable[size];
         }
     };
     
     private MyParcelable(Parcel in) {
         mData = in.readInt();
     }
 }
```
  
For many simple cases users want to do something like

```kotlin
@Parcelize
class MyParcelable(val data: Int): Parcelable
```  

> The name of the annotation is just an example here. Note: we can't use `Parcelable` for the annotation name as it clashes with the base interface name.
  
There's a number of annotation processing libraries that mitigate this:
  - A comparison is available [here](http://blog.bradcampbell.nz/a-comparison-of-parcelable-boilerplate-libraries/)
  - [Parceler](https://github.com/johncarl81/parceler)
  - [AutoParcel](https://github.com/frankiesardo/auto-parcel)
  - [AutoValue Parcelable Extension](https://github.com/rharter/auto-value-parcel)
  - [PaperParcel](https://github.com/grandstaish/paperparcel)
  
  
### Difficulties specific to Kotlin

To implement `Parcelable` manually in Kotlin, one has to create a companion object and use `@JvmField`:

```kotlin
data class MyParcelable(var data: Int): Parcelable {

    override fun describeContents() = 1

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(data)
    }

    companion object {
        @JvmField 
        val CREATOR = object : Parcelable.Creator<MyParcelable> {
            override fun createFromParcel(source: Parcel): MyParcelable {
                val data = source.readInt()
                return MyParcelable(data)
            }

            override fun newArray(size: Int) = arrayOfNulls<MyParcelable>(size)
        }
    }
}
```

This creates even more nesting and requires even more details to be memorized. There's a community [plugin](https://github.com/nekocode/android-parcelable-intellij-plugin-kotlin) for IntelliJ IDEA/Android Studio that generates this boilerplate. The [PaperParcel] library seems to work with Kotlin too, but requires manual creation of `CREATOR` fields:

```kotlin
@PaperParcel
data class User(
    val id: Long,
    val firstName: String,
    val lastName: String
) : PaperParcelable {
  companion object {
    @JvmField val CREATOR = PaperParcelUser.CREATOR
  }
}
```

## Proposal

We can have a compiler extension to generate all the required boilerplate behind the scenes. This is similar to what (some) annotation processors do, but 
1. Java annotation processors can't alter Kotlin code, 
2. Normally, annotation processors can't add methods to existing classes (this is possible though a private API only),
3. A compiler extension can be potentially more flexible in terms of suppressing errors and providing syntactic means to the user.  

### Simple case: completely automatic Parcelable

A compiler extension can generate serialization/deserialization logic for all properties in a primary constructor of a class marked with a special annotation:

```kotlin
@Parcelize
class MyParcelable(val data: Int): Parcelable
```  

Note that the class is not required to be a data class.

The following requirements apply here:
- The class annotated with `@Parcelize` must implement `Parcelable` (directly or through a chain of supertypes)
  - Otherwise it's a compiler error
- All parameters of the primary constructor must be properties, otherwise they can not be (de)serialized
  - If there is a non-property parameter, it is a compilation error
- Properties with initializers declared in the class body are also difficult to deserialize correctly: we'd have to generate an alternative constructor that does not execute initializers (including `init` blocks) at all and only uses the serialized data, but this has all the issues that `java.io.Serializable` has wrt "magic" object creation.
  - Properties in the class body must be marked with a `@Transient` annotation, otherwise it's a compiler warning (or error?) 
- If some properties are not parcelable (i.e. do not implement `Parcelable`, are not supported by the `Parcel` interface, and are not customized (see below)), it's a compilation error
- The user is not allowed to manually override methods of `Parcelable` or create the `CREATOR` field
   - it results in a compilation error  
   - Question: maybe overriding `describeContents()` is OK?

The annotations for Parcelable classes, transient fields, etc. should sit in a complimentary runtime library that ships together with the compiler extension.  

TODO: Check interactions with inheritance by delegation.
  
Some syntactic options:    
- The names `Parcelize` and `Transient` are just placeholders for now.
- We could allow the user to omit the supertype `Parcelable`, to be more DRY and have only the annotation to signify that the class is Parcelable, but this would be more challenging wrt the tooling support.
- We could annotate the supertype itself to make it more local: 
  - `class MyParcelable(val data: Int): @Auto Parcelable`  
  
### Generated logic

The generated serialization logic should take into account the following:
- support for `IBinder`
- support for collections and arrays
- support for file descriptors wrt `describeContents()` 
    
>Discussion:    
There is an option to generate two constructors:
>- one ordinary primary constructor,
>- another one that takes `Parcel` and creates an object from it.
>
>This has a benefit of being more convenient when it comes to hierarchies (see below). The issue here is that the second constructor will have to bypass any initializers in the class body or do sophisticated combination of that logic and the deserialization. Overall it seems easier to have only one traditional constructor and call it from the `createFromParcel` method.  
    
### Custom Parcelables

Sometimes users need to write their own custom serialization logic manually, but the boilerplate described above is undesirable. We can simplify this task by introducing the following convention:

```kotlin
@Parcelize
class MyParcelable(val data: Int) : Parcelable {
    companion object : Parceler<MyParcelable> {
        override fun MyParcelable.writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(data)
        }
        
        override fun createFromParcel(parcel: Parcel): MyParcelable {
            return MyParcelable(parcel.readInt())
        }
    }
}
```

The `Parceler` interface is defined in the same runtime library that comes with the compiler extension. It's defined as follows:

```kotlin
interface Parceler<P : Parcelable> {
    fun describeContents() = 0

    fun P.writeToParcel(parcel: Parcel, flags: Int)
    
    fun createFromParcel(parcel: Parcel): P    
    
    fun newArray(size: Int): Array<P>
}
```

If a `@Parcelize` class has a companion object that implements `Parceler`:
- the necessary boilerplate is generated for the `Parcelable` convention and delegated to the companion object
- the `newArray()` method of the companion object is implemented automatically unless it's explicitly overridden
- the type argument to `Parceler` must match the containing class, otherwise it's a compiler error

Note: Indirect implementations (`object : Foo`, where `Foo : Parceler`) are questionable here, but we can allow them if there are use cases for it.

Syntactic options:
- It does not have to be a companion object, a named object, e.g. `object Parceler: Parceler<MyPercelable>` may be ok too
- We may want to require the object to be annotated to explicitly show that the `newArray()` is auto-generated

> Discussion: why not implement `newArray()` in the `Parceler` interface itself?
First, this method can not be implemented generically (with erasure), because the runtime type of the array created is different every time.
We could do something like `fun newArray(size: Int) = throw UnsupportedOperationException("This method must be overridden by subclasses")`, this has the benefit of making the IDE's life easier: otherwise we'd have to teach it to skip `newArray()` when generating stubs for the Override/Implement action in annotated classes. OTOH, this is error-prone in the case of non-annotated classes. A solution here could be to magically implement `newArray()` in all concrete subclasses of `Parceler` regardless of the annotation. I wonder if this can be promoted to a general feature of Kotlin...       

## Per-property and per-type Parcelers

Existing annotation processors allow for per-field and per-type customization of serialization logic, e.g.:

```kotlin
@Parcelize
class MyParcelable(
    @CustomParceler(FooParceler::class)
    val foo: Foo,
    @CustomParceler(Bar.Parceler::class)
    val bar: Bar
): Parcelable
``` 

The `@CustomParceler` annotation can be defined as follows:

```kotlin
annotation class CustomParceler(val parcelerClass: KClass<out Parceler<*>>)
```

Additional rules:
- the class passed as a custom Parceler for a property of type `T` must implement `Parceler<T>` and be a singleton (declared as object)

Question: how to handle `describeContents()` here? Should we bitwise-or all the parcelers? How do annotation processors go about it?    

It would also be desirable to provide bulk customization for all properties of the same type, e.g. `java.util.Date`. This can be done globally, e.g. though a meta-annotation, or locally, e.g.:

```kotlin
@Parcelize
@CustomParcelerForType(
    type = Date::class,
    parceler = DateParceler::class
)
class MyParcelable(val from: Date, val to: Date): Parcelable
```

Local customization is more flexible, but will likely result in a lot of duplication.
Global customization is problematic wrt inceremental and separate compilation, but this can be addressed in the future.  

## Handling hierarchies of Parcelable classes

Hierarchies of Parcelable classes are challenging from two points of view:
- organizing constructors (see above)
- deserializing an instance of the right class from a Parcel, when the container has only a reference to a superclass.

The latter issue seems to be resolvable by "tagging" such records through writing fully-qualified names of concrete classes to parcels, but it's not clear whether it's a good idea, since
- it involves reflection
- it will increase the size of parcels

This proposal does not support hierarchies for now.  

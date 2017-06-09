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
- All parameters of the primary constructor must be properties, otherwise they can not be (de)serialized
  - If there is a non-property parameter, it is a compilation error
- Properties with initializers declared in the class body are also difficult to deserialize correctly: we'd have to generate an alternative constructor that does not execute initializers (including `init` blocks) at all and only uses the serialized data, but this has all the issues that `java.io.Serializable` has wrt "magic" object creation.
  - Properties in the class body must be marked with a `@Transient` annotation.
    
## Making writing custom Parcelables easy

- companion objects
- Parceler

## Per-property and per-type Parcelers

## Handling hierarchies of Parcelable classes
# Compiler Extension to Support `android.os.Parcelable`

* **Type**: Design proposal
* **Status**: Under consideration
* **Prototype**: In progress

## Summary

- Difficulties with Parcelable in general
  - https://developer.android.com/reference/android/os/Parcelable.html
- Difficulties specific to Kotlin

```kotlin
data class Model(var test1: Int, var test2: Int): Parcelable {

    constructor(source: Parcel): this(source.readInt(), source.readInt())

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel?, flags: Int) {
        dest?.writeInt(this.test1)
        dest?.writeInt(this.test2)
    }

    companion object {
        @JvmField final val CREATOR: Parcelable.Creator<Model> = object : Parcelable.Creator<Model> {
            override fun createFromParcel(source: Parcel): Model{
                return Model(source)
            }

            override fun newArray(size: Int): Array<Model?> {
                return arrayOfNulls(size)
            }
        }
    }
}
```

- Existing solutions
  - Comarison in http://blog.bradcampbell.nz/a-comparison-of-parcelable-boilerplate-libraries/
  - https://github.com/johncarl81/parceler
  - https://github.com/frankiesardo/auto-parcel
  - https://github.com/rharter/auto-value-parcel
  - https://github.com/grandstaish/paperparcel
  - https://github.com/nekocode/android-parcelable-intellij-plugin-kotlin

## Making the simple case easy

- Only parameters of the promary constructor
  - why

## Making writing custom Parcelables easy

- companion objects
- Parceler

## Per-property and per-type Parcelers
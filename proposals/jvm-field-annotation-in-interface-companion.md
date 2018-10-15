# @JvmField for interface companion properties

* **Type**: Design proposal
* **Author**: Mike Bogdanov
* **Status**: Implemented in Kotlin 1.3
* **Discussion**: [KEEP-152](https://github.com/Kotlin/KEEP/issues/152)

## Summary

Allow to use `@JvmField` annotation on interface companion properties similar to class companion ones.

## Description

`@JvmField` annotation on interface companion properties has same effect and similar restrictions as annotation on class companion members:
* generates static field in interface with initialization in interface <clinit> 
* adding or removing `@JvmField` annotation is binary incompatible change cause it's changes fields owner
* not applicable for `const`, `lateinit` and delegated properties
* property should not have any custom accessors
* property can't override anything
* **applicable only if all companion properties are `public final val` annotated with `@JvmField`** (additional restriction)   


``` kotlin
interface Foo {
    companion object {
        @JvmField
        val foo: String = "bar"            
    }
}
```

## Open questions

Maybe weak additional condition ("all companion properties are `public final val` annotated with `@JvmField`") 
to allow use `const` properties withing `@JvmField` ones. 
In such case `const` properties should also be moved to interface 
(NB: now additional declaration copy is created in interface for `const` property in companion)     


## Related issues

* [KT-15807](https://youtrack.jetbrains.com/issue/KT-15807) @JvmField is not applicable to interface companion properties


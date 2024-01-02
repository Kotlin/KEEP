# @JvmStatic for interface companion members

* **Type**: Design proposal
* **Author**: Mike Bogdanov
* **Status**: Implemented in Kotlin 1.3
* **Discussion**: [KEEP-150](https://github.com/Kotlin/KEEP/issues/150)

## Summary

Allow to use `@JvmStatic` annotation on interface companion members similar to class companion ones.

## Description

`@JvmStatic` annotation on interface companion members has same effect and similar restrictions as annotation on class companion members:
* generates static method in interface. This static method will delegate to the companion member
* can't be used on `const` and `@JvmField` properties
* additionally requires '-jvm-target 1.8' compilation option


``` kotlin
interface Foo {
    companion object {
        @JvmStatic
        fun foo() {}

        @JvmStatic
        val foo: String
            get() = "bar"
    }
}
```

## Related issues

* [KT-6301](https://youtrack.jetbrains.com/issue/KT-6301) Support JvmStatic annotation on interface companion object members


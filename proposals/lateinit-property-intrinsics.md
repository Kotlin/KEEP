# Lateinit property intrinsics

Goal: provide a way to check whether a `lateinit` property was assigned, and a way to reset its value.

## Motivation

Original issue: https://youtrack.jetbrains.com/issue/KT-9327.

A prominent use case is tests, for example JUnit. A lateinit property may or may not be initialized in `setUp`, however `tearDown` must perform the cleanup only if the property was initialized, or otherwise it would have to catch `UninitializedPropertyAccessException`.

``` kotlin
class Test {
    lateinit var file: File

    @Before fun setUp() {
        file = createFile()
    }

    @After fun tearDown() {
        if (... file has been initialized ...) {
            file.delete()
        }
    }
}
```

There's a similar use case for resetting the value, when the property retains memory that should be freed after running the test.

``` kotlin
class Test {
    lateinit var environment: Environment

    @Before fun setUp() {
        environment = createEnvironment()
    }

    @After fun tearDown() {
        ... reset environment ...
    }
}
```

## Description

We propose to add two declarations to the standard library, an extension property `isInitialized` and an extension function `deinitialize`. Both of them would be available _only on a property reference expression_ that references a lateinit property, accessible at the call site. Both of them are inline and intrinsic, i.e. a corresponding bytecode is generated manually by the compiler at each call site, because it's not possible to implement them in Kotlin.

``` kotlin
package kotlin

import kotlin.internal.InlineOnly
import kotlin.internal.AccessibleLateinitPropertyLiteral
import kotlin.reflect.KProperty0

/**
 * Returns `true` if this lateinit property has been assigned a value, and `false` otherwise.
 */
@SinceKotlin("1.2")
@InlineOnly
inline val @receiver:AccessibleLateinitPropertyLiteral KProperty0<*>.isInitialized: Boolean
    get() = throw NotImplementedError("Implementation is intrinsic")

/**
 * Resets the value of this lateinit property, making it non-initialized.
 */
@SinceKotlin("1.2")
@InlineOnly
inline fun @receiver:AccessibleLateinitPropertyLiteral KProperty0<*>.deinitialize() {
    throw NotImplementedError("Implementation is intrinsic")
}
```

`AccessibleLateinitPropertyLiteral` is an internal annotation (accessible only in standard library sources) defined as follows:

``` kotlin
package kotlin.internal

/**
 * The value of this parameter should be a property reference expression (`this::foo`), referencing a `lateinit` property,
 * the backing field of which is accessible at the point where the corresponding argument is passed.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
@SinceKotlin("1.2")
internal annotation class AccessibleLateinitPropertyLiteral
```

To call any of these two declarations, simply pass a bound reference to the property in question as the receiver:

``` kotlin
if (this::environment.isInitialized) {
    this::environment.deinitialize()
}
```

## Resolution

`isInitialized` and `deinitialize` are two normal declarations that are resolved according to the standard Kotlin rules. To prevent them from being called on any `KProperty0` instances except symbolic references, we implement a new call checker (call checkers are run only after the resolution is complete and successful). This checker is going to check that an argument passed to a parameter annotated with `AccessibleLateinitPropertyLiteral` is indeed a property literal (after being deparenthesized) and its backing field is accessible at the call site.

``` kotlin
class Test(val name: String) {
    lateinit var file: File

    fun test() {
        this::file.isInitialized      // OK
        (this::file).isInitialized    // OK

        val otherTest = Test()
        otherTest::file.isInitialized // OK, backing field is accessible, even if it's another instance

        this::name.isInitialized      // Error, referenced property is not lateinit

        val q: KProperty0<*> = getProperty(...)
        q.isInitialized               // Error, receiver must be a property literal

        val p = this::file
        p.isInitialized               // Error, receiver must be a property literal
    }
}

class Other {
    fun test() {
        Test()::file.isInitialized    // Error, backing field is not accessible here
    }
}
```

Note that the `AccessibleLateinitPropertyLiteral` annotation is needed mostly for the users who are looking at these declarations in standard library sources. We could hard-code the support of exactly `isInitialized` and `deinitialize` from the standard library into the call checker, however it would be difficult for a user to understand why it's not possible to pass any `KProperty0` instance, not just a reference.

## Code generation

Using property's getter to check `isAccessible` is not possible because it throws an `UninitializedPropertyAccessException` if the property is not initialized, and using property's setter to nullify it in `deinitialize` is not possible because it asserts that the value parameter is non-null at the beginning. So, the compiler must generate direct read/write accesses to the backing field's property. The requirement "must be a reference to a property, the backing field of which should be accessible" is thus needed because the compiler must know _statically_ which property is referenced, check that it's a lateinit property, and ensure that it's possible to generate access to the backing field.

The generated bytecode itself is very simple. Because both declarations are intrinsic, we're able to avoid the generation of the anonymous class for the property reference and use `GETFIELD`/`PUTFIELD` instructions.

``` kotlin
class Test {
    lateinit var file: File

    fun test1() {
        this::file.isInitialized   // ALOAD 0, GETFIELD Test.file, IFNULL ...
    }

    fun test2() {
        this::file.deinitialize()  // ALOAD 0, ACONST_NULL, PUTFIELD Test.file
    }
}
```

### Backing field accessibility

We'd like to allow calling `isInitialized` and `deinitialize` on as many properties and from as many different contexts as possible. However, without certain limitations we would have problems with source and binary compatibility.

The main limitation is that we do not allow calling `isInitialized` or `deinitialize` on a lateinit property declared in another class, lexically unrelated to the class or file of the call site. It's not that we don't know what bytecode to generate. If the property (and thus its backing field) is public, we could generate `GETFIELD`/`PUTFIELD` exactly in the same way as for the property in the containing class. However, doing so would make removing the `lateinit` modifier on a property, which is today merely an implementation detail, a source-breaking and binary-breaking change. We don't want to make the fact that the property is `lateinit` a part of its API, so we disallow usages from other classes.

``` kotlin
class Test {
    lateinit var file: File
}

class Other {
    fun test() {
        Test()::file.isInitialized    // Error!
    }
}
```

Note that we could allow usages from other classes _in the same file_ but for simplicity, and to avoid errors when moving classes between files, we disallow such usages.

Note that we _do_ allow usages from nested/inner classes of the property's declaring class, as well as from any lambdas and local classes inside members of the declaring class.

``` kotlin
class Test {
    lateinit var file: File

    inner class Inner {
        fun test(t: Test) {
            this@Test::file.isInitialized    // OK
            run {
                t::file.isInitialized        // OK
            }
        }
    }
}
```

In case the property is private and is accessed from a nested/local class or lambda, the JVM back-end must generate a synthetic accessor for the backing field:

``` kotlin
class Test {
    private lateinit var file: File

    fun test() {
        run {
            this::file.isInitialized    // ALOAD 0, INVOKESTATIC Test.access$getFile, IFNULL ...
        }
    }
}
```

## Tooling support

Code completion in the IDE must only propose `isInitialized` and `deinitialize` if the calls are going to be allowed by the compiler, i.e. on an accessible lateinit property references.

## Known issues

* The solution is admittedly very ad-hoc. The `AccessibleLateinitPropertyLiteral` annotation serves only one particular purpose which is hardly generalizable to be useful to solve any other problems.
* Because of the weird nature of the proposed call checker, trivial refactorings like "Extract variable" or "Use .let" could break the code here in an unexpected way:
    ``` kotlin
    if (this::file.isInitialized) ...             // OK

    // Extract variable

    val property = this::file
    if (property.isInitialized) ...               // Error!

    // Use .let

    if (this::file.let { it.isInitialized && it.get() == ...}) ...    // Error!
    ```
* Usages in inline functions are risky because the generated bytecode references the backing field and thus exposes it to all clients who can see the inline function. If the property is made non-lateinit (which, as discussed earlier, should be a source & binary compatible change), already inlined bytecode would no longer work. Looks like it's safer to disallow usages in inline functions for now.
    ``` kotlin
    class Test {
        lateinit var file: File

        inline fun getFileName(): String? {
            if (this::file.isInitialized) {      // file's backing field is exposed!
                return file.name
            }
            return null
        }
    }
    ```

## Other approaches

* TODO

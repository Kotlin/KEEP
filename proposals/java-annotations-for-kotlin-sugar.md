# Java annotations for Kotlin sugar

* **Type**: Design proposal
* **Author**: Jake Wharton
* **Status**: Under consideration
* **Prototype**: Partially implemented in Kotlin 1.1.60


## Feedback

Discussion of this proposal is held in
[this issue](https://github.com/Kotlin/KEEP/issues/110).


## Summary

This proposal adds annotations which enable the use of more Kotlin language
features like extension functions, extension properties, default values, and
property names for Java code.

 * `@ExtensionFunction` / `@ExtensionProperty` - Turn a static method with at
   least one argument into an extension function or an extension property.
 * `@DefaultValue` - Default parameter values.
 * `@KtName` - An alternate name for methods, fields, and parameters for use
   by Kotlin code.


## Motivation / use cases

There is a large corpus of Java libraries which, for various reasons, can't
or won't port to Kotlin for the foreseeable future. This fact does not render
them as obsolete or inferior to a Kotlin library. In fact, a certain portion
of Java library authors are sympathetic to Kotlin users and are willing to do
more for compatibility.

Java libraries following _Effective Java_ item 1 have static factory methods
for adapting arguments into instances of the enclosing type. For example,
to [create a Guava `ByteSource` from a `File`][file-byte-source]:
```kotlin
val file = File("hello.txt")
val source = Files.asByteSource(file)
```

This calling convention is significantly different than what would be used
creating an `InputStream` from a `File` with the Kotlin standard library:
```kotlin
val file = File("hello.txt")
val input = file.inputStream()
```

A Kotlin-friendly version of Guava's `ByteSource` factory could be changed
to:
```java
@ExtensionFunction
public static ByteSource asByteSource(File file) { .. }
```
which would allow Kotlin callers to instead use it as an extension:
```kotlin
val file = File("hello.txt")
val source = file.asByteSource()
```

To complete the calling convention parity with the standard library,
the function name can be adjusted solely on the Kotlin side:
```java
@ExtensionFunction
@KtName("byteSource")
public static ByteSource asByteSource(File file) { .. }
```
to enable:
```kotlin
val file = File("hello.txt")
val source = file.byteSource()
```

Changing the name from the Kotlin side is essential as the context of the class
name is lost when changing to an extension function. For example, if we naively
translate Guava's `ImmutableSet` factory:
```java
@ExtensionFunction
public static <T> ImmutableSet<T> copyOf(Collection<T> list) { .. }
```
the function lacks clarity in the operation it performs:
```kotlin
val list = listOf("a", "b", "c")
val immutableSet = list.copyOf()
```

With `@KtName` we can again create parity with the calling convention of Kotlin:
```java
@ExtensionFunction
@KtName("toImmutableSet")
public static <T> ImmutableSet<T> copyOf(Collection<T> list) { .. }
```
```kotlin
val list = listOf("a", "b", "c")
val immutableSet = list.toImmutableSet()
```

Static methods annotated with `@ExtensionProperty` will be turned into
extension properties. For example, to [get the root cause of an exception with
Guava][root-cause]:
```kotlin
} catch (e: InvocationTargetException) {
  // Original calling convention
  val cause = Throwables.getRootCause(e)
}
```
```java
// Updated Java signature with annotation
@ExtensionProperty
public static Throwable getRootCause(Throwable t) { .. }
```
```kotlin
} catch (e: InvocationTargetException) {
  // New calling convention
  val cause = e.rootCause
}
```

`@ExtensionProperty` methods with names that match the ["Getters and Setters"
Java-to-Kotlin interop rules][getters] will have the same renaming behavior as
members. Using `@KtName` allows overriding this behavior, if desired.

While the Java class file format does allow parameter names as of version 52
(corresponding to Java 8), they are opt-in and almost never included. Once
enabled, they also require committing to stable parameter names for the
entirety of the API surface. `@KtName` can also be used to define stable
parameter names. For example, [Android's `View.setPadding`][android-padding]
has four parameters in an order that can be difficult to remember:
```java
public void setPadding(int left, int top, int right, int bottom) { .. }
```
By adding `@KtName`, Kotlin callers can specify the parameters in any order:
```java
public void setPadding(
    @KtName("left") int left,
    @KtName("top") int top,
    @KtName("right") int right,
    @KtName("bottom") int bottom) { .. }
```
```kotlin
val view = View(context)
view.setPadding(top = 5, bottom = 5, left = 10, right = 10)
```

Default values can also be specified with `@DefaultValue` which are interpreted
as expressions in a similar fashion to [`ReplaceWith`][replace-with]. In
keeping with Android's `View.setPadding` example, we can now add defaults that
look up the current value allowing a subset of values to be passed:
```java
public void setPadding(
    @KtName("left") @DefaultValue("paddingLeft") int left,
    @KtName("top") @DefaultValue("paddingTop") int top,
    @KtName("right") @DefaultValue("paddingRight") int right,
    @KtName("bottom") @DefaultValue("paddingBottom") int bottom) { .. }
```
```kotlin
val view = View(context)
view.setPadding(left = 10, right = 10)
```

These annotations can all combine together to adapt a Java API which is
otherwise unidiomatic to something which feels very natural.
```java
@ExtensionFunction
@KtName("toByteString")
public static ByteString of(
    byte[] bytes,
    @KtName("offset") @DefaultValue("0") int offset,
    @KtName("count") @DefaultValue("bytes.size - offset") int count) { .. }
```
```kotlin
val bytes = byteArrayOf(1, 2, 3)
// without annotations:
val byteString1 = ByteString.of(bytes, 0, bytes.length)
// with annotations:
val byteString2 = bytes.toByteString()
```


## Alternatives

At present, libraries written in Java that want to be Kotlin friendly can
[add nullness annotations to avoid platform types][platform-types] and can use
well-known names to enable some [operator use][operators] and
[property use][getters]. This is the limit of the Kotlin language features they
have access to.

A Java library can do one of two things to go farther with its support:

 1. Publish Kotlin extensions inside their library or as a sibling artifact.
 2. Rewrite their public API (or the entire library) in Kotlin.

Since rewriting in Kotlin would obviously solve all of the interoperability
problems, option #1 is the only real comparison. [JUnit 5][junit5] and
[Project Reactor][reactor] are two examples which include Kotlin extensions in
their primary artifact but with an optional Kotlin dependency.
[RxKotlin][rxkotlin] and [Android KTX][android-ktx] are two examples of
separate artifacts (for RxJava and the Android framework, respectively) to
provide extensions.

The majority of features provided by these libraries are adapting Java
static methods into Kotlin instance extensions. Functionality-wise, there
should be no difference between an annotated Java method and a manually-written
Kotlin extension. For the Guava `ByteSource` factory, a zero-overhead extension
can be written:
```kotlin
inline fun File.byteSource(): ByteSource = Files.asByteSource(this)
```

Adding parameter names and default values to manually-written Kotlin extensions
works for adapting Java static method but does not fully work for instance
methods. For the Android padding example, you can write an extension that can
enables the use of parameter names for a subset of arguments, but fails when
you try to include all four:
```kotlin
inline fun View.setPadding(
  left: Int = paddingLeft,
  top: Int = paddingTop,
  right: Int = paddingRight,
  bottom: Int = paddingBottom
) {
  setPadding(left, top, right, bottom)
}

// works:
view.setPadding(left = 5, right = 5)
// error:
view.setPadding(left = 5, right = 5, top = 10, bottom = 10)
```

This is because Kotlin will always prefer calling a real member over an
extension. When all four arguments are provided the member (and its lack of
parameter name support) is resolved.

As outlined in the "Migration" section, the annotation-based approach enables
Kotlin consumers to be gradually introduced to the Kotlin calling convention
without having to know they're available and without having to seek out a
sibling artifact.

Other advantages start to become subjective:

 - The annotation-based approach keeps a single source of truth for both Java
   and Kotlin callers. This is similar to what `@JvmName` and `@JsName` provide
   to Kotlin API authors for controlling how Java and Javascript consumers see
   their API.

   A potential downside of this is that you are limited to the functionality
   that the annotations provide.

   A single source of truth can be both an advantage and disadvantage for
   documentation. For usage samples, it can be challenging to cover the
   conventions of each language. The full documentation is always present,
   though, regardless of the calling language. Manually-written extensions tend
   to contain only a summary and a `@see` directive to the original Java
   method.

 - No new compiler / toolchain has to be introduced for the annotation-based
   approach. Your compilation and distribution mechanism for Java does not have
   to be updated to include Kotlin in the main or a sibling artifact.


## Migration

Since these annotations change the calling convention and/or the name of
functions and properties, their addition is otherwise a source-incompatible
change for Kotlin users. In an effort to mitigate the pain that this would
cause and to guide the consumer to discoverability of the Kotlin-specific
calling convention, both the Java-style and Kotlin-style invocations are
allowed.

The previous example:
```java
@ExtensionFunction
@KtName("toByteString")
public static ByteString of(
    byte[] bytes,
    @KtName("offset") @DefaultValue("0") int offset,
    @KtName("count") @DefaultValue("bytes.length") int count) { .. }
```
```kotlin
val bytes = byteArrayOf(1, 2, 3)
// Java-style:
val byteString1 = ByteString.of(bytes, 0, bytes.length)
// Kotlin-style:
val byteString2 = bytes.toByteString()
```
would compile as-is having both styles in use.

To gently migrate users to the Kotlin-style syntax, the Kotlin IDEA
plugin would highlight Java-style call sites with a yellow underline. An
intention action would automatically rewrite the Java-style to Kotlin-style,
using default values where appropriate.

This migration behavior is designed to match what happens when other
Java-style conventions are used such as calling `foo.equals(bar)` (which
should be `foo == bar`) or `foo.getBaz()` (which should be `foo.baz`).

The compiler would not emit these as actual warnings.


## Failure scenarios

If a library author chooses to ignore validation of any kind and creates an
invalid configuration the Kotlin consumer shouldn't be punished.

For example, the use of `@KtName` opens up the possibility to create name
collisions:
```java
public static String one() { .. }

@KtName("one")
public static String uno() { .. }
```

In this case the **entire** `uno` method would be rejected from any annotation
enhancement. This is important as the intent can be ambiguous in certain
configurations. For example, both `@ExtensionFunction` and `@ExtensionProperty`
may be present on a single method.

The Kotlin compiler, when consuming an annotated Java library, should emit a
warning for each Java method with an invalid configuration. The Kotlin IDEA
plugin should not show any warnings inline, not offer any migration assistance,
and treat the method as otherwise being pure, vanilla Java.


## Annotation artifact

Since these annotations are meant for consumption by Java libraries, they
should not be part of the Kotlin standard library. Since they are
Kotlin-specific, they should not be part of `org.jetbrains:annotations`.

A new artifact, `org.jetbrains.kotlin:kotlin-java-annotations` should be
created to house these annotations. Since the interpreter for these
annotations is the Kotlin compiler and Kotlin IDEA plugin, they should
live in the [JetBrains/kotlin][kotlin-repo] repository and be versioned
and released as part of the normal Kotlin process.

This artifact could also be the same for the annotations proposed in
[KEEP-99][keep-99].


## Validation

When adding platform-specific annotations such as `@JvmName` or `@JsName` to
Kotlin code the compiler validates you are not violating the target platform's
language rules. When Java code is being annotated, however, the Kotlin compiler
is not present to perform validation. An annotation processor will be provided
to perform validation.

The annotation processor artifact will be
`org.jetbrains.kotlin:kotlin-java-annotations-validator` and live in the
[JetBrains/kotlin][kotlin-repo] repository and be versioned and released as
part of the normal Kotlin process.

In order to get real-time validation in the IDE, the Kotlin IDEA plugin will
also include the validation rules which it will run on Java sources containing
the annotations to show errors.


## Scope

This proposal focuses on only 4 annotations: `@ExtensionFunction`,
`@ExtensionProperty`, `@DefaultValue`, and `@KtName`. These were chosen because
a sampling of Java libraries showed them to have the most impact to Kotlin
consumers if implemented.

There are certainly other annotations which could be proposed and implemented
to further interop. Ones that come to mind are denoting top-level functions,
lambda parameters becoming lambda-with-receiver, final classes with a static 
`getInstance()` method becoming an object, `Class`-accepting generic methods
being automatically provided with reification, and destructing component
markers. While appealing, these are decidedly more rare and would only increase
the complexity of this proposal. If desired they can be pursued at a later
time.





 [file-byte-source]: https://google.github.io/guava/releases/25.0-jre/api/docs/com/google/common/io/Files.html#asByteSource-java.io.File-
 [root-cause]: https://google.github.io/guava/releases/25.0-jre/api/docs/com/google/common/base/Throwables.html#getRootCause-java.lang.Throwable-
 [android-padding]: https://developer.android.com/reference/android/view/View#setPadding(int,%20int,%20int,%20int)
 [replace-with]: http://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-replace-with/index.html
 [kotlin-repo]: https://github.com/JetBrains/kotlin
 [keep-99]: https://github.com/Kotlin/KEEP/issues/99
 [getters]: https://kotlinlang.org/docs/reference/java-interop.html#getters-and-setters
 [platform-types]: https://kotlinlang.org/docs/reference/java-interop.html#null-safety-and-platform-types
 [junit5]: https://github.com/junit-team/junit5/blob/6b7da8949e8b0f93f7e4f7f2b745ae0988474c9a/junit-jupiter-api/src/main/kotlin/org/junit/jupiter/api/Assertions.kt
 [reactor]: https://github.com/reactor/reactor-core/tree/d9d76aba749022466d125890d13e3ba0f23702cd/reactor-core/src/main/kotlin/reactor/core/publisher
 [rxkotlin]: https://github.com/ReactiveX/RxKotlin/#readme
 [android-ktx]: https://github.com/android/android-ktx#readme
 [operators]: http://kotlinlang.org/docs/reference/java-interop.html#operators
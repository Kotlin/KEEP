# Improvements to annotation use-site targets on properties

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Alexander Udalov, Ilmir Usmanov, Mikhail Zarechenskii
* **Discussion**: [KEEP-402](https://github.com/Kotlin/KEEP/issues/402)
* **Status**: Experimental in Kotlin 2.2.0
* **Related YouTrack issue**: [KT-19289](https://youtrack.jetbrains.com/issue/KT-19289/Hibernate-Validator-Annotations-Dropped), [KTIJ-31300](https://youtrack.jetbrains.com/issue/KTIJ-31300/Draft-Validation-Annotation-Email-Doesnt-Work-in-Kotlin-Constructor)

## Abstract

Several kinds of property declarations in Kotlin define more than one use-site target for annotations. If an annotation is applied, one of those targets is chosen using the [defaulting rule](https://kotlinlang.org/docs/annotations.html#java-annotations). This KEEP proposes to change that behavior to choose several targets instead, and introduce a new `all` meta-target.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Motivation](#motivation)
  * [Potential misunderstandings](#potential-misunderstandings)
  * [Alignment with Java](#alignment-with-java)
* [Technical details](#technical-details)
  * [Compiler flags](#compiler-flags)
    * [Migration](#migration)
    * [Exceptions](#exceptions)
  * [Examples](#examples)
  * [Impact](#impact)
* [Other design choices](#other-design-choices)

## Motivation

Kotlin offers succint syntax to define a constructor parameter, a property, and its underlying backing field, all in one go.

```kotlin
class Person(val name: String, val age: Int)
```

The other side of the coin is that whenever an annotation is given in that position, there is some ambiguity to which of those three elements the annotation should be applied. Kotlin provides a way to be explicit, namely [use-site targets](https://kotlinlang.org/docs/annotations.html#annotation-use-site-targets).

> [!NOTE]
> Throughout this proposal we shall use annotations from [Jakarta Bean Validation]( https://beanvalidation.org/) in the examples.

```kotlin
class Person(@param:NotBlank val name: String, @field:PositiveOrZero val age: Int)
```

Declaring a use-site target is not mandatory, though. In case none is given, the **defaulting rule** applies:

> If you don't specify a use-site target, the target is chosen according to the `@Target` annotation of the annotation being used. If there are multiple applicable targets, the first applicable target from the following list is used: `param`, `property`, `field`.

We argue below that this defaulting rule should be changed. Instead of choosing a single target, the annotation should be applied to _both_ the constructor parameter and the property or field. Furthermore, sometimes it is also important to apply the same annotation to getters and setters, a scenario that currently requires duplication.

### Potential misunderstandings

The main issue with the current defaulting rule is that developers are often surprised when an annotation is _not_ applied to the target they intended. Consider the following example, a variation of the previous one in which the properties are mutable.

```kotlin
class Person(@NotBlank var name: String, @PositiveOrZero var age: Int)
```

Following the defaulting rule, the validation annotations `@NotBlank` and `@PositiveOrZero` are applied _solely_ to the constructor parameter. In practical terms, this means that their values are validated only when first creating the instance, but not on later modifications of the properties. This does not seem like the intended behavior, and may lead to broken invariants.

### Alignment with Java

Java provides [records](https://openjdk.org/jeps/395) since version 16 (experimental since 14). Records are syntactically very close to definition of properties in primary constructors, and they also expand to several declarations in the underlying JVM platform.

```java
record Person(String name, int age) { }
```

The rules for [annotations on record components](https://openjdk.org/jeps/395#Annotations-on-record-components) go even further than the tryad of `param`, `property`, and `field`; they also apply to the property getter and to the Java-only `RECORD_COMPONENT` target.

Since JVM is one of the main targets for Kotlin, we think alignment with the rest of the players is very important. One reason is to make it easier for developers to work on multi-language projects, without having to remember small quirks per language. On top of that, libraries developed with Java in mind may assume the behavior of records, and they would then fail in a very similar scenario in Kotlin.

For full comparison, Scala also applies a [defaulting rule](https://www.scala-lang.org/api/current/scala/annotation/meta.html) giving preference to parameters:

> By default, annotations on (`val`-, `var`- or plain) constructor parameters end up on the parameter, not on any other entity.

However, they provide a way to create a version of an annotation with a specific target. That way the correct defaulting can be chosen per annotation.

## Technical details

**Param-and-property defaulting rule**: the defaulting rule should read as follows.

> If you don't specify a use-site target, the target is chosen according to the `@Target` annotation of the annotation being used. If there are multiple targets, choose one or more as follows:
> 
> - If the constructor parameter target `param` is applicable, use it.
> - If any of the property target `property` or field target `field` is applicable, use the first of those.
>
> It is an error if there are multiple targets and none of `param`, `property` and `field` is applicable.

**New `all` annotation use-site target**: in addition to the existing use-site targets, we define a new meta-target for _properties_. Such an annotation should be propagated, whenever applicable:

- To the parameter constructor (`param`), if the property is defined in the primary constructor,
- To the property itself (`property`),
- To the backing field (`field`), if the property has one,
- To the getter (`get`),
- To the setter parameter (`set_param`), if the property is defined as `var`.
- If the class is annotated with `@JvmRecord`, to the Java-only target `RECORD_COMPONENT`.

The last rule ensures that way the behavior of a `@JvmRecord` with annotations using `all` as use-site target aligns with Java records.

Note that the annotation is **not** propagated to types, and potential extension receivers or context receivers/parameters.

The `all` target may **not** be used with [multiple annotations](https://kotlinlang.org/spec/syntax-and-grammar.html#grammar-rule-annotation). It is unclear what the behavior should be when the multiple annotations have different targets.

```kotlin
@all:[A B] // forbidden, use `@all:A @all:B`
val x: Int = 5
```

The `all` target may **not** be used with [delegated properties](https://kotlinlang.org/spec/declarations.html#delegated-property-declaration). It is unclear whether the annotation should or should not be propagated to the underlying delegate; in other words, whether the `delegate` target should be part of the propagation.

### Compiler flags

The Kotlin compiler shall provide a flag to change the defaulting behavior.

- `-Xannotation-defaulting=first-only` corresponds to the defaulting rule in version 1.9 of the Kotlin specification.
- `-Xannotation-defaulting=param-property` corresponds to the new proposed param-and-property defaulting rule.

#### Migration

The param-and-property defaulting rule should become the new defaulting rule in the language. For an orderly transition between the two worlds, we define an additional compiler flag.

- `-Xannotation-defaulting=first-only-warn` behaves as `first-only`; in addition, it raises a warning whenever the following are true:
  - The annotation does _not_ have a explicit use-site target,
  - Both the `param` and one of `property` or `field` targets are allowed for the specific element.

If the user wants to keep the `first-only` behavior but _not_ receive any warnings, the workaround is to explicitly write the use-site target. This is also a future-proof way to keep the current behavior.

> [!TIP]
> _Tooling support_: in response to this warning, editors supporting Kotlin are suggested to include actions to make them go away. That may include enabling the proposed flag project-wise, or making the use-site target for an annotation explicit.

In the next version of the Kotlin compiler after this KEEP is approved, the default value of the flag should be `first-only-warn`. After this transitional period, the default value should change to `param-property`.

#### Exceptions

There are two exceptions to the above rule for migration. In these two cases _no_ warning should be issued, even though the target may change between versions.

- Deprecation and suppression annotations, including `@Deprecated` and `@Suppress`.
- Annotations on properties of annotation classes; in this case instanced are created through special reflection support.

### Examples

Consider [`Email` from Jakarta Bean Validation](https://jakarta.ee/specifications/bean-validation/3.0/apidocs/jakarta/validation/constraints/email), whose targets are defined as follows.

```java
@Target(value={METHOD,FIELD,ANNOTATION_TYPE,CONSTRUCTOR,PARAMETER,TYPE_USE})
public @interface Email { }
```

Those Java targets are mapped to the [corresponding ones in Kotlin](https://kotlinlang.org/spec/annotations.html#annotation-targets). In particular, note that `PROPERTY` is _not_ a targe.

Consider now the following code which uses the annotation in two different places.

```kotlin
data class User(val username: String, /* 1️⃣ */ @Email val email: String) {
  /* 2️⃣ */ @Email val secondaryEmail: String? = null
}
```

Before this proposal, in position 1️⃣ the annotation is applied to the constructor parameter only (target `param`). With this proposal, now it is applied to targets `param` and `field`. There is no change in position 2️⃣, the annotation is still applied only to use-site target `field`.

```kotlin
// equivalent to
data class User(val username: String, @param:Email @field:Email val email: String) {
  @field:Email val secondaryEmail: String? = null
}
```

If the `Email` annotation is used with the `all` target instead,

```kotlin
data class User(val username: String, /* 1️⃣ */ @all:Email val email: String) {
  /* 2️⃣ */ @all:Email val secondaryEmail: String? = null
}
```

Then the annotation is additionally applied as `@get:Email` in the two marked positions. In this case the `get` target comes from "translating" Java's `METHOD` target. If the property was defined as `var`, the additional `set_param` target would also be selected.

This behavior does not only apply to Java annotations. For example, [`IntRange` from `androidx.annotations`](https://developer.android.com/reference/androidx/annotation/IntRange) is defined ["natively" in Kotlin](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:annotation/annotation/src/commonMain/kotlin/androidx/annotation/IntRange.kt?q=file:androidx%2Fannotation%2FIntRange.kt%20class:androidx.annotation.IntRange).

An example in which the `property` target is involved is given by [`JSONName` from `kjson`](https://github.com/pwall567/kjson-annotations/blob/main/src/main/kotlin/io/kjson/annotation/JSONName.kt).

```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class JSONName(val name: String)
```

If we consider again the two positions in which the annotation may appear,

```kotlin
data class User(val username: String, /* 1️⃣ */ @JSONName("mail1") val email: String) {
  /* 2️⃣ */ @JSONName("mail2") val secondaryEmail: String? = null
}
```

there is a change in behavior in 1️⃣ -- with this proposal `@JSONName` is applied to both the parameter and the property --, and no change in 2️⃣ -- `property` is still chosen as the use-site target.

```kotlin
data class User(val username: String, @param:JSONName("mail1") @property:JSONName("mail1") val email: String) {
  @property:JSONName("mail2") val secondaryEmail: String? = null
}
```

The developer may select the three potential targets by using `@all:JSONName("mail1")` in the definition.

### Impact

To understand the impact of this change, we need to consider whether the annotation was defined in Java or in Kotlin. The reason is that annotations defined in Java may _not_ define `property` as one of their targets. As a consequence, the proposed defaulting rule effectively works as "apply to parameter and field". This is exactly the behavior we want, as described in the _Motivation_ section.

To understand whether the choice between `property` and `field` is required in the rule above, we have consulted open source repositories (for example, [query in GitHub Search](https://github.com/search?q=%40Target%28AnnotationTarget.PROPERTY%2C+AnnotationTarget.FIELD%29+lang%3AKotlin&type=code)). The conclusion is there is an important amount of annotations with both potential targets in the wild, which makes is dangerous to scrape the defaulting between `property` and `field` altogether.

## Other design choices

**Make `all` the default for `@JvmRecord`**: we have considered the possibility of fully aligning Kotlin's behavior with Java's in that case. Although this might be interesting for JVM-only projects, it is very unclear what the behavior should be for Multiplatform projects. Both potential options have strong drawbacks:

- If we make the behavior JVM-only, a different set of targets is chosen depending on the platform. This breaks some expectations around common code, and lacks uniformity.
- If we make the behavior apply to all platforms, it seems quite un-intuitive that an annotation (`@JvmRecord`) affects how all other annotations are applied.

Furthermore, it is still very early to know how popular frameworks will handle Java records. If at a future time Java interoperability begins to suffer, we shall revisit this choice.

# Change the defaulting rule for annotations

* **Type**: Design proposal
* **Author**: Alejandro Serrano
* **Contributors**: Alexander Udalov, Ilmir Usmanov
* **Discussion**:
* **Status**: In discussion
* **Related YouTrack issue**: [KT-19289](https://youtrack.jetbrains.com/issue/KT-19289/Hibernate-Validator-Annotations-Dropped), [KTIJ-31300](https://youtrack.jetbrains.com/issue/KTIJ-31300/Draft-Validation-Annotation-Email-Doesnt-Work-in-Kotlin-Constructor)

## Abstract

Properties defined in primary constructor positions define several use-site targets for annotations. If an annotation is applied, one of those targets is chosen using the [defaulting rule](https://kotlinlang.org/docs/annotations.html#java-annotations). This KEEP proposes to change that behavior to choose several targets instead.

## Table of contents

* [Motivation](#motivation)
  * [Potential misunderstandings](#potential-misunderstandings)
  * [Alignment with JVM](#alignment-with-jvm)
* [Technical details](#technical-details)
  * [Impact](#impact)
  * [Migration period](#migration-period)

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

We shall argue below that the defaulting rule should be changed for the case of properties defined in primary constructors. Instead, the annotation should be applied to _both_ the constructor parameter and the property or field.

### Potential misunderstandings

The main issue with the current defaulting rule is that developers are often surprised when an annotation is _not_ applied to the target they intended. Consider the following example, in which the properties are now mutable.

```kotlin
class Person(@NotBlank var name: String, @PositiveOrZero var age: Int)
```

Following the defaulting rule, the validation annotations `@NotBlank` and `@PositiveOrZero` are applied _solely_ to the constructor parameter. In practical terms, this means that their values are validated only when first creating the instance, but not on later modifications of the properties. This does not seem like the intended behavior, and may lead to broken invariants.

### Alignment with JVM

Java provides [records](https://openjdk.org/jeps/395) since version 16 (experimental since 14). Records are syntactically very close to definition of properties in primary constructors, and they also expand to several declarations in the underlying JVM platform.

```java
record Person(String name, int age) { }
```

The rules for [annotations on record components](https://openjdk.org/jeps/395#Annotations-on-record-components) are very similar for those in this proposal: apply to every declaration.

Since JVM is one of the main targets for Kotlin, we think alignment with the rest of the players is very important. One reason is to make it easier for developers to work on multi-language projects, without having to remember small quirks per language. On top of that, libraries developed with Java in mind may assume the behavior of records, and they would then fail in a very similar scenario in Kotlin.

## Technical details

The technical content of this KEEP is quite short. The defaulting rule should be changed to:

> If you don't specify a use-site target, the target is chosen according to the `@Target` annotation of the annotation being used. If there are multiple targets, choose one or more as follows:
> 
> - If the constructor parameter target `param` is applicable, use it.
> - If any of the property target `property` or field target `field` is applicable, use the first of those.
>
> It is an error if there are multiple targets and none of `param`, `property` and `field` is applicable.

### Impact

To understand the impact of this change, we need to consider whether the annotation was defined in Java or in Kotlin. The reason is that annotations defined in Java may _not_ define `property` as one of their targets. As a consequence, the new defaulting rule effectively works as "apply to all possible targets". This is exactly the behavior we want, as described in the _Motivation_ section.

To understand whether the choice between `property` and `field` is required in the rule above, we have consulted open source repositories (for example, [query in GitHub Search](https://github.com/search?q=%40Target%28AnnotationTarget.PROPERTY%2C+AnnotationTarget.FIELD%29+lang%3AKotlin&type=code&p=2)). The conclusion is there is an important amount of annotations with both potential targets in the wild, which makes is dangerous to scrape the defaulting between `property` and `field` altogether.

### Migration period

The complicated part in this case is to ensure an orderly transition between the two different worlds. For that matter, we define three different behaviors for the compiler:

1. Apply the old defaulting rule,
2. Apply the old defaulting rule, but warn when more than one target is applicable,
3. Apply the new default rule.

The Kotlin compiler currently behaves as (1). We propose to change the behavior to (2) for a period of time to be defined by the implementation, and then move to (3). During this transitional period the user may silence the warnings by explicitly choosing (1) or (3).

> [!NOTE]
> If the Kotlin compiler supports flags, the behavior may be controlled by them.
> - `-Xannotation-defaulting=old` applies the old rule,
> - `-Xannotation-defaulting=old-warn` applies the old rule and warns,
> - `-Xannotation-defaulting=new` applies the new rule.

# Artifact with meta-annotations for Java types enhancement

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Contributors**: Andrey Breslav
* **Status**: Submitted
* **Prototype**: Not started
* **Discussion**: [KEEP-99](https://github.com/Kotlin/KEEP/issues/99)
* **Related proposals**: [JSR-305 custom nullability qualifiers](https://github.com/Kotlin/KEEP/blob/master/proposals/jsr-305-custom-nullability-qualifiers.md)

## Summary

Add a separate artifact containing meta-annotations similar to ones from [JSR-305](https://jcp.org/en/jsr/detail?id=305).

## Motivation

- We need to put somewhere `@ApplyToTypeArgumentsAnnotation` meta-annotation (see the [discussion](https://github.com/Kotlin/KEEP/issues/79#issuecomment-336905480)).
- There is a [modules-related issue](https://blog.codefx.org/java/jsr-305-java-9/) with JSR-305 and Java 9.
- It's worth simplifying the way how JSR-305 nullability meta-annotations are being used
and integrating them with Kotlin-specific meta annotations. Namely, `@UnderMigration` and `@ApplyToTypeArguments`.

## Description

This section describes proposed semantics of the new annotations and partly the layout of resulting artifact.

### Root package
Root package for this artifact will be `kotlin.annotations.jvm`.
All classes/packages names are assumed to be placed in the package and only their
relative names are mentioned below.

### Built-in qualifiers
In JSR-305, there is [`@TypeQualifier`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/meta/TypeQualifier.html)
annotation that allows to introduce custom qualifiers (like `@Nonnull`).
But that kind of meta-meta level seems to be unnecessary to our needs (at least for now),
the Kotlin compiler supports only nullability qualifier
(and in the nearest future mutability might be supported as well).

So, there will only be fixed number of built-in qualifier annotations:
- `nullability.Nullable`
- `nullability.NotNull`
- `mutability.Mutable`
- `mutability.ReadOnly`

Their target set would be the following:
ElementType.METHOD, ElementType.FIELD,
ElementType.PARAMETER, ElementType.LOCAL_VARIABLE

And semantics when being applied to types is just the same as for analogue
from `org.jetbrains.annotations` (see [more](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md#more-precise-type-information-from-annotations) about types enhancement)

### Alias annotation
This proposal suggests to introduce `meta.Alias` annotation which would affect the compiler
in a similar way that `@TypeQualifierNickname` [does](https://github.com/Kotlin/KEEP/blob/master/proposals/jsr-305-custom-nullability-qualifiers.md#type-qualifier-nicknames).

In a basic version its declaration may look like
```kotlin
package kotlin.annotations.jvm.meta

@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class Alias(val qualifier: KClass<*>)
```

and its usages is supposed to look like:
```kotlin
@Alias(kotlin.annotations.jvm.nullability.Nullable::class)
annotation class MyNullable
```

Thus, applying `@MyNullable` should have the same effect as `@Nullable` itself has.
Beside it, `@MyNullable` may have `@UnderMigration` annotation on it that would change its
[migration status](https://github.com/Kotlin/KEEP/blob/master/proposals/jsr-305-custom-nullability-qualifiers.md#undermigration-annotation).

### Default qualifiers options<a name="default-qualifiers"></a>
This proposal has two options how
[default qualifiers](https://github.com/Kotlin/KEEP/blob/master/proposals/jsr-305-custom-nullability-qualifiers.md#type-qualifier-default)
semantics can be introduced.

And both of them are assume using special enum class instead of `ElementType`
that is used as a parameter type for JSR-305 `@TypeQualifierDefault`.
It might look like:
```kotlin
enum class ApplicabilityKind {
    RETURN_TYPE, VALUE_PARAMETER, FIELD, TYPE_USE, TYPE_ARGUMENT
}
```

All elements should work just the same as relevant entries from `ElementType` do for `@TypeQualifierDefault`,
beside `TYPE_ARGUMENT`.
The latter one should have the following effect: if a default qualifier is determined
to be applied to some top-level type (using the same logic as for `@TypeQualifierDefault`)
and the set of applicability kinds contain `TYPE_ARGUMENT` then this qualifier should also be applied
to all of it's type arguments (recursively).

#### Parameter in @Alias (Option 1)
The first option is adding `propagateTo: Array<DefaultApplicabilityType>` parameter to `meta.Alias` annotation.

Thus, declaration of `AllParametersAreNullableByDefault` would look like this:
```kotlin
@Alias(Nullable::class, propagateTo=[ApplicabilityKind.VALUE_PARAMETER])
annotation class AllParametersAreNullableByDefault
```

*Known pros:*
- It's nice to have one annotation for all issues.
- Probably, for someone such form of default qualifiers would look nicer than one suggested by JSR-305.

*Known cons:*
- It would look a little weird when it's used in cases like the one below:
```kotlin
@Alias(Nullable::class)
@UnderMigration(...)
annotation class MyNullable

@Alias(MyNullable::class, propagateTo=[ApplicabilityKind.PARAMETER])
annotation class MyAllParametersAreNullableByDefault
```
- Also it might be confusing when one alias (probably with non-empty `propagateTo`)
references another alias with non-trivial `propagateTo` argument

#### Separate annotation (Option 2)
Another option would be a separate annotation `meta.ApplyByDefault` with vararg-parameter
of type `DefaultApplicabilityType`:
```kotlin
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class ApplyByDefault(val qualifier: KClass<*>, vararg val elements: ApplicabilityKind)
```

Basically, it should work just the same as `@TypeQualifierDefault`, e.g. it might be used like:
```kotlin
@ApplyByDefault(MyNullable::class)
annotation class MyAllParametersAreNullableByDefault
```

### Avoidance of @UnderMigration annotation
After revisiting design for meta-annotations it looks like a natural thing to set up
a migration status for an annotation with its argument instead of another meta-annotation.

Thus, the idea is to add `migrationStatus` parameter to `meta.Alias`
(and to `meta.ApplyByDefault` if we decide to introduce the latter) with default value to be `STRICT`.

This argument should be processed by the compiler in the same way as for [`@UnderMigration`](https://github.com/Kotlin/KEEP/blob/master/proposals/jsr-305-custom-nullability-qualifiers.md#undermigration-annotation) annotation
having the same argument value.

### Details on artifact

There is already an artifact called [kotlin-annotations-jvm](https://mvnrepository.com/artifact/org.jetbrains.kotlin/kotlin-annotations-jvm)
that might be the best candidate where the new meta annotations may be placed.

Probably, the annotations that are already there should be moved to a different packages:

- `kotlin.annotations.jvm.MigrationStatus` -> `kotlin.annotations.jvm.meta`
- `kotlin.annotations.jvm.Mutable`, `kotlin.annotations.jvm.ReadOnly` -> `kotlin.annotations.jvm.collections`

## Remaining questions
- Should aliasing JSR-305 qualifiers like `javax.annotation.Nonnull` be allowed?
- What option is the best to choose for supporting [default qualifiers](#default-qualifiers)?
- Should there be a `migrationStatus` parameter in `@Alias` or migration status
should be tracked through `@UnderMigration` annotation?
- What is the best name for `ApplicabilityKind` enum class?
Would it be better to place it inside the `ApplyByDefault` annotation class (if it will be there)

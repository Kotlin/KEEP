# JSR 305 custom nullability qualifiers

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Status**: Submitted
* **Prototype**: In progress

## Summary

- Support loading more precise type information for Java declarations marked 
with custom nullability annotations based on [JSR 305](https://jcp.org/en/jsr/detail?id=305)
    - Both `@TypeQualifierNickname` and `@TypeQualifierDefault` should be 
    supported
- Introduce `@Migration` annotation and additional compiler flags to allow a 
library maintainer and its users to control the way how these new annotations
affect the compiler's behavior. Namely, they may lead to errors, warnings on 
usages from Kotlin or just be ignored

## Motivation

- It might be too verbose to annotate each signature part in a library API, so
`TypeQualifierDefault` is the solution. One can simply declare that all types
in a package are not nullable by default, and additionally annotate nullable 
parts where it's necessary (or vice versa)
- The naming of default JSR 305 annotation may be rather confusing:
`javax.annotation.Nullable` does not actually have the same meaning as nullable
types in Kotlin, it only means that nullability is unknown unlike  the 
`javax.annotation.CheckForNull`. So a library maintainer may want to introduce 
its own`Nullable` annotation as a type qualifier nickname to `CheckForNull` 
with a more precise/clear meaning. 
- Annotating already existing parts of a library API is a source incompatible
change in Kotlin, thus in some cases it's worth migrating users smoothly: 
    - Release a version of the library where there will be a warnings on 
    incorrect usages of the newly annotated parts
    - Turn these warnings into errors for the the next release

## Description

By default all reference types from Java are perceived by the Kotlin compiler
as [flexible](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md), 
it means that they can be used both as nullable and not-nullable. 

In addition, Java types declarations may be annotated with some of the known 
annotations to make them have [more precise types](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md#more-precise-type-information-from-annotations)
when used from Kotlin. Let's call this kind of annotating as types enhancement.

Current proposal is supposed to introduce additional ways of types enhancement
and instruments to control their migration status.

### Type qualifier nicknames
[`@TypeQualifierNickname`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/meta/TypeQualifierNickname.html) 
annotation from the JSR 305 among others allows to introduce new nullability 
annotations, and this proposal suggests to interpret them in Kotlin in the 
same way as other (built-in) nullability annotations.

The rules are following: an annotation class must be annotated both with 
`@javax.annotation.meta.TypeQualifierNickname` and `@javax.annotation.NonNull` 
annotation with an optional argument. The latter annotation may be replaced
with another type qualifier nickname to `NonNull`, including for example
`@CheckForNull`.

This new annotation class when being applied to a type container 
declaration (a value parameter, field or method) must enhance related type
in the same way it would be done if it was annotated as `@NonNull` with
corresponding `when` argument. Namely:
- `When.ALWAYS` makes the type not-nullable
- `When.MAYBE` makes the type nullable
- `When.NEVER/When.UNKNOWN` doesn't affect type's nullability

```java
@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyNonnull {
}

@TypeQualifierNickname
@CheckForNull // a nickname to another type qualifier nickname
@Retention(RetentionPolicy.RUNTIME)
public @interface MyNullable {
}

interface A {
    @MyNullable
    String foo(@MyNonnull String x);
}
```

`A::foo` in the example above should be available in Kotlin as `fun foo(x: 
String): String?`


### Type qualifier default
[`@TypeQualifierDefault`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/meta/TypeQualifierDefault.html)
allows to introduce annotations that when being applied define the default 
nullability within the scope of the annotated element.

To make it work new annotation class must be annotated with 
`@TypeQualifierDefault` and `@NonNull` or any type qualifier nickname to the 
latter.
The single argument for the `@TypeQualifierDefault` defines a set of element 
types for which the annotation may enhance a type. 

When determining immediate nullability of a type the Kotlin compiler should look
for nullability annotation on the type itself at first.
- If there is one, then it should use it
- Otherwise nullability is determined by the closest annotation marked as 
`TypeQualifierDefault` and having appropriate applicability in the argument:
    - `ElementType.METHOD` for return type of methods
    - `ElementType.PARAMETER` for value parameters
    - `ElementType.FIELD` for fields
    - `ElementType.TYPE_USE` for any top-level type
    
Note, that `javax.annotation.ParametersAreNonnullByDefault` annotation should 
work automatically
by applying the rules above.

Also it's important to support reading Java package annotations, because it's 
supposed to be the most common way to declare a default nullability.

```java
@Nonnull
@TypeQualifierDefault({ElementType.METHOD, ElementType.PARAMETER})
public @interface NonNullApi {
}

@Nonnull(when = When.MAYBE)
@TypeQualifierDefault({ElementType.METHOD, ElementType.PARAMETER})
public @interface NullableApi {
}
```
```java
// FILE: test/package-info.java

@NonNullApi // declaring all types in package 'test' as non-nullable by default
package test;


// FILE: test/A.java
package test;

@NullableApi // overriding default nullability from the package
interface A {
    String foo(String x); // fun foo(x: String?): String?
 
    @NotNullApi // overriding default from the class
    String bar(String x, @Nullable String y); // fun bar(x: String, y: String?): String 
}

```

### `@Migration` annotation
Current proposal suggests to add the following meta-annotation in 
the `kotlin.annotation` package:
```kotlin
enum class MigrationStatus {
    ERROR,
    WARNING,
    IGNORE
}

@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class Migration(val status: MigrationStatus)
```

Its aim is to define a migration status of an annotation it's been applied to.
To the moment it can be only used for custom nullability qualifier 
[nicknames](#Type qualifier nicknames) introduced in this proposal as well.

When the annotation is applied to a nullability nickname its argument specifies
how the compiler handles the nickname:
- `MigrationStatus.ERROR` makes annotation work just the same way as any plain
nullability annotation, i.e. reporting errors for inappropriate usages of an 
annotated type. In other words, it's equivalent to absence of the 
`@Migration` annotation.
- `MigrationStatus.WARNING` should work just the same as 
`MigrationStatus.ERROR`, but compilation warnings must be reported instead of 
errors 
for 
inappropriate usages of an annotated type 
- `MigrationStatus.IGNORE` makes compiler to ignore the annotation completely


Inappropriate usages in Kotlin that are subjects to report errors/warnings
include:
- Passing nullable arguments for a parameters those types are enhanced from
annotations to not null
- Using enhanced nullable values as an argument for kotlin not-nullable 
parameter or as a receiver for method call
- Overriding enhanced Java methods with incorrect signature in Kotlin

```java
@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@Migration(status = MigrationStatus.WARNING)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyNonnull {
}


interface A {
    void foo(@MyNonnull String x);
}

```

```kotlin
fun bar(a: A) {
    a.foo(null) // a warning will be reported on 'null' argument
}
```

In the example the warning will be reported until status argument is changed
to `ERROR`, then it should become an error.

It's important that migration annotation works only for its immediate usages
on types or through `TypeQualifierDefault` annotation.
It means that another nickname annotation aliased to the one under migration
doesn't inherit its migration status, thus by default it would be a 
`MigrationStatus.ERROR`

### Migration-related compiler flags

Compiler flags might be useful mostly for library users who for some reasons 
need a migration state different from the one offered by a library maintainer.

They may be set in a build systems configuration files or in the IDE.  

#### Global state of JSR 305 support
The flag `-Xjsr305-annotations` has three options: ignore, enable and warn.

It effectively defines the migration status behavior only for nullability 
qualifier nicknames that haven't yet been annotated with `kotlin.Migration`.

This annotation is necessary since using all of the custom nullability 
qualifiers, and especially `TypeQualifierDefault` is already spread among many
well-known libraries and users may need to migrate smoothly when updating to
the Kotlin compiler version containing JSR 305 support.

### Global migration status
The flag `-Xjsr305-annotation-migration` has the same set of options and has 
the similar meaning, but overrides the behavior defined by the `status` argument
for all of the `@Migration` annotated qualifiers.

It might be needed in case when library users have different view on a 
migration status for the library: both they may want to have errors while the
official migration status is `WARNING`, and vice versa, they may wish to 
postpone errors reporting for some time because they're not completed their 
migration yet.

### Overriding migration status for specific annotation
Sometimes it might be necessary to manage a migration phase for a specific 
library. This could be done with the flag `-Xjsr305-user-annotation`. It 
accepts fully-qualified name of a nullability qualifier annotation and its 
desired status in a format `<fq.name>:ignore/warn/error`.

This flag can be repeated several times in a configuration to set up a 
different behavior for different annotations.

*Current limitation:* In current prototype this flag only overrides the 
behavior for the annotations that are annotated with `@Migration`, but it 
seems that this requirement may be weakened.

Note, that this flag overrides behavior for both `Global migration status` 
and for the `status` argument of `@Migration` annotation.
    

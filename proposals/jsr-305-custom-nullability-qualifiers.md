# JSR-305 custom nullability qualifiers

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Contributors**: Andrey Breslav, Leonid Stashevsky
* **Status**: Submitted
* **Prototype**: Implemented in 1.2
* **Discussion**: [KEEP-79](https://github.com/Kotlin/KEEP/issues/79)

## Summary

- Support loading more precise type information for Java declarations marked 
with custom nullability annotations based on [JSR-305](https://jcp.org/en/jsr/detail?id=305)
    - Both `@TypeQualifierNickname` and `@TypeQualifierDefault` should be 
    supported
- Introduce `@UnderMigration` annotation and additional compiler flags to allow a 
library maintainer and its users to control the way how these new annotations
affect the compiler's behavior. Namely, they may lead to errors, warnings on 
usages from Kotlin or just be ignored

## Motivation

- It might be too verbose to annotate each signature part in a library API, so
`TypeQualifierDefault` is the solution. One can simply declare that all types
in a package are not nullable by default, and additionally annotate nullable 
parts where it's necessary (or vice versa)
- The naming of default JSR-305 annotation may be rather confusing:
`javax.annotation.Nullable` does not actually have the same meaning as nullable
types in Kotlin, it only means that nullability is unknown unlike the
`javax.annotation.CheckForNull`. So a library maintainer may want to introduce 
its own `Nullable` annotation as a type qualifier nickname to `CheckForNull`
with a more precise/clear meaning. 
- Annotating already existing parts of a library API is a source incompatible
change in Kotlin, thus in some cases, it's worth migrating users smoothly:
    - Release a version of the library where there will be warnings on
    incorrect usages of the newly annotated parts
    - Turn these warnings into errors for the next release

## Description

By default all reference types from Java are perceived by the Kotlin compiler
as [flexible](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md), 
it means that they can be used both as nullable and not-nullable. 

In addition, Java types declarations may be annotated with some of the known 
annotations to make them have [more precise types](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md#more-precise-type-information-from-annotations)
when used from Kotlin. Let's call this kind of annotating as types enhancement.

Current proposal is supposed to introduce additional ways of types enhancement
and instruments to control their migration status.

### Type qualifier nicknames<a name="type-qualifier-nickname"></a>
[`@TypeQualifierNickname`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/meta/TypeQualifierNickname.html) 
annotation from the JSR-305 allows to define nicknames to a 
[type qualifier](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/meta/TypeQualifier.html)
that may be considered as a kind of extensions for type system of Java 
programming language.

Among others, JSR-305 defines nullability type qualifier [`Nonnull`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/Nonnull.html) 
that allows to specify nullability of Java types.
And while `Nonnull` itself is already supported by Kotlin compiler, this proposal 
suggests to interpret qualifier nicknames to it in Kotlin in the same way as other 
(built-in) nullability annotations.

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
- `When.MAYBE/When.NEVER`, make the type nullable
- `When.UNKNOWN` forces the type to remain flexible

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


### Type qualifier default<a name="type-qualifier-default"></a>
[`@TypeQualifierDefault`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/meta/TypeQualifierDefault.html)
allows introducing annotations that when being applied define the default
nullability within the scope of the annotated element.

To make it work new annotation class must be annotated with 
`@TypeQualifierDefault` and `@NonNull` or any type qualifier nickname to the 
latter.
The single argument for the `@TypeQualifierDefault` defines a set of element 
types for which the annotation may enhance a type. 

When determining immediate nullability of a type the Kotlin compiler should look
for nullability annotation on the type itself at first.
- If there is one, then it should use it
- Otherwise, nullability is determined by the innermost enclosing element 
annotated with `TypeQualifierDefault` and having appropriate applicability 
in the argument:
    - `ElementType.METHOD` for return type of methods
    - `ElementType.PARAMETER` for value parameters
    - `ElementType.FIELD` for fields
    - `ElementType.TYPE_USE` for any type including type arguments, upper bounds 
    of type parameters and wildcard types
    
Note, that [`javax.annotation.ParametersAreNonnullByDefault`](https://aalmiray.github.io/jsr-305/apidocs/javax/annotation/ParametersAreNonnullByDefault.html) 
annotation should work automatically by applying the rules above:
```java
@Nonnull
@TypeQualifierDefault(value=PARAMETER)
@Retention(value=RUNTIME)
public @interface ParametersAreNonnullByDefault {}
```

Also, it's important to support reading Java package annotations because it's
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
```

```java
// FILE: test/A.java
package test;

@NullableApi // overriding default nullability from the package
interface A {
    String foo(String x); // fun foo(x: String?): String?
 
    @NotNullApi // overriding default from the class
    String bar(String x, @Nullable String y); // fun bar(x: String, y: String?): String 
    
    // The type of `x` parameter remains flexible because there's explicit UNKNOWN-marked
    // nullability annotation
    String baz(@Nonnull(when = When.UNKNOWN) String x); // fun baz(x: String!): String?
}

```

### `@UnderMigration` annotation
Current proposal suggests to add the following meta-annotation in 
the `kotlin.annotations.jvm` package in a separate library named `kotlin-annotations-jvm`:
```kotlin
package kotlin.annotations.jvm

enum class MigrationStatus {
    STRICT,
    WARN,
    IGNORE
}

@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class UnderMigration(val status: MigrationStatus)
```

Its aim is to define a migration status of an annotation it's been applied to.
`@UnderMigration` can be used both for [nicknames](#type-qualifier-nickname) and
[default qualifiers](#type-qualifier-default)

When it is applied to a nullability annotation its argument specifies
how the compiler handles the annotation:
- `MigrationStatus.STRICT` makes annotation work just the same way as any plain
nullability annotation, i.e. reporting errors for inappropriate usages of an 
annotated type. In other words, it's almost equivalent to the absence of the 
`@UnderMigration` annotation. The only difference is how the annotation is handled
by the compiler when [migration status flag](#migration-status) is set.

- `MigrationStatus.WARN` should work just the same as 
`MigrationStatus.STRICT`, but compilation warnings must be reported instead of 
errors 
for 
inappropriate usages of an annotated type 
- `MigrationStatus.IGNORE` makes compiler to ignore the nullability annotation completely


Inappropriate usages in Kotlin that are subjects to report errors/warnings
include:
- Passing nullable arguments for parameters those types are enhanced from
annotations to not null
- Using enhanced nullable values as an argument for kotlin not-nullable 
parameter or as a receiver for a method call
- Overriding enhanced Java methods with incorrect signature in Kotlin

```java
@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@UnderMigration(status = MigrationStatus.WARN)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyNonnull {
}

@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@UnderMigration(status = MigrationStatus.IGNORE)
@Retention(RetentionPolicy.RUNTIME)
public @interface IgnoredNonnull {
}


interface A {
    void foo(@MyNonnull String x);
    void bar(@IgnoredNonnull String x);
}

```

```kotlin
fun bar(a: A) {
    // a warning will be reported on 'null' argument
    a.foo(null) 
    // no warning or error will be reported because `IgnoredNonnull` has IGNORE migration status
    a.bar(null) 
}
```

In the example the warning will be reported until status argument is changed
to `STRICT`, then it should become an error.

It's important that migration annotation works only for its immediate usages
on types or through `TypeQualifierDefault` annotation.
It means that another nickname annotation aliased to the one under migration
doesn't inherit its migration status, thus by default, it would be a
`MigrationStatus.STRICT`

Examples:
```java
@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@UnderMigration(status = MigrationStatus.WARN)
public @interface MyNonnull {
}

@MyNonnull
@TypeQualifierDefault({ElementType.METHOD, ElementType.PARAMETER})
public @interface NonNullApi {
}

// Everything in the class is non-null, but only warnings would be reported
// because `MyNonnull` annotation is annotated as @UnderMigration(status = MigrationStatus.WARN)
@NonNullApi 
public class Test {} 
```

```java
@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@UnderMigration(status = MigrationStatus.WARN)
public @interface MyNonnull {
}

@TypeQualifierNickname
@MyNonnull
public @interface MyNonnullNickname {
}

public class A {
    public void foo(@MyNonnullNickname String x) {}
}
```

```kotlin
fun bar(a: A) {
    // an error must be reported, because `MyNonnullNickname` is not annotated as `@UnderMigration` even though
    // the nicknamed `MyNonnull` is `@UnderMigration(status = MigrationStatus.WARN)`
    a.foo(null) 
}
```

If both nickname and default qualifier have non-trivial migration status the one
from default qualifier must be chosen:
```java
@TypeQualifierNickname
@Nonnull(when = When.ALWAYS)
@UnderMigration(status = MigrationStatus.ERROR)
public @interface MyNonnull {
}

@MyNonnull
@TypeQualifierDefault({ElementType.METHOD, ElementType.PARAMETER})
@UnderMigration(status = MigrationStatus.WARN)
public @interface NonNullApi {
}

// Everything in the class is non-null, but only warnings would be reported
// because `NonNullApi` annotation is annotated as @UnderMigration(status = MigrationStatus.WARN),
// even though `MyNonnull` has ERROR migration status
@NonNullApi 
public class Test {} 
```

### Compiler configuration for JSR-305 support
Custom compiler configuration for JSR-305 support might be useful mostly 
for library users who for some reasons need a migration state different 
from the one offered by a library maintainer.

The state of JSR-305 is defined by the single compiler flag called `-Xjsr305`.
It may be set in a build systems configuration files or in the IDE.  

Basically, it has three kinds of arguments that allow to control the behavior
for different sets of annotations.

#### Global state of JSR-305 support
To define the state for all annotations that haven't been annotated with 
`kotlin.annotations.jvm.UnderMigration` the flag must be used in format: `-Xjsr305={ignore|warn|strict}`

Each of the options has the same meaning as the fields of `MigrationStatus`:
- `ignore` effectively disables `UnderMigration`-unaware annotations
- `warn` leads to warnings being reported on the nullability-unsafe usages in Kotlin
- `strict` makes nullability-unsafe usages in Kotlin to be errors

Changing the global state of JSR-305 might be needed since custom nullability
qualifiers, and especially `TypeQualifierDefault` is already spread among many
well-known libraries and users may need to migrate smoothly when updating to
the Kotlin compiler version containing JSR-305 support.

*Notes*
- For kotlin compiler versions 1.1.50+/1.2 the default behavior is the same as 
the flag is set to `-Xjsr305=warn`. 
- The `strict` option must be considered as experimental in a sense that there are no
guarantees that code compiled with this option enabled in 1.2 will still be correct 
in the next Kotlin version.
It's very likely that there will be more strict checks in 1.3
- There are plans to turn the default behavior into `strict` in 1.3

#### Global migration status<a name="migration-status"></a>
The flag when used with argument in the format `-Xjsr305=under-migration:{ignore|warn|strict}` 
overrides the behavior defined by the `status` argument
for all of the `@UnderMigration` annotated qualifiers.

It might be needed in case when library users have different view on a 
migration status for the library: both they may want to have errors while the
official migration status is `WARN`, and vice versa, they may wish to 
postpone errors reporting for some time because they're not completed their 
migration yet.

This kind of argument can be used together to the one described in the previous
section. 
For example, to ignore all JSR-305 annotations (both `UnderMigration` aware and ones that aren't)
the compiler configuration should contain among other flags
`-Xjsr305=ignore -Xjsr305=under-migration:ignore`

#### Overriding migration status for specific annotation
Sometimes it might be necessary to manage a migration phase for a particular
library. 
That could be done with the flag argument in the format
`-Xjsr305=@<fq.name>:{ignore|warn|strict}`. 
`<fq.name>` here is a fully-qualified name of a JSR-305 annotation

For example, if a library defines its own `MyNullable` annotation in a package 
`org.library` to disable it one can add `-Xjsr305=@org.library.MyNullable:ignore`
to the set of compiler flags.

Again, this kind of argument can be repeated several times in a configuration 
to set up a different behavior for different annotations and can be used together
with the previous ones.

Example:
- `-Xjsr305=ignore -Xjsr305=under-migration:ignore -Xjsr305=@org.library.MyNullable:warn`
makes compiler ignore all the annotations but the `org.library.MyNullable` and report
warnings on unsafe usages related to the latter

Note, that this flag overrides behavior for both `Global migration status` 
and for the `status` argument of `@UnderMigration` annotation.

# Dependency on JSR-305 annotations in the classpath
Because annotation classes from the JSR-305 are not actually needed at runtime
and because of its unknown release state, many library maintainers may not want
to ship `jsr305.jar` as a dependency to their artifacts.

But once a library was successfully compiled with annotations in the 
classpath, there's no need for the dependency as all necessary information 
(annotation fully-qualified names, arguments, etc.) is already contained in 
the resulting classfiles.

Thus, it's worth explicitly declaring that the Kotlin compiler should be able
to load information from nullability annotations (the built-in ones and 
custom nullability qualifiers) without `jsr305.jar` as a dependency.

# Open questions/issues
- Should module-level default nullability qualifiers (in `module-info.java`) be
supported in the same way as package-level?

# Conflicts between default qualifiers and overridden

```java
// FILE: test/package-info.java

@NonNullApi // declaring all types in package 'test' as non-nullable by default
package test;


// FILE: test/A.java
package test;

interface A<T> {
    @Nullable
    T foo(); // fun foo(): T?
}

interface B extends A<String> {
    String foo(); // fun foo(): String
}
```

The problem here is that the return type of `B::foo` is enhanced to not-nullable
because of package-level `NonNullApi` annotation, although its overridden 
member in `A` has `Nullable` annotation.

Probably it's worth reconsidering the rule of applying default nullability 
qualifiers and use them only in the case of absence of both explicit annotations
and nullability info from the overridden descriptors.

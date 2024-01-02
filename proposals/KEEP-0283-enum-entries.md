# Decommission `Enum.values()` and replace it with `Enum.entries`

* **Type**: Design proposal
* **Author**: Vsevolod Tolstopytov
* **Status**: Accepted
* **Prototype**: Implemented
* **Discussion**: [KEEP-283](https://github.com/Kotlin/KEEP/issues/283)
* **Related issues**: [KT-48872](https://youtrack.jetbrains.com/issue/KT-48872)

This proposal describes a rationale and a path to migrate from function `values()` provided by each enum type to a collection-based and more predictable abstraction.

## Motivation

Every Java and Kotlin enum type provides a [`values`](https://kotlinlang.org/spec/declarations.html#enum-class-declaration) method
that returns an array of all enum entries. 

Arrays are mutable by default, meaning that each call to `values()` always has to allocate a new instance of the array.
The API shape does not indicate that fact, often [leading](#examples-of-performance-issues) to hidden performance issues in both Kotlin and Java.
It is hard for library authors to determine whether an arbitrary call to `values()` may lead to a performance bottleneck as
the profile depends on the "hotness" of the method, not on the enum's characteristics, effectively forcing authors to apply
a workaround or leave it as a potential performance issue.

Apart from that, most of the API being written leverages Collections API, not arrays, forcing users to manually convert arrays to lists.

### Acknowledgements of the problem

* It is considered a ["design bug"](http://mail.openjdk.java.net/pipermail/compiler-dev/2018-July/012242.html) in Java.
* [JDK-8073381 need API to get enums values without creating a new arra...](https://bugs.openjdk.java.net/browse/JDK-8073381).
* Scala 3 [attempted](https://github.com/lampepfl/dotty/issues/6620) to address this issue.
* Graal [specifically optimizes](https://github.com/oracle/graal/issues/574) errorneous pattern `values()[idx]`.
* [Memory-Hogging Enum.values() Method](https://dzone.com/articles/memory-hogging-enumvalues-method)

### Examples of performance issues

 * [HttpStatus.resolve allocates HttpStatus.values() once per invocation](https://github.com/spring-projects/spring-framework/issues/26842)
 * [Kotlin standard library](https://github.com/JetBrains/kotlin/blob/92d200e093c693b3c06e53a39e0b0973b84c7ec5/libraries/stdlib/jvm/src/kotlin/text/CharCategoryJVM.kt#L170)
 * [kotlinx.serialization Enum deserializer]( https://github.com/Kotlin/kotlinx.serialization/issues/1372)
 * [MySQL JDBC  Remove Enum.values() calls to avoid unnecessary array](https://github.com/Microsoft/mssql-jdbc/pull/1065)

## Proposal

The proposal that addresses all of the above is as follows:

* Decommission of `Enum.values()` with the IDE assistance without deprecation.
* Introduce property `entries: EnumEntries<E>` that returns an unmodifiable list of all enum entries.
* For already compiled Kotlin enums and Java enums, a special mapping class that contains a pre-allocated list of entries is generated. 

### Decommission of `Enum.values()`

Enums have been existing since the very beginning of Kotlin, and since Java 1.5, released in 2004, meaning that the deprecation
of `values` using Kotlin's standard [deprecation cycle](https://kotlinlang.org/docs/kotlin-evolution.html#incompatible-changes) 
will create unnecessary disturbance in the ecosystem and will render a lot of already existing educational materials outdated.

To avoid that, `values()` will be softly decommissioned with the help of IDE assistance:

* `values` will be de-prioritized and eventually removed from IDE auto-completion.
* Soft warning with an intention to replace call to `values()` with call to `entries` will be introduced.
* All the corresponding materials, such as Kotlin guides, J2K and tutorials will be adjusted to use `entries` API.
* Eventually, we are going to re-visit this decision and decide on the further deprecation of `values` API.

### `EnumEntries<E>` type

Effectively, `entries` represents an immutable list of all enum entries and can be represented as type `List<E>`.
We consider `List<E>` insufficient as the long-term solution:

- `List<E>` does not represent well the intended meaning of `entries` being a list of **all** existing enum entries. 
  - It means that the semantics of the value is lost as soon as it leaves the callsite.
  - It doesn't allow programmers to introduce their own extensions on list of all enum entries (e.g. `valueOfOrNullIgnoreCase`) as they do not have the right tool to express it in the signature.
- It prevents Kotlin from further extension of Enum's API without introducing new code-generated members to `enum` type.

To address that, we expose a direct subtype of `List<E>` named `EnumEntries`:

```
sealed interface EnumEntries<E : Enum<E>> : List<E>
```

We deliberately limit any implementations of this type to the standard library to have a future-proof way to 
extend it in a backwards-compatible manner.
All further potential extensions, such as `valueOfOrNull(String)`, `hasValue(String)` can be implemented on the standard library
level as members or extensions over `EnumEntries`.


### Naming alternatives

Various languages use different notations to refer to enumeration elements, making the naming choice especially hard. 

We have considered the following alternatives:

* `values` property. While being the most familiar one, it carries the burdens of the existing `values()` API:
  * It is easy to misspell it with `values()` and fall into the same trap.
  * Introduction of `values` property adds an unfixable method reference ambiguity for all the callers of `E::values`.
* Java language [refers](https://docs.oracle.com/javase/specs/jls/se7/html/jls-8.html#jls-8.9.1) to enum elements as `constants`. 
  * The risks of introducing widely-used API named `constants` with the potential clashes with [Kotlin constant evaluation](https://youtrack.jetbrains.com/issue/KT-14652) outweighs the benefits of the straightforward name.
* `valuesList` and `entryList` suffer the same disease -- excessive verbosity of the name.
* `components` is already reserved in Kotlin for positional deconstructors.
* Kotlin specification and documentation already refer to enum elements as entries, making it our final choice.

### Translation strategy for Kotlin enum

For source-compiled enums, a new static property with a JVM signature `public static EnumEntries<E> getEntries()` is added,
returning a pre-allocated immutable list of enum entries.

`EnumEntries` is pre-allocated during static initialization of enum class and does not interfere with `values()` and `$VALUE`
code generation.

#### Implementation note

A special type, `EnumEntriesList` is introduced to the standard library. The type has the only constructor
accepting an array of all enum entries and the corresponding call is generated in enum's class initialization section.

The final decompiled class for the enum:
```kotlin
enum class MyEnum {
    A;
}
```

has the following form (all irrelevant parts omitted):
```java
enum MyEnum extends Enum<MyEnum> {
    private static final synthetic MyEnum[] $VALUES
    private static final synthetic EnumEntries<MyEnum> $ENTRIES;
   
    <clinit> {
        A = new MyEnum("A", 0);
        $VALUES = $values();
        $ENTRIES = EnumEntriesKt.enumEntries($VALUES); // internal factory from standard library
    }

    public static MyEnum[] values() {
        return $VALUES.clone();
    }
  
    public static EnumEntries<MyEnum> getEntries() {
        return $ENTRIES;
    }

    private synthetic static MyEnum[] $values() {
        return new MyEnum[] { A };
    }
}
```

### Translation strategy for compiled Kotlin enums and Java enums

For already compiled Kotlin enums and Java enums, a separate synthetic mapping classfile will be introduced 
for each callsite of `Enum.entries`. The mapping class acts as a storage for a lazily-initialized and pre-allocated list of
enum entries leveraging the same mechanism as already existing mappings for `when` over enum classes.

## Risks and assumptions

The proposal has two main risks:


### Change of resolve in case of ambiguous declarations

The first is the potential source-compatibility issue for `entries` property in the companion object within an Enum.
After the implementation of the proposed change as is, the following existing code will change its behaviour:

```kotlin
enum class MyEnum {
    A;
    
    companion object {
        val entries: Any? = ...
    }
}

MyEnum.entries // <- member has a higher priority than a companion member
```


To mitigate this and similar (e.g. top-level property named `entries`) ambiguities, a separate compiler-assistant deprecation cycle is introduced, that will keep ambiguous declaration's
priority higher than auto-generated `entries` member's priority for the duration of the deprecation cycle.

Eventually, we expect all such ambiguous callsites to be resolved to the proposed `entries` member, while all previously-accessible declarations will still be available using their fully-qualified name. 
The only exception from this rule is already compiled Kotlin enum classes and Java enum classes that have an enum entry with the name `entries`, in order
to preserve the backwads compatibility and ability to programmatically refer to `entries` entry. In that scenario, in order to refer to `entries` member, 
a top-level function `enumEntries<EnumType>()` should be used instead.

To address potential risks of the changed resolve at compile-time, we deliberately prolong the deprecation cycle, 
as well as artificial de-prioritization of `entries` member for multiple additional major releases.

### Developers familiarity with `values`

The second risk is the education disturbance and a new name for developers to get familiar with — `entries`, opposed to already well-known `values`.
It is mitigated by indefinitely long [soft decommission](#decommission-of-enumvalues) instead of a regular deprecation cycle, as well as by our learning materials, official recommendations and overall adoption over official tools and libraries.

## Collateral changes

In addition to an existing [`enumValues`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/enum-values.html) function in the standard library,
`enumEntries` function that returns `entries` list is added.
`enumValues` is deprecated for the removal.

## Timeline

The feature is going to be available as experimental starting from Kotlin 1.8.20 and as stable starting from Kotlin 1.9.0.
The deprecation cycle for ambiguous declarations will be prolonged until Kotlin 2.1.
The corresponding language feature can be enabled with the `-XXLanguage:+EnumEntries` compiler argument in Kotlin 1.8.0.

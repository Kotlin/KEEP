# JVM records support

* **Type**: Design proposal
* **Author**: Ilya Gorbunov
* **Status**: Under consideration
* **Discussion**: [KEEP-233](https://github.com/Kotlin/KEEP/issues/233)
* **Related issues**: [KT-44121](https://youtrack.jetbrains.com/issue/KT-44121)

## Introduction

JDK 16 introduces a special type of classes called [records](https://openjdk.java.net/jeps/395). Records allow declaring
nominal tuples in a concise way in Java. While records remove a vast amount of boilerplate on a declaration site of such
tuples (similar to data classes in Kotlin), for consumers, records are not that different from plain Java classes
with a manually implemented constructor, component accessors, `equals`/`hashCode`/`toString` methods. 
That means that even if Kotlin did nothing to support JVM records, they would be nevertheless usable from Kotlin 
due to the normal Kotlin-Java interoperability.

However, it still makes sense to support JVM records in a special way in Kotlin. In essence, records are similar to 
Kotlin data classes, so their components should be visible as Kotlin properties.

The support of Java records in Kotlin can be broken down into two aspects: 
 - the first is how records declared in Java should be seen in Kotlin, 
 - the second is how to declare a record in Kotlin.

## Java records seen in Kotlin

In Java records, component accessor functions have the same names as the corresponding components. Without special support
Kotlin would see them only as functions.

### Synthetic properties for component accessors

Kotlin is able to recognize [getter and setter methods](https://kotlinlang.org/docs/reference/java-interop.html#getters-and-setters) 
in Java classes and provide synthetic properties for them if they follow the convention:
- `getSomething()` method is seen as a synthetic property `something`
- `isSomething()` method returning `boolean` is seen as a synthetic property `isSomething`

So in order to see record component accessors as properties, Kotlin adds another convention specifically for Java records
in addition to the two above:
- `something()` method is seen as a synthetic property `something`

#### Property accessor naming convention conflicts

When a record has both methods `something()` and `getSomething()`, where the former is an automatically generated or
manually declared accessor and the latter is a manually declared method, we have a conflict of two synthetic property
conventions. While we believe that in practice both methods will most likely have the same implementation, we still have
to decide what to do in this situation.

- If `getSomething` is an override of a Kotlin interface with the property `something`, the record should have the
_member_ property `something` inherited from the interface with the accessor method `getSomething()`.
- If `getSomething` is an override of a Java interface method, or a just a method declared in the record, 
we prefer the synthetic property derived from the `getSomething` accessor. 
Note that this is different from the current situation when a plain Java class has both `getIsSomething()` and `isSomething()` 
accessors.

## Authoring records in Kotlin

There's not much use in declaring JVM records in Kotlin besides two use cases:
- migrating an existing Java record to Kotlin and preserving its ABI;
- generating a record class attribute with record component info for a Kotlin class to be read later 
  by a potential framework relying on Java reflection to introspect records.

In order to author a record class in Kotlin, we provide a new annotation: `@JvmRecord`, which can be placed on a class
to compile it as a record. This JVM-specific annotation enables generating:
- the record components corresponding to the class properties in the class file,
- the property accessor methods named according to the Java record naming convention,
- `equals`/`hashCode`/`toString` implementations when they are not provided explicitly or by the class being a data class.

Note that applying `JvmRecord` to an existing class is not a binary compatible change: it changes
the naming convention of the class property accessors.


### Conditions for a class to meet to be eligible for `@JvmRecord`

- The class shall be in a module that targets JVM 16 bytecode (or 15 if `-Xjvm-enable-preview` compiler option is enabled).
- The class cannot inherit any other class explicitly (including `Any`) because all records implicitly inherit `java.lang.Record`.
- There must be a clear relation between the primary constructor parameters and the class properties with backing fields. 
  Currently, we have such clear relation in data classes and in those plain Kotlin classes where all 
  primary constructor parameters declare `val` properties.
  Note that the prototype implementation restricts the annotation applicability further only on _data_ classes.
- The class cannot declare any additional state, i.e. properties with backing fields, 
  except those initialized from the corresponding primary constructor parameters.
- The class cannot declare any mutable state, i.e. mutable properties with backing fields.
- The class cannot be local.
- The class primary constructor must be as visible as the class itself.

### Property accessor method names

By default, in a Kotlin class annotated with `@JvmRecord`, property accessor names should follow the Java record 
component accessor method naming convention, i.e. they should have the same name as the corresponding properties.

#### Overriding Kotlin interface properties

If a Kotlin class annotated with `@JvmRecord` implements a Kotlin interface overriding the interface properties 
with the corresponding component properties, the class should generate additional accessors for these properties 
bridging the accessor methods from the interface.

#### JvmName on property accessors

<!-- Not supported initially:
In Kotlin, it is possible to change the generated names of property accessor methods by annotating these properties with 
the `@JvmName` annotation. In case if `@JvmName` is applied on a property of a Kotlin class annotated with `@JvmRecord`,
it doesn't rename the property accessor method, but generates an additional method with the specified name, 
which invokes the property accessor.
-->

In Kotlin, `@JvmName` annotation applied on a property accessor allows changing its name visible for Java. 
Thus, applying it on record property accessors is prohibited because record component accessor methods should 
follow the strict naming convention.

### toString implementation of a JvmRecord class

While the exact format of automatically generated `toString` implementation in a Java record is not specified, 
it produces a result very similar to that in Kotlin data classes with the only distinction in the parentheses used
to surround class properties/record components.

It may be valuable to preserve `toString` format when migrating Java record to Kotlin, thus when `@JvmRecord` is applied
on a _plain_ Kotlin class without an explicit `toString` implementation, it gets the Java record `toString` format.
However, if `@JvmRecord` is placed on a data class, the format of a data class `toString` is used.

### `@JvmRecord` restrictions in multiplatform projects

`@JvmRecord` is a JVM-specific annotation, though it is available in the common standard library as a so-called
optional expectation annotation. This means that this annotation can have no actual implementation in some platforms,
namely, in all platforms except JVM in this case.

However, since this annotation affects the generation of `equals`/`hashCode`/`toString` methods if they are not provided 
explicitly, just ignoring it in the other platforms would lead to a different equality/toString behavior compared to 
that in JVM. To avoid this, `@JvmRecord` brings an additional restriction on a class in non-JVM platforms: 
- the class must provide implementations of `equals`/`hashCode`/`toString` either explicitly, 
  or have them implicitly generated if the class is a _data_ class.

## Prototype

The prototype implementation of the JVM record support is provided in Kotlin 1.4.30.

At the start, the `@JvmRecord` annotation will be applicable only to _data_ classes.

## Java-to-Kotlin conversion

Since migrating existing Java records to Kotlin is one of the use cases of authoring record classes in Kotlin,
it's also important that the Kotlin IDE plugin provided a smart Java-to-Kotlin conversion for Java record classes.
For now, considering the limitations of the prototype, J2K should transform a Java record into a Kotlin data class
annotated with `@JvmRecord` when it is possible for the converted class code to meet the limitations of a 
`@JvmRecord`-annotated class. In case when it is not possible, J2K should produce a plain Kotlin class (without `@JvmRecord`)
with a comment warning about the lost "recordness" of the converted class.

## Future improvements

### Allowing `@JvmRecord` on non-data Kotlin classes

The prototype allows `JvmRecord` annotation placed only on data classes. We could relax this restriction provided that 
there's still a clear relation between the class's properties and primary constructor parameters. A class could satisfy
this restriction if either:
- it has the primary constructor where all parameters declare `val` properties;
- or it has the primary constructor where all parameters have the same names and types and are following in the same order
  as the class properties with backing fields.

### Canonical constructor parameter names

Usually, Kotlin does not trust parameter names in Java methods because they are not a part of the method contract 
and can be missing in the compiled bytecode of the method. So Kotlin prohibits calling Java methods with named parameters.
In record declarations, however, component names and order are significant. 
Therefore, Kotlin could treat record canonical constructor parameter names as significant too and allow invoking
canonical constructors with named parameters. Note that in this case, parameter names of such a constructor 
are disregarded even if present in the bytecode and are always derived from the corresponding component names.

# Kotlin serialization compiler plugin

* **Type**: Design proposal
* **Authors**: Leonid Startsev, Roman Elizarov, Vsevolod Tolstopyatov
* **Status**: Implemented in 1.4 as a compiler plugin and standalone library.
* **Discussion**: [KEEP-149](https://github.com/Kotlin/KEEP/issues/149)

## Feedback

The original proposal and its discussion are available in this [forum thread](https://discuss.kotlinlang.org/t/kotlin-serialization/2063).
A lot of feedback is gathered in the repository [issues](https://github.com/Kotlin/kotlinx.serialization/issues?q=is:issue+label:design+).

## Synopsis

Kotlin 1.2 and later offers support for multiplatform projects and, in general, Kotlin provides a richer type system than an underlying platform with nullable types and default property values, which creates a need for a multiplatform Kotlin-specific serialization library.

This proposal describes how a compiler plugin is used for this task, and offers a convention between the compiler and core module of the runtime library [kotlinx.serialziation](https://github.com/Kotlin/kotlinx.serialization/). It consists of three chapters:

* [Overview](#overview) chapter briefly describes the `kotlinx.serialization` framework, and introduces its core concepts and interfaces.
* [Code generation](#code-generation) chapter covers code generation by the plugin in detail.
* [Appendix](#appendix) provides additional details and shows approaches for the most common use-cases.

The serialization plugin is designed to work alongside the core runtime library, which has out-of-the box support for
serialization of various complex types from the Kotlin standard library and supports various serialization formats
like JSON, Protobuf, etc.
The detailed description of the core library is out of the scope of this document.
For more detailed information on it, read the [library's guide](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serialization-guide.md).

## Overview

This chapter gives an overview of Kotlin serialization.

### Problems this proposal solves

* Platform serialization mechanisms do not always work well with Kotlin: [KT-7939](https://youtrack.jetbrains.com/issue/KT-7939), [KT-23998](https://youtrack.jetbrains.com/issue/KT-23998)
* Reflection API is not available in Kotlin/JS and Kotlin/Native, so it is impossible to use a runtime-based solution.
* Kapt is not supported by Kotlin/JS and Kotlin/Native, so it is impossible to use annotation processing.
* Standalone code generators do not work well with incremental compilation and it is non-trivial to include them as a build phase.
* Implementing serialization without some kind of code-generation requires a lot of boilerplate code.

### Goals of the serialization framework

* Provide the single abstraction over various serialization formats.
* Eliminate usage of reflection for all Kotlin target platforms.
* Work with speeds comparable to or better than traditional reflection-based solutions.
* Provide a mechanism for saving both a class and its schema. A schema could be automatically generated from a class' Kotlin source code.
* Leverage ability to traverse class data for other use-cases: hashers, ORM, lenses, etc.

### Core API overview and mental model

At a glance, `kotlinx.serialization` provides an abstraction over different serialization formats by exposing the API which can encode primitive types one-by-one (in a serial fashion). A compiler generates code that uses this API, since it knows everything about fields of a class during compile-time. This plugin is similar to [parcelize plugin](android-parcelable.md), but for bigger API which is not tied to any particular serialization format.

To support all the use-cases, a clear distinction is made between the **serialization** process and the **encoding** process.

* **Serialization** is a process of transforming one single entity to a stream of its elements. Each element is either a primitive or a complex entity; the latter is transformed recursively. In the end, a serializer emits a stream of primitive elements.

* **Encoding** is processing of elements in a stream produced by serialization. Contrary to the traditional definition of serialization, we don't say that an encoder must write elements to a storage. It could do so (e.g. JSON encoder saves elements to a string and encapsulates all the knowledge about char encoding, delimiters, etc...), but it also could process the stream in-memory to transform or aggregate the elements.

> Inverse processes are called **deserialization** and **decoding**, respectively.

> The source code of the API given here is not full and contains only declarations which need to be stable and discoverable by the code generator. Other parts of the library and additional interfaces' methods are not connected with the plugin and therefore are not included here.

The core API is located in `kotlinx.serialization` package.

### Serializer interfaces

The following interfaces are provided in the library to be implemented by the serializer of a particular class:

```kotlin
interface SerializationStrategy<in T> {
    val descriptor: SerialDescriptor
    fun serialize(encoder: Encoder, value: T)
}

interface DeserializationStrategy<out T> {
    val descriptor: SerialDescriptor
    fun deserialize(decoder: Decoder): T
}

interface KSerializer<T>: SerializationStrategy<T>, DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor
}
```

`KSerializer<T>` is an entity that knows how to decompose the class `T` and to feed its primitives to some encoding mechanism, and to do the reverse process.
Its implementation is usually generated by the compiler plugin, but it can be written manually in some special cases.
Note, that, generally, serializer should not know anything about the output or storage format.

> In the documentation for this framework, we are using the words "serializer" and "serialization" widely. Please pay special attention that, in most cases, these terms refer to a capable entity or a process of both serialization (saving, decomposing) and deserialization (loading, composing) – just because there is no good word for "both serialization and deserialization at the same time".

### Descriptor of a serializable entity

Because reflection can't be used in this library, there is a need for a data structure sufficient to hold simple metadata about the serializable class &mdash; to be able to retrieve the information needed for saving both the class and its schema, e.g. class name, properties names, types, and so on.
The interface for this data structure is provided by the library and called `SerialDescriptor`.
It _describes_ **serializable entity**, or just **entity**, which has **elements** that are identified
by an integer index (starting from zero).

```kotlin
interface SerialDescriptor {
    val serialName: String
    val kind: SerialKind
    val elementsCount: Int
    val isNullable: Boolean
    val annotations: List<Annotation>

    fun getElementName(index: Int): String
    fun getElementIndex(name: String): Int
    fun getElementAnnotations(index: Int): List<Annotation>
    fun getElementDescriptor(index: Int): SerialDescriptor
    fun isElementOptional(index: Int): Boolean
}
```

The corresponding documentation and the usage of the interface are out of the scope of this document; the interface itself is presented here to introduce the general concept of serializable entity metadata.
This interface is also implemented by the compiler plugin for a given class, because `KSerializer<T>` has a reference to `SerialDescriptor`.

#### Serial kinds

Note that we especially don't limit ourselves to "class and properties" terminology, because it is not only classes that can be described with this data structure. `SerialKind` can give a clue of which kinds of entities can be serialized:

```kotlin
sealed class SerialKind {
    object ENUM: SerialKind()
    object CONTEXTUAL: SerialKind()
}

sealed class PrimitiveKind: SerialKind() {
    object INT : PrimitiveKind()
    object STRING: PrimitiveKind()
    // other primitives cut out for shortness
}

sealed class StructureKind: SerialKind() {
    object CLASS: StructureKind()
    object LIST: StructureKind()
    object MAP: StructureKind()
    object OBJECT: StructureKind()
}

sealed class PolymorphicKind: SerialKind() {
    object SEALED: PolymorphicKind()
    object OPEN: PolymorphicKind()
}

```

User-defined classes are considered to be **structures**. A value of the structure is defined by listing its **elements**.
Lists and maps are considered to be special cases of the structures since number of their elements is known only at runtime.
Kotlin's singleton `object`s are treated as structures with zero elements because one usually interested in singleton identity, not its content.

Kotlin serialization supports two types of polymorphism: `SEALED`, where all inheritors are known at compile time and which is naturally expressed by `sealed` classes;
and `OPEN` polymorphism, that requires runtime registration of all known subtypes and is discussed later in this document.

There are two kinds left that do not fall in any group: `ENUM`, that represents 'one of many' choice and therefore does not qualify as a structure; and `CONTEXTUAL`, that represents a special mechanism to determine serializer at runtime (discussed later in this document).

Detailed invariants of `SerialDescriptor` for each particular kind are described in their respective documentation. Entities which do not have a primitive kind are collectively called **composite**.

### Encoder interfaces

An encoder encapsulates the knowledge about the output format and storage. This interface is provided by a serialization format in the runtime and should be used by a serializer. For performance and type-safety reasons, the `Encoder` interface contains one method for each primitive type:

```kotlin
interface Encoder {
    fun encodeNotNullMark()
    fun encodeNull()

    fun encodeBoolean(value: Boolean)
    fun encodeByte(value: Byte)
    fun encodeShort(value: Short)
    fun encodeInt(value: Int)
    fun encodeLong(value: Long)
    fun encodeFloat(value: Float)
    fun encodeDouble(value: Double)
    fun encodeChar(value: Char)
    fun encodeString(value: String)

    fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int)

    // Encoder is allowed to override this default implementation
    fun <T : Any?> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) =
        strategy.serialize(this, value)

    // more methods to follow ...
}
```

However, this interface is not able to write composite data like classes or collections, because there is no way to express delimiters and metadata about the entity itself.
`CompositeEncoder` serves this purpose. It has a similar interface to `Encoder` with methods that accept a descriptor and the current position in a structure.
Note that it does not extend `Encoder` because once you've started to write the structure, you're not allowed to write primitive values without indices.

```kotlin
interface Encoder {
    // in addition to previous methods
    fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder
}

interface CompositeEncoder {
    // delimiter
    fun endStructure(descriptor: SerialDescriptor)

    // Invoked if an element equals its default value, making it possible to omit it in the output stream
    fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean

    fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean)
    fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte)
    fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short)
    fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int)
    fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long)
    fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float)
    fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double)
    fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char)
    fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String)

    fun <T> encodeSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T)
}
```

> Note, that this interface lacks functions similar to `encodeNotNullMark` and `encodeNull` by design.
In general, generated class serializers support only non-nullable values of the corresponding type,
which is default in the idiomatic Kotlin code. Nullable types are handled by wrapping the
original serializer and are encoded via invocation of `encodeSerializableElement` or
`encodeNullableSerializableElement` without wrapping as an optimization.

### Decoder interfaces

A decoder, like an encoder, encapsulates the knowledge about a format, and
provides primitive values when a deserializer asks for them.
The `Decoder` interface is the mirror of `Encoder`:

```kotlin
interface Decoder {
    // returns true if the following value is not null, false if null
    fun decodeNotNullMark(): Boolean
    // consumes null, returns null, is be called when decodeNotNullMark() is false
    fun decodeNull(): Nothing?

    fun decodeBoolean(): Boolean
    fun decodeByte(): Byte
    fun decodeShort(): Short
    fun decodeInt(): Int
    fun decodeLong(): Long
    fun decodeFloat(): Float
    fun decodeDouble(): Double
    fun decodeChar(): Char
    fun decodeString(): String
    fun decodeEnum(enumDescriptor: SerialDescriptor): Int

    // Decoder is allowed to override this default implementation
    fun <T : Any?> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        strategy.deserialize(this)

    // more methods to follow ...
}
```

However, an opposite interface for `CompositeEncoder` would not be very useful.
Primitives in the input stream can go in an arbitrary order, so in addition to methods opposite to `encodeXxxElement`, there is a `decodeElementIndex` method, which is called to determine the current position in the structure.
The index returned from this method should be used as an argument to `decodeXxxElement` to insert value in the correct position in the structure.

It is also possible for format to support optimized decoding, where elements in structure are decoded in the
initial order without calls to `decodeElementIndex`. In that case, `decodeSequentially` should return `true`.
Note, that this is an optional optimization and this method may never be called.

```kotlin
interface Decoder {
    // in addition to previous methods
    fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder
}

interface CompositeDecoder {
    fun endStructure(desc: SerialDescriptor)

    // decodeElementIndex results
    companion object {
        // end of input
        const val DECODE_DONE = -1
        // format encountered an element that does not present in the structure's descriptor
        const val UNKNOWN_NAME: Int = -3
    }

    // returns either index or one of the companion object constants
    fun decodeElementIndex(desc: SerialDescriptor): Int

    // checks whether the current decoder supports strictly ordered decoding of the data without calling to decodeElementIndex.
    fun decodeSequentially(): Boolean = false

    fun decodeUnitElement(desc: SerialDescriptor, index: Int)
    fun decodeBooleanElement(desc: SerialDescriptor, index: Int): Boolean
    fun decodeByteElement(desc: SerialDescriptor, index: Int): Byte
    fun decodeShortElement(desc: SerialDescriptor, index: Int): Short
    fun decodeIntElement(desc: SerialDescriptor, index: Int): Int
    fun decodeLongElement(desc: SerialDescriptor, index: Int): Long
    fun decodeFloatElement(desc: SerialDescriptor, index: Int): Float
    fun decodeDoubleElement(desc: SerialDescriptor, index: Int): Double
    fun decodeCharElement(desc: SerialDescriptor, index: Int): Char
    fun decodeStringElement(desc: SerialDescriptor, index: Int): String

    fun <T : Any?> decodeSerializableElement(desc: SerialDescriptor, index: Int, strategy: DeserializationStrategy<T>, previousValue: T?): T
}
```

## Code generation

This chapter gives an overview of the code generated by serialization plugin.

### Requirements

First, let's narrow down the scope for now and say that only a class or object can be serializable, not an interface or annotation.
(Interfaces can be serialized polymorphically; see 'Polymorphism' section.)

If the compiler plugin has complete control over the class, then it can automatically implement `KSerializer<T>` for class `T` if its every primary constructor parameter is `val` or `var` (parameters that are not properties are impossible to save for restoring later).
In this case (we call it **internal** serialization), the plugin injects a special synthetic constructor into the class to be able to correctly initialize its private and/or body properties.
Delegated properties are not supported (they can be safely excluded from the process).

If the plugin was asked to generate `KSerializer<T>` without modifying `T` (**external** serialization), then the class `T` must have an accessible primary constructor.
The following properties would be impossible to initialize correctly after deserialization and therefore they are skipped:

- `private`/`protected` body vals and vars (`internal` too if `T` is located in another module).
- `public` body vals.

### Layout of synthetic classes and methods for structures

In the case of internal serialization, which is expressed by a `@Serializable` annotation on class `T`, the compiler plugin generates:

1. A synthetic constructor for `T`, which initializes all the state properties including private and body ones.
2. A nested class with a special name `$serializer` that implements `KSerializer<T>`. If `T` has no type arguments, then this class is a singleton. If it does, e.g. `T = T<T0, T1...>`, then its constructor has the properties `KSerializer<T0>, KSerializer<T1>, ...`.
3. The implementation property `$serializer.descriptor` that holds the metadata about the class.
4. The method `T.Companion.serializer()` that returns `$serializer`. If `T` has type arguments (`T = T<T0, T1...>`), then this method has arguments `KSerializer<T0>, KSerializer<T1>, ...`. If a companion is not declared in the class, its default body is also generated.
5. The implementation method `$serializer.serialize(Encoder, T)` that feeds T into `Encoder` by making calls to `beginStructure`, `encodeXxxElement` several times, and `endStructure`.
6. The implementation method `$serializer.deserialize(Decoder, T?): T` that collects variables from `Decoder` by making calls to `beginStructure`, `decodeElementIndex`, `decodeXxxElement` with the correct index until end of input, and `endStructure`. Then it validates that either all primitives were read and constructs T from the collected primitives.

In the case of external serialization, expressed by `@ExternalSerializer(forClass=...)`, only the last three items are done. In `deserialize`, the plugin calls a primary constructor of the class and then all the available property setters.

A user should be able to provide a custom implementation for `serialize` and `deserialize` just by manually writing functions with the proper signatures. In this case, the compiler plugin skips the corresponding steps.

### Special serializers

There are several special cases that differ in their `serialize` in `deserialize` methods:

* Enum serializers. Although enums classes are natively supported and do not require to be `@Serializable`,
a generated serializer for them is required in case they have some special metadata (for example, custom `@SerialName`).
In that case, `serialize`/`deserialize` methods of such a serializer contain a single call to `encodeEnum`/`decodeEnum`.

* Singleton (`object`) serializers. They preserve the identity of a singleton and do not encode/decode any data.
Therefore, their `serialize`/`deserialize` methods call only `beginStructure` and `endStructure` without intermediate `encodeXxx`/`decodeXxx` calls.

* Polymorphic serializers and collection serializers delegate to the corresponding serializers from core library.

### Resolving and lookup

When generating the implementation methods, the compiler plugin needs to choose a concrete method to call from the group of `(en|de)codeXxxElement`. If the current property has a primitive non-nullable type, such as `Int` or `String`, then it directly calls a method matched by the signature. If it has nullable and/or complex `E`, the compiler plugin uses the following rules to find a serializer for `E` and then call `(en|de)codeSerializableElement`:

1. If a current property is annotated with `@Serializable(with=T::class)`, construct and use an instance of `T` as a serializer for `E`.
1. If file has an annotation `@file:UseSerializers(..., T::class, ...)` and `T` is a `KSerializer<E>`, construct and use an instance of `T`.
2. If `E` is a type parameter, use the corresponding serializer passed in the constructor.
3. If `E` is a primitive type (boxed for some reason), use the corresponding pre-defined serializer from the core library.
4. If `E = V?` – nullable type, find a serializer for `V` and adapt it with `NullableSerializer` from the core library.
5. If `E` is one of the supported types from the standard library, then find a serializer for its generic arguments and construct the corresponding serializer from the core library.
6. If `E` is a user type annotated with `@Serializable`, construct and use its `$serializer`.
7. If `E` is a user type annotated with `@Serializable(with=T::class)`, construct and use an instance of `T` as a serializer.
8. If none of the previous rules apply, report a compile-time error about non-serializable field in the class.


### Serializers for the standard library types

Currently, this package `kotlinx.serialization.internal` contains serializers for the following types:
`Array`, `String`, `(Mutable)List`, `ArrayList`, `(Mutable)Set`, `LinkedHashSet`, `(Mutable)Map`, `LinkedHashMap`, `Map.Entry`, `Pair`, `Triple`, all primitive types, and arrays of primitive types.
This package is a part of the `kotlinx-serialization-core` library.
They can be acquired by user by using their corresponding constructor-like functions from `kotlinx.serialization.builtins` package.

### Tuning generated code

To be more flexible and support the various use-cases, the plugin respects the following annotations from `kotlinx.serialization` package:

* `@SerialName`, which alters the name of a property or class that has been written to metadata in the descriptor. It is also applicable to enum cases.
* `@Transient`, which makes the property invisible to the plugin.
* `@SerialInfo` meta-annotation, which allows definition of custom annotations that are record them in the descriptor.

## Appendix

This chapter provides additional details and explains various concrete use-cases.

### Saving schemas with a descriptor

Serial descriptors provide access to all the elements' descriptors via `getElementDescriptor`, allowing to view them as a tree-like structure for schema traversing and saving. The descriptor provides content-based `equals` and `hashCode` implementations, allowing to lazily compute and cache any schema information that is derived from the descriptor.

For example, ProtoBuf encoder implementation can compute layout and additional type information of the structure
that is being serialized once for each `SerialDescriptor` it encounters and cache it for future use.

### Optionality

For the simplicity and convenience, properties that have default values are considered to be optional.
Handling optional properties during deserialization is easy – the compiler plugin just generates a bit mask for validation, similar to how default values in functions are compiled.
However, one of the most popular feature requests for the library is omitting default values during serialization. For this purpose, the method `shouldEncodeElementDefault` is introduced in `CompositeEncoder`.
For every optional property, the compiler plugin emits the following construction:

```kotlin
// Assume we are recording "i" from the following class definition:
// @Serializable class Data(val s: String, val i: Int = 42)
if (value.i != 42 || encoder.shouldEncodeElementDefault(this.descriptor, 1))
    encoder.encodeIntElement(this.descriptor, 1, value.i)
```

With this code, the decision whether to encode a default value is passed to the encoder and therefore to its storage format. Some formats may have this setting as a boolean option (e.g. JSON), some formats always need a value (e.g. hasher), while some of them rely on additional information in the descriptor to make this decision.

### Contextual serialization

By default, all serializers are resolved by the plugin statically when compiling the serializable class. This provides type-safety, performance, and avoid reflection usage.
However, in certain cases, it is desirable to delay resolution of serializers until runtime.
One possible case is when you want to define two different serializers for different formats, e.g. serializing `Date` differently in JSON and in XML.
To support such cases, the concepts of `SerializersModule` and `ContextualSerializer` are introduced.
Roughly speaking, module is a map where the runtime part of the framework is looking for serializers that were not resolved at compile time by the plugin.

To enable this mapping, serializable property is annotated with a `@Contextual` annotation. Annotation can also be applied at the file level in the form `@UseContextualSerialization(vararg classes: KClass)`. `ContextualSerializer` captures the `KClass` of the property at compile-time and looks up serializer for the property at run-time.

The current `SerializersModule` is available as `val serializersModule` in the encoders and decoders. It contains the function `.getContextual(forClass: KClass)` which is used by `ContextualSerializer`.
However, it does not contain the functions to register serializers.
For that purpose, you should use `SerializersModule {}` DSL builder function.
See the core library documentation for more details.

### Polymorphism

Usually, polymorphism usage in serialization is discouraged because of the security problems it entails. However, writing complex business logic is almost impossible without this main OOP feature.
In this serialization framework, we get rid of the 'deserialize-anything' security problem that plagues naive approaches to polymorphism in serialization. All serializable implementations of some abstract class must be registered in advance. This also avoids reflection usage (such as `Class.forName`), which makes naive polymorphic serialization hard to implement on JS and Native.

Note that the most simple and straightforward approach to polymorphism that does not require additional setup, is to mark your classes as `sealed` instead of `abstract`.
However, sometimes you want more permissive approach that allows registering subclasses in runtime, not compile-time. For example, such approach allows adding additional subclasses that were defined in a separate module, dependent on the base module with the base class.

Polymorphic serialization is enabled automatically only for interfaces. To enable this feature, use `@Polymorphic` annotation
on the property, abstract or open class.

Another feature is that we only allow registering subclasses in the scope of a base class. The motivation for this is easily understandable from the example:

```kotlin
abstract class BaseRequest()
@Serializable data class RequestA(val id: Int): BaseRequest()
@Serializable data class RequestB(val s: String): BaseRequest()

abstract class BaseResponse()
@Serializable data class ResponseC(val payload: Long): BaseResponse()
@Serializable data class ResponseD(val payload: ByteArray): BaseResponse()

@Serializable data class Message(
    @Polymorphic val request: BaseRequest,
    @Polymorphic val response: BaseResponse
)
```

In this example, both `request` and `response` in `Message` are serializable with `PolymorphicSerializer` because of the annotation on them. (They are not required to be serializable by themselves.)
Yet `PolymorphicSerializer` for `request` should only allow `RequestA` and `RequestB` serializers, and none of the response's serializers.

For details about registering and using pre-defined _serializersModules_, please refer to the library's documentation.

## Future work

* Serializable inline classes.
* Serializable coroutines and continuations.

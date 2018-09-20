# Kotlin serialization compiler plugin

* **Type**: Design proposal
* **Authors**: Leonid Startsev, Roman Elizarov, Vsevolod Tolstopyatov
* **Status**: Draft, Submitted
* **Prototype**: Partially implemented, shipped separately

## Feedback

The original proposal and its discussion are available in this [forum thread](https://discuss.kotlinlang.org/t/kotlin-serialization/2063).
A lot of feedback is gathered in the repository [issues](https://github.com/Kotlin/kotlinx.serialization/issues?q=is:issue+label:design+).

## Synopsis

Kotlin 1.2 and later offers support for multiplatform projects and, in general, Kotlin provides a richer type system than an underlying platform with nullable types and default property values, which creates a need for a multiplatform Kotlin-specific serialization library.

This proposal describes how a compiler plugin is used for this task, and offers a convention between the compiler and runtime library [kotlinx.serialziation](https://github.com/Kotlin/kotlinx.serialization/). It consists of three chapters:

* [Overview](#overview) chapter briefly describes the `kotlinx.serialization` framework, and introduces its core concepts and interfaces. 
* [Code generation](#code-generation) chapter covers code generation by the plugin in detail. 
* [Appendix](#appendix) provides additional details and shows approaches for the most common use-cases.

The serialization plugin is designed to work alongside the runtime library, which has out-of-the box support for 
serialization of various complex types from the Kotlin standard library and supports various serialization formats
like JSON, Protobuf, etc. The detailed description of the runtime library is out of the scope of this document.  

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
    fun serialize(output: Encoder, obj : T)
    val descriptor: SerialDescriptor
}

interface DeserializationStrategy<out T> {
    fun deserialize(input: Decoder, oldValue: T?): T
    val descriptor: SerialDescriptor
}

interface Serializer<T>: SerializationStrategy<T>, DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor
}
```

`Serializer<T>` is an entity that knows how to decompose the class `T` and to feed its primitives to some encoding mechanism, and to do the reverse process. Its implementation is usually generated by the compiler plugin, but it can be written manually in some special cases. Note, that, generally, serializer should not know anything about the output or storage format.

> In the documentation for this framework, we are using the words "serializer" and "serialization" widely. Please pay special attention that, in most cases, these terms refer to a capable entity or a process of both serialization (saving, decomposing) and deserialization (loading, composing) – just because there is no good word for "both serialization and deserialization at the same time".

### Descriptor of a serializable entity

Because reflection can't be used in this library, there is a need for a data structure sufficient to hold simple metadata about the serializable class &mdash; to be able to retrieve the information needed for saving both the class and its schema, e.g. class name, properties names, types, and so on. The interface for this data structure is provided by the library and called `SerialDescriptor`. It _describes_ **serializable entity**, or just **entity**, which has **elements** that are identified
by an integer index (starting from zero). 

```kotlin
interface SerialDescriptor {
    val name: String
    val kind: SerialKind

    fun getElementName(index: Int): String
    fun getElementIndex(name: String): Int

    fun getEntityAnnotations(): List<Annotation>
    fun getElementAnnotations(index: Int): List<Annotation>

    val elementsCount: Int

    fun getElementDescriptor(index: Int): SerialDescriptor

    val isNullable: Boolean
    fun isElementOptional(index: Int): Boolean
}
```

The corresponding documentation and the usage of the interface are out of the scope of this document; the interface itself is presented here to introduce the general concept of serializable entity metadata. This interface is also implemented by the compiler plugin for a given class, because `Serializer<T>` has a reference to `SerialDescriptor`.

#### Serial kinds

Note that we especially don't limit ourselves to "class and properties" terminology, because it is not only classes that can be described with this data structure. `SerialKind` can give a clue of which kinds of entities can be serialized:

```kotlin
sealed class SerialKind

sealed class PrimitiveKind: SerialKind() {
    object INT : PrimitiveKind()
    object STRING: PrimitiveKind()
    // other primitives cut out for shortness
}

sealed class StructureKind: SerialKind() {
    object CLASS: StructureKind()
    object LIST: StructureKind()
    object MAP: StructureKind()
}

sealed class UnionKind: SerialKind() {
    object OBJECT: UnionKind()
    object ENUM: UnionKind()
    object SEALED: UnionKind()
    object POLYMORPHIC: UnionKind()
}
```

User-defined classes are considered to be **structures**. A value of the structure is defined by listing its **elements**. List and maps are considered to be special cases of the structures (the number of their element is only know at runtime) and their representation is out of scope of this document, since their corresponding serializers are not generated by plugin, but are provided by the runtime library.  

Union stands for a **tagged union**, also known as **sum type**. Its elements are its **cases**, and the current element is the currently tagged case.
Although one union kind is called `SEALED`, this kind is applicable not only to Kotlin's `sealed` classes; any bounded polymorphism (where all inheritors are known in advance, at compile-time) can be expressed via a special annotation on the union base class.
Open polymorphism, expressed as `POLYMORPHIC`, requires runtime registration of all known subtypes and is discussed later in this document.
The difference between `ENUM` and `OBJECT` is motivated by the fact that even if enum consists of one case (and therefore is practically an object), it can get more cases as the program evolves.

Detailed invariants of `SerialDescriptor` for each particular kind are described in their respective documentation. Entities which do not have a primitive kind (structures and unions) are collectively called **composite**.

### Encoder interfaces

An encoder encapsulates the knowledge about the output format and storage. This interface is provided by a serialization format in the runtime and should be used by a serializer. For performance and type-safety reasons, the `Encoder` interface contains one method for each primitive type:

```kotlin
interface Encoder {
    fun encodeNotNullMark()
    fun encodeNull()

    fun encodeUnit()
    fun encodeBoolean(value: Boolean)
    fun encodeByte(value: Byte)
    fun encodeShort(value: Short)
    fun encodeInt(value: Int)
    fun encodeLong(value: Long)
    fun encodeFloat(value: Float)
    fun encodeDouble(value: Double)
    fun encodeChar(value: Char)
    fun encodeString(value: String)

    // Encoder is allowed to override this default implementation 
    fun <T : Any?> encodeSerializable(strategy: SerializationStrategy<T>, value: T) =
        strategy.serialize(this, value)
    
    // more methods to follow ...
}
```

However, this interface is not able to write composite data like classes, collections, or unions, because there is no way to express delimiters and metadata about the entity itself. 
`CompositeEncoder` serves this purpose. It has a similar interface to `Encoder` with methods that accept a descriptor and the current position in a structure or case in a union. Note that it does not extend `Encoder` because once you've started to write the structure, you're not allowed to write primitive values without indices.

```kotlin
interface Encoder {
    // in addition to previous methods
    fun beginEncodeComposite(desc: SerialDescriptor): CompositeEncoder
}

interface CompositeEncoder {
    // delimiter
    fun endEncodeComposite(desc: SerialDescriptor)

    // Invoked if an element equals its default value, making it possible to omit it in the output stream
    fun shouldEncodeElementDefault(desc: SerialDescriptor, index: Int): Boolean
    
    fun encodeUnitElement(desc: SerialDescriptor, index: Int)
    fun encodeBooleanElement(desc: SerialDescriptor, index: Int, value: Boolean)
    fun encodeByteElement(desc: SerialDescriptor, index: Int, value: Byte)
    fun encodeShortElement(desc: SerialDescriptor, index: Int, value: Short)
    fun encodeIntElement(desc: SerialDescriptor, index: Int, value: Int)
    fun encodeLongElement(desc: SerialDescriptor, index: Int, value: Long)
    fun encodeFloatElement(desc: SerialDescriptor, index: Int, value: Float)
    fun encodeDoubleElement(desc: SerialDescriptor, index: Int, value: Double)
    fun encodeCharElement(desc: SerialDescriptor, index: Int, value: Char)
    fun encodeStringElement(desc: SerialDescriptor, index: Int, value: String)

    fun <T> encodeSerializableElement(desc: SerialDescriptor, index: Int, strategy: SerializationStrategy<T>, value: T)
}
```

> Note, that this interface lacks functions similar to `encodeNotNullMark` and `encodeNull` by design.
In general, generated class serializers support only non-nullable values of the corresponding type, 
which is default in the idiomatic Kotlin code. Nullable types are handled by wrapping the 
original serializer and are encoded via invocation of `encodeSerializableElement`. 

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

    fun decodeUnit()
    fun decodeBoolean(): Boolean
    fun decodeByte(): Byte
    fun decodeShort(): Short
    fun decodeInt(): Int
    fun decodeLong(): Long
    fun decodeFloat(): Float
    fun decodeDouble(): Double
    fun decodeChar(): Char
    fun decodeString(): String

    // Decoder is allowed to override this default implementation
    // todo: Add oldValue to deserialize 
    fun <T : Any?> decodeSerializable(strategy: DeserializationStrategy<T>): T = 
        strategy.deserialize(this)
    
    // more methods to follow ...
}
```

However, an opposite interface for `ElementEncoder` would not be very useful. Primitives in the input stream can go in an arbitrary order, so in addition to methods opposite to `encodeXxxElement`, there is a `decodeElementIndex` method, which is called to determine the current position in the structure.

```kotlin
interface Decoder {
    // in addition to previous methods
    fun beginDecodeComposite(desc: SerialDescriptor): CompositeDecoder
}

interface CompositeDecoder {
    fun endDecodeComposite(desc: SerialDescriptor)

    // decodeElementIndex results
    companion object {
        // end of input
        const val READ_DONE = -1
        // the decoder is sure that elements go in order, do not call decodeElementIndex anymore
        const val READ_ALL = -2
    }

    // returns either index or one of READ_XXX constants
    fun decodeElementIndex(desc: SerialDescriptor): Int

    /**
     * Optional method to specify collection size to pre-allocate memory,
     * called in the beginning of collection reading.
     * If the decoder specifies in-order reading ([READ_ALL] is returned from [decodeElementIndex]), then
     * the correct implementation of this method is mandatory.
     *
     * @return Collection size or -1 if not available.
     */
    fun decodeCollectionSize(desc: SerialDescriptor): Int = -1

    /**
     * This method is called when [decodeElementIndex] returns the index which was 
     * already encountered during deserialization of this class. 
     *
     * @throws [UpdateNotSupportedException] if this implementation 
     *                                       doesn't allow duplication of fields.
     */
    fun decodeElementAgain(desc: SerialDescriptor, index: Int): Unit

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

    // [wasRead] is analogous to [decodeElementAgain] passed here, so fast decoders
    // won't have to save it in a state variable
    fun <T : Any?> decodeSerializableElement(desc: SerialDescriptor, index: Int, strategy: DeserializationStrategy<T>, oldValue: T?, wasRead: Boolean): T
}
```

## Code generation

This chapter gives an overview of the code generated by serialization plugin.

### Requirements

First, let's narrow down the scope for now and say that only a class or object can be serializable, not an interface or annotation.

If the compiler plugin has complete control over the class, then it can automatically implement `Serializer<T>` for class `T` if its every primary constructor parameter is `val` or `var` (parameters that are not properties are impossible to save for restoring later). In this case (we call it **internal** serialization), the plugin injects a special synthetic constructor into the class to be able to correctly initialize its private and/or body properties. Delegated properties are not supported (they can be safely excluded from the process).

If the plugin was asked to generate `Serializer<T>` without modifying `T` (**external** serialization), then the class `T` must have an accessible primary constructor. The following properties would be impossible to initialize correctly after deserialization and therefore they are skipped:

- `private`/`protected` body vals and vars (`internal` too if `T` is located in another module).
- `public` body vals.

### Layout of synthetic classes and methods for structures

In the case of internal serialization, which is expressed by a `@Serializable` annotation on class `T`, the compiler plugin generates:

1. A synthetic constructor for `T`, which initializes all the state properties including private and body ones.
2. A nested class with a special name `$serializer`, which implements `Serializer<T>`. If `T` has no type arguments, then this class is singleton. If it does, e.g. `T = T<T0, T1...>`, then its constructor has the properties `Serializer<T0>, Serializer<T1>, ...`.
3. The implementation property `$serializer.descriptor`, which holds the metadata about the class.
4. The method `T.Companion.serializer()`, which returns `$serializer`. If `T` has type arguments (`T = T<T0, T1...>`), then this method has arguments `Serializer<T0>, Serializer<T1>, ...`. If a companion is not declared in the class, its default body is also generated.
5. The implementation method `$serializer.serialize(Encoder, T)`, which feeds T into `Encoder` by making calls to `beginStructure`, `encodeXxxElement` several times, and `endStructure`.
6. The implementation method `$serializer.deserialize(Decoder, T?): T`, which collects variables from `Decoder` by making calls to `beginStructure`, `decodeElementIndex`, `decodeXxxElement` with the correct index until end of input, and `endStructure`. Then it validates that either all primitives were read or the oldValue was provided earlier to get them from old value and constructs T from the collected primitives.

In the case of external serialization, expressed by `@ExternalSerializer(forClass=...)`, only the last three items are done. In `deserialize`, the plugin calls a primary constructor of the class and then all the available property setters.

A user should be able to provide a custom implementation for `serialize` and `deserialize` just by manually writing functions with the proper signatures. In this case, the compiler plugin skips the corresponding steps.

### Layout for unions

For unions, the plugin does the same thing, except the bodies of `serialize` and `deserialize` methods: only one `encodeSerializableElement` method is called. The index argument represents a particular instance that is being serialized, and the serializer for that instance is passed.
_Sealed classes_ serialization from this point of view is straightforward.
_Objects_ are represented as unions which always have one an instance with index zero. 

_Enums_ are represented as sealed classes with a `Unit` body  — `encodeUnitElement` is called instead of encodeSerializable. By default, the common singleton serializer is used for them; however, in cases when additional metadata has to be recorded (e.g. a special serial name for one of the enum's instances), the plugin generates the corresponding serializer.

_Polymorphic_ serialization resolves the types at runtime; therefore, the indices and serializers for it as a union are built using a registry. See below for more information.

### Resolving and lookup

When generating the implementation methods, the compiler plugin needs to choose a concrete method to call from the group of `(en|de)codeXxxElement`. If the current property has a primitive non-nullable type, such as `Int` or `String`, then it directly calls a method matched by the signature. If it has nullable and/or complex `E`, the compiler plugin uses the following rules to find a serializer for `E` and then call `(en|de)codeSerializableElement`:

1. If a current property is annotated with `@Serializable(with=T::class)`, construct and use an instance of `T` as a serializer for `E`.
2. If `E` is a type parameter, use the corresponding serializer passed in the constructor.
3. If `E` is a primitive type (boxed for some reason), use the corresponding pre-defined serializer from the runtime library.
4. If `E = V?` – nullable type, find a serializer for `V` and adapt it with `NullableSerializer` from the runtime library.
5. If `E` is one of the supported types from the standard library, then find a serializer for its generic arguments and construct the corresponding serializer from the runtime library.
6. If `E` is a user type annotated with `@Serializable`, construct and use its `$serializer`.
7. If `E` is a user type annotated with `@Serializable(with=T::class)`, construct and use an instance of `T` as a serializer.
8. If none of the previous rules apply, report a compile-time error about non-serializable field in the class.

### Auto-discovering serializers for the standard library types

Serializers located in the `kotlinx.serialization.builtins` package are automatically
discovered by the plugin when there is no ambiguity, i.e. for a given `T` there is only one `Serializer<T>`. Generic arguments of `T` are not taken into account.
Currently, this package contains serializers for the following types: `Array`, `(Mutable)List`, `ArrayList`, `(Mutable)Set`, `LinkedHashSet`, `(Mutable)Map`, `LinkedHashMap`, `Pair`, `Triple`, and all primitive types.
This package is a part of `kotlinx.serialization` runtime library.

### Auto-discovering user-defined external serializers

To give the compiler plugin a hint about an external serializer, the annotation `@Serializable(with: KClass<*>)` can be applied on a property. However, it can be boilerplate-ish to annotate every property if you have a large number of domain classes which have to use, for example, an external serializer for `java.util.Date`.
For this purpose, the annotation `@Serializers(vararg s: Serializer<*>)` is introduced. It can be applied to a class or to a file, and adds the specified serializers to the scope of the compiler plugin.

### Tuning generated code

To be more flexible and support the various use-cases, the plugin respects the following annotations from `kotlinx.serialization` package:

* `@SerialName`, which alters the name of a property or class that has been written to metadata in the descriptor. It is also applicable to enum cases.
* `@Optional`, which allows a property to be absent in the decoder input. Requires a default value on the property. By default, all properties are required (even with default values).
* `@Transient`, which makes the property invisible to the plugin.
* `@SerialInfo` meta-annotation, which allows definition of custom annotations that are record them in the descriptor.

## Appendix

This chapter provides additional details and explains various concrete use-cases.

### Saving schemas with a descriptor

Serial descriptors provide access to all the elements' descriptors via `getElementDescriptor`, allowing to view them as a tree-like structure for schema traversing and saving. The descriptor provides content-based `equals` and `hashCode` implementations, alolwing to lazily compute and cache any schema information that is derived from the descriptor.

For example, ProtoBuf encoder implementation can compute layout and additional type information of the structure
that is being serialized once for each `SerialDescriptor` it encounters and cache it for future use. 

### Optionality

Handling optional properties during deserialization is easy – the compiler plugin just generates a bit mask for validation, similar to how default values in functions are compiled. However, one of the most popular feature requests for the library is omitting default values during serialization. For this purpose, the method `shouldEncodeElementDefault` is introduced in `CompositeEncoder`. For every optional property, the compiler plugin emits the following construction:

```kotlin
// Assume we are recording "i" from the following class definition:
// @Serializable class Data(val s: String, @Optional val i: Int = 42)
if (obj.i != 42 || output.shouldEncodeElementDefault(this.descriptor, 1))
    output.encodeIntElement(this.descriptor, 1, obj.i)
```

With this code, the decision whether to encode a default value is passed to the encoder and therefore to its storage format. Some formats may have this setting as a boolean option (e.g. JSON), some formats always need a value (e.g. hasher), while some of them rely on additional information in the descriptor to make this decision.

### Reading values twice — updating, overwriting or banning

To simplify detection of element duplicates in the input stream, the method `CompositeDecoder.decodeElementAgain` is provided. This method serves purely for indicative purposes and can't alter other calls to the decoder or skip them.
To abort the process, the decoder implementation can throw an exception. If the implementation supports overwrite/merge on duplicate properties, it is ok to do nothing.
If this method returns normally, the deserialization process continues as usual (including call to `decodeXxx` with the given index). 
Primitives can't be merged, and therefore they are overwritten by consequent calls to `decodeXxx`. Complex values are decoded via `decodeSerializableElement(desc: SerialDescriptor, index: Int, strategy: DeserializationStrategy<T>, oldValue: T?, wasRead: Boolean)` and it is up to implementation to analyze the `wasRead` flag. The implementation is free to decide whether to use `oldValue` to merge it with the current stream by passing it to the corresponding `.deserialize()` call or to ignore it.

### Contextual serialization

By default, all serializers are resolved by the plugin statically when compiling the serializable class. This provides type-safety, performance, and avoid reflection usage. However, in certain cases, it is desirable to delay resolution of serializers until runtime. One possible case is when you want to define two different serializers for different formats, e.g. serializing `Date` differently in JSON and in XML. To support such cases, the concepts of `SerialContext` and `ContextSerializer` are introduced. Roughly speaking, context it a map where the runtime part of the framework is looking for serializers that were not resolved at compile time by the plugin.

To enable this mapping, serializable property is annotated with `@SerializableWith(ContextSerializer::class)` or `@ContextualSerialization`. The latter annotation can also be applied at the file level in the form `@ContextualSerialization(vararg classes: KClass)`. `ContextSerializer` captures the `KClass` of the property at compile-time and looks up serializer for the property at run-time.

The current `SerialContext` is available as `val context` in the encoders and decoders. It contains the function `.getContextualSerializer(forClass: KClass)` which is used by `ContextSerializer`. However, it does not contain the functions to register serializers.

`interface MutableSerialContext : SerialContext` can be exposed by high-level abstractions (e.g. concrete JSON format, which can encapsulate a bunch of encoders) and provides an ability to correctly register all the serializers. 

These concepts will be described more precisely in the runtime library documentation.

### Polymorphism

Usually, polymorphism usage in serialization is discouraged because of the security problems it entails. However, writing complex business logic is almost impossible without this main OOP feature.
In this serialization framework, we get rid of the 'deserialize-anything' security problem that plagues naive approaches to polymorphism in serialization. All serializable implementations of some abstract class must be registered in advance. This also avoids reflection usage (such as `Class.forName`), which makes naive polymorphic serialization hard to implement on Kotlin/JS.

This is a more permissive approach than the 'bounded polymorphism' approach described in the section next to `SerialKind.SEALED`, because it allows registering subclasses in runtime, not compile-time. For example, it allows adding additional subclasses to the registry that were defined in a separate module, dependent on the base module with the base class.

Polymorphic serialization is never enabled automatically. To enable this feature, use `@SerializableWith(PolymorphicSerializer::class)` or just `@Polymorphic` on the property.

Another feature is that we only allow registering subclasses in the scope of a base class called _basePolyType_. The motivation for this is easily understandable from the example:

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

In this example, both `request` and `response` in `Message` are serializable with `PolymorphicSerializer` because of the annotation on them; `BaseRequest` and `BaseResponse` became _basePolyType_ s. (They are not required to be serializable by themselves.)
Yet `PolymorphicSerializer` for `request` should only allow `RequestA` and `RequestB` serializers, and none of the response's serializers. This is achieved via `registerPolymorphicSerializer` function, which accepts two `KClass` references: `registerPolymorphicSerializer(basePolyType: KClass, concreteClass: KClass, serializer: Serializer = concreteClass.serializer())`.

For details about registering and using pre-defined _modules_, please refer to the library's documentation.

## Issues open for discussion

* `SerializationStrategy` and `DeserializationStrategy` aren't used as widely as `Serializer`, but those names are long and cumbersome.
* It may not be clear that a `Serializer` can do both serialization and deserialization. Do we need a better name?
* Should the user be able to manipulate layout and order of calls to the encoder from generated code? [#121](https://github.com/Kotlin/kotlinx.serialization/issues/121)
* Should `@Optional` annotation be applied automatically when a property has a default value? [#19](https://github.com/Kotlin/kotlinx.serialization/issues/19)
* Should primitive arrays (ByteArray etc.) be treated specially by the plugin, or should the burden of handling them be borne by format implementation? [#52](https://github.com/Kotlin/kotlinx.serialization/issues/52)
* How to support different class name representations for different formats in polymorphism? [#168](https://github.com/Kotlin/kotlinx.serialization/issues/168)
* Should a polymorphic serializer omit class names in trivial cases (primitives), and how to implement this? [#40](https://github.com/Kotlin/kotlinx.serialization/issues/40)

## Future work

* Implement serialization/deserialization of interfaces.
* Serializable coroutines and continuations.

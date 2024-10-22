# Uuid

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Contributors**: Ilya Gorbunov, Filipp Zhinkin, Vsevolod Tolstopyatov, Dmitry Khalanskiy
* **Status**: Implemented in Kotlin 2.0.20-Beta2
* **Prototype**: Implemented
* **Target issue**: [KT-31880](https://youtrack.jetbrains.com/issue/KT-31880)
* **Discussion**: [KEEP-382](https://github.com/Kotlin/KEEP/issues/382)

## Summary

This proposal aims to introduce a class for representing a Universally Unique Identifier (UUID) in the
common Kotlin Standard Library. Additionally, it proposes APIs for the following UUID-related operations:
* Generating UUIDs.
* Parsing UUIDs from and formatting them to their string representations.
* Creating UUIDs from specified 128 bits.
* Accessing the 128 bits of a UUID.

## Motivation and use cases

Kotlin is positioning itself as a multiplatform programming language, especially for mobile app development.
As it gains popularity, there is a growing need for a common API for frequently used features.
UUIDs are an example of such features. They are ubiquitous in application development,
and currently, a common UUID API is one of the top requests from the Kotlin community:
* https://youtrack.jetbrains.com/issue/KT-31880/UUID-functionality-to-fix-Java-bugs-as-well-as-extend-it
* https://youtrack.jetbrains.com/issue/KT-43042/Add-UUID-to-the-common-Stdlib
* https://discuss.kotlinlang.org/t/uuid-for-kotlin-multiplatform/21925
* https://stackoverflow.com/questions/55424458/generate-uuid-on-kotlin-multiplatform

The lack of a common UUID API complicates the process of sharing code among multiple platforms.
This proposal aims to introduce a convenient and consistent API for UUID operations to simplify multiplatform
software development in Kotlin.

Use cases for UUID include:
* IDs in database records.
* Web session identifiers.
* Any scenario requiring unique identification or tracking. UUIDs facilitate retrieving the items associated with them.

UUIDs are particularly useful in environments where coordinating the generation of unique identifiers is
challenging or impractical.

## Similar API review

Several libraries were analyzed. This analysis included reviewing their design choices, user feedback, functionality,
and the popularity of those functionalities. These insights informed our design decisions and the feature set we
chose to implement in the initial release. Below is the list of the libraries reviewed:
* Java [`UUID`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/UUID.html) class.
* Apache commons [`UUID`](https://commons.apache.org/sandbox/commons-id/apidocs/org/apache/commons/id/uuid/UUID.html) class.
* [Java Uuid Generator (JUG)](https://github.com/cowtowncoder/java-uuid-generator) library.
* [UUID Creator](https://github.com/f4b6a3/uuid-creator?tab=readme-ov-file) library.
* Python [uuid](https://docs.python.org/3/library/uuid.html) module.
* Go [uuid](https://pkg.go.dev/github.com/google/uuid#section-readme) package.
* Swift Foundation [`UUID`](https://developer.apple.com/documentation/foundation/uuid) class.
* C# [`GUID`](https://learn.microsoft.com/en-us/dotnet/api/system.guid?view=net-8.0) class.
* Rust [uuid](https://docs.rs/uuid/latest/uuid/) crate.

## Proposal

It is proposed to introduce a `Uuid` class that represents a Universally Unique Identifier (UUID).

### Considered `Uuid` features

Several important aspects of the `Uuid` type, which are described in the subsections below, were discussed.

#### Interfaces implemented by `Uuid`

Two interfaces were considered for implementation by the `Uuid` class: `java.io.Serializable` in Kotlin/JVM
and `kotlin.Comparable` in all Kotlin targets.

##### `Serializable` interface

Despite the known issues with `java.io.Serializable`, like those highlighted [here](https://openjdk.org/projects/amber/design-notes/towards-better-serialization#whats-wrong-with-serialization),
this interface remains widely used in many applications. UUIDs are extensively used in distributed and storage systems,
and related frameworks typically require entities to implement some form of serialization. The `java.util.UUID` also implements
`Serializable`. Omitting this interface in `Uuid` would complicate the process of replacing existing `java.util.UUID`
usages with `Uuid`. Moreover, it would place `Uuid` at a disadvantage when users select a UUID implementation
for developing new applications. Therefore, we decided that `Uuid` should implement `Serializable` in Kotlin/JVM.
It's worth noting that `Uuid` is not the only Kotlin class implementing `Serializable` in Kotlin/JVM.
`Pair`, `Triple`, `Result`, `Regex`, and all collections also implement this interface.

##### `Comparable` interface

When it comes to implementing the `kotlin.Comparable` interface, a couple of factors convinced us to decide against it.

First, different UUID versions have different means of comparison. For example, time-based UUIDs are naturally ordered
by their timestamps, while name-based UUIDs might be better ordered lexically.
Second, `java.util.UUID` implements `Comparable` [incorrectly](https://bugs.openjdk.org/browse/JDK-7025832).
Transitioning from `java.util.UUID` to `Uuid` in existing projects could lead to unexpected differences in ordering.

However, we recognize the usefulness of being able to order UUIDs. Therefore, we have decided
to take a different approach: providing comparator objects instead of implementing `Comparable`. 
Initially, we have introduced the `Uuid.LEXICAL_ORDER` comparator, which compares two UUIDs bit by bit sequentially,
starting from the most significant bit to the least significant. This method of comparison is equivalent to comparing
UUID strings in a case-insensitive manner. In the future, we may introduce additional comparators to accommodate
different use cases.

#### UUID versions the Kotlin Standard Library should generate

We have examined which UUID versions can be correctly generated within the stdlib and which versions are popular.

Correctly generating time-based UUIDs is not straightforward. Each new UUID must have a timestamp that is not earlier
than the timestamp of the previously generated one. Handling clock rollbacks and system restarts would require storing
the last generated UUID in stable storage, as described in the [RFC](https://www.rfc-editor.org/rfc/rfc9562.html#name-uuid-generator-states). 
We believe the Standard Library is not an appropriate place to implement such logic.
Hence, it was decided not to implement the generation of time-based UUIDs.

The popularity of each UUID version was also explored. Our findings indicate that in approximately 90 percent of cases
users generate a UUID version 4 (random). Excluding time-based UUIDs, this figure rises to over 97 percent.

Considering this, it was decided to initially provide an API only for generating version 4 (random) UUIDs.
These UUIDs are produced using a cryptographically secure pseudorandom number generator (CSPRNG) available
on the platform. For more details about the underlying APIs used to produce the random `Uuid` in each of the
supported targets, refer to the official documentation of the `Uuid.random()` function.

#### UUID string formats the Kotlin Standard Library should parse and format to

The standard string representation of a UUID is in the format "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
where 'x' represents a hexadecimal digit. This format is also known as the "hex-and-dash" format.
Because a UUID is a 128-bit value, it can also be represented in binary, decimal, and hexadecimal forms.
For details, see [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#name-uuid-format).

The "hex-and-dash" format is the most popular. The hexadecimal form without dashes also has practical use cases,
such as when storing a UUID as text or sending a UUID string over the network. Removing dashes reduces the number
of characters.

Therefore, it was decided to support both formatting to and parsing from the standard "hex-and-dash" format
and the hexadecimal form without dashes.

#### Constructing UUID from given bits and retrieving UUID bits

The most straightforward method of providing 128 bits for UUID construction is via a `ByteArray`. Most programming
languages and libraries support constructing a UUID from a byte array, and these APIs are commonly used.
Therefore, we have decided to introduce such an API as well.

Java, however, only provides an API for constructing a UUID from two `Long`s. Developers have already
designed their logic and applications around this representation of a UUID. To ensure an easy transition
from `java.util.UUID` to Kotlin `Uuid`, we have introduced an API for constructing UUIDs from two `Long`s as well.
This will also facilitate the sharing of existing code that uses `java.util.UUID` among multiple platforms.

Additionally, we have decided to introduce APIs for constructing UUIDs from a `UByteArray` or two `ULong`s.
These improve the clarity of the bits being provided, especially when the sign bit of a `Byte` or a `Long` would be set.
For example:
```kotlin
val uuid1 = Uuid.fromLongs(-0x0AF17BFF1D64BE2CL, -0x58E9BB99AABC0000L)
val uuid2 = Uuid.fromULongs(0xF50E8400E29B41D4uL, 0xA716446655440000uL)

// Both create the same Uuid value
println(uuid1 == uuid2) // true
// Two ULong literals better reflect the value of the Uuid bits
println(uuid1) // f50e8400-e29b-41d4-a716-446655440000
```

Symmetrically, we have introduced APIs for retrieving `Uuid` bits in the form of a `ByteArray`, `UByteArray`,
two `Long`s, or two `ULong`s.

#### Accessing UUID fields

Most programming languages and libraries that provide a UUID type also offer APIs for accessing its fields,
as specified in [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#name-uuid-version-1).
For example, the [Java UUID class](https://docs.oracle.com/javase/8/docs/api/java/util/UUID.html)
provides `variant()`, `version()`, `timestamp()`, `clockSequence()`, and `node()` functions.
We have analyzed such APIs in various libraries and found very few usages. Moreover, each UUID version has
a different set of fields. For instance, the `timestamp` field does not make sense for a UUID version 4 or version 5.
The only field shared by all UUID values is the `variant` field.  Since knowing only a UUID variant is not
particularly useful without accessing other relevant fields, we have decided not to provide any API for
accessing UUID fields at this time.

However, users can retrieve UUID bits and perform operations on them to extract the value embedded in
particular bit positions.

#### Special UUID values and constants

[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#name-nil-uuid) defines two special UUID values: `Nil` and `Max`.
The `Nil` UUID has all bits set to zero, while the `Max` UUID has all bits set to one.
Our research of the existing codebase indicates that the `Nil` UUID has compelling use cases. For example,
serving as a placeholder for a non-null but not yet initialized variable. However, we did not find compelling
use cases for the `Max` UUID. Therefore, only the `Nil` is provided as a predefined `Uuid` instance.

We have also decided to introduce `SIZE_BYTES` and `SIZE_BITS` constants for the `Uuid` class.
They can be useful when creating a byte array to store a UUID or for performing calculations that involve
the UUID's binary size. It is worth noting, however, that unlike for primitive types, `SIZE_BYTES` and `SIZE_BITS`
do not indicate the memory footprint of a `Uuid` value.

### Proposed API

The discussions and decisions detailed above led us to design the API presented below.
These decisions were driven by our desire to keep the `Uuid` API surface small while still covering the
essential and most common use cases. In the future, the API could be expanded to add more features.
```kotlin
/**
 * Represents a Universally Unique Identifier (UUID), also known as a Globally Unique Identifier (GUID).
 */
public class Uuid : Serializable {

    /**
     * Executes the specified block of code, providing access to the uuid's bits in the form of two [Long] values.
     */
    public inline fun <T> toLongs(action: (mostSignificantBits: Long, leastSignificantBits: Long) -> T): T
    
    /**
     * Executes a specified block of code, providing access to the uuid's bits in the form of two [ULong] values.
     */
    public inline fun <T> toULongs(action: (mostSignificantBits: ULong, leastSignificantBits: ULong) -> T): T

    /**
     * Returns the standard string representation of this uuid.
     */
    override fun toString(): String

    /**
     * Returns the hexadecimal string representation of this uuid without hyphens.
     */
    public fun toHexString(): String

    /**
     * Returns a byte array representation of this uuid.
     */
    public fun toByteArray(): ByteArray

    /**
     * Returns an unsigned byte array representation of this uuid.
     */
    public fun toUByteArray(): UByteArray

    public companion object {
        /**
         * The uuid with all bits set to zero.
         */
        public val NIL: Uuid

        /**
         * The number of bytes used to represent an instance of [Uuid] in a binary form.
         */
        public const val SIZE_BYTES: Int = 16

        /**
         * The number of bits used to represent an instance of [Uuid] in a binary form.
         */
        public const val SIZE_BITS: Int = 128

        /**
         * Creates a uuid from specified 128 bits split into two 64-bit Longs.
         */
        public fun fromLongs(mostSignificantBits: Long, leastSignificantBits: Long): Uuid

        /**
         * Creates a uuid from specified 128 bits split into two 64-bit ULongs.
         */
        public fun fromULongs(mostSignificantBits: ULong, leastSignificantBits: ULong): Uuid

        /**
         * Creates a uuid from a byte array containing 128 bits split into 16 bytes.
         */
        public fun fromByteArray(byteArray: ByteArray): Uuid

        /**
         * Creates a uuid from an unsigned byte array containing 128 bits split into 16 unsigned bytes.
         */
        public fun fromUByteArray(ubyteArray: UByteArray): Uuid

        /**
         * Parses a uuid from the standard string representation as described in [Uuid.toString].
         */
        public fun parse(uuidString: String): Uuid

        /**
         * Parses a uuid from the hexadecimal string representation as described in [Uuid.toHexString].
         */
        public fun parseHex(hexString: String): Uuid

        /**
         * Generates a new random [Uuid] instance.
         */
        public fun random(): Uuid

        /**
         * A [Comparator] that lexically orders uuids.
         */
        public val LEXICAL_ORDER: Comparator<Uuid>
    }
}
```

The full documentation for each declaration can be found [here](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/uuid/Uuid.kt).

###  Kotlin/JVM-only convenience functions

To improve interaction with APIs that take or return a `java.util.UUID`,
we have introduced the following extension functions:
```kotlin
/**  
 * Converts this [java.util.UUID] value to the corresponding [kotlin.uuid.Uuid] value.
 */
 public fun java.util.UUID.toKotlinUuid(): Uuid  

/**  
 * Converts this [kotlin.uuid.Uuid] value to the corresponding [java.util.UUID] value.
 */
public fun Uuid.toJavaUuid(): java.util.UUID
```

We have also observed a frequent pattern in the existing Java codebase, where users write two `Long`s
representing a UUID to a [`ByteBuffer`](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html)
and similarly read two `Long`s to construct a UUID. To simplify such use cases,
the following extension functions have been introduced:
```kotlin
/**  
 * Reads a [Uuid] value at this buffer's current position. 
 */
public fun ByteBuffer.getUuid(): Uuid

/**  
 * Reads a [Uuid] value at the specified [index].  
 */
public fun ByteBuffer.getUuid(index: Int): Uuid

/**  
 * Writes the specified [uuid] value at this buffer's current position. 
 */
public fun ByteBuffer.putUuid(uuid: Uuid): ByteBuffer

/**  
 * Writes the specified [uuid] value at the specified [index].  
 */
public fun ByteBuffer.putUuid(index: Int, uuid: Uuid): ByteBuffer
```
These functions ignore the buffer's [byte order](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html#order--),
and read/write 16 bytes sequentially starting from the most significant byte of the `Uuid`. As a result, the functions
that read/write the `Uuid` at the buffer's current [position](https://docs.oracle.com/javase/8/docs/api/java/nio/Buffer.html#position--)
increment the position by 16. The other functions that take an index do not update the buffer's position.

### Examples

This is an example of how the proposed `Uuid` API could be used:

```kotlin
// Constructing Uuid from bits and parsing from string
val ubyteArray = ubyteArrayOf(
    0x55u, 0x0Eu, 0x84u, 0x00u, 0xE2u, 0x9Bu, 0x41u, 0xD4u,
    0xA7u, 0x16u, 0x44u, 0x66u, 0x55u, 0x44u, 0x00u, 0x00u
)
val uuid1 = Uuid.fromUByteArray(ubyteArray)
val uuid2 = Uuid.fromULongs(0x550E8400E29B41D4uL, 0xA716446655440000uL)
val uuid3 = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

println(uuid1) // 550e8400-e29b-41d4-a716-446655440000
println(uuid1 == uuid2) // true
println(uuid2 == uuid3) // true

// Accessing Uuid bits
val version = uuid1.toLongs { mostSignificantBits, _ ->
    ((mostSignificantBits shr 12) and 0xF).toInt()
}
println(version) // 4

// Generating a random Uuid, unique with a very high probability
val randomUuid = Uuid.random()

println(uuid1 == randomUuid) // false

// Converting Kotlin Uuid to java.util.UUID
val kotlinUuid = Uuid.parseHex("550e8400e29b41d4a716446655440000")
val javaUuid = kotlinUuid.toJavaUuid()
// Pass javaUuid to an API that takes a java.util.UUID
```

## Alternative approach

We have also considered making the Kotlin UUID class a [mapped type](https://kotlinlang.org/docs/java-interop.html#mapped-types)
of the Java UUID class. In Kotlin/JVM, the Kotlin UUID class would be compiled to Java UUID, so at runtime,
there would be no difference between them. This is similar to how Kotlin `String`, primitive types,
and collection types are mapped to their Java equivalents in Kotlin/JVM.
Such mapping would allow us to design a new API surface for the type, yet compile it to Java UUID.
This approach would enable Kotlin UUID to seamlessly work with APIs that use Java UUID,
keeping [platform types](https://kotlinlang.org/docs/java-interop.html#null-safety-and-platform-types) in mind.

One downside of this approach is that we would not be able to implement the `Comparable` interface in the future,
even if we find convincing arguments to do so. This limitation arises because Java `UUID.compareTo()`
[conducts signed `Long` comparisons](https://bugs.openjdk.org/browse/JDK-7025832), leading to non-lexical order.
We would not be comfortable bringing this behavior to the Kotlin UUID implementation on other platforms.

Additionally, it's worth mentioning that switching to the mapped type approach would change the `Serialization`
implementation currently in place; specifically, the serial representation would become larger.
Also, this approach would require special treatment by the Kotlin compiler.

## Alternatives to stdlib UUID

Because Kotlin did not previously provide a type for representing a UUID in common code,
the community has implemented several libraries to address this gap:
* https://github.com/benasher44/uuid
* https://github.com/cy6erGn0m/kotlinx-uuid
* https://github.com/hfhbd/kotlinx-uuid

Providing `Uuid` type directly in the Kotlin Standard Library avoids additional dependencies,
ensures the API is consistent with other Kotlin stdlib API, and ensures it has good performance.
Moreover, this enables better interoperability between different Kotlin libraries,
because they would converge on using the same type.

## Naming and design choices

Below we provide the rationale for the names and design choices we have made.
Currently, the API is experimental. We welcome the community to challenge our decisions
and are eager to hear your perspectives on how the API could be improved.

### Class name: UUID vs Uuid

There are many reasons to name the class in ALL CAPS. `UUID` reads and looks solid. Additionally,
many programming languages use `UUID`. However, in Kotlin, we have decided to use the CamelCase `Uuid`.
While there is no consensus in the Kotlin or Java ecosystems on what case to use for abbreviations,
we found several benefits of using CamelCase in the Kotlin Standard Library for `Uuid` and in the future:
* It is consistent with existing names `Base64.UrlSafe` and `Base64.Mime` names.
* It integrates better when used in a multiword name,
  e.g., `fromUuidString()` vs `fromUUIDString()` or `UuidJs.kt` vs `UUIDJs.kt`.
* It helps disambiguate whether the Java or Kotlin UUID type is being used without looking at imports.

### Constructing `Uuid` from given bits and retrieving `Uuid` bits

It was decided not to provide a public constructor for the `Uuid` class.
The two `Long`s or the `ByteArray` used to provide the bits are not properties of the `Uuid`,
nor do they affect its internal functioning. Instead, the bits are extracted from them to form the 128 bits
of the `Uuid` being constructed. Additionally, in the case of byte array, we need to validate its size.
For these reasons, it was decided to provide the following functions on the `Uuid.Companion` object:
* For constructing from two `Long`s: `fromLongs()`
* For constructing from two `ULong`s: `fromULongs()`
* For constructing from a `ByteArray`: `fromByteArray()`
* For constructing from a `UByteArray`: `fromUByteArray()`

For retrieving `Uuid` bits in the form of a byte array, `toByteArray()` and `toUByteArray()` were introduced. Retrieving bits
in the form of `Long`s and `ULong`s required some creativity. Introducing separate functions (or properties)
to retrieve the most significant 64 bits and the least significant 64 bits is not extensible. We would need to
provide four functions for both `Long` and `ULong`. Coming up with good names for these functions to
differentiate those returning `Long` from those returning `ULong` is a challenge. It is also possible to
introduce functions that return the two `Long`s or `ULong`s as a `LongArray` or `ULongArray` of size two.
However, this approach would introduce a performance penalty, complicate the usage of the bits,
and damage the explicitness of the bits being accessed.
We have analyzed how the `getMostSignificantBits()` and `getLeastSignificantBits()` methods of Java UUID are
used in the existing codebase. Our findings show that these values are typically used together. It is very rare
that one is needed without the other, whether for serializing them with ProtoBuf, storing them in a database,
or checking a bit pattern. Hence, we decided to introduce a single `toLongs()` function that provides both `Long`
values to the specified action block. Similarly, the `toULongs()` function is introduced to provide both values in
the form of `ULong`s to the block.

Similar designs can be found in existing functions of the Standard Library:
```kotlin
// Functions on companion objects to construct an instance of a type
Regex.fromLiteral(literal: String): Regex
// In kotlinx-datetime
Instant.fromEpochSeconds(epochSeconds: Long, nanosecondAdjustment: Int): Instant
LocalTime.fromMillisecondOfDay(millisecondOfDay: Int): LocalTime
// In ktor
HttpProtocolVersion.fromValue(name: String, major: Int, minor: Int): HttpProtocolVersion
HttpStatusCode.fromValue(value: Int): HttpStatusCode

// Duration functions for splitting it into components
inline fun <T> toComponents(action: (days: Long, hours: Int, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T
inline fun <T> toComponents(action: (seconds: Long, nanoseconds: Int) -> T): T
```

### Parsing and formatting to string

The following function names were selected:
* For parsing from the standard string representation: `parse()`
* For formatting to the standard string representation: `toString()`
* For parsing from the hexadecimal string representation without dashes: `parseHex()`
* For formatting to the hexadecimal string representation without dashes: `toHexString()`

The parsing functions throw an `IllegalArgumentException` if the input string is not in the expected format.
Parsing is performed in a case-insensitive manner. Formatting functions return the string representation in lowercase.

These names are consistent with existing functions in the Standard Library:
```kotlin
// For parsing from string
Duration.parse/parseOrNull(value: String): Duration
Duration.parseIsoString/parseIsoStringOrNull(value: String): Duration
// In the kotlinx-datetime library
Date entities.parse/parseOrNull(input: CharSequence): Date

// For converting to string
Duration.toIsoString(): String
// The same function is available for primitive types as well
ByteArray.toHexString(format: HexFormat = HexFormat.Default): String
```

An alternative approach is to support several formats in `parse()` and introduce `parseHexDash()`
and `toHexDashString()` functions for parsing from and formatting to the standard "hex-and-dash" representation.
The concern with supporting multiple formats in `parse()` is that supporting an additional format in the future
could be a breaking change. This situation occurs when code that relies on the rejection of formats not
currently supported is linked with a newer version of the Standard Library that accepts other formats.

## Dependencies

The dependencies of the proposed API:
* JDK: `java.util.UUID` and `java.nio.ByteBuffer`
* The APIs used for producing the random `Uuid` in each of the supported targets:
  * Kotlin/JVM - [java.security.SecureRandom](https://docs.oracle.com/javase/8/docs/api/java/security/SecureRandom.html)
  * Kotlin/JS - [Crypto.getRandomValues()](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/getRandomValues)
  * Kotlin/WasmJs - [Crypto.randomUUID()](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID)
  * Kotlin/WasmWasi - [random_get](https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md#random_get)
  * Kotlin/Native:
    * Linux targets - [getrandom](https://www.man7.org/linux/man-pages/man2/getrandom.2.html)
    * Apple and Android Native targets - [arc4random_buf](https://man7.org/linux/man-pages/man3/arc4random_buf.3.html)
    * Windows targets - [BCryptGenRandom](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptgenrandom)

## Placement

* Standard Library
* `kotlin.uuid` package

## Reference implementation

* `Uuid` class: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/uuid/Uuid.kt
* Kotlin/JVM-only convenience functions: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/src/kotlin/uuid/UuidJVM.kt
* Unit tests for the `Uuid` functions: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/test/uuid/UuidTest.kt
* Unit tests for the Kotlin/JVM-only convenience functions: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/test/uuid/UuidJVMTest.kt

## Future advancements

* Support more UUID string formats for parsing and formatting
    * e.g., formats enclosed in curly braces
* Support the generation of more UUID versions
    * e.g., the name-based UUID version 5
* Provide APIs for accessing UUID fields
    * e.g., `inline fun <T> toVersionOneFields(action: (variant: Int, version: Int, timestamp: Long, clockSequence: Int, node: Long) -> T): T`
* Support specifying the randomness source used for generating UUIDs

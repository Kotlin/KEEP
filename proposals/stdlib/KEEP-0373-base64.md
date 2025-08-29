# Base64

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Contributors**: Ilya Gorbunov, Roman Elizarov, Vsevolod Tolstopyatov, Dmitry Khalanskiy
* **Status**: Stable in 2.2.0
* **Prototype**: Implemented
* **Target issue**: [KT-57762](https://youtrack.jetbrains.com/issue/KT-55978/)
* **Discussion**: [KEEP-373](https://github.com/Kotlin/KEEP/issues/373)

## Summary

Introduce a convenient API for Base64 encoding and decoding for converting binary data to a printable string and vice versa.

## Motivation

As Kotlin strives to become a multiplatform programming language, there is a growing demand for features that work across all platforms.
Base64 is one such feature. The absence of a multiplatform Base64 complicates the process of sharing code between multiple targets.
We have received multiple requests from users for a Kotlin/Common API for Base64 encoding and decoding. Here are a few examples:
* https://discuss.kotlinlang.org/t/how-to-use-base64-in-multiplatform-ios-android/19755
* https://discuss.kotlinlang.org/t/kotlin-native-base64-en-decoder-code/10043
* https://youtrack.jetbrains.com/issue/KT-9823/Stdlib-should-include-base16-32-64-encoding-and-decoding
* https://youtrack.jetbrains.com/issue/KT-43185/Add-base64-String-ByteArray-en-and-decoding-to-the-standard-library
* https://youtrack.jetbrains.com/issue/KT-6695/add-extension-function-to-turn-ByteArray-into-a-base64-String

Base64 is one of the most widely used binary-to-text encodings for several fundamental reasons:
* Base64 converts binary data into an ASCII string relatively efficiently.
  It increases the data size by only about 33%.
* Historically, the ASCII characters used in Base64 encoding have been compatible with various systems and protocols.
  This allows encoded data to be easily transmitted and stored in systems that may only support text data.
* The implementation of a Base64 encoder/decoder is relatively simple and fast.
  Moreover, the encoding scheme is well-documented in standards such as [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648#section-4).

The benefits listed above, combined with the wide availability of implementations in virtually all programming languages,
make it easy to understand why it is so ubiquitous.
By providing a convenient API for common use cases described below, we aim to make coding in Kotlin easier and more
enjoyable. Additionally, it streamlines code sharing across platforms.

## Use cases

### Storing or transmitting binary data in text-only formats

Sometimes binary data needs to be embedded into text-only formats such as JSON or XML,
making it easier to transmit over HTTP. Similarly, this encoding allows users to embed images directly into HTML or CSS files.
Our research indicates that in this use case, Base64 encoding is among the most frequently, if not the most, used encoding.

### Include binary data in URLs

Base64 encoding is used to embed binary data, such as small images, into URLs. It reduces the number of server requests.
Because the '+' and '/' characters used in the default Base64 encoding scheme are special characters in URLs,
they are replaced with '-' and '_' correspondingly in this use case.

### Email and MIME encoding

Email systems use Base64 to encode attachments. This is necessary because email protocols are primarily text-based and
cannot handle raw binary data directly. In this use case, the encoded result is usually split into multiple lines.


## Similar API review

The following similar APIs were analyzed: 
* Java [`Base64`](https://docs.oracle.com/javase/8/docs/api/java/util/Base64.html) class.
* Guava [`BaseEncoding`](https://guava.dev/releases/snapshot-jre/api/docs/com/google/common/io/BaseEncoding.html) class.
* Python [`base64`](https://docs.python.org/3/library/base64.html) module.
* Go [`base64`](https://pkg.go.dev/encoding/base64) package.
* Swift `Data` [methods](https://developer.apple.com/documentation/foundation/data#2888198) and [initializers](https://developer.apple.com/documentation/foundation/data#2890507).

## Proposal

Considering the use cases mentioned above, it is proposed to support the following three variants of Base64:
1. The standard Base 64 encoding scheme described in [`RFC 4648 section 4`](https://www.rfc-editor.org/rfc/rfc4648#section-4)
2. URL and filename safe encoding scheme described in [`RFC 4648 section 5`](https://www.rfc-editor.org/rfc/rfc4648#section-5)
3. MIME Base 64 encoding scheme described in [`RFC 2045 section 6.8`](https://www.rfc-editor.org/rfc/rfc2045#section-6.8)

For all encoding variants, it is proposed to support the following operations:
* Encode binary data (`ByteArray`) and return the result in `String` form.
* Decode Base64-encoded `CharSequence` and return the resulting binary data (`ByteArray`).

Quite often, the encode result is directed into a byte stream. To facilitate the use case and avoid `String` to
`ByteArray` conversion, the following operations are proposed:
* Encode binary data (`ByteArray`) and return the result in `ByteArray` form.
* Decode Base64-encoded `ByteArray` and return the resulting binary data (`ByteArray`).

Each Base64 character occupies one byte in `ByteArray`, represented by its [`code`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/code.html).

In scenarios where encoding or decoding is performed in chunks, or when the reuse of the buffer between multiple
operations is required, the following operations are proposed:
* Encode binary data (`ByteArray`) and append the result to a specified `Appendable`, returning the destination appendable.
* Decode Base64-encoded `CharSequence` and write the result to a specified `ByteArray`, returning the number of bytes written.
* Encode binary data (`ByteArray`) and write the result to a specified `ByteArray`, returning the number of bytes written.
* Decode Base64 encoded `ByteArray` and write the result to a specified `ByteArray`, returning the number of bytes written.

### Proposed API

#### Encode and decode functions

It is proposed to introduce a `Base64` class that declare the operations above as member functions:
```kotlin
public open class Base64 {

    public fun encode(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size): String

    public fun <A : Appendable> encodeToAppendable(
        source: ByteArray,
        destination: A,
        startIndex: Int = 0,
        endIndex: Int = source.size
    ): A

    public fun encodeToByteArray(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size): ByteArray

    public fun encodeIntoByteArray(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = source.size
    ): Int

    public fun decode(source: CharSequence, startIndex: Int = 0, endIndex: Int = source.length): ByteArray

    public fun decodeIntoByteArray(
        source: CharSequence,
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = source.length
    ): Int

    public fun decode(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size): ByteArray

    public fun decodeIntoByteArray(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = source.size
    ): Int

}
```

#### Construction

The class won't have public constructors, and it is not supposed to be inherited or instantiated by calling its
constructor. Instead, a predefined instance of this class will be provided for each supported Base64 variant:
```kotlin
public open class Base64 {

    public companion object Default : Base64() {
        public val UrlSafe: Base64
        public val Mime: Base64
    }

}
```
The instances can be used to encode and decode using the encoding scheme of the corresponding Base64 variant. For example:
```kotlin
Base64.encode(...)
Base64.Default.encode(...) // same as above
Base64.UrlSafe.encode(...)
Base64.Mime.encode(...)
```

#### Padding options

The padding option for all predefined instances is set to `PRESENT`.
This means they pad on encode and require padding on decode.
New instances with different padding options can be created using the `withPadding` function:
```kotlin
public open class Base64 {
    
    public enum class PaddingOption {
      PRESENT,
      ABSENT,
      PRESENT_OPTIONAL,
      ABSENT_OPTIONAL
    }

    public fun withPadding(option: PaddingOption): Base64
}
```

The `withPadding` function does not modify the receiving instance.
Instead, it creates a new instance using the same alphabet but configured with the specified padding option.
The table below explains how each option impacts encoding and decoding results:

| PaddingOption    | On encode    | On decode                   |
|------------------|--------------|-----------------------------|
| PRESENT          | Emit padding | Padding is required         |
| ABSENT           | Omit padding | Padding must not be present |
| PRESENT_OPTIONAL | Emit padding | Padding is optional         |
| ABSENT_OPTIONAL  | Omit padding | Padding is optional         |

These options provide flexibility in handling the padding characters (`'='`) and enable compatibility with
various Base64 libraries and protocols.

Optional padding on decode means the input may be either padded or unpadded.
When padding is allowed and the input contains a padding character, the correct amount of padding
character(s) must be present. In this case, the padding character `'='` marks the end of the encoded data,
and subsequent symbols are prohibited.

There are two padding configurations that are not provided in the `PaddingOption` enum:
* `PRESENT_ABSENT` (pad on encode and prohibit padding on decode)
* `ABSENT_PRESENT` (do not pad on encode and require padding on decode)

We did not find convincing use cases for such padding configurations.
Therefore, we decided not to introduce them for now.

#### Decoding and encoding of input and output streams in Kotlin/JVM

On JVM, it is beneficial to provide an input stream that decodes Base64 symbols from the underlying input stream and supplies binary data:
```kotlin
public fun InputStream.decodingWith(base64: Base64): InputStream
```
Similarly, an output stream that encodes the supplied binary data and writes Base64 symbols to the underlying output stream:
```kotlin
public fun OutputStream.encodingWith(base64: Base64): OutputStream
```

These functions are particularly handy when the data being encoded or decoded is large, and loading it all into memory
at once in not desirable. For example, they can be used for encoding an attachment from the file system and embedding it
into an email.

## Contracts

For encode operations:
* Whether a `Base64` instance pads the result with `'='` to make it an integral multiple of 4 symbols depends on the
  padding option set for the instance. The padding option for all predefined instances is set to `PaddingOption.PRESENT`.
* `Base64.Default` and `Base64.UrlSafe` do not separate the result into multiple lines.
* `Base64.Mime` adds a CRLF every 76 symbols, but it does not add a CRLF at the end of the encoded output.

For decode operations:
* Whether a `Base64` instance requires, prohibits, or allows padding in the input symbols is determined by the
  padding option set for the instance. The padding option for all predefined instances is set to `PaddingOption.PRESENT`.
* All `Base64` instances interpret the padding character as the end of the encoded binary data, and subsequent symbols are prohibited.
* All `Base64` instances require the pad bits to be zeros.
* `Base64.Default` throws if it encounters a character outside "The Base64 Alphabet" as specified in Table 1 of RFC 4648.
* `Base64.UrlSafe` throws if it encounters a character outside "The URL and Filename safe Base 64 Alphabet" as specified in Table 2 of RFC 4648.
* `Base64.Mime` ignores all line separators and other characters outside "The Base64 Alphabet" as specified in Table 1 of RFC 2045.

### Examples

This is an example of how the proposed Base64 API could be used:

```kotlin
val foBytes = "fo".map { it.code.toByte() }.toByteArray()
Base64.Default.encode(foBytes) // "Zm8="
// Alternatively:
// Base64.encode(foBytes)

Base64.withPadding(Base64.PaddingOption.ABSENT).encode(foBytes) // "Zm8"

val foobarBytes = "foobar".map { it.code.toByte() }.toByteArray()
Base64.UrlSafe.encode(foobarBytes) // "Zm9vYmFy"

Base64.Default.decode("Zm8=") // foBytes
// Alternatively:
// Base64.decode("Zm8=")

Base64.UrlSafe.decode("Zm9vYmFy") // foobarBytes
```

## Alternatives

Because Kotlin didn't provide a Common API for Base64 encoding, community has implemented a few libraries targeting this gap:
* https://github.com/05nelsonm/encoding
* https://github.com/saschpe/Kase64

Providing Base64 encoding/decoding directly in the Kotlin standard library avoids additional dependencies,
ensures the API is consistent with other Kotlin stdlib API, and ensures it has a good performance.

## Naming

### Existing functions for encoding a String (UTF-16) to UTF-8 and decoding back

```kotlin
public fun String.encodeToByteArray(...): ByteArray

public fun ByteArray.decodeToString(...): String
```

### Proposed naming

When researching usages of Java `Base64`, we observed two patterns that influenced our design and naming.

First, the vast majority of users obtain an encoder or decoder and use it immediately,
e.g., `Base64.getDecoder().decode(...)`. This led us to avoid separating the encoder and decoder into different entities.
We also didn't find evidence that the benefits of such a separation outweigh the usage inconvenience. 

Second, encoding to `String` and decoding from `String` are considerably more popular than encoding to `ByteArray` and
decoding from `ByteArray`. Thus, we decided to make the more frequently used functions shorter.
Another reason for this decision is that a compound operation, such as encoding a `ByteArray` to a Base64 `String` and
then encoding that `String` to a UTF-8 `ByteArray`, should have a compound name.

As a result, the following names are proposed:
* Encode and return the result in `String` form: `encode`.
* Encode and return the result in `ByteArray` form: `encodeToByteArray`.
* Encode and append the result to a specified `Appendable`: `encodeToAppendable`.
* Encode and write the result to a specified `ByteArray`: `encodeIntoByteArray`.
* Decode (a `CharSequence` or `ByteArray`) and return the resulting `ByteArray`: `decode`.
* Decode (a `CharSequence` or `ByteArray`) and write the result to a specified `ByteArray`: `decodeIntoByteArray`.

Any naming suggestions are welcome.

## Dependencies

Only a subset of Kotlin Standard Library available on all supported platforms is required.

## Placement

* Standard Library
* `kotlin.io.encoding` package

## Reference implementation

* `Base64` class: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/io/encoding/Base64.kt
* `InputStream` and `OutputStream` extensions: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/src/kotlin/io/encoding/Base64IOStream.kt
* Test cases for `Base64` member functions: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/test/io.encoding/Base64Test.kt
* Test cases for `InputStream` and `OutputStream` extensions: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/test/io/Base64IOStreamTest.kt

## Future advancements

* Adding an option to allow non-zero pad bits on decoding
    * e.g., `fun Base64.allowNonZeroPadBits(): Base64` could be introduced
* Supporting the override of the default line length for `Base64.Mime` encoder: [KT-70456](https://youtrack.jetbrains.com/issue/KT-70456)

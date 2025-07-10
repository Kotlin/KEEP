**REDIRECT TO**: https://github.com/Kotlin/KEEP/blob/main/proposals/stdlib/KEEP-0362-hex-format.md

# HexFormat

* **Type**: Standard Library API proposal
* **Author**: Abduqodiri Qurbonzoda
* **Status**: Implemented in Kotlin 1.9.0
* **Prototype**: Implemented
* **Target issue**: [KT-57762](https://youtrack.jetbrains.com/issue/KT-57762/)
* **Discussion**: [KEEP-362](https://github.com/Kotlin/KEEP/issues/362)

## Summary

Convenient API for formatting binary data into hexadecimal string form and parsing back.

## Motivation

Our research has shown that hexadecimal representation is more widely used than other numeric bases,
second only to decimal representation. There are some fundamental reasons for the hex popularity:
* Hexadecimal representation is more human-readable and understandable when it comes to bits.
  Each digit in the hex system represents exactly four bits of data,
  making the mapping of a hex digit to its corresponding nibble straightforward.
* Hex representation is more compact than the decimal format and consumes a predictable number of characters.
* The implementation of a hex encoder/decoder is relatively simple and fast.

By providing a convenient API for common use cases described below, we aim to make coding in Kotlin easier and more enjoyable.

## Use cases

### Logging and debugging

The readability of the format makes it very appealing for logging and debugging.
The value that is converted to hex for logging is usually less informative itself than its binary representation, 
e.g., when the value has some particular bit pattern. Another popular use case is printing bytes in some
[hex dump](https://en.wikipedia.org/wiki/Hex_dump) format, split into lines and groups.

### Storing or transmitting binary data in text-only formats

Sometimes binary data needs to be embedded into text-only formats such as URL, XML, or JSON.
Our research indicates that in this use case, hex encoding is among the most frequently used encodings,
especially when encoding primitive values such as `Int` and `Long`.

### Protocol requirements

The following popular protocols require hex format:
* When generating or parsing HTML code, one might need to work with the hex representation of RGB color codes.
  e.g., `<div style="background-color:#ff6347;">...</div>`
* To express Unicode code points in HTML or XML.
  e.g., `<message>It's &#x1F327; outside, be sure to grab &#x2602;</message>`
* The framework used in your project might require specifying IP or MAC addresses in a certain hex format.
  e.g., `"00:1b:63:84:45:e6"` or `"001B.6384.45E6"`

## Similar API review

* Java [`HexFormat`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/HexFormat.html) class.
* Python [binascii](https://docs.python.org/3/library/binascii.html) module. Also,
  `hex` and `fromhex` functions on [bytes objects](https://docs.python.org/3/library/stdtypes.html#bytes-objects).

## Proposal

Considering the use cases mentioned above it is proposed to have the following format options.

For formatting a numeric value:
* Whether upper case or lower case hexadecimal digits should be used
* The prefix of the hex representation
* The suffix of the hex representation
* Whether to remove leading zeros in the hex representation
* The minimum number of hexadecimal digits to be used in the hex representation

For formatting `ByteArray`:
* Whether upper case or lower case hexadecimal digits should be used
* The number of bytes per line
* The number of bytes per group
* The string used to separate groups in a line
* The string used to separate bytes in a group
* The prefix of a byte hex representation
* The suffix of a byte hex representation

### Creating a format

It is proposed to introduce an immutable `HexFormat` class that holds the options.
`Builder` is used to configure a format. Each option in the builder has a default value that can be customized.
All related types are nested inside `HexFormat` to reduce the top-level surface area of the API:
```
public class HexFormat internal constructor(
    val upperCase: Boolean,
    val bytes: BytesHexFormat,
    val number: NumberHexFormat
) {

    public class Builder internal constructor() {
        var upperCase: Boolean = false
        val bytes: BytesHexFormat.Builder = BytesHexFormat.Builder()
        val number: NumberHexFormat.Builder = NumberHexFormat.Builder()
        
        inline fun bytes(builderAction: BytesHexFormat.Builder.() -> Unit)
        inline fun number(builderAction: NumberHexFormat.Builder.() -> Unit)
    }
  
    public class BytesHexFormat internal constructor(
        val bytesPerLine: Int,
        val bytesPerGroup: Int,
        val groupSeparator: String,
        val byteSeparator: String,
        val bytePrefix: String,
        val byteSuffix: String
    ) {

        public class Builder internal constructor() {  
            var bytesPerLine: Int = Int.MAX_VALUE  
            var bytesPerGroup: Int = Int.MAX_VALUE  
            var groupSeparator: String = "  "  
            var byteSeparator: String = ""  
            var bytePrefix: String = ""  
            var byteSuffix: String = ""  
        }
    }  

    public class NumberHexFormat internal constructor(
        val prefix: String,
        val suffix: String,
        val removeLeadingZeros: Boolean,
        val minLength: Int
    ) {

        public class Builder internal constructor() {  
            var prefix: String = ""  
            var suffix: String = ""  
            var removeLeadingZeros: Boolean = false  
            var minLength: Int = 1
        }
    }
}
```

`BytesHexFormat` and `NumberHexFormat` classes hold format options for `ByteArray` and numeric values, correspondingly.
The `upperCase` option, which is common to both `ByteArray` and numeric values, is stored in `HexFormat`.

It's not possible to instantiate a `HexFormat` or its builder directly. The following function is provided instead:
```
public inline fun HexFormat(builderAction: HexFormat.Builder.() -> Unit): HexFormat
```

Additionally, two predefined `HexFormat` instances are provided for convenience:
* `HexFormat.Default` - the hexadecimal format with all options set to their default values.
* `HexFormat.UpperCase` - the hexadecimal format with all options set to their default values,
  except for the `upperCase` option, which is set to `true`.

### Formatting

For formatting, the following extension functions are proposed:
```
// Formats the byte array using HexFormat.upperCase and HexFormat.bytes
public fun ByteArray.toHexString(format: HexFormat = HexFormat.Default): String

public fun ByteArray.toHexString(  
    startIndex: Int = 0,  
    endIndex: Int = this.size,  
    format: HexFormat = HexFormat.Default  
): String

// Formats the numeric value using HexFormat.upperCase and HexFormat.number
// N is Byte, Short, Int, Long, and their unsigned counterparts
public fun N.toHexString(format: HexFormat = HexFormat.Default): String
```

When formatting a byte array, one can assume the following steps:
1. The bytes are split into lines with `bytesPerLine` bytes in each line,
   except for the last line, which may have fewer bytes.
2. Each line is split into groups with `bytesPerGroup` bytes in each group,
   except for the last group in a line, which may have fewer bytes.
3. All bytes are converted to their two-digit hexadecimal representation,
   each prefixed by `bytePrefix` and suffixed by `byteSuffix`.
   The `upperCase` option determines the case (`A-F` or `a-f`) of the hexadecimal digits.
4. Adjacent formatted bytes within each group are separated by `byteSeparator`.
5. Adjacent groups within each line are separated by `groupSeparator`.
6. Adjacent lines are separated by the line feed (LF) character `'\n'`.

When formatting a numeric value, the result consists of a `prefix` string,
the hex representation of the numeric value, and a `suffix` string.
The hex representation of a value is calculated by mapping each four-bit chunk of its binary representation
to the corresponding hexadecimal digit, starting with the most significant bits.
The `upperCase` option determines the case of the hexadecimal digits (`A-F` or `a-f`).
If the `removeLeadingZeros` option is `true` and the hex representation is longer than `minLength`,
leading zeros are removed until the length matches `minLength`. However, if `minLength` exceeds the length of the
hex representation, `removeLeadingZeros` is ignored, and zeros are added to the start of the representation to
achieve the specified `minLength`.

### Parsing

It is critical to be able to parse the results of the formatting functions mentioned above.
For parsing, the following extension functions are proposed:
```
// Parses a byte array using the options from HexFormat.bytes
public fun String.hexToByteArray(format: HexFormat = HexFormat.Default): ByteArray

// Parses a numeric value using the options from HexFormat.number
// N represents Byte, Short, Int, Long, and their unsigned counterparts
public fun String.hexToN(format: HexFormat = HexFormat.Default): N
```

When parsing, the input string must conform to the structure defined by the specified format options.
However, parsing is somewhat lenient:
* For byte arrays:
  * Parsing is performed in a case-insensitive manner for both the hexadecimal digits and the format elements
    (prefix, suffix, separators) defined in the `HexFormat.bytes` property.
  * Any of the char sequences CRLF (`"\r\n"`), LF (`"\n"`) and CR (`"\r"`) is considered a valid line separator.
* For numeric values:
  * Parsing is performed in a case-insensitive manner for both the hexadecimal digits and the format elements
    (prefix, suffix) defined in the `HexFormat.number` property.
  * The `removeLeadingZeros` and `minLength` options are ignored.
    However, the input string must contain at least one hexadecimal digit between the `prefix` and `suffix`.
    If the number of hexadecimal digits exceeds the capacity of the type being parsed, based on its bit size,
    the excess leading digits must be zeros.

### Contracts
* Assigning a non-positive value to `BytesHexFormat.Builder.bytesPerLine/bytesPerGroup`
  and `NumberHexFormat.Builder.minLength` is prohibited. In this case `IllegalArgumentException` is thrown.
* Assigning a string containing LF or CR character to `BytesHexFormat.Builder.byteSeparator/bytePrefix/byteSuffix`
  and  `NumberHexFormat.Builder.prefix/suffix` is prohibited. In this case `IllegalArgumentException` is thrown.

### Examples

```
// Parsing an Int
"3A".hexToInt() // 58
// Formatting an Int
93.toHexString() // "0000005d"

// Parsing a ByteArray
val macAddress = "001b638445e6".hexToByteArray()

// Formatting a ByteArray
macAddress.toHexString(HexFormat { bytes.byteSeparator = ":" }) // "00:1b:63:84:45:e6"

// Defining a format and assigning it to a variable
val threeGroupFormat = HexFormat { upperCase = true; bytes.bytesPerGroup = 2; bytes.groupSeparator = "." }
// Formatting a ByteArray using a previously defined format
macAddress.toHexString(threeGroupFormat) // "001B.6384.45E6"
```

## Alternatives

### For numeric values

The Kotlin standard library provides `Primitive.toString(radix = 16)` for converting primitive values
to their hex representation. However, this function focuses on converting the values, not bits. As a result:
* Negative values are formatted with minus sign.
  One needs to convert values of signed types to corresponding unsigned types before converting to hex representation.
* Leading zero nibbles are ignored. To get the full length one must additionally `padStart` the result with `'0'`.
* Related complaint: [KT-60782](https://youtrack.jetbrains.com/issue/KT-60782)

There is also `String.toPrimitive(radix = 16)` for parsing back a primitive value.
But this function throws if the primitive type can't have the resulting value, even if the bits fit.
e.g., `"FF".toByte()` fails. To prevent this, the string must first be converted to the corresponding unsigned type.

### For `ByteArray`

`ByteArray.joinToString(separator) { byte -> byte.toString(radix = 16) }` can be used to format a ByteArray.
Downsides are:
* Not possible to separate bytes into groups and lines
* Challenges with formatting `Byte` to hex described above

There is no API for parsing `ByteArray` currently.

## Naming

### Existing functions for converting to String

For ByteArray:
* `contentToString`
* `encodeToByteArray`/`decodeToString`
* `joinToString`

For primitive types:
* `toString(radix)`
* `Char.digitToInt()`
* `Int.digitToInt()`

### Naming options

As listed above, existing functions with similar purpose use `toString` suffix when converting to `String`,
and `toType` when converting from `String` to another type. Thus, options with similar naming schemes were considered:
* **Proposed:** `toHexString` and `hexToType` for formatting and parsing, correspondingly
  * "hex" used as an adjective
* `hexToString` or `hexifyToString` for formatting
  * "hex" used as a verb
  * A similar verb is needed to describe the parsing of a hex-formatted string
* Use `format` and `parse` verbs, e.g., `formatToHexString` and `parseHexToByteArray`
  * `To` already indicates that the function converts the receiver

## API design approaches

* **Proposed:** Provide formatting and parsing functions as extensions on the type to be converted
  * Pro: Discoverable
    * Users already know and use the `toString` family of extension functions.
      When typing "toString", code completion displays the hex conversion functions as well.
      This can also prompt users to wonder how `toString(radix = 16)` differs from `toHexString()`,
      and help to choose the proper one.
    * Typing ".hex" is enough for code completion to display the hex conversion function for the receiver.
      No need to remember the exact function name.
  * Pro: Allows chaining with other calls
  * Con: May pollute code completion for `String` receiver
* Provide all formatting and parsing functions on `HexFormat`, similar to Java `HexFormat` and Kotlin `Base64`
  * Pro: Gathers all related functions under a single type
  * Con: Less discoverable than the proposed approach. Users need to remember that there is `HexFormat` class.
  * Con: Requires `let` or `run` [scope function](https://kotlinlang.org/docs/scope-functions.html) for chaining with other calls
* Have `BytesHexFormat` and `NumberHexFormat` as top-level classes, each with its own `upperCase` property.
  No need for `HexFormat` class. Functions for formatting/parsing `ByteArray` take `BytesHexFormat`,
  while functions for numeric types take `NumberHexFormat`. e.g.,
  ```
  byteArray.toHexString(
      BytesHexFormat { byteSeparator = " "; bytesPerLine = 16 }
  )
  ```
  * Pro: Eliminates possible confusion about what options affect formatting
  * Con: Two variables are needed to store preferred format options
* `Builder` overrides a provided format,
  e.g., `HexFormat(MY_HEX_FORMAT) { bytes.bytesPerLine = ":" }`
  * Not so many use cases for altering an existing format
  * Can be added as an overload of `fun HexFormat()`
* Pass options to formatting and parsing functions directly, without introducing `HexFormat`
  * Not convenient in cases when a format is defined once and used in multiple occasions
  * Adding new options in the future is problematic
  * There is no way in Kotlin to require calling a function with named arguments.
    Passing multiple arguments without specifying names damages code readability,
    e.g., `bitMask.toHexString(true, "0x", false)`

## Dependencies

Only a subset of Kotlin Standard Library available on all supported platforms is required.

## Placement

* Standard Library
* `kotlin.text` package

## Reference implementation

* HexFormat class: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/text/HexFormat.kt
* Extensions for formatting and parsing: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/text/HexExtensions.kt
* Test cases for formatting and parsing `ByteArray`: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/test/text/BytesHexFormatTest.kt
* Test cases for formatting and parsing numeric values: https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/test/text/NumberHexFormatTest.kt

## Future advancements

* Overloads for parsing a substring: [KT-58277](https://youtrack.jetbrains.com/issue/KT-58277)
* Overloads for appending format result to an `Appendable`
  * `toHexString` might need to be renamed to `hexToString/Appendable` or `hexifyToString/Appendable`, because 
    `Int.toHexString(stringBuilder)` isn't intuitive to infer that the result is appended to the provided `StringBuilder`
* Formatting and parsing I/O streams in Kotlin/JVM
  * Similar to [`InputStream.decodingWith(Base64)`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io.encoding/java.io.-input-stream/decoding-with.html) 
    and [`OutputStream.encodingWith(Base64)`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io.encoding/java.io.-output-stream/encoding-with.html)
* Formatting and parsing a `Char`
  * Although `Char` is not a numeric type, it has a `Char.code` associated with it. 
    With the proposed API formatting a `Char` won't be an easy task: `Char.code.toShort().toHexString()`

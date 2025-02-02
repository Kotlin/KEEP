# Support for UUID v5 and v7

# Title

* **Type**: Standard Library API proposal
* **Author**: Vasilii Kudriavtsev
* **Contributors**: 
* **Status**: Submitted
* **Prototype**: Not started


## Summary

Extend existing UUID APIs to support generation of UUIDs version 5 and 7, as defined in [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.txt).

## Similar API review

### Existing Kotlin standard Library APIs

Currently, the standard library [supports](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/)  generation of v4 UUIDs since Kotlin ver 2.0.

### Other languages and frameworks

* .NET 9 [added](https://github.com/dotnet/runtime/issues/103658) support for UUID v7 generation.
* Amazon RDS [supports](https://aws.amazon.com/ru/blogs/database/implement-uuidv7-in-amazon-rds-for-postgresql-using-trusted-language-extensions/) UUID v7 via an extension. 
* PostgreSQL [supports](https://commitfest.postgresql.org/47/4388/) UUID v7 since version 17
* In Java UUID v7 (and other versions) is supported through some open source libraries:
  * https://github.com/cowtowncoder/java-uuid-generator

Also, there exist libraries for both .NET and Java supporting ULIDs - another lexicographically sortable type of identifier, 
serving mostly the same purpose as UUID v7

## Use cases

## UUIDv7 use-cases

The distinct trait of UUIDv7 is the sequential order of generated UUIDS. That makes them especially useful as database
primary keys, as entities generated at approximately the same time . This locality trait 
enhances performances, as fewer pages have to be read from disk when executing many types of queries. 

UUID v4 generation, supported in the standard library, is based on purely random data. Thus, several v4 UUIDs generated in succession,
will probably be stored in different parts of the index, slowing down performance both of writing and reading (at some
later point in time).

## UUIDv5 use-cases

UUID version 5 provides support for consistent generation of UUIDs from identifiers with very high probability of
uniqueness. This type of UUID is handy when a mapping between two domains, one of which uses UUIDs to identify objects is required,
and storing such a mapping is for some reason undesirable.

Imagine a middleware that receives messages from multiple senders identified by their host names and forwards them to 
another system that accepts messages tag by UUIDs. In this case the middleware may generate random UUID when it first 
encounters a new host name and store the mapping into some persistent storage. But if the maximum number of hosts is known
to be much smaller than the possible number of UUIDs v5, than storage and all connected overhead (which included performance, 
backups, distributed database consistency and so on) can be avoided.

## Alternatives

Implementing compliant and performant UUID generation is not trivial, so most probably execution
environment specific open-source will be used. 

Providing additional UUID versions in the Kotlin standard library avoids additional,
execution environment specific, dependencies. Also it
ensures the API is consistent with other Kotlin stdlib API, and ensures it has a good performance.

## Proposal

It is proposed to support two additional version of UUID as specified in RFC 9562(https://www.rfc-editor.org/rfc/rfc9562.txt).

The earlier proposed v6 version is not included, as it was never widely adopted and fully superseded by v7.

### Proposed API

#### UUID generator interface

A new interface, representing a UUID generator is added. Although a pure function-based API is often simpler to use,
an object represents the stateful nature of UUID generation better. It is also more handy in the following cases:

* Providing a repeatable way to generate UUIDs, for example when used in testing.
* Tune the UUID generation process, for example changing the entropy of generated UUIDs.
* Provide a pooling implementation (see UUID pooling).

```kotlin
    public interface UuidGenerator {
    
        public fun generate() : Uuid
        
   }
```

A separate interface is created for name-bases UUID generator, as they need to accept input to generate UUIDs.

```kotlin


/**
 * Namespace used for DNS names.
 */
val NAMESPACE_DNS: Uuid = Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

/**
 * Namespace used for URLs.
 */
val NAMESPACE_URL: Uuid = Uuid.parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
/**
 * Namespace used for names created from OID.
 */
val NAMESPACE_OID: Uuid = Uuid.parse("6ba7b812-9dad-11d1-80b4-00c04fd430c8")

/**
 * Namespace used for X.500 identifier names
 */
val NAMESPACE_X500: Uuid = Uuid.parse("6ba7b814-9dad-11d1-80b4-00c04fd430c8")

public interface NameBasedUuidGenerator {

    public fun generate(name: CharSequence): Uuid

    public fun generate(name: ByteArray): Uuid

}
```


#### Companion object of class `Uuid` new methods

New methods are added to the Companion object of class `Uuid`.

```kotlin

/**
 *  Returns a UUIDv4 generator, that generates random UUIDs.
 */
public fun uuidV4Generator(): UuidGenerator

/**
 *  Returns a UUIDv5 generator, that generates UUIDs from supplied names
 */
public fun uuidV5Generator(namespace: Uuid = Uuid.NIL): NameBasedUuidGenerator

/**
 *  Returns a UUIDv7 generator, that generates time-ordered UUIDs.
 */
public fun uuidV7Generator(): UuidGenerator

```

#### Code examples

```kotlin

import kotlin.uuid.Uuid
import kotlin.uuid.NAMESPACE_URL

fun main() {

    val uuidV5Generator = Uuid.uuidV5Generator(NAMESPACE_URL)
    val uuidV5 = oneMoreV5Generator.generate("http://www.jetbrains.com")

    val uuidV7Generator = Uuid.uuidV7Generator()
    val uuidV7_1 = uuidV7Generator.generate()
    val uuidV7_2 = uuidV7Generator.generate()
}

```

## Dependencies

There should be no new dependencies for JVM platform.
TODO for other runtimes.

## Placement

* Standard Library
* `kotlin.uuid` package

## Reference implementation

TODO

## Considered alternative designs

### Specifying UUID version as a generation function argument

### Naming UUID generation functions with descriptive names

[Some](https://github.com/cowtowncoder/java-uuid-generator) implementations made the design choice to name the generators with descriptive names,
detailing its purpose and generation approach, instead of simply referencing a UUID version.

Example:

```java
    UUID uuid = Generators.timeBasedEpochGenerator().generate(); // Version 7
```

This proposal chooses for shorter names, referencing UUID versions, based on the following
assumptions:

* Shorter names are easier to remember and use.
* On the internet, the algorithm will probably be referred by its version number, so searching both on the internet and in the library documentation will ne simplified if the version is explicitly referenced.

## Future advancements

### UUID pooling

Generating secure UUIDs can become performance challenging is some cases, for example when UUIDs are used to identify
user session or as keys in a large batch of objects created and inserted into a database. 

Generating UUIDs securely implies use of randomness, and many environments (including real-world Java scenarios) may struggle to provide them
at required pace.

Moreover, the entropy source may be exhausted, and that can lead to slowdowns in other parts of the system. Such performance
problems are often very hard to diagnose.

One possible solution, successfully tested in production, may be maintaining a pool of UUID objects which will be replenished
by a separate thread when the system has spare resources.

### Explicit non-secure UUID generation

As discussion above suggests, generating securely random UUIDs can be rather slow and even impacting
on other parts of the system that use the same entropy source. Also, in many cases truly random
UUIDs are not needed. So and option to explicitly generate non-secure UUIDs may be considered.

### UUID v8 support

UUID v8 was also introduced in RFC 9562(https://www.rfc-editor.org/rfc/rfc9562.txt).

-------

# Appendix: Questions to consider
These questions are not a part of the proposal,
but be prepared to provide the answers if they aren't trivial.

## Naming

* Is it clear from name what API is for?
* Is it named consistently with other API with the similar purpose?
* Consider explorability of API via completion.
    Generally we discourage introducing extensions imported by default for unconstrained generic type or `Any` type, as it pollutes the completion.

Inspiring article on naming: http://blog.stephenwolfram.com/2010/10/the-poetry-of-function-naming/

## Contracts

* What are the failure conditions and how are they handled?
* Whether the contracts (preconditions, invariants, exception handling) are consistent and are what they may be expected from the similar features.

## Compatibility impact

* How the proposal affects:
    - source compatibility,
    - binary compatibility (JVM),
    - serialization compatibility (JVM)?
* Does it obsolete some other API? What deprecations and migrations are required?

## Shape

For new functions consider alternatives:

* top-level or extension or member function
* function or property




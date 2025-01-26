# Support for UUID v5, v7 and v8

# Title

* **Type**: Standard Library API proposal
* **Author**: Vasilii Kudriavtsev
* **Contributors**: 
* **Status**: Submitted
* **Prototype**: Not started


## Summary

Extend existing UUID APIs to support generation of UUIDs version 5, 7 and 8.

## Similar API review

### Existing Kotlin standard Library APIs

Currently, the standard library [contains](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/) support for generation of v4 UUIDs.

### Other languages and frameworks

* .NET 9 [added](https://github.com/dotnet/runtime/issues/103658) support for UUID v7 generation.
* Amazon RDS [supports](https://aws.amazon.com/ru/blogs/database/implement-uuidv7-in-amazon-rds-for-postgresql-using-trusted-language-extensions/) UUID v7 via an extension. 
* PostgreSQL [supports](https://commitfest.postgresql.org/47/4388/) UUID v7 since version 17
* In Java UUID v7 (and other versions) is supported through some open source libraries:
  * https://github.com/cowtowncoder/java-uuid-generator

## Use cases

* Provide several *real-life* use cases (either links to public repositories or ad-hoc examples).

## Alternatives

Implementing compliant and performant UUID generation is not trivial, so most probably execution
environment specific open-source will be used. 

Providing additional UUID versions in the Kotlin standard library avoids additional,
execution environment specific, dependencies,
ensures the API is consistent with other Kotlin stdlib API, and ensures it has a good performance.

## Proposal

It is proposed to support three additional version of UUID as specifien in RFC 9562(https://www.rfc-editor.org/rfc/rfc9562.txt)

The earlier proposed v6 version is not included, as it was never widely adopted and fully superseded by v7.


## Dependencies

TODO

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

### Changing default version used to generate UUIDs

As UUID v7 seems to be useful in most cases and does not seem to carry any performance burdens,
it could be beneficial to change the existing function `Uuid.random()` to return UUID v7 instances.

Still, the perceived benefits do not seem to be substantial enough to risk potential compatibility problems. 

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




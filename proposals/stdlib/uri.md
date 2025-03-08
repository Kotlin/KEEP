# Proposal: Add URI Type to Kotlin Standard Library

**Author**: iseki zero  
**Status**: Pending  
**Submitted**: 2025-03-09  

---

## Summary

This proposal advocates for introducing a first-class `URI` type to the Kotlin Standard Library. 

URIs are a **well-established standard** ([RFC 3986]) for identifying resources, forming the foundation of modern web protocols like HTTP and essential concepts like URLs. A standardized `URI` type would unify resource handling across Kotlin platforms, enable safer and more expressive APIs for future libraries (e.g., HTTP clients), and resolve inconsistencies caused by platform-specific implementations.

## Motivation

Since URI is not required to refer an accessible resources, many and many libraries use URIs to represent some identifier.

Not only in HTTP access(URLs), such as [package-url](https://github.com/package-url) is also an valid URI.

And, if you don't care use-cases, the data-uri is also an URI([RFC 2397]).

### Current Issues

- On JVM platform we have `java.net.URI`. But:
  - The behaviors is exactly following the [RFC 3986], but the cross language supports is broken. In other words, it doesn't implement [RFC 3987].
  - It's not following the Kotlin style, such as lacks of null-safety.
- On JavaScript platform we have no standarized type, but we have tons of 3rd-library, the ecosystem is fragmentized.
- All of them are harmful to Kotlin Multiplatform.

### Goals

- Provides a standarized, cross-platform, consistent type to represents URI, following [RFC 3986], even [RFC 3987].
- Provides a way to easily building, parsing it.
- Provides interopbility with `java.net.URI`.
- All of them will lay the foundation for future features in Kotlin Multiplatform.

## Description

```kotlin
fun main(){
   val parsedURI = URI("https://example.org/kotlin/multiplatform")
   val builtURI = buildURI{
      schema = "https"
      host = "example.org"
      path {
         append("kotlin")
         append("multiplatform")
      }
      // other representation of path-adder...
   }
}
```

## Sample implementation

WIP


[RFC 3986]: https://datatracker.ietf.org/doc/html/rfc3986 "Uniform Resource Identifier (URI): Generic Syntax"

[RFC 3987]: https://datatracker.ietf.org/doc/html/rfc3987 "Internationalized Resource Identifiers (IRIs)"

[RFC 2397]: https://datatracker.ietf.org/doc/html/rfc2397 "The \"data\" URL scheme"

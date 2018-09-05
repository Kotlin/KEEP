# Multiplatform DateTime API

* **Type**: Standard Library API proposal
* **Author**: Leonardo "Kerooker" Lopes
* **Contributors**: Currently none
* **Status**: Submitted
* **Prototype**: Not started


## Summary

Introduce an API in the Standard Library to manipulate Date and Time objects in any Kotlin-supported platform


## Similar API review

* Java: `[java.time.LocalDateTime](https://docs.oracle.com/javase/8/docs/api/java/time/LocalDateTime.html)`, `[Oracle article on Java's Date and Time API](https://www.oracle.com/technetwork/articles/java/jf14-date-time-2125367.html)
* `[Joda Time](http://www.joda.org/joda-time/)`

## Motivation and use cases
In the JVM, Java's Time API is available for usage. However, there's no good DateTime API that works on any platform. As Date and Time is important for a lot of APIs, there's a need to have that natively in Kotlin STDLib.

The use cases for a DateTime API are established, but some examples are:

* Scheduling an event in a calendar
* Registering when a given transaction happened
* Showing and manipulating dates in different formats

## Alternatives

The current alternative is to use Java 8 `java.time`, or any library for date and time manipulation in JVM. 
However. for platforms such as Android, Kotlin Native and others, these are not easily available, and current backports and libraries are not very similar.

## Dependencies

What are the dependencies of the proposed API:

* a subset of Kotlin Standard Library available on all supported platforms.

## Placement

* Standard Library, in `kotlin.time` package (package to be created)

## Reference implementation

* Implementations may be similar to [Joda Time](https://github.com/JodaOrg/joda-time), but including a idiomatic Kotlin approach.

## Unresolved questions

* Currently none


-------


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

The current alternative is to use Java 8 STDLib, or any library for date and time manipulation in JVM. 
However. for platforms such as Android, these are not easily available, and current backports and libraries are not very similar.

## Dependencies

What are the dependencies of the proposed API:

* a subset of Kotlin Standard Library available on all supported platforms.
* JDK-specific dependencies, specify minimum JDK version.
* JS-specific dependencies.

## Placement

* Standard Library, in `kotlin.time` package (package to be created)

## Reference implementation

* Provide the reference implementation and test cases.
In case if the API should be specialized for each primitive, only one reference implementation is enough.
* Provide the answers for the questions from the [Appendix](#appendix-questions-to-consider) in case they are not trivial.

## Unresolved questions

* List unresolved questions if any.
* Provide options to solve them.

## Future advancements

* What are the possible and most likely extension points?


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

## Additional considerations for collection operations

### Receiver types

Consider if the operation could be provided not only for collections,
but for other collection-like receivers such as:

* arrays
* sequences
* strings and char sequences
* maps
* ranges

It is helpful to determine what are the collection requirements:

* any iterable or sequence
* has size
* has fast indexed access
* has fast `contains` operation
* allows to mutate its elements
* allows to add/remove elements

### Return type

* Is the operation lazy or eager? Choose between `Sequence` and `List`
* What is the return type for each receiver type?
* Does the operation preserve the shape of the receiver?
I.e. returning `Sequence` for sequences and `List` for iterables.


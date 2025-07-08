# Proposal template for new API in the Standard Library

This document provides a template that can be used to compose a proposal about new API in the Standard Library.

# Title

* **Type**: Standard Library API proposal
* **Author**: author name
* **Contributors**: (optional) contributor names, if any
* **Status**: Submitted
* **Prototype**: Not started / In progress / Implemented


## Summary

Provide a brief description of the API proposed.

## Similar API review

* Is there a similar functionality in the standard library?
* How the same/similar concept is implemented in other languages/frameworks?

## Use cases

* Provide several *real-life* use cases (either links to public repositories or ad-hoc examples).

## Alternatives

* How verbose would be these use cases without the API proposed?

## Dependencies

What are the dependencies of the proposed API:

* a subset of Kotlin Standard Library available on all supported platforms.
* JDK-specific dependencies, specify minimum JDK version.
* JS-specific dependencies.

## Placement

* Standard Library or one of kotlinx extension libraries
* package(s): should it be placed in one of packages imported by default?

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


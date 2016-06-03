# Sealed class inheritance

* **Type**: Design proposal
* **Author**: Stanislav Erokhin
* **Status**: Under consideration
* **Prototype**: Implemented

## Summary
Allow top-level subclasses for top-level sealed class in the same file

## Motivation / use cases

- Nicer names for subclasses
- It's painful to create complex sealed class hierarchy -- nested level is too big
- >7 votes on [KT-11573](https://youtrack.jetbrains.com/issue/KT-11573): Support sealed class inheritors in the same file

## Open questions

- Should we allow not top-level subclasses?
  ```kotlin
  sealed class A
  
  class B {
    class C: A()
  }
  ```

- Should we allow subclasses of a sealed class on the same level?
  ```kotlin
  class A {
    sealed B
    class C: B()
  }
  ```

- Should we allow subclasses of a sealed class in other files?
- Can we provide a way to restrict inheritors explicitly?
  ```
  sealed(B, C) class A
  
  class B : A()
  class C : A()
  ```

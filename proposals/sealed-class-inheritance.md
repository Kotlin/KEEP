# Sealed class inheritance

* **Type**: Design proposal
* **Author**: Stanislav Erokhin
* **Contributors**: Andrey Breslav, Alexander Udalov
* **Status**: Implemented since Kotlin 1.1

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/29).

## Summary
In kotlin 1.0 all direct subclasses for a sealed class should be declared inside it.
For example:
```kotlin
sealed class A {
  class B: A()
}
```

For some cases such limitation is inconvenient (see use cases below).

Proposal: allow top-level subclasses for a top-level sealed class in the same file.

## Motivation / use cases

- Nicer names for subclasses
- It's painful to create complex sealed class hierarchy -- nesting is too deep
- >7 votes on [KT-11573](https://youtrack.jetbrains.com/issue/KT-11573): Support sealed class inheritors in the same file

## Implementation details

### Compiler checks

For a non top-level sealed class all subclasses should be declared inside it. 
So, for such classes nothing changes.

Let us describe changes for top-level sealed classes.
Suppose that we have a top-level class `A`.
For every class `B` which has a class `A` among its supertypes, we should check:

- if `B` is a top-level class, then we should check that `A` and `B` are declared in same file;
- otherwise we should check that `B` is declared inside `A`.

Examples:
```kotlin
// FILE: 1.kt
sealed class A {
  class B : A() { // B is declared inside A -- ok
    class C: A() // C is declared inside A -- ok
  }
}

class D : A() { // D and A are declared in same file -- ok
  
  class E : A() // E is declared outside A -- error 
}

// FILE: 2.kt
class F: A() // F and A are declared in different files -- error
```

### Exhaustive `when` check

Suppose we have `when` with parameter `a` where `a` is an instance of a sealed class `A`.
Example:
```kotlin
fun foo(a: A) = when(a) {
  is B -> 1
  is C -> 2
}
```
In such code the compiler should check that `when` is exhaustive, i.e. all branches are presented.
To do this, we want to collect all direct subclasses of `A`.
So we should collect all classes inside it and, if class `A` is top-level, collect all classes in the same package.
After this we should choose from them only direct subclasses of class `A`.

As we see above, all direct subclasses of sealed classes will be declared in same file with corresponding sealed class.
Because of this, it is impossible to add direct subclass of class `A` without recompilation of class `A`.

### Bytecode generation

In bytecode we should generate special synthetic constructors for class A, which will be called from direct subclasses constructors. Actually, the same sythetic constructors are generated for sealed classes in kotlin 1.0, so we should reuse corresponding algorithm.

**Future improvements:**

- Store information about direct subclasses in binary metadata for corresponding sealed class. [KT-12795](https://youtrack.jetbrains.com/issue/KT-12795)

## Open questions

- Should we allow non top-level subclasses?
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

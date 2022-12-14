# Support references to synthetic Java properties

* **Type**: Design proposal
* **Author**: Pavel Mikhailovskii
* **Status**: Proposed
* **Prototype**: Supported under a flag in 1.3.70 and enabled by default in .gradle.kts. Preview in 1.8.20 with `-language-version 1.9`. Planned to be enabled by default in 1.9.
* **Related issues**: [KT-8575](https://youtrack.jetbrains.com/issue/KT-8575), [KT-35933](https://youtrack.jetbrains.com/issue/KT-35933), [KT-54525](https://youtrack.jetbrains.com/issue/KT-54525), [KT-54770](https://youtrack.jetbrains.com/issue/KT-54770)

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/328).

## Summary

While Kotlin allows to access Java synthetic properties, it provides only limited and, until recently, 
not fully correct support for obtaining references to such properties using `object::property` or `class::property` syntax. 
This document describes the known bugs and limitations of the current implementation, a proposes a number of steps for improving the situation. 

## Motivation

The possibility to access Java synthetic properties the same way as properties defined in Kotlin is widely used in Kotlin/JVM.
For the sake of language consistency, it would make sense to allow to obtain `KProperty` references to such properties
similarly to Kotlin properties as well.

## Context

Methods that follow the Java conventions for getters and setters (no-argument methods with names starting with get
(or `is` for boolean properties) and single-argument methods with names starting with set)
are represented as properties in Kotlin, as described [here](https://kotlinlang.org/docs/java-interop.html#getters-and-setters).

So, properties defined in a Java class
```java
class Widget {
  private String name;
  private boolean active;
  
  public String getName() {
    return name;
  }
  
  public void setName(String value) {
    name = value;
  }
  
  public boolean isActive() {
    return active;
  }
  
  public boolean isActive(boolean value) {
    active = value;
  }
}
```
Can be accessed in Kotlin as follows:
```kotlin
val widget = Widget()
widget.name = "Widget1"
widget.isActive = true
```

## References to synthetic Java properties: current situation

In Kotlin 1.3.70 a possibility to obtain references to such properties was added.
```kotlin
val widget = Widget()
val widgetName = widget::name
widgetName.set("Widget1")
```
That functionality was never widely advertised, and was only available under a feature flag.
It also wasn't properly documented or covered with tests.
Nonetheless, it was enabled by default in `.gradle.kts` (see [KT-35933](https://youtrack.jetbrains.com/issue/KT-35933)).
Until recently, it didn't work in K2 (see [KT-54770](https://youtrack.jetbrains.com/issue/KT-54770)); the issue has been
solved in our prototype.

Our review of the implementation added in 1.3.70 uncovered a number of issues:
- Most of the features provided by `kotlin-reflect`, such as `KProperty::visibility` worked incorrectly.
- Synthetic Java properties weren't included in `KClass.members`.
- Synthetic Java properties can be overshadowed by "physical" declarations with the same name.

We are going to consider the found issues and possible ways of solving them below.

## Reflection support

As was mentioned above, all features requiring `kotlin-reflect`, such as `KProperty::visibility` worked incorrectly. 
Instead of somehow taking into account the synthetic nature of the property, they tried to access a Java **field** of the same name. 
If no such field existed a run-time exception would be thrown.
In particular, that meant that calling `propertyReference.seter(value)` would bypass the original setter method and write
directly to the underlying field!

Possibly, a proper solution to this issue would be to implement proper reflection support.
However, the demand for such a feature is most likely very low, so that we could postpone its implementation.
Supporting reflection would require making some extra design decisions, for example on what should be returned
by such properties as `KProperty::annotations` or `KProperty.Getter::name`.

As a midterm solution, we propose to forbid `kotlin-reflect`-based reflection for synthetic Java properties.
Any invocation of a method or a property requiring `kotlin-reflect` would result in an `UnsupportedOperationException`.
That solution has been implemented in our prototype.
If a significant number of users asks for full reflection, we'll reconsider this decision.

The fact that synthetic Java properties aren't included in `KClass.members` doesn't seem to be an issue.
By design, `KClass.members` returns only real members and doesn't include any synthetic ones.
In the future, we could possibly consider introducing a separate property for them, e.g. `KClass.syntheticMembers`.

## Reference resolution strategy

The existing reference resolution strategy seems to be consistent and doesn't require any modification, even
though the logic behind it may look puzzling at a first glance.

First of all, synthetic members always have lesser priority than any "physical" declarations. If a Java synthetic property is
shadowed by "physical" declaration with the same name, and both members satisfy the expected type, or no expected type constraint is present,
the compiler will prefer the physical member without reporting ambiguity. 

In particular, it means that for "is"-prefixed boolean synthetic Java properties a reference expression without
an expected type annotation will return a reference to the getter, not the property:
```kotlin
val isActiveRef = widget::isActive // returns a KFunction0<Boolean> reference the getter method
```

If a situation when the expected type is known, it will be taken into account, so that only declarations satisfying the
expected type will take part in resolution. This rule is not specific to synthetic Java properties and applies to all 
kinds of callable references in Kotlin. It makes it possible to obtain a reference to a synthetic Java property even if the
latter is overshadowed by a physical member with the same name:
```kotlin
val isActivePropertyRef: KMutableProperty0<Boolean> = widget::isActive // now it's a reference to the property, not the getter
```

We believe that it should be enough to simply explain this logic in user documentation.

## The proposed solution

To sum up, we propose the following:
- Make it possible to reference synthetic Java properties using the `::` syntax.
- Disable `kotlin-reflect`-dependent features for now; throw an `UnsupportedOperationException` with a message making
it clear that the limitation applies only to Java synthetic properties.
- Do not include synthetic Java properties in `KClass.members`.
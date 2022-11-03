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

# Context

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
It also wasn't properly documented add covered with tests.
Nonetheless, it was enabled by default in `.gradle.kts` (see [KT-35933](https://youtrack.jetbrains.com/issue/KT-35933)).
Until recently, it didn't work in K2 (see [KT-54770](https://youtrack.jetbrains.com/issue/KT-54770)); the issue has been
solved in our prototype.

Our review of the implementation added in 1.3.70 uncovered a number of issues:
- Synthetic Java properties weren't included in `KClass.members`.
- Most of the features provided by `kotlin-reflect`, such as `KProperty::visibility` worked incorrectly.
- The compiler didn't report resolution ambiguity when it occurred.

We are going to consider the found issues and possible ways of solving them below.

## Reflection support

As was said above, synthetic Java properties aren't included in `KClass.members`.
Even though it wouldn't be difficult to include them, such a change could potentially break users' code.
Very often Java synthetic properties use an underlying field whose name is the same as the name of the property itself.
As we already include Java fields in `KClass.members`, adding also Java synthetic properties would mean that `KClass.members` would
contain two `KProperty` entries with the same type and name for each synthetic property. 
User code using `KClass.members` to directly access Java fields may break after addition of extra members.
This consideration seems to be a serious argument against inclusion of synthetic Java properties into `KClass.members`.
```kotlin
val nameField = Widget::class.memberProperties.single { it.name == "name"} // would fail if we include both the field and the synthetic property
```

As was mentioned above, all features requiring `kotlin-reflect`, such as `KProperty::visibility` worked incorrectly. 
Instead of somehow taking into account the synthetic nature of the property, they tried to access a Java **field** of the same name. 
If no such field existed a run-time exception would be thrown.
In particular, that meant that calling `propertyReference.seter(value)` would bypass the original setter method and write
directly to the underlying field!

Possibly, a proper solution to this issue would be to implement proper reflection support.
However, the demand for such a feature is most likely very low, so that we could postpone its implementation.
As a midterm solution, we propose to forbid full reflection for synthetic Java properties.
Any invocation of a method or a property requiring `kotlin-reflect` would result in an `UnsupportedOperationException`.
That solution has been implemented in our prototype.
If a significant number of users asks for full reflection, we'll reconsider this decision.

## Reporting resolution ambiguity

At the moment the compiler doesn't report resolution ambiguity error in the cases when a reference expression `object::member` can
refer to both a synthetic Java property and some other member. Instead, it always silently prefers other types of members
to Java synthetic properties. That may lead to hard-to-spot and hard-to-understand errors.

In particular, for boolean properties whose name starts with "is" prefix, the name of the property would be the same as
the name of its getter. In that case, if no type information sufficient to decide whether the reference points to a property, or to a function,
it would be treated as a reference to the getter:
```kotlin
val nameRef = widget::name         // returns an instance of KMutableProperty0<String>
val isActiveRef = widget::isActive // returns an instance of KFunction0<Boolean>
val isActivePropertyRef: KMutableProperty0<Boolean> = widget::isActive // returns an instance of KMutableProperty0<Boolean>
```
We suggest that the compiler should always report an overload ambiguity error in such cases.

## The proposed solution

To sum up, we propose the following:
- Make it possible to reference synthetic Java properties using the `::` syntax.
- Disable `kotlin-reflect`-dependent features for now; throw an `UnsupportedOperationException`.
- Do not include synthetic Java properties in `KClass.members`.
- When checking reference expressions for overload ambiguity the compiler should handle Java synthetic properties
the same way as Kotlin properties.
- For boolean properties starting with "is" the compiler should always report errors for references that may point
to both the property itself and its getter.

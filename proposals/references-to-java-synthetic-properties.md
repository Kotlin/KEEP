# Support references to synthetic Java properties

* **Type**: Design proposal
* **Author**: Pavel Mikhailovskii
* **Status**: Proposed
* **Prototype**: Supported under a flag in 1.3.70 and enabled by default in .gradle.kts. Preview in 1.8.20 with `-language-version 1.9`. Planned to be enabled by default in 1.9.
* **Related issues**: [KT-8575](https://youtrack.jetbrains.com/issue/KT-8575), [KT-35933](https://youtrack.jetbrains.com/issue/KT-35933), [KT-54525](https://youtrack.jetbrains.com/issue/KT-54525)

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/328).

## Summary

While Kotlin allows to access Java synthetic properties, it provides only limited and, until recently, 
not fully correct support for obtaining references to such properties using `object::property` or `class::property` syntax. 
This document describes the known bugs and limitations of the current implementation, a proposes a number of steps for improving the situation. 

## Motivation

The possibility to access Java synthetic properties the same way as properties defined in Kotlin is widely used in Kotlin/JVM.
For the sake of language consistency, it would make sense to allow to obtain `KProperty` references to such properties
similarly to Kotlin properties as well.

## Historical Digression

Kotlin allows to access synthetic Java properties the same way as properties defined in Kotlin.
The main known limitation is don't support indexed synthetic Java properties
(see [JavaBeans Specification](https://download.oracle.com/otndocs/jcp/7224-javabeans-1.01-fr-spec-oth-JSpec/) chapter 7.2).  

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

In Kotlin 1.3.70 a possibility to obtain references to such properties was added.
```kotlin
val widget = Widget()
val widgetName = widget::name
widgetName.set("Widget1")
```
That functionality was never widely advertised, and was only available under a feature flag.
It also wasn't properly documented add covered with tests.
Nonetheless, it was enabled by default in `.gradle.kts`.

Our review of the implementation added in 1.3.70 uncovered a number of issues:
- Synthetic Java properties weren't included in `KClass.members`
- Most of the features provided by `kotlin-reflect`, such as `KProperty::visibility` worked incorrectly.
- The property reference syntax worked differently for boolean and other properties.

We are going to consider the found issues and possible ways of solving them below.

## Reflection support

As was said above, synthetic Java properties aren't included in `KClass.members`.
Even though it wouldn't be difficult to include them, such a change could potentially break users' code.
Very often Java synthetic properties use an underlying field whose name is the same as the name of the property itself.
As we already include Java fields in `KClass.members`, adding also Java synthetic properties would mean that `KClass.members` would
contain two `KProperty` entries with the same type and name for each synthetic property. 
User code using `KClass.members` to directly access Java fields may break after addition of extra members.
This consideration seems to be a serious argument against inclusion of synthetic Java properties into `KClass.members`.
If we ever decide to o that, we will probably have to add a new overloaded version of `KClass.members`
with a boolean parameter controlling inclusion of Java synthetic properties.
We would also need to add a property, indicating that a `KProperty` instance represents a synthetic property,
e.g. `KProperty::isSythetic`.

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

## The asymmetry between boolean and other properties

According to the JavaBeans specification, the name of a getter method for a boolean property can with either "get" or "is" prefix.
In the latter case Kotlin
(in contrast to the [JavaBeans Specification](https://download.oracle.com/otndocs/jcp/7224-javabeans-1.01-fr-spec-oth-JSpec/)
and JDK utility classes such as `com.sun.beans.introspect.PropertyInfo`) treats the "is" prefix as a part of the property name.
Unfortunately, that has an unexpected effect on the semantic of reference expressions.
While `val nameProperty = widget::name` would return an instance of `KMutableProperty0<Boolean>`, a similar expression for a boolean property
`val isActiveProperty = widget::isActive` would return an instance of `KFunction` referencing the getter method of the same name.
To obtain a reference to the property, an explicit type annotation is needed:
`val isActiveProperty: KMutableProperty0<Boolean> = widget::isActive`.
Such an inconsistency may lead to hard-to-spot and hard-to-understand errors. 
It seems that the only way to mitigate this issue is to implement an IDE inspection offering a quick fix adding an
explicit type annotation and allowing to choose between referencing a property and a getter.
In the cases where a property reference is not assigned to a variable, but is passed elsewhere,
the fix should introduce a temporary `val` with an explicit type annotation:
```kotlin
val props = listOf(widget::name, widget::isActive)
```
After the quick fix is applied:
```kotlin
val widgetIsActivePropertyReference: KMutableProperty0<Boolean> = widget::isActive
val props = listOf(widget::name, widgetIsActivePropertyReference)
```

## The proposed solution

Tu sum up, we propose the following:
- Make it possible to reference synthetic Java properties using the `::` syntax.
- Disable `kotlin-reflect`-dependent features for now; throw an `UnsupportedOperationException`.
- Do not include synthetic Java properties in `KClass.members`.
- Implement an IDE inspection  offering a quick fix adding an explicit type annotation for references to boolean 
synthetic Java properties with "is"-prefixed getter.
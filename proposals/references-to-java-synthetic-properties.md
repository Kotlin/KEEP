# Support references to synthetic Java properties

* **Type**: Design proposal
* **Author**: Pavel Mikhailovskii
* **Status**: Proposed
* **Prototype**: Supported under a flag in 1.3.70 and enabled by default in .gradle.kts. Preview in 1.8.20 with `-language-version 1.9`. Planned to be enabled by default in 2.1.
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

```java
class Jaba {
    public boolean isFoo; // (0) physical val
    public boolean isFoo() {return true;} // (1) physical method, (2) synthetic val

    public boolean clazz() {return true;} // (3) physical method
    public class clazz {} // (4) physical class

    public String getField() {return "";} // (5) physical method, (6) synthetic val
    public int field = 2; // (7) physical val

    public int bar() {return 1;}  // (8) physical method
    public int bar; // (9) physical val

    public int getGetGoo() {return 1;} // (10) physical method, (11) synthetic val
    public int getGoo = 2; // (12) physical val

    public String getIsBaz() {return "getIsBaz";} // (15) physical method, (16) synthetic val
    public boolean isBaz() {return true;} // (17) physical method, (18) synthetic val
}
```

```kotlin
import kotlin.reflect.KProperty

class Kt {
    val prop: Int = 42 // (13)
}

val Kt.prop: String get() = "extension" // (14)

fun main() {
    // Case (a)
    Jaba::isFoo // Conflict between physical members (0) and (1)
    Jaba::clazz // Conflict between physical members (3) and (4)
    Jaba::bar // Conflict between physical members (8) and (9)

    // Case (b)
    Jaba::field // Resolves to (7). Physical member (7) is preferred over synthetic val (6)
    Jaba::getGoo // Resolves to (12). physical val (12) is preferred over synthetic val (11)

    // Case (c)
    val z: KProperty<Boolean> = Jaba::isFoo // Resolves to (0). A different member is chosen in conflicting situation.
                                            //                  The conflict is resolved

    // Case (d)
    val x: KProperty<String> = Jaba::field // Resolves to (6). A different member is chosen in non-conflicting situation
    val y: KProperty<String> = Kt::prop // Resolves to (14). A different member is chosen in non-conflicting situation

    // Case (e)
    val w: KProperty<*> = Jaba::isBaz // Conflict between synthetic members (16) and (18)

    // Case (f)
    val v: KProperty<String> = Jaba::isBaz // Resolves to (16). The conflict is resolved
    val h: KProperty<Boolean> = Jaba::isBaz // Resolves to (18). The conflict is resolved
}
```

The following resolution rules apply:
1. If the candidates are physical members, the conflict is reported. Case **(a)**
2. If the candidates are physical and synthetic members, physical member will be chosen. Case **(b)**
3. Sometimes conflict can be resolved with the help of expected type. Cases **(c)** and **(f)**
4. Synthetic member can be chosen over physical member with the help of expected type. Case **(d)**
5. If the candidates are synthetic members, the conflict is reported. Case **(e)**

Even though the code might look puzzling at a glance (why `Jaba::isFoo` results in a conflict but `Jaba::field` doesn't?),
the resolution rules are in fact consistent.

## The proposed solution

To sum up, we propose the following:
- Make it possible to reference synthetic Java properties using the `::` syntax.
- Disable `kotlin-reflect`-dependent features for now; throw an `UnsupportedOperationException` with a message making
it clear that the limitation applies only to Java synthetic properties.
- Do not include synthetic Java properties in `KClass.members`.
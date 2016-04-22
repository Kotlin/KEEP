# Attach to delegate property object

* **Type**: Design proposal
* **Author**: Ilya Ryzhenkov
* **Contributors**:
* **Status**:
* **Prototype**: Was implemented and then disabled

## Summary

Recognize special operator function available for delegate object and call it right after it has been stored
into a field for delegation.

## Glossary

- "delegate instance": instance on which property's get/set accessors execute respective getValue/setValue calls.
- "host instance": instance containing delegated properties as members, or being extended with extension property.
- "attachTo": placeholder for the name of the operator function
- "constructor" means either primary constructor, or each of secondary constructor if there is no primary constructor

## Motivation / use cases

Make delegate instance aware of host instance and property it was used with.

- Register host instance in external table of managed entities, e.g. database schema, component container.
- Register delegate properties in host instance with callback for "metadata", e.g. for database columns.
- Call back into host instance with additional information, e.g. property metadata, delegate instance.
  Can be used to communicate delegate instance back to the host instance for later use, by declaring member
  extension `attachTo` function with receiver of delegate type.
- Execute custom code before first access to property, e.g. `async` property that is being calculated
  in the background, and blocks as needed when trying to use the value. With coroutines, we can suspend instead
  of blocking.

## Description

Client code:
```
class HostType {
   val name by DelegateType()
}
```

Attach operator:
```
operator fun DelegateType.attachTo(instance: HostType, property: KProperty<*>) : Unit`
```

- No extra instances are created, all information is already available

## Alternative names

- bindToInstance
- delegatedFrom

## Implementation

### Syntax

No new syntax is required.

### Resolution

- resolving the `attachTo` function happens similar to resolving `getValue`/`setValue` functions.
- if there are more than one `attachTo` functions available, overload resolution selects best candidate.
- `attachTo` that is not accessible is ignored.
- `attachTo` can be inline.
- `attachTo` can be generic, inference happens normally based on the type of host instance.
- Member extension `attachTo` on matching delegate type is preferred.
- No implicit `attachTo` operator from Java

### Diagnostics

Declaration site:
- `attachTo` should have Unit return type
- `attachTo` should have two parameters, second one should have `KProperty<*>` type

Use site (`by` keyword):
-

### Code generation (JVM)

At the end of the primary constructor, or each of secondary constructors:
For each property with discovered `attachTo` operator:
Call to `attachTo` is generated passing `this` and property metadata.

Member `attachTo`
```
name$delegate.attachTo(this, $$delegatedProperties[0])
```

Extension `attachTo`
```
FileKt.attachTo(name$delegate, this, $$delegatedProperties[0])
```

Member Extension `attachTo`
```
this.attachTo(name$delegate, this, $$delegatedProperties[0])
```

Notes:
- inline `attachTo` function is inlined normally in place of call

### Code generation (JS)

Similar to JVM.

### Debug information

- Breakpoint on a property should be triggered on `attachTo` call.
- Step over on a property should go to next property with `attachTo` generated
- Step into should go into respective `attachTo` method
- Step over at the end of constructor should jump to first property with `attachTo` generated
- Stacktrace navigation should show end of constructor as a place where `attachTo` was called

## Questions and Notes

- No special exception safety is required here. If `attachTo` throws, it aborts instance creation and propagates
  further out of constructor.
- Call happens at the end of a constructor, escaped `this` is not a big problem there since instance is initialised,
  but still can be cause for "virtual call in constructor" issue if further inherited types are yet to be constructed.
  However, if host instance is stored in a location accessible externally by other cores, it may also be problematic.
- Stacktrace navigation is ambiguous at the point of `attachTo` call, it can be calling constructor
  or respective property. Consider creating private function `attachTo$properties` that is called at the end of
  constructor, which in turn calls all required `attachTo` functions.
- Accessible operator function `attachTo` available on delegate instance cannot be called with host instance,
  but since entire call is optional, should it be warning, error or just silently ignoring it?
  Inaccessible `attachTo` functions are silently ignored without any diagnostics, which is not good as well.
  Also it cannot be invokable property, object or functional value, because those cannot be operators.


## Future evolution

Enable `attachTo` variant for interface delegation:
```
class Foo : List<String> by mutableListOf() {
    private lateinit var collection : MutableList<String>
    private operator fun MutableList<String>.attachTo(instance: Foo, type: KClass<*>) {
        collection = this
    }
}
```
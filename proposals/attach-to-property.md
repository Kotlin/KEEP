# Interception of delegated property binding

* **Type**: Design proposal
* **Author**: Ilya Ryzhenkov
* **Contributors**: Zalim Bashorov
* **Status**:
* **Prototype**: Was implemented and then disabled

## Summary

Recognize special operator function available for delegate object and call it right after it has been bound to a
property.

## Glossary

- "delegate instance": instance on which property's get/set accessors execute respective getValue/setValue calls.
- "host instance": instance containing delegated properties as members, or being extended with extension property.
- "binding": creating an association between property and delegate instance
- "attachTo": placeholder for the name of the operator function
- "delegation constructor" means any constructor that does not delegate to another constructor

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
- interceptDelegation

## Implementation

### Syntax

No new syntax is required.

### Resolution

- resolving the `attachTo` function happens similar to resolving `getValue`/`setValue` functions.
- if there are more than one `attachTo` functions available, overload resolution selects best candidate.
- `attachTo` that is not accessible is ignored, no accessibility error or warning is reported.
- `attachTo` can be inline.
- `attachTo` can be generic, inference happens normally based on the HostType and DelegateType.
- host instance can be null, if delegated property is top-level
- if `attachTo` function is defined in Java in DelegateType or its base type, it is not considered to be a
  suitable operator.

### Diagnostics

Declaration site:
- `attachTo` should have Unit return type
- `attachTo` should have two parameters, second one should have `KProperty<*>` type
- `attachTo` cannot be external
- `attachTo` cannot have vararg parameters
- `attachTo` cannot have parameters with default values

Use site (`by` keyword):
- `attachTo` must be operator

### Code generation (JVM)

At the end of the delegation constructor:
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
- When it happens that accessible operator function `attachTo` available on delegate instance cannot be called with
  host instance for some reason, compiler will keep silent about it, since entire call is optional.
  Should it be warning, error or just silently ignoring it?
  Inaccessible `attachTo` functions are silently ignored without any diagnostics, which is not good as well.
  Also it cannot be invokable property, object or functional value, because those cannot be operators.

## Alternatives

### createDelegate
Instead of calling `attachTo` on an expression, consider ability to hook into delegate creation process:
```
private val name$delegate = expression.createDelegate(this, ::foo)
val name: String
    get() = name$delegate.getValue(this, ::foo)
```

A function can be added to standard library that will make current code work:
```
operator inline fun <D> D.createDelegate(instance: Any?, property: KProperty<*>): D = this
```

Pros:
- better debugging experience, since there is now single point in bytecode associated with `by` keyword
- ability to provide custom delegation instance, get it from cache, etc
- potential to annotate `createDelegate` operator function with information to not store the returned value and use it
  on every call, like this:
```
val name: String
    get() = expression.createDelegate(this, ::foo).getValue(this, ::foo)
```
Of course, `createDelegate` can be inline and simply return its receiver, so no actual call is emitted:
```
val name: String
    get() = expression.getValue(this, ::foo)
```

Cons:
- coordination between getValue and createDelegate can be more complex, and not apparent from source code.
  In this variant `getValue` should be defined on an instance `createDelegate` returns, which is not there in source code.
- harder to optimize for cases when multiple properties delegate to a single instance

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
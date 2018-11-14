# Flexible delegated property convention

* **Type**: Design proposal
* **Author**: Dmitry Petrov
* **Contributors**:
* **Status**:
* **Prototype**: In progress

Discussion: https://github.com/Kotlin/KEEP/issues/168

## Problem description

Existing [delegated property convention](http://kotlinlang.org/docs/reference/delegated-properties.html)
requires corresponding operator functions to accept "this" object and a reflection object for a property itself.
For some delegated properties, including frequently used ones such as
[lazy](http://kotlinlang.org/docs/reference/delegated-properties.html#lazy) this is unneeded.
Also, it requires creating reflection objects for delegated properties even if they are unused in the actual
`provideDelegate`/`getValue`/`setValue` operator functions.

## Design details

Allow `provideDelegate`/`getValue`/`setValue` operator functions to be declared:
* without parameters:
```
operator fun <T> Lazy<T>.getValue(): T = value
```
* with "this" object parameter only:
```
operator fun Component.getValue(thisRef: Entity) = manager[thisRef]
```

When resolving delegated property convention calls for operator functions `provideDelegate`/`getValue`/`setValue`,
try to resolve a call:
1) without arguments;
2) with "this" argument only;
3) with both "this" and property reference arguments.


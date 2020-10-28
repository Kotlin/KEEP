# JDK dependent built-in classes

* **Type**: Design proposal
* **Author**: Denis Zharkov
* **Contributors**: Andrey Breslav, Ilya Gorbunov
* **Status**: Accepted
* **Prototype**: Implemented in Kotlin 1.1
* **Discussion**: https://github.com/Kotlin/KEEP/issues/30

## Goals

There are a some members of JDK classes that are not reflected in corresponding Kotlin built-in classes. For example `Collection.stream`, `Throwable.fillInStackTrace`, etc. The main rationale for this is that these members depend on types that we are not ready to make available on every platform we implement Kotlin for.  

This makes such members unavailable in Kotlin: for both calling and overriding *(this may be not impossible but rather hard)*. 

> Known workarounds
* We can cast to a relevant JDK class when calling a specific method (e.g. `(x as java.util.List<...>).stream()`,
* Formally it's even possible to override them (without *override* keyword). But it's still impossible to make a super-call

The goal of this proposal is to fix this and make the members available if they are available in the current JDK.

## Feedback

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/30).

## Known problematic members

* Related to Stream API, in `Collection`: `stream`, `parallelStream`, `spliterator`
* Map-specific: `compute`, `computeIf*`, `merge`
* Common container methods: `forEach`, `removeIf`
* `Throwable.fillInStackTrace`

## Possible solutions

### Extensions in stdlib

To enable calling such methods it's enough to just add extensions with similar signatures to stdlib.

###### Pros
* Looks like the easiest way to resolve the major part of requests
* Overriding is still possible (with the no-override-keyword-hack)

###### Cons
* It's necessary to maintain several stdlib jars for different targets. (*I believe it should happen at some point anyway.*)
* Overriding model looks very fragile, because no signature check happens
* Super-calls are not available

### Different built-ins declarations for each major JDK version

One of the obvious options is to have several versions of built-ins declarations, different for each major JDK version (and one for JS?).

###### Pros
* This solution seems to be much more reliable then the one about extensions
* We can explicitly a choose subset of members that will appear in each built-in class
* Also we can control types of those members:
  * nullability / mutability
  * use-site / declaration-site variance?

###### Cons
* It's still necessary to maintain different runtime jars (*anyway it looks necessary now*)
* There should also be several source versions of built-in declarations with some parts shared (*it can be achieved with same mechanism as one used in stdlib to specify that given declaration is for X target*)
* It's not very flexible in the sense that each new major JDK release requires additional manual work to be done (*I believe these rare events require some attention anyway*)
* If parameter `Collection.forEach` will have functional type, some additional work is required to emit right type in corresponding JVM method (`Consumer`) (*seems to be not very hard to implement, also one can explicitly declare SAM-adapter extension*)

#### Add members through synthetic supertypes
It's possible to achieve a similar effect through adding some synthetic supertypes containing necessary members (e.g.
`CollectionWithStream` with `stream`, `parallelStream`, `spliterator`) to the built-in classes. Similar idea is already used to provide `Serializable` supertype for each declaration. But further investigation is needed to check that nothing breaks because of new non-existing classes.

### Load additional methods from JDK classes on the class-path

###### Pros
* This option decreases the amount of manual work required for each JDK
* A lot of things will just work as they already do for common Java code (like SAM adapters and `Collection.forEach`)
* Also it's a pretty simple solution for problems with `Object.wait` and `Object.notify`
* Currently it seems to be the easiest solution to implement
* New JDK members appear without compiler/runtime update

###### Cons
* Uncontrollable set of members in built-ins
* A lot of flexible types
* **Important:** When something appears in built-in classes, and then it's removed or has a signature change, it could be declared as breaking change

## Chosen solution

The third solution (loading additional methods from JDK classes on the class-path) was chosen both because of it's flexibility and implementation simplicity.

### Visible/Hidden Method lists
Some methods in JDK classes are undesirable in Kotlin built-ins (e.g. a lot of String methods or List.sort(), because there are Kotlin analogues with better signatures already defined).

Also for Kotlin containers with mutable analogues it's unknown whether given additional method should belong to a mutable or read-only version of the interface.

Thus, to provide some level of control it's proposed to maintain predefined lists in the compiler describing what to do with listed members:
* *Visible list* defines the set of methods that are allowed to be visible at Kotlin call-sites,
* *Hidden list* defines the set of method that are prohibited from being visible at Kotlin call-sites. At the same time such methods are still available for overriding and `super`-qualified calls
* *Mutable methods list* defines the set of methods that should be added to a mutable interface. By default, such methods go to the read-only interface.

All methods not listed in *Visible* nor in *Hidden* lists are available for calls, but such usages should be marked with a warning because it may become unresolved in the next language version.

### Additional member method list
* Let `X` be some Kotlin built-in class
* If `X` is `Any`, then no additional members should be added
* Let `M` be a mutable version of `X` if it exists or `X` itself
* Let `Y` be a JDK analogue for `X` (e.g. `java.util.Set` for `kotlin.collections.Set`)
* Take every method that exists in member scope of `Y` and can not be override of any member in `M`
* Filter out methods with not public API visibility (nor public/protected) and ones that are deprecated
* Process methods in accordance with predefined *Method lists*
* Annotate non-invariant type parameters occurrences in unsafe variance position with `@UnsafeVariance` annotation (e.g. second parameter of `Map.getOrDefault`)
* Add resulting methods into `X`'s member scope

### Additional constructor list
Rules for constructors are similar to ones for methods, but they do not consider containers' mutability *(interfaces don't have constructors)* and instead of plain overridability, both-ways overridability is used (i.e. two constructors are considered to be the same if their signatures allow them to be overrides of each other).

### Static members
It's proposed not to import additional static members from JDK classes because most of them already exist in stdlib as extensions to companion objects of corresponding Kotlin classes and have refined signatures.

Also if even there is no stdlib analogue, it's always possible to call it through the JDK class itself.

### Different JDK versions in dependent modules
Let module `m1` uses JDK 6, while `m2` depends on `m1` and JDK8.

* If there is some class declared in `m1` implementing `Collection`, `m2` should see `stream` in it's member scope
* If there is some method in `m1` with return type of `Collection`, `m2` can use it's result as a receiver of `stream` again

i.e. each module X should "see" other modules as they have same JDK as X does.

### Flexible type enhancement
Some of additional members may have inappropriate nullability/mutability signatures with flexible types.

It's proposed to maintain another hard-coded list in the compiler describing enhanced signatures for some JDK methods.

### Backward compatibility of overrides
The problem is that it's impossible now to override an additional member so that it could be compiled with both language version (1.0/1.1).

```
abstract class A : Map<String, String> {
    override fun getOrDefault(key: Any?, defaultValue: String?): String? {
        return super.getOrDefault(key, defaultValue)
    }
}
```

In this example `override` keyword is necessary for JDK 8 with 1.1 language version, while 1.0 doesn't see such method in Map, thus `override` is an error for it.

A similar problem arises when switching between different JDK's.

Solution comes from Java: allow omitting `override` keyword when the only override comes from additional member in a built-in class.

### Open questions
* Should fields with public API visibility defined in JDK classes also be added to relevant Kotlin built-in classes?

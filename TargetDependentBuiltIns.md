# JDK dependent built-in classes

## Goals
There are a some members of JDK classes that are not reflected in
corresponding Kotlin built-in classes. For example `Collection.stream`,
`Throwable.fillInStackTrace`, etc.

This makes impossible to use such members in Kotlin, both as callees and as
overridden *(may be not impossible but rather hard)*.

**TODO: Keep in mind static members and constructors**

##### Known workarounds
* It's always possible to cast an instance to relevant JDK class when calling
specific method
* Formally it's even possible to override them (without *override* keyword).
But it's still impossible to perform super-call

Therefore, the main goal is to allow using them if they are available in
current JDK.

## Known problem members
* Stream API related in `Collection`: `stream`, `parallelStream`,
`spliterator`
* Map specific: `compute`, `computeIf*`, `merge`
* Common container methods: `forEach`, `removeIf`
* `Throwable.fillInStackTrace`

## Possible solutions
### Extensions in stdlib
Just to overcome using such methods on call-site it's enough just to add
extensions with similar signature in stdlib.

###### Pros
* Looks like the easiest way to resolve the major part of requests
* Overriding is still possible

###### Cons
* It's necessary to maintain several stdlib jars for different targets
 (*I believe it should happen at some point anyway*)
* Overriding model looks very fragile, because no signature check happens

### Different built-ins declarations for each major JDK version
One of the obvious options is to have several versions of built-ins
declarations, different for each major JDK version (and one for JS?).

###### Pros
* This solution seems to be much more reliable then the one about extensions
* We can explicitly choose subset of members that will appear in built-in
class
* Also we can control types of those members:
  * nullability / mutability
  * use-site / declaration-site variance?

###### Cons
* It's still necessary to maintain different runtime jars (**TODO: this is
not obvious statement**)
* There should also several sources versions of built-in declarations with
some parts shared (*it can be achieved with same mechanism as one used in
stdlib to specify that given declaration is for X target*)
* It's not very flexible in a sense that each new major JDK release requires
additional manual work to be done (*I believe these rare events require some
attention anyway*)
* If parameter `Collection.forEach` will have functional type, some additional
work is required to emit right type in corresponding JVM method (`Consumer`)
(*seems to be not very hard to implement, also one can explicitly declare
SAM-adapter extension*)

#### Add members through synthetic supertypes
It's possible to achieve similar effect with adding to built-in classes some
synthetic supertypes containing necessary members (e.g.
`CollectionWithStream` with `stream`, `parallelStream`, `spliterator`).
Similar idea is already used to provide `Serializable` supertype for each
declaration.
But further investigation is needed to check that nothing breaks because of
new non-existing classes.

### Load additional methods from JDK classes in class-path

###### Pros
* This option decrease amount of manual work required for each JDK
* A lot of things will just work as they already do for common Java code
(like SAM adapters and `Collection.forEach`)
* Also it's pretty simple solution for problems with overriding `Object.wait`
and `Object.notify`
* Currently it seems to be the easiest solution to implement
* New JDK members appear without compiler/runtime update

###### Cons
* Uncontrollable set of members in built-ins
* A lot of flexible types
* **Important:** When something appears in built-ins classes, and then it's
being removed or had signature change, it could be declared as breaking change

#### Solution sketch
* Take common members from built-in declaration
* Take common members from JDK prototype like it's subtype of built-in
* Add all members that are not overrides of built-in ones

## Chosen solution

Third solution (loading additional methods from JDK classes in class-path)
was chosen both because of it's flexibility and implementation simplicity.

### What members should we add

### Known problems
#### Different JDK versions in dependent modules
Let module `m1` uses JDK 6, while `m2` depends on `m1` and JDK8.

* If there is some class declared in `m1` implementing `Collection`, `m2`
should see `stream` in it's member scope
* If there is some method in `m1` with return type of `Collection`, `m2` can
use it's result as a receiver of `stream` again

i.e. each module X should "see" other modules as they have same JDK as X does.

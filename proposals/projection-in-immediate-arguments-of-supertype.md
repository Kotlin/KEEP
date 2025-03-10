# Projection in immediate arguments of supertype

* **Type**: Design proposal
* **Authors**: Sergei Grishchenko

# Summary

There is star projection in the Kotlin language, but for some reason it can't be used in the heritage clause.
Example:

```kotlin
interface Parent<T>

interface Child : Parent<*>
// ERROR: Projections are not allowed for immediate arguments of a supertype
```

In this KEEP, it is proposed to remove this restriction and allow to use star projection in supertype parameters.

# Motivation

## Unused type parameters

In projects with declarative UI next supertype for UI components can be introduced:

```kotlin
abstract class RComponent<P : RProps, S : RState>
```

Implementation of this component can look like this:

```kotlin
class SidebarProps : RProps()

class Sidebar : RComponent<SidebarProps, RState>()
```

In code base lots of components can be stateless, so they actually don't care what is an actual type of `S : RState`
from `RComponent`. But now it is required to write everywhere in code
base `class SomeComponent : RComponent<SomeComponentProps, RState>()`. If this KEEP is implemented, this declaration can
be reduced to `class SomeComponent : RComponent<SomeComponentProps, *>()`.

## Abstractions and type parameter bounds

[Karakum](https://github.com/karakum-team/karakum) project is a tool for
conversion [TypeScript](https://www.typescriptlang.org/) declarations to Kotlin external declarations.
In TypeScript there is mechanic
for [default type parameters](https://www.typescriptlang.org/docs/handbook/2/generics.html#generic-parameter-defaults).
So it makes possible to omit some generics if a code author doesn't want to pass them for some reason. Example:

```typescript
interface Bound {
}

interface Parent<T extends Bound = Bound> {
}

interface Child extends Parent { // Pay attention here, no type parameter passed
}
```

In Kotlin, these declarations will look like this:

```kotlin
interface Bound

interface Parent<T : Bound>

interface Child : Parent<Bound> // Pay attention here, the Bound type should be passed explicitly
```

So to generate valid Kotlin declarations from considered TypeScript declaration, such tools like Karakum need to analyze
declaration point of type parameter, extract its bounds and apply them in reference point. If this KEEP is implemented,
it could be just star projection:

```kotlin
interface Child : Parent<*> 
```

## Abstractions and variance

It is possible to declare some type for function with one parameter:

```kotlin
interface CallableFunction<in A, out R> {
    operator fun invoke(param: A): R
}
```

If code author wants to get the most abstract type of `CallableFunction` they can do it this way:

```kotlin
typealias AbstractCallableFunction = CallableFunction<*, *>
```

For example, such a type can be used as type parameter bound. But if code author wants to declare some abstract type
for `CallableFunction` but with meta-information (e.g., call history) it can't be done in so simple way:

```kotlin
interface FunctionWithHistory : CallableFunction<Nothing, Any?> {
    val calls: List<Pair<Any?, Any?>>
}
```

Here code author should be careful not to make a mistake with variance, because it is quite simple to declare something
like this:

```kotlin
interface FunctionWithHistory : CallableFunction<Any?, Any?> {
    //                                           ^^^    
    //                                           Mistake is here    
    val calls: List<Pair<Any?, Any?>>
}
```

It is incorrect because such invocation is possible:

```kotlin
val fn: FunctionWithHistory = TODO()

fn("Random string!!!")
```

But it was required to declare **abstract** type of `CallableFunction`, not concrete one, which can be invoked with
anything. With star projection, this task could be solved more easily and safely:

```kotlin
interface FunctionWithHistory : CallableFunction<*, *> {
    val calls: List<Pair<Any?, Any?>>
}
```

# Design

It is proposed to allow using of star projection in the heritage clause.

### Reference to type parameter in inheritors

Some signatures of a supertype that contains projected type parameter can be overridden in inheritor. If projected type
parameter has `in` of `out` variance it is trivial.

If type parameter has `out` variance, it can be replaced with upper bound in inheritor:

```kotlin
interface Bound

interface OutParent<out T : Bound> {
    val x: T
}

interface OutChild : OutParent<*> {
    override val x: Bound
}
```

If type parameter has `in` variance, it can be replaced with `Nothing` in inheritor:

```kotlin
interface InParent<in T> {
    fun y(value: T)
}

interface InChild : InParent<*> {
    override fun y(value: Nothing)
}
```

If a type parameter is invariant, it can be replaced with:

* upper bound in inheritor, if it is placed in `out` position
* `Nothing` in inheritor, if it is placed in `in` position
* `*` in inheritor if it is placed in invariant position

```kotlin
interface Bound

interface Parent<T : Bound> {
    val x: T // out position

    fun y(value: T) // in position

    var z: T // in-out position

    val list: MutableList<T> // in-out position
}

interface Child : Parent<*> {
    override val x: Bound

    override fun y(value: Nothing)

    override var z
        get(): Bound = TODO()
        set(value: Nothing) = TODO()

    override val list: MutableList<*>
}
```

# Related features in other languages

## Scala

There is no projection restriction in Scala:

```scala
trait Parent[T]:
  val x: T
  def y(value: T): Unit

trait Child extends Parent[_]
```

[Playground](https://scastie.scala-lang.org/npogTbnjSgiID2Zf2BHItg)

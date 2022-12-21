# Multi-field value classes KEEP draft

# Multi-field value classes

* **Type**: Design proposal
* **Author**: Evgeniy Zhelenskiy
* **Prototype**: Experimental in Kotlin/JVM since 1.8.20

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/340).

## Summary

Currently working [Inline Classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) allow making a performant identity-less wrapper for a value of some type. It is performant because it stores the inner value directly when possible and differentiates the types in compile type.

```kotlin
@JvmInline
value class Password(val value: String) {
    init {
        require(isValidPassword(value)) { "$value is not a valid password" }
    }
}
```

Using `Password` class instead of raw `String` helps you not to pass invalid password when validated one is expected and it helps not to pass it accidentally as `Username`. There is no overhead because password is represented as raw `String` in the compiled code and the runtime.

However, Inline classes are very limited: it may be useful to escape creating wrappers because of performance issues for classes with multiple fields too. Inline classes generalization is named [Value classes](https://github.com/Kotlin/KEEP/blob/master/notes/value-classes.md) (shallow immutable classes without identity). Since single field value classes are inline classes, which are already implemented, this KEEP describes multi-field value classes (MFVC).

Value classes are very similar to data classes but:

* They do not have component functions and `copy` methods.
* They can only have `val` constructor parameters, while data classes can have `var` parameters as well.
* It is forbidden to use identity of value classes.
* Customizing equals is not allowed until “typed equals” feature is released.

## Use cases

The typical example of MFVC usage is applications that create a lot of small effective structures without identity, e.g. geometry structures:

```kotlin
interface Figure
interface FigureWithPerimeter<T> : Figure {
    val perimeter: T
}
interface FigureWithArea<T> : Figure {
    val area: T
}

@JvmInline
value class DPoint(val x: Double, val y: Double): Figure

@JvmInline
value class DSegment(val p1: DPoint, p2: DPoint): FigureWithPerimeter<Double> {
    inline val middle: DPoint
        get() = DPoint((p1.x + p2.x) / 2.0, (p1.y + p2.y) / 2.0)
        
    val length: Double
        get() = sqrt((p1.x - p2.x).square() + (p1.y - p2.y).square())
    
    @TypedEquals
    fun equals(other: DSegment) =
        p1 == other.p1 && p2 == other.p2 || p1 == other.p2 && p2 == other.p1
    
    override hashCode() = p1.hashCode() + p2.hashCode()
    
    override val perimeter get() = length
}

@JvmInline
value class DRectangle(
    val topLeftVertex: DPoint, val bottomRightVertex: DPoint
) : FigureWithPerimeter<Double>, FigureWithArea<Double> {
    init {
        require(topLeftVertex.x <= bottomRightVertex.x) {
            "${topLeftVertex.x} > ${bottomRightVertex.x}"
        }
        require(topLeftVertex.y >= bottomRightVertex.y) {
            "${topLeftVertex.y} < ${bottomRightVertex.y}"
        }
    }

    inline val topRightVertex: DPoint
        get() = DPoint(bottomRightVertex.x, topLeftVertex.y)
    inline val bottomLeftVertex: DPoint
        get() = DPoint(topLeftVertex.x, bottomRightVertex.y)
    
    val width: Double
        get() = bottomRightVertex.x - topLeftVertex.x
    
    val hight: Double
        get() = topLeftVertex.y - bottomRightVertex.y
    
    override val area: Double
        get() = height * width
    
    override val perimeter: Double
        get() = 2 * (height + width)
}

fun Double.square() = this * this
```

Another use case is creating some custom primitives for graphics:

```kotlin
@JvmInline
value class Color(
    val alpha: UByte, val red: UByte, val green: UByte, val blue: UByte
)

@JvmInline
value class Gradient(val from: Color, val to: Color)

fun Gradient.colorAt(percentage: Double): Color {
     require(percentage in 0.0..100.0) { "Invalid percentage: $percentage" }
     val c1 = 100.0 - percentage
     val c2 = percentage
     return Color(
         alpha = (from.alpha.toDouble() * c1 + to.alpha.toDouble() * c2).toUByte(),
         red = (from.red.toDouble() * c1 + to.red.toDouble() * c2).toUByte(),
         green = (from.green.toDouble() * c1 + to.green.toDouble() * c2).toUByte(),
         blue = (from.blue.toDouble() * c1 + to.blue.toDouble() * c2).toUByte(),
     )
}
```

Value classes can also be used to make named tuples. And those can be used to return multiple values that are not connected between each other (the syntax of named tuples is not real, it does not exist yet):

```kotlin
fun <T: Comparable<T>> split(tree: Tree<T>, key: T): (left: Tree<T>, right: Tree<T>) {
    ...
    return (left = l, right = r)
    ...
}
```

## Description

Value classes are declared using soft keyword `value` as inline classes are. In Kotlin/JVM they also need `@JvmInline` annotation (several examples are above) before [Valhalla project](https://openjdk.org/projects/valhalla/) release. After the release, Valhalla value classes would become the default and expected way to create ones in Kotlin/JVM because they offer more optimizations than it is possible to do for pre-Valhalla value classes. So the latter are marked with annotations.

The reasons why the feature is implemented in Kotlin for pre-Valhalla JVMs too are:

* Valhalla is only JVM, while Kotlin is not.
* Valhalla would hardly be adopted in Android in any nearest future because Android Runtime is still compatible with JVM 1.8.
* Adoption of new JVM’s is quite slow.


As any other class, value classes can declare member (except inner classes), be a member of some other class, have generics, extensions and other language features.

MFVC are currently only supported in Kotlin/JVM so description below is related only to it.

## Current limitations

Limitations for MFVC are similar to the [limitations of inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#current-limitations) but primary constructors can have arbitrary number of parameters greater than 1 (case of 1 parameter is inline class).

## Java interoperability

Each variable of MFVC is stored as several variables corresponding to their state. The same is for fields. if some MFVC contains other MFVC fields, they are flattened recursively. It means that the actual representation of variable (field)  of `DSegment` type (declared above) in the code is 4 `Double` variables (fields) corresponding to the both coordinates of its points.

## Boxing

Each multi-field value class has its own wrapper that is represented as a usual class on JVM. This wrapper is needed to box values of value class types and use it where it's impossible to use unboxed values.

**Rules for boxing** are the following:

* **Boxed when used as other type.** It means that if one uses MFVC as nullable type, implemented interface or `Any`, the boxing will happen: `dRectangle.area` doesn’t require the boxing while `(dRectangle as FigureWithArea<Double>).area` does.
* **When used as type parameter (including function types).** It happens because type parameters are erased in JVM. It is the same as for inline classes. However, there are exception for the rule:
    * When type parameter upper bound is MFVC.
    * When type parameter is a type parameter of inline function or lambda.
* **Returning from not inline function.** It happens because it is impossible to pass several objects between frames in JVM. The exception is chain of MFVC getters: the compiler replaces `wrapper.segment.p1.x` with getter `wrapper.`getSegment-p1-x`()` instead of `wrapper.getSegment().getP1().getX()`. And this complex getter escapes boxing when getter implementation was initially just reading the field.
* **Secondary constructors.** As they are replaced with non-inline function.
* **Passing returned boxed value to another function that expects boxed parameter.** Intermediate value isn’t unboxed due to optimization because it would be boxed back otherwise.
* **Lateinit variables and properties.** Because nullable types are stored.
* **Between suspension points.** Continuation is generic, and while the generics are erased, the value can be stored only boxed there.

The compiler avoids boxing-unboxing chains when possible.

## Generics

MFVC’s with fields whose types are type arguments are allowed and they are mapped to the corresponding type parameters upper bounds as it is done for [Inline Classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#generic-inline-class-mapping).

```kotlin
@JvmInline
value class Generic<T, R>(val x: T, val y: R)
fun foo(g: Generic<Int, Long>) {}
```

These MFVC would also benefit from reified generics because lots of boxing would be eliminated:

```kotlin
@JvmInline value class Reified<reified T, reified R>(val x: T, val y: R)
fun foo(a: Reified<Int, Long>, b: Reified<Byte, Boolean>)
```

## Kotlinx.serialization

MFVC behave similarly to data classes during serialization and deserialization.

## Reflection

Kotlin reflection is not supported for MFVC yet but will be supported soon.

## Methods from `kotlin.Any`

As it is done for [inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#methods-from-kotlinany), compiler generates `toString`, `hashCode`, `equals` (but not component functions because positional destructuring is considered harmful). `toString` and `hashCode` can be customized by overriding, customization of equals will be possible by using feature “typed equals” (which takes the corresponding type as parameter instead of `Any?`) that is in development.

## Arrays of Value Classes

This paragraph is actual for both inline classes and multi-field value classes so they are generified to value classes here. Value arrays of value classes are reified classes which are not implemented yet. They also cause many other design questions because of necessity of value Lists etc. So there are no value arrays (`VArray'`s) for now and it is forbidden to use `vararg` for value classes. The same thing is already [written](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#arrays-of-inline-class-values) for Inline Classes.

## Expect/Actual MFVC

As other platforms are not supported yet, expect and actual modifiers do nothing for MFVC.

## Mangling

As for [Inline Classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#mangling), mangling is used to mitigate problems of overload resolution, non-public primary constructors and initialization blocks. MFVC methods, constructors and functions that take MFVC as parameters or receivers are also not accessible from Java. Secondary constructors with bodies are also enabled as experimental in 1.8.20 and stable in 1.9.0.

[Mangling rules](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#mangling-rules) are the same as to Inline Classes but function return type does not take part in computing hash because MFVC return type is not replaced with any other as it is done for Inline Classes.

## MFVC ABI (JVM)

It is not always possible to pass multi-field value class (MFVC) values to functions as unboxed. The limitations are listed above in [Boxing](#Boxing) section.

Also, there are strong limitations for returning MFVC from functions: it is forbidden to return multiple values from function in JVM. However, if we could never cope with it, all the optimizations would be useless: if a value was boxed once, there is no reason to escape boxing because using the existing box guarantees that no more boxes for this value are created.

Fortunately, there are several workarounds:

* Using shadow stacks. Benchmark showed that it is even slower than using a box that is rather cheap in modern JVMs.
* Using local variables for the calling site. It is only possible for inline functions as they are in the same stack frame.
* Getting result MFVC from arguments at the calling site. It is only possible for primary constructors of MFV. They are the main and initial source of all MFVCs.

Primary constructors do two things: field initialization and side effects. They can be separated and the latter is stored in so-called `/*static*/ fun constructor-impl(...): Unit` which takes field values as parameters and performs side effects. So MFVC primary constructor calls can be replaced with storing arguments in variables and then calling the `constructor-impl` function on them.

The `constructor-impl` must be generated even if it is empty because public ABI must not depend on the body inisides.

### (Un)boxing functions

Every MFVC can exist in 2 forms: boxed (single field, variable, parameter)  and unboxed (several fields, variables, parameters).

Conversion from unboxed one into boxed one is rather simple: calling static synthetic function `box-impl` with necessary type and value arguments. However, there are several points that make synthetic unboxing functions `unbox-impl-[...]` more complicated.

#### Choice between `unbox-impl` and getter.

```kotlin
@JvmInline
value class DPoint(val x: Double, val y: Double)

```

The class above has 2 ways to access `x`: `DPoint::getX` and `DPoint::unbox-impl-x`. So, when generating some code it is necessary to choose method via some convention.

Furthermore, `DPoint::getX` can be protected/internal or not even exist if `x` is private while it is necessary to be able to unbox publicly.

Although `DPoint::getX` is much more readable than `DPoint::unbox-impl-x`, there is no need to read generated code.

To sum up, when generating code, an unbox function should be always preferred to the getter.

#### Part of a regular class

Boxed MFVC can be not just a box itself but also a part of another regular class. It must also contain unbox functions for each flattened field whose property has default getter.

Their visibility must be not less than the original property visibility. As they are synthetic, they cannot be accessed from Java. As they might be used in kotlin-reflect, they should be public.

If the property had a setter it should be untied from the property as its flattened signature invalidates setters signature convention.

```kotlin
@JvmInline
value class DPoint(val x: Double, val y: Double)
class SomeClass(var value: DPoint)
```

`SomeClass.setValue: (DPoint) -> Unit` becomes `SomeClass.setValue-<hash>: (Double, Double) -> Unit`, it has 2 parameters.

#### Nested MFVC

Both MFVC instances within a regular class and independent ones can be nested.

```kotlin
@JvmInline
value class DPoint(val x: Double, val y: Double)
@JvmInline
value class DSegment(val p1: DPoint, val p2: DPoint)
class SomeClass(val segment: DSegment)
```

`DSegment` and `SomeClass` contain 4 `Double` fields.

With nested MFVC we may also want to access some intermediate properties (`DSegment.p1`, `DSegment.p2` here), not just leaves (`DSegment.p1.x`, `DSegment.p1.y`, `DSegment.p2.x`, `DSegment.p2.y` here).

A simple (not actually used) way for it is to generate `box-impl` invocations for each usage of the properties `p1` and `p2`:

```kotlin
DPoint.`box-impl`(x.`unbox-impl-p2-x`(), x.`unbox-impl-p2-y`()) // x is DSegment here
```

instead of

```kotlin
x.getP2()
```

This solution has a drawback that it generates a huge repeating code footprint.

We could use existing public getter in this case, but it would not exist if the property was more nested. When using public getters consequently (`x.getMfvc1().getMfvc2().getX()`) useless intermediate boxes would be generated.

```kotlin
@JvmInline
value class Mfvc2(val x: Int, val y: Int)

@JvmInline
value class Mfvc1(val mfvc2: Mfvc2, val y: Int)

data class Regular(val mfvc1: Mfvc1) {
    val mfvc1Custom: Mfvc1
        get() {
            println("42")
            return mfvc1
        }
}

val r: Regular = ...
r.mfvc1.mfvc2.x // is usual unbox can be used
r.mfvc1 // can use getter Regular::getMfvc1
r.mfvc1.mfvc2 // option #1: Mfvc2.`box-impl`(r.`getX-0`(), r.`getX-1`()) creates large code footprint and cannot be overridden (see below)
              // option #2: (x.getMfvc1()).getMfvc2() creates Mfvc1 box that can be escaped
```


So, it is necessary to create intermediate MFVC unbox methods for nested MFVC properties.

Furthermore, if they existed, they could be overridden to optimize nested access. The methods must also have default implementations that created intermediate boxes. This would allow such properties to be overridden in Java or by non-trivial getters. It would also help to save binary compatibility. As MFVC cannot have properties with custom getter and backing field simultaneously, the only cause of choice between effective and default implementation is whether the current regular class property getter is default or not. So, when it becomes default/not default subgetters of the current class change there implementation to effective/default and do not affect other classes.

Naming unbox methods becomes more difficult with nested MFVC. A node in MFVC tree can be identified by its path from the root (e.g. `["mfvc1", "mfvc2", "x"]` for the example above). Each of elements of a path can be identified by its name or by its index. Better readability is necessary in stack traces. So, names are chosen.

Actually called functions for the example above:

```kotlin
r.mfvc1.mfvc2.x // r.getMfvc1-mfvc2-x()
r.mfvc1 // r.getMfvc1()
r.mfvc1.mfvc2 // r.getMfvc1-mfvc2() which does NOT create intermediate Mfvc1
r.mfvc1Custom.mfvc2 // r.getMfvc1Custom-mfvc2() which DOES create intermediate Mfvc1
```

Visibility of intermediate unbox methods must be also public as they are synthetic (so not accessible from Java) but needed for kotlin-reflect.

Recompilation of a class that contains MFVC properties is only required if order and types of the MFVC leaves change.

The mentioned example with `DPoint`, `DSegment` and `SomeClass` contains the following unbox function declarations:

```kotlin
DPoint {
    fun `unbox-impl-x`(): Double
    fun `unbox-impl-y`(): Double
}

DSegment {
    fun `unbox-impl-p1`(): DPoint
    fun `unbox-impl-p1-x`(): Double
    fun `unbox-impl-p1-y`(): Double
    fun `unbox-impl-p2`(): DPoint
    fun `unbox-impl-p2-x`(): Double
    fun `unbox-impl-p2-y`(): Double
}

SomeClass {
    fun `getSegment-p1`(): DPoint
    fun `getSegment-p1-0`(): Double
    fun `getSegment-p1-1`(): Double
    fun `getSegment-p2`(): DPoint
    fun `getSegment-p2-0`(): Double
    fun `getSegment-p2-1`(): Double
    fun getSegment(): DSegment
}
```

`getSegment` is not mangled as MFVCs, returned from functions, are always boxed.

`f(x/*: DPoint*/)` can be compiled into `f(x.unbox-impl-x(), x.unbox-impl-y())` and `f(x.getX(), x.getY())`. If the `getX` or `getY` are not public necessity of unbox functions is obvious. But we actually always need them because if we call them instead of getters, we don't need to recompile existing calls when the name or visibility changes.

#### Optimization

When accessing a MFVC leaf from within the class it is better to use get-field directly than the unbox function as it is done for regular properties with backing fields. If the property is private, MFVC leaves will be also private and used only by kotlin-reflect.

#### Overriding example:

```kotlin
interface Interface {
    val point: DPoint
}

class DefaultGetter(override val point: DPoint)

class NonDefaultGetter(private val pointImpl: DPoint) {
     override val point: DPoint
         get() {
             println("Called")
             return pointImpl
         }
}

fun f(x: Interface) = x.point.x * x.point.y
```

It is compiled to

```kotlin
interface Interface {
    fun getPoint(): DPoint
    @JvmSynthetic 
    fun `getPoint-x`(): Double = getPoint().getX()
    @JvmSynthetic
    fun `getPoint-y`(): Double = getPoint().getY()
}

class DefaultGetter {
    ...
    override fun getPoint(): DPoint {
        return DPoint.`box-impl`(`getPoint-x`(), `getPoint-y`())
    }
    
    @JvmSynthetic
    override fun `getPoint-x`(): Double {
        return point-x // field
    }
    
    @JvmSynthetic
    override fun `getPoint-y`(): Double {
        return point-y // field
    }
}

class NonDefaultGetter(private val pointImpl: DPoint) {
    override fun getPoint(): DPoint {
        println("Called")
        return getPointImpl()
    }
    
    @JvmSynthetic
    override fun `getPoint-0`(): Double {
        return getPoint().`unbox-impl-x`()
    }
    
    @JvmSynthetic
    override fun `getPoint-1`(): Double {
        return getPoint().`unbox-impl-y`()
    }
}

fun f(x: Interface) = x.`getPoint-x`() * x.`getPoint-y`()
```

### Annotations

Each property and its parts (field, getter, setter) can be annotated. MFVC properties are not exceptions.

Annotating MFVC leaves does not cause any problems because they are not transformed while annotating other MFVC properties are.

Annotating non-leaves is currently forbidden due to lack of use-cases.

### Expression flattening

As described above, the default representation of MFVC is flattened, so it is necessary to flatten existing expressions.
The easiest way is to take a box and then flatten it. but we should escape boxing when possible.

* If the expression is a primary constructor call, we flatten each of subfields recursively to variables and then call `constructor-impl` function. The flattened expressions are the read accesses to the variables.
* If the expression already has some flattened representation, we can just use it. For example, it could be some getter call that can be replaced with multiple unboxing functions calls.
* If the expression is some block of statements, we flatten the last one recursively as it is the result of the block.
* `when`, `if` and `try` expressions results are also flattened.
* Otherwise, we should actually receive a boxed expression and then unbox it with unbox methods or field getting.


If we flatten not to variables themselves but we just want expressions, we may escape using variables when possible (keeping evaluation order the same):

```kotlin
*// Before:
val a = 2
val b = 3
val c = b + 1
// [a, b, c]

// After:
val a = 2
val b = 3
// [a, b, b + 1]*
```

If the flattened representation is used to reset (not initialize) a variable/field, new value representation must be stored in variables. If an exception is thrown during evaluation of the new value, the old value must be kept. The tail of flattened expressions that cannot throw an error is inlined (variables are not created).

If boxing result is not needed, boxing is not called.

As the variables are used outside the expression they are used for the first time, there declaration is placed in the most inner block that contains all the usages. It is done to save variable slots. Also, if it is possible to combine declaration and initialization, it is done to remove default initialization.

#### Examples

##### Example 1

```kotlin
println("Some random actions that do not use the variables")
val a = DSegment(DPoint(0.0, 1.0), makePoint(2.0, 3.0))
```

```kotlin
println("Some random actions that do not use the variables")
val `a-p1-x`: Double = 0.0
val `a-p1-y`: Double = 1.0
DPoint.`constructor-impl`(`a-p1-x`, `a-p1-y`)
val tmp0 = makePoint(2.0, 3.0)
val `a-p2-x`: Double = tmp0.`unbox-impl-x`()
val `a-p2-y`: Double = tmp0.`unbox-impl-y`()
DSegment.`constructor-impl`(`a-p1-x`, `a-p1-y`, `a-p2-x`, `a-p2-y`)
```

##### Example 2

```kotlin
var p = DPoint(doubleProducer(), 5.0) // no temporary variables
p = DPoint(doubleProducer(), 6.0) // temporary variables for the first argument
```

```kotlin
var `p-x` = doubleProducer()
var `p-y` = 5.0
DPoint.`constructor-impl`(`p-x`, `p-y`)
val tmp0 = doubleProducer()
DPoint.`constructor-impl`(tmp0, 6.0)
`p-x` = tmp0
`p-y` = 6.0
```

##### Example 3

```kotlin
fun f() {
    DPoint(100.0, 200.0)
}
```

```kotlin
fun f() {
    val `tmp0-x` = 100.0
    val `tmp0-y` = 200.0
    DPoint.`constructor-impl`(`tmp0-x`, `tmp0-y`)
    // no box-impl call here
}
```

##### Example 4

```kotlin
val x = if (condition) {
    DPoint(1.0, 2.0)
    DPoint(3.0, 4.0)
} else {
    DPoint(5.0, 6.0)
    DPoint(7.0, 8.0)
}
```

```kotlin
val `x-x`: Double
val `x-y`: Double
if (condition) {
    val `tmp0-x`: Double = 1.0
    val `tmp0-y`: Double = 2.0
    DPoint.`constructor-impl`(`tmp0-x`, `tmp0-y`)
    `x-x` = 3.0
    `x-y` = 4.0
    DPoint.`constructor-impl`(`x-x`, `x-y`)
} else {
    val `tmp0-x`: Double = 5.0
    val `tmp0-y`: Double = 6.0
    DPoint.`constructor-impl`(`tmp0-x`, `tmp0-y`)
    `x-x` = 7.0
    `x-y` = 8.0
    DPoint.`constructor-impl`(`x-x`, `x-y`)
}
```

### Function flattening

If the function has parameters to be flattened, it is replaced with mangled function which is made static if it is a MFVC method replacement.

If necessary, bridges are generated.

#### Equality

There is an additional function `equals-impl0` for equals replacement that takes both parameters flattened. It is called instead of `==` when possible. If the right argument is MFVC and the left one is literal null, right argument is not boxed. Also, if right argument is unboxed `MFVC`, and the left one is `MFVC?`, the compiler generates an if expression, that checks the left for being null and then uses `equals-impl0` if it is not.

#### String concatenation

Concatenating string and MFVC produces effective `toString` replacement function calls to escape boxing.

## Further development

* Compose support
* Kotlin Reflection
* Better debugger support
* Project Valhalla support
* Other backends + multiplatform support
* Reified value classes, VArrays, value interfaces and other JVM-related features from the [design notes](https://github.com/Kotlin/KEEP/blob/master/notes/value-classes.md).
* Frontend features from the [design notes](https://github.com/Kotlin/KEEP/blob/master/notes/value-classes.md).

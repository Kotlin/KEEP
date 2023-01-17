# Multi-field value classes

* **Type**: Design proposal
* **Author**: Evgeniy Zhelenskiy
* **Prototype**: Experimental in Kotlin/JVM since 1.8.20

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/340).

## Description

### Inline Classes

Current [Inline Classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) are performant identity-less type-safe wrappers with human-readable `toString` for a value of some type. They are represented with raw (unboxed) value at runtime when possible, thus eliminating need for wrapper allocations.

```kotlin
@JvmInline
value class Password(val value: String) {
    init {
        require(isValidPassword(value)) { "$value is not a valid password" }
    }
}
```

Using `Password` class instead of raw `String` helps you not to pass invalid password when validated one is expected and it helps not to pass it accidentally as `Username`. There is no overhead because password is represented as raw `String` in the compiled code and the runtime.

### Multi-field value classes

However, Inline classes are very limited: it may be useful to escape creating wrappers because of performance issues for classes with multiple fields too. Inline classes generalization is named [Value classes](https://github.com/Kotlin/KEEP/blob/master/notes/value-classes.md) (shallow immutable classes without identity).

As well, as for Inline classes, one of the main aims of MFVC is compiler optimizations that are possible due to the lack of fixed identity. Unfortunately, there are several limitations for optimization for JVM target described below in the [Boxing](#boxing) section.

Multi-field value classes are the next part of the Value classes feature after Inline classes.

### Value classes

Value classes are effective shallow-immutable data classes without identity, copy functions and component functions.

Shallow immutability is chosen to make it possible to implement internally mutable classes with immutable public API such as using property of type `Lazy<T>`: `value class SomeData(val x: Int, private val lazyY: Lazy<Int>) { val y get() = lazyY.value }`. However, there are domains where deep immutability is required, but allowing such requirement is currently outside the scope of the document.

Value classes do not have `copy` methods due to their inconvenience for usage to mutate:
* They are verbose and hard to understand when combined with constructions such as `if`s, loops.
* They are unsuitable for nested structures: `a = a.copy(b = a.b.copy(c = f(a.b.c + 1)))` instead of `a.b.c = f(a.b.c)` for mutable classes.
* They cannot be used for operators: `a = a.copy(b = a.b + 2)` instead of `a.b += 2` for mutable classes.

Not yet implemented lens syntax is going to be used for convenient mutating mutable fields/variables of immutable object types. Other applications of the shallow-copying function `copy` do not exist for shallow-immutable value classes.

Value classes do not have component functions as they are used for positional destructuring which is considered harmful because of exposing of the internal state and order of the fields. It will be replaced with not yet implemented named destructuring syntax.

Customizing `equals` is not allowed until “typed equals” feature is released. Customizing existing `equals` would lead to unavoidable boxing of the parameter.

### Further development: `VArray`s and reification

One of other important steps of Value classes feature are Value arrays (`VArray`s) and reified classes and reified not-inline functions. They significantly extend applicability and optimizations of Value classes, **but they require MFVC to be already implemented** for that. Their importance is caused by frequent usage of containers that store and use stored values: they are currently handle value classes as boxed.

`VArray` is the fundamental container and first reified class. `VArray`s solve the problem of effective arrays (with invariant type parameter) without necessity of declaring manual [`UIntArray`](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/unsigned/src/kotlin/UIntArray.kt#L15) for each inline class type. It also generifies existing `IntArray`, `LongArray`, `BooleanArray` etc. with scalable general `VArray<T>` which maps to `VArray<Int>`, `VArray<Long>`, `VArray<Boolean>` correspondingly.

Having `VArray`s and reified functions also allows to write generic functions, extensions that operate on generic `VArray<T>`:
```kotlin
operator fun <reified T> VArray<T>.plus(other: VArray<T>) =
    VArray<T>(this.size + other.size) { if (it < this.size) this[it] else other[it - this.size] }
```

Having reified functions for generics of primitive or value class types helps to escape boxing. For example, being used within non-reified generic function, `x/*int*/ + y/*int*/` becomes `Integer.valueOf(x/*Integer*/.value, y/*Integer*/.value)`. So, it is better to generate specializations for such classes if the type parameters are marked as reified. However, reifying all parameters as it is done in C++ and Rust leads to huge code footprint and exponential growth of compilation time while it gives no significant performance boost for reference types.

`VArray` is not the only container or class the users may want to reify, thus reification of other classes shall also be possible in the future.

### Project Valhalla

There is [Project Valhalla](https://openjdk.org/projects/valhalla/) for JVM that suggests Value Classes that are similar to ones this proposal is about. It solves the same problems but on the runtime side (efficient user-defined identity-less objects, arrays of them, classes with them as fields, generic specialization). While the compiler is limited by JVM bytecode restrictions (cannot return several values from function) and uses the open world model (does not know all code that will be executed and usages of all classes, functions), the Valhalla-capable runtime is not limited and has closed world model (does know all executing code and usages of all classes, functions). It gives a great advantage in performing optimizations to the Valhalla-based Value classes.

Value classes without `@JvmInline` annotation will be supported and mapped to Valhalla Value classes after its release.

However, these classes require usage of the capable runtime, which is impracticable condition for Android, where runtime is still compatible with JVM 1.8 that was released in 2014. That is why Kotlin/JVM compiler needs to provide the functionality independent of Valhalla Project. Nevertheless, the latter solution is preferred, so simple migration to it must be possible.

## Use cases
*Since single field value classes are inline classes, which are already implemented, this KEEP describes further only multi-field value classes (MFVC).*

The typical example of MFVC usage is applications that create a lot of small effective structures without identity, e.g. geometry structures, complex numbers, rational numbers:

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
    
    override fun hashCode() = p1.hashCode() + p2.hashCode()
    
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

### Existing applications

As Kotlin language is widespread, there are existing pieces in code-bases, where user suffers from lack of the MFVC and further features.

Links to graphics-related classes are shown without class names, because they are very specific and say to the framework.

#### [Jetpack Compose](https://developer.android.com/jetpack/compose)

Jetpack Compose is Android’s recommended modern toolkit for building native UI. It simplifies and accelerates UI development on Android.

User interface requires frames to be rendered fast to keep proper FPS, thus the smallest possible number of intermediate allocations that may trigger GC and freeze the screen.

Jetpack Compose uses lots of graphics primitives and several optimization strategies for them:

If a graphical primitive contains single `Int`, `Long`, `Float`, `Double` inside, it is reasonably an inline class.

If it contains two `Float`s or two `Int`s, it uses inline class with `Long` storage: [1](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/Dp.kt#L261), [2](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/Dp.kt#L379), [3](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/IntOffset.kt#L46), [4](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/IntSize.kt#L39), [5](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/TextUnit.kt#L79), [6](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/Constraints.kt#L54), [7](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-geometry/src/commonMain/kotlin/androidx/compose/ui/geometry/Size.kt#L42), [8](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-geometry/src/commonMain/kotlin/androidx/compose/ui/geometry/Offset.kt#L61), [9](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-geometry/src/commonMain/kotlin/androidx/compose/ui/geometry/CornerRadius.kt#L44), [10](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/Color.kt#L115), [11](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/colorspace/ColorModel.kt#L32), [12](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/TextRange.kt#L46). Some other classes could also benefit from the strategy: [1](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/colorspace/WhitePoint.kt#L26), [2](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/style/LineHeightStyle.kt#L39), [3](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/style/TextGeometricTransform.kt#L33), [4](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/style/TextIndent.kt#L32).

Such solution is applicable only for 2 `Float`s, 2 `Int`s, 2..4 `Short`s, 2..4 `Char`s, 2..8 `Byte`s, 2..64 `Boolean`s and thus is very limited. It offers more optimization than MFVC can offer because it has only one underlying value that can be successfully returned from any function. However, it breaks one of the design goals of JVM - preventing [word tearing](https://docs.oracle.com/javase/specs/jls/se8/html/jls-17.html#jls-17.6), thus it is not done by default for MFVC. Nevertheless, if users find this packing strategy useful, it is possible to add (not existing now) annotation that will do it automatically.

Jetpack Compose has examples when this limitation is broken, and usual boxing `data class` (or its manual analogue without component/copy functions) is used: [1](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/Dp.kt#L520), [2](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-unit/src/commonMain/kotlin/androidx/compose/ui/unit/IntRect.kt#L33), [3](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-geometry/src/commonMain/kotlin/androidx/compose/ui/geometry/Rect.kt#L32), [4](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-geometry/src/commonMain/kotlin/androidx/compose/ui/geometry/RoundRect.kt#L29), [5](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/Shadow.kt#L29), [6](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/PixelMap.kt#L36), [7](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/colorspace/TransferParameters.kt#L35), [8](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/Placeholder.kt#L37).

[MutableRect](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-geometry/src/commonMain/kotlin/androidx/compose/ui/geometry/MutableRect.kt#L32) is mutable to allow convenient syntax and reuse the wrapper. If it was MFVC, the first problem would have been solved by lens syntax, the second one would not exist at all.

[Vertices](https://github.com/JetBrains/androidx/blob/458f93113905b5753b999df784aeb92dab51cf65/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/Vertices.kt#L23) suffers from lack of `VArray`s of Value classes and imitates them manually.

### [KorGE](https://korge.org/)

KorGE is a modern multiplatform game engine for Kotlin.

Game engines also require small number of allocations as Compose. Furthermore, it manipulates geometry objects and collects of them so it must do it effectively. Actually it does it by manual specialization for these hand-made classes.

Specializations: [VectorArrayList](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/VectorArrayList.kt#L33), [IntSegmentSet](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/segment/IntSegmentSet.kt#L14), [PointArrayList](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/PointArrayList.kt#L96), [PointIntArrayList](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/PointArrayList.kt#L295), [TriangleList](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/triangle/Triangle.kt#L214).

Graphics: [1](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/length/Length.kt#L199), [2](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/length/Length.kt#L215), [3](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/length/Length.kt#L217), [4](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Size.kt#L29), [5](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Size.kt#L82), [6](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Margin.kt#L33)\*, [7](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Margin.kt#L74)\*, [8](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Anchor.kt#L6).

Mathematics: [Quaternion](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Quaternion.kt#L13)\*, [Rectangle](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Rectangle.kt#L13)\*, [RectangleInt](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Rectangle.kt#L364), [MutableRectCorners](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/RectCorners.kt#L35)\*, [Ray3D](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Ray3D.kt#L3), [Ray](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Ray.kt#L3), [Point](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Point.kt#L111), [PointInt](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Point.kt#L361), [Matrix](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Matrix.kt#L18)\*, [Line](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Line.kt#L13), [LineIntersection](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Line.kt#L181), [EulerRotation](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/EulerRotation.kt#L3)\*, [Circle](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Circle.kt#L12), [Bounding volume hierarchy](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/AABB3D.kt#L7), [Edge](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/vector/Edge.kt#L19)\*, [Edge](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/triangle/Edge.kt#L9), [SegmentInt](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/trapezoid/SegmentInt.kt#L5), [TrapezoidInt](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/trapezoid/TrapezoidInt.kt#L17), [TriangleInt](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/trapezoid/TriangleInt.kt#L3), [DoubleRangeExclusive](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/range/OpenRange.kt#L5).

Pools with reusable boxed wrappers: [PointPool](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/PointPool.kt#L13), [MatrixPool](https://github.com/korlibs/korge/blob/109d582eb8b6810403f406ad28872b480bb9fb8b/korma/src/commonMain/kotlin/com/soywiz/korma/geom/Matrix.kt#L27).

\* These classes are mutable to reuse boxes and for convenient mutating syntax as in [Jetpack Compose](#jetpack-compose). They still can be replaced with MFVC.

### Other projects
* Time: [Timed value](https://github.com/JetBrains/kotlin/blob/30788566012c571aa1d3590912468d1ebe59983d/libraries/stdlib/src/kotlin/time/measureTime.kt#L68), [PickedTime](https://github.com/yuriykulikov/AlarmClock/blob/90f55fd2f36c0aded535226fda27dbb6bfa77227/app/src/main/java/com/better/alarm/presenter/PickedTime.kt#L3);
* Gaming: [Labyrinth](https://github.com/JetBrains/kotlin/blob/dd051c155640ff4b83f1cf1c730bd21387a49453/kotlin-native/performance/ring/src/main/kotlin/org/jetbrains/ring/CoordinatesSolver.kt#L26), [Chess](https://github.com/Kotlin-Polytech/KotlinAsFirst-Coursera/blob/39cc67d9b7a74b7f519ffd2e99dc1fd217a5a83f/src/lesson8/task2/Chess.kt#L9);
* [Graphics](https://github.com/CCBlueX/LiquidBounce/blob/f716f371b940e11445ddf791e8254d285ef955dc/src/main/kotlin/net/ccbluex/liquidbounce/render/engine/RenderTasks.kt#L124);
* [Tuples](https://github.com/airbnb/mavericks/blob/main/mvrx-common/src/main/java/com/airbnb/mvrx/MavericksTuples.kt);
* Metadata: [1](https://github.com/lingxiaoplus/BiliBili/blob/5a9aa28e06602f1d51e41553fb15d8914a75aa78/app/src/main/java/com/bilibili/lingxiao/play/model/VideoData.kt#L19), [2](https://github.com/wix/Detox/blob/085de8c4b8d6f691107aacd053176732faa13bfb/detox/android/detox/src/full/java/com/wix/detox/reactnative/ReactNativeInfo.kt#L6);
* Geometry: [1](https://github.com/RedApparat/Fotoapparat/blob/9454f3e4d1d222799049b00f3d84b274c3e02ee6/fotoapparat/src/main/java/io/fotoapparat/hardware/metering/PointF.kt#L6), [2](https://github.com/indy256/codelibrary/blob/master/kotlin/ConvexHull.kt), [3](https://github.com/Ramotion/navigation-toolbar-android/blob/4706c15209dff67e3f5ce191211fbb87dedfd13d/navigation-toolbar/src/main/kotlin/com/ramotion/navigationtoolbar/HeaderLayoutManager.kt#L70), [4](https://github.com/CCBlueX/LiquidBounce/blob/f716f371b940e11445ddf791e8254d285ef955dc/src/main/kotlin/net/ccbluex/liquidbounce/utils/aiming/RotationData.kt#L26), [5](https://github.com/data2viz/data2viz/blob/e549fa3bb4fd9ea73c27e261e38077b4cd819202/hierarchy/src/commonMain/kotlin/io/data2viz/hierarchy/pack/Enclose.kt#L24);
* [Credentials](https://github.com/http4k/http4k/blob/f7d6cf5d2e88bc66864dd180bc2faef203597e2d/http4k-core/src/main/kotlin/org/http4k/core/Credentials.kt#L3);
* Ranges: [Stdlib](https://github.com/JetBrains/kotlin/blob/30788566012c571aa1d3590912468d1ebe59983d/libraries/stdlib/src/kotlin/ranges/PrimitiveRanges.kt#L54), [Custom1](https://github.com/KronicDeth/intellij-elixir/blob/f4663b802aae61c833550feba199b9e0a06fba15/src/org/elixir_lang/NameArityRange.kt#L8), [Custom2](https://github.com/EmmyLua/IntelliJ-EmmyLua/blob/72e5fd324c3d31da99469ed08a48ae9656793f78/src/main/java/com/tang/intellij/lua/debugger/remote/commands/DefaultCommand.kt#L61);
*  [Graphs](https://github.com/FunkyMuse/KAHelpers/blob/main/dataStructuresAndAlgorithms/src/main/java/com/crazylegend/datastructuresandalgorithms/graphs/Edge.kt);
* Other domain objects: [1](https://github.com/minecraft-dev/MinecraftDev/blob/3641836795b4ddffe79d7755de2852d5c55fa012/src/main/kotlin/platform/mcp/fabricloom/FabricLoomData.kt#L26), [2](https://github.com/spotify/heroic/blob/9a021a7a4acf643012cd0b2bfe8f59e7b6cfda89/heroic-component/src/main/java/com/spotify/heroic/metric/Point.kt#L24), [3](https://github.com/aws/aws-toolkit-jetbrains/blob/8dcb5bbc256dd30e2bd21db355300dfbcc5ba938/jetbrains-core/src/software/aws/toolkits/jetbrains/services/ecr/resources/EcrResources.kt#L28), [4](https://github.com/ethereum-lists/tokens/blob/9d8c86ab33fee60f54e77fb5001a2fbe32dea413/src/main/kotlin/org/ethereum/lists/tokens/model/Support.kt#L3), [5](https://github.com/microsoft/fluentui-android/blob/809bff2bff91cf20b821937f9a049dfe0b419f6b/fluentui_transients/src/main/java/com/microsoft/fluentui/tooltip/Tooltip.kt#L459).

## Syntax

Value classes are declared using soft keyword `value` as inline classes are. In Kotlin/JVM they also need `@JvmInline` annotation (several examples are above) before [Valhalla project](https://openjdk.org/projects/valhalla/) release. After the release, Valhalla value classes would become the default and expected way to create ones in Kotlin/JVM because they offer more optimizations than it is possible to do for pre-Valhalla value classes. So the latter are marked with annotations in Kotlin/JVM. Adding or removing the annotation will break binary compatibility.

As any other class, value classes can declare member (except inner classes), be a member of some other class, have generics, extensions and other language features.

MFVC are currently only supported in Kotlin/JVM so description below is related only to it.

## Current limitations

Limitations for MFVC are similar to the [limitations of inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#current-limitations) but primary constructors can have arbitrary number of parameters greater than 1 (case of 1 parameter is inline class).

## Java interoperability

Each variable of MFVC is stored as several variables corresponding to their state. The same is for fields. if some MFVC contains other MFVC fields, they are flattened recursively. It means that the actual representation of variable (field)  of `DSegment` type (declared above) in the code is 4 `Double` variables (fields) corresponding to the both coordinates of its points.

## Boxing

Each multi-field value class has its own wrapper that is represented as a usual class on JVM. This wrapper is needed to box values of value class types and use it where it's impossible to use unboxed values.

**Rules for boxing** are the following:

* **Boxed when used as other type.** It means that if one uses MFVC as nullable type, implemented interface or `Any`, the boxing will happen: `dRectangle.area` does not require the boxing while `(dRectangle as FigureWithArea<Double>).area` does.
* **When used as type parameter (including function types).** It happens because type parameters are erased in JVM. It is the same as for inline classes. However, there are exceptions to the rule:
    * When type parameter upper bound is MFVC.
    * When type parameter is a type parameter of inline function or inline lambda.
* **Returning from not inline function.** *(This point does not apply to primary constructors of MFVC).* It happens because it is impossible to pass several objects between frames in JVM. The exception is a chain of MFVC getters: the compiler replaces `wrapper.segment.p1.x` with getter ``wrapper.`getSegment-p1-x`()`` instead of `wrapper.getSegment().getP1().getX()`. And this complex getter escapes boxing when getter implementation was initially just reading the field.
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

As it is done for [inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#methods-from-kotlinany), the compiler generates `toString`, `hashCode`, `equals` (but not component functions as described [above](#value-classes)). `toString` and `hashCode` can be customized by overriding, customization of equals will be possible by using feature “typed equals” (which takes the corresponding type as parameter instead of `Any?`) that is in development.

## Arrays of Value Classes

This paragraph is actual for both inline classes and multi-field value classes, so they are generified to value classes here. Value arrays of value classes are reified classes which are not implemented yet. They also cause many other design questions because of necessity of value Lists etc. So there are no [value arrays](#further-development--varray-s-and-reification) (`VArray`s) for now and it is forbidden to use `vararg` for value classes. The same thing is already [written](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#arrays-of-inline-class-values) for Inline Classes.

## Expect/Actual MFVC

As other platforms are not supported yet, expect and actual modifiers do nothing for MFVC.

## Mangling

As for [Inline Classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#mangling), mangling is used to mitigate problems of overload resolution, non-public primary constructors and initialization blocks. MFVC methods, constructors and functions that take MFVC as parameters or receivers are also not accessible from Java. Secondary constructors with bodies are also enabled as experimental in 1.8.20 and stable in 1.9.0.

[Mangling rules](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md#mangling-rules) are the same as to Inline Classes but function return type does not take part in computing hash because MFVC return type is not replaced with any other as it is done for Inline Classes.

## MFVC ABI (JVM)

It is not always possible to pass MFVC values to functions as unboxed. The limitations are listed above in [Boxing](#Boxing) section.

Also, there are strong limitations for returning MFVC from functions: it is forbidden to return multiple values from function in JVM. However, if we could never cope with it, all the optimizations would be useless: if a value was boxed once, then there is no reason to escape boxing because using the existing box guarantees that no more boxes for this value are created.

Fortunately, there are several workarounds:

* Using shadow stacks. A benchmark showed that it is even slower than using a box that is rather cheap in modern JVMs.
* Using local variables for the calling site. It is only possible for inline functions as they are in the same stack frame.
* Getting result MFVC from arguments at the calling site. It is only possible for primary constructors of MFVC. They are the main and initial source of all MFVCs.

Primary constructors do two things: field initialization and side effects. They can be separated and the latter is stored in so-called `/*static*/ fun constructor-impl(...): Unit` which takes field values as parameters and performs side effects. So MFVC primary constructor calls can be replaced with storing arguments in variables and then calling the `constructor-impl` function on them.

The `constructor-impl` calls must be generated even if it is empty because public ABI must not depend on the body insides.

### (Un)boxing functions

Every MFVC can exist in 2 forms: boxed (single field, variable, parameter)  and unboxed (several fields, variables, parameters).

Conversion from unboxed one into boxed one is rather simple: calling static synthetic function `box-impl` with necessary type and value arguments. However, there are several points that make synthetic unboxing functions `unbox-impl-[...]` more complicated.

#### The choice between `unbox-impl` and getter.

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

Furthermore, if they existed, they could be overridden to optimize nested access. The methods must also have default implementations that create intermediate boxes. This would allow such properties to be overridden in Java or by non-trivial getters. It would also help to save binary compatibility. As MFVC cannot have properties with custom getter and backing field simultaneously, the only cause of choice between effective and default implementation is whether the current regular class property getter is default or not. So, when it becomes default/not default subgetters of the current class change there implementation to effective/default and do not affect other classes.

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

`f(x/*: DPoint*/)` can be compiled into `f(x.unbox-impl-x(), x.unbox-impl-y())` and `f(x.getX(), x.getY())`. If the `getX` or `getY` are not public, necessity of unbox functions is obvious. But we actually always need them because if we call them instead of getters, we do not need to recompile existing calls when the name or visibility changes.

#### Optimization

When accessing a MFVC leaf from within the class, it is better to use get-field directly than the unbox function as it is done for regular properties with backing fields. If the property is private, MFVC leaves will be also private and used only by kotlin-reflect.

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

Each property and its parts (field, getter, setter) can be annotated. MFVC properties are not the exceptions.

Annotating MFVC leaves does not cause any problems because they are not transformed while annotating other MFVC properties are.

Annotating non-leaves is currently forbidden due to lack of use-cases.

### Expression flattening

As described above, the default representation of MFVC is flattened, so it is necessary to flatten existing expressions.
The easiest way is to take a box and then flatten it, but we should escape boxing when possible.

* If the expression is a primary constructor call, we flatten each of subfields recursively to variables and then call `constructor-impl` function on them. The flattened expressions are the read accesses to the variables.
* If the expression already has some flattened representation, we can just use it. For example, it could be some getter call that can be replaced with multiple unboxing functions calls.
* If the expression is some block of statements, we flatten the last one recursively as it is the result of the block.
* `when`, `if` and `try` expressions results are also flattened.
* Otherwise, we should actually receive a boxed expression and then unbox it with unbox methods or field getting.


If we flatten not to variables themselves, but we just want expressions, we may escape using variables when possible (keeping evaluation order the same):

```kotlin
// Before:
val a = 2
val b = 3
val c = b + 1
// [a, b, c]

// After:
val a = 2
val b = 3
// [a, b, b + 1]*
```

If the flattened representation is used to reset (not initialize) a variable/field, the new value representation must be firstly stored in temporary variables. If an exception is thrown during evaluation of the new value, the old value must be kept. The tail of flattened expressions that cannot throw an error is inlined (variables are not created).

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

If there is a user-defined typed equals, no `equals-impl0` is generated, and the user function is renamed to `equals-impl0`.

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

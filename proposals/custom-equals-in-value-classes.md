# Custom equals in value classes

* **Type**: Design proposal
* **Authors**: Vladislav Grechko
* **Status**: Prototype implemented
* **Issue**: [KT-24874](https://youtrack.jetbrains.com/issue/KT-24874/)

## Summary

Allow overriding `equals` from `Any` and declaring so-called *typed equals* in value classes.

## Motivation and use cases

### Collections

Suppose we want to implement wrapper for an object that implements `List` interface. We want this wrapper to be
light-weight and avoid allocation of wrapper object. This might be achieved by declaring following inline class:

```Kotlin
@JvmInline
value class SingletonList<T>(val element: T) : List<T> {
    ...
}
```

Since different implementations of `List` must be equal if they contain same elements, we have to
customize `equals(other: Any?)` in `SingletonList`.

> Note that passing instance of `SingletonList` as argument to non-inline function that takes `List`  will lead to
> boxing and allocation a new object. For inline functions, boxing can be avoided when IR inliner is implemented.
> Related issue:
> [KT-40391](https://youtrack.jetbrains.com/issue/KT-40391/Inline-function-boxes-inline-class-type-argument)
>

### Units of measurement

Inline classes are well suited to represent units of measurement. It might be useful to establish custom equivalence
relation for them.

```Kotlin
@JvmInline
value class Degrees(val value: Double)


fun foo() {
    // customize equality relation to make the set contain a single value
    val uniqueAngles = setOf(Degrees(90.0), Degrees(-270.0))
}
```

```Kotlin
@JvmInline
value class Minutes(val value: Int)

@JvmInline
value class Seconds(val value: Int)


fun foo() {
    // customize equality relation to make the set contain a single value
    val uniqueTimeIntervals = setOf(Minutes(1), Seconds(60))
}
```

## Proposal

### Typed equals

Simply overriding `equals(other: Any?)` in value class would require boxing of the right-hand side operand of
every `'=='` comparison:

```Kotlin
@JvmInline
value class Degrees(val value: Double) {
    override fun equals(other: Any?): Boolean {
        ...
    }
}

fun foo() {
    // have to box to pass to equals(other: Any?)
    println(Degrees(0) == Degrees(45))
}
```

That is why in [KEEP for inline classes](https://github.com/Kotlin/KEEP/blob/master/proposals/inline-classes.md) a new
concept of **typed equals** was proposed:

```Kotlin
@JvmInline
@AllowTypedEquals
value class Degrees(val value: Double) {
    @TypedEquals
    fun equals(other: Degrees) = (value - other.value) % 360.0 == 0.0
}
```

Type of `other` will be unboxed during compilation and passing argument will not require boxing.

More precise, we define typed equals as a function such that:

* Has name `"equals"`
* Declared as a member function of value class
* Has a single parameter which type is a star-projection of enclosing class
    * We will elaborate on this restriction in the next section
* Returns `Boolean`
* Annotated with `@TypedEquals` annotation

Typed equals, as well as equals from `Any`, must define an equivalence relation, i.e. be symmetric, reflexive,
transitive and consistent.

We forbid typed equals to have type parameters.

> From now, we will be calling equals from `Any` *untyped* equals, in contrast, to *typed* one.

### Interaction of typed and untyped equals

We need to establish consistency of typed and untyped equals, i.e. the following property:
> For any `x`, `y` such that `x`, `y` refer instances of an inline class, it must be true
> that `x.equals(y) == (x as Any).equals(y)`

We propose the following:

* If only typed equals is declared:
    * Generate consistent untyped one. Implementation of untyped equals will be trying to cast the argument to
      corresponding value class type and passing to typed equals if succeeded.
* If only untyped equals is declared:
    * Generate consistent typed one. Implementation of typed equals will be boxing argument and passing it to untyped
      equals. Implement special diagnostics to warn programmers about negative performance impact of boxing in
      auto-generated code
* If both equals methods are declared:
    * Consider programmer responsible for their consistency

Note, that in the first case on JVM level we will be casting argument to the raw type of value class, thus we will know
nothing about its type arguments. We emphasize this fact by requiring the parameter of typed equals to be
star-projection of the value class.

### Custom `hashCode`

It is obvious that the ability of overriding `equals` makes us allow overriding of `hashCode`.

### `@TypedEquals` and `@AllowTypedEquals` annotations

Customization of equals in value classes is currently considered as an experimental feature. Thus, we want to force
user to `@OptIn` before using it. In previous section we introduced `@TypedEquals` annotation, which serves as a marker
for typed equals function. Unfortunately, simply annotating `@TypedEquals` with `@RequireOptIn` will not work, as
typed equals may be invoked implicitly:

```Kotlin
@JvmInline
value class IC(val x: Int) {
    @TypedEquals
    fun equals(other: IC) = ...
}

fun foo() {
    setOf(IC(1), IC(2)) // compiles without @OptIn, but invokes typed equals internally
}
```

We propose to add new annotation `@AllowTypedEquals`. This annotation is applicable for value classes only and any value
class that declares typed equal must be annotated by `@AllowTypedEquals`:

```Kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@RequiresOptIn
annotation class AllowTypedEquals
```

```Kotlin
@JvmInline
@AllowTypedEquals
value class IC(val x: Int) {
    @TypedEquals
    fun equals(other: IC) = ...
}

@OptIn(AllowTypedEquals::class) // usage of value class IC requires OptIn
fun foo() {
    setOf(IC(1), IC(2))
}
```

## Other questions

### Inline classes over inline classes

Suppose we have an inline class `B` over inline class `A` and `A` declares typed equals. In this case,
default-generated equals in `B` will be comparing it underlying values using custom equals of `A`.

### Interaction with Valhalla

In its current prototype version `20-valhalla+20-75` Valhalla supports customization of equals and hashCode in values
classes, thus, we will
be able to compile Kotlin value classes with custom equals to Valhalla values classes.

```Java
value class ValueClass {
    int x;

    public ValueClass(int x) {
        this.x = x;
    }

    @Override
    public boolean equals(Object other) {
        return true;
    }

    @Override
    public int hashCode() {
        return 42;
    }
}

class Main {
    public static void main(String[] args) {
        ValueClass zeroVal = new ValueClass(0);
        ValueClass oneVal = new ValueClass(1);

        Object zeroObj = zeroVal;

        System.out.println(zeroVal.equals(oneVal));        // true
        System.out.println(zeroObj.equals(oneVal));        // true
        System.out.println(zeroObj.hashCode());            // 42
    }
}
```

## Possible future improvements

### Typed equals in non-value classes

Introducing typed equals in non-value classes could help to get rid of boilerplate code:

```Kotlin
class A {
    fun equals(other: A) {
        //do check
        ...
    }
}
```

instead of:

```Kotlin
class A {
    override fun equals(other: Any?) {
        if (other is A) {
            //do check
            ...
        }
        return false
    }
}
```

### Resolving references to typed equals in IDE

Resolving `'=='` operation to typed equals in IDE could let user navigate from invocation to declaration.
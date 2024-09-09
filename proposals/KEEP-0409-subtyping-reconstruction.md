# Subtyping reconstruction

* **Type**: Design proposal
* **Authors**: Roman Venediktov, Daniil Berezun
* **Contributors**: Marat Akhin, Mikhail Zarechenskiy, Alejandro Serrano Mena
* **Discussion:** [KEEP-409](https://github.com/Kotlin/KEEP/issues/409)
* **Status**: In discussion
* **Related YouTrack issue**: [KT-18510](https://youtrack.jetbrains.com/issue/KT-18510/GADT-style-smart-casts)

## Table of Contents

- [Introduction](#introduction)
  - [Motivation](#motivation)
  - [Real-world examples](#real-world-examples)
  - [Broad overview](#broad-overview)
    - [Constrained subclasses](#constrained-subclasses)
    - [Scope of the constraints](#scope-of-the-constraints)
    - [Outline](#outline)
    - [Limitations](#limitations)
  - [More advanced use-cases](#more-advanced-use-cases)
    - [Run-time subtyping evidence](#run-time-subtyping-evidence)
    - [Type-safe extensions](#type-safe-extensions)
    - [Type-level properties](#type-level-properties)
  - [Relation to the Generalized Algebraic Data Types](#relation-to-the-generalized-algebraic-data-types)
- [Bounds inference algorithm](#bounds-inference-algorithm)
  - [Generation of constraints](#generation-of-constraints)
    - [Examples of constraint generation](#examples-of-constraint-generation)
      - [Simple example](#simple-example)
      - [Example with several lowest common classifiers](#example-with-several-lowest-common-classifiers)
    - [Special cases](#special-cases)
    - [How does this compare to the Scala's algorithm?](#how-does-this-compare-to-the-scalas-algorithm)
  - [Constraint resolution](#constraint-resolution)
    - [Examples of constraint resolution](#examples-of-constraint-resolution)
      - [Resolution of type variable constraint](#resolution-of-type-variable-constraint)
      - [Resolution with intersection types](#resolution-with-intersection-types)
      - [Resolution with flexible types](#resolution-with-flexible-types)
      - [Resolution with nullable types](#resolution-with-nullable-types)
    - [Special cases](#special-cases-1)
      - [Type projections](#type-projections)
- [Adding subtyping reconstruction to Kotlin's flow-sensitive type system](#adding-subtyping-reconstruction-to-kotlins-flow-sensitive-type-system)
  - [New type of control-flow graph statements](#new-type-of-control-flow-graph-statements)
  - [Changes to the type intersection statement collection](#changes-to-the-type-intersection-statement-collection)
  - [Flow-sensitive type checker state](#flow-sensitive-type-checker-state)
  - [Examples](#examples)
- [When subtyping reconstruction is not enough](#when-subtyping-reconstruction-is-not-enough)
  - [When there is no expected type](#when-there-is-no-expected-type)
  - [Can we fix these problems?](#can-we-fix-these-problems)
- [Feature interaction](#feature-interaction)
  - [Bare types](#bare-types)
  - [Builder inference](#builder-inference)
  - [Smart casts](#smart-casts)
  - [Unreachable code detection](#unreachable-code-detection)
  - [Exhaustiveness checking](#exhaustiveness-checking)
- [Possible breaking changes](#possible-breaking-changes)
  - [Incompatibility with unsafe casts](#incompatibility-with-unsafe-casts)
  - [Overload resolution changes](#overload-resolution-changes)
- [Addendum](#addendum)
  - [Prototype](#prototype)
  - [References](#references)

## Introduction

### Motivation

Kotlin aims to reduce the number of casts using smart-cast techniques.
However, sometimes they are not powerful enough to eliminate certain redundant unsafe casts.
Let's consider implementing a simple arithmetic expression evaluator in Kotlin:

```Kotlin
sealed interface Expr<out V>
class IntLit(val value: Int) : Expr<Int>
class Add(val left: Expr<Int>, val right: Expr<Int>) : Expr<Int>
class Tuple<X, Y>(val x: Expr<X>, val y: Expr<Y>) : Expr<Pair<X, Y>>
// ...

fun <T> eval(e: Expr<T>): T = when (e) {
    is IntLit -> e.value
    // ...
}
```

In this example, we introduce a type parameter `V` for the `Expr` interface,
representing the type of the value that the expression evaluates to.
This design ensures that any expression created is correct by construction.
For instance, it becomes impossible to create an expression that uses a binary operator on tuples:

```Kotlin
val e = Add(Tuple(IntLit(1), IntLit(2)), IntLit(3))
//          ^ 
// Argument type mismatch: actual type is 'Tuple<X, Y>', but 'Expr<kotlin.Int>' was expected.
```

However, when we try to evaluate this expression, even for the simplest `IntLit` case,
the compiler produces a type mismatch error: `Type mismatch. Required: T, Found: Int`
To resolve this error, we need to write an explicit unsafe cast:
`is IntLit -> e.value as T` and add a `@Suppress("UNCHECKED_CAST")` annotation, 
resulting in a more verbose and less safe code:

```Kotlin
@Suppress("UNCHECKED_CAST")
fun <T> eval(e: Expr<T>): T = when (e) {
    is IntLit -> e.value as T
    // ...
}
```

In this particular case,
the cast is indeed safe because if this function was called with `IntLit`,
then `T` has to be a supertype of `Int` (due to the covariance of `Expr`).
Therefore, it is safe to pass an expression of type `Int` where `T` is expected.

We can ensure that the cast is safe using the constraints encoded in the declaration of `IntLit`.
By inheriting from `Expr<Int>`, `IntLit` constrains parameter `V` of `Expr` to be `Int`.
But currently, 
the compiler does not incorporate this information into the type system, leading to redundant unchecked casts.
The goal of this KEEP is to improve smart-casts by leveraging the constraints from class declarations.

Because this process reconstructs subtyping relation for generic types, 
it is called **subtyping reconstruction**.

### Real-world examples

Several real-world examples of similar cases could be found in Kotlin compiler:

1. [GitHub link](https://github.com/JetBrains/kotlin/blob/242c1cf5f0814fbe9df02b4b85a63298b30b4b67/core/reflection.jvm/src/kotlin/reflect/jvm/internal/calls/ValueClassAwareCaller.kt#L45)
2. [GitHub link](https://github.com/JetBrains/kotlin/blob/242c1cf5f0814fbe9df02b4b85a63298b30b4b67/compiler/resolution/src/org/jetbrains/kotlin/resolve/calls/KotlinCallResolver.kt#L165)
3. [GitHub link](https://github.com/JetBrains/kotlin/blob/242c1cf5f0814fbe9df02b4b85a63298b30b4b67/compiler/fir/providers/src/org/jetbrains/kotlin/fir/types/TypeUtils.kt#L211-L214)
4. [GitHub link](https://github.com/JetBrains/kotlin/blob/242c1cf5f0814fbe9df02b4b85a63298b30b4b67/jps/jps-plugin/src/org/jetbrains/kotlin/jps/model/ProjectSettings.kt#L72-L75)

Let's review the first example in more detail.

```Kotlin
// interface Caller<out M>
// class BoundStatic(...): Caller<ReflectMethod>
// class BoundStaticMultiFieldValueClass(...): Caller<ReflectMethod>

// oldCaller: Caller<M>

private val caller: Caller<M> = if (oldCaller is CallerImpl.Method.BoundStatic) {
    //                              From this ^ check, we know that oldCaller is of class BoundStatic.
    //                              Thus, we know that if this check passed, then M is a supertype of ReflectMethod
    val receiverType = (descriptor.extensionReceiverParameter ?: descriptor.dispatchReceiverParameter)?.type
    if (receiverType != null && receiverType.needsMfvcFlattening()) {
        val unboxMethods = getMfvcUnboxMethods(receiverType.asSimpleType())!!
        val boundReceiverComponents = unboxMethods.map { it.invoke(oldCaller.boundReceiver) }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        CallerImpl.Method.BoundStaticMultiFieldValueClass(oldCaller.member, boundReceiverComponents) as Caller<M>
        // This cast ^ is always safe,
        // because M :> ReflectMethod => Caller<M> :> Caller<ReflectMethod> :> BoundStaticMultiFieldValueClass
    } else {
        oldCaller
    }
} else {
    oldCaller
}
```

> For more advanced examples, see [More advanced use-cases](#more-advanced-use-cases) section.

### Broad overview

#### Constrained subclasses

As mentioned, this feature aims to incorporate constraints from class declarations into the type system.
To demonstrate some possible constraints, let's consider the following examples:

```Kotlin
interface Box<V>
interface ColoredBox<T> : Box<T> {
    val color: String
}
interface IntBox : Box<Int>
interface AggregateBox<T> : Box<List<T>>
interface SerializableBox<T : Serializable> : Box<T>, Serializable
```

In the first example, `ColoredBox` does not introduce any constraints on parameter `V` of class `Box`,
it just introduces a new field, leaving `V` equal to unconstrained `T`.
Thus, this class is not interesting for subtyping reconstruction.

In the other examples `IntBox`, `AggregateBox`, and `SerializableBox`, the constraints are introduced.
They are either an equality constraint (`V =:= Int` for `IntBox`), 
or shape constraint (`V =:= List<T>` for `AggregateBox`),
or subtype constraint (`V :> Serializable` for `SerializableBox`).
All of them are useful for subtyping reconstruction.

> The notation `V =:= T` represents the constraint that `V` is equal to `T`, and `V :> T` represents the constraint that `V` is a supertype of `T`.

There also may be indirect constraints. 
For example, `IntBox` constrains not only `Box`, but also `ColoredBox`, 
meaning if there is a value that is an instance of `IntBox` and `ColoredBox`, 
it is required for type parameter of `ColoredBox` to be equal to `Int`.

#### Scope of the constraints

So now we know what is constraint encoded in the class declarations.
But when could they be used for type-checking?

If we have some abstract value of type `Box<T>`,
we do not have any constraints as interface `Box` does not introduce any.
It may be `ColoredBox` with any type parameter.

But once we introspected this value to be of class `IntBox`, 
we are able to introduce constraint `T =:= Int` in a same way as we do in [Motivation](#motivation) section.

So once we have information that our value of type `Box<T>` is also of type `IntBox`,
we are able to use additional constraints for type-checking.
Thus, the compiler has to extract this initial information from the code.
Luckily, statements "value `V` of type `X` in expression `E` is also of type `Y`" 
are already collected by the compiler for smart-casts.
The only consequence the compiler makes now is that "value `V` in expression `E` may be used as a value of type `Y`".
But in cases type `Y` constrains some type parameters in type `X` resulting in a set of constrains `C`,
we are able to make additional conclusion "in expression `E` we are able to use constraints `C` for type checking".
(Note: we do not limit ourselves to value `V` or types `X` and `Y` in this conclusion. 
Constraints `C` could be used for any value in the expression `E`.)

Such an approach allows us to type-check all the following examples:

```Kotlin
sealed interface Expr<out T>
class IntLit(val i: Int) : Expr<Int>

fun <T> evalWhen(e: Expr<T>): T = when (e) {
    is IntLit -> e.i
}

fun <T> evalIs(e: Expr<T>): T {
    if (e is IntLit) {
        return e.i
    }
    TODO()
}

fun <T> evalNotIs(e: Expr<T>): T {
    if (e !is IntLit) {
        TODO()
    }
    return e.i
}

fun <T> evalEquality(e: Expr<T>, e2: ExprIntLit): T {
    if (e === e2) {
        return e.i
    }
    TODO()
}
```

In all these examples, 
compiler is able to figure out that "In expression `e.i`, value `e` of type `Expr<T>` is also of type `IntLit`".
Which could be translated to "In expression `e.i` we are able to use `{T :> Int}` for type checking".
Which allows us to return value of type `Int`, where `T` is expected.

Henceforth, statements like "value `V` of type `X` is also of type `Y`" 
will be denoted as **type intersection statements**, referring to type intersection `X & Y`.
Constraints will be called **bounds for generic types** 
as they are of the form `TypeVariable =? Type`, where `=?` is a subtyping or equality operation.

#### Outline

Summarizing, the overall structure of the approach is as follows:

1. We utilize **type intersection statements** collected by the existing mechanism for smart-casts.
2. We apply the bounds inference algorithm to these statements, resulting in new **bounds for generic types**.
3. We propagate **bounds for generic types** through the control-flow graph.
4. We supply the type checker in the corresponding nodes with the **bounds for generic types** to improve type checking.

#### Limitations

The main limitation of the feature is that it uses constraints only for type checking.
Thus, the expression without an expected type will be typed without bounds for generic types.
For example:

```Kotlin
fun <T> foo(e: Expr<T>, t: T) {
    val v1 = when (e) {
        is IntLit -> e.i
        else -> t
    }
    val v2: T = when (e) {
        is IntLit -> e.i
        else -> t
    }
}
```

Both initializers, for `v1` and `v2`, will be successfully typed.
However, despite being identical, `v1` will be typed as `Any?` compared to `T` for `v2`.
This is because `v1` has no expected type 
and compiler behaves as if there are no constraints and infer `Any?` as it does now.
While for `v2` we have an expected type `T` and compiler uses constraint `{T :> Int}` for type checking.

This limitation is because type inference with scoped bounds for generic types is a very complex problem.
More details are in [When Subtyping Reconstruction Is Not Enough](#when-subtyping-reconstruction-is-not-enough) section.

### More advanced use-cases

Use-cases of this feature are not limited to simple improvements for existing code, 
but it also allows some new type-safe patterns that were not possible before.

#### Run-time subtyping evidence

One simple, but useful in some domains (e.g., type-safe DSLs for data queries), benefit of subtyping reconstruction is the ability to express, store and use type relations such as $A <: B$ or $A =:= B$ in your code.

```Kotlin
sealed interface EqT<A, B>{
  class Evidence<X> : EqT<X, X>
}

sealed interface SubT<A, B>{
  class Evidence<A, B : A> : SubT<A, B>
}
```

It may be used like this:

```Kotlin
class Data<S, D> {
  val value: D = ...
  // ...
  fun coerceJsonToString(ev: EqT<D, JsonNode>): Data<S, String> =
    when (ev) {
      is EqT.Evidence<*> -> Data(this.value.convertJsonNodeToString())
      // Inferred: D =:= JsonNode
      //   e.g. it is type-safe to do this.value.convertJsonNodeToString()
    }
  // ...
}
```

Without the subtyping reconstruction, you could have the following solutions to this problem.

* Write or generate separate implementations for each possible combination of types.
  This will require much additional code with significant code duplication.
* Pass the type relation properties as other values (e.g., as a boolean, enum value or a string).
  In this case, we have to explicitly write error-prone casts in every place where we rely on the type relations.

All these solutions are less type-safe and more prone to human errors.

#### Type-safe extensions

Let's say we have a library for chart drawing.

```Kotlin
sealed interface Chart<A> {
    fun draw(chartData: A)
}
class PieChart : Chart<PieData>
class XYChart : Chart<XYData>
```

If we would like to write an extension which draws pie charts differently, it may look like this:

```Kotlin
fun <A> Chart<A>.customDraw(chartData: A): Unit =
  when (this) {
    is PieChart -> {
      val pieData = chartData as PieData
      ... // modify
      draw(pieData)
    }
    else -> draw(chartData)
  }
```

The programmer has to explicitly cast `chartData` to `PieData`, however, this could be inferred by subtyping reconstruction.
The resulting code becomes more type-safe and less verbose.

```Kotlin
fun <A> Chart<A>.customDraw(chartData: A): Unit =
  when (this) {
    is PieChart -> {
      // We know that `A =:= PieData` here
      //   aka we know that chartData is PieData
      ... // modify
      draw(chartData)
    }
    else -> draw(chartData)
  }
```

#### Type-level properties

Another use-case of the subtyping reconstruction is type-level properties.
By creating synthetic classes representing not data but properties of the data
and embedding relations between them into a type system,
it is possible to make the type checker validate the correctness of the code regarding this property.

One of the most simple examples is the type-level guarantee of balance for an AVL tree:
[implementation](https://github.com/e2e4b6b7/AVLTyped/blob/master/src/main/kotlin/Main.kt).

While this technique is too complex and quite alien to the Kotlin programming style,
some simplified form of it could be useful in some cases.

### Relation to the Generalized Algebraic Data Types

This feature is quite similar to the 
Generalized Algebraic Data Types (GADT) feature in functional programming languages.
More precisely, it is almost the same as GADT or Subtyping Reconstruction in Scala 3.
But there are some differences.

It is more general than GADT in functional languages 
as it looks for constraints not only in flat and direct hierarchies (like data in Haskell).
In our case we use an approach for object-oriented languages which use indirect and multi-level constraints.

Also, GADTs are usually used in conjunction with pattern matching, 
but we do not limit subtyping reconstruction to any kind of statement, like `when` expression, 
rather support it in Kotlin-y style in a data-flow-sensitive way like smart casts.
It allows widening the scope of the feature 
by introducing constraints in more cases and propagating them into a wider scope.

Another small difference to Subtyping Reconstruction in Scala is that inheritance in Kotlin is more restrictive, 
allowing more specialized algorithm, which covers more cases.
More details are further in the [How does this compare to the Scala's algorithm?](#how-does-this-compare-to-the-scalas-algorithm) section.

## Bounds inference algorithm

The subtyping reconstruction is based on the bounds inference algorithm.
The purpose of this algorithm is to infer bounds for generic types based on the extended subtype information available from Kotlin's flow-sensitive type system.

As an input, the algorithm accepts a set of types `T1 & T2 & ...` for a specific value which this value definitely has at a specific program point.
As an output, it infers additional bounds for generic types used in types `T1 & T2 & ...`.
The algorithm consists of two parts, generation of subtyping and equality constraints, and their resolution.

### Generation of constraints

The goal of the first part of the algorithm is to generate intermediate constraints.
They are intermediate because their form is not `TypeVariable =? Type`, but rather `Type =? Type`.
These intermediate constraints are simplified into the form of bounds for generic types in the second part of the algorithm.

The pseudocode for the constraint generation is shown below.

```Kotlin
fun generateConstraintsFor(supertypes: List<Type>) {
  val assumptions = List<Assumption>()
  val projections = supertypes.map {                       // Stage 2
    createRealTypeProjection(it.classifier)
  }
  supertypes.zip(projections).forEach {                    // Stage 3
    supertype, projection ->
      assumptions.add(projection <: supertype)
  }
  projections.cartesianProduct().forEach { proj1, proj2 -> // Stage 4
    val lowestCommonClassifiers: List<Classifier> = lcc(
      proj1.classifier, proj2.classifier)
    lowestCommonClassifiers.forEach { classifier ->
      val upcastedProj1 = upcast(proj1, classifier)
      val upcastedProj2 = upcast(proj2, classifier)
      assumptions.add(upcastedProj1 =:= upcastedProj2)     // Stage 5
    }
  }
}
```

The algorithm is based on two fundamental observations.
The first one is the existence of any object's real type that was assigned to the object during its creation.
The second is that this real type is a subtype of the inferred or ascribed type in the scope (type of the argument, type of the local variable, etc.) and the checked type (type of the classifier on the right side of the `is` or `as` expression, etc.).

> The second observation can sometimes be violated by the use of unsafe / unchecked casts.
> We do not consider such scenarios, leaving them, as usual, the responsibility of the user.

The input for the algorithm is a list of known supertypes for some value, which come from the compile-time information in the code (type declarations, type checks, etc.).

Stage 1: If these supertypes contain intersection types, we consider each of the intersection type components as a separate supertype.

Stage 2: Next we create so-called "type projections" of the real type on the classifiers of these supertypes.
A type projection of a real type is also a real type, which is this supertype's classifier type parameterized with fresh type arguments (if any).
It can be viewed as a placeholder for the actual run-time type of the value.

Stage 3: Then we record the constraint that these type projections are subtypes of their corresponding supertypes, as the actual run-time type of the value will be a subtype of its compile-time checked supertype.

Stage 4: After that we iterate over all lowest common classifiers for each possible pair of the type projections.
The lowest common classifiers are determined with respect to the inheritance relation.
Then we upcast both projections on all of those classifiers.
Upcasting is the process of "lifting" the subtype to its supertype along the inheritance hierarchy together with the substitution of the type parameters.

Stage 5: Finally, we generate strict equalities between these upcasted projections, as they represent supertypes of the same type (real type of the analyzed value) w.r.t. the same classifier.
This is justified by the following paragraph of the Kotlin specification.

> The transitive closure S∗(T) of the set of type supertypes S(T : \(S_1\), . . . , \(S_m\)) = {\(S_1\), . . . , \(S_m\)} ∪ S(\(S_1\)) ∪ . . . ∪ S(\(S_m\))
> is consistent, i.e., does not contain two parameterized types with different type arguments.

#### Examples of constraint generation

##### Simple example

Let's review the algorithm on the following example.

```Kotlin
interface Expr<out T>
class ExprInt(val value: Int) : Expr<Int>

fun <T> eval(e: Expr<T>): T =
  when (e) {
    is ExprInt -> e.value
  }
```

As an input for the algorithm, we have two supertypes of the value `e`: `ExprInt` and `Expr<T>`.

The flow of the algorithm is shown in the following diagram.

![](https://raw.githubusercontent.com/danyaberezun/KEEPs/GADT-keep/images/example_simple.png)

The upper part of the diagram shows the final generated constraints.
Let's follow the algorithm step by step.

* Stage 1. Not applicable.
* Stage 2. Do type projection on `Expr<T>` to get `Expr<R>` (where `R` is a fresh type variable) and on `ExprInt` to get `ExprInt`.
* Stage 3. Record the constraints $Expr\langle T\rangle :> Expr\langle R\rangle$ and $ExprInt :> ExprInt$.
* Stage 4. For the lowest common classifier `Expr`, upcast the corresponding projections and get types $Expr\langle R\rangle$ and $Expr\langle Int\rangle$.
* Stage 5. Record the constraint $Expr\langle R\rangle =:= Expr\langle Int\rangle$.

##### Example with several lowest common classifiers

Let's review the algorithm on the following, more complicated example.

```Kotlin
interface Expr<T>
interface Tag<T>
interface TExpr<E, T> : Expr<E>, Tag<T>
class ExprInt(val value: Int) : Expr<Int>, Tag<String>

fun <E, T> eval(e: TExpr<E, T>): E = when (e) {
  is ExprInt -> e.value
}
```

As an input of the algorithm, we have two supertypes of the value `e`: `ExprInt` and `TExpr<E, T>`.

The flow of the algorithm is shown in the following diagrams:

![](https://raw.githubusercontent.com/danyaberezun/KEEPs/GADT-keep/images/example_several_least_common_classifiers_1.png)

![](https://raw.githubusercontent.com/danyaberezun/KEEPs/GADT-keep/images/example_several_least_common_classifiers_2.png)

Let's follow the algorithm step by step.

* Stage 1. Not applicable.
* Stage 2. Do type projection on `TExpr<E, T>` to get `TExpr<R1, R2>` (where `R1` and `R2` are fresh type variables) and `ExprInt` to get `ExprInt`.
* Stage 3. Record the constraints $TExpr\langle E, T\rangle :> TExpr\langle R1, R2\rangle$ and $ExprInt :> ExprInt$.
* Stage 4.
    * For the lowest common classifier `Expr`, upcast the corresponding projections and get types $Expr\langle R1\rangle$ and $Expr\langle Int\rangle$.
    * For the lowest common classifier `Tag`, upcast the corresponding projections and get types $Tag\langle R2\rangle$ and $Tag\langle String\rangle$.
* Stage 5.
    * Record the constraint $Expr\langle R1\rangle =:= Expr\langle Int\rangle$.
    * Record the constraint $Tag\langle R2\rangle =:= Tag\langle String\rangle$.

#### Special cases

* Flexible types. For flexible types, we have to run the algorithm on their upper bound, as it is the type that is guaranteed to be a supertype of the real type.

  Let's review the example with flexible types:

    * Java:
      ```java
      class SerializableList implements List<Serializable> { ... }
      
      static <T> List<T> foo(T v) {
          if (v instanceof Serializable) {
               return SerializableList.of(v);
          } else {
               return SerializableList.empty();
          }
      }
      ```
    * Kotlin:
      ```Kotlin
      fun <T> bar(v: T): T { 
          val l = foo(v)
          // l : MutableList<T!>..List<T?>?
          if (l is SerializableList) {
              // Type intersection statement for lower bound: [{MutableList<T!> & SerializableList}]
              // Bounds for generic types for lower bound: [T! =:= Serializable]
              // But this is unsound, f.e. see `baz`
      
              // Type intersection statement for upper bound: [{List<T?>? & SerializableList}]
              // Bounds for generic types for upper bound: [T :> Serializable]
              // This result is always sound
      
              l.add(v)
      
              // After we have used the value as MutableList,
              //   we may state that l : MutableList<T!>
              // And then we could infer [T! =:= Serializable]
              // If it is unsound because of unsound `foo` implementation,
              //   we are not able to guarantee soundness,
              //   just as we cannot do that now.
              // If it is unsound because the result of `foo` is immutable,
              //   the unsoundness comes from the flexible types.
              // Therefore, either we soundly infer the correct bound or
              //   the code is unsound, but the reason for unsoundness
              //   is not the use of subtyping reconstruction.
          }
          ...
      }
      
      fun baz() {
          bar(object : Any() {})
      }
      ```
      
* Final classes.
  One more special case not covered by the presented algorithm could be observed in [another example](https://github.com/JetBrains/kotlin/blame/c2104d1927bf43939d33589d1a4ae930287e2272/kotlin-native/runtime/src/main/kotlin/kotlin/native/internal/FloatingPointParser.kt#L160) from the Kotlin compiler:

   ```kotlin
   @Suppress("UNCHECKED_CAST")
   private inline fun <reified T> unaryMinus(value: T): T {
       return when (value) {
           is Float -> -value as T
           is Double -> -value as T
           else -> throw NumberFormatException()
       }
   }
   ```

   Type intersection that we have in the first branch is `Float & T`.
   If we try to apply the algorithm to this case, we will not achieve any constraints.
   But actually, `Float` is final and any type that can be assigned to a value of type `Float` is a supertype of it.
   So constraint `T :> Float` can be reconstructed in this case.

   Generally speaking, in case when in our intersection statement one of the types is final with only invariant type parameters (denoted as `F`),
   we may immediately transform `T & F` to `T :> F`.
   While it does not introduce anything new in case top-level constructor of `T` is a classifier,
   it is useful in case `T` is a generic type variable.

#### How does this compare to the Scala's algorithm?

The algorithm is quite different from the Scala's algorithm and may infer bounds in more cases.
The main difference arises from the mentioned paragraph of the Kotlin specification, aka supertype set consistency, which allows to simplify and enhance the algorithm.

For instance, the following code:

```Scala 3
trait Func[-A, +B]
trait Identity[X] extends Func[X, X]
trait FalseIdentity extends Identity[Int], Func[Any, Int]
```

is valid in Scala, while the same code in Kotlin:

```Kotlin
interface Func<in A, out B>
interface Identity<X> : Func<X, X>
interface FalseIdentity : Identity<Int>, Func<Any, Int>
```

fails to compile with error: `Type parameter B of 'Func' has inconsistent values: Int, Any`.

As a result, for the code like this:

```Kotlin
fun <A, B> foo(func: Func<A, B>) = when (func) {
    is Identity<*> -> {
        val b: B = mk() as A
    }
    else -> TODO()
}
```

we are able to infer relation $A <: B$ in Kotlin.

However, this is not a case for Scala, as there we may have a value of `FalseIdentity` type, for which $A$ would be `Any` and $B$ would be `Int`, and these do not satisfy $A <: B$.

### Constraint resolution

The second part of the subtyping reconstruction, constraint resolution, is very similar to the resolution of the type constraint system during regular type inference.
Roughly speaking, this resolution works this way: reduce (simplify) input constraints until all of them are simple enough, i.e., transform a set of input constraints into a set of output (reduced) constraints.

```Kotlin
fun resolveConstraints(constraints: Set<Constraint>): Set<Constraint>
```

Let's denote the reduced set of constraints as $R$ and the input set of constraints as $C$.

The ideal behavior of the reduce function is: $R <==> C$, meaning that the input and reduced set of constraints are exactly equivalent.
Consequently, if there is a solution for the original set of constraints, then there is a solution for the reduced set of constraints and vice versa.

Usually it is impossible to always express the original constraints using simpler ones due to the constraint handling limitations (undecidability, approximations, etc.).
In these cases, the behavior of the reduce function is: $R ==> C$, meaning that the reduced set of constraints is stricter than the original one.
Consequently, if there is a solution for the reduced set of constraints, then there is a solution for the original set of constraints, but not vice versa.

> Such behavior means that if some code is type-safe for the reduced set of constraints, it is also type-safe for the original set of constraints.
> Because of this, we will reject some type-safe code as unsafe, but we will never accept type-unsafe code as safe.

For subtyping reconstruction, which provides additional information, we should relax the behavior in the other direction and allow the reduced set of constraints to be weaker, meaning the behavior of the reduce function can be: $R <== C$.
Consequently, if there is a solution for the original set of constraints, then there is a solution for the reduced set of constraints, but not vice versa.

> Such behavior means that subtyping reconstruction could add less information than available in the original set of constraints, but it can never add information which was not there.
> This preserves the type safety property: for a given set of inference constraints `T`, which we want to enhance with subtyping reconstruction information, if $C ==> R$, we have $T\ \\&\ R ==> T\ \\&\ C$.

To implement this, we have to adapt the existing resolution algorithm to this new relaxed strategy.
We did not do a complete adaptation in the developed prototype, but we believe it is definitely feasible to do so, given enough time.

#### Examples of constraint resolution

##### Resolution of type variable constraint

For example, say we are trying to reduce the constraint $S <: T$, where `T` is a type variable.

For the regular inference, the result of the reduction algorithm may be $S <: LB(T)$, where $LB(T)$ is the lower bound of `T`.
This constraint guarantees that the original constraint is always satisfied.

For the subtyping reconstruction inference, the result of the reduction algorithm may be $S <: UB(T)$, where $UB(T)$ is the upper bound of `T`.
This constraint is guaranteed to be satisfied by the original constraint.

As a demonstration, let's consider the following constraints:

* Context: $Out\langle Serializable\rangle :> T$
* $T :> Out\langle V\rangle$


1. For function generics, such a system could be achieved with the following code:

    ```Kotlin
    interface Out<out T>
    interface In<in T>
    
    fun <V> foo(t: In<Out<V>>) {}
    
    fun <T : Out<Serializable>> bar() {
        foo(object : In<T> { })
    }
    ```

   The resolution of constraints for call of `foo` would be:
   `In<Out<V>> :> In<T> => Out<V> <: T => Out<V> <: LB(T) = Nothing => unresolved`

2. For subtyping reconstruction, such a system could be achieved with the following code:

    ```kotlin
    interface InInv<T> : In<T>
    
    fun <T : Out<Serializable>, V> bar(v: In<Out<V>>, t: InInv<T>) {
        if (v === t) {
            // (1)
        }
    }
    ```

   The resolution of the system at location (1) would be:
   `[{In<Out<V>> & InInv<T>}] => [Out<V> <: R, T =:= R] => Out<V> <: T => Out<V> <: UB(T) = Out<Serializable> => V <: Serializable`

Because of different approximations in case of regular inference, we are not able to infer bounds for `V` and end up with unresolved constraints.
However, in case of subtyping reconstruction, we are able to infer that `V <: Serializable`.

##### Resolution with intersection types

If we would like to satisfy a constraint `A :> B & C`, this results in a disjoint constraints `A :> B | A :> C` which is (exponentially) hard to solve.
As stated [here](https://youtu.be/VV9lPg3fNl8?t=1391), if Scala's algorithm encounters such a situation, these disjoint constraints are skipped.
In case if all-except-one of the disjoint constraints are immediately unsatisfied, then such a constraint could be processed.

In the current algorithm, we propose to adopt the Scala approach and ignore such constraints.
However, it is possible to consider implementing some kind of heuristics to handle these constraints.

##### Resolution with flexible types

For flexible types, we have to follow [their subtyping rules](https://github.com/JetBrains/kotlin/blob/master/spec-docs/flexible-java-types.md) when doing constraint reduction.
More precisely:

* $A :> B..C => A :> C$
* $B..C :> A => B :> A$
* $A = B..C => B :> A, A :> C$

##### Resolution with nullable types

As we discussed earlier, subtyping reconstruction is based on the assumption that the intersection type is inhabited by a run-time value of some real type.
For nullable types, however, this assumption is trivially satisfiable, as `null` is always a valid value for any nullable type.
The same holds for an intersection type when all its components are nullable.

This means that, for such trivially satisfiable cases, we cannot perform any subtype reconstruction.

An example of such a case and why it would be unsound to do subtyping reconstruction for nullable types, is as follows:

```Kotlin
fun <T> foo(b: Box<T>?, t: T): T {
    if (b is BoxInt?) {
        // [{Box<T>? & BoxInt?}] => T =:= Int
        return 1
    }
    ...
}

println(foo(null, "str")) // ClassCastException as we are trying to cast Int to String
```

It is important to note that we should still track such nullable (intersection) types, as once we encounter a not-null check, the type would become non-nullable, and we would be able to do subtyping reconstruction.

#### Special cases

##### Type projections

Projections are handled by the resolution algorithm via capturing.
As a result of this, we will have some constraints which may contain captured types.

The problem here is that currently Kotlin approximates captured types to regular types immediately after the type system solution, and programmers do not interact with captured types directly, which is good for regular type inference.

For subtyping reconstruction, however, we cannot approximate captured types after the resolution of constraints, as it may lead to unsound results.
Let's review some examples of this using the following definitions.

```Kotlin
class Inv<T>
class Out<out T>
class In<in T>
```

1. If the algorithm produces constraint $T :> Out\langle Captured(\*)\rangle$, then this only provides us information that `T` is not nullable and $Out\langle T\rangle :> Out\langle Out\langle Any?\rangle\rangle$.
   If we approximated it, we would get $T :> Out\langle \*\rangle$, which leads to $T :> Out\langle \*\rangle :> Out\langle Int\rangle$, and this is unsound with respect to the original constraint.
   The reason for unsoundness is that after the approximation we do not get a relaxed type, we get a strengthened type.
   We have a relation $Out\langle \*\rangle :> Out\langle Captured(\*)\rangle$ between approximated and original type.
   And we cannot infer $T :> Out\langle \*\rangle$ from $T :> Out\langle Captured(\*)\rangle$ and $Out\langle \*\rangle :> Out\langle Captured(\*)\rangle$.

2. On the contrary, for $Out\langle In\langle Captured(\*)\rangle\rangle$
   we are able to approximate the constraint $T :> Out\langle In\langle Captured(\*)\rangle\rangle$ to $T :> Out\langle In\langle \*\rangle\rangle$.
   This is because $Out\langle In\langle Captured(\*)\rangle\rangle :> Out\langle In\langle \*\rangle\rangle$, i.e. for this constraint approximation properly relaxes the approximated type.
   And we can infer $T :> Out\langle In\langle \*\rangle\rangle$ from $T :> Out\langle In\langle Captured(\*)\rangle\rangle$, $Out\langle In\langle Captured(\*)\rangle\rangle :> Out\langle In\langle \*\rangle\rangle$ and transitivity rule.

The core principle is that by approximating a type with captured types during subtyping reconstruction, we should be relaxing the approximated type constraints.

In addition, we are losing some type information with this relaxation, specifically, we lose the equalities between the captured types (and their corresponding existential variables).
For example, if we have a constraints $V = Inv\langle Captured(\*)\rangle$ and $U = Inv\langle Captured(\*)\rangle$, where the captured types are equal, we should be able to call a function `fun <T> foo(i1: Inv<T>, i2: Inv<T>)` with the values of types `V` and `U`.
But after approximation, this information will be lost.

> Such code with captured type equalities happens when you are working with data structures that are controlling some invariants on the type level, for example, AVL tree with type-level control of the balance factor.

The possible solutions to this problem are:

1. Erase all bounds containing captured types that could not be soundly approximated.
   The type system will be sound in this case.
   But, this option will limit the applicability of the subtyping reconstruction.
   For example, the last example above will not work, meaning `foo(V, U)` will be ill-typed.

2. Preserve the captured types in the constraints.
   This option will complicate the compiler diagnostics as currently captured types are not really supposed to be printed.
   On the other hand, this option will increase the applicability of the subtyping reconstruction and allow to improve the handling of captured types in other parts of the type system as well, making them closer in expressiveness to existential types (which they actually are).
   However, this would require some additional work around how captured types are handled in the type system implementation.

## Adding subtyping reconstruction to Kotlin's flow-sensitive type system

Now that we have an algorithm which allows us to infer additional information about types, we need to integrate this algorithm to our flow-sensitive type system.
Here we explain the steps needed to achieve that.

### New type of control-flow graph statements

We re-use an existing type of statements handled by the data-flow analysis, called *type intersection*.
Type intersection statement for value `v` with types `T1 & T2 & ...` says that, in a specific node of a control-flow graph, value `v` definitely has a set of types `T1 & T2 & ...`, and these types should be used for subtyping reconstruction.

After type intersection statements are handled by the subtyping reconstruction, we get additional type constraints of the form `TypeVariable =? Type`, where `=?` is a subtyping or equality operation.
These constraints are stored and used by the data-flow analysis as a new type of statements, called *bounds for generic types*.

For example, let us consider the following code.

```Kotlin
open class Box<T>
class BoxString : Box<String>()

interface ListString : List<String>

fun <T> foo(box: Box<T>, list: List<T>): T {
    if (...) {
        box as BoxString
        // Type intersection statement: [{Box<T> & BoxString}]
        // Bounds for generic types: [T =:= String]
    } else {
        list as ListString
        // Type intersection statement: [{List<T> & ListString}]
        // Bounds for generic types: [T :> String]
    }
    // Merged bounds for generic types: [T :> String]
}
```

We are able to infer that `T :> String` at the end of the function, 
because we can merge different bounds for generic types from different control-flow graph branches.
In other words, type intersection statements *locally* add information used by the subtyping reconstruction algorithm, 
which creates bounds for generic types used *globally* in a flow-sensitive way.

### Changes to the type intersection statement collection

The only modification required in the *type intersection* statement collection is to include temporary values, 
not just stable variables. 
Currently, type intersection statements are collected only for stable variables, 
which allows the compiler to perform smart casts. 
However, for subtyping reconstruction, 
we should collect type intersection statements for temporary values as well.
For example:

```kotlin
fun <T> id(t: T): T = t

fun <T> foo(b: Box<T>) {
    if (id(b) is BoxString) {
        // Type intersection statement: [{Box<T> & BoxString}]
        // Bounds for generic types: [T =:= String]
    }
}
```

In this example, we can reconstruct that `T =:= String`, 
just as we would if the check were performed on a stable variable.

These *type intersection* statements for temporary values are not useful for smart casts and cannot be further refined. 
Therefore, they do not need to be stored or propagated in the data-flow analysis on the contrary to the corresponding *bounds for generic types*.

### Flow-sensitive type checker state

Compared to regular smart casts, which are simply refining variable types, subtyping reconstruction adds arbitrary new type constraints.
To use them, we have to incorporate them into the type system when needed.
As in different control-flow graph nodes, these constraints might be different, it means the type checker state becomes "flow-sensitive", i.e., it additionally depends on the data-flow analysis state.

### Examples

An example that utilizes flow-sensitivity was already shown in the [Real-world examples](#real-world-examples) section.
There we used data flow to propagate $M :> ReflectMethod$ in the body of the `if` expression.

Let's review the second example from the Kotlin compiler from this section as it demonstrates the power of flow-sensitive subtyping reconstruction better.

```Kotlin
interface CandidateFactory<out C>
class CallableReferencesCandidateFactory(...) : CandidateFactory<CallableReferenceResolutionCandidate>

interface ScopeTowerProcessor<out C>

fun createCallableReferenceProcessor(CallableReferencesCandidateFactory): ScopeTowerProcessor<CallableReferenceResolutionCandidate>

fun <C : ResolutionCandidate> resolveCall(
        // ...
        kotlinCall: KotlinCall,
        candidateFactory: CandidateFactory<C>,
    ): Collection<C> {
        @Suppress("UNCHECKED_CAST")
        val processor = when (kotlinCall.callKind) {
            KotlinCallKind.CALLABLE_REFERENCE -> {
                createCallableReferenceProcessor(candidateFactory as CallableReferencesCandidateFactory) as ScopeTowerProcessor<C>
            }
            // ...
        }
        // ...
    }
```

In this example we are able to eliminate unsafe cast `as ScopeTowerProcessor<C>` using subtyping reconstruction.

It might look like we are getting the subtyping information from a `when` expression, however, `KotlinCall` is just an enum class without type parameters.
Thus, it does not provide any subtyping information.

The actual origin of the information is the cast of `candidateFactory` to `CallableReferencesCandidateFactory`.
From `[{CandidateFactory<C> & CallableReferencesCandidateFactory}]` we are able to infer `C :> CallableReferenceResolutionCandidate`.
This information is propagated through the data flow to the next cast, `as ScopeTowerProcessor<C>`, where it is used to check that `ScopeTowerProcessor<C> :> ScopeTowerProcessor<CallableReferenceResolutionCandidate>`.

## When subtyping reconstruction is not enough

### When there is no expected type

Typechecking expressions with an expected type, while using bounds for generic types, 
is not a problem, as demonstrated in the following example.

```Kotlin
fun <T> foo(v: Box<T>) {
    val t: T = when (v) {
        is BoxString -> "string" // [T =:= String]
        is BoxInt -> 1 // [T =:= Int]
    }
}
```

However, if there is no expected type, in many cases, the subtyping reconstruction information alone will not be able to help us.

```Kotlin
fun <T> foo(v: Box<T>): T {
    val t = when (v) {
        is BoxString -> "string" // [T =:= String]
        is BoxInt -> 1 // [T =:= Int]
    }
    // t is inferred to be of type Comparable<*>
    //   and we actually have no reason to try and infer it
    //   to T & Comparable<*>
    return t
}
```

Function call is another case when we do not have an expected type, which stops us from using the bounds for generic types.

```kotlin
fun foo(a: Any?) = println("Any?")
fun foo(s: String) = println("String")
fun foo(i: Int) = println("Int")

fun <T> bar(t: T, b: Box<T>) = foo(when (b) {
    is BoxInt -> t
    else -> 42
})
```

Since the actual expected type for the argument of `foo` is unknown, the inferred type for the argument expression is `Any?`, and we resolve the call to `foo(a: Any?)`.
Although, if we extract `when` expression into a separate variable and annotate the expected type as `Int`, everything will type-check correctly, and call will be resolved to `foo(i: Int)`.
This happens because we do not have expected types for function arguments due to overload resolution.

### Can we fix these problems?

We do not have a good universal solution for these problems.

One of the possible solutions could be to preserve the constraint information in the inferred types.
For the `when` example, when propagating the type `Int`, knowing that we also have `T =:= Int`, we could transform the type into `T & Int`.
Together with `T & String`, we could then infer `T & Comparable<*>` for the whole `when` expression.

However, for more complex bounds for generic types, this naive solution does not work.
For example, assume we have `T <: Box<T>`.
If we transform the type to `T & Box<T>`, we would not be able to type check against expected type `Box<out Box<T>>`.

Fundamentally, this means we may need to add ways to encode recursive types (aka `µ` types) or some other ways to propagate such complex (e.g., recursive) constraints in the type system.

We leave the solutions to these problems for possible future work.

## Feature interaction

Subtyping reconstruction potentially interacts with multiple other Kotlin features.
Here we describe these interactions and how they improve Kotlin.

> Note: all these interactions and improvements are *optional*, meaning they could be added, but it is not mandatory for the subtyping reconstruction to work.

### Bare types

Subtyping reconstruction has an interesting interaction with inference of bare types.
Bare type inference happens for cases when there are generic types with omitted type parameters, for example, in the following code.

```Kotlin
fun <T> foo(l: Collection<T>) {
    when (l) {
        is List -> ... // l : List<T>
        is Set -> ... // l : Set<T>
    }
}
```

Now, type parameters of bare types are inferred from the type parameters of the is-checked value.
And this bare type inference is fundamentally a restricted and flawed version of the subtyping reconstruction algorithm.

The flaws come from the handling of projections, which are not approximated when needed.

```Kotlin
private fun <E> List<E>.addAnything(element: E) {
    if (this is MutableList) {
        // Bare type inference: MutableList<E>
        // Subtyping reconstruction: MutableList<out E>
        this.add(element)
    }
}
```

This means that adding subtyping reconstruction could also improve bare type inference.

### Builder inference

Builder inference is a feature allowing to infer a type of the lambda's parameters based on the code inside the lambda.
During the typing of lambda, type parameters are replaced with type variables.
Constraints on them are collected during type-checking, and their values are inferred on the lambda's exit.

This feature conflicts with subtyping reconstruction
because later one requires the exact types in intersection during the type-checking, not at the lambda's exit.
For example, in the following code:

```Kotlin
interface Inv<T>
interface InvInt : Inv<Int>

fun <T> foo(v: Inv<T>, t: T) = buildList {
    add(v) // From this statement, we inferred that the type parameter of the lambda is Inv<T>
    if (this.first() is InvInt) { 
        // But at the moment of this check, we have TypeVariable(E) as a type of lhs expression
        // Consequently, we are not able to infer that T =:= Int inside the `if` branch
    }
}
```

While the same code without builder inference would work correctly.
This is not a significant issue as builder inference has other issues with the same origin,
and all of them could be fixed by explicit type annotations for the lambda parameters.

### Smart casts

The other existing feature that is affected by subtyping reconstruction is smart casts.
We could add the information from the subtyping reconstruction, so that it is usable by the smart casts, for example:

```Kotlin
interface A<in T, in V>
interface A1<in V> : A<Int, V>
interface A2<in T> : A<T, Int>

fun f(v: A1<*>) {
    val v1 : A2<Int> = when (v) {
        is A2<*> -> v // Now: A1<*> & A2<*>
                      // With subtyping reconstruction: A1<Int> & A2<Int>
        else -> throw Exception()
    }
}
```

### Unreachable code detection

To detect more unreachable code, using the additional type constraints from the subtyping reconstruction, we can run the type inference algorithm and then check that all inferred constraints are satisfiable.

The constraints are satisfiable, if for each type parameter and variable, their types are inhabited, i.e., there exists at least one type satisfying all their constraints.
To check this, we can use the following algorithm.

1. Find all types that are the least common supertypes for all lower bounds.
2. Check that any of those types is a subtype of all upper bounds.

If there is no such type, then the constraints are unsatisfiable, meaning the code with these constraints is unreachable.

A simple, but incomplete approximation of this property is to check whether all of the lower bounds are subtypes of all of the upper bounds.

### Exhaustiveness checking

One of the most common use-cases for unreachable code detection is exhaustiveness checks.
Additional information from type constraints could be used to improve them.
For example, this code is marked as non-exhaustive.

```Kotlin
interface A
interface B

sealed interface I<T>
class IA : I<A>
class IB : I<B>

fun <T : A> f(i: I<T>) {
    when (i) {
        is IA -> TODO()
        // IB is impossible,
        // as `T <: A & IB <: I<T>` is unsatisfiable
    }
}
```

And the following code is marked as correct in Kotlin 2.0.

```Kotlin
interface A
interface B

sealed interface I<T>
class IA : I<A>
class IB : I<B>

fun <T : A> f(i: I<T>) {
    when (i) {
        is IA -> TODO()
        is IB -> TODO()
        // IB is still impossible,
        // as `T <: A & IB <: I<T>` is still unsatisfiable
    }
}
```

At the moment, Kotlin does not consider the satisfiability of all available type constraints in exhaustiveness checking.
But this could be improved with the subtyping reconstruction.
To implement better exhaustiveness checking, we can reuse the same idea as for the detection of unreachable code.
In this case, we have to infer constraints for each unmatched classifier and remove classifiers that are impossible aka have unsatisfiable constraints.

## Possible breaking changes

### Incompatibility with unsafe casts

Subtyping reconstruction makes unsafe casts more dangerous 
since it extends unsafe type information to the whole function.
This may even change the behavior of the existing code with unsafe casts.
For example, the following code executes successfully in the current Kotlin version, 
but will throw a `ClassCastException` with subtyping reconstruction.

```kotlin
fun Any?.debug() = "Any?: ${toString()}"
fun Int.debug() = "Int: ${toString()}"

open class Box<T>(val value: T)
class IntBox(value: Int): Box<Int>(value)

fun <T> test(a: Box<T>, b: T) {
    if (a is IntBox) {
        // T =:= Int => `debug` becomes resolved to `Int.debug`
        println(b.debug())
    }
}

fun main() {
    test(IntBox(1) as Box<String>, "1")
}
```

We are aware of this issue but follow the general Kotlin principle that unsafe casts are not guaranteed to work correctly.

### Overload resolution changes

As we will extend the type information, aka infer more precise types if subtyping reconstruction is available, it may affect the overload resolution results.
For example:

```Kotlin
interface A
interface B : A

interface Out<out T>
interface OutB : Out<B>

fun <T> f(b: B, out: Out<T>) {
    fun A.foo() = "A"
    fun T.foo() = "T"
    
    when (out) {
        is OutB -> {
            // we infer that T :> B
            
            b.foo()
            // before: resolved to A.foo()
            // now: resolved to A.foo() and T.foo(), ambiguity error
        }
        else -> TODO()
    }
}
```

While this is technically a problem, in general [we do not consider](https://kotlinlang.org/docs/kotlin-evolution.html#libraries) overload resolution changes caused by inference of more precise types as a breaking change.

## Addendum

### Prototype

[Prototype implementation](https://github.com/e2e4b6b7/kotlin/pull/3)

The prototype was written at the beginning of the KEEP design, and it does not support the full algorithm described in this document.

The current prototype limitations are:

* We save type intersection statements and not bounds for generic types in the DFA => we have non-optimal data-flow union.
* We use a different algorithm of constraint generation => it is more complex and covers fewer possible cases.
* We use ad-hoc handwritten constraint resolution algorithm => it works only in the most simple cases.
* Improper handling of captured types => the bounds for generic types are unsound in some cases.

### References

1. [Presentation of the Scala 3 implementation from the typelevel summit](https://www.youtube.com/watch?v=VV9lPg3fNl8)
2. [Description of the Scala 3 implementation](https://dl.acm.org/doi/pdf/10.1145/3563342) (Section 6.2)
3. [Formalization of GADTs for C#](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/gadtoop.pdf) (Section 5.1)

> Here we have an assortment of papers about GADT use-cases / Scala libraries with GADT usages, 
> which show the advantages and the feasibility of subtyping reconstruction.

* https://github.com/higherkindness/mu-scala
* https://github.com/AdrielC/free-arrow
* https://github.com/milessabin/shapeless
* https://github.com/owlbarn/owl
* http://gallium.inria.fr/~fpottier/publis/fpottier-regis-gianas-typed-lr.pdf
* http://pauillac.inria.fr/~fpottier/slides/slides-popl04.pdf
* https://www.cs.tufts.edu/~nr/cs257/archive/tim-sheard/lang-of-future.pdf
* https://infoscience.epfl.ch/record/98468/files/MatchingObjectsWithPatterns-TR.pdf

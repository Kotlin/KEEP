# Type-checking of reified types

* **Type**: Design proposal
* **Author**: Johannes Neubauer
* **Status**: Shepherded
* **Shepherd**: [@dnpetrov](https://github.com/dnpetrov)
* **Prototype**: *not yet*

## Feedback 

Discussion of this proposal has been done in [this issue](https://youtrack.jetbrains.com/issue/KT-12897) and in [this pull request](https://github.com/Kotlin/KEEP/pull/35). An issue in the KEEP project has to be opened.

## Summary

Support type-checking for reified types in inline functions.

## Motivation

Currently, the byte code of the function body of inline functions is copied as is. Since Kotlin has a multipass compiler it should be possible to recheck the type after copying. For now, you need to do the type-check manually (via a `when`-expression, the visitor-pattern, or sealed classes which is error-prone).

## Description

The following example shows, that the current behavior is different from non-inline functions. The scenario takes place in 
the context of libGDX in an android application:

```kotlin
object Actions {
  // other stuff ...
  inline fun <reified T: Action> action(): T {
        val pool = Pools.get(T::class.java)
        val action = pool.obtain()
        action.pool = pool
        return action
  }
}

inline fun <reified T: Action>T.copy(): T {
    val copyAction = Actions.action<T>()
    println("""
        |The reified types are: ${T::class.java.name},
        | ${this.javaClass.name},
        | ${copyAction.javaClass.name}
        |""".trimMargin())
    // this leads to the UnsupportedOperationException 
    // (although the output is: "The reified types are: \
    // com.badlogic.gdx.scenes.scene2d.actions.SequenceAction\n \
    // com.badlogic.gdx.scenes.scene2d.actions.SequenceAction"):
    this copyTo copyAction 
    // Using the following instead works ("that's good" is printed out):
    // (this as SequenceAction) copyTo (copyAction as SequenceAction)
    return copyAction
}

/**
 * This is the "else"-case for `a copyTo b` since we use it in
 * `copyInternal()` generically on `T: Action`.
 */
infix fun Action.copyTo(action: Action) {
    throw UnsupportedOperationException(
            """ |Tried to copy an (unsupported) action type
                |'${this.javaClass.kotlin.simpleName}'
                |""".trimMargin()
    )
}

// there are more overloaded methods for copyTo

infix fun ParallelAction.copyTo(action: ParallelAction) {
    action.actions.addAll(this.actions)
}

infix fun SequenceAction.copyTo(action: SequenceAction) {
    println("that's good")
    this as ParallelAction copyTo action
}

// here it is called:
fun someMethod() {
  val sequenceAction = SequenceAction()
  sequenceAction.copy()
}
```

Currently, even this expression (replacing the respective line in `<reified T: Action>T.copy(): T`) uses the upper bound `Action` instead of the (reified) type used at call-site:

```kotlin
(this as T) copyTo (copyAction as T)
```

The Kotlin documentation reads:
> We qualified the type parameter with the reified modifier, now it’s accessible 
> inside the function, **almost as if it were a normal class.**

-- https://kotlinlang.org/docs/reference/inline-functions.html#reified-type-parameters

But in this case it does not behave as if it were a normal class although `return copyAction as T` is allowed.

This proposal is not a solution for [proposal 46](https://github.com/Kotlin/KEEP/pull/46), since calling the copy-method above (even with the new behavior) would lead to unwanted results:

```kotlin
// here it is called:
fun someMethod() {
  val sequenceAction: Action = SequenceAction()
  // `T` will be of type `Action` although I would like to copy a `SequenceAction`
  sequenceAction.copy()
}
```

So, independent from this proposal ["overriding extension methods"](https://github.com/Kotlin/KEEP/pull/46) has its own right to exist.

No macros are necessary for implementing this proposal. The compiler should be able to inline the function body 
and recheck without a macro since it is a multi-pass compiler. Naively, the inlining could be done in a 
preprocessing step (not a macro) on the code-level and then compile it.

## Intended Behavior

The concrete type of the reified type should be evaluated using the type at *call-site*.

## Conflict Resolution

There are cases where conflicts arise regarding the overload resolution in inline methods. The conflicts should be propagated to the *call-site*:

```kotlin
// inline function declaration-site
interface A
fun A.foo() {}

inline fun <reified T : A> invokeFooOn(instance: T) = instance.foo()

// inline function call-site
interface B
fun B.foo() {}

class C : A, B

fun test() {
    C().foo() // throws an error: ambigous call
    (C() as A).foo() // OK.
    
    invokeFooOn(C()) // (should) throw(s) the same error: ambigous call
    invokeFooOn(C() as A) // OK.
}
```

Inline functions are inlined at compile-time. Operations involving reified type parameters are annotated with special bytecode instructions. Bytecode is post-processed at call-site, where corresponding type arguments are known. Hence, all information is available at compile-time to produce this error although one might argue that this results in leaking abstraction.

## Realization

Since the naive solution mentioned above (just copy the code to inline before compilation) needs access to the source code of the inline function it is not feasible. The current solution uses special bytecode instructions in the body of inline functions, which are post-processed after inlining. This approach can be enhanced to replace any call to methods on instances of the reified type by one typechecked at call-site.

## Open Questions

1. Is the proposed conflict resolution (throwing an error that has been caused inside the implementation of the inline function) leak abstraction or is it a straightforward solution?
2. Is this change a "bug fix" (see the excerpt of Kotlin documentation above) or silently breaking a lot of code?

## Alternatives

Use a big when-expression. But this is error-prone, since you have to conform to the order of inheritance. For example 
`SequenceAction extends ParallelAction`, which means that all `SequenceAction`s are converted to `ParallelAction`s 
unintendedly during copying:

```kotlin
inline fun <reified T: Action>T.copy(): T {
    return when (this) {
        is AlphaAction -> {
            val copyAction = Actions.action<AlphaAction>()
            this copyTo copyAction
            copyAction as T
        }

        is ParallelAction -> {
            val copyAction = Actions.action<ParallelAction>()
            this copyTo copyAction
            copyAction as T
        }
        is SequenceAction -> {
            val copyAction = Actions.action<SequenceAction>()
            this copyTo copyAction
            copyAction as T
        }
        is VisibleAction -> {
            val copyAction = Actions.action<VisibleAction>()
            this copyTo copyAction
            copyAction as T
        }
        else -> throw UnsupportedOperationException("""
                |Tried to copy an (unsupported) action type
                |'${this.javaClass.kotlin.simpleName}'
                |""".trimMargin()
        )
    }

}
```

Another alternative would be to use the visitor pattern or sealed classes. But in an example like the one above, 
i.e., you do not have access to the types, you cannot add the visitor pattern or sealed classes without reproducing the
whole type hierarchy (which is error-prone). In order to add the visitor pattern, we would need dynamic dispatch on extension methods.

Further alternatives are to use the dispatching of the *implicit extension receiver*, but this way the type hierarchy has to be rebuild, too:

```kotlin
interface CopyableActionExtension<T : Action> {
  fun create(): T
  fun T.copyTo(action: T)
}

object CopyableTemporalAction : CopyableActionExtension<TemporalAction> {
  // ...
  
  // uses implicit receiver 
  fun TemporalAction.copyTo(action: TemporalAction): TemporalAction {
    // ...
  }
}

// rebuild type hierarchy with one CopyableXYZAction per XYZAction

fun <T : Action> CopyableActionExtension<T>.copyOf(action: T): T {
  val newAction = create()
  action.copyTo(newAction)
  return newAction
}

... CopyableTemporalAction.copyOf(temporalAction) ...
```

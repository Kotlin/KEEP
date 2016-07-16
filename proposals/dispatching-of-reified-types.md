# Dispatching of reified types

* **Type**: Design proposal
* **Author**: Johannes Neubauer
* **Status**: Pull request initiated
* **Prototype**: *not yet*

## Feedback 

Discussion of this proposal has been done in [this issue](https://youtrack.jetbrains.com/issue/KT-12897) 
so far. An issue in the KEEP project has to be opened.

## Summary

Support dispatching of method calls for reified types in inline functions.

## Motivation

Currently, the function body is copied as is, but since kotlin has a multipass compiler it should be possible to recheck the 
type after copying. For now, you need to do the dispatch manually (which is error-prone).

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

No macros are necessary for implementing this. The compiler should be able to inline the function body 
and recheck without a macro since it is a multi-pass compiler. Naively, the inlining could be done in a 
preprocessing step (not a macro) on the code-level and then compile it.

## Open Questions

*No, for the time being.*

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

Another alternative would be to use the visitor pattern or the sealed classes. But in an example like the one above, 
i.e. you do not have access to the types, you cannot add the visitor pattern or sealed classes without reproducing the
whole type hierarchy. In order to add the visitor pattern, we would need dynamic dispatch of extension methods.

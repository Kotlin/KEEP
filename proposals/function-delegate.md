# Function delegate

* **Type**: Design proposal
* **Author**: Marcin Moskała
* **Contributors**: 
* **Status**: Proposition
* **Prototype**: Proposition

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/25).

## Summary

Support function delegates - delegae that can be used on to delegate function calls.

## Description

Allow to use function type as a function delegate:

```
interface MainView {
    fun clearList()
}

class ListAdapter {
    fun clear() {}
}

class MainActivity : MainView {
    val adapter = ListAdapter()
    
    override fun clearList() by adapter::clear
}
```

## Motivation / use cases

Two main motivations behind this feature are:
 * Simplify function calls passing that is opened for function signature changes. 
 * Allow wrapper functions with state to add catching, synchronization and other functionalities that needs state. 
Let's discusse them one after another. 

### Function calls passing 

When we are keeping strict architectures, like MVP or Clean Architecture, we often need to pass call from one class to another. For example, when Presenter is deciding that list needs to be changed it needs to call Activity function which is passing this call to adapter which is managing elements on list:

```
interface MainView {
    fun deleteListElementAt(position: Int)
}

class ListAdapter {
    fun deleteAt(position: Int) {}
}

class MainActivity : MainView {
    val adapter = ListAdapter()
    
    override fun deleteListElementAt(position: Int) {
        adapter.deleteAt(position)
    }
}
```

Simpler and more agile implementation would be if we could use function delegate instead:


```
class MainActivity : MainView {
    val adapter = ListAdapter()
    
    override fun deleteListElementAt(position: Int) by adapter::deleteAt
}
```

Similarly, when we are passing calls to change specific trait of layout, we need to pass function call to specific change on layout:

```
interface MainView {
    fun setTitle(text: String)
}

class MainActivity: MainView {

    override fun setTitle(text: String) {
        titleView.setText(text)
    }
}
```

We would replace it with function delegate:

```
class MainActivity: MainView {

    override fun setTitle(text: String) by titleView::setText
}
```

Note, that when we are operaring on function type, we can use all functional features like currying or extension functions.

### Function wrapper implementation

Sometimes we need to add functionalities to function that needs to hold state. For example, when we need to add buffering to function. Let's say we have fibbonacci function:

```
fun fib(i: Int) = if (i <= 2) 1 else fib(i - 1) + fib(i - 1)
```

Without buffering it is very unefficient. Using function delegation we might add buffering this way:

```
fun <V, T> memoize(f: (V) -> T): (V) -> T {
    val map = mutableMapOf<V, T>()
    return { map.getOrPut(it) { f(it) } }
}

fun fib(i: Int): Int by memoize { i -> if (i <= 2) 1 else fib(i - 1) + fib(i - 1) }
```

Similarly synchronization for single function could be added.

## Implementation details

Function delegate could be compiled to delegate holden in seperate property and its invocation in function body. Invocation should include all the paramters defined by function. For example, above `fib` function with `memoize` should be compiled to:

```
private val fib$delegate: (Int) -> Int = memoize { i -> if (i <= 2) 1 else fib(i - 1) + fib(i - 1) }
fun fib(i: Int) = fib$delegate(i)
```

similarly `setTitle` will be compiled:

```
class MainActivity: MainView {

    private val setTitle#delegate = titleView::setText
    override fun setTitle(text: String) = setTitle#delegate(text)
}
```

## Alternatives

If we are using property instead of function when we can get all the functionalities. For example:

```
val fib: (Int) -> Int = memoize<Int, Int> { a ->
    if (a <= 2) 1
    else (::fib).get()(a - 1) + (::fib).get()(a - 2)
}
```

The problem with this alternative is when we are using recurrence (because property is not defined yet). Also there is probem with interfaces, because we would have to replace functions with properties with function types:

```
interface MainView {
    val clearList: ()->Unit
}

class ListAdapter {
    fun clear() {}
}

class MainActivity : MainView {
    val adapter = ListAdapter()
    
    override val clearList: ()->Unit = adapter::clear
}
```

## Other notation proposition

Instead of `as` keyword, we could use simpler notation:

```
fun fib = memoize<Int, Int> { a -> if (a <= 2) 1 else fib(a - 1) + fib(a - 2) }
```

Type of arguments would be inferred from delegate. Argument types would be inferred from delegate.

## Open questions

Is `by` keyword a good choice while it is reminding property delegation, and as oposed to it, here are are not passing context and function reference? What if one day we would want to make function delegate that actually acts like property delegate:

```
class Delegate {
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, val property: Int): Int // Designed for this function
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, vararg arguments: Any?): Int // Desifned for any functio
}

class A {
    fun a(val a: Int) by Delegate() 
}
```

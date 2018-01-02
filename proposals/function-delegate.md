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

Allow to use object as a function delegate. We can use similar notation as for property delegation:

```kotlin
class Delegate {
    // Designed for this function with single argument of type Int
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, property: Int): Int

    // Desifned for any function
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, vararg arguments: Any?): Int
}

class A {
    fun a(a: Int) by Delegate() // We use here first method
    fun a(a: String) by Delegate() // We use here second method
    fun a(a: Int, b: String) by Delegate() // We use here second method
}
```

## Motivation / use cases

Main motivation behind this feature is to allow to extract common logic behind function to delegate instead of writing it every time.
With above definition, every function pattern that is used in the project can be extracted.
Let's see some examples.

### View binding

Let's say that we often define functions that are making single change in layout but that needs to be extracted to keep MVP structure:

```kotlin
interface MainView {
    fun setTitle(title: String)
}

class MainActivity : MainView {

    override fun setTitle(title: String) {
        titleView.text = title 
    }
}
```

With function delegation we can define simple function delegate and then use it to replace all such functions with simpler notation:

```kotlin
class bindToText(val elementProvider: ()->TextView) {
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, text: String) {
        elementProvider().text = text
    }
}

class MainActivity : MainView {

    override fun setTitle(title: String) by bindToText { titleView }
}
```

### Function calls passing 

If we often pass function calls, we can define generic extension functions to function type:

```kotlin
operator fun (()->Unit).functionDelegate(thisRef: Any?, function: KFunction<*>) = this()

operator fun <T1> ((T1)->Unit).functionDelegate(thisRef: Any?, function: KFunction<*>, arg1: T1) = this(arg1)

operator fun <T1, T2> ((T1, T2)->Unit).functionDelegate(thisRef: Any?, function: KFunction<*>, arg1: T1, arg2: T2) = this(arg1, arg2)
```

And use it to have simpler notation for passing calls to functions. So instead of this notation:

```kotlin
interface MainView {
    fun deleteListElementAt(position: Int)
}

class ListAdapter {
    fun deleteAt(position: Int) {}
}

class MainActivity : MainView {
    val adapter = ListAdapter()
    
    override fun deleteListElementAt(position: Int) = adapter.deleteAt(position)
}
```

We can use following:

```kotlin
class MainActivity : MainView {
    val adapter = ListAdapter()
    
    override fun deleteListElementAt(position: Int) by adapter::deleteAt
}
```

Advantages are:
 * It is shorter and more readable.
 * When function signature changes, we don't need to change also parameters passed to next function. This is two times less work when we need to change signature of function that is passed. Also now we can get better support from IDE.

Note, that when we are operating on function type, we can use all functional features like currying or extension functions.

### Function wrapper implementation

Sometimes we need to add functionalities to function that needs to hold state. For example, when we need to add buffering to function. Let's say we have Fibonacci function:

```kotlin
fun fib(i: Int) = if (i <= 2) 1 else fib(i - 1) + fib(i - 1)
```

Without buffering it is very unefficient. Using function delegation and previously defined extension functions to function type we might add buffering this way:

```kotlin
fun <V, T> memoize(f: (V) -> T): (V) -> T {
    val map = mutableMapOf<V, T>()
    return { map.getOrPut(it) { f(it) } }
}

fun fib(i: Int): Int by memoize { i -> if (i <= 2) 1 else fib(i - 1) + fib(i - 1) }
```

Similarly synchronization for single function could be added.

### Functional style

Function delegation and above definitions would also open a way for more functional style of programming where functions are defined using another functions. 
For instance, we could use define functions using [Haskell pointfree stype](https://wiki.haskell.org/Pointfre):

```kotlin
infix fun <T, R, S> ((T) -> R).compose(f: (R) -> S) : (T) -> S = { f(this(it)) }

fun add5(i: Int) = i + 5

fun add10(i: Int) = i + 10

fun stringToInt(s: String) = s.toInt()

fun convertAndAdd15 by ::stringToInt compose ::add5 compose ::add10
```

Or to create new functions using partial application.

## Implementation details

Function delegate could be compiled to delegate holden in separate property and its invocation in function body. 
Invocation should include all the parameters defined by the function. 
For instance, above `a` methods should be compiled to:

```kotlin
class Delegate {
    // Designed for this function with single argument of type Int
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, property: Int): Int

    // Desifned for any function
    operator fun functionDelegate(thisRef: Any?, function: KFunction<*>, vararg arguments: Any?): Int
}

class A {
    val aInt$Delegate = Delegate()
    fun a(a: Int) = aInt$Delegate.functionDelegate(a)
    
    val aString$Delegate = Delegate()
    fun a(a: String) = aInt$Delegate.functionDelegate(a)
    
    val aIntString$Delegate = Delegate()
    fun a(a: Int, b: String) = aInt$Delegate.functionDelegate(a, b)
}
```

This is how `fib` should be compiled:

```kotlin
private val fib$delegate = memoize { i -> if (i <= 2) 1 else fib(i - 1) + fib(i - 1) }
fun fib(i: Int) = fib$delegate.functionDelegate(i)
```

## Alternatives

If we are using property instead of function and use property delegation. Also when we use property then it can hold state so we can use functions like memorize:

```
val fib: (Int) -> Int = memoize<Int, Int> { a ->
    if (a <= 2) 1
    else (::fib).get()(a - 1) + (::fib).get()(a - 2)
}
```

The problem with this alternative is when we are using recurrence (because property is not defined yet). Also there is problem with interfaces, because we would have to replace functions with properties with function types:

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
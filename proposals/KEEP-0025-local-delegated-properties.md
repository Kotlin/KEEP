# Local delegated properties

* **Type**: Design proposal
* **Author**: Michael Bogdanov
* **Contributors**: Dotlin, Michael Bogdanov
* **Status**: Accepted
* **Prototype**: Implemented in Kotlin 1.1

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/25).

## Summary

Support delegated properties for local scopes (local delegated properties).

## Description

Allow to use delegates for local variables similar to general delegated properties:

```
import kotlin.reflect.KProperty

class Delegate {
    operator fun getValue(t: Any?, p: KProperty<*>): Int = 1
}

fun box(): String {
    val prop: Int by Delegate()
    return if (prop == 1) "OK" else "fail"
}
```

## Open questions

- How property metadata should be linked to local delegated properties: statically or dynamically?

```
fun test() {
    val prop: Int by Delegate() //Is property metadata created on each invocation of 'test' function or just once?
    println(prop)
}

fun test2(){
    for (i in 1..2) {
        val prop: Int by Delegate() //Is 'prop' metadata same on each loop iteration?
        println(prop)
    }    
}
```

- Should property metadata be changed upon function inlining?
```
inline fun test() {
    val prop: Int by Delegate() //Is metadata for prop variable same or not after inlining into 'main' function?
    println(prop)
}

fun main(args: Array<String>) {
    test()
}
```

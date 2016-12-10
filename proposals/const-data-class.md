# Const modifier for data classes

* **Type**: Design proposal
* **Author**: Salomon BRYS
* **Contributors**: Salomon BRYS
* **Status**: Under consideration
* **Prototype**: Not started

## Feedback

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/pull/51).

## Synopsis

Kotlin data classes are natural candidates for `HashMap` composite keys.

Kotlin's data classes can contain either `var` or `val` values.
This mutability is, of course, a welcomed liberty but it prevents the `hashcode` value of the data class to be cached.

A very simple benchmark (see appendix 1) has shown that the simple optimization of **caching the hashcode value** to divide by 3 the access time of a simple 2-layer data class (See appendix 2).

The [issue KT-12991](https://youtrack.jetbrains.com/issue/KT-12991) introduces the idea to access the `hashcode` function generated for the data class even if the function is overridden.
This would allow the programmer to manually cache the `hashCode` of a data class but there would be no guarantee of the validity of such a measure other than *convention* (e.g. the programmer could cache the result of the hashcode even if the data class contains a `var`).

We propose the notion of `const data class` that severely limits the possibility of such classes but enables the compiler to generate `hashcode` and `toString` functions with cached values with the *guarantee* that the output will always reflect the data (e.g. the data can *never* change).

Note that this proposal does NOT substitutes itself to KT-12991 as manually caching can still be very usefull when using data classes that do not (or cannot) comply with const data classes limitations.

## Const data classes

### limitations

A `const data class` has the same limitations as a `data class` with the following additions:

- It can only contain `val` constructor values.
- It can only contain constructor values of type:
	- Primitive
	- String
	- Const data class
- Its `hashcode` and `equals` functions cannot be overridden.

### Auto-detection by the compiler

The compiler could detect that a data class complies with all restrictions and apply silently the optimization if it does.
Using the `const` keyword would then force the programmer to enforce said limitations.

### Keyword

The `const` keyword used in this proposal is proposed because of its semantic.
It can easily be replaced by a compiler annotation (such as `@CachedHashcode`).

## Compiler implementation

#### Definition

The `toString` value is generated only once and then cached. Each time the function is called, it first checks whether the value has already been generated and returns it if it has. Note that the `toString` method can be overridden.

The `hashCode` value is generated only once and then cached. Each time the function is called, it first checks whether the hash code has already been generated and returns it if it has.

Both cached values are marked `@Transient` to prevent them to be serialized or transfered along with the data.

The `equals` function checks `hashcode()` equality *before* checking any other equality.
Because the hash is most likely already cached, this enables to fail fast.
*(Note: this assertion should be statistically checked: if most `equals` function call succeed, this will slightly slow down execution instead of speeding it up)*.

#### Example

See appendix 1 implementation of the `Optimized` class that features optimized `hashcode` and `equals` functions.

## Alternative approaches

#### Annotation `@CachedHashcode`

This annotation would be allowed only on data classes.
As stated in the synopsis, there would be no *guarantee* that the data class is effectively constant, and that the cached hash code does represent the current state of the data class.

#### Manually caching the result

See [issue KT-12991](https://youtrack.jetbrains.com/issue/KT-12991).
This would allow the programmer to achieve the same result but, again, with no strong constant guarantee, other of course than *convention*.

## Arguments against this proposal

- This is an optimization that can be easily implemented by the programmer (at the cost of some boilerplate code that can be reduced with KT-12991).
- This optimization is useful *if and only if* keys to `HashMap` & `HashSet` are *reused*.
  Recreating the object everytime (e.g. `map.contains(Key(1, 2))`) renders the optimisation completely useless.

## Appendix

#### 1: Benchmark code

```kotlin
import java.util.*

data class Person(val firstName: String, val lastName: String)

data class Optimized(val id: Int, val person: Person) {
    @Transient
    private var _hashcode = 0;
    override fun hashCode(): Int{
        if (_hashcode == 0)
            _hashcode = 31 * id + person.hashCode()
        return _hashcode
    }
    override fun equals(other: Any?): Boolean{
        if (this === other) return true
        if (other !is Optimized) return false

        if (hashCode() != other.hashCode()) return false
        if (id != other.id) return false
        if (person != other.person) return false

        return true
    }
}

data class Standard(val id: Int, val person : Person)

const val ITERATIONS = 10000000

inline fun time(name: String, f: () -> Unit) {
    val start = System.currentTimeMillis()
    f()
    val time = System.currentTimeMillis() - start
    println("$name: $time")
}

fun main(args: Array<String>) {
    val s = HashSet<Standard>()
    val o = HashSet<Optimized>()

    val person = Person("Salomon", "BRYS")

    for (i in 0..ITERATIONS)
        s.add(Standard(i, person))

    for (i in 0..ITERATIONS)
        o.add(Optimized(i, person))

    time("Standard") {
        s.forEach {
            if (!s.contains(it))
                throw IllegalStateException("WTF?!?!")
        }
    }

    time("Optimized") {
        o.forEach {
            if (!o.contains(it))
                throw IllegalStateException("WTF?!?!")
        }
    }
}
```

Note: The `Person` class should feature the same optimizations as the `Optimized` class to conforms to const data class limitations.
In this benchmark, however, it does not affect the results (it would affect them a lot if we benchmarked puts).

#### 2: Benchmark result

I've run the benchmark multiple times on my PC through IDEA and found consistent results (Linux Mint, Oracle JVM 8.0.101).

```
Standard: 582
Optimized: 191
```

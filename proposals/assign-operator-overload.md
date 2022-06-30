# Assign operator overload

* **Type**: Design proposal
* **Authors**: Anže Sodja, Lóránt Pintér, Stefan Wolf, Roman Elizarov
* **Status**: In review
* **Discussion**: [KEEP-309](https://github.com/Kotlin/KEEP/issues/309)
* **Prototype**: Implemented


## Abstract

This KEEP is a proposal on providing an assign operator overload that would provide DSL for assigning values to mutable container objects.


## Background

Kotlin has a rich set of operators that make working with various containers in the standard library easier. For example, you can write the following code with lists:

```kotlin
val list = mutableListOf<Int>()
list += 1 // operator plusAssign (1)
list += listOf(2, 3) // operator plusAssign (2)
list[0] = 4 // operator set
```

Notice that the `list` is defined as a read-only (`val`) property, but contains a reference to a mutable container of type `MutableList` which is modified with various operators using variants of assign (`=`) operator.

These operators are not hardcoded in Kotlin to operate on lists or arrays, for that matter, but are implemented in the language via _operator conventions_. The language is designed to recognize those conventions and resolve the corresponding operators (in the above example we use operators `plusAssign` and `set`), while the actual support for the specific containers is provided in the standard library. Other libraries are free to define those operators on their own containers to suit the intended usage of those containers.

Essentially, these operators form a DSL for collection manipulation that allow writing collection-manipulation code in an easier-to-read way. To see the advantage of having these operators in the language, imagine a configuration part of code that sets various properties and parameters for some subsystem in the code:

```kotlin
configuration {
    localName = "myServer"
    additionalOptions += "fast"
    remote["origin"] = "example.com"
}
```

By having a DSL that consistently uses variants of assignment (`=`) operator to update both the regular mutable (`var`) properties and various collections, the code becomes easier to read for a human.

It is customary in Kotlin to overload the corresponding operators to perform distinct operations depending on the parameter type. In the initial example, the 1st usage of `plusAssign` adds a single element to a list (aka `add` operation), while the 2nd usage of `plusAssign` adds all elements from another list (aka `addAll` operation).

The Kotlin standard library only provides mutable containers for multiple elements (things like lists, sets, maps, etc) and the repertoire of provided operator conventions in the language is well suited for them. However, one can imagine one-element mutable containers (boxes) of values that would need an operator similar to `set`, but in the form that does need any square brackets (`[...]`) to provide an additional index or key. The following section provides one such motivating example.


## Motivation

Let’s say you have mutable value container classes, for example defined as:


```kotlin
class Property<T>(var value: T) {
      /** Get the current value of this property. */
      fun get(): String = value
      /** Set the value of this property to the given value eagerly. */
      fun set(value: T) {
          this.value = value
      }
      /** Make this property mirror the value of the given source. */
      fun set(source: Property<T>) {
         this.value = source.get()
      }
}
```

And you use such container class on your object:

```kotlin
data class MyClass(val prop: Property<String> = Property<String>(""))
```

Then you have to modify `prop` by explicitly calling `.set`. What we propose is to be able to overload assign operator `=` so one could set a container value with `=`. That would simplify use cases especially for DSL scripts. 


### Example

In Gradle, users construct tasks as classes that have _inputs_ or _outputs_ modeled as container objects. That gives Gradle the benefit of “tracking" properties and with that option to handle the lifecycle of a property, detect dependencies between tasks etc. Additionally users can also specify values lazily. Lets show a simplified version of Gradle tasks.


Let’s say that we have a class like, where Property is a class defined in the [motivation section](#motivation):
```kotlin
open class MyTask {
    val input1: Property<File> = Property<File>()
    val input2: Property<File> = Property<File>()
    val output: Property<File> = Property<File>()
}
```

Given an instance of MyTask, you'd set values like:

```kotlin
val myTask = MyTask()
val calculatedProperty: Property<File> = calculateProperty()
myTask.input1.set(File("input-file.txt"))
myTask.input2.set(calculatedProperty)
myTask.output.set(File("input-file.txt"))
```

Or as a DSL

```
myTask.apply {
    input1.set(File("input-file.txt"))
    input2.set(calculatedProperty)
    output.set(File("input-file.txt"))
}
```

This can soon become very verbose, especially if properties are set with complex expressions.
With the option to overload the `=` operator one could write tasks like:

```kotlin
val myTask = MyTask()
val calculatedProperty: Property<File> = calculateProperty()
myTask.input1 = File("input-file.txt")
myTask.input2 = calculatedProperty
myTask.output = File("input-file.txt")
```

Or as a DSL

```kotlin
myTask.apply {
    input1 = File("input-file.txt")
    input2 = calculatedProperty
    output = File("input-file.txt")
}
```

That simplifies the usage and improves readability especially when used in the context of a DSL.

> It is tempting to try to implement this DSL via the existing feature of delegated properties. E.g., one can provide the corresponding `setValue` and `getValue` operators on a `Property` class and declare `var input1 by Property<File>()` in `MyTask` class. However, this solution does not support overloaded operators, where one can write both `myTask.input1 = File(...)` as well as `myTask.input1 = anotherInput`. See more discussion on that in the [alternatives section](#alternatives).


## Implementation


### Basic usage

Implementation wise operator `=` overload is similar to the `+=` operator. Let’s say we have again a similar class like in the [motivation section](#motivation), but, let's use a simpler version like:

```kotlin
data class StringProperty(var value: String = "") {
    fun get() = value
    fun set(value: String) {
        this.value = value
    }
}
```


one could define operator overload methods such as:

```kotlin
operator fun StringProperty.assign(value: String) {
    this.set(value)
}

operator fun StringProperty.assign(value: Int) {
    this.set(value.toString())
}
```

This could be then used in a code like:

```kotlin
class Task(val stringProperty: StringProperty = StringProperty(""))
val task = Task(...)
// Note that different types are accepted for `=`
task.stringProperty = "different value"
task.stringProperty = 42

// As DSL
task.apply {
   stringProperty = "different value"
   stringProperty = 42
}
```

Alternatively to the extension function, one could also add an `assign()` method to the class itself:

```kotlin
data class StringProperty(var value: String = "") {
    operator fun assign(value: String) {...}
}
```


### Resolution of assign operator overload

A resolution of assign operator overload is done on read-only (`val`) properties or read-only (`val`) variables only. Addition to current `val` property resolution logic in pseudo code looks like:
```kotlin
fun resolveValAssign() {
     if (assignOperatorOverloadExists) {
          useOperatorOverload()
     } else {
         // Current val flow
    }
}
```

There are the following rules when resolving assign operator overload:
1. Assign operator overload should work for _val properties/variables_
2. Assign operator overload should NOT work for _var properties/variables_. Setter resolution logic should always have a priority even if type doesn’t match.
3. Assign overload operator should NOT work for the resolution where set() operator is used, e.g. array[i] = 5


### Resolution compile errors

When there is a assign operator overload method but types don't match compiler should provide helpful error like:
```kotlin
operator fun Int.assign(v: String)
val value = 5
value = 6
       ~~~ Assign operator method exists, but type doesn’t match
```

If there is no operator overload method, the user should see the usual `Val cannot be reassigned` error.


### Java interoperability

For the purpose of Java interoperability this new operator should behave the same way as other operators. That is, if some Java class has `void assign(...)` method with parameter, then treat it just like Kotlin operator.

We could also not translate Java _assign_ method to the Kotlin _assign operator_ automatically, since _assign operator_ is primarily meant for usage in the Kotlin DSL. But that might cause confusion due to inconsistency with other operators.

### Combining with other operator conventions

The design of the overloadable `assign` operator does not support combination with other operator conventions in the compiler. Consider the following snippet:

```kotlin
remote["origin"] = "example.com"
```

Assuming that `remote` is a property of type `MutableMap<String,String>`, this code resolves via the existing convention to `remote.set("origin", "example.com")`. But if a `remote` has a type of `MutableMap<String,Property<String>>` the above code will not compile due to the type mismatch between `Property<String>` and `String`.

The solution for this is straightforward. One can declare `operator set` for such assignments:

```kotlin
operator fun <K, V> MutableMap<K,Property<V>>.set(key: K, value: V) {
   get(key)?.set(value) ?: put(key, Property(value))
}
```

So, in a sense, Kotlin already supports overloadable assignment operator for property-like classes, but only if the instances of those property classes are stored in another map-like or list-like container.


## Use-cases and style considerations

The use-cases of this `assign` operator are limited to the configuration-like DSLs where containers’ contents are mostly assigned in the code as explained in the [background section](#background). The important feature of a property declared as `val input = Property<T>` with an `operator assign` on a `Property` type is that reading such an `input` results in an expression of type `Property`. It does not call any kind of `Property.get()` automatically, so it does not help with use-cases when the properties are being mostly read.

This `operator assign` might turn out to be useful elsewhere. Consider, a [MutableStateFlow](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-state-flow/index.html) as a kind of mutable container. You access and modify its contents via its `value` property. One can define an `operator assign` for a `MutableStateFlow` to reduce some boilerplate of setting a new value into the `val state = MutableStateFlow(...)` property. However, appropriateness of this usage depends on how the application works with the corresponding state flow.

If the code assign the current values of the state value using `state.value = newStateValue`  syntax and all the consumption of its values happens asynchronously via `state.collect { currentState -> … }` calls and the like, then using an `operator assign` to have a nicer `state = newStateValue` syntax for new values would be a good fit.

However, if the application mostly works with the current state synchronously by reading `state.value`, then using `state = newStateValue` syntax instead of an explicit `state.value = newStateValue` might be confusing and the pattern of declaring two properties with the delegation would be still preferred:

```kotlin
val stateFlow = MutableStateFlow(...) // the underlying container
var state by stateFlow // the value
```


## Known issues

This section presents all the known issues in this design that were taken into account when weighting this design against [alternatives](#alternatives) that are presented later. It concludes with an approach to mitigate those issues.


### Potential abuse

The overloadable `assign` operator can be abused in general code, leading to hard to understand code with unexpected behavior. Consider the snippet:

```kotlin
x = 42 // assign a value to `x` (1)
// some time later in code
doSomething(x) // read value of `x` (2)
```

In general Kotlin code, `x = 42` at line (1) assigns a value of `42` to a variable `x`, so that, barring other sources of change, that developers are accustomed to be looking for, the read of `x` at line (2) reads the value of 42 with the same type.

Now, the overloadable nature of the `assign` operator makes it easy to obscure what this code does, as `x = 42` can be made to do an arbitrary action, despite looking like a simple assignment.

This issue is answered by the following considerations.

First of all, Kotlin already has a custom setter and delegation, thus `x = 42` can already have an arbitrary behavior in Kotlin. To fully understand what `x = 42` does in Kotlin one has to consult the declaration of `x`. This KEEP does not create a new place for developers to looks for the source of trust, as the `assign` operator can only operate on read-only (`val`) properties, so the the first glance at the declaration of `x` will give a hint that there is an `assign` operator in action, or the `x = 42` line will simple not compile.

The second consideration is that the feature, described in this KEEP, like many other Kotlin features (property delegation being one of them), is designed for DSLs. It should not be used in general Kotlin code at all.

Even for DSLs, the goal of Kotlin here is to provide the choice of various tools to the DSL authors. If DSL authors want their DSL to have custom property assignment syntax with an overloaded operator `=`, then they'll have this tool they can use, if they want to, but they are not forced to. It will be just one tool in their disposal.


### IDE Performance

The overloadable assign operator has potential impact on IDE performance. One important aspect is that, in a big project, it will be virtually impossible to find all usages of a given `operator fun assign` declaration. The reason is that its usage on a call-site looks like `=`, which occurs in virtually every Kotlin source file. So, in order to find the actual usages, the IDE will have to essentially resolve every Kotlin source file. Text indices cannot help with that.

Conceptually, this is similar to the problem of finding usages of `operator fun invoke` and other popular operators in Kotlin. It does not seem to be presenting a novell complication, but still needs to be taken into consideration.


### Issues mitigation plan

In order to fully understand the impact of the issues with this KEEP, the initial plan is to enable this language feature only in Gradle script files and study how it behaves there. The final decision to enable this feature for the general Kotlin code will be made later, based on the results of these initial experiments. However, any codebase that would wish to experiment with it will be able to turn it on via a separate compiler flag: `-Xassign-operator`.


## Alternatives

This section describes alternative approaches to a problem presented in the [motivation section](#motivation) and discusses why they were not chosen instead.


### Property delegation

With the suitable implemented inline `getValue` and `setValue` operators on a `Property` class one can write:

```kotlin
var input by Property<File>()
```

Essentially, the above line desugars after inlining into the following declaration:

```kotlin
private val input$delegate = Property<File>()
var input: File
    get() = input$delegate.get()
    set(value) { input$delegate.set(value) }
```

It provides the ability to write a DSL that looks like an assignment `input = File(...)`, yet it actually performs the `Property.set` call just as it was desired.

However, if you have `var anotherInput by Property<File>()`, it does not support binding of two properties via the same syntax like `input = anotherInput` for two reasons:

* An assignment itself (the `input = …` part) cannot be overloaded. It cannot accept both the `File` and `Property<File>`
* The read part (the `anotherInput` part) returns `File`, which is the type of the `anotherProperty`, not the `Property<File>`.

The only workaround that is available using existing Kotlin features is to declare two properties:

```kotlin
val inputProperty = Property<File>
var input by inputProperty()
```

This way, you can do `input = File(...)` for value assignment and some kind of `inputProperty.bind(anotherFileProperty)` for binding between property values. The downsides of this solution are:


* Boilerplate at every declaration. It is especially worrying at scale in a DSL with many entities.
* Boilerplate at every use-site. The distinction between `input = …` and `inputProperty.bind(…)`, while being technically important, does not help a user who writes or reads any kind of DSL configuration. From the standpoint of a business domain, the `input` is getting assigned in both cases, and the distinction between `input` assignment  and `inputProperty` binding is an implementation-enforced ceremony that the user must follow.

The latter could be quite distracting for a reader of a typical DSL, see:

```kotlin
myTask.apply {
    input1 = File("input-file.txt")
    input2Property.bind(calculatedProperty)
    output = File("input-file.txt")
}
```


### Setter overload

Potentially, a solution with [property delegation](#property-delegation) can be improved with additional language features. The primary one is discussed in [KT-4075](https://youtrack.jetbrains.com/issue/KT-4075) "Allow setters overloading for properties" issue, but to avoid the declaration-site boilerplate we also need add [KT-51932](https://youtrack.jetbrains.com/issue/KT-51932) "Consider adding overloading of setValue operator" issue into the mix.

The idea is that we can declare `getValue` and `setValue` delegation operators so that `input` has a type of `Property<File>` with an overloaded setter (via on overloaded `setValue` operator)  that also accepts a `File` type on assignment. This way, the `input = …` assignment will accept both `File` and `Property<File>` types.

However, this whole construction turns out to be very complicated from the language design standpoint. Just the overloading of setters has wide-reaching implications throughout the language. Adding the required `setValue` operator overload on top of that gets even more complicated by the fact, that the whole `getValue`/`setValue` and property `by` delegation design in Kotlin has been historically centered about a very different goal of being able to capture the variable name, to conveniently build SQL-like DSLs, so it has complex operator convention with non-trivial, hard-to-extend resolution rules.

The unique challenge of having an overloaded `setValue` lies in the fact that all such overloads will have to be resolved when compiling the `var input by …` delegation declaration and all the resolved candidates will lead to some code being generated at that moment. It does not have any analogy in the Kotlin language. In Kotlin the resolution algorithm always walks up the scope and always picks a single candidate from the closest such scope using the overload resolution rules of the language.

All-in-all, the delegation-based design might be possible to pull off, yet the amount of effort required even for a decent prototype is not feasible in a short term. No coherent proposal, let alone an implementation prototype, has been even presented on how it might fit into the Kotlin language.

On the other hand, the `assign` operator-based design that is present in this KEEP is clear, easy to implement, and is totally based on tradition for how other assignment operators for various containers get overloaded in Kotlin.


### Delegate access operator

As a part of [KEEP-278](https://github.com/Kotlin/KEEP/issues/278) there is ongoing design work on providing an explicit syntax to access underlying backing field of a property. Now, imagine there is such a syntax in a form of `$name` (Kotlin used to have [exactly this syntax in the past]([https://blog.jetbrains.com/kotlin/2015/10/kotlin-m14-is-out/](https://blog.jetbrains.com/kotlin/2015/10/kotlin-m14-is-out/)), before 1.0 release). It adds another way to retrofit [property delegation](#property-delegation) to solve the use-case from the [motivation section](#motivation). Given:

```kotlin
var input by Property<File>()
```

We can write `input = File(...)` to set a property value and `$input.bind($anotherInput)` for binding two properties. However, it addresses only part of [property delegation](#property-delegation) downsides, leaving the use-side DSL cluttered:

```kotlin
myTask.apply { 
    input1 = File("input-file.txt")
    $input2.bind(calculatedProperty)
    output = File("input-file.txt")
} 
```


### Separate operator syntax for assignment

Another alternative is generally to take the approach presented in this KEEP, but instead of overloading a general-purpose assignment operator (`=`) that is used to update mutable (`var`) properties, introduce a new syntax for the overloadable assignment operator. A leading candidate for this syntax is `:=`. For example, a piece of code from the [motivation section](#motivation) becomes looking like this:

```kotlin
myTask.apply {
    input1 := File("input-file.txt")
    input2 := calculatedProperty
    output := File("input-file.txt")
}
```

The advantage of this approach is that it leaves no confusion when this assignment operator is used in general code, totally avoiding the [potential abuse](#potential-abuse) issue described above. You can clearly syntactically distinguish between `x = 42` being an assignment to a mutable property `x` and `x := 42` being an `x.assign(42)` call that leaves the value of `x` property itself intact.

However, a separate operator is clearly at disadvantage when used inside a richer DSL. Take a look at the configuration DSL snippet from the [background section](#background), rewritten with a separate operator `:=`:

```kotlin
configuration {
    localName := "myServer" // (1)
    additionalOptions += "fast"
    remote["origin"] =  "example.com"
}
```

The difference between the 1st line (1), that uses `:=`, and others, that don’t need to use `:=`, becomes striking. The need to remember which operator to use when will be a distraction to a person reading and writing with this configuration DSL. The problem becomes even worse, if a DSL mixes both overloaded properties and a regular mutable variables (for example, for properties that do not support linking):

```kotlin
configuration {
   localName := "myServer"
   port = 42 // a regular var
}
``` 

The fact that `localName` in this DSL is implemented via an overloaded `assign` operator is a pure implementation detail for this piece of code that should not leak to the shape of how it is used.


### Separate operator syntax for binding

A variant of [separate operator syntax for assignment](#separate-operator-syntax-for-assignment) is to start with an alternative from the [delegate access operator](#delegate-access-operator) section and further go on to improve the binding DSL with a special-purpose assignment operator that implies the use of underlying delegate on its left-hand-side, so that that [motivation](#motivation) use-site will look like this:

```kotlin
myTask.apply {
    input1 = File("input-file.txt")
    input2 := calculatedProperty // desugars to $input2.bind(calculatedProperty)
    output = File("input-file.txt")
}
```

This provides a more explicit syntax that is proposed in this KEEP which might look compelling, but it shares a problem with [separate operator syntax for assignment](#separate-operator-syntax-for-assignment) in that it becomes challenging to combine this new syntax `+=` and `[...]` operator conventions, especially given the fact, that those conventions already work with existing playing assignment (`=`) syntax as explained in [combining with other operator conventions section](#combining-with-other-operator-conventions).

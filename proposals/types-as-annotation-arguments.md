# Types as annotation arguments

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Status**: Under consideration
* **Prototype**: Not started

Discussion of this proposal is held in [this issue](TODO).

## Synopsis

Currently, we can only mention classes in annotation arguments: `@Ann(Foo::class)`. Although in some contexts terms *class* and *type* can be used interchangeably, those are two different things, for example, a class `Foo` can be mentioned in many different types: `Foo`, `Foo<Bar>`, `Foo<Foo<Bar>>`, `Foo<*>`, `Bar<Foo>, etc.
   
Sometimes we need to refer to types, not classes. For example, in many cases it's useless to say `List::class`, we need more information like `List<String>` or `List<Nothing>`.
    
We propose adding support for types as annotation arguments (and, optionally, elsewhere in the language):
- an annotation parameter may be declared to be of type `KType`,
- an annotation argument may be a type (in some syntactic form, e.g. `@Ann(Foo<Bar>)`).


Example (syntax is subject to discussion):

``` kotlin
annotation class Ann(val type: KType)

@Ann(type = Foo<Bar>)
fun test() {} 
```

## References

- [KT-42](https://youtrack.jetbrains.com/issue/KT-42) Support type parameters for annotations
- [KT-6563](https://youtrack.jetbrains.com/issue/KT-6563) Support reified type parameters on annotation classes

## Syntax

> A thing to keep in mind: the same syntax is likely to be relevant for otehr uses of types as values, e.g. for reflection literals

### Strawman types: `@Ann(Foo<Bar>)` 

- **pro**: the most intuitive syntax possible
- **con**: as types are syntactically ambiguous with expressions (simples example: `Foo`), using types as they are may be problematic for parsing,

### Reflection-like literals

Something like `Foo<Bar>::type`.

- **pro**: looks like reflection,
- **con**: the type on the left may have a member named `type` (which is too popular to be made a keyword), so this syntax is ambiguous with a member reference.

### `typeof`

Something like `@Ann(typeof(Foo<Bar>))`. Note: `typeof` is [reserved as a keyword](https://github.com/JetBrains/kotlin/blob/master/compiler/frontend/src/org/jetbrains/kotlin/lexer/Kotlin.flex#L249) already.

- "typeof" is not a great name, because the result is not the *type of* the expression in parentheses,
- what to use: `typeof<...>` or `typeof(...)`?
- alternative names like `type<...>` can be parsed in annotations, but may not work for reflection.
  
### Some prefix, like `: Foo<Bar>`  

Something like `@Ann(:Foo<Bar>)` or `@Ann(type = :Foo<Bar>)`

- **con**: looks weird
- **pro**: has some logic to it :)

## Implementation issues

JVM annotations can only have primitives, strings, annotations, classes and arrays of the above as parameters/arguments. This means that we'll need to encode the types somehow to represent them in class files.

### String representation

Some kind of a string encoding might work for this: 
- something like `@Ann("com.example/Foo<com.example/Bar>")` stored in the class file,
- Java sees this as a string,
- Kotlin performs runtime conversion from such a string to `KType`.

> Why this is better than simply use strings from Kotlin: types mentioned through proper syntax can be properly checked by the compiler (all names resolve, all arguments applicable etc).

### Structured representation

We could use library annotation types to encode tha AST of the type. Example:

``` kotlin
@Ann(
    type = @Type(
        classifier = Foo::class, 
        args = arrayOf(
            @TypeArgument(
                type = @Type(
                    classifier = Bar::class
                ),
                projection = NONE
            )
        )
    )
)
``` 

This seems like a more verbose representation (more bytes in classfiles), but it's easier to identify any type precisely (see below). 

### Local/Anonymous types

There's an issue with identifying an arbitrary type: some types are local/anonymous. For such cases we'd need to use an unambiguous name in the string representation. Such a name is available, of course, but it's platform-dependent and compiler-dependent, e.g. `TestKt$text$1$Local1$main$1`. We could devise another stable schema for identifying types, but it's extra work and extra contract to support.  

In the structured representation approach all classifiers are represented the same as classes are represented currently. All platforms need to support it anyways. 

> **Note** that, basically, the difference here is whether we embed compiler-generated class names into strings (and we are responsible for these strings), or we emit them into a constant pool as class literals and the JVM spec is responsible for them.

### Platform/dynamic types

It looks like there's no need in representing platform types unless we support a true `typeof(expr)` that can materialize a type of any expression, i.e. any valid type at all.

On the other hand, `dynamic` type has to be represented, because it can be mentioned explicitly in the Kotlin/JS code.

## Open questions

- `KType` doesn't have means of capturing the type itself (unlike `KClass` that has a type parameter that captures the represented class as a static type): so, there won't be a straightforward way to constrain the types relevant for a given parameter. 

> A possible approach here could be through annotations:
> ``` kotlin
> annotation class Ann(val type: @SubtypeOf(Foo<Bar>) KType)    
> ```

## Arguments against this proposal
 
- The use cases are not too many, one notable example is [Scope control for builders](https://github.com/Kotlin/KEEP/pull/38)
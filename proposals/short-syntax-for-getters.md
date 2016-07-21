# Short syntax for property getters

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Contributors**: Valentin Kipiatkov
* **Status**: Under consideration
* **Prototype**: Not started

## Feedback 

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/TODO).

## Synopsis

`Val`'s are often implemented with a one-line getter:

``` kotlin
val length: Int
    get() = size
```

This syntax is verbose in at least three ways:
- it requires a type annotation,
- it requires a `get()`, 
- the `get()` is usually on a separate line.

This is relevant for

- simple properties: `val length: Int get() = size`
- overridden properties: `override val length: Int get() = 0`

In the projects we have access to, we have the following frequency of such cases:

| Project | Total properties | Simple get-only properties | Overridden get-only properties |
| ------- | ---------------- | ----------- | ------ |
| Kotlin        | # (TODO) | #(%) | #(%) |
| Ktor          | # (TODO) | #(%) | #(%) |
| IntelliJ IDEA | # (TODO) | #(%) | #(%) |
| JB Project 1  | # (TODO) | #(%) | #(%) |
| JB Project 2  | # (TODO) | #(%) | #(%) |
| All of GitHub | # (TODO) | #(%) | #(%) |

**NOTE**: The straightforward syntax like `override val length = 0` has the performance disadvantage of creating an unnecessary field for such a property.  

## Relevant YouTrack issues

- [KT-550](https://youtrack.jetbrains.com/issue/KT-550) Properties without initializer but with get[/set] must infer type from getter/setter
- [KT-12996](https://youtrack.jetbrains.com/issue/KT-12996) Simplify the case of overriding only one accessor
- [KT-487](https://youtrack.jetbrains.com/issue/KT-487) Allow extension property initializer to be a getter shorthand
- [KT-6519](https://youtrack.jetbrains.com/issue/KT-6519) Setter only properties

## Possible solutions

### Option 1. Optimization-based

If we don't emit backing fields for constants, the cases above can be written simply as `override val length = 0`.
 
- **pro**: no syntax
- **con**: many things are not constants, e.g. `emptyList()`
- **con**: issues with reflection/annotations etc when field is absent

### Option 2. Different sign for "="

- `val length => 0`
- `val length := 0`
- ...

- **pro**: syntax change is local
- **con**: such signs are not self-explanatory (and look rather alien)
 
### Option 3. Parentheses to indicate a computed property

We could put parentheses after the name of a computed property:

``` kotlin
override val length() = 0
```

- **pro**: this is more or less aligned with Scala's and Groovy's `def length() = 0`
- **con**: `val length(): Int = 0` doesn't look all that smooth
- **con**: this break the symmetry between the call site an the declarartion site: before this we had either parenthese on both sites, or on no site

**NOTE**: A variation of this is `val length: Int ()= 0`, which makes `()=` a new operator, but this looks really weird when we can't put a space between `()` and `=`.
  
## Feature interaction
  
- Can the short syntax be used for a `var`?
- Can it be used for a property with a backing field?  

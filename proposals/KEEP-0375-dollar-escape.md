# Multi-dollar interpolation

* **Type**: Design proposal
* **Authors**: Alejandro Serrano Mena
* **Discussion**: [KEEP-375](https://github.com/Kotlin/KEEP/issues/375)
* **Status**: Experimental expected for 2.1
* **Prototype**: Implemented in [this branch](https://github.com/JetBrains/kotlin/compare/rr/serras/dollar-escape-3)
* **Related YouTrack issue**: [KT-2425](https://youtrack.jetbrains.com/issue/KT-2425/Provide-a-way-for-escaping-the-dollar-sign-symbol-in-multiline-strings-and-string-templates)

## Abstract

We propose an extension of string literal syntax to improve the situation around `$` in string literals. Literals may configure the amount of `$` characters required for interpolation.

## Table of Contents

* [Abstract](#abstract)
* [Table of Contents](#table-of-contents)
* [Motivating examples](#motivating-examples)
  * [Single-line string literals](#single-line-string-literals)
  * [Additional requirements](#additional-requirements)
* [Proposed solution](#proposed-solution)
* [Alternatives](#alternatives)

## Motivating examples

Strings are one of the fundamental types in Kotlin, developers routinely create (parts of) them by using string literals. However, the current design has a few inconveniences, as witnessed by this [YouTrack issue](https://youtrack.jetbrains.com/issue/KT-2446/String-literals). This KEEP pertains to how to improve the situation around `$`. It is a non-goal to change any behavior of string literals, including indentation (or stripping thereof).

[Kotlin's multiline strings](https://kotlinlang.org/docs/strings.html#multiline-strings) are raw, that is, every character from the start to the end markers is taken as it appears. In particular, there are no escaping sequences (`\n`, `\t`, ...) as found in single-line strings. Still, `$` is used to mark interpolation. If you need that character in the string, the most often used workaround is to interpolate the character, leading to an awkward sequence of characters, like `${'$'}`.

One important use case is embedding some pieces of code in which `$` is required by the syntax. Here is a (non-exhaustive) list of languages where `$` appears quite often:

- [JSON Schema](https://json-schema.org/learn/getting-started-step-by-step) uses `$` to define schema parameters.
- [GraphQL](https://graphql.org/learn/queries/#variables) requires variable names to be prefixed by `$`.
- Shell scripts often use `$`, as highlighted in [this discussion](https://teamcity-support.jetbrains.com/hc/en-us/community/posts/360006480400-Write-literal-bash-script-in-kotlin-string-?page=1#community_comment_360000882020).

```kotlin
val jsonSchema: String = """
{
  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
  "${'$'}id": "https://example.com/product.schema.json",
  "title": "Product",
  "description": "A product in the catalog",
  "type": "object"
}
             """
```

It is desirable for string literals that embed a schema or script in those languages to not require any changes in comparison to a standalone file. As a result, some IDE features like [_Language Injections_](https://www.jetbrains.com/help/idea/using-language-injections.html#edit_injected_fragment) provide a better user experience.

Furthermore, the use of `${'$'}` as a workaround has additional (bad) consequences if in the future Kotlin implements a feature akin to string templates. That `'$'` character would appear as one of the interpolated values, instead of as "static part" of the string.

### Single-line string literals

Single-line strings have their own ways of escaping the `$` character, namely a backslash,

```kotlin
val order = Order(product = "Guitar", price = 120)

val amount = "${order.product} costs \$${order.price}"
println(amount)
// Guitar costs $120
```

However, having a way for the `$` character to appear verbatim has some use cases. The main one is [better interoperability with i18n software](https://youtrack.jetbrains.com/issue/KT-7258/String-interpolation-plays-badly-with-i18n-and-string-positioning). For example, GNU `gettext` requires `%n$` to appear [verbatim in program source](https://www.gnu.org/software/gettext/manual/html_node/c_002dformat-Flag.html). This is currently not possible, since escaping is only available using `\$`.

```kotlin
String.format(tr("Could not copy the dropped file into the %1\$s application directory: %2\$s"), a, b)
```

Furthermore, we prefer to have fewer differences between the different kinds of string literals in the language.

### Additional requirements

Two additional requirements inform our proposed solution. First, any proposed solution must _not_ change the meaning of any existing string literal.

Second, interpolation must still be available in some form. For example, we would like the following code, in which `title` is  computed from the members of the receiver `KClass`, to be expressible in the new syntax.

```kotlin
val KClass<*>.jsonSchema: String
  get()= """
{
  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
  "${'$'}id": "https://example.com/product.schema.json",
  "title": "${simpleName ?: qualifiedName ?: "unknown"}",
  "type": "object"
}
         """
```

## Proposed solution

> The solution is inspired by [C# 11's raw string literals](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-11.0/raw-string-literal#detailed-design-interpolation-case).

Every string literal, single- or multiline, may be **prefixed** by a sequence of one or more `$` characters, before the quotes.

* Single-line literals begin with `"`, and multiline literals with `"""`, as currently.
* No character is allowed between the block of `$` characters and the first quote.

Using a single `$` as the prefix is allowed, but should result in a warning.

Having more than one `$` character prefixing the string changes **interpolation**. Instead of a single `$`, interpolation is done using the same amount of `$` characters as in the prefix.

* For string literals without a `$` prefix, the rule stays the same. That is, one `$` character is used.
* In single-line literals, `\$` does _not_ count as one of the characters for interpolation. For example, `$$"$\$$hello"` represents the value `$$$hello`; the first dollar is not enough to start interpolation, `\$` is taken as a verbatim dollar, and the final dollar is again not enough to start interpolation.

Using this rule, the definition of `jsonSchema` for a `KClass` reads as follows.

```kotlin
val KClass<*>.jsonSchema: String
  get()= $$"""
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://example.com/product.schema.json",
  "title": "$${simpleName ?: qualifiedName ?: "unknown"}",
  "type": "object"
}
         """
```

We can also satisfy the requirements of GNU `gettext` of `$` appearing verbatim.

```kotlin
String.format(tr($$"Could not copy the dropped file into the %1$s application directory: %2$s"), a, b)
```

Blocks of consecutive `$` characters longer than the prefix should be understood as a block of `$` characters followed by an interpolation.

```kotlin
val amount = $$"$${order.product} costs $$${order.price}"
println(amount)
// Guitar costs $120
```

We acknowledge that this solution does _not_ solve the problem of escaping (three or more) `"` characters inside a multiline string. The workaround is using `${"\"\"\""}`, or similar code which interpolates a single-line string with the three symbols.

## Alternatives

Apart from the proposed solution, some alternatives have been considered. This section describes them and the reason why they have been rejected.

**Keep the current status quo.** As mentioned in the _Motivating examples_ section, using `${'$'}` to escape the dollar character in multiline strings interacts badly with potential string templates. In particular, something that should be thought of as a "static part" of the string template appears as one of the "dynamic parts".

**Add escaping syntax without a prefix.** There are many syntactical possibilities, like `${}` or `$_`, but all share the fact that they would be added to multiline strings. The main problem here is backward compatibility: whereas before `$_` represented a dollar and an underscore, now simply represents a dollar. This means we would need a long period before making the actual change. In contrast, the proposed solution works right away, since the new escaping mechanism is opt-in: you need to prefix the string with some dollar character to be able to escape it.

**IDE-assisted replacement.** Another solution is to use a character different from `$`, like `%`, and then programmatically replace the latter with the former, `.replace('%', '$')`. This could even be assisted by the IDE, in the same way that IntelliJ now suggests adding `.trimIndent()` to multiline strings. The main problem is the interaction with interpolation, since the `.replace` call affects also the interpolated values; however, this is oftentimes not the intended behavior.

**Escaping quotes.** A previous iteration of this proposal also allowed to begin a multiline string literal with a number of `"` characters different than three, which had to be matched to end the string literal. However, this would be different to the behavior of current multiline strings,

```kotlin
val thing = """"thing""""
println(thing)
// "thing"
```

This behavior is [used quite often](https://github.com/search?q=%22%22%22%22+language%3AKotlin&type=code&ref=advsearch). Although we could provide the new behavior only when the string literal also begins with `$`, it would break uniformity across the different kinds of literals.

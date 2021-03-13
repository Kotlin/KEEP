# Extensions to find all occurrences of a pattern in a CharSequence

* **Type**: Standard Library API proposal
* **Author**: Christian Brüggemann
* **Status**: Declined
* **Prototype**: Implemented
* **Discussion**: [KEEP-20](https://github.com/Kotlin/KEEP/issues/20)



## Summary

An extension method `CharSequence.occurrencesOf(pattern: CharSequence, ignoreCase: Boolean, matchOverlapping: Boolean): Sequence<Int>` is proposed.

It returns a lazy sequence of all indices where the non-regex literal pattern is found in the receiver, based on a linear-time algorithm.

If `ignoreCase` is set to `true`, the method treats all characters case-insensitively, meaning that two characters are considered equal if `Char.equals(other = x, ignoreCase = true)` returns true.

If `matchOverlapping` is set to `false`, only non-overlapping occurrences are matched, meaning that when an occurrence is found, the method does not look for occurrences of the pattern straddling the occurrence that was found just now. Otherwise, they are matched as well.

## Similar API review

* Repeated calls to `indexOf` with incremented startIndex with superlinear runtime, especially if overlapping occurrences are searched for.
* Similarly, a regex pattern can be used to achieve this with the same properties as repeated calls to `indexOf`.

## Use cases

This is a very fundamental method that can be used in a wide-range of specific use cases. Many of them can be grouped into the following categories:

1. Search for words/phrases in big documents, web pages, etc., be it in a web search engine, a text editor or even a word processor. A search function is built into many programs, which could benefit from this method.
2. Examples in bioinformatics: Find subsequences of DNA,  proteins that are known to be important.
3. Natural language processing: Find out in which context a word is used.

## Alternatives

* Use regular expressions:
```kotlin
val occurrences = Regex.fromLiteral(pattern).findAll(text).map { it.range.start }
```

* With the solutions presented in the *Similar API review*, a loop would be needed:
```kotlin
// given text: String and pattern: String
val result = mutableListOf<Int>()
var index = 0
while (true) {
    index = text.indexOf(pattern, index)
    if (index < 0) break
    result.add(index)
    index += pattern.length
}
```
* or a manual sequence implementation:
```kotlin
val result = Sequence {
    object : AbstractIterator<Int>() {
        var index = 0
        override fun computeNext() {
            index = text.indexOf(pattern, index)
            if (index < 0)
                done()

            setNext(index)
            index += pattern.length
        }
    }
}
```

However, this proposal is not about current solutions being too verbose, but too slow.


## Dependencies

* CharSequence
* IntArray
* Sequence

## Placement

 - module: `kotlin-stdlib`
 - package: `kotlin.text`

## Reference implementation

The API is already completely [implemented](https://github.com/JetBrains/kotlin/pull/821). The implementation uses early returns for trivial solutions and otherwise uses the Knuth-Morris-Pratt algorithm, which runs in `O(n)` where `n` is the length of the text that is analyzed for occurrences of the pattern.
Tests are also already implemented.

## Unresolved questions

* Is the operation really convenient for the use cases given?

## Future advancements

In the very initial proposal, the method did not have support for finding only non-overlapping occurrences and was case-sensitive. Both of these issues have been eliminated and as such, the method is already very versatile and should not be subject to further extensions.

Depending on how efficient `CharSequence.indexOf` currently is, the Knuth-Morris-Pratt part could be useful for that method as well. In the future, this could be evaluated with benchmarks. At a glance, it looks like its [implementation](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/text/Strings.kt#L832) is naive.

# Extensions to find all occurrences of a pattern in a CharSequence

* **Type**: Standard Library API proposal
* **Author**: Christian Brüggemann
* **Status**: Submitted
* **Prototype**: [Implemented](https://github.com/JetBrains/kotlin/pull/821)


## Summary

An extension method `CharSequence.occurrencesOf(pattern: CharSequence, ignoreCase: Boolean, matchOverlapping: Boolean): Sequence<Int>` is proposed. 
It returns a lazy sequence of all indices where the non-regex pattern is found in the receiver, based on a linear-time algorithm.
If `ignoreCase` is set to `true`, the method treats all characters case-insensitively, meaning that two characters are considered equal if `Char.equals(other = x, ignoreCase = true)` returns true.
If `matchOverlapping` is set to `false`, only non-overlapping occurrences are matched, meaning that when an occurrence is found, the method does not look for occurrences of the pattern straddling the occurrence that was found just now. Otherwise, they are matched as well.

## Similar API review

* Repeated calls to `indexOf` with incremented startIndex with superlinear runtime, especially if overlapping occurrences are searched for.
* Similarly, a regex pattern can be achieved to do this with the same properties as repeated calls to `indexOf`.

## Use cases

**TODO**

## Alternatives

* With the solutions presented in the *Similar API review*, a loop would be needed.
* This proposal is not about current solutions being too verbose, but too slow.

## Dependencies

* CharSequence
* IntArray
* Sequence

## Placement

The method is supposed to be placed inside `kotlin/text/Strings.kt`.

## Reference implementation

The API is already completely [implemented](https://github.com/JetBrains/kotlin/pull/821). The implementation uses early returns for trivial solutions and otherwise uses the Knuth-Morris-Pratt algorithm, which runs in `O(n)` where `n` is the length of the text that is analyzed for occurrences of the pattern.
Tests are also already implemented.

## Unresolved questions

None.

## Future advancements

In the very initial proposal, the method did not have support for finding only non-overlapping occurrences and was case-sensitive. Both of these issues have been eliminated and as such, the method is already very versatile and should not be subject to further extensions.

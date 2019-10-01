# Allow 'break' and 'continue' inside 'when' expression

* **Type**: Design proposal
* **Author**: Dmitry Petrov
* **Contributors**:
* **Status**:
* **Prototype**: Implemented

Discussion: https://github.com/Kotlin/KEEP/issues/192

## Problem description

In Kotlin 1.0 `break` and `continue` expressions without labels are forbidden,
because their meaning was subject to further design related to possible fall-through in `when`:

```
fun test(xs: List<String>) {
    FOR_X@for (x in xs) {
        when (x) {
            "a" -> break            // Error in Kotlin 1.0
            "b" -> continue         // Error in Kotlin 1.0
            "A" -> break@FOR_X
            "B" -> continue@FOR_X
        }
    }
}
```

However, the resulting code has proven to be rather cumbersome in cases such as the the example above,
and no reasonable design was proposed for `break` or `continue` usage as a fall-through expression in `when`.

## Design details

Allow `break` and `continue` expressions without labels inside `when`.
Resolve them as `break` and `continue` for the corresponding innermost loop:

```
fun test(xs: List<String>) {
    FOR_X@for (x in xs) {
        when (x) {
            "a" -> break            // Not an error, equivalent to 'break@FOR_X'
            "b" -> continue         // Not an error, equivalent to 'continue@FOR_X'
        }
    }
}
```

All other relevant limitations for `break` and `continue` apply, e.g.:

```
fun test(xs: List<String>) {
    for (x in xs) {
        when (x) {
            "a" -> run { break }    // Error, BREAK_OR_CONTINUE_JUMPS_ACROSS_FUNCTION_BOUNDARY
            "b" -> run { continue } // Error, BREAK_OR_CONTINUE_JUMPS_ACROSS_FUNCTION_BOUNDARY
        }
    }
}
```

## Open questions

None.
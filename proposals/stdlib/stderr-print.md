# Add an API for printing to the standard error stream to the Standard Library

* **Type**: Standard Library API proposal
* **Author**: Niklas Kniep
* **Contributors**:
* **Status**: Submitted
* **YouTrack Issue**: [KT-6064](https://youtrack.jetbrains.com/issue/KT-6064)
* **Prototype**: In progress

## Summary

The Kotlin Standard Library currently provides no multiplatform way to print to the standard error stream (called stderr for the rest of this document).
This proposal aims to fix this by introducing two new functions, `eprint` and `eprintln`, which print their message
with and without the line separator, respectively, to stderr.

```kt
/**
 * Prints the given message to the standard error stream.
 */
fun eprint(message: Any?)

/**
 * Prints the line separator to the standard error stream.
 */
fun eprintln()

/**
 * Prints the given message and the line separator to the standard error stream.
 */
fun eprintln(message: Any?)
```

## Similar API review

* [`print`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/print.html), [`println`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/println.html)
  in kotlin.io provide similar functionality, but print to stdout instead of stderr.
* [`eprint`](https://doc.rust-lang.org/std/macro.eprint.html), [`eprintln`](https://doc.rust-lang.org/std/macro.eprintln.html) in Rust.

## Use cases

* Print debug messages.
* Print error messages without a stacktrace or crashing the program.

## Existing Alternatives

* `print`, `println`. Drawbacks:
  * Prints to stdout and not stderr.
* Throw an exception. Drawbacks:
  * Shows a stack trace, which often isn't useful for users of an application.
  * Crashes the program if it isn't caught.
* Use platform specific functions. Drawbacks:
  * More boilerplate.

## Alternative Designs

* Add a stream argument to `print` and `println` to specify the stream to print to. The stream would default to stdout.

```kt
/**
 * Prints the given message to the stream.
 */
fun print(message: Any?, stream: OutputStream = stdout)

/**
 * Prints the line separator to the stream.
 */
fun println(stream: OutputStream = stdout)

/**
 * Prints the given message and the line separator to the stream.
 */
fun println(message: Any?, stream: OutputStream = stdout)
```

## Dependencies

What are the dependencies of the proposed API:

* Platform specific ways to print to stderr.

## Placement

* Standard Library
* package: kotlin.io

## Reference implementation

```kt
// Common

expect fun eprint(message: Any?)

expect fun eprintln()

expect fun eprintln(message: Any?)

// JVM

actual fun eprint(message: Any?) {
    System.err.print(message)
}

actual fun eprintln() {
    System.err.println()
}

actual fun eprintln(message: Any?) {
    System.err.println(message)
}

// specialized versions for JVM primitives?

// JS

...

// Native

...
```

## Unresolved questions

* Naming: possibilities
  * eprint, eprintln
  * errprint, errprintln
  * printerr, printlnerr
  * ...

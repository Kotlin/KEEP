# Opting out of cancellation exceptions in Swift/ObjC exports

* **Type**: Design proposal
* **Authors**: Gleb Lukianets, Marat Akhin
* **Status**: Draft
* **Discussion**: KEEP-xxxx (to be assigned)

## Abstract

Kotlin suspend functions exported to Swift currently become `async throws` functions because they can complete with `CancellationException`.
The same implicit cancellation error is present in Objective-C export, where suspend functions use an error-bearing completion handler.

This proposal lets an API author disable the automatic inclusion of `CancellationException` in a suspend function's exported error set.
Once cancellation is no longer exported, the function is exported to Swift as `async` (rather than `async throws`), unless other exceptions declared with `@Throws` still require `throws`.
Existing declarations retain their current behavior.

The *exported semantics* of the opt-out are settled and described in [Exported semantics](#exported-semantics).
The *Kotlin surface syntax* is not: there are two viable spellings, and **this document is the entry point for choosing between them.**
Both are fully specified in [Design options](#design-options), and [Choosing between the options](#choosing-between-the-options) lays out the criteria.

## Motivation

Swift's `async` functions are not inherently throwing.
Nevertheless, every exported Kotlin suspend function currently requires `try await`, including functions whose implementations and callees do not propagate cancellation.
This makes the generated Swift API more pessimistic than the contract intended by its Kotlin author.

The opt-out controls the exception contract at the interop boundary.
Kotlin has no cancellation effect system, and the compiler does not attempt to prove whether a suspend function and all of its callees can propagate `CancellationException`.
This is consistent with `@Throws`, which already describes an interop boundary rather than enforcing checked exceptions in Kotlin.

## Exported semantics

The rules in this section are identical for both design options; the two options differ only in how the opt-out is written in Kotlin source (see [Design options](#design-options)).

Opting out removes the implicitly added `CancellationException` from the exported error set, and nothing else.
An explicitly listed `CancellationException`, or an applicable superclass, is handled in the same way as any other exception declared in `@Throws`, so it remains exported even when the implicit addition is disabled.

The generated Apple API follows this table:

| Kotlin declaration | Exported errors | Swift declaration |
|---|---|---|
| `suspend fun f()` | `CancellationException` | `async throws` |
| `@Throws(A::class) suspend fun f()` | `A` and `CancellationException` | `async throws` |
| opt out | none | `async` |
| `@Throws(A::class)` + opt out | `A` | `async throws` |
| `@Throws(CancellationException::class)` + opt out | `CancellationException` | `async throws` |

For Objective-C export, an empty exported error set means that the exported completion handler does not expose an `NSError` result.
For direct Swift export, it means that the generated wrapper is non-throwing.

For example, the Objective-C completion-handler shape changes as follows:

```kotlin
suspend fun load(): Data          // default
// opted out                      // via either design option
suspend fun load(): Data
```

```objc
// default: cancellation is exported, so the handler carries an error
- (void)loadWithCompletionHandler:(void (^)(Data * _Nullable result,
                                            NSError * _Nullable error))completionHandler;

// opted out: no exported errors, so the error parameter is dropped
- (void)loadWithCompletionHandler:(void (^)(Data * _Nullable result))completionHandler;
```

### Unhandled cancellation

If `CancellationException` escapes while it is absent from the resulting exported error set, it receives no cancellation-specific treatment.
The selected export path handles it in exactly the same way as any other exception outside that path's `@Throws` contract; on Kotlin/Native today, that terminates the program.
A non-throwing wrapper does not synthesize a normal result or introduce a cancellation-specific error channel.
This proposal does not define a new common failure mechanism for such exceptions and does not require different exporters to adopt one.

Authors should therefore treat the opt-out as a genuine contract: a function whose cancellation must be observable to the caller should not opt out.

### Redundant use

The opt-out is meaningful only for suspend declarations on the Apple export path.
On any other declaration: a non-suspend function, or a suspend function that is not exported --- it has no semantic effect and should be reported as redundant.

### Overrides

The opt-out is part of a declaration's exported contract, so it must be consistent across an override chain.
An override may not disagree with the declaration it overrides: it can neither reintroduce cancellation that the base opted out of, nor opt out of cancellation that the base exports.
The exported foreign signature is fixed by the base declaration, and callers of that signature already observe a particular throwing shape.
A mismatch is reported as an error.

## Design options

Both options produce exactly the exported API described in [Exported semantics](#exported-semantics).
They differ only in the Kotlin surface syntax and in their effect on the common standard library.

### Option A: a parameter on `kotlin.Throws`

Add a defaulted parameter to `kotlin.Throws`:

```kotlin
public expect annotation class Throws(
    vararg val exceptionClasses: KClass<out Throwable>,
    val includeCancellationException: Boolean = true,
)
```

Its default value is `true`, preserving the existing implicit cancellation error; setting it to `false` performs the opt-out:

```kotlin
@Throws(A::class, includeCancellationException = false)
suspend fun load(): Data
```

**For it.** The policy is expressed on the annotation that already owns the exported exception contract; a single annotation covers both the listed exceptions and the cancellation decision.
It expresses "export `A`, but not cancellation" directly.

**Against it.** `kotlin.Throws` is a common annotation whose JVM meaning is to contribute exception classes to the generated `Exceptions` attribute.
`includeCancellationException` describes an implicit rule of Apple export and has no natural JVM meaning: leaving it ignored on the JVM places a Native-export-only concept in a common annotation API, while making it do anything on the JVM would change existing JVM behavior and bytecode metadata.
Adding a defaulted element after the existing vararg parameter is also a binary- and metadata-compatibility question that must be validated for every actualization of the optional expectation (`kotlin.jvm.Throws` and the others); if that evolution is not compatible, this option is off the table.

### Option B: a Native `@ExportWithoutCancellationException` annotation

Keep `kotlin.Throws` unchanged and introduce a Native optional-expect annotation:

```kotlin
package kotlin.native

@OptionalExpectation
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public expect annotation class ExportWithoutCancellationException
```

The annotation is usable from common source, actualized and interpreted only on Native, and ignored on platforms without an actualization:

```kotlin
@ExportWithoutCancellationException
@Throws(A::class)
suspend fun load(): Data
```

The resulting exported error set contains `A` but not the implicitly added `CancellationException`.

**For it.** It does not change the schema of the common `kotlin.Throws`, and it has a clean JVM story: the concept is simply absent where it has no meaning.
It follows the existing model of Native interop annotations such as `@ObjCName` and `@HiddenFromObjC`, and it is purely additive, avoiding the compatibility question that Option A raises.

**Against it.** It introduces a new export-specific annotation for a policy conceptually owned by `@Throws`, and expressing "export `A`, but not cancellation" requires composing two annotations.
Combining `@ExportWithoutCancellationException` with an explicit `@Throws(CancellationException::class)` is contradictory and should be reported as such.

### Choosing between the options

The decision this KEEP exists to resolve comes down to:

* **Ownership of the concept.** Is opting out of cancellation part of the exception contract (favoring Option A, keeping it on `@Throws`), or a Native interop directive (favoring Option B, alongside `@ObjCName` and friends)?
* **The common-API surface.** How acceptable is it to place a Native-export-only concept — a parameter that is meaningless on the JVM — into the common `kotlin.Throws`?
  Option B avoids this entirely.
* **Compatibility.** What is the actual result of validating a defaulted element added after `vararg` in `@Throws` across all actualizations?
  A negative result eliminates Option A and settles the choice mechanically.
* **Ergonomics.** One annotation carrying the whole contract (Option A) versus composing two annotations (Option B).

Because the exported semantics are identical, this choice does not affect the generated foreign API; it affects only the Kotlin-side spelling and the standard-library surface.

## Scope

This proposal controls only the implicit inclusion of `CancellationException` in Apple exports.
It does not introduce a Kotlin-wide guarantee that a function is non-cancellable, add cancellation information to function types, or provide static verification of the opt-out.

It also does not by itself change how direct Swift export treats the non-cancellation exceptions listed in `@Throws`, whose current handling differs from Objective-C export.
Unifying that behavior is a separate compatibility decision.
Regardless of the mechanism, opting out has the single effect described in [Exported semantics](#exported-semantics): it removes `CancellationException` from the exported error set, after which the export path's ordinary handling of an exception outside the set applies.

## Compatibility

Existing source code retains its meaning under both options:

* a suspend function without `@Throws` continues to export cancellation;
* `@Throws(A::class)` continues to export both `A` and cancellation; and
* existing annotation usages keep exporting cancellation (the default), whether that is expressed as the `true` value of the new parameter or as the absence of the Native annotation.

Adding the opt-out to a published function intentionally changes its exported API.
In Swift, `async throws` becomes `async` when no other exceptions remain.
In Objective-C, the completion-handler shape may change.
This change must therefore be visible to exported API and ABI validation tools.

The two options differ in their own compatibility footprint: Option A requires the annotation-schema validation described in [Option A](#option-a-a-parameter-on-kotlinthrows), whereas Option B is purely additive.

## Cancellation propagation

This proposal does not change cancellation propagation between Swift `Task` and Kotlin `Job`.
Each export path continues to propagate, ignore, or otherwise represent cancellation according to its existing design.

If that behavior causes a Kotlin `CancellationException` to escape from a declaration that does not export it, the exception is handled as described in [Unhandled cancellation](#unhandled-cancellation): through the same path as any other exception outside the declaration's `@Throws` contract, with no synthesized result and no cancellation-specific error channel.

## Alternatives considered

Both spellings above are considered viable and are presented as options rather than alternatives.
The following were considered and rejected.

### Empty `@Throws()`

Using `@Throws()` as the opt-out requires no annotation-schema change, but makes the empty form a special case with semantics opposite to the annotation's name.
It also cannot express "export `A`, but not cancellation" without changing the meaning of existing non-empty annotations.

### Swift Export Gradle setting

A Gradle setting would be non-local and would make a declaration's foreign signature depend on the consuming build configuration.
The contract should instead be stored with the declaration in Kotlin metadata.

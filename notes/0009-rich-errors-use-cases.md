# Design Notes: Evaluating Rich Errors in the Wild 

* **Authors**: Roman Venediktov, Michail Zarečenskij
* **Discussion**: [#498](https://github.com/Kotlin/KEEP/discussions/498)

## Summary

This document demonstrates the expected use of Rich Errors ([KEEP-0462](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0462-rich-errors.md)) on real-world code. We mined three open-source Android codebases: DuckDuckGo-Android (privacy browser, \~100 Gradle modules), Anki-Android (AnkiDroid flashcards app), and Signal-Android (messenger), for places where errors are already treated as values today. 
All code below is real (exact files linked, pinned to the commits we analyzed), only lightly trimmed. The examples are ordered from the simplest to the most complex and are followed by a use-site analysis showing how callers of these APIs can be changed with Rich Errors.

## Motivation

This document is intended to demonstrate the expected use of Rich Errors in real projects.

## Table of Contents

* [Assumptions](#assumptions)
* [A quick look at the numbers](#a-quick-look-at-the-numbers)
* [Examples: declaration sites](#examples-declaration-sites)
  * [Example 1: a generic `Result` class](#example-1-a-generic-result-class)
  * [Example 2: `Success` object + `Failure` with payload](#example-2-success-object--failure-with-payload)
  * [Example 3: `null` for failure + a callback for the error details](#example-3-null-for-failure--a-callback-for-the-error-details)
  * [Example 4: three-way outcomes: success, recoverable warning, failure](#example-4-three-way-outcomes-success-recoverable-warning-failure)
  * [Example 5: one shared result type for functions with different success payloads](#example-5-one-shared-result-type-for-functions-with-different-success-payloads)
  * [Example 6: the "exception in a data class" wrapper](#example-6-the-exception-in-a-data-class-wrapper)
  * [Example 7: a whole API surface — per-endpoint error sets with heavy duplication](#example-7-a-whole-api-surface--per-endpoint-error-sets-with-heavy-duplication)
  * [Example 8: sequential calls with different error sets](#example-8-sequential-calls-with-different-error-sets)
  * [Example 9: nested "success data" hierarchies to multiplex return types](#example-9-nested-success-data-hierarchies-to-multiplex-return-types)
* [Examples: use sites: how callers of APIs with Rich Errors change](#examples-use-sites-how-callers-of-apis-with-rich-errors-change)
  * [Use-site for Example 1: the binary result at its only call site](#use-site-for-example-1-the-binary-result-at-its-only-call-site)
  * [Use-site for Example 2: early-return validation](#use-site-for-example-2-early-return-validation)
  * [Use-site for Example 3: the caller pays for the callback channel too](#use-site-for-example-3-the-caller-pays-for-the-callback-channel-too)
  * [Use-site for Examples 5/9: shared result types force unchecked casts on callers](#use-site-for-examples-59-shared-result-types-force-unchecked-casts-on-callers)
  * [Example 10: the multi-stage pipeline caller that only translates results](#example-10-the-multi-stage-pipeline-caller-that-only-translates-results)
  * [Example 11: caller-side dispatch on outcomes coming from different callees](#example-11-caller-side-dispatch-on-outcomes-coming-from-different-callees)
* [Other projects](#other-projects)
* [Observations](#observations)
* [What about exceptions?](#what-about-exceptions)


## Assumptions

While showing examples of code from different repositories, we do not challenge the choice of error-handling technique or any other architectural decisions. 
We are trying to see and demonstrate how the same signatures could be achieved with Rich Errors.

## A quick look at the numbers

None of the three projects uses Arrow/`Either`. Instead, every project introduces its own result types several times:

| Project            | Custom `sealed ...Result/Error/Failure` types |
|:-------------------|:----------------------------------------------|
| DuckDuckGo-Android | 75                                            |
| Anki-Android       | 18                                            |
| Signal-Android     | 125                                           |

Each of those sealed hierarchies is essentially an encoding of `T | E1 | ... | En`. They cannot be combined across functions, they force a wrapper allocation and an unwrapping `when` at every call site, and they are all slightly different in style (`Result`, `Outcome`, `Failure`, nested `Error` subclasses, exceptions-in-data-classes, callbacks).

A quotable one: Signal wrote its own 469-line, 29-function result monad ([`NetworkResult.kt`](https://github.com/signalapp/Signal-Android/blob/c47a74ba0961903eeaeda6d22347a990056c3e83/core/network/src/main/java/org/signal/network/NetworkResult.kt)) and its KDoc honestly documents the ceiling of the wrapper-class approach:

> *"If you have a very complicated network request with lots of different possible response types based on specific errors, this isn't for you. You're likely better off writing your own sealed class."*

So even within one codebase there are two parallel error-as-value systems (a generic `NetworkResult<T>` plus dozens of custom sealed classes) because a general wrapper cannot serve for all possible errors.

## Examples: declaration sites

### Example 1: a generic `Result` class

DuckDuckGo, [`CustomHeaderAllowedChecker.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/app/src/main/java/com/duckduckgo/app/browser/trafficquality/CustomHeaderAllowedChecker.kt)

The `Result` class contains quite typical "Successful" and "Failure" inheritors that can hold configuration data:

```kotlin
interface CustomHeaderAllowedChecker {
    fun isAllowed(): Result<TrafficQualityAppVersion>
}

sealed class Result<out R> {
    data class Allowed(val config: TrafficQualityAppVersion) : Result<TrafficQualityAppVersion>()
    data object NotAllowed : Result<Nothing>()
}
```

Note the artifacts: the generic parameter `R` is fake (only ever instantiated with one type), `Allowed` is a wrapper allocation around the config, and callers must pattern-match.

With Rich Errors:

```kotlin
error object NotAllowed

interface CustomHeaderAllowedChecker {
    fun isAllowed(): TrafficQualityAppVersion | NotAllowed
}
```

The declaration shrinks from 4 lines to 1, the success value is not wrapped, the name clash with `kotlin.Result` disappears, and the call site can use `ifError { return }` \+ smart-cast instead of a `when` (see the [use-site section](#use-site-for-example-1-the-binary-result-at-its-only-call-site) below).

### Example 2: `Success` object \+ `Failure` with payload

AnkiDroid, [`NoteFieldsCheckResult.kt`](https://github.com/ankidroid/Anki-Android/blob/48fa7785980b5b71454769b78e0622afb4f4a9b7/AnkiDroid/src/main/java/com/ichi2/anki/NoteFieldsCheckResult.kt)

```kotlin
sealed interface NoteFieldsCheckResult {
    data object Success : NoteFieldsCheckResult

    /** @property localizedMessage user-readable error message */
    data class Failure(val localizedMessage: String?) : NoteFieldsCheckResult
}

suspend fun checkNoteFieldsResponse(note: Note): NoteFieldsCheckResult
```

This is the inverted shape of Example 1: the *error* carries some data, and the success is an empty marker. With Rich Errors the marker object is simply `Unit`:

```kotlin
error class InvalidNoteFields(val localizedMessage: String?)

suspend fun checkNoteFieldsResponse(note: Note): Unit | InvalidNoteFields
```

For readability purposes we may also introduce something like `typealias Ok = Unit` to have signatures `fun
checkNoteFieldsResponse(note: Note): Ok | InvalidNoteFields`

### Example 3: `null` for failure \+ a callback for the error details

AnkiDroid, [`MediaRegistration.kt`](https://github.com/ankidroid/Anki-Android/blob/48fa7785980b5b71454769b78e0622afb4f4a9b7/AnkiDroid/src/main/java/com/ichi2/anki/MediaRegistration.kt)

In the following example, the value travels through the return type as `String?`, while the error travels through an "out"-parameter `DisplayMediaError` callback 

```kotlin
private typealias DisplayMediaError = (MediaRegistration.MediaError) -> Unit

object MediaRegistration {
    sealed class MediaError {
        data object GenericError : MediaError()
        data class GenericErrorTryAgain(val details: String?) : MediaError()
        class ConversionError(val message: String) : MediaError()
        data object ImageTooLarge : MediaError()
        data object VideoTooLarge : MediaError()
        data object AudioTooLarge : MediaError()
    }

    /**
     * @param showError A callback function for displaying error messages based on media error type.
     * @return A string reference to the media if successfully processed, or null if an error occurred.
     */
    fun onPaste(
        context: Context,
        uri: Uri,
        description: ClipDescription,
        pasteAsPng: Boolean,
        showError: DisplayMediaError,
    ): String?
}
```

The type system cannot verify that error handling is consistent in terms of success or error result: nothing stops an implementation from returning `null` without invoking `showError`, or invoking it and still returning a value. Internally, every failing helper must both call `showError(...)` and `return null`.

With Rich Errors we can get rid of the "out"-parameter and move everything to the return type:

```kotlin
error object GenericMediaError
error class GenericMediaErrorTryAgain(val details: String?)
error class MediaConversionError(val message: String)
error object ImageTooLarge
error object VideoTooLarge
error object AudioTooLarge

typealias MediaError = GenericMediaError | GenericMediaErrorTryAgain
    | MediaConversionError | ImageTooLarge | VideoTooLarge | AudioTooLarge

fun onPaste(
    context: Context,
    uri: Uri,
    description: ClipDescription,
    pasteAsPng: Boolean,
): String | MediaError
```

Now the inconsistency disappears, and the caller decides where to render the error instead of having rendering pushed into every callee.

### Example 4: three-way outcomes: success, recoverable warning, failure

AnkiDroid, [`InstantEditorViewModel.kt`](https://github.com/ankidroid/Anki-Android/blob/48fa7785980b5b71454769b78e0622afb4f4a9b7/AnkiDroid/src/main/java/com/ichi2/anki/instantnoteeditor/InstantEditorViewModel.kt)

```kotlin
sealed class SaveNoteResult {
    data object Success : SaveNoteResult()
    data class Failure(val message: String? = null) : SaveNoteResult()
    /** Example: when user tries to save cloze field with no cloze */
    data class Warning(val message: String?) : SaveNoteResult()
}
```

Here the `SaveNoteResult` class is needed to have an additional error outcome (`Warning`). This is actually a common objection to `Either`\-style handling as errors are not always binary. 

With Rich Errors it's straightforward to handle N-ary outcomes: one more error outcome is just another member of the union:

```kotlin
error class SaveNoteFailure(val message: String? = null)
error class SaveNoteWarning(val message: String?)

suspend fun saveNote(): Unit | SaveNoteFailure | SaveNoteWarning
```

Notably, now it's easier to narrow the type as smart-casts work naturally with Rich errors.

### Example 5: one shared result type for functions with different success payloads

DuckDuckGo, [`CaptchaResolver.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/pir/pir-impl/src/main/java/com/duckduckgo/pir/impl/common/CaptchaResolver.kt)

Sealed hierarchies can be expensive to declare, so one hierarchy might be reused across several operations, leading to worse precision and, essentially, dead code as in the following case. 
Both functions below return the same `CaptchaResolverResult`, but can produce only one "success" result:

```kotlin
interface CaptchaResolver {
    suspend fun submitCaptchaInformation(...): CaptchaResolverResult   // never SolveCaptchaSuccess
    suspend fun getCaptchaSolution(...): CaptchaResolverResult         // never CaptchaSubmitSuccess

    sealed class CaptchaResolverResult {
        data class CaptchaSubmitSuccess(val transactionID: String) : CaptchaResolverResult()
        data class SolveCaptchaSuccess(val token: String, val meta: CaptchaSolutionMeta) : CaptchaResolverResult()
        data class CaptchaFailure(val code: Int, val type: CaptchaResolverError, val message: String) : CaptchaResolverResult()
    }

    sealed class CaptchaResolverError {
        data object SolutionNotReady : CaptchaResolverError()
        data object UnableToSolveCaptcha : CaptchaResolverError()
        data object CriticalFailure : CaptchaResolverError()
        data object InvalidRequest : CaptchaResolverError()
        data object TransientFailure : CaptchaResolverError()
        data object ClientFailure : CaptchaResolverError()
    }
}
```

Every caller of `submitCaptchaInformation` must either write a dead `is SolveCaptchaSuccess ->` branch or an `else ->` that gives up exhaustiveness checking. Also notice the double encoding of the error: a `CaptchaFailure` wrapper containing a second sealed enum-like `CaptchaResolverError`.

With Rich Errors, the errors are declared once, and each function states its precise contract:

```kotlin
error class CaptchaFailure(val code: Int, val error: CaptchaResolverError, val message: String) { 
    sealed class CaptchaResolverError {
        data object SolutionNotReady : CaptchaResolverError()
        data object UnableToSolveCaptcha : CaptchaResolverError()
        data object CriticalFailure : CaptchaResolverError()
        data object InvalidRequest : CaptchaResolverError()
        data object TransientFailure : CaptchaResolverError()
        data object ClientFailure : CaptchaResolverError()
    }
}

interface CaptchaResolver {
    suspend fun submitCaptchaInformation(...): CaptchaTransactionId | CaptchaFailure
    suspend fun getCaptchaSolution(...): CaptchaSolution | CaptchaFailure
}
```

Now impossible states become unrepresentable, callers benefit from exhaustiveness.

### Example 6: the "exception in a data class" wrapper

DuckDuckGo, [`SavedSitesImporter.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/saved-sites/saved-sites-api/src/main/java/com/duckduckgo/savedsites/api/service/SavedSitesImporter.kt)

```kotlin
sealed class ImportSavedSitesResult {
    data class Success(val savedSites: List<Any>) : ImportSavedSitesResult()
    data class Error(val exception: Exception) : ImportSavedSitesResult()
}

suspend fun import(uri: Uri, importFolder: ImportFolder = ImportFolder.Root): ImportSavedSitesResult
```

This pattern (a sealed wrapper whose error case just carries an `Exception`) appears many times across all three repos. 
Kotlin does offer its own `kotlin.Result` for exactly this "value or exception" shape, but it seems to be avoided in practice due to being unergonomic, leading to the introduction of custom sealed wrappers instead. Rich Errors give a direct equivalent with no wrapper on the success path:

```kotlin
error class ImportFailed(val exception: Exception)

suspend fun import(uri: Uri, importFolder: ImportFolder = ImportFolder.Root): List<SavedSite> | ImportFailed
```

### Example 7: a whole API surface — per-endpoint error sets with heavy duplication

Signal, [`NetworkController.kt`](https://github.com/signalapp/Signal-Android/blob/c47a74ba0961903eeaeda6d22347a990056c3e83/feature/registration/src/main/java/org/signal/registration/NetworkController.kt)

This is the most instructive example. The registration API declares \~20 endpoints, each returning `RequestResult<T, E>` (a generic result monad from libsignal) with a dedicated sealed error class per endpoint (17 sealed hierarchies in the end):

```kotlin
suspend fun createSession(e164: String, ...): RequestResult<SessionMetadata, CreateSessionError>
suspend fun getSession(sessionId: String): RequestResult<SessionMetadata, GetSessionStatusError>
suspend fun updateSession(...): RequestResult<SessionMetadata, UpdateSessionError>
suspend fun submitVerificationCode(...): RequestResult<SessionMetadata, SubmitVerificationCodeError>
suspend fun registerAccount(...): RequestResult<RegisterAccountResponse, RegisterAccountError>
suspend fun restoreMasterKeyFromSvr(...): RequestResult<MasterKeyResponse, RestoreMasterKeyError>
// ... 14 more

sealed class CreateSessionError : BadRequestError {
    data class InvalidRequest(val message: String) : CreateSessionError()
    data class RateLimited(val retryAfter: Duration) : CreateSessionError()
}
sealed class UpdateSessionError : BadRequestError {
    data class RejectedUpdate(val message: String) : UpdateSessionError()
    data class InvalidRequest(val message: String) : UpdateSessionError()
    data class RateLimited(val retryAfter: Duration, val session: SessionMetadata) : UpdateSessionError()
}
sealed class RegisterAccountError : BadRequestError {
    data class SessionNotFoundOrNotVerified(val message: String) : RegisterAccountError()
    data class RegistrationRecoveryPasswordIncorrect(val message: String) : RegisterAccountError()
    data object DeviceTransferPossible : RegisterAccountError()
    data class InvalidRequest(val message: String) : RegisterAccountError()
    data class RegistrationLock(val data: RegistrationLockResponse) : RegisterAccountError()
    data class RateLimited(val retryAfter: Duration) : RegisterAccountError()
}
// ... 14 more hierarchies
```

Two things to notice.

First — duplication. Because sealed subclasses are tied to their parent, common error cases must be re-declared inside every hierarchy. In this single file:

- `InvalidRequest(message)` is declared 11 times.
- `RateLimited(retryAfter)` — 9 times (with slight variations in payload).  
- `SessionNotFound(message)` — 3 times, `Unauthorized` — 4 times, `NotRegistered` — 3 times.

A downstream `handleRateLimited(...)` helper cannot be written against "any RateLimited" — there is no common type. With Rich Errors, the flat error hierarchy makes errors shared building blocks composed by unions:

```kotlin
error class InvalidRequest(val message: String)
error class RateLimited(val retryAfter: Duration, val session: SessionMetadata? = null)
error class SessionNotFound(val message: String)
error object Unauthorized
error class WrongPin(val triesRemaining: Int)
error object NoSvrDataFound
// ... one declaration per distinct error, ~15 total instead of ~50 subclasses

typealias CreateSessionError = InvalidRequest | RateLimited
typealias UpdateSessionError = RejectedUpdate | InvalidRequest | RateLimited
typealias RegisterAccountError = SessionNotFoundOrNotVerified | RegistrationRecoveryPasswordIncorrect
    | DeviceTransferPossible | InvalidRequest | RegistrationLock | RateLimited

suspend fun createSession(e164: String, ...): SessionMetadata | CreateSessionError
suspend fun registerAccount(...): RegisterAccountResponse | RegisterAccountError
```

Now `fun handleRateLimited(e: RateLimited)` is expressible, error sets are visibly composed from shared vocabulary, and adding/removing an error from an endpoint is a one-token change to a typealias.

Second, the wrapper is not expressive enough anyway. `RequestResult` also carries transport-level cases (`RetryableNetworkError`, `ApplicationError`), so every call site handles two nested levels of dispatch (see Example 8). With Rich Errors, transport errors are just two more members of the union — same level, one `when`:

```kotlin
error class NetworkFailure(val cause: IOException)   // retryable
error class UnexpectedFailure(val cause: Throwable)

suspend fun createSession(...): SessionMetadata | CreateSessionError | NetworkFailure | UnexpectedFailure
```

And the generic `RequestResult<T, E>` monad is no longer needed at all.

### Example 8: sequential calls with different error sets

Signal, [`PinEntryForRegistrationLockViewModel.kt`](https://github.com/signalapp/Signal-Android/blob/c47a74ba0961903eeaeda6d22347a990056c3e83/feature/registration/src/main/java/org/signal/registration/screens/pinentry/PinEntryForRegistrationLockViewModel.kt)

A real two-stage flow: restore the master key from SVR, then register the account with the derived lock token. Today each stage needs a 4-branch `when` over `RequestResult`, with a nested `when` over the endpoint-specific error inside the `NonSuccess` branch (abbreviated, the real function is \~120 lines):

```kotlin
val restoreResult = repository.restoreMasterKeyFromSvr(svrCredentials, event.pin, forRegistrationLock = true)

val masterKey: MasterKey = when (restoreResult) {
    is RequestResult.Success -> restoreResult.result.masterKey
    is RequestResult.NonSuccess -> {
        return when (val error = restoreResult.error) {
            is NetworkController.RestoreMasterKeyError.WrongPin -> { /* update tries counter / lock */ }
            is NetworkController.RestoreMasterKeyError.NoDataFound -> { /* account locked */ }
        }
    }
    is RequestResult.RetryableNetworkError -> return state.copy(oneTimeEvent = OneTimeEvent.NetworkError)
    is RequestResult.ApplicationError -> return state.copy(oneTimeEvent = OneTimeEvent.UnknownError)
}

// ... derive registrationLockToken from masterKey ...

val registerResult = repository.registerAccountWithSession(e164, sessionId, registrationLockToken, ...)

return when (registerResult) {
    is RequestResult.Success -> { /* proceed */ }
    is RequestResult.NonSuccess -> when (val error = registerResult.error) { /* 6 cases */ }
    is RequestResult.RetryableNetworkError -> state.copy(oneTimeEvent = OneTimeEvent.NetworkError)
    is RequestResult.ApplicationError -> state.copy(oneTimeEvent = OneTimeEvent.UnknownError)
}
```

With Rich Errors the two dispatch levels flatten into one, the shared transport errors (`NetworkFailure`, `UnexpectedFailure`) can be handled once for both stages, and the domain-specific errors keep their dedicated handling:

```kotlin
suspend fun restoreMasterKeyFromSvr(...): MasterKeyResponse | WrongPin | NoSvrDataFound | NetworkFailure | UnexpectedFailure
suspend fun registerAccountWithSession(...): RegisterAccountResponse | RegisterAccountError | NetworkFailure | UnexpectedFailure

val masterKey = repository.restoreMasterKeyFromSvr(svrCredentials, event.pin, forRegistrationLock = true)
    .ifError { error ->
        return when (error) {
            is WrongPin -> { /* update tries counter / lock */ }
            is NoSvrDataFound -> { /* account locked */ }
            is NetworkFailure -> state.copy(oneTimeEvent = OneTimeEvent.NetworkError)
            is UnexpectedFailure -> state.copy(oneTimeEvent = OneTimeEvent.UnknownError)
        }
    }.masterKey  // smart-cast to MasterKeyResponse after ifError with Nothing-returning handler

// ... derive registrationLockToken ...

return when (val result = repository.registerAccountWithSession(e164, sessionId, registrationLockToken, ...)) {
    is RegisterAccountResponse -> { /* proceed */ }
    is SessionNotFoundOrNotVerified, is InvalidRequest -> { /* reset session */ }
    is RegistrationLock -> { /* still locked */ }
    is RateLimited -> { /* show retryAfter */ }
    is NetworkFailure -> state.copy(oneTimeEvent = OneTimeEvent.NetworkError)
    is UnexpectedFailure -> state.copy(oneTimeEvent = OneTimeEvent.UnknownError)
}
```

If chain-call grows (e.g., an intermediate `getSession` call), the value-path stays the same via `|.` chaining while the error set of the chain is accumulated automatically by the type system. 
With the sealed-class encoding, each added call adds another full 4-branch `when` pyramid.

### Example 9: nested "success data" hierarchies to multiplex return types

DuckDuckGo, [`NativeBrokerActionHandler.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/pir/pir-impl/src/main/java/com/duckduckgo/pir/impl/common/NativeBrokerActionHandler.kt)

When a single entry point serves several operations, the wrapper approach forces success payloads into their own nested sealed hierarchy (three levels deep here):

```kotlin
suspend fun pushAction(nativeAction: NativeAction): NativeActionResult

sealed class NativeActionResult {
    data class Success(val data: NativeSuccessData) : NativeActionResult() {
        sealed class NativeSuccessData {
            data class Email(val generatedEmailData: GeneratedEmailData) : NativeSuccessData()
            data class CaptchaTransactionIdReceived(val transactionID: String) : NativeSuccessData()
            data class CaptchaSolutionStatus(val status: CaptchaStatus) : NativeSuccessData() {
                sealed class CaptchaStatus {
                    data class Ready(val token: String, val meta: CaptchaSolutionMeta) : CaptchaStatus()
                    data object InProgress : CaptchaStatus()
                }
            }
        }
    }
    data class Failure(val error: PirError, val retryNativeAction: Boolean = false) : NativeActionResult()
}
```

Consumers must unwrap `Success` → `NativeSuccessData` → (sometimes) `CaptchaStatus`, and casts to the expected variant are unchecked by design.
With Rich Errors the natural refactoring is one typed function per action with a shared error:

```kotlin
error class PirActionFailed(val error: PirError, val retryable: Boolean = false)

suspend fun getEmail(brokerName: String): GeneratedEmailData | PirActionFailed
suspend fun submitCaptchaInfo(...): CaptchaTransactionId | PirActionFailed
suspend fun getCaptchaStatus(transactionID: String): CaptchaSolution | SolutionInProgress | PirActionFailed
```

Note `getCaptchaStatus`: "solution not ready yet" is naturally an error-union member (`SolutionInProgress`) rather than a success variant since the caller's retry loop becomes a simple `is SolutionInProgress ->` branch.

## Examples: use sites: how callers of APIs with Rich Errors change

The examples above focused on declaration sites. This part shows the real call sites of the same APIs and how they change, plus new examples where the API declaration barely changes but callers get more ergonomic code.

### Use-site for Example 1: the binary result at its only call site

DuckDuckGo, [`AndroidFeaturesHeader.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/app/src/main/java/com/duckduckgo/app/browser/trafficquality/AndroidFeaturesHeader.kt), the caller of `isAllowed()` (with sealed hierarchy as a result):

```kotlin
return when (val result = customHeaderAllowedChecker.isAllowed()) {
    is Allowed -> {
        val headers = mutableMapOf<String, String>()
        androidFeaturesHeaderProvider.provide(result.config)?.let { headers.put(X_DUCKDUCKGO_ANDROID_HEADER, it) }
        headers.put(X_DUCKDUCKGO_ANDROID_APP_VERSION_HEADER, appVersionProvider.provide(isStub = false))
        headers
    }
    NotAllowed -> mapOf(X_DUCKDUCKGO_ANDROID_APP_VERSION_HEADER to appVersionProvider.provide(isStub = true))
}
```

The caller must bind `result`, dispatch with `when`, and get the payload out via `result.config`. 
With Rich Errors the fallback-on-error intent is expressed directly, and the success value needs no unwrapping:

```kotlin
val config = customHeaderAllowedChecker.isAllowed().ifError {
    return mapOf(X_DUCKDUCKGO_ANDROID_APP_VERSION_HEADER to appVersionProvider.provide(isStub = true))
}

// `config` is smart-cast to TrafficQualityAppVersion below
val headers = mutableMapOf<String, String>()
androidFeaturesHeaderProvider.provide(config)?.let { headers.put(X_DUCKDUCKGO_ANDROID_HEADER, it) }
headers.put(X_DUCKDUCKGO_ANDROID_APP_VERSION_HEADER, appVersionProvider.provide(isStub = false))
return headers
```

The happy path is no longer indented inside a `when` branch (but can be written as such if needed). 
The error case processed as an early return with a fallback.

### Use-site for Example 2: early-return validation

AnkiDroid, [`NoteEditorFragment.kt:1265`](https://github.com/ankidroid/Anki-Android/blob/48fa7785980b5b71454769b78e0622afb4f4a9b7/AnkiDroid/src/main/java/com/ichi2/anki/NoteEditorFragment.kt#L1265):

```kotlin
val noteFieldsCheck = checkNoteFieldsResponse(editorNote!!)
if (noteFieldsCheck is NoteFieldsCheckResult.Failure) {
    addNoteErrorMessage = noteFieldsCheck.localizedMessage ?: getString(R.string.something_wrong)
    displayErrorSavingNote()
    return@launch
}
addNoteErrorMessage = null
saveNoteWithProgress()
```

With Rich Errors:

```kotlin
checkNoteFieldsResponse(editorNote!!).ifError {
    addNoteErrorMessage = it.localizedMessage ?: getString(R.string.something_wrong)
    displayErrorSavingNote()
    return@launch
}
addNoteErrorMessage = null
saveNoteWithProgress()
```

More or less the same, but no temporary variable in scope afterwards (`noteFieldsCheck` stays visible), and, with the must-use checker, the compiler rejects a caller who forgets the check entirely.

### Use-site for Example 3: the caller pays for the callback channel too

AnkiDroid, [`NoteEditorMultimediaController.kt`](https://github.com/ankidroid/Anki-Android/blob/48fa7785980b5b71454769b78e0622afb4f4a9b7/AnkiDroid/src/main/java/com/ichi2/anki/noteeditor/NoteEditorMultimediaController.kt#L206), a caller of `MediaRegistration.onPaste`:

```kotlin
val mediaTag =
    MediaRegistration.onPaste(
        context, uri, description, pasteAsPng,
        showError = { type -> fragment.showSnackbar(type.toHumanReadableString(context)) },
    ) ?: return false

fragment.insertStringInField(editText, mediaTag)
return true
```

The "two-channel" API forces a two-channel call site: the error handling lives inside the argument list (as a lambda that captures `fragment` and `context`), while control flow lives after the call (`?: return false`). Reading order is inverted. 
With the single-channel signature from Example 3:

```kotlin
val mediaTag = MediaRegistration.onPaste(context, uri, description, pasteAsPng).ifError { error ->
    fragment.showSnackbar(error.toHumanReadableString(context))
    return false
}

fragment.insertStringInField(editText, mediaTag)
return true
```

Error rendering and error control flow are now in one place, after the call, in usual reading order.

### Use-site for Examples 5/9: shared result types force unchecked casts on callers

This is one of the most complicated examples in the three repos. 
Because `pushAction` multiplexes three operations through one `NativeActionResult` (Example 9), every call site in DuckDuckGo, [`PirActionsRunner.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/pir/pir-impl/src/main/java/com/duckduckgo/pir/impl/common/PirActionsRunner.kt) must cast the success payload to the variant it "knows" it will get:

```kotlin
nativeBrokerActionHandler
    .pushAction(SubmitCaptchaInfo(actionId = effect.actionId, siteKey = ..., url = ..., type = ...))
    .also {
        if (it is Success) {
            engine?.dispatch(
                CaptchaInfoReceived(
                    transactionID = (it.data as CaptchaTransactionIdReceived).transactionID,  // ← unchecked cast
                ),
            )
        } else if (it is Failure && !effect.isRetry && it.retryNativeAction) {
            delay(60_000)
            engine?.dispatch(RetryGetCaptchaSolution(actionId = effect.actionId, responseData = effect.responseData))
        } else {
            val result = it as Failure                                                        // ← another cast
            onError(result.error, ...)
        }
    }
```

And in the captcha-polling handler:

```kotlin
.run {
    if (this is Success) {
        when (val status = (this.data as CaptchaSolutionStatus).status) {                     // ← unchecked cast
            is Ready -> engine?.dispatch(ExecuteBrokerStepAction(...SolveCaptcha(token = status.token)))
            else -> { /* retry or fail */ }
        }
    }
    ...
}
```

These `as` casts are exactly the type-safety hole that the imprecise shared result type opens: the compiler cannot verify that `SubmitCaptchaInfo` yields `CaptchaTransactionIdReceived`, the caller just asserts it.

With the precise per-operation signatures from Example 9, the same call site is fully checked:

```kotlin
when (val result = nativeBrokerActionHandler.submitCaptchaInfo(effect.actionId, siteKey, url, type)) {
    is CaptchaTransactionId -> engine?.dispatch(CaptchaInfoReceived(transactionID = result.value))
    is PirActionFailed ->
        if (!effect.isRetry && result.retryable) {
            delay(60_000)
            engine?.dispatch(RetryGetCaptchaSolution(actionId = effect.actionId, responseData = effect.responseData))
        } else {
            onError(result.error, ...)
        }
}
```

No unsafe casts, exhaustive `when`, and the "impossible" variants are gone from the caller's mental model entirely.

### Example 10: the multi-stage pipeline caller that only translates results

Here API doesn't change much but callers get more egonomics. 
DuckDuckGo, [`BookmarkImportProcessor.kt`](https://github.com/duckduckgo/Android/blob/bbdc318fffa5e3381eaa7dfb3d372d84db2e8104/autofill/autofill-impl/src/main/java/com/duckduckgo/autofill/impl/importing/takeout/processor/BookmarkImportProcessor.kt) operater three stages: download a zip, extract bookmarks, import them. Each stage uses a different error machinery (`runCatching`, `ExtractionResult`, `ImportSavedSitesResult`), and the orchestrator's whole job is to conform errors to each other. 
Today this takes the main function plus three private helper functions:

```kotlin
override suspend fun downloadAndImportFromTakeoutZipUrl(url: String, userAgent: String, folderName: String): ImportResult =
    withContext(dispatchers.io()) {
        runCatching {
            val zipUri = takeoutZipDownloader.downloadZip(url, userAgent)      // throws → DownloadError
            processBookmarkZip(zipUri, folderName)
        }.getOrElse { e -> Error.DownloadError }
    }

private suspend fun processBookmarkZip(zipUri: Uri, folderName: String): ImportResult =
    runCatching {
        val extractionResult = bookmarkExtractor.extractBookmarksFromFile(zipUri)   // ExtractionResult
        handleExtractionResult(extractionResult, folderName)
    }.getOrElse { e -> ParseError }
        .also { cleanupZipFile(zipUri) }

private suspend fun handleExtractionResult(extractionResult: ExtractionResult, folderName: String): ImportResult =
    when (extractionResult) {
        is ExtractionResult.Success ->
            handleImportResult(takeoutBookmarkImporter.importBookmarks(extractionResult.tempFileUri, ImportFolder.Folder(folderName)))
        is ExtractionResult.Error -> ParseError
    }

private fun handleImportResult(importResult: ImportSavedSitesResult): ImportResult =
    when (importResult) {
        is ImportSavedSitesResult.Success -> ImportResult.Success(importResult.savedSites.size)
        is ImportSavedSitesResult.Error -> Error.ImportError
    }
```

Note the shape: the pipeline is linear, but because each stage's result must be unwrapped before the next stage can run, the code is forced into either a nested-`when` pyramid or (as here) a chain of helper functions where the actual flow is scattered across four bodies. 
Also note it declares another sealed hierarchy (`ImportResult` with nested `Error` subclasses) just to be a target of the translation.

If the three stage APIs return unions, the orchestrator collapses into a single linear function, and its error set (`DownloadFailed | ParseError | ImportFailed`) is assembled by the compiler from the stages:

```kotlin
suspend fun downloadZip(url: String, userAgent: String): Uri | DownloadFailed
suspend fun extractBookmarksFromFile(zipUri: Uri): TempFileUri | ParseError
suspend fun importBookmarks(uri: Uri, folder: ImportFolder): List<SavedSite> | ImportFailed

override suspend fun downloadAndImportFromTakeoutZipUrl(
    url: String, userAgent: String, folderName: String,
): ImportedCount | DownloadFailed | ParseError | ImportFailed =
    withContext(dispatchers.io()) {
        val zipUri = takeoutZipDownloader.downloadZip(url, userAgent).ifError { return@withContext it }
        try {
            val tempFile = bookmarkExtractor.extractBookmarksFromFile(zipUri).ifError { return@withContext it }
            takeoutBookmarkImporter.importBookmarks(tempFile, ImportFolder.Folder(folderName))|.let { ImportedCount(it.size) }
        } finally {
            cleanupZipFile(zipUri)
        }
    }
```

Four functions can be now expressed as a single one and downstream callers get more information than today's collapsed `ParseError`/`ImportError` objects (the union members can carry the original exception). 
If a stage adds a new error, the orchestrator either handles it or the compiler tells it to widen its signature instead of silently putting everything into the nearest bucket.

### Example 11: caller-side dispatch on outcomes coming from different callees

Recall the Signal call site from Example 8, the call-site deserves its own example. 
In Signal, [`PhoneNumberEntryViewModel.kt`](https://github.com/signalapp/Signal-Android/blob/c47a74ba0961903eeaeda6d22347a990056c3e83/feature/registration/src/main/java/org/signal/registration/screens/phonenumber/PhoneNumberEntryViewModel.kt), the same function calls three endpoints (`registerAccount`, local-restore, `checkSvrCredentials`), and each of the three `when`s must repeat the `RetryableNetworkError` / `ApplicationError` branches with almost identical bodies. 
The wrapper generic `RequestResult<T, E>` gives no way to write a shared handler for "any transport error, whatever the endpoint", because the type parameter `E` differs.

With unions, transport errors are ordinary types shared by all endpoints, so the caller can factor its policy out:

```kotlin
private fun handleTransport(e: NetworkFailure | UnexpectedFailure): RegistrationState =
    when (e) {
        is NetworkFailure -> state.copy(oneTimeEvent = OneTimeEvent.NetworkError)
        is UnexpectedFailure -> state.copy(oneTimeEvent = OneTimeEvent.UnknownError)
    }

// at each of the three call sites:
val response = repository.registerAccount(...).ifError { error ->
    return when (error) {
        is NetworkFailure, is UnexpectedFailure -> handleTransport(error)
        is SessionNotFoundOrNotVerified -> ...   // endpoint-specific
        ...
    }
}
```

Error-only unions (`NetworkFailure | UnexpectedFailure` as a parameter type) is a feature no sealed-class can offer without inventing a common supertype in advance and as such examples show the common supertype might not exist when you need it.

## Other projects

We also briefly analyzed several other projects. The popularity of sealed Result-like hierarchies varies between them, but the pattern is still common in several cases:

| Project                | Custom `sealed ...Result/Error/Failure` types |
|:-----------------------|:----------------------------------------------|
| IntelliJ IDEA Ultimate | 552                                           |
| Space                  | 260                                           |
| Kotlin Compiler        | 93                                            |
| Apollo Kotlin          | 18                                            |
| Ktor                   | 8                                             |
| Exposed                | 1                                             |
| Okio                   | 0                                             |

In IntelliJ IDEA Ultimate, these hierarchies are scattered across different modules, each defining its own dialect of "Success or Error". 
In Space, we found service-layer result types that often wrap or duplicate common error cases. Apollo Kotlin demonstrates similar patterns, where sealed interfaces are used to represent everything from GraphQL validation issues to authentication failures. 
In our beloved Kotlin Compiler, the Analysis API relies on sealed hierarchies to forward complex applicability and compilation results.

In contrast, Exposed, Okio, and Ktor are projects that still predominantly use exceptions for error handling. 
Therefore, not all projects will benefit significantly from the feature immediately, although some of them might still adopt it in their APIs or internals where it provides better ergonomics than exceptions.

## Observations

1. Errors-as-values handling techniques are rarely unified even inside one project. And different projects use a different subset of all possible options. A developer moving between modules (or even companies) re-learns the local error dialect each time. Rich Errors make the dialect part of the language.   
     
2. Error precision can be expensive today (because of sealed hierarchies), so code is imprecise. Declaring a sealed hierarchy per function is so costly that one imprecise result type might be shared across functions (Examples 5, 9\), or cases might be duplicated across many hierarchies (Example 7). With unions, precision costs one `|` per error.  
     
3. No exceptions were harmed. Every example above already treats errors as values: sealed classes, `null`s, callbacks. The migration to Rich Errors preserves each author's architectural intent and only removes the encoding overhead.

## What about exceptions?

There were no examples of transforming exceptions into errors. This is because such a transformation requires challenging an architectural decision currently used in the repository, which is not the adoption path to errors we expect. Even for expected exceptional cases, the right choice might not be obvious. An example of this can be found in Roman’s post about error handling for IO: [https://elizarov.medium.com/kotlin-and-exceptions-8062f589d07](https://elizarov.medium.com/kotlin-and-exceptions-8062f589d07)

In our vision, we expect an overall adoption to look as follows:

- Cases where exceptional outcomes are already treated as values (`null`, a `sealed class` or an `Either`\-like container) adopt errors in almost all cases, preserving the errors-as-values semantics and just using a more unified and ergonomic approach  
- Cases where exceptions are currently used do not immediately adopt merely on the condition of “expectedness” of the exceptional case. The better conditions would be:  
  - “When I was designing this function, I used exceptions just because it was easier (e.g., no new external dependency or less boilerplate), but Rich Errors are now a comparable approach, which fits this use case better”  
  - “When I was designing this API, exceptions provided the best ergonomics at the use site and were easier for the users of the API to understand, but now Rich Errors offer better or comparable ergonomics and are widely known to the users”

We already have some examples where the ergonomics of Rich Errors justify replacing exceptions with errors. One of them is in the [Kotlin Wrappers library](https://github.com/JetBrains/kotlin-wrappers). Authors consider migrating some `DOMException` from the wrapped API to Rich Errors. For example, `AbortError` from [`showOpenFilePicker`](https://developer.mozilla.org/en-US/docs/Web/API/Window/showOpenFilePicker) is a good candidate for this migration. 
This error represents the outcome of user interaction that the user dismissed the prompt without making a choice. As it is an ordinary outcome, it might be better to handle it in a type-safe manner.

# Build Tools API

* **Type**: Build Tools API Proposal
* **Author**: Alexander Likhachev
* **Discussion**: [KEEP-421](https://github.com/Kotlin/KEEP/issues/421)
* **Related YouTrack issue**: [KT-76255](https://youtrack.jetbrains.com/issue/KT-76255)

## Abstract

This proposal describes an API for integration of the Kotlin compiler ecosystem into build systems.
The Build Tools API described here will initially be introduced as experimental,
with the intention of stabilizing shortly after the introduction based on adoption and feedback.

## Table of contents

- [Abstract](#abstract)
- [Motivation](#motivation)
- [Proposal](#proposal)
  - [API rules](#api-rules)
  - [Core API concepts](#core-api-concepts)
    - [Optional configuration](#optional-configuration)
    - [BuildOperation](#buildoperation)
    - [Platform toolchains](#platform-toolchains)
    - [KotlinToolchain](#kotlintoolchain)
- [Details](#details)
  - [KotlinToolchain](#kotlintoolchain-1)
  - [JvmPlatformToolchain](#jvmplatformtoolchain)
  - [NativePlatformToolchain](#nativeplatformtoolchain)
  - [JsPlatformToolchain, WasmPlatformToolchain](#jsplatformtoolchain-wasmplatformtoolchain)
  - [Incremental compilation](#incremental-compilation)
  - [Build metrics](#build-metrics)
  - [Compatibility guarantees](#compatibility-guarantees)

## Motivation

Currently, we provide the best tooling for the Gradle integration via the Kotlin Gradle plugin (from now on, KGP), 
while the feature support is limited for Maven and other build systems.
However, features like incremental compilation and Kotlin daemon support could benefit other build systems,
implementing them requires significant effort due to:
1. Direct usage of internal symbols (Kotlin compiler has no stable API, 
   especially in the places used for integration.
   The only guaranteed public contract currently is the compiler CLI a.k.a. string-based interface)
   and as an effect tight coupling 
   between build system plugin versions and compiler versions.
2. Complex integration with Kotlin daemon
   (it’s undocumented and has a lot of places to make mistakes,
   it exposes the underlying communication protocol)
3. Limited reuse of compilation optimization features across build systems and limited controls for the build process lifecycle.
4. Complexities in Kotlin Multiplatform (from now on, KMP) support due to a lot of internal things integrated and acknowledged only in KGP.

There’s interest in Kotlin compiler integration into various build systems and similar environments.
Currently, it’s a big challenge.
Especially it becomes a challenge when it comes to KMP support.

Also, the compiler CLI could be aware and supporting the features we have in KGP, like incremental compilation.

## Proposal

The proposal is to introduce the Build Tools API (from now on, BTA) as a stable API layer between build systems and the Kotlin compiler ecosystem that:
1. Is Kotlin-first. The initial solution is JVM-based 
   (because currently the compiler is a JVM application only) and may still expose some JVM specifics,
   but in general this should be avoided. The API should aim to be multiplatform.
2. Provides support for building all the KMP platforms and working with related tools, however, initially limited to:
   1. Compilation for JVM (e.g., sources → .class files)
   2. Compilation and linking for Kotlin/Native (e.g., sources → .klib, .klib → binaries like .exe, .framework, etc)
   3. Compilation and linking for Kotlin/JS (e.g., sources → .klib, .klib → binaries like .js)
   4. Compilation and linking for Kotlin/Wasm (e.g., sources → .klib, .klib → binaries like .wasm)
3. Is split into two entities: the API and the implementation. 
   There may be different reasons why users want to use another version of implementation.
   Specifically, there are cases among Kotlin/Native users that use Kotlin/Native of one version for compilation 
   and of another (newer) version for linking.
   Another case is the ability to update the Kotlin compiler separately from the build system plugin.
   That especially makes sense with third-party plugins that may be released with a delay from the Kotlin compiler release cycle.
   Also, it might be easier to maintain the build by updating the project sources separately from
   the project configuration in regard to breaking changes.
   The API provides such a capability, and then it’s up to the build system to either support this or not.
4. Offers type-safe representation for compiler arguments.
5. Enables a unified metrics collection.
6. Supports incremental compilation and daemon features for all build systems.

A special note about Kotlin/Native:
currently, Kotlin/Native has a completely separated distribution that is to be supplied by a build system.
It can be bundled into BTA,
or the provisioning may be encapsulated in it to simplify the integration.
The exact way of simplification is an implementation detail.

More precisely, this proposal defines the fundamental blocks of the API that would allow building
a feature-complete support of Kotlin Multiplatform in the follow-up work.
The goal is to make these fundamental blocks extensible enough to be able to afford it.

### API rules

The proposed API follows the rules:
1. Type-safety is important. For example, avoid configuration through raw strings.
2. Defaults are provided as much as possible to make the integration process smooth.
3. Compatibility is a must.
   We define some reasonable compatibility policy between the API and the implementation;
   we put the best effort to provide compatibility beyond it.
4. The API can be consumed in Java idiomatically.
5. Expose as little as possible to avoid blocking evolution.

### Core API concepts

#### Optional configuration

A concept used to expose optional configuration of an entity. 
Also referred to as the option approach.

```kotlin
interface EntityWithOptions {
  class Key<V>(val id: String)

  public operator fun <V> get(key: Key<V>): V?

  public operator fun <V> set(key: Key<V>, value: V)

  public companion object {
	@JvmField
	public val OPTION_ONE: Key<Int> = Key("OPTION_ONE")

	@JvmField
	public val OPTION_TWO: Key<String> = Key("OPTION_TWO")
  }
}
```

For each key declaration in the API, the implementation contains a mapped key with a default value.
This way, defaults are provided for all the keys and may change between implementations.
Everything that could be exposed like this should be exposed like this for 
the sake of API consistency and smaller cognitive load by having fewer concepts.

Using this approach, new options could be easily added and managed, accessed both from Kotlin and Java (thus other JVM languages too).

`@JvmField` is used to make it convenient for Java clients, e.g.

```java
// java
options.set(EntityWithOptions.OPTION_ONE, 5);
```

```kotlin
// kotlin
options[EntityWithOptions.OPTION_ONE] = 5
```

For example, it may be used for exposing compiler/linker arguments, incremental compilation configuration.

**Compatibility concerns:**

1. API version X used with implementation version X-1. API provides a key that the implementation does not know.  
   The implementation ignores the key and value. Perhaps, it emits a warning.
2. API version X-1 used with implementation version X. API does not provide a way to configure some new option.  
   Not a problem. Options, you know, are optional. The default value is used.

#### BuildOperation

```kotlin
public interface BuildOperation {
  public class Option<V> internal constructor(public val id: String)

  public operator fun <V> get(key: Option<V>): V?

  public operator fun <V> set(key: Option<V>, value: V)

  public companion object {
    // defines options common to all operations, like (but not limited to):
    @JvmField
    public val PROJECT_ID: Option<ProjectId> = Option("PROJECT_ID")

    @JvmField
    public val METRICS_COLLECTOR: Option<BuildMetricsCollector> = Option("METRICS_COLLECTOR")

    /**
      * KT-73037 Enables the feature of tracking inputs for completely cleaning out outputs on incompatible changes.
      * An example of such a change is a change to [BaseCompilerArguments.LANGUAGE_VERSION]
      * Some build systems do this job automatically, like Gradle.
      */
    @JvmField
    public val TRACK_INPUTS: Option<Boolean> = Option("TRACK_INPUTS")
  }
}
```

A generic descriptor of build operation, such as compilation, linking,
calling a cinterop tool, commonizer, etc…
It provides a generic way for collecting build metrics.
Also, it could be scoped to a project.
The scoping may benefit performance in the future
by allowing holding some caches between different build operations that are usually not kept.

Such abstraction will also allow in the future more easily implementing
a high-level KMP Build Tools API that would, for example,
generate a list of [BuildOperation](#buildoperation) by a given KMP project structure.

**Why is this a concern?**  
It would be nice to provide a straightforward way
to build KMP projects without a complex execution model.
E.g., there could be build systems that do not hold concepts like tasks in Gradle
and do not require execution units separation.
We may provide a fast path "KMP structure \-\> build everything".
However, the compilation model of shared source sets
(aka commonMain, nativeMain, etc.) may change in the future,
and the API should be ready for such changes.

#### Platform toolchains

Facades for operations related to a Kotlin target platform.
They define all the build operations that BTA provides.
These facades include compilation build operations but are not limited to.
In other words,
they group build operations specific to a compiler backend and build tools around it.

The platform toolchains are described in more details in [Details](#details).

#### KotlinToolchain

Acts as an entry point to the API, providing access to the platform toolchains.
Additionally, provides API to execute [BuildOperation](#buildoperation) and perform cleanup after them.

```kotlin
public interface KotlinToolchain {
    public val jvm: JvmPlatformToolchain
    public val js: JsPlatformToolchain
    public val native: NativePlatformToolchain
    public val wasm: WasmPlatformToolchain

    public fun createExecutionPolicy(): ExecutionPolicy

    // no @JvmOverloads on interfaces :(
    public suspend fun <R> executeOperation(
        operation: BuildOperation<R>,
    ): R

    public suspend fun <R> executeOperation(
        operation: BuildOperation<R>,
        executionMode: ExecutionPolicy = createExecutionPolicy(),
        logger: KotlinLogger? = null,
    ): R

    /**
     * This must be called at the end of the project build (i.e., all build operations scoped to the project are finished)
     * iff [projectId] is configured via [BuildOperation.PROJECT_ID]
     */
    public fun finishBuild(projectId: ProjectId)

    public companion object {
        @JvmStatic
        public fun loadImplementation(classLoader: ClassLoader): KotlinToolchain =
            loadImplementation(KotlinToolchain::class, classLoader)
    }
}
```

While the platform toolchains could be inlined into KotlinToolchain, it’s better to build clear borders between them.

## Details

For simplicity of the API review,
please refer to draft changes with usage samples committed just for demonstration:
https://github.com/JetBrains/kotlin/compare/master...ALikhachev/bta-renewed-design

### KotlinToolchain

**Source code:** [GitHub](https://github.com/JetBrains/kotlin/blob/77fd6344ffd91f42dabbb2248c38fa41cf3d349f/compiler/build-tools/kotlin-build-tools-api-new/src/main/kotlin/org/jetbrains/kotlin/buildtools/api/KotlinToolchain.kt)
An entry point to the API.
Any BTA implementation (within the compatibility policy) can be plugged to be used with this entry point.
An example of such a pluggable approach is JVM’s ServiceLoader.
We could also benefit here from KMP implementation of service locator ([KT-53056](https://youtrack.jetbrains.com/issue/KT-53056)).

Imposes a requirement that each tool should work both inside the build system process and inside Kotlin daemon modes.
The mode is selected via ExecutionPolicy using the option approach.
For the migration period, 
some operations may not support all the modes and throw an exception,
but we aim to provide complete support.

Provides handles for managing build lifecycle,
like `finishBuild` that should be called at the end of the build.
In this sense, the first executed `BuildOperation` linked to a project is considered a build start.
BuildOperations can be canceled granularly by using coroutines
or some wrappers on top of them for non-Kotlin consumers.

Allows providing a custom `KotlinLogger` to override the logging.
Currently, the underlying logger adapter allows little control on formatting,
such as how to render the position of code producing a warning/error.
In the future, we should provide a way to control the formatting by client themselves,
by providing components to build the final message.
It would also provide a way to structurally retrieve such information for further processing.

### JvmPlatformToolchain

**Source code:** [GitHub](https://github.com/JetBrains/kotlin/blob/77fd6344ffd91f42dabbb2248c38fa41cf3d349f/compiler/build-tools/kotlin-build-tools-api-new/src/main/kotlin/org/jetbrains/kotlin/buildtools/api/JvmPlatformToolchain.kt)  
A facade for JVM-related tools.   
**Exposed methods:**

* createJvmCompilationOperation – create a configurable [BuildOperation](#buildoperation) 
  for calling JVM backend compilation of sources into class files.
    * The returned `JvmCompilationOperation` uses the [options](#optional-configuration) approach to enable incremental compilation.
    * Also, exposes always available type-safe `compilerArguments`
      which use the option approach to configure compiler arguments.
      The argument classes are currently handcrafted. They are supposed to be auto-generated.
* createClasspathSnapshottingOperation – create a configurable BuildOperation 
  that captures an ABI snapshot of classes directory / jar and holds it in memory.
    * The returned `JvmClasspathEntrySnapshot` provides API to persist it on disk.
    * Having a captured snapshot, we should provide a way to introspect it
      or provide a method to retrieve the difference between 2 snapshots.
      According to the feedback from the KSP team, it would be useful for them.

Using similar ideas, it should also expose Kapt.

Incremental compilation support is split into two entities:   
* `JvmIncrementalCompilationOptions` using the option approach to configure things like 
  precise Java tracking, root project directory (for making IC caches relocatable), 
  a handle to force the compilation being non-incremental, etc. 
  The IC should work without touching the values of options (but may work inefficiently)
* `JvmIncrementalCompilationConfiguration` holding the values that clients should explicitly pass.

```kotlin
/**
 * @property workingDirectory the working directory for the IC operation to store internal objects.
 * @property sourcesChanges changes in the source files, which can be unknown, to-be-calculated, or known.
 * @property dependenciesSnapshotFiles a list of paths to dependency snapshot files produced by [JvmPlatformToolchain.calculateClasspathSnapshot].
 * @property options an option set produced by [JvmCompilationOperation.makeSnapshotBasedIcOptions]
 */
public class JvmSnapshotBasedIncrementalCompilationConfiguration(
  public val workingDirectory: Path,
  public val sourcesChanges: SourcesChanges,
  public val dependenciesSnapshotFiles: List<Path>,
  public val options: JvmIncrementalCompilationOptions,
) : JvmIncrementalCompilationConfiguration
```

`JvmSnapshotBasedIncrementalCompilationConfiguration` is attached to the compilation operation using the option approach.

### NativePlatformToolchain

**Source code:** [GitHub](https://github.com/JetBrains/kotlin/blob/77fd6344ffd91f42dabbb2248c38fa41cf3d349f/compiler/build-tools/kotlin-build-tools-api-new/src/main/kotlin/org/jetbrains/kotlin/buildtools/api/NativePlatformToolchain.kt)  
**Exposed methods:**

1. createKlibCompilationOperation – create a configurable [BuildOperation](#buildoperation) for performing sources to Klib compilation
    1. The returned `NativeKlibCompilationOperation` exposes type-safe `compilerOptions` which uses the option approach to configure compiler arguments.
2. createBinaryLinkingOperation – create a configurable [BuildOperation](#buildoperation) for performing Klib to binary linking
    1. The returned `NativeBinaryLinkingOperation` exposes type-safe `linkerOptions` which uses the option approach to configure linker arguments

The notable thing is that we should clearly separate the compilation and linking phases.
The compiler arguments are supposed to be separated and auto-generated.

In follow-up work, we should expose:
1. Commonizer – extracts common cinterop API
    1. It’s required for proper IDE support of shared source sets
    2. It’s required for shared native source sets metadata compilation, like `nativeMain`
2. CInterop import
    1. A standalone tool for Kotlin \-\> C/Obj-C interop that generates a special klib by given headers.
    2. Those klibs then from the POV of the API are passed as library klibs
3. Swift interop
    1. A tool for allowing calling Kotlin from Swift code. Does not work the other way around.

### JsPlatformToolchain, WasmPlatformToolchain

**Source code (JS):** [GitHub](https://github.com/JetBrains/kotlin/blob/77fd6344ffd91f42dabbb2248c38fa41cf3d349f/compiler/build-tools/kotlin-build-tools-api-new/src/main/kotlin/org/jetbrains/kotlin/buildtools/api/JsPlatformToolchain.kt)  
**Source code (Wasm):** [GitHub](https://github.com/JetBrains/kotlin/blob/77fd6344ffd91f42dabbb2248c38fa41cf3d349f/compiler/build-tools/kotlin-build-tools-api-new/src/main/kotlin/org/jetbrains/kotlin/buildtools/api/WasmPlatformToolchain.kt)

Currently, it does not structurally differ from `NativePlatformToolchain`. 
The notable thing is
that we should clearly separate the compilation and linking phases.
The compiler arguments are supposed to be separated and auto-generated.
Here, additionally,
we could expose new tools that produce meta-information required for further processing,
e.g., webpack bundling for web targets.
The produced meta-information could be consumed by plugins for the systems like webpack.

### Incremental compilation

A general note about incremental compilation support: 
it may rely a lot on the information provided by a build system, like changed files set.
BTA should consider both the build systems that are capable of input tracking
(like Gradle) and the build systems that are not (like Maven).
Incremental compilation capabilities may vary between targets, but we should put our best effort
to expose them using similar approaches.

### Build metrics

During the build process, we collect a lot of metrics like compiler analysis time, 
compiler generation time,
time spent on calculations related purely to incremental compilation, and other.
This already helped us identify and fix some performance problems,
also it provides nice insights about the project to advanced Kotlin developers and/or build engineers.
So, collecting build metrics is an essential thing.

Similarly to IC in [JvmCompilationOperation](#jvmplatformtoolchain), there’s a way to provide a metrics collector to all operations, using the option approach.   
See the example: https://github.com/JetBrains/kotlin/blob/77fd6344ffd91f42dabbb2248c38fa41cf3d349f/compiler/build-tools/kotlin-build-tools-api-new/src/test/kotlin/kotlinUsageExample.kt#L124

The build metrics collection is considered an experimental part of BTA and is subject to changes.
E.g.,
the entire build metrics collection may be reworked in the future
to use opentelemetry or other existing tools.

In the initial version, we expose metric keys just as strings, 
and nesting of metrics like RUN\_COMPILER \-\> ANALYSE\_SOURCES is exposed as a single string like `RUN_COMPILER.ANALYSE_SOURCES`.
That’s because the exact exposed metrics set is currently an implementation detail
and is subject to changes between versions.
If we want to define them more clearly, that should be covered by a separate RFC.
Here, we aim to provide basic unified support for metrics collection.

It would also be nice to have a way to track the status of build operation execution.
As an example, a compilation operation could indicate whether it’s currently in analyzing,
generation, or another compilation phase.

### Compatibility guarantees

Even during the experimental introduction stage,
we commit to providing compatibility guarantees described below,
on a best-effort basis,
with the acknowledgement of possible small violations to allow iterative evolution.
This short-term flexibility enables us
to quickly react and adjust the API design based on feedback from API consumers and real-world integrations.

After leaving the experimental stage and stabilizing the API,
the following compatibility guarantees will apply strictly:

1. The implementation is strictly aligned with the compiler and the tools versions.
2. BTA version X is guaranteed to work with major implementation versions \[X-3, X+1\].
   This gives flexibility
   but allows keeping the API clean in terms of removing obsolete/deprecated stuff.
3. Experimental compiler features are available through raw arguments and without any guarantees.
4. All optional configurations should have default values.
5. Deprecation cycle: stable \-\> warning \-\> error \-\> hidden \-\> removed (the hidden step is optional, to be decided case by case)
6. Each option must contain a `sinceVersion` property, like

```kotlin
public class JvmCompilerArgument<V>(public val id: String, public val sinceVersion: KotlinVersion)
...
@JvmField
public val GENERATE_VALHALLA_CLASSES: JvmCompilerArgument<Path> = JvmCompilerArgument("GENERATE_VALHALLA_CLASSES", KotlinVersion(2, 5, 0))
```

While passing an option that is unknown to implementation, does not harm it, 
but such version annotation would allow to perform an easy version check 
and report a more detailed warning, like using BTA 2.5.0 and implementation 2.4.10, instead of
\> Unknown option `GENERATE_VALHALLA_CLASSES`  
There could be a warning reported from the implementation  
\> `GENERATE_VALHALLA_CLASSES` is a feature supported since Kotlin 2.5.0, you’re using Kotlin 2.4.10

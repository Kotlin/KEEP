# KMP Publication Scheme

* **Type**: Design proposal
* **Authors**: Anton Lakotka, Timofey Solonin
* **Contributors**: Andrei Tyrin
* **Discussion**: ...
* **Status**: Under consideration
* **Prototype**: In progress [KT-74005](https://youtrack.jetbrains.com/issue/KT-74005)
* **Related YouTrack issue**: [KT-68323](https://youtrack.jetbrains.com/issue/KT-68323)

## Abstract

This design document summarizes some of the impact the Unified Klib (UKlib) [specification](uklibs.md) 
has on Kotlin Gradle Plugin and the Kotlin Multiplatform (KMP) publication scheme.

## Table of contents

- [Abstract](#abstract)
- [Motivation](#motivation)
- [Proposal](#proposal)
- [UKlibs in Kotlin Gradle Plugin](#uklibs-in-kotlin-gradle-plugin)
- [Standardizing KMP publication and simplifying dependencies](#standardizing-kmp-publication-and-simplifying-dependencies)
  - [One component to rule them all](#one-component-to-rule-them-all)
  - [Publication metadata and dependencies](#publication-metadata-and-dependencies)
  - [Stub artifacts and variants](#stub-artifacts-and-variants)
  - [Consistent resolution of dependencies](#consistent-resolution-of-dependencies)
- [KMP publication with Android target](#kmp-publication-with-android-target)
- [Cinterops and commonized metadata](#cinterops-and-commonized-metadata)
  - [Fragments resolution algorithm for commonized metadata](#fragments-resolution-algorithm-for-commonized-metadata)
- [Addressing UX issues of new KMP resolution scheme](#addressing-ux-issues-of-new-kmp-resolution-scheme)
- [Changes in current KMP Dependencies Model in Kotlin Gradle Plugin](#changes-in-current-kmp-dependencies-model-in-kotlin-gradle-plugin)

## Motivation

We want to simplify how Kotlin Multiplatform (KMP) libraries work in build systems that wish to support KMP. 
Support for KMP is now complicated in build systems beyond Gradle because:

* Resolution of multiplatform libraries requires the build system to fetch and manipulate discrete compilation artifacts described by Gradle Metadata variants at specific compilation phases.
* Build system that wants to truthfully reproduce KMP resolution as it happens in the Kotlin Gradle Plugin needs to follow the compatibility and disambiguation rules as defined in the Kotlin Gradle Plugin.
* Common code compilation classpath is formed through an intricate algorithm that depends directly on Gradle resolution and an auxiliary undocumented KMP-specific metadata.
* Future changes to KMP compilation scheme need to map well onto Gradle Metadata format and its consumers.

## Proposal

With this proposal, we aim for the following changes:

* Make KMP publications fit into a single file: the Unified Klib (UKlib)
* Simplify how interlibrary dependencies work in KMP
* Standardize the publication metadata of a KMP library

It is easiest to explain the result we want to achieve with an example. 
Let's take the following multiplatform library declared using Kotlin Gradle Plugin:

```kotlin
// kmp-lib/build.gradle.kts  
plugins {
    `maven-publish`
    kotlin("multiplatform")
}

kotlin {
    jvm()
    iosArm64()
    iosX64()
    linuxArm64()
    linuxX64()

    sourceSets.commonMain.dependencies {
        api("org:another-kmp-library")
    }
    sourceSets.iosMain.dependencies {
        implementation("org:kmp-library-with-apple-targets-only")
    }
    sourceSets.jvmMain.dependencies {
        compileOnly("com.google.guava:guava")
    }
}
```

At present, this library produces the following publication structure:  
```text
current/
└── org
    ├── kmp-lib
    │   ├── kmp-lib-kotlin-metadata.jar
    │   └── kmp-lib.module
    ├── kmp-lib-jvm
    │   ├── kmp-lib-jvm.jar
    │   ├── kmp-lib-jvm.module
    │   └── kmp-lib-jvm.pom
    ├── kmp-lib-iosarm64
    │   └── kmp-lib-iosarm64.klib
    │   └── kmp-lib-iosarm64.module
    ├── kmp-lib-iosx64
    │   └── kmp-lib-iosx64.klib
    │   └── kmp-lib-iosx64.module
    ├── kmp-lib-linuxarm64
    │   ├── kmp-lib-linuxarm64.klib
    │   └── kmp-lib-linuxarm64.module
    └── kmp-lib-linuxx64
        ├── kmp-lib-linuxx64.klib
        └── kmp-lib-linuxx64.module
```

After implementation of the proposed changes, the equivalent library will have the following definition:  
```kotlin
// kmp-lib/build.gradle.kts
plugins {
    `maven-publish`
    kotlin("multiplatform")
}

kotlin {
    jvm()
    iosArm64()
    iosX64()
    linuxArm64()
    linuxX64()

    dependencies {
        api("org:another-kmp-library")
        implementation("org:kmp-library-with-apple-targets-only")
        compileOnly("com.google.guava:guava")
    }
}
```

And the publication structure will be the following:

```text
new/
└── org
    └── kmp-lib
        ├── kmp-lib.jar
        ├── kmp-lib.uklib
        ├── kmp-lib.module
        └── kmp-lib.pom
```

Namely, the changes will be:

* Dependencies will exist between the whole KMP modules
* We will go from several separate publication components to a single component
* All the discrete parts of a KMP compilation will be contained in a single `.uklib` file

## UKlibs in Kotlin Gradle Plugin

The UKlib container format and the new KMP dependency model is described in detail in the [UKlib specification](uklibs.md).

Most importantly, during the migration period in the Kotlin Gradle Plugin, we will implement UKlibs publication in compatibility with the current KMP publication. 
That means that at some point we will start publishing the UKlib in the root component of the current KMP publication.

For the migration period, we might relax some of the constraints of the UKlib design. 
Specifically, we might store `dependsOn` edges before moving to the UKlib attributes model described in the UKlibs specification. 
The intermediate step with `dependsOn` edges is needed to support an easier migration in the following cases:
```kotlin
// build.gradle.kts
kotlin {
    iosArm64()
    iosX64()
    
    // Default target hierarchy applies implicitly
}

// src/commonMain/kotlin/Common.kt -> fun common() { ... }
// src/iosMain/kotlin/iOS.kt       -> fun ios() { ... }

// ./gradlew publish - UKlib publication emits error because commonMain and iosMain are fragments with identical attributes
```

Eventually, we will emit deprecations when publishing libraries with UKlib incompatible source set graphs. 
For example, the following will be prohibited:

```kotlin
kotlin {
    iosArm64()
    iosX64()
    jvm()

    // Violation of “Fragment F1 refines fragment F2 <=> targets of F1 are compatible with F2”
    applyHierarchyTemplate {
        group("iosAndJvm") {
            withIosArm64()
            withIosX64()
            withJvm()
        }
        group("onlyIos") {
            withIosArm64()
            withIosX64()
        }
    }
}
```

## Standardizing KMP publication and simplifying dependencies

Beyond introducing an UKlib serialization format for KMP libraries, UKlibs will also change how we emit multiplatform publications. 
These changes stem from our desire to have a dependency model that is simple to resolve in any build system. 
We envision that the build systems that want to support KMP will be able to declare dependencies to whole KMP libraries:

```xml
<!-- kmp-in-maven.pom -->
<dependencies>
  <dependency>
    <groupId>org</groupId>
    <artifactId>kmp-library</artifactId>
  </dependency>
</dependencies>
```

Such a simple model opposes the KMP dependency model in Kotlin Gradle Plugin, where we store the information about 
the source set where the dependency was consumed and constraint transitive consumers using this knowledge.

For the migration period, we might allow specifying dependencies at the source set level:

```kotlin
// kmp-lib/build.gradle.kts
kotlin {
    iosArm64()
    iosX64()
    jvm()

    sourceSets.iosMain.dependencies {
        implementation("org:ios-only-dependency")
    }
}
```

Though the UKlib consumers will be able to see all dependencies regardless of the source set where they were consumed.

### One component to rule them all

With the new publication model, we aim to have 1 component. 
Most importantly, we want to avoid having a separate `-jvm` “subcomponent” for Maven JVM consumers to prevent 
discrepancies in coordinates for different consumers and in the publication metadata.

### Publication metadata and dependencies

Regarding publication metadata, we need to specify 2 files that will be consumed by the resolvers: 
* Gradle Module metadata (`.module`)
* Maven Project Object Model (`.pom`)

In the POM, we will mark the KMP component as: 
```xml
<packaging>uklib</packaging>
```

This should not impact Maven JVM resolution and will hint that there is a `.uklib` artifact for Maven KMP consumers.

In Gradle Module metadata, we will emit two new variants: `uklibApiElements` and `uklibRuntimeElements`, similarly to the existing JVM variants.

To implement consistent resolution for all consumers, we plan to emit the same dependencies in all dependency blocks. 
For example:
```kotlin
kotlin {
    iosArm64()
    jvm()

    dependencies {
        api("com.google.guava:guava")
    }
}
```
will produce `uklibApiElements` and `uklibRuntimeElements` with a `com.google.guava:guava` dependency even though Guava 
does not apply to `iosArm64()` compilations. It will be up to the build system (or more likely Build Tools API) 
to filter out non-KMP artifacts that don’t apply to a particular compilation.

Similarly, all KMP libraries will be visible in the `<dependencies>` section of the POM even if they do not have a `jvm()` target.

Including JVM dependencies in UKlib variants and KMP dependencies in the POM is needed to ensure that every consumer 
(Maven, Gradle, Maven-resolving build system) has a consistent view of transitive dependencies in the resolution graph.

One problematic case that needs consideration is the chain of dependencies that exposes non-KMP Android components to Gradle and Maven Java consumers. 
Consider the following chain of dependencies:
```text
Gradle Java consumer -> KMP (jvm(), androidTarget()) library -> Android library
```

We might exclude non-KMP Android dependencies from JVM dependency blocks as an exception.

### Stub artifacts and variants

To ensure that transitive library resolution remains resolvable according to the UKlib dependency model in all build systems, 
we will emit a stub `.jar` artifact and a stub JVM Gradle variant in KMP publications when the `jvm()` target is not declared.

### Consistent resolution of dependencies

It should be noted that to achieve consistent dependency resolution in all build systems, all build systems need to 
resolve version conflicts in the same way and have the same scoping behavior. 
In reality, this is not the case. For example, Gradle and Maven have different default strategies for resolving version conflicts. 
Also, some scoping behavior, such as Gradle's `implementation()` configuration, is not available in the Maven POM.

This proposal doesn’t address these discrepancies, but we might provide further guidance on minimizing these resolution
differences in build systems implementing KMP support.

## KMP publication with Android target

At the moment we will not pack `.aar` into the UKlib. All intermediate fragments within the UKlib that compile to 
Android target will still have the Android UKlib attribute to ensure the consumer forms the correct metadata compilation classpath.

For now, Android publication will remain a separate component, but this fact is an implementation detail that may change in the future.

## Cinterops and commonized metadata

At present cinterops and the metadata klibs derived by commonizing them are published in separate artifacts. 
For example, the publication of the following library:
```kotlin
kotlin {
    linuxArm64()
    linuxX64()

    targets.configureEach {
        val nativeCompilation = (compilations.findByName("main") as? KotlinNativeCompilation) ?: return@configureEach
        nativeCompilation.cinterops.create("a") {
            definitionFile.set(project.layout.projectDirectory.file("a.def"))
        }
        nativeCompilation.cinterops.create("b") {
            definitionFile.set(project.layout.projectDirectory.file("b.def"))
        }
    }
}
// a.def
language = C
---
void inA(void);

// b.def similar to a.def

// ./gradlew publish -Pkotlin.mpp.enableCInteropCommonization=true
```

results in a publication structure with:
* 2 additional cinterop klib artifacts
* Multiple commonized metadata klibs in the root component's metadata jar

An equivalent solution in UKlibs might be to emit a UKlib per cinterop group and store the commonized metadata in this UKlib
```text
/kmp-lib.uklib
/kmp-lib-cinterop-a.uklib
  /cinterop-a-linuxArm64
  /cinterop-a-linuxX64
  /nativeMain-commonized-cinterop-a
  /linuxMain-commonized-cinterop-a
/kmp-lib-cinterop-b.uklib
```

However, it is not clear how the POM resolvers might discover these additional UKlibs. 
As a consequence we might relax the requirement that a UKlib is not allowed to have fragments with the same attributes 
for the cinterop case and will end up storing all the fragments in the `kmp-lib.uklib`.

### Fragments resolution algorithm for commonized metadata

Each fragment of the commonized cinterop metadata, unlike that of the regular metadata klib, contains all the metadata
produced by commonizing the participating cinterop fragments. That means we must pass the most specific commonized
fragment to the consuming analyzer, such as the IDE and metadata compiler, per commonization group in the UKlib.

The specificity of the fragments will be determined by applying the "Fragment Resolution" sorting algorithm to the
fragments of the commonized cinterop metadata group and taking the first fragment. We might handle the edge case
of the diamond refinement by prohibiting the publication of commonized metadata where such a refinement graph occurs.

To distinguish commonized metadata fragments from other fragments, we will introduce a property in the fragment
declaration in the umanifest. In addition, we will introduce an identifier of the commonized metadata group to
distinguish between multiple such groups. These identifiers might be implemented as separate "Kotlin Attributes", but
then we will need to define how multiple dimensions of "Kotlin Attributes" interact for this implementation.

## Addressing UX issues of new KMP resolution scheme

In the current source set level KMP dependency model in Kotlin Gradle Plugin, explaining “where can’t I write code 
with this KMP dependency” is achieved through Gradle resolution errors. Explaining granular KMP resolution through 
Gradle resolution errors is not ideal, but provides some intuition about where the API from the consumed dependencies 
will (not) be available. 
For example:
```kotlin
// producer/build.gradle.kts
kotlin {
    iosArm64()
    iosX64()
    linuxArm64()
}

// consumer/build.gradle.kts
kotlin {
    iosArm64()
    iosX64()
    linuxArm64()
    jvm()

    // Fails to resolve because commonMain compiles to jvm and producer is missing jvm
    sourceSets.commonMain.dependencies {
        implementation(project(":producer"))
    }

    // Works: now the appropriate code from the producer can be used in nativeMain, appleMain and iosMain sources
    sourceSets.nativeMain.dependencies {
        implementation(project(":producer"))
    }

    // In UKlibs resolution scheme, this dependency will be allowed
    dependencies {
        implementation(project(":producer"))
    }
}
```

With the UKlib publication changes the Gradle resolution errors will be suppressed, and it might not be clear where (if at all) 
the consumed dependency is accessible. To mitigate this issue before releasing UKlibs, we will provide a diagnostic tool 
to understand what API of a consumed library is available in the source sets of the consumer project.

## Changes in current KMP Dependencies Model in Kotlin Gradle Plugin

We plan to change the current KMP dependency model to be compatible with the UKlib dependency model to allow gradual migration.
To allow consumption of partially compatible dependencies, we will infer the visible consumed API in the current KMP publication.
The lenient resolution will be achieved by using `metadataApiElements` variant as a fallback when the platform variant is not available.

We will also add the possibility to declare dependencies on Kotlin-extension level instead of the source set level in
the KMP Kotlin Gradle Plugin.

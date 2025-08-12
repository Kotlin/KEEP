# Moving ABI Validation to Kotlin Gradle Plugin

* **Type**: Design proposal
* **Author**: Sergei Shanshin
* **Discussion**: [KEEP-445](https://github.com/Kotlin/KEEP/issues/445)
* **Status**: Public discussion
* **Related YouTrack issue**: [KT-71170](https://youtrack.jetbrains.com/issue/KT-71170)

## Abstract
We propose using Binary Compatibility Validator out of the box in Kotlin Gradle Plugin and Kotlin Maven Plugin without using separate plugins.

## Table of contents

* [Abstract](#abstract)
* [Table of contents](#table-of-contents)
* [Introduction to binary compatibility](#introduction-to-binary-compatibility)
* [Motivation](#motivation)
  * [UX improvement](#ux-improvement)
  * [Reliability](#reliability)
* [Previous implementation](#previous-implementation)
* [Current state](#current-state)
  * [Dump file formats and naming](#dump-file-formats-and-naming)
  * [ABI Tools](#abi-tools)
    * [API of ABI Tools](#api-of-abi-tools)
  * [BCV as a part of the Kotlin Gradle Plugin](#bcv-as-a-part-of-the-kotlin-gradle-plugin)
    * [Enabling ABI validation](#enabling-abi-validation)
    * [checkLegacyAbi](#checklegacyabi)
    * [updateLegacyAbi](#updatelegacyabi)
* [Stabilization of the current solution](#stabilization-of-the-current-solution)
  * [Missing features](#missing-features)
  * [Stabilization of ABI Tools API](#stabilization-of-abi-tools-api)
  * [Open questions](#open-questions)
  * [Gradle DSL](#gradle-dsl)
  * [Documentations](#documentations)
* [Future development](#future-development)
  * [New format](#new-format)
  * [Improve Android support](#improve-android-support)
  * [New validation architecture](#new-validation-architecture)

## Introduction to binary compatibility
Kotlin libraries are distributed in binary form (class-files in jar files or klibs). Binary libraries contain binary declarations, executable code and resources.

All together, publicly available binary declarations form the so-called ABI (Application Binary Interface).

At the time of linking the declarations, the dependency version may differ from the expected one, for example, in the case of a dependency conflict in the build system.
The declarations are linked at the moment of the first use of the class for the JVM or the compilation of a dependent module for klib.

Dependency conflicts itself do not cause errors and sometimes are desirable. However, an error may occur in linking the stage if some expected binary declaration from the library is missing in the actual version.
This is binary incompatibility between versions.
Examples of the most specific incompatible changes: deleting or renaming a class/function, changing the parameter type.

Thus, let's say that the actual version of the library is binary compatible with the expected one if all links from the dependent module are resolved without errors.

A different version of the library may contain a different set of public binary declarations.

Not all changes are binary compatible. For example, deleting a class, function, or changing the type of a parameter is most often an incommensurable change.

Manually tracking an ABI change is challenging because it is not always obvious how a change in the declaration in the source code affects it.
However, tracking binary compatibility is a necessary procedure for library authors, as binary incompatible changes reduce the quality of users' lives.

For automated monitor binary compatibility of changes, we suggest using a tool called Binary Compatibility Validator (hereinafter BCV).
This tool helps library authors detect binary incompatible changes.

## Motivation
Previously, [Binary Compatibility Validator](https://github.com/Kotlin/binary-compatibility-validator) was a separate Gradle plugin.

We found two main reasons for making Binary Compatibility Validation as a part of Kotlin Gradle Plugin:
### UX improvement
It’s much more convenient to use the functionality out of the box, all the main features come in one bundle; also this is a more discoverable approach.

To use Binary Compatibility Validator users need to add an additional plugin to their build.
### Reliability
No need to track the compatibility of different versions of Kotlin Gradle Plugin, Gradle, Kotlin Compiler; a more reliable implementation, because there is direct access to the KGP classes

## Previous implementation
The separate Gradle plugin extracts Application Binary Interface of the library (ABI) to the text file called ABI dump file or just a dump.
A dump contains publicly available binary declarations.

An ABI dump for the previous library version, so-called reference dump, stored in the project directory in `api` subdirectory.
After making the local changes, a dump file is generated, which is compared with the reference one.

The comparison takes place as a search for differences in text files, so the users must determine for themselves which changes are binary compatible and which are not.



## Current state
We have initiated the moving of Binary Compatibility Validator logic to Kotlin Gradle Plugin.

This will help to collect the bottlenecks and technical problems of this approach.
It will also improve the implementation of a more convenient DSL that meets the requirements of KGP.

ABI validation has been added in KGP since version `2.2.0` in experimental state.

### Dump file formats and naming
The current ABI validation in KGP implementation retains the dump file formats, dump file naming method (matches the name of the project) and directory names.

This is done for a smooth migration so that users can switch to using ABI validation in KGP without significant changes.


### ABI Tools
A new artifact has been created for the BCV program call.
Maven coordinates of the artifact: [org.jetbrains.kotlin:abi-tools](https://mvnrepository.com/artifact/org.jetbrains.kotlin/abi-tools)

This dependency can be used to add ABI and dump capabilities to an arbitrary build system, as well as to write tasks with custom validation logic.

#### API of ABI Tools
> [!WARNING]
> This is not the final version of the API, and it can be modified

To work with the ABI Tools API, you must first get an instance of it.
```kotlin
val abiTools = org.jetbrains.kotlin.abi.tools.AbiToolsFactory().get()
```
`org.jetbrains.kotlin.abi.tools.api.AbiToolsInterface` interface provides methods to work with JVM and KLIB binary declarations.

For full description, please refer to [the KDocs](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/abi-validation/abi-tools-api/src/main/kotlin/org/jetbrains/kotlin/abi/tools/api/AbiToolsInterface.kt).
Experimental API is not published on https://kotlinlang.org/api yet.

### BCV as a part of the Kotlin Gradle Plugin
> [!WARNING]
> This is not the final version of the DSL, and it can be modified

Starting from the `2.2.0` version tasks `checkLegacyAbi` and `updateLegacyAbi` are created.

#### Enabling ABI validation
By default, tasks `checkLegacyAbi` and `updateLegacyAbi` are silently skipped, even if they are explicitly called.

This is done due to the fact that often in multi-project builds there is a need to track the ABI for only a few projects.
However, the command is often called locally or on CI by name (`checkLegacyAbi`), rather than by its path (`:lib:checkLegacyAbi`).
If these tasks were enabled by default, it would cause the `checkLegacyAbi` execution error in those projects where there is no need to track the ABI.

To enable ABI validation, you must add to the build script:
```kotlin
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Use the set() function to ensure compatibility with older Gradle versions
        enabled.set(true)
    }
}
```

#### checkLegacyAbi
Task compares ABI for current code with ABI in the reference dump and fails if there are any differences.

By default, the reference dump files are located in the directory `api` in the corresponding project directory.

#### updateLegacyAbi
Task overwrites reference dump with ABI for current code.

By default, the reference dump files are located in the directory `api` in the corresponding project directory.

## Stabilization of the current solution
We will assume that the migrated part of the BCV is stable when all the tasks listed below are closed and no critical bugs are created by users for a long time.

### Missing features
- add Gradle DSL to extract ABI from already compiled jars (perhaps, from Gradle artifacts)

### Stabilization of ABI Tools API
- stabilize ABI Tools API for the existing dump format

### Open questions
- Decide if we should create tasks if ABI validation is disabled in the project [KT-77687](https://youtrack.jetbrains.com/issue/KT-77687)
- Decide if `check` task should depend on `checkAbi` if ABI validation is enabled in the project [KT-78525](https://youtrack.jetbrains.com/issue/KT-78525)

### Gradle DSL
- stabilize filtering DSL (exclusions and inclusions)
- rename `checkLegacyAbi` and `updateLegacyAbi` tasks to `checkAbi` and `updateAbi`
- maybe we should rename `legacyDump` block to some other, to clearly indicate what exactly is being configured (old report format) 
- find a group to place the tasks [KT-78717](https://youtrack.jetbrains.com/issue/KT-78717)
- write descriptions for the tasks
- make `abiValidation {}` block stable part of `kotlin { }` extension


### Documentations
- add ABI `abi-tools` and `abi-tools-api` on https://kotlinlang.org/api
- write the migration guide (where? https://kotlinlang.org?)
- add the description of binary compatibility near to [Backward compatibility guidelines for library authors](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html).
- add the link on the binary compatibility description to [Backward compatibility guidelines for library authors](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
- add the page with a description of the direct use of `abi-tools`
- add the link on the page with the `abi-tools` description to [Backward compatibility guidelines for library authors](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
- edit [Backward compatibility guidelines for library authors](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html) to specify to use ABI Validation in BCV
- describe all abilities of ABI Validation in [Binary compatibility validation in the Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html) and add link to migration guide


## Future development
The development and implementation of new features does not block the stabilization of migrated existing features out of BCV.

However, new features have a lower priority and in some cases can only be implemented after certain existing features are stabilized.

### New format
- consider the possibility of creating two file types: the dump file and the diff file
- develop the new dump format [KT-78009](https://youtrack.jetbrains.com/issue/KT-78009)
- migrate to the new dump format with breaking changes
  - starting from some KGP version tasks `checkAbi` and `updateAbi` will support only the new dump format
  - we can add a property to change a dump format

### Improve Android support
- introduce DSL for working with Android flavors, build types and build variants (task) [KT-78025](https://youtrack.jetbrains.com/issue/KT-78025)

### New validation architecture
- Design final version of validation architecture
- Implement Validator API for ABI Tools [KT-78300](https://youtrack.jetbrains.com/issue/KT-78300), [KT-78134](https://youtrack.jetbrains.com/issue/KT-78134)
- Implement ABI compatibility validators [KT-78299](https://youtrack.jetbrains.com/issue/KT-78299), [KT-78301](https://youtrack.jetbrains.com/issue/KT-78301)

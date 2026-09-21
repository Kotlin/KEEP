# Design Notes: Kotlin Archive 

* **Type**: Design Notes
* **Author**: Pavel Kunyavskiy
* **Contributors**: Anton Lakotka, Sebastian Sellmair
* **Related YouTrack issue**: [KT-87910](https://youtrack.jetbrains.com/issue/KT-87910)

## Summary

We are introducing a new publication format as a response to [limits](https://central.sonatype.org/publish/maven-central-publishing-limits/)
on publication sizes in Maven central. 

Kotlin Archive introduces a publication format that bundles common metadata, supported platform KLIBs, and cinterop KLIBs into one archive per library module. Their contents are stored in an uncompressed ZIP archive (.kar), which is compressed with XZ for publication as .kar.xz. JVM, Android, and custom targets continue to use separate components.

This document provides an overview of differences in formats to enable
build systems other than Gradle to implement support of consumption and 
publication of the new format. 

## Motivation

Multiplatform publication has a long story of compatibility,
which leads to quite an inefficient publication format. 
A single publication of a large multimodule library sometimes is above the introduced limits, which is completely not sustainable.

When adding a new target, the new publication scheme wouldn’t create new files, and the increase in overall size would be significantly smaller than with the old scheme.
Moreover, in some cases, the total publication would be smaller than a single-target publication under the old scheme because of improved compression.

For example, for [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) the whole set of published
artifacts goes from 7539 files and 91 MB to 1036 files and 23 MB, and about 16 MB of that result comes from a
single module bundling OpenSSL.

## Table of Contents

* [Goals and non-goals](#goals-and-non-goals)
* [Current state overview](#current-state-overview)
* [New artifact](#new-artifact)
* [Archive content](#archive-content)
* [Gradle metadata compatibility](#gradle-metadata-compatibility)
* [Sources publication](#sources-publication)
* [Known limitations](#known-limitations)

## Goals and non-goals

This proposal concentrates only on distribution size and file count. We don't try to improve the dependency model as the UKlib proposal [does](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0446-uklibs.md).
This allows us to make the change significantly simpler and less intrusive.

## Current state overview

Before enabling Kotlin Archive, when you publish a multiplatform library you get the following artifacts:

* root component `$group:$library:$version`
  * `$library-$version.jar` --- metadata of common source-sets
  * gradle.metadata describing all variants
    * There is a variant per platform referencing the platform component
* jvm platform component `$group:$library-jvm:$version`
  * `$library-jvm-$version.jar`
* android platform component `$group:$library-android:$version`, plus `$group:$library-android-debug:$version` if the debug variant is published
  * `$library-android-$version.aar`
* other platform components `$group:$library-$platform:$version`
  * `$library-$platform-$version.klib`
  * For native targets with cinterops an additional klib for each cinterop `$library-$platform-$version-cinterop-$cinterop.klib`
  
Correct artifacts can be resolved by Gradle with variant-aware resolution:
* A user (consumer) declares a dependency on the root component
* For common compilation, Gradle selects metadata jar of common source-sets
* For platform compilations, Gradle selects the matching variant using attributes such as the target platform. That variant contains an `available-at` reference to a separate platform component, from which Gradle gets the required artifacts, including necessary klibs

With the new format, a build system can obtain the same set of artifacts as before by transforming downloaded artifacts. The main change is in dependency resolution and artifact preparation. We recommend other build systems to support it in the same way.

## New artifact

We are using `.kar` as the extension for the new artifact in the decompressed version.
This artifact is a zip archive without compression. For publication, it would be compressed additionally with XZ compression, leading to the overall 
`.kar.xz` extension. 

That allows us to bring good parts from both formats:
* XZ allows to compress better, and reuse compression between entries
  * That's exceptionally important, as many parts compiled for different platforms are almost the same
* Zip provides the ability to use directory structures, and easily reuse the parts of the file further in the pipeline, providing better compatibility with existing tools.

As an additional optional size optimization, entries in the archive are ordered
not in tree-order, but by file name. This allows XZ compression to better
utilize the compression window on similar parts, as they would be closer
to each other. 

We considered using more modern stream compression algorithms, but decided to stick with xz for the following reasons:
* It's widely available everywhere, as it's used for tarballs distribution
* It has a pure java implementation without native dependencies, which significantly simplifies distribution of the Gradle plugin.
* Kotlin Gradle plugin already bundles it, so it wouldn't increase its size. 

Gradle metadata uses an attribute to indicate the compression format used, so
it would be possible to support new formats in the future. 

## Archive content

The archive file itself has the following structure:
```
<library>.kar   # Uncompressed ZIP stream after XZ decompression
├── metadata/   # The content of the metadata jar from the root component  
│   ├── project-structure-metadata.json 
│   ├── <source-set-name>/                
│   │   └── ... unpacked metadata KLIB contents
│   └── <native-source-set>-cinterop/   
│       └── <cinterop1-name>
│           └── ... unpacked commonized  metadata KLIB contents
│       └── <cinterop2-name>
│           └── ... unpacked commonized  metadata KLIB contents
├── platform/       
│   └── <target-name>/  
│       └── ... unpacked platform KLIB contents for this target
├── cinterops/ 
│   └── <target-name>/
│       ├── <cinterop1-name>/
│       │   └── ... unpacked C interop KLIB contents
│       └── <cinterop2-name>/
│           └── ... unpacked C interop KLIB contents
└── manifest.json # just a `{"version":"1.0"}` for now
```

One can easily create it from the old set of artifacts, or
recreate old artifacts from it, by just repacking relevant parts.

## Gradle metadata compatibility

For targets included in Kotlin Archive, there are no separate platform components anymore. Their variants remain in the root component’s Gradle metadata with the following changes:
* Variants reference the archive (`.kar`) directly. They don't use `available-at` reference to a separate platform component anymore
* Variants declare new `org.jetbrains.kotlin.kar.compression.method=xz` attribute
* Variants declare `group:library:$version` capability (root) alongside `group:library-platform:version` (platform) capability.
* That's required as there is no more platform component, but we still can't allow having both the old platform component and the new Kotlin Archive in one publication

JVM, Android or custom targets are not supported in Kotlin Archive: they keep their own platform components referenced with "available-at" pointers, as before.

That metadata provides enough information for a build system to
unpack the Kotlin Archive into old artifacts and proceed as before that change.

Another change is `metadataApiElements` and `metadataRuntimeElements` are now named `metadataApiElements-published` and `metadataRuntimeElements-published` consistently with other variants. The name of a variant doesn't participate in resolution, so it's a purely cosmetic change. 

## Sources publication

Sources jars, if published before, were using the same scheme:
- Common sources are stored in the root sources.jar
- Sources variant per platform, referencing the other component. 

As there is no other component anymore, sources for platforms
stored in Kotlin Archive should be added to the root component `sources.jar`, which should be now referenced as artifact from these variants. 
Similarly to platform variants, these variants should now declare capabilities, to avoid having both the old and the new jar in one resolution.

IDE import is able to use variant-aware resolution to find such sources.

## Known limitations

* Kotlin Gradle plugin 2.4.20 and before is not able to consume this artifact.
  * That's not a big deal, as the 2.4.20 compiler wouldn't be able to consume klibs produced by the 2.5 compiler anyway
  * Kotlin Gradle plugin 2.4.20 reports a warning about `.kar` files leaking into the compiler class-path, while 2.4 and before silently ignore them. 
* If a library includes lots of platform-specific native artifacts, compression is limited
  * For example, for ktor, a single module which includes libcurl is now bigger than all other modules combined.

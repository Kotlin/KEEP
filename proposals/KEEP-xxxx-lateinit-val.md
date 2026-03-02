# lateinit val

* **Type**: Design Proposal
* **Author**: Mikhail Vorobev
* **Contributors**: Marat Akhin, Faiz Ilham Muhammad
* **Discussion**: [KEEP-0452](https://github.com/Kotlin/KEEP/discussions/471)
* **Status**: Public Discussion

# Abstract

We propose to introduce `lateinit val` declarations to Kotlin
to provide first-class support for properties
with delayed initialization and assign-once semantics.

`lateinit val` bridges the gap between `lateinit var` and `val`:
it allows late initialization while preventing reassignment
and enabling smartcasts.
It is compiled to delegation to the thread-safe `AssignOnce` delegate
provided by the standard library.

The `AssignOnce` delegate can also be used directly
for cases where customization (e.g., thread-safety mode) is desired,
though as a regular delegated property it does not benefit from smartcasts.

# Table of Contents

<!-- TOC -->
* [Abstract](#abstract)
* [Motivation](#motivation)
* [Goals](#goals)
* [Intended Semantics](#intended-semantics)
* [Design](#design)
  * [`AssignOnce` Delegate](#assignonce-delegate)
  * [`lateinit val` Declaration](#lateinit-val-declaration)
    * [Compilation Strategy](#compilation-strategy)
* [Interaction with Other Features](#interaction-with-other-features)
  * [Annotations](#annotations)
  * [`isInitialized`](#isinitialized)
  * [Serialization](#serialization)
  * [Reflection](#reflection)
* [Migration from `lateinit var`](#migration-from-lateinit-var)
<!-- TOC -->

# Motivation

TODO

## Goals

TODO

# Intended Semantics

TODO

# Design

## `AssignOnce` Delegate

TODO

## `lateinit val` Declaration

TODO

### Compilation Strategy

TODO

# Interaction with Other Features

## Annotations

TODO

## `isInitialized`

TODO

## Serialization

TODO

## Reflection

TODO

# Migration from `lateinit var`

TODO

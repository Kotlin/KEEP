# KEEP - Kotlin Evolution and Enhancement Process

This repository contains proposals for the [Kotlin Programming Language](https://kotlinlang.org), including 
draft design notes and discussions for in-progress proposals as well as
the design documentation on the changes that were already implemented. 

The proposals themselves are colloquially referred to as KEEPs. 
They cover both the language itself and its standard library. 

The implementation of the corresponding design enhancements usually lives in 
the [Kotlin Source Code Repository](https://github.com/JetBrains/kotlin).

## Current KEEPs

* Current in-progress KEEPs are listed in [issues](https://github.com/Kotlin/KEEP/issues).
* New KEEPs and additions to current KEEPs are submitted as [pull requests](https://github.com/Kotlin/KEEP/pulls).
* When KEEPs are implemented, the corresponding design documents are merged into this repository and stored in a [proposals](proposals) directory.

### Design notes

Some feature ideas that are being discussed for Kotlin represent important directions of potential enhancement, but 
are not complete to call them design proposals, yet they still need to be discussed
with the Kotlin community to gather use-cases for these features, their potential syntax, impact on existing Kotlin code, etc.
They are called "design notes" and are stored in a separate [notes](notes) directory.

## How to contribute to the design process

Language enhancements/features usually go through the following informal stages:

1. Discussion of an idea.
2. Collection of use-cases.
3. Design proposal and prototype implementation.
4. Experimental support in the language.
5. Further refinement and stable release.

All stages involve gathering of feedback on the corresponding feature.
It does not stop even when the feature becomes stable.
The community feedback on all stages is crucial to the open philosophy of Kotlin language enhancement process. 

### Contributing ideas

If you have a vague idea on the potential enhancement, not sure whether it is worthwhile and/or
fits the Kotlin language, or just want to get community feedback, you can use either
of the two channels you feel most comfortable with:

* [#language-proposals](https://kotlinlang.slack.com/messages/language-proposals/) channel in Kotlin public Slack
  (get invite [here](http://slack.kotlinlang.org/));
* [Kotlin Forum in Language design category](https://discuss.kotlinlang.org/c/language-design).

### Contributing use-cases and specific enhancement proposals

If you have a use-case that is not covered by the language or have a specific language enhancement in mind,
then, please, file an [YouTrack issue](https://kotl.in/issue) in the `Language Design` subsystem. 
While many popular enhancements and language design problems are already filed in 
[Kotlin YouTrack](https://youtrack.jetbrains.com/issues/KT?project=kt), your contribution to them is still very important:

* 👍 Vote for the issues you encounter in your work.
* 📝 Comment with the description of your specific use-cases.

Our practice of language design shows that contributing **real-life use-cases** is the most valuable piece of 
feedback from the Kotlin community.  

### Contributing design proposals (KEEPs)

Design proposals in this repository are expected to be well thought-through and usually come with
a prototype implementation that demonstrates their feasibility. All design proposals in this repository
shall be backed by maintainers of the corresponding subsystems in the Kotlin compiler or its standard library.

If you are in doubt of whether the proposal meets those criteria, please start with the discussion
of your idea, use-case, or a specific enhancement in the appropriate channels and secure support for your general
idea/proposal from maintainers before submitting a KEEP.

We will be gradually moving KEEPs that are not backed by Kotlin maintainers into YouTrack issues for further 
discussions. 

### Contributing to existing KEEPs

* For in-progress KEEPs, please keep discussions in the corresponding issue.
* If you find problems with the _text_ or have text correction for merged KEEPs, feel free to create a separate
  issue, or a pull-request with the proposed correction.

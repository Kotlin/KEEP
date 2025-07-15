[![official JetBrains project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub) [![check-uniq-keep-ids](https://github.com/Kotlin/KEEP/actions/workflows/check-uniq-keep-ids.yml/badge.svg?branch=main)](https://github.com/Kotlin/KEEP/actions/workflows/check-uniq-keep-ids.yml)

# KEEP - Kotlin Evolution and Enhancement Process

This repository contains proposals for the [Kotlin Programming Language](https://kotlinlang.org), including 
draft design notes and discussions for in-progress proposals as well as
the design documentation on the changes that were already implemented. 

The proposals themselves are colloquially referred to as KEEPs. 
They cover both the language itself and its standard library. 

The implementation of the corresponding design enhancements usually lives in 
the [Kotlin Source Code Repository](https://github.com/JetBrains/kotlin).

## KEEP document lifecycle

**1. The proposal is published.**
Every new proposal gets a KEEP number (previous proposal number + 1).
The Markdown file is prefixed with the `KEEP-xxxx-` number and merged to the `main` branch.

**2. Public KEEP review stage.**
Immediately after publishing the proposal, a new [GitHub Discussion](https://github.com/Kotlin/KEEP/discussions) is opened.
At this stage, we invite the community to read the proposal and share their opinion in the discussion.

**3. The final decision on the proposal.**
The proposal is either Accepted, Rejected, or requires further refinement.
For the specific proposals of your interest, it's also worth following an appropriate YouTrack issue
which is usually mentioned in the KEEP document header.

The up-to-date status of each proposal must be recorded in the KEEP document header.
Generally we use the following statuses:
- `Public discussion`
- `In progress`
- `Experimental in 2.0.0`
- `Stable` (discouraged, old status)
- `Stable in 2.1.0`
- `Declined`
- `Unknown` (discouraged, used for old proposals)
- `Superseded by KEEP-xxxx`

## Follow for updates

Every time a new KEEP proposal is published,
we notify the community in the following channels:
* [#language-proposals](https://kotlinlang.slack.com/messages/language-proposals/) channel in Kotlin public Slack
  (get an invite [here](http://slack.kotlinlang.org/));
* [GitHub Discussions RSS feed](https://github.com/Kotlin/KEEP/discussions/categories/keep-discussions.atom?discussions_q=category%3Akeep-discussions+sort%3Adate_created)

We encourage you to follow one of the channels and share your opinion once a new proposal reaches its public review stage.
The community feedback is crucial to the open philosophy of the Kotlin language enhancement process.

## Design notes

Some feature ideas that are being discussed for Kotlin represent important directions of potential enhancement but 
are not complete to call them design proposals. They still need to be discussed
with the Kotlin community to gather use-cases for these features, their potential syntax, impact on existing Kotlin code, etc.
They are called "design notes" and are stored in a separate [notes](notes) directory.

## Contributing use-cases and specific enhancement proposals

If you have a use case that is not covered by the language or have a specific language enhancement in mind,
then, please, file a [YouTrack issue](https://kotl.in/issue) in the `Language Design` subsystem. 
While many popular enhancements and language design problems are already filed in 
[Kotlin YouTrack](https://youtrack.jetbrains.com/issues/KT?project=kt), your contribution to them is still very important:

* 👍 Vote for the issues you encounter in your work.
* 📝 Comment with the description of your specific use-cases.

Our practice of language design shows that contributing **real-life use-cases** is the most valuable piece of
feedback from the Kotlin community.

## Contributing to existing KEEPs

* For in-progress KEEPs, please keep discussions in the corresponding GitHub Discussion thread.
* If you find problems with the _text_ or have text correction for merged KEEPs, feel free to create a separate
  pull request with the proposed correction.

## Contributing design proposals (KEEPs)

We don't expect Pull Requests that submit new KEEP proposals.
Consider submitting a YouTrack ticket and describing your real-life use-cases as described above.

#!/usr/bin/env kotlin
val duplicateIds = java.io.File("proposals").walkTopDown().filter { it.isFile }
    .flatMap { Regex("""KEEP-\d+-""").findAll(it.name) }
    .map { it.value }
    .groupingBy { it }
    .eachCount()
    .filter { it.value > 1 }
    .keys
    .filter { it != "KEEP-0412-" } // Two related proposals were submitted under a single KEEP number:
                                   // - KEEP-0412-underscores-for-local-variables.md
                                   // - KEEP-0412-unused-return-value-checker.md
if (duplicateIds.isNotEmpty()) {
    println("!!! Duplicated KEEP IDs found !!!")
    duplicateIds.forEach(::println)
    kotlin.system.exitProcess(1)
}

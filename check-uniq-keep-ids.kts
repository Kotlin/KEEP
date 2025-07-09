#!/usr/bin/env kotlin
val duplicateIds = java.io.File("proposals").walkTopDown().filter { it.isFile }
    .flatMap { Regex("""KEEP-\d+-""").findAll(it.name) }
    .map { it.value }
    .groupingBy { it }
    .eachCount()
    .filter { it.value > 1 }
    .keys
if (duplicateIds.isNotEmpty()) {
    println("!!! Duplicated KEEP IDs found !!!")
    duplicateIds.forEach(::println)
    kotlin.system.exitProcess(1)
}

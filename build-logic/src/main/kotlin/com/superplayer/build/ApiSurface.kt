package com.superplayer.build

import java.io.File
import java.io.InputStream
import java.util.jar.JarFile
import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses

/**
 * Rendering and comparison of a module's consumer-facing API surface.
 *
 * The signatures themselves come from `binary-compatibility-validator`, used here as a library
 * rather than as its Gradle plugin: the plugin attaches itself to the `kotlin-android` plugin id,
 * which AGP 9's built-in Kotlin support never applies, so it silently registers no tasks in this
 * build. Its signature loader is public API, so the wiring — which classes to read, and when — is
 * all that is done here. See docs/api-surface.md.
 *
 * Everything in this file is a plain function over files and strings so that it is unit-tested
 * without standing up a Gradle build, the same way [findHardcodedMedia3Versions] is.
 */

/**
 * Android's build tools generate these into every library module. They are an artifact of the
 * build rather than something a consumer is offered, and `R` in particular changes whenever a
 * resource is added, which would turn the tracked surface into noise.
 *
 * `BuildConfig` is dropped for the same reason and not because it is uninteresting: a module that
 * wants to publish a constant should declare it, in Kotlin, on purpose.
 */
private val GENERATED_CLASS_NAMES = Regex("""(^|/)(R|R\$[^/]+|BuildConfig)\.class$""")

/**
 * Renders the public and protected declarations reachable in [classRoots] as
 * `binary-compatibility-validator`'s canonical dump format, or the empty string when there are
 * none. [classRoots] are directories of class files and jars, which is how AGP hands a variant's
 * own compiled output over.
 *
 * The output is deterministic — BCV sorts classes and members — which is what makes it reviewable
 * as a checked-in file and diffable in a failure message.
 */
internal fun renderApiSurface(classRoots: Iterable<File>): String {
    val directories = classRoots.filter { it.isDirectory }
    // Held open for the whole load — the signature loader resolves supertypes across every class it
    // is given, so it has to see all roots at once — and closed in `finally` rather than per entry.
    val jars = classRoots.filter { it.isFile && it.extension == "jar" }.map { JarFile(it) }

    try {
        val fromDirectories = directories.asSequence().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && !it.isGenerated() }
                .map { it.inputStream() as InputStream }
        }

        val fromJars = jars.asSequence().flatMap { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(".class") && !GENERATED_CLASS_NAMES.containsMatchIn(it.name) }
                .map { jar.getInputStream(it) }
        }

        // The loader closes each class stream it is handed; only the archives are ours to release.
        val signatures = (fromDirectories + fromJars).loadApiFromJvmClasses().filterOutNonPublic()
        return signatures.dump(StringBuilder()).toString()
    } finally {
        jars.forEach { it.close() }
    }
}

private fun File.isGenerated(): Boolean = GENERATED_CLASS_NAMES.containsMatchIn(invariantSeparatorsPath)

/**
 * Returns a human-readable account of how [actual] departs from [expected], or null when they
 * agree. Trailing-newline differences are not a divergence: a dump is a set of lines.
 *
 * [expected] is null when the tracked file does not exist yet, which a new module's first build
 * hits. That is drift too, and reported in the same place so there is one answer to "why did the
 * API surface check fail".
 *
 * The report names the tracked file and the command that regenerates it, because the failure a
 * contributor sees is the whole of the documentation they will read at that moment.
 */
internal fun describeApiSurfaceDrift(expected: String?, actual: String, trackedFile: String, updateTask: String): String? {
    if (expected == null) {
        return "$trackedFile does not exist. Run `./gradlew $updateTask` to create it, and commit " +
            "it: the public API surface of a published module is tracked in this repository."
    }

    val expectedLines = expected.lines().dropLastWhile { it.isEmpty() }
    val actualLines = actual.lines().dropLastWhile { it.isEmpty() }
    if (expectedLines == actualLines) return null

    // Counted, not set, difference. The dump repeats lines constantly — `}`, blank separators,
    // `public fun <init> ()V` — so subtracting sets would drop a genuinely changed line whenever
    // the same text happens to appear somewhere else, and report drift with an empty body.
    val removed = expectedLines.minusCounted(actualLines)
    val added = actualLines.minusCounted(expectedLines)

    return buildString {
        appendLine("The public API surface has changed but $trackedFile has not been regenerated.")
        appendLine()
        if (removed.isEmpty() && added.isEmpty()) {
            // Same lines, different order. BCV sorts its output, so this means the dump format
            // itself changed — worth saying so rather than printing nothing at all.
            appendLine("  The same declarations are present but their order changed.")
        } else {
            removed.forEach { appendLine("  - $it") }
            added.forEach { appendLine("  + $it") }
        }
        appendLine()
        appendLine("If the change is intended, run `./gradlew $updateTask` and commit the result")
        appendLine("as part of the same change, so the new surface is reviewed rather than discovered.")
    }
}

/**
 * Returns the entries of this list that [other] does not account for, respecting repeats: a line
 * present three times here and once in [other] is returned twice.
 */
private fun List<String>.minusCounted(other: List<String>): List<String> {
    val remaining = other.groupingBy { it }.eachCount().toMutableMap()
    return filter { line ->
        val available = remaining[line] ?: 0
        if (available > 0) {
            remaining[line] = available - 1
            false
        } else {
            true
        }
    }
}

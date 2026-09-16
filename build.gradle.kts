plugins {
    // Applied `apply false` so the versions resolve once, from the catalog, for all modules.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false

    // Formatting and Apache-2.0 file headers. Applied to the root project only; the `spotless`
    // block below reaches every Kotlin file in the repository by path rather than by project.
    alias(libs.plugins.spotless)

    // Repo-wide verification: see build-logic.
    id("superplayer.verification")
    id("superplayer.trace-conversion")
    // Device measurement, deliberately outside `check`: see build-logic and devicelab/README.md.
    id("superplayer.devicelab")
}

// Where the license header ends and the file begins.
//
// Spotless replaces everything before the first match of this pattern with the header, so the
// pattern has to match the first line of real content — and must never match the header itself,
// or every run would prepend another copy.
//
// `/**` is in the list because each module's `package-info.kt` opens with a KDoc block above its
// `package` line. Matching only `package ` would make Spotless treat that documentation as a
// stale header and delete it. `/**` cannot match the header, which opens with a single asterisk.
val licenseHeaderDelimiter = """(/\*\*|package |@file|import )"""

// What to leave out: the output and scratch directories of the Gradle builds under this root, which
// sit at a known depth — `build/` and `.gradle/` here, and the same pair inside each module, inside
// `build-logic/` and inside `demo/`.
//
// Spelled out rather than written `**/build/**`, which was the first attempt and was wrong: `**`
// matches across directory separators, so it also excluded
// `build-logic/src/main/kotlin/com/superplayer/build/`, the package the convention plugins' task
// classes live in. Ten Kotlin files silently went unformatted and unheadered, and nothing said so.
val gradleOutputDirectories = listOf("build/**", "*/build/**", ".gradle/**", "*/.gradle/**")

// What else to leave out: the git worktrees an agent working on this repository checks out under
// `.claude/worktrees/`. They are whole copies of the tree, so each carries its own `build/` and
// `.gradle/` at *its* depth rather than at one of the four above, and a worktree left behind after
// a batch of issues puts thousands of generated Kotlin files — kotlin-dsl accessors, extracted
// plugin blocks — where `**/*.kt` reaches them. What that looks like is a local `check` failing on
// `spotlessKotlinCheck` for files no commit contains, while CI, which builds a clean checkout, is
// green; the cause takes longer to find than it has any right to.
//
// `**` across separators is what is wanted here, unlike above: nothing under this path belongs to
// any of the three builds, so excluding the subtree whole is exactly right.
val agentWorktrees = listOf(".claude/worktrees/**")

// The ktlint settings from `.editorconfig`, handed to ktlint through the channel it actually reads.
//
// Spotless's ktlint step consults `.editorconfig` for the rules it only *reports*, but formats with
// ktlint's own defaults whatever that file says: a rule switched off there is still applied when it
// can autocorrect, which is how a `max_line_length = off` and a disabled `function-signature` end up
// producing 152-column lines anyway. `editorConfigOverride` is the channel that does reach the
// formatter. Reading the settings out of `.editorconfig` rather than repeating them here keeps that
// file the one place the rules are written down — and the place an IDE reads them from.
//
// Sections are merged most general first, so `[*.{kt,kts}]` wins over `[*]` exactly as .editorconfig
// itself resolves them. `[*]` is read at all so that a setting applying to every file in the
// repository reaches ktlint too, rather than holding only because ktlint's default happens to agree.
//
// This function, the delimiter above and the `spotless` block below are repeated in
// `demo/build.gradle.kts`, and that is not for want of trying to share them. A script applied with
// `apply(from = ...)` is compiled against the base build classpath and cannot see Spotless's types
// at all; and applying any script that way makes Android Lint crash while analysing the demo's build
// scripts, which takes down the `lintDebug` that proves ADR-0001 rule 2 from a consumer's side.
// What the two copies read — `config/license-header.txt` and `.editorconfig` — is shared, so the
// values they format by cannot drift; only the wiring is written twice.
fun readEditorConfigSettings(editorConfig: File, sections: List<String>): Map<String, String> {
    val bySection = sections.associateWith { mutableMapOf<String, String>() }
    var section = ""
    editorConfig.forEachLine { line ->
        val text = line.trim()
        when {
            text.startsWith("[") -> section = text

            text.isEmpty() || text.startsWith("#") -> Unit

            else -> bySection[section]?.put(
                text.substringBefore('=').trim(),
                text.substringAfter('=').trim()
            )
        }
    }
    return sections.fold(emptyMap()) { merged, name -> merged + bySection.getValue(name) }
}

val ktlintSettings = readEditorConfigSettings(file(".editorconfig"), listOf("[*]", "[*.{kt,kts}]"))

// Formatting and license headers, for all three Gradle builds.
//
// This repository is three builds — root, the included `build-logic`, and the standalone `demo` —
// but it is one directory tree, so Spotless is configured here by *path*: one
// `./gradlew spotlessApply` at the root formats every Kotlin file in the repository, the demo's and
// build-logic's included, and `./gradlew check` verifies all of them for the same reason.
//
// `demo/build.gradle.kts` applies Spotless a second time over its own sources. That is not
// redundant: the demo has its own wrapper and CI builds it without the root build running at all,
// so without it `(cd demo && ./gradlew ...)` would enforce nothing.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude(gradleOutputDirectories + agentWorktrees)
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintSettings)
        licenseHeaderFile(file("config/license-header.txt"), licenseHeaderDelimiter)

        // The one exemption that cannot be expressed as a rule setting at all.
        // `standard:no-empty-file` counts a file whose only content is a `package` line and
        // documentation as empty — which is exactly what each module's `package-info.kt` is — and
        // it keeps reporting whether the property is set to `disabled` or to `false`. Suppressing
        // the lint through Spotless is what works; `.editorconfig` records why.
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:no-empty-file"
        }
    }
    kotlinGradle {
        // The convention plugins in `build-logic/src/main/kotlin` are `.gradle.kts` scripts and are
        // formatted here, alongside the build files. They carry no license header: a Gradle script
        // has no `package` line to anchor one to, and headers are tracked on `.kt` sources.
        target("**/*.gradle.kts")
        targetExclude(gradleOutputDirectories + agentWorktrees)
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintSettings)
    }
}

// Spotless attaches `spotlessCheck` to the lifecycle `check` task itself, so formatting and headers
// are part of what `./gradlew check` means without any wiring here.

import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application)
    // Not applied, only placed on the buildscript classpath, so AGP 9's built-in Kotlin support
    // resolves the catalog's Kotlin rather than its own bundled one. `demo/build.gradle.kts`
    // explains why that is load-bearing; the same applies here for the same reason.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.spotless)
}

// Formatting and license headers for the benchmark's own sources.
//
// The root build's Spotless configuration already reaches these files by path, so
// `./gradlew spotlessApply` at the root formats them. This second application is what makes the
// benchmark enforce the same thing *without* the root build, exactly as `demo/` does: this build has
// its own wrapper and CI runs it with nothing of the root build running.
//
// The block below is a copy of the root `build.gradle.kts`, which is where it is explained. What the
// copies read is shared — `config/license-header.txt` and `.editorconfig` are one file each — so the
// header text and the ktlint rules cannot come to disagree. Only the wiring is written three times.
val licenseHeaderDelimiter = """(/\*\*|package |@file|import )"""

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

val ktlintSettings = readEditorConfigSettings(file("../.editorconfig"), listOf("[*]", "[*.{kt,kts}]"))

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintSettings)
        licenseHeaderFile(file("../config/license-header.txt"), licenseHeaderDelimiter)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintSettings)
    }
}

extensions.configure<ApplicationExtension> {
    namespace = "com.superplayer.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.superplayer.benchmark"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // The build type a device run measures: release-like, so the numbers describe a build
        // somebody would ship, but installable without release signing and profileable so Perfetto
        // can attach. The same reasoning, and the same shape, as `demo/`'s — see its build file and
        // `devicelab/README.md`. A debuggable build runs meaningfully slower, and a peak-RSS or
        // battery figure taken from one describes a build nobody ships.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources out of the unit-test build.
            // Without this it starts against an empty resource table and every test that touches the
            // application context fails obscurely.
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Disabled here, and deliberately *not* in `demo/`, which is the whole point of the
        // difference. The demo keeps `UnsafeOptInUsageError` on because it is the proof of ADR-0001
        // rule 2 from a consumer's side: an ordinary app integrating SuperPlayer needs no opt-in.
        //
        // The benchmark is not an ordinary app. Arms (a) and (b) *are* a bare `ExoPlayer` and a
        // `DefaultLoadControl`, and the metrics for them come off Media3's `AnalyticsListener` —
        // every one of those is `@UnstableApi`, and naming them is this build's entire job. Keeping
        // the check on here would say nothing about the library's API and would only be noise.
        disable += "UnsafeOptInUsageError"

        abortOnError = true
        warningsAsErrors = false
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// How the matrix is configured, from the command line to the runner.
//
// `benchmark/bench` is the entry point a person uses and it passes these; the defaults below are
// what `./gradlew test` gets, which is deliberately the smoke matrix rather than the full one. A
// benchmark is a measurement someone reads, not a check a change must pass, so the full matrix is
// something you ask for — but the runner itself has to keep working between baselines, and a
// one-run-per-cell pass of it under `test` is what proves that without costing minutes.
//
// README.md is the manual. `-Pruns` and `-Pout` are the two knobs.
tasks.withType<Test>().configureEach {
    (findProperty("runs") as String?)?.let { systemProperty("superplayer.benchmark.runs", it) }
    (findProperty("out") as String?)?.let { systemProperty("superplayer.benchmark.out", it) }
    (findProperty("cells") as String?)?.let { systemProperty("superplayer.benchmark.cells", it) }

    // The full matrix plays hundreds of sessions in one JVM. Robolectric's default heap is not sized
    // for that, and the failure mode without this is an OutOfMemoryError partway through a run that
    // has already taken minutes.
    maxHeapSize = "4g"

    // A benchmark run is never up to date: the report records the host it ran on, so a run skipped
    // because Gradle could see no input change would be a measurement of a different machine
    // wearing this one's name. Cheap to state, and only ever wrong in the direction of re-measuring.
    outputs.upToDateWhen { false }

    // The matrix prints its progress as it goes — a full run is minutes, and a silent one reads as a
    // hang. Robolectric's own warnings are already on stderr; this is the runner's own stdout.
    testLogging {
        showStandardStreams = true
        events("failed")
    }
}

dependencies {
    // Published coordinates, NOT project(":superplayer-core"). See settings.gradle.kts.
    implementation("com.superplayer:superplayer-core:${libs.versions.superplayer.get()}")
    // Arm (c)'s metrics come from the shipped collector rather than from anything written here,
    // which is half of what makes the three arms comparable. The other half is `StockTelemetry`.
    implementation("com.superplayer:superplayer-telemetry:${libs.versions.superplayer.get()}")
    // Arms (a) and (b) are a bare `ExoPlayer`, and `StockTelemetry` reads Media3's own
    // `AnalyticsListener`. Both are named directly here rather than reached through core's graph,
    // because this build depends on them deliberately rather than incidentally.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.androidx.annotation)

    // The deterministic half of the matrix. `superplayer-testkit` is what builds all three arms over
    // one clock and one shaped transport (`PlaybackHarness`), and it is test-only: the device arm
    // plays real streams over a real network and loads none of it.
    testImplementation("com.superplayer:superplayer-testkit:${libs.versions.superplayer.get()}")
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
}

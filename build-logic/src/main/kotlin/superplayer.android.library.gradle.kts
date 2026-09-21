import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.superplayer.build.CheckApiSurface
import com.superplayer.build.DumpApiSurface
import com.superplayer.build.ModulePublication
import com.superplayer.build.UpdateApiSurface
import com.superplayer.build.VerifyNoUnstableMedia3InPublicApi
import com.superplayer.build.isResolvableByAnAdopter
import com.superplayer.build.modulePublicationOf
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    // AGP 9 has built-in Kotlin support; the separate org.jetbrains.kotlin.android plugin
    // is no longer applied. See https://kotl.in/gradle/agp-built-in-kotlin
    id("com.android.library")
    id("maven-publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// One JVM target, declared once in the catalog, applied to both Java and Kotlin.
val jvmTargetVersion = libs.findVersion("jvmTarget").get().requiredVersion
val javaVersion = JavaVersion.toVersion(jvmTargetVersion)

group = "com.superplayer"
version = libs.findVersion("superplayer").get().requiredVersion

// Whether this module is published, read out of `settings.gradle.kts` — the one place that is
// declared (ADR-0017 rule 1, `ModulePublication`). Three things below hang off it, and a module
// that declaration cannot account for fails here rather than being published by default.
//
// `providers.fileContents` rather than `File.readText()`, so the configuration cache knows the
// build was configured against this file and re-runs configuration when it moves. The path is
// relative to this module because every library module is a direct child of the root, which
// `settings.gradle.kts` itself would have to change for it to stop being true.
val settingsScript = layout.projectDirectory.file("../settings.gradle.kts")
val publication = modulePublicationOf(
    providers.fileContents(settingsScript).asText.get(),
    project.name
) ?: error(
    "settings.gradle.kts declares neither an include(\":${project.name}\") nor an " +
        "unpublishedModules or locallyPublishedModules entry for it, so whether it is published " +
        "is unknown. Add it to one of the three rather than leaving the answer to a default " +
        "(see ModulePublication)."
)

// Two questions, not one, and #353 is what separated them. *Does an artifact exist* decides whether
// a Maven publication is registered; *can an adopter resolve it* decides whether a public API
// surface is tracked. They answered together until `superplayer-moq` became LOCAL_ONLY: the demo
// build resolves published coordinates, so it needs the artifact, while nothing about #369's
// licence and ABI questions is changed by one existing in this machine's local repository.
val publishesAnArtifact = publication != ModulePublication.NOT_PUBLISHED
val published = publication.isResolvableByAnAdopter

extensions.configure<LibraryExtension> {
    namespace = "com.superplayer." + project.name.removePrefix("superplayer-").replace('-', '.')
    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    lint {
        // Media3's @UnstableApi is an androidx RequiresOptIn marker, enforced by Android
        // Lint rather than by the Kotlin compiler, so a Kotlin `-opt-in=` argument does
        // nothing for it. SuperPlayer's modules exist to wrap Media3's engine surface, so
        // they opt in module-wide here instead of annotating every file.
        //
        // This is not what keeps the instability contained. ADR-0001 rule 2 does that, by
        // barring @UnstableApi types from SuperPlayer's own public API, and the tracked API
        // signature check is what will enforce it mechanically.
        disable += "UnsafeOptInUsageError"

        // Explicit rather than inherited: a lint error fails the build, in CI and locally.
        abortOnError = true

        // Warnings are deliberately not errors. Some lint checks are time-dependent rather
        // than change-dependent — GradleDependency fires when a newer version of a
        // dependency is published — so promoting warnings would redden CI on changes that
        // are not at fault, and a check that cries wolf is a check that gets turned off.
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources out of the unit-test APK-less
            // build. Without this it starts against an empty resource table and every test that
            // touches a resource or the application context fails obscurely.
            isIncludeAndroidResources = true
        }
    }

    // One publication per module, built from the release variant — for a module that publishes an
    // artifact at all. An unpublished one declares no `release` component, so `publish` has nothing
    // to offer rather than offering something nobody registered a destination for.
    if (publishesAnArtifact) {
        publishing {
            singleVariant("release") {
                withSourcesJar()
            }
        }
    }
}

// Golden traces, tracked in `src/test/golden/` and validated by the module's own tests.
//
// The contract mirrors the API surface above: a test compares what a session produced against the
// committed file through `GoldenFile` (superplayer-testkit), fails on any difference with the diff
// in the message, and nothing rewrites the file implicitly. `updateGoldenTraces` is the one thing
// that does — it runs the golden tests alone, in update mode, and the diff it leaves is reviewed
// with the change that caused it. `docs/testing.md`, *Golden traces*, says what a diff means.
//
// The mode reaches the test JVM as a system property, decided once here from what was asked for on
// the command line: there is one unit-test task per variant and it cannot run in two modes at once,
// so `./gradlew updateGoldenTraces check` would run `check`'s tests in update mode too — run the
// update on its own, then `check`. The directory travels the same way, relative because AGP runs
// unit tests from the module directory, so the property carries nothing machine-specific into the
// task's inputs.
val goldenTracesDirectory = layout.projectDirectory.dir("src/test/golden")
val updatingGoldenTraces = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "updateGoldenTraces" }

tasks.withType<Test>().configureEach {
    // A failed assertion's message in the console, not only in the XML report. Truth puts what was
    // expected and what was observed in the message, and Gradle's default prints the exception's
    // class and line alone, so a table test like the hostile corpus went red on CI saying nothing
    // about which row moved until the build-reports artifact was downloaded (issue #105).
    testLogging {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
    }
    systemProperty("superplayer.golden.dir", "src/test/golden")
    systemProperty("superplayer.golden.mode", if (updatingGoldenTraces) "update" else "check")
    // A golden edited by hand re-runs the tests that hold it to the file.
    inputs.files(fileTree(goldenTracesDirectory))
        .withPropertyName("goldenTraces")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    if (updatingGoldenTraces) {
        outputs.upToDateWhen { false }
        filter {
            // The convention: a test that holds a golden has `GoldenTrace` in its class name.
            includeTestsMatching("*GoldenTrace*")
            isFailOnNoMatchingTests = false
        }
    }
}

tasks.register("updateGoldenTraces") {
    group = "verification"
    description = "Rewrites src/test/golden/ from what this module's golden trace tests currently produce."
    dependsOn("testDebugUnitTest")
}

// The consumer-facing API surface, tracked in `api/<module>.api` and validated by `check`.
//
// Read off the release variant's own compiled classes, which is what a consumer resolves.
// `updateApiSurface` regenerates the tracked file; nothing regenerates it implicitly, because the
// point of tracking it is that widening the surface produces a reviewable diff. ADR-0001 rule 2
// depends on this: an `@UnstableApi` Media3 type reaching public API cannot land unnoticed.
//
// An unpublished module has none of this, and that is the same fact rather than a second decision:
// `docs/api-surface.md` tracks a surface because a *consumer* resolves the artifact, and there is
// no artifact. A tracked file for one would be a diff nobody outside this repository can be broken
// by, reviewed as though they could.
if (published) {
    val trackedApiSurfaceFile = layout.projectDirectory.file("api/${project.name}.api")
    val builtApiSurfaceFile = layout.buildDirectory.file("api-surface/${project.name}.api")

    val dumpApiSurface = tasks.register<DumpApiSurface>("dumpApiSurface") {
        group = "verification"
        description = "Writes the release variant's public API surface into the build directory."
        apiFile.set(builtApiSurfaceFile)
    }

    val updateApiSurface = tasks.register<UpdateApiSurface>("updateApiSurface") {
        group = "verification"
        description = "Rewrites api/${project.name}.api from what this module currently builds."
        builtApiFile.set(dumpApiSurface.flatMap { it.apiFile })
        trackedApiFile.set(trackedApiSurfaceFile)
    }

    val checkApiSurface = tasks.register<CheckApiSurface>("checkApiSurface") {
        group = "verification"
        description = "Fails if the built public API surface differs from the tracked api/${project.name}.api."
        builtApiFile.set(dumpApiSurface.flatMap { it.apiFile })
        trackedApiFile.from(trackedApiSurfaceFile)
        trackedApiFilePath.set("${project.name}/api/${project.name}.api")
        updateTaskPath.set("${project.path}:updateApiSurface")
        stampFile.set(layout.buildDirectory.file("verification/api-surface.txt"))

        // `./gradlew updateApiSurface check` is the natural "regenerate, then verify" invocation,
        // and both tasks touch the tracked file. Without an ordering Gradle refuses the pair
        // outright.
        mustRunAfter(updateApiSurface)
    }

    val verifyNoUnstableMedia3InPublicApi =
        tasks.register<VerifyNoUnstableMedia3InPublicApi>("verifyNoUnstableMedia3InPublicApi") {
            group = "verification"
            description = "Fails if an @UnstableApi Media3 type has reached this module's public API."
            apiSurfaceFile.set(dumpApiSurface.flatMap { it.apiFile })
            stampFile.set(layout.buildDirectory.file("verification/no-unstable-media3-in-public-api.txt"))
        }

    tasks.named("check") {
        dependsOn(checkApiSurface, verifyNoUnstableMedia3InPublicApi)
    }

    extensions.configure<LibraryAndroidComponentsExtension> {
        onVariants(selector().withBuildType("release")) { variant ->
            variant.artifacts
                .forScope(ScopedArtifacts.Scope.PROJECT)
                .use(dumpApiSurface)
                .toGet(
                    ScopedArtifact.CLASSES,
                    DumpApiSurface::classJars,
                    DumpApiSurface::classDirectories,
                )

            // The compile classpath hands out `.aar` files, and Media3's annotations sit inside
            // their nested `classes.jar`. AGP publishes that unpacked jar as an artifact view, so
            // the check asks for it by artifact type rather than learning to open an archive
            // inside an archive.
            verifyNoUnstableMedia3InPublicApi.configure {
                media3Classpath.from(
                    variant.compileConfiguration.incoming.artifactView {
                        attributes.attribute(
                            Attribute.of("artifactType", String::class.java),
                            "android-classes-jar",
                        )
                    }.files
                )
            }
        }
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    // Explicit API mode: every public declaration in a library main source set states its
    // visibility and its return type, or it does not compile.
    //
    // This is the half the tracked API surface cannot do. `api/<module>.api` reports that the
    // surface changed, after the fact; Kotlin's default is `public`, so widening the API is what
    // happens when an author writes nothing at all, and the diff cannot recover whether they meant
    // it. Here publicness is something someone typed, which is what makes a surprising diff a real
    // question rather than possibly an oversight. The explicit return type serves ADR-0001 rule 2
    // for the same reason: an inferred public signature can change when a body changes, with
    // nothing in the source diff to show it.
    //
    // It applies to main sources only — test source sets are exempt by Kotlin's own rule — and
    // only to modules applying this plugin, so `demo/`, a consumer rather than a library, is
    // untouched.
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
    }
}

dependencies {
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
    add("testImplementation", libs.findLibrary("junit").get())
}

// `maven-publish` stays applied to every module — it is in the `plugins` block above, which is
// evaluated before anything can be decided — and an unpublished module registers no publication
// under it, so `publishToMavenLocal` produces nothing for it (ADR-0017 rule 1,
// `ModulePublication`). A LOCAL_ONLY module does register one: the artifact reaches this machine's
// local repository, where `demo/` resolves it, and no remote repository is declared anywhere in
// this build for it to reach instead (`docs/releasing.md`).
if (publishesAnArtifact) {
    extensions.configure<PublishingExtension> {
        publications {
            register<MavenPublication>("release") {
                // AGP creates the `release` software component during evaluation, so it cannot
                // be referenced while this plugin is being applied.
                afterEvaluate { from(components["release"]) }
                artifactId = project.name
                pom {
                    name.set(project.name)
                    description.set("SuperPlayer — a production playback layer on AndroidX Media3.")
                    url.set("https://github.com/ramesh130/superplayer")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }
}

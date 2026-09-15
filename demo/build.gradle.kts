import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    // Not applied, only placed on the buildscript classpath. AGP 9's built-in Kotlin support
    // resolves the Kotlin compiler from there, so this pins the demo to the same catalog
    // Kotlin version the library is built with.
    //
    // This is load-bearing: without it the demo compiles with AGP 9.4's bundled Kotlin 2.2.0
    // and fails on the library's 2.4.10 stdlib metadata ("Module was compiled with an
    // incompatible version of Kotlin"). The library gets the same pin implicitly, because
    // build-logic puts the Kotlin Gradle plugin on its own classpath. If an AGP upgrade
    // changes how the built-in Kotlin version is chosen, this is the line to revisit.
    alias(libs.plugins.kotlin.android) apply false
    // Applied, unlike the line above: the Compose compiler is a Kotlin compiler plugin and the
    // UI does not compile without it. Its version is the catalog's Kotlin version, because the
    // Compose compiler is released as part of Kotlin.
    alias(libs.plugins.kotlin.compose)
    // Formatting and Apache-2.0 file headers. See below.
    alias(libs.plugins.spotless)
}

// Formatting and license headers for the demo's own sources.
//
// The root build's Spotless configuration already reaches these files by path, so
// `./gradlew spotlessApply` at the root formats them. This second application is what makes the
// demo enforce the same thing *without* the root build: the demo has its own wrapper, and CI runs
// `(cd demo && ./gradlew assembleDebug lintDebug spotlessCheck)` with nothing of the root build
// running.
//
// The block below is a copy of the root `build.gradle.kts`, which is where it is explained — the
// delimiter, the `.editorconfig` reading, and why the copy could not be shared as an applied
// script. What the two copies read is shared: `config/license-header.txt` and `.editorconfig` are
// one file each, so the header text and the ktlint rules cannot come to disagree. Only the wiring
// is written twice.
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
    namespace = "com.superplayer.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.superplayer.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // What devicelab measures: release-like, but installable without release signing. Not
        // debuggable, because a debuggable build runs differently and meaningfully slower, so frame
        // timings and heap graphs taken from one describe a build nobody ships. Profileable, so that
        // Perfetto and Macrobenchmark can still attach — that is the manifest's `<profileable>`,
        // which is what makes this build type worth having. See devicelab/README.md.
        //
        // If release ever turns minification on, this inherits it, and a heap graph from it is
        // unreadable without the mapping file: revisit before a leak hunt reads one.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        // `UnsafeOptInUsageError` is deliberately left ON, and this app is the only place that
        // proves ADR-0001 rule 2 from a consumer's side: the library modules disable the check
        // module-wide, because their job is to use Media3's unstable surface.
        //
        // It used to be disabled here, and that suppression was hiding a real leak. `SuperPlayer`
        // extended Media3's `@UnstableApi` ForwardingPlayer, so every call below resolved to an
        // annotated declaration and lint demanded `@OptIn(UnstableApi::class)` from the consumer —
        // five errors from five calls. The facade now implements `Player` by delegation, so the
        // methods a consumer reaches are SuperPlayer's own and carry no marker.
        //
        // If this ever needs an opt-in again, that is the news, not the fix.
        abortOnError = true
        warningsAsErrors = false
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// What SuperPlayer this APK was built against, recorded inside it.
//
// The demo resolves SuperPlayer from Maven local, so an APK built after a stale
// `publishToMavenLocal` builds, installs and plays — as the previous version. Nothing fails, and a
// measurement taken on it is a plausible number about the wrong code. devicelab pulls this file back
// off the device, compares it with the artifact it has just published, and refuses to measure on a
// mismatch (devicelab/lib/artifacts.sh).
//
// The hash is of each AAR exactly as the build resolved it, so it answers the question directly
// rather than through a version string, which for a SNAPSHOT names every build of a release alike.
// It costs an adopter nothing: this is the demo's build, not the library's, and no `superplayer-*`
// artifact carries anything of the kind.
abstract class RecordSuperPlayerArtifacts : DefaultTask() {
    @get:Internal
    lateinit var artifacts: ArtifactCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val artifactFiles: FileCollection
        get() = artifacts.artifactFiles

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun record() {
        val lines = artifacts.artifacts
            .map { artifact ->
                val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                "${id.group}:${id.module}:${id.version} ${sha256(artifact.file)}"
            }.sorted()
        val directory = outputDir.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        File(directory, "superplayer-artifacts.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        // The AARs themselves, as resolved for this variant's runtime classpath: the published files,
        // before any of AGP's transforms unpack them.
        val superPlayerArtifacts = variant.runtimeConfiguration.incoming
            .artifactView {
                attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
                componentFilter { it is ModuleComponentIdentifier && it.group == "com.superplayer" }
            }.artifacts
        val record = tasks.register<RecordSuperPlayerArtifacts>(
            "record${variant.name.replaceFirstChar(Char::uppercase)}SuperPlayerArtifacts",
        ) {
            artifacts = superPlayerArtifacts
        }
        variant.sources.assets?.addGeneratedSourceDirectory(record, RecordSuperPlayerArtifacts::outputDir)
    }
}

dependencies {
    // Published coordinates, NOT project(":superplayer-core"). See settings.gradle.kts.
    // The version comes from the shared catalog, so it cannot drift from what the library
    // actually publishes.
    implementation("com.superplayer:superplayer-core:${libs.versions.superplayer.get()}")
    // The feed's three additive modules, by published coordinates for the same reason: a POM that
    // forgets core, or a module that forgets a Media3 artifact it loads through, fails here first.
    implementation("com.superplayer:superplayer-preload:${libs.versions.superplayer.get()}")
    implementation("com.superplayer:superplayer-cache:${libs.versions.superplayer.get()}")
    implementation("com.superplayer:superplayer-telemetry:${libs.versions.superplayer.get()}")
    implementation(libs.media3.ui)
    // For `androidx.annotation.OptIn`, the form of opt-in that works on Media3's Java
    // `@UnstableApi` marker — see MainActivity.showBufferingSpinner. Named here rather than
    // resolved through media3-common, so that the demo's one opt-in does not depend on a Media3
    // POM continuing to export the annotation that expresses it.
    implementation(libs.androidx.annotation)

    // The BOM pins every androidx.compose.* artifact from one catalog version.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // `LifecycleStartEffect`: the demo acquires the player on the activity's start, not on
    // composition. See MainActivity.
    implementation(libs.androidx.lifecycle.runtime.compose)
}

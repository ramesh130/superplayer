import com.android.build.api.dsl.LibraryExtension
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

    // One publication per module, built from the release variant.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
    }
}

dependencies {
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
    add("testImplementation", libs.findLibrary("junit").get())
}

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

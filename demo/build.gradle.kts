import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Published coordinates, NOT project(":superplayer-core"). See settings.gradle.kts.
    // The version comes from the shared catalog, so it cannot drift from what the library
    // actually publishes.
    implementation("com.superplayer:superplayer-core:${libs.versions.superplayer.get()}")
    implementation(libs.media3.ui)
}

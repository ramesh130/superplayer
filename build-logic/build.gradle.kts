plugins {
    `kotlin-dsl`
}

// Convention plugins for the SuperPlayer modules. They exist so that the 12 library
// modules share one definition of "what a SuperPlayer module is" rather than 12 copies
// of the same Android/Kotlin/publishing block.

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.binary.compatibility.validator)
    implementation(libs.asm)

    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}

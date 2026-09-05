plugins {
    // Applied `apply false` so the versions resolve once, from the catalog, for all modules.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false

    // Repo-wide verification: see build-logic.
    id("superplayer.verification")
}

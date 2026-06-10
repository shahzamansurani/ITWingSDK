// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.google.services) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "androidx.core:core:1.18.0",
            "androidx.core:core-ktx:1.18.0",
        )
    }
}

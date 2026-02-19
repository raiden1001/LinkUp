plugins {
    // Root plugins with versions applied false to be used by subprojects
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
}

allprojects {
   // Repositories defined in settings.gradle.kts
}

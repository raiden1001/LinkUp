plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.kotlin.gradle)
    implementation(libs.plugin.android.gradle)
    implementation(libs.plugin.ktlint.gradle)
    implementation(libs.plugin.compose.gradle)
}

plugins {
    id("chat.jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kafka.clients)
    implementation(libs.logback)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.serialization.kotlinx.json)
}

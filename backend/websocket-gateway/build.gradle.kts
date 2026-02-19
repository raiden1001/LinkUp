plugins {
    id("chat.jvm")
    id("io.ktor.plugin") version libs.versions.ktor.get()
    alias(libs.plugins.kotlinSerialization)
}

application {
    mainClass.set("com.simplogics.chat.gateway.ApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kafka.clients)
    implementation(libs.logback)
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.plugins.JavaPluginExtension

plugins {
    id("chat.ktlint")
}

// Apply Kotlin JVM plugin using pluginManager to avoid conflicts
// when it's already applied by other plugins (e.g., Ktor plugin)
pluginManager.apply("org.jetbrains.kotlin.jvm")

extensions.configure<JavaPluginExtension>("java") {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-receivers"
        )
    }
}

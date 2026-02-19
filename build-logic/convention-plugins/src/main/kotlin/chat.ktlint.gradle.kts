import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

configure<KtlintExtension> {
    version.set("1.0.1") 
    debug.set(true)
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    outputColorName.set("RED")
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

// In KMP, there isn't a single 'compileKotlin' task.
// The listener 'check' -> 'ktlintCheck' is usually sufficient.
// If we strictly want lint before compilation, we can try matching tasks, but it might be overkill/fragile.
// Let's rely on 'check' which is standard.

tasks.matching { it.name == "check" }.configureEach {
    dependsOn("ktlintCheck")
}

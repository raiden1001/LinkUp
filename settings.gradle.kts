rootProject.name = "LinkUp"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":common")
include(":backend:auth-service")
include(":backend:chat-service")
include(":backend:websocket-gateway")
include(":backend:media-service")
include(":backend:notification-service")
include(":backend:kafka-workers")
include(":mobile:shared-ui")
include(":mobile:androidApp")

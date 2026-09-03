pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}

rootProject.name = "hdri360"

// The Android app module joins in phase 4; the core, its suite and the desktop
// harness are plain Kotlin/JVM and deliberately buildable without the Android SDK.
include(":core", ":core-tests", ":tools")

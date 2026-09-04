pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}

rootProject.name = "hdri360"

// The core, its suite and the desktop harness are plain Kotlin/JVM and remain
// buildable without the Android SDK; only :app needs it.
include(":core", ":core-tests", ":tools", ":app")

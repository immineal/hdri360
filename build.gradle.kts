// 360 HDRI Camera. The radiance core is plain Kotlin/JVM with no third-party
// runtime dependencies, so the whole test suite runs on a bare JVM in seconds.
// Repositories are declared once in settings.gradle.kts.
plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.android.application") version "9.4.0" apply false
}

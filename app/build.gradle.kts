import java.io.FileInputStream
import java.util.Properties

plugins {
    // AGP 9 has Kotlin support built in; the kotlin-android plugin is gone.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing, read from a file that is not in the repository.
//
// keystore.properties sits beside settings.gradle.kts, holds four lines
// (storeFile, storePassword, keyAlias, keyPassword) and is gitignored along with
// the .jks itself. When it is absent - which is every checkout that has not been
// set up to sign, including CI - the release build simply comes out unsigned
// rather than failing, because an unsigned APK is still a useful artefact and a
// build that dies on a missing secret is not.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val canSign = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.immineal.hdri360"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.immineal.hdri360"
        minSdk = 26              // Camera2 FULL with RAW is realistic from Oreo up
        targetSdk = 37
        // 1 was uploaded to Play before the foreground service came out; a
        // version code cannot be used twice even for a draft that never rolled.
        versionCode = 2
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    signingConfigs {
        if (canSign) create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (canSign) signingConfig = signingConfigs.getByName("release")
            // On, now that there is something to ship. Nothing here is reached
            // by name - see proguard-rules.pro - so R8 has a free hand, and the
            // line numbers are kept so a crash from a real capture is still
            // readable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}

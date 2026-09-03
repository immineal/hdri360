plugins { kotlin("jvm") }

// Zero dependencies. Everything in here is arithmetic on primitive arrays, which
// is what lets the suite run without Gradle, an emulator or the Android SDK.
kotlin { jvmToolchain(17) }

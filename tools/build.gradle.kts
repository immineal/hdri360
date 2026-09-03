plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }

// Uses javax.imageio, so it lives outside the app module by design.
dependencies { implementation(project(":core")) }

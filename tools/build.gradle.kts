plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }

// Uses javax.imageio, so it lives outside the app module by design.
dependencies { implementation(project(":core")) }

tasks.register<JavaExec>("restitch") {
    group = "verification"
    description = "Re-stitch a folder of photographs with the Kotlin core."
    mainClass.set("com.immineal.hdri360.tools.RestitchTool")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "6g"
    args = (project.findProperty("restitchArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

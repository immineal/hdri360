plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }

dependencies { implementation(project(":core")) }

// The suite is a plain main(), not a JUnit run, so tools/verify.sh can invoke it
// with nothing but a JDK. `gradle :core-tests:verify` is the same thing.
tasks.register<JavaExec>("verify") {
    group = "verification"
    mainClass.set("com.immineal.hdri360.test.TestRunner")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-ea")
    args = (project.findProperty("suiteArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

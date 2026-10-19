description = "Wow Kernel: the aggregate model and the decide, evolve, append loop"

dependencies {
    api(project(":spi"))
    api("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // The in-memory backend runs every TCK spec; the kernel's own tests use the test DSL (decision D196).
    testImplementation(project(":tck"))
    testImplementation(project(":test-dsl"))
}

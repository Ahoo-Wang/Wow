plugins {
    `java-test-fixtures`
}

description = "Wow Kernel: the aggregate model and the decide, evolve, append loop"

dependencies {
    api(project(":wow-spi"))
    api(project(":wow-manifest"))
    api("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

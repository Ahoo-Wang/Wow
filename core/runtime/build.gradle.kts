description = "Wow Runtime: gateway, router, cluster channel, executor, subscriptions, waits and lifecycle"

dependencies {
    api(project(":kernel"))
    // The Manifest the node is built from: its contexts, routes and contracts (decision D231 ③: the kernel does not
    // bring it).
    api(project(":manifest"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(testFixtures(project(":manifest")))
}

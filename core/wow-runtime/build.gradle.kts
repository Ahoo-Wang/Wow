description = "Wow Runtime: gateway, router, cluster channel, executor, subscriptions, waits and lifecycle"

dependencies {
    api(project(":wow-kernel"))
    implementation("io.github.oshai:kotlin-logging-jvm")
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

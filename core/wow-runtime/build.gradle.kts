description = "Wow Runtime: gateway, router, cluster channel, executor, subscriptions, waits and lifecycle"

dependencies {
    api(project(":wow-kernel"))
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

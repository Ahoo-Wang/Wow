description = "Wow CoCache API: the cache of another context's aggregate states that a domain injects " +
    "(StateViewCache); no dependencies"

dependencies {
    // A suspending get, called from the test.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}

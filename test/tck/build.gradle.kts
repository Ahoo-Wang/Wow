description = "Wow TCK: the contract specs of the SPI ports that every backend runs"

// The SPI contract specs (decision D196), out of :spi's test fixtures; they build on the SPI only (06-modules.md). The
// containers a backend runs them against are :tck-containers', so a spec needs no Docker.
dependencies {
    api(project(":spi"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    implementation("me.ahoo.test:fluent-assert-core")
}

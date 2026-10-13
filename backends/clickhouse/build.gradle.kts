description = "Wow ClickHouse: the ClickHouse Sink and the dataset query backend"

// The Sink of work package 6.4b (decisions D142, D164); the dataset backend comes with 6.4a.
dependencies {
    api(project(":spi"))
    api(project(":query"))
    api(libs.clickhouse.client.v2)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // Managed by the Testcontainers BOM that wow-dependencies imports; the 9.x wow-clickhouse tests use it too.
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
}

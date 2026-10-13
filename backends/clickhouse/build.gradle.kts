description = "Wow ClickHouse: the ClickHouse Sink and the dataset query backend"

// The Sink of work package 6.4b (decisions D142, D164) and the dataset query backend of 6.4a.
dependencies {
    api(project(":spi"))
    api(project(":query"))
    api(libs.clickhouse.client.v2)
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(testFixtures(project(":query")))
    // DatasetQueryBackendSpec, the dataset backend contract.
    integrationTestImplementation(testFixtures(project(":query")))
    integrationTestImplementation(sourceSets["test"].output)
    // Managed by the Testcontainers BOM that wow-dependencies imports; the 9.x wow-clickhouse tests use it too.
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
}

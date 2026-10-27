description = "Wow ClickHouse: the ClickHouse Sink and the dataset query backend"

// The Sink of work package 6.4b (decisions D142, D164) and the dataset query backend of 6.4a.
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":spi"))
    api(project(":query"))
    api(libs.clickhouse.client.v2)
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(project(":query-tck"))
    // DatasetQueryBackendSpec, the dataset backend contract.
    integrationTestImplementation(project(":query-tck"))
    // SinkSpec, the Sink contract.
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(sourceSets["test"].output)
    // The shared ClickHouse container (decision D196).
    integrationTestImplementation(project(":tck-containers"))
}

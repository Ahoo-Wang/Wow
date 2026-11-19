description = "Wow SPI: the ports a backend implements (log, snapshots, checkpoints, leases, failure ledger, reservations, sinks)"

dependencies {
    api(project(":api"))
    // The query protocol whose JSON is :spi's, shared by the query engine, the edge and the client (decision D293).
    api(project(":query-api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // WowJson: the one JSON mapper configuration of Wow (decision D122).
    api("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // ExactJson, the TCK's exact JSON comparison.
    testImplementation(project(":tck"))
}

// StrictReadsReferenceTest holds the JSON values reference's last column to the strict reads' table.
tasks.named<Test>("test") {
    val root = rootDir
    inputs.files(fileTree(root) { include("documentation/docs/*/reference/json-values.md") })
        .withPropertyName("jsonValuesReference").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("wow-test.repository.root", root.path)
}

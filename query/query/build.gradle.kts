description = "Wow Query: the query engine over the snapshot, event and dataset models, and its backend SPI"

// The coroutine port of 9.x wow-query (decision D126).
dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":query-api"))
    api(project(":spi"))
    api(project(":manifest"))
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // QueryBackendSpec, the backend contract every query backend passes, run against the in-memory reference backend
    // (main code, decision D209).
    testImplementation(project(":query-tck"))
}

// The manifest-tools snapshots the Manifest fixtures copy, which ManifestFixturesTest holds them to.
val manifestToolsSnapshots = rootProject.layout.projectDirectory.dir("compiler/manifest-tools/src/test/resources/query-keywords")
tasks.named<Test>("test") {
    systemProperty(
        "wow.manifest-tools.snapshots",
        projectDir.toPath().relativize(manifestToolsSnapshots.asFile.toPath()).toString(),
    )
    inputs.dir(manifestToolsSnapshots)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("manifestToolsSnapshots")
}

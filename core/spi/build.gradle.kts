description = "Wow SPI: the ports a backend implements (log, snapshots, checkpoints, leases, failure ledger, reservations, sinks)"

dependencies {
    api(project(":api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // WowJson: the one JSON mapper configuration of Wow (decision D122).
    api("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // ExactJson, the TCK's exact JSON comparison.
    testImplementation(project(":tck"))
}

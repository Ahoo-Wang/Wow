description = "Wow SPI: the ports a backend implements (log, snapshots, checkpoints, leases, failure ledger, reservations, sinks)"

dependencies {
    api(project(":wow-api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}

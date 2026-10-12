plugins {
    `java-test-fixtures`
}

description = "Wow SPI: the ports a backend implements (log, snapshots, checkpoints, leases, failure ledger, reservations, sinks)"

dependencies {
    api(project(":api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // WowJson: the one JSON mapper configuration of Wow (decision D122).
    api("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testFixturesImplementation("me.ahoo.test:fluent-assert-core")
}

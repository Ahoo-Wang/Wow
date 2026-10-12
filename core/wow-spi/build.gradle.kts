plugins {
    `java-test-fixtures`
}

description = "Wow SPI: the ports a backend implements (log, snapshots, checkpoints, leases, failure ledger, reservations, sinks)"

dependencies {
    api(project(":wow-api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testFixturesImplementation("me.ahoo.test:fluent-assert-core")
}

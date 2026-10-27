description = "Wow data migration: imports 9.x event streams into the V10 log and verifies them"

plugins {
    // The command-line entry point: ./gradlew :migrate-data:run --args="check --config <file>" (MigrateData.kt).
    application
}

application {
    mainClass.set("me.ahoo.wow.migrate.MigrateDataKt")
}

dependencies {
    implementation(project(":kernel"))
    // The V10 log the import writes to, on either first-class backend (decisions D11, D162, D177). The 9.x MongoDB
    // event store is read with the same sync driver :mongo builds on, by its storage format: no 9.x module (D85).
    implementation(project(":postgres"))
    implementation(project(":mongo"))
    // The events index the tags backfill re-sinks (decision D259); no other Elasticsearch use.
    implementation(project(":elasticsearch"))
    // The PostgreSQL target's connection pool (version from the Spring Boot BOM): one connection per call would exhaust
    // the client's ports on a large log (R4-Q2c).
    implementation("com.zaxxer:HikariCP")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(project(":manifest"))
    integrationTestImplementation(project(":tck-containers"))
    // The ops API's reading of the imported parked entries (roadmap 8b.4): ParkedOps over a subscription runtime.
    integrationTestImplementation(project(":runtime"))
    // The 9.x example's fixtures and its V10 port, shared with the unit tests.
    integrationTestImplementation(sourceSets.test.get().output)
}

// Every declaration here is internal (finding R5.C-6): the CLI's arguments and configuration are the contract, not a
// JVM signature. The integration tests share the unit tests' fixtures, so they see the unit tests' internals too.
kotlin {
    target.compilations.named("integrationTest") {
        associateWith(target.compilations.getByName("test"))
    }
}

// The tags backfill's cost is measured only on demand: ./gradlew :migrate-data:integrationTest -Dwow.spike.tags=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.tags", providers.systemProperty("wow.spike.tags").orElse("false").get())
}

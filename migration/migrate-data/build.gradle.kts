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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(testFixtures(project(":manifest")))
    integrationTestImplementation(project(":tck-containers"))
    // The 9.x example's fixtures and its V10 port, shared with the unit tests.
    integrationTestImplementation(sourceSets.test.get().output)
}

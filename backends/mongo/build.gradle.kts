description = "Wow MongoDB: log, snapshots, query, checkpoints, leases, failure ledger and reservations on MongoDB"

dependencies {
    api(project(":spi"))
    // The snapshot query backend (decision D131).
    api(project(":query"))
    // The log and the stores run on the sync driver and virtual threads, like wow-postgres (D27).
    api("org.mongodb:mongodb-driver-sync")
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(testFixtures(project(":query")))
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation(testFixtures(project(":query")))
}

// Spike H1 (MongoDB) measures throughput only on demand: ./gradlew :mongo:integrationTest -Dwow.spike.h1=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.h1", providers.systemProperty("wow.spike.h1").orElse("false").get())
}

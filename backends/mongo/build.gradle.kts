description = "Wow MongoDB: log, snapshots, checkpoints, leases, failure ledger and reservations on MongoDB"

dependencies {
    api(project(":spi"))
    // The log and the stores run on the sync driver and virtual threads, like wow-postgres (D27).
    api("org.mongodb:mongodb-driver-sync")
    integrationTestImplementation(testFixtures(project(":spi")))
    integrationTestImplementation("org.testcontainers:testcontainers-mongodb")
}

// Spike H1 (MongoDB) measures throughput only on demand: ./gradlew :mongo:integrationTest -Dwow.spike.h1=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.h1", providers.systemProperty("wow.spike.h1").orElse("false").get())
}

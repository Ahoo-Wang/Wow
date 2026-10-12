description = "Wow PostgreSQL: log, snapshots, query, checkpoints, leases, failure ledger and reservations on PostgreSQL"

dependencies {
    api(project(":spi"))
    api("org.postgresql:postgresql")
    implementation("tools.jackson.core:jackson-databind")
    integrationTestImplementation(testFixtures(project(":spi")))
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("com.zaxxer:HikariCP")
}

// Spike H1 measures throughput only on demand: ./gradlew :postgres:integrationTest -Dwow.spike.h1=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.h1", providers.systemProperty("wow.spike.h1").orElse("false").get())
}

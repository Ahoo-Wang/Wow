description = "Wow PostgreSQL: log, snapshots, query, checkpoints, leases, failure ledger and reservations on PostgreSQL"

dependencies {
    api(project(":spi"))
    // The snapshot query backend (decision D131).
    api(project(":query"))
    api("org.postgresql:postgresql")
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(project(":query-tck"))
    integrationTestImplementation(project(":tck"))
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation(project(":query-tck"))
    integrationTestImplementation("com.zaxxer:HikariCP")
}

// Spike H1 measures throughput only on demand: ./gradlew :postgres:integrationTest -Dwow.spike.h1=true
tasks.named<Test>("integrationTest") {
    systemProperty("wow.spike.h1", providers.systemProperty("wow.spike.h1").orElse("false").get())
}

description = "V10 spike H6: partition leases, membership and rebalancing on PostgreSQL and MongoDB"

dependencies {
    integrationTestImplementation(project(":wow-runtime"))
    integrationTestImplementation(project(":wow-postgres"))
    integrationTestImplementation(project(":wow-mongo"))
    integrationTestImplementation("tools.jackson.module:jackson-module-kotlin")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("org.testcontainers:testcontainers-mongodb")
    integrationTestImplementation("com.zaxxer:HikariCP")
}

// Spike H6 runs only on demand: ./gradlew :wow-lease-spike:integrationTest -Dwow.spike.h6=true
// (-Dwow.spike.h6.quick=true runs one short repetition per series, which only checks the harness;
// -Dwow.spike.h6.repetitions=<n> and -Dwow.spike.h6.only=<backend> narrow a run).
tasks.named<Test>("integrationTest") {
    listOf("wow.spike.h6", "wow.spike.h6.quick", "wow.spike.h6.repetitions", "wow.spike.h6.only").forEach {
        systemProperty(it, providers.systemProperty(it).orElse("").get())
    }
    maxHeapSize = "4g"
    // The repository's test logging is DEBUG (config/logback.xml); the MongoDB driver then formats every command,
    // which costs more than what the spike measures. Its own configuration logs warnings only.
    jvmArgs = listOf("-Dlogback.configurationFile=${projectDir}/src/integrationTest/resources/logback-test.xml")
}

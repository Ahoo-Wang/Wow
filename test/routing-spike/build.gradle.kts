description = "V10 spike H2: affinity routing (Partitioned) against Local and ingress hashing, on PostgreSQL"

dependencies {
    integrationTestImplementation(project(":runtime"))
    integrationTestImplementation(project(":postgres"))
    integrationTestImplementation("tools.jackson.module:jackson-module-kotlin")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("com.zaxxer:HikariCP")
}

// Spike H2 runs only on demand: ./gradlew :routing-spike:integrationTest -Dwow.spike.h2=true
// (add -Dwow.spike.h2.quick=true for a shortened matrix that only checks the harness).
tasks.named<Test>("integrationTest") {
    listOf("wow.spike.h2", "wow.spike.h2.quick").forEach {
        systemProperty(it, providers.systemProperty(it).orElse("false").get())
    }
    maxHeapSize = "4g"
}

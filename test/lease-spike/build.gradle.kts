description = "Wow spike H6: partition leases, membership and rebalancing on PostgreSQL and MongoDB"

dependencies {
    integrationTestImplementation(project(":runtime"))
    integrationTestImplementation(testFixtures(project(":runtime")))
    integrationTestImplementation(project(":postgres"))
    integrationTestImplementation(project(":mongo"))
    integrationTestImplementation("tools.jackson.module:jackson-module-kotlin")
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation("com.zaxxer:HikariCP")
}

// Spike H6 runs only on demand: ./gradlew :lease-spike:integrationTest -Dwow-test.spike.h6=true
// (-Dwow-test.spike.h6.quick=true runs one short repetition per series, which only checks the harness;
// -Dwow-test.spike.h6.repetitions=<n> and -Dwow-test.spike.h6.only=<backend> narrow a run).
tasks.named<Test>("integrationTest") {
    listOf("wow-test.spike.h6", "wow-test.spike.h6.quick", "wow-test.spike.h6.repetitions", "wow-test.spike.h6.only").forEach {
        systemProperty(it, providers.systemProperty(it).orElse("").get())
    }
    maxHeapSize = "4g"
    // The repository's test logging is DEBUG (config/logback.xml); the MongoDB driver then formats every command,
    // which costs more than what the spike measures. Its own configuration logs warnings only.
    jvmArgs = listOf("-Dlogback.configurationFile=src/integrationTest/resources/logback-test.xml")
}

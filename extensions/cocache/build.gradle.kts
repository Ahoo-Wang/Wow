description = "Wow CoCache: another context's aggregate states shared through CoCache, kept by the context " +
    "that owns them and read as their generated views (PublishedStateCache)"

dependencies {
    api(project(":cocache-api"))
    // The sink subscriptions that write the states, the node's typed queries that read a miss, Wow's JSON.
    api(project(":runtime"))
    // The views the peer contracts on the class path name.
    implementation(project(":manifest"))
    api("me.ahoo.cocache:cocache-core")
    api("me.ahoo.cocache:cocache-coroutines")
    // The configuration the host imports (AutoConfiguration.imports) is plain Spring: only the host imports Spring
    // Boot (decision D118's module rules). CoCache's starter, when the application has it, gives the runtime and
    // applies `cocache.caches.<name>.*` to the caches this module creates.
    compileOnly("org.springframework:spring-context")
    compileOnly("me.ahoo.cocache:cocache-spring-boot-starter")
    testImplementation("org.springframework:spring-context")
    testImplementation("me.ahoo.cocache:cocache-spring-boot-starter")
    // ApplicationContextRunner, and the import selector that reads this module's auto-configuration metadata.
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-test")
    // The generator of this module's configuration metadata, and the host's check of the keys against it (D302).
    testImplementation(testFixtures(project(":spring-boot")))
    // The owner's gateway in a test: the real query engine over snapshots in memory.
    testImplementation(project(":test-query"))
    integrationTestImplementation("me.ahoo.cocache:cocache-redis")
    integrationTestImplementation("org.testcontainers:testcontainers")
}

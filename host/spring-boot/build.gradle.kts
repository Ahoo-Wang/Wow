description = "Wow Spring Boot: auto-configuration by classpath; wiring only"

dependencies {
    api(project(":runtime"))
    api(project(":http"))
    // The starter, as every Spring Boot starter has: auto-configuration, logging and SnakeYAML, so an application that
    // depends on the host alone reads its `application.yaml` (eShop DX gap G2).
    api("org.springframework.boot:spring-boot-starter")
    // The public routes are served by the application's WebFlux server (decision D30), the internal port by a Reactor
    // Netty server of the host's own (D89).
    api("org.springframework.boot:spring-boot-webflux")
    implementation("org.springframework.boot:spring-boot-reactor-netty")
    implementation("io.projectreactor.netty:reactor-netty-http")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.github.oshai:kotlin-logging-jvm")
    // The backend follows what the application puts on the classpath (`wow.backend`, D110): the host wires either
    // and brings neither.
    compileOnly(project(":postgres"))
    compileOnly(project(":mongo"))
    // The Kafka transport and the ClickHouse Sink are wired when the application has them; the query plane comes
    // with wow-http.
    compileOnly(project(":kafka"))
    compileOnly(project(":clickhouse"))
    // Activated only when the application has it: a Bean Validation provider (D106). CosId's ids come from wow-cosid,
    // which wires itself (D171).
    compileOnly("jakarta.validation:jakarta.validation-api")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-jackson")
    testImplementation("org.springframework:spring-test")
    testImplementation(project(":postgres"))
    testImplementation(project(":mongo"))
    // The in-memory reference query backend, for the snapshot queries of a node on the memory backend.
    testImplementation(testFixtures(project(":query")))
    testImplementation(project(":kafka"))
    testImplementation(project(":clickhouse"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("org.testcontainers:testcontainers-mongodb")
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
    integrationTestImplementation("com.zaxxer:HikariCP")
}

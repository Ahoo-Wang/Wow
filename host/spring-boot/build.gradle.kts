description = "Wow Spring Boot: auto-configuration by classpath; wiring only"

dependencies {
    api(project(":runtime"))
    api(project(":http"))
    // The OpenAPI document of the public routes, and the security schemes an application declares for it (D233).
    api(project(":openapi"))
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
    // The backend follows what the application puts on the classpath (`wow.backend`, D110): each backend's store
    // provider is found by ServiceLoader (review R3.3-1), so the host neither brings nor compiles against them.
    // The Kafka transport and the ClickHouse Sink are wired when the application has them; the query plane comes
    // with wow-http.
    compileOnly(project(":kafka"))
    compileOnly(project(":clickhouse"))
    // Tracing and metrics are wired when the application has wow-opentelemetry (decision D174).
    compileOnly(project(":opentelemetry"))
    // Activated only when the application has it: a Bean Validation provider (D106). CosId's ids come from wow-cosid,
    // which wires itself (D171).
    compileOnly("jakarta.validation:jakarta.validation-api")
    // The springdoc bridge (decision D233): the Wow routes join springdoc's documents when the application has it.
    compileOnly("org.springdoc:springdoc-openapi-starter-common")
    compileOnly(libs.swagger.core)
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-jackson")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-api")
    testImplementation(libs.json.schema.validator)
    testImplementation(testFixtures(project(":manifest")))
    testImplementation(project(":postgres"))
    testImplementation(project(":mongo"))
    testImplementation(project(":kafka"))
    testImplementation(project(":clickhouse"))
    testImplementation(project(":opentelemetry"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
    integrationTestImplementation("com.zaxxer:HikariCP")
}

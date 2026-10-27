// The test fixtures: the generator of the configuration metadata, which the platform services' tests use too.
plugins {
    `java-test-fixtures`
}

description = "Wow Spring Boot: auto-configuration by classpath; wiring only"

dependencies {
    // The Spring Boot BOM goes with the host alone (D287): an application on wow-spring-boot is a Spring Boot
    // application and takes its versions; a module that depends on another Wow module does not.
    api(platform(libs.spring.boot.dependencies))
    api(project(":runtime"))
    api(project(":http"))
    // The typed clients of the peers (decision D257) and the sagas' synchronous calls (decision D193).
    api(project(":client"))
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
    // A topic transport and a Sink are found the same way, by their providers (finding R5.B-5), so the host compiles
    // against neither the Kafka transport nor the ClickHouse Sink; the ClickHouse dataset backend is wired when the
    // application has it. The query plane comes with wow-http.
    compileOnly(project(":clickhouse"))
    // The application's DataSource and MongoClient from its properties alone (decisions D310, D318), when it has
    // HikariCP or wow-mongo and no Spring Boot JDBC or MongoDB of its own.
    compileOnly("com.zaxxer:HikariCP")
    compileOnly("org.mongodb:mongodb-driver-sync")
    // CoSec is wired when the application has wow-cosec and CoSec's WebFlux module, as Kafka is (review R5.C-12).
    compileOnly(project(":cosec"))
    compileOnly(libs.cosec.webflux)
    // Tracing and metrics are wired when the application has wow-observability (decision D174).
    compileOnly(project(":observability"))
    // Activated only when the application has it: a Bean Validation provider (D106). CosId's ids come from wow-cosid,
    // which wires itself (D171).
    compileOnly("jakarta.validation:jakarta.validation-api")
    // The node's health in Actuator (review R3.5-3): its contributors and probe groups when the application has
    // Spring Boot Actuator.
    compileOnly("org.springframework.boot:spring-boot-health")
    // The springdoc bridge (decision D233): the Wow routes join springdoc's documents when the application has it.
    compileOnly("org.springdoc:springdoc-openapi-starter-common")
    compileOnly(libs.swagger.core)
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("org.springframework.boot:spring-boot-health")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-jackson")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-api")
    testImplementation(libs.json.schema.validator)
    testImplementation(testFixtures(project(":manifest")))
    // The host's DataSource (decision D310).
    testImplementation("com.zaxxer:HikariCP")
    testImplementation(project(":postgres"))
    testImplementation(project(":mongo"))
    testImplementation(project(":kafka"))
    testImplementation(project(":cosec"))
    testImplementation(libs.cosec.webflux)
    testImplementation(project(":clickhouse"))
    testImplementation(project(":observability"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
    // The store combinations with Elasticsearch (DG-6): snapshots and events search beside a log elsewhere.
    integrationTestImplementation(project(":elasticsearch"))
    integrationTestImplementation("com.zaxxer:HikariCP")
}

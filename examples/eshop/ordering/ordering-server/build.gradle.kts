description = "Wow eShop: the ordering service on the Spring Boot host"

// An application: `./gradlew :eshop-ordering-server:run --args='--spring.profiles.active=kafka'` starts it on the
// memory backend with Kafka on localhost (examples/eshop/README.md). Never published (decision D159): the root build
// keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt")
}

// The inventory's contract comes in the jar of :eshop-inventory-api, which the domain depends on (decision D234): the
// order-process saga sends the inventory's commands, and the stock-confirmation saga reads its stock stream.
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("examples/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-ordering-domain"))
    implementation(project(":spring-boot"))
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // Traces and metrics on the node's seams (decision D174), through the OpenTelemetry Java agent when the
    // deployment attaches one (deploy/eshop-*: the OpenTelemetry Operator's annotation), a no-op otherwise.
    implementation(project(":observability"))
    // The `kafka` profile: the basket's orders arrive on wow.eshop-ordering.commands, the inventory's commands and the
    // order stream go out, and the inventory's receipts and stock stream come in.
    implementation(project(":kafka"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation(project(":mongo"))
    // The `cosec` profile, behind the CoSec gateway (decisions D295, D296): CoSec's inject mode reads the principal
    // from the token the gateway verified, and wow-cosec's resolver makes it the caller (decision D301).
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    // The `clickhouse` profile: the order stream is written to ClickHouse by the Sink, and the sales dataset of
    // META-INF/wow/dataset/eshop-ordering.sales.json is queried there (work package E.6).
    implementation(project(":clickhouse"))
    testImplementation(project(":test-spring-boot"))
    // The integration tests run the service in their JVM on PostgreSQL and ClickHouse in containers, with the test's
    // command transport in memory (InMemoryOrderingTopics): the snapshot queries and the sales dataset (decision D204).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
}

// `./gradlew :eshop-ordering-server:run` is a local run: the `dev` profile joins whatever profiles `--args` activates,
// so the callers are named in the Wow-* identity headers, which the host reads in a development profile alone.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.include", "dev")
}

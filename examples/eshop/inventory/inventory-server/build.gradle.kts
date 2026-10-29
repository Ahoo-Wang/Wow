description = "Wow eShop: the inventory service on the Spring Boot host"

// An application: `./gradlew :eshop-inventory-server:run` starts it on the memory backend (examples/eshop/README.md).
// Never published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
    id("me.ahoo.wow.service")
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt")
}

// The service module (decision D363 ①): `me.ahoo.wow.service` merges the service's Manifest from its class path,
// writes its OpenAPI document and gates `check` on manifest/baseline.json (decision D76).
// The ordering service knows this context by the contract in the jar of :eshop-inventory-api (decision D234): its saga
// sends this context's commands, and reads its stock stream.
dependencies {
    implementation(project(":eshop-inventory-domain"))
    implementation("me.ahoo.wow:wow-spring-boot")
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
    implementation("me.ahoo.wow:wow-observability")
    // The `kafka` profile: the commands the ordering context sends arrive on wow.eshop-inventory.commands, the receipts
    // of those this context rejects go back to it, and the stock stream is published.
    implementation("me.ahoo.wow:wow-kafka")
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation("me.ahoo.wow:wow-postgres")
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation("me.ahoo.wow:wow-mongo")
    // The `cosec` profile, behind the CoSec gateway: CoSec's gateway mode reads the principal the gateway verified
    // from its CoSec-Identity header, and wow-cosec's resolver makes it the caller.
    implementation("me.ahoo.wow:wow-cosec")
    implementation(libs.cosec.spring.boot)
    implementation(libs.cosec.webflux)
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
}

// `./gradlew :eshop-inventory-server:run` is a local run: the `dev` profile joins whatever profiles `--args` activates,
// so the callers are named in the Wow-* identity headers, which the host reads in a development profile alone.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.include", "dev")
}

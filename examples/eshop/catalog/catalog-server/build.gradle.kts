description = "Wow eShop: the catalog service on the Spring Boot host"

// An application: `./gradlew :eshop-catalog-server:run` starts it on the memory backend, and
// `./gradlew :eshop-catalog-server:run --args='--spring.profiles.active=postgres'` on PostgreSQL
// (examples/eshop/README.md). Never published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
    id("me.ahoo.wow.service")
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.catalog.server.CatalogServerKt")
}

// The service module (decision D363 ①): `me.ahoo.wow.service` merges the service's Manifest from its class path,
// writes its OpenAPI document and gates `check` on manifest/baseline.json (decision D76).
// region dependencies
dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation("me.ahoo.wow:wow-spring-boot")
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // A Bean Validation provider: the host checks each command's constraints with it.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness: the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // Traces and metrics on the node's seams, through the OpenTelemetry Java agent when the
    // deployment attaches one (deploy/eshop-*: the OpenTelemetry Operator's annotation), a no-op otherwise.
    implementation("me.ahoo.wow:wow-observability")
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation("me.ahoo.wow:wow-postgres")
    // The pool the host makes of `spring.datasource.*` in the postgres profile.
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set.
    implementation("me.ahoo.wow:wow-mongo")
    // The `search` profile, MongoDB with the products' search on Elasticsearch, over the client Spring Boot's
    // Elasticsearch support makes of `spring.elasticsearch.uris`.
    implementation("me.ahoo.wow:wow-elasticsearch")
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    // The `cosec` profile, behind the CoSec gateway: CoSec's inject mode reads the principal
    // from the token the gateway verified, and wow-cosec's resolver makes it the caller.
    implementation("me.ahoo.wow:wow-cosec")
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
    // The springdoc bridge's acceptance: the document checked against the OpenAPI 3.1 schema.
    testImplementation(libs.json.schema.validator)
}
// endregion

// `./gradlew :eshop-catalog-server:run` is a local run: the `dev` profile joins whatever profiles `--args` activates,
// so the callers are named in the Wow-* identity headers, which the host reads in a development profile alone.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.include", "dev")
}

description = "Wow eShop: the catalog service on the Spring Boot host"

// An application: `./gradlew :eshop-catalog-server:run` starts it on the memory backend, and
// `./gradlew :eshop-catalog-server:run --args='--spring.profiles.active=postgres'` on PostgreSQL
// (example/eshop/README.md). Never published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.catalog.server.CatalogServerKt")
}

// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
// The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server applies the repository's
// script in its place (DX gap G6).
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

// region dependencies
dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host checks each command's constraints with it.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness: the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // Traces and metrics on the node's seams, through the OpenTelemetry Java agent when the
    // deployment attaches one (deploy/eshop-*: the OpenTelemetry Operator's annotation), a no-op otherwise.
    implementation(project(":observability"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation(project(":postgres"))
    // The pool the host makes of `spring.datasource.*` in the postgres profile.
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set.
    implementation(project(":mongo"))
    // The `search` profile, MongoDB with the products' search on Elasticsearch, over the client Spring Boot's
    // Elasticsearch support makes of `spring.elasticsearch.uris`.
    implementation(project(":elasticsearch"))
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    // The `cosec` profile, behind the CoSec gateway: CoSec's inject mode reads the principal
    // from the token the gateway verified, and wow-cosec's resolver makes it the caller.
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    testImplementation("org.springframework:spring-test")
    // The springdoc bridge's acceptance: the catalog's routes in springdoc's documents and Swagger UI,
    // the document checked against the OpenAPI 3.1 schema.
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    testImplementation(libs.json.schema.validator)
}
// endregion

// `./gradlew :eshop-catalog-server:run` is a local run: the `dev` profile joins whatever profiles `--args` activates,
// so the callers are named in the Wow-* identity headers, which the host reads in a development profile alone.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.include", "dev")
}

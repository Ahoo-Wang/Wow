description = "Wow view store: the view store service on the Spring Boot host (roadmap 7.2a)"

// The 9.x standalone view store server (view-store/wow-view-store-server) on 10.x: `./gradlew :view-store-server:run`
// starts it on the memory backend, `--args='--spring.profiles.active=postgres'` on PostgreSQL. Wow modules are not
// published before M8 (decision D88).
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.viewstore.server.ViewStoreServerKt")
}

// The Manifest compatibility gate (decision D76, roadmap 5a.4): `check` compares the service's Manifest with
// manifest/baseline.json. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server
// applies the repository's script in its place, as the eShop's servers do (DX gap G6).
apply(from = rootProject.file("examples/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":view-store-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation(project(":mongo"))
    // The `postgres` profile: the host keeps the views on PostgreSQL and serves their snapshot queries there.
    implementation(project(":postgres"))
    // The `cosec` profile (roadmap P.15, decision D229 G-3): CoSec verifies each request's token, and the edge reads
    // the user and the application CoSec authorized from CoSec's security context (decisions D232, D236); CoSec
    // itself is the application's to bring.
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    // Traces and metrics on the node's seams (decision D174), through the OpenTelemetry Java agent when the
    // deployment attaches one, a no-op otherwise.
    implementation(project(":observability"))
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation(project(":test-spring-boot"))
    // ViewStoreDevelopmentCallersTest resolves callers of a MockServerRequest.
    testImplementation("org.springframework:spring-test")
    // The generator of the configuration metadata of the service's own keys (design rule K8).
    testImplementation(testFixtures(project(":spring-boot")))
    // The same contract on PostgreSQL in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
}

// `./gradlew :view-store-server:run` is a local run, in the `dev` profile: the callers are named in the Wow-* identity
// headers, which the host reads in development alone (review A3-4); a deployment uses CoSec instead.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.active", "dev")
    // The internal port on this machine alone, a development node; a deployment puts it behind a NetworkPolicy (D300).
    systemProperty("wow.internal.host", "127.0.0.1")
}

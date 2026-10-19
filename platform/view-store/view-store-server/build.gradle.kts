description = "Wow V10 view store: the view store service on the Spring Boot host (roadmap 7.2a)"

// The 9.x standalone view store server (view-store/wow-view-store-server) on V10: `./gradlew :view-store-server:run`
// starts it on the memory backend, `--args='--spring.profiles.active=postgres'` on PostgreSQL. V10 modules are not
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
extra["wowService"] = "view-store-service"
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":view-store-domain"))
    implementation(project(":spring-boot"))
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
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
    implementation(project(":opentelemetry"))
    implementation("com.zaxxer:HikariCP")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.springframework:spring-test")
    // The same contract on PostgreSQL in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
}

// `./gradlew :view-store-server:run` is a local run: the view engine names its caller in request headers, which the server trusts
// only with wow.platform.trust-identity-headers (review A3-4); a deployment uses CoSec instead.
tasks.named<JavaExec>("run") {
    systemProperty("wow.platform.trust-identity-headers", "true")
    // The internal port on this machine alone, which runs without a cluster token (decision D226); a deployment sets
    // wow.internal.token (WOW_INTERNAL_TOKEN).
    systemProperty("wow.internal.host", "127.0.0.1")
}

description = "Wow V10 dataset registry: the dataset registry service on the Spring Boot host (roadmap 9.3b)"

// The dataset registry service (decisions D238, D241), in place of the 9.x dataset-registry/ starter:
// `./gradlew :dataset-registry-server:run` starts it on the memory backend, its catalog on the ClickHouse
// `wow.clickhouse.endpoint` names; `--args='--spring.profiles.active=postgres'` keeps the sources on PostgreSQL.
// V10 modules are not published before M8 (decision D88).
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.datasetregistry.server.DatasetRegistryServerKt")
}

// The Manifest compatibility gate (decision D76, roadmap 5a.4): `check` compares the service's Manifest with
// manifest/baseline.json. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server
// applies the repository's script in its place, as the other platform servers do (DX gap G6).
extra["wowService"] = "dataset-registry-service"
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":dataset-registry-domain"))
    implementation(project(":spring-boot"))
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3).
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // The warehouse the catalog reads (decision D241): the host builds the ClickHouse client of
    // `wow.clickhouse.endpoint`, which the catalog queries `system.tables`, `system.columns` and `wow_catalog` with.
    implementation(project(":clickhouse"))
    // The `postgres` profile: the sources' log and snapshots on PostgreSQL, their snapshot queries served there.
    implementation(project(":postgres"))
    // The `cosec` profile (roadmap P.15): CoSec verifies each request's token, and the edge reads the caller from it.
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    // Traces and metrics on the node's seams (decision D174).
    implementation(project(":opentelemetry"))
    implementation("com.zaxxer:HikariCP")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.springframework:spring-test")
    // The catalog on ClickHouse in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
}

// `./gradlew :dataset-registry-server:run` is a local run: the callers are named in request headers, which the server
// trusts only with wow.platform.trust-identity-headers (review A3-4); a deployment uses CoSec instead.
tasks.named<JavaExec>("run") {
    systemProperty("wow.platform.trust-identity-headers", "true")
    systemProperty("wow.internal.host", "127.0.0.1")
}

description = "Wow V10 ops: the ops service on the Spring Boot host (roadmap 7.1a)"

// The aggregation and operation surface of every service's failure ledger (decisions D14, D141), in place of the 9.x
// compensation server: `./gradlew :ops-server:run --args='--spring.profiles.active=kafka'` starts it on the memory
// backend with Kafka on localhost, `postgres,kafka` on PostgreSQL. V10 modules are not published before M8 (D88).
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.ops.server.OpsServerKt")
}

// The Manifest compatibility gate (decision D76, roadmap 5a.4): `check` compares the service's Manifest with
// manifest/baseline.json. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server
// applies the repository's script in its place, as the eShop's servers do (DX gap G6).
extra["wowService"] = "ops-service"
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

// The console (roadmap 7.1b), served by the server as static resources from the distribution's ui/ folder
// (`spring.web.resources.static-locations`): `pnpm --filter wow-ops-console... build` first, or the folder is empty.
distributions {
    main {
        contents {
            from(rootProject.file("platform/ops/console/dist")) {
                into("ui")
            }
        }
    }
}

dependencies {
    implementation(project(":ops-domain"))
    implementation(project(":spring-boot"))
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // The `kafka` profile: the services' ledger records come in on wow.ops.commands, and an operator's action goes out
    // to the owning context's command topic as the ledger's system command.
    implementation(project(":kafka"))
    // The `postgres` profile: the entries' log and snapshots on PostgreSQL, their snapshot queries served there.
    implementation(project(":postgres"))
    // The `cosec` profile (roadmap P.15, decision D229 G-3): CoSec verifies each request's token, and the edge reads
    // the operator from CoSec's security context; CoSec itself is the application's to bring.
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
    // The same flow on PostgreSQL in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation(project(":tck-containers"))
}

// `./gradlew :ops-server:run` is a local run: the console names its caller in request headers, which the server trusts
// only with wow.platform.trust-identity-headers (review A3-4); a deployment uses CoSec instead.
tasks.named<JavaExec>("run") {
    systemProperty("wow.platform.trust-identity-headers", "true")
    // The internal port on this machine alone, which runs without a cluster token (decision D226); a deployment sets
    // wow.internal.token (WOW_INTERNAL_TOKEN).
    systemProperty("wow.internal.host", "127.0.0.1")
}

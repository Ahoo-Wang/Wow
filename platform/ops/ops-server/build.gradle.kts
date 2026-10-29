description = "Wow ops: the ops service on the Spring Boot host (roadmap 7.1a)"

// The aggregation and operation surface of every service's failure ledger (decisions D14, D141), in place of the 9.x
// compensation server: `./gradlew :ops-server:run --args='--spring.profiles.active=kafka'` starts it on the memory
// backend with Kafka on localhost, `postgres,kafka` on PostgreSQL. Wow modules are not published before M8 (D88).
plugins {
    application
    id("me.ahoo.wow.service")
}

application {
    mainClass.set("me.ahoo.wow.ops.server.OpsServerKt")
}

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

// The service module (decision D363 ①): `me.ahoo.wow.service` merges the service's Manifest from its class path,
// writes its OpenAPI document and gates `check` on manifest/baseline.json (decision D76).
dependencies {
    implementation(project(":ops-domain"))
    implementation("me.ahoo.wow:wow-spring-boot")
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // The `kafka` profile: the services' ledger records come in on wow.ops.commands, and an operator's action goes out
    // to the owning context's command topic as the ledger's system command.
    implementation("me.ahoo.wow:wow-kafka")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation("me.ahoo.wow:wow-mongo")
    // The `postgres` profile: the entries' log and snapshots on PostgreSQL, their snapshot queries served there.
    implementation("me.ahoo.wow:wow-postgres")
    // The `cosec` profile (roadmap P.15, decision D229 G-3): CoSec verifies each request's token, and the edge reads
    // the operator from CoSec's security context; CoSec itself is the application's to bring.
    implementation("me.ahoo.wow:wow-cosec")
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    // Traces and metrics on the node's seams (decision D174), through the OpenTelemetry Java agent when the
    // deployment attaches one, a no-op otherwise.
    implementation("me.ahoo.wow:wow-observability")
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
    // The same flow on PostgreSQL in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("me.ahoo.wow:wow-tck-containers")
}

// `./gradlew :ops-server:run` is a local run, in the `dev` profile: the callers are named in the Wow-* identity
// headers, which the host reads in development alone (review A3-4); a deployment uses CoSec instead.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.active", "dev")
    // The internal port on this machine alone, a development node; a deployment puts it behind a NetworkPolicy (D300).
    systemProperty("wow.internal.host", "127.0.0.1")
}

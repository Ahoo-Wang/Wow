description = "Wow analytics catalog: the analytics catalog service on the Spring Boot host (roadmap 9.3b)"

// The analytics catalog service (decisions D238, D241), in place of the 9.x analytics-catalog/ starter:
// `./gradlew :analytics-catalog-server:run` starts it on the memory backend, its catalog on the ClickHouse
// `wow.clickhouse.endpoint` names; `--args='--spring.profiles.active=postgres'` keeps the sources on PostgreSQL.
// Wow modules are not published before M8 (decision D88).
plugins {
    application
    id("me.ahoo.wow.service")
}

// The context's service routes (decision D359): KSP reads the `@ServiceRoute` functions of the server, lists them in
// the module's Manifest fragment and binds them in its model provider, as the domain module's aggregates are.
wow {
    context = "analytics-catalog"
}

application {
    mainClass.set("me.ahoo.wow.analytics.catalog.server.AnalyticsCatalogServerKt")
}

// The console (roadmap 7.2c), served by the server as static resources from the distribution's ui/ folder
// (`spring.web.resources.static-locations`): `pnpm --filter wow-analytics-catalog-console... build` first, or the
// folder is empty.
distributions {
    main {
        contents {
            from(rootProject.file("platform/analytics/catalog/console/dist")) {
                into("ui")
            }
        }
    }
}

// The service module (decision D363 ①): `me.ahoo.wow.service` merges the service's Manifest from its class path,
// writes its OpenAPI document and gates `check` on manifest/baseline.json (decision D76).
dependencies {
    implementation(project(":analytics-catalog-domain"))
    implementation("me.ahoo.wow:wow-spring-boot")
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger UI over the service's routes (/swagger-ui.html): the host merges Wow's document into springdoc's.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3).
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // The warehouse the catalog reads (decision D241): the host builds the ClickHouse client of
    // `wow.clickhouse.endpoint`, which the catalog queries `system.tables`, `system.columns` and `wow_catalog` with.
    implementation("me.ahoo.wow:wow-clickhouse")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation("me.ahoo.wow:wow-mongo")
    // The `postgres` profile: the sources' log and snapshots on PostgreSQL, their snapshot queries served there.
    implementation("me.ahoo.wow:wow-postgres")
    // The `cosec` profile (roadmap P.15): CoSec reads the identity the CoSec gateway forwards, and the edge the caller.
    implementation("me.ahoo.wow:wow-cosec")
    implementation(libs.cosec.spring.boot)
    implementation(libs.cosec.webflux)
    // Traces and metrics on the node's seams (decision D174).
    implementation("me.ahoo.wow:wow-observability")
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
    // The generator of the configuration metadata of the service's own keys (design rule K8).
    testImplementation(testFixtures(project(":spring-boot")))
    // The catalog on ClickHouse in a container (decision D196).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("me.ahoo.wow:wow-tck-containers")
}

// `./gradlew :analytics-catalog-server:run` is a local run, in the `dev` profile: the callers are named in the Wow-* identity
// headers, which the host reads in development alone (review A3-4); a deployment uses CoSec instead.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.active", "dev")
    systemProperty("wow.internal.host", "127.0.0.1")
}

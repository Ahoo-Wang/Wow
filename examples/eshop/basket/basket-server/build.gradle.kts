description = "Wow eShop: the basket service on the Spring Boot host"

// An application: `./gradlew :eshop-basket-server:run --args='--spring.profiles.active=kafka'` starts it on the memory
// backend with Kafka on localhost (examples/eshop/README.md). Never published (decision D159): the root build keeps the
// eShop out of publishing.
plugins {
    application
    id("me.ahoo.wow.service")
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.basket.server.BasketServerKt")
}

// Its Kubernetes manifests (generateWowManifests, in assemble): the wow-service chart rendered with the deployment's
// values for the namespace it runs in, a Kustomize base of plain YAML in build/wow/k8s that an overlay applies over
// with `kubectl apply -k`. BasketManifestsTest holds the objects it renders with the defaults. An environment whose
// namespace or derived values differ is another render, never an overlay that moves the base: the namespace, and values
// files after the deployment's (paths from the repository's root, comma-separated), are the build's properties, as
// .github/ga-e2e/cluster.sh renders it for its cluster:
//
//   ./gradlew :eshop-basket-server:generateWowManifests -Peshop.kubernetes.namespace=default \
//     -Peshop.kubernetes.values=.github/ga-e2e/services/values.yaml
// region kubernetes
wow {
    kubernetes {
        values.from(rootProject.file("deploy/eshop-basket/values.yaml"))
        values.from(
            providers.gradleProperty("eshop.kubernetes.values").map { paths ->
                paths.split(',').map(String::trim).filter(String::isNotEmpty).map(rootProject::file)
            }.orElse(emptyList()),
        )
        namespace = providers.gradleProperty("eshop.kubernetes.namespace").orElse("eshop")
    }
}
// endregion

tasks.test {
    val manifests = tasks.named("generateWowManifests")
    inputs.files(manifests).withPropertyName("manifests").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("eshop.manifests", layout.buildDirectory.dir("wow/k8s").get().asFile.path)
}

// The service module (decision D363 ①): `me.ahoo.wow.service` merges the service's Manifest from its class path,
// writes its OpenAPI document and gates `check` on manifest/baseline.json (decision D76).
// The ordering's contract comes in the jar of :eshop-ordering-api, which the domain depends on (decision D234): the
// checkout saga calls the ordering context's commands by the type names it gives them.
dependencies {
    implementation(project(":eshop-basket-domain"))
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
    // The `kafka` profile: the basket stream is published (the checkout saga calls the ordering service over HTTP,
    // decision D193).
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
    // Tests refer to the ordering's orders by its generated contract.
    testImplementation(project(":eshop-ordering-api"))
    testImplementation("me.ahoo.wow:wow-test-spring-boot")
}

// `./gradlew :eshop-basket-server:run` is a local run: the `dev` profile joins whatever profiles `--args` activates,
// so the callers are named in the Wow-* identity headers, which the host reads in a development profile alone.
tasks.named<JavaExec>("run") {
    systemProperty("spring.profiles.include", "dev")
}

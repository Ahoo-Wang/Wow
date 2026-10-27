description = "Wow eShop: the payment service on the Spring Boot host"

// An application: `./gradlew :eshop-payment-server:run --args='--spring.profiles.active=kafka'` starts it on the
// memory backend with Kafka on localhost (example/eshop/README.md). Never published (decision D159): the root build
// keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.payment.server.PaymentServerKt")
}

// The ordering's contract comes in the jar of :eshop-ordering-api, which the domain depends on (decision D234): the
// order-payment saga reads the order stream and sends the ordering's commands.
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-payment-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // Traces and metrics on the node's seams (decision D174), through the OpenTelemetry Java agent when the
    // deployment attaches one (deploy/eshop-*: the OpenTelemetry Operator's annotation), a no-op otherwise.
    implementation(project(":observability"))
    // The `kafka` profile: the order stream comes in, the ordering's commands go out, and the receipts of those the
    // ordering rejects come back.
    implementation(project(":kafka"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    // The pool the host makes of `spring.datasource.*` in the postgres profile (decision D310).
    runtimeOnly("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation(project(":mongo"))
    // The `cosec` profile, behind the CoSec gateway (decisions D295, D296): CoSec's inject mode reads the principal
    // from the token the gateway verified, and wow-cosec's resolver makes it the caller (decision D301).
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
    // Tests refer to the ordering's orders by its generated contract.
    testImplementation(project(":eshop-ordering-api"))
}

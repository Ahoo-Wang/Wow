description = "Wow eShop: the inventory service on the Spring Boot host"

// An application: `./gradlew :eshop-inventory-server:run` starts it on the memory backend (example/eshop/README.md).
// Never published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt")
}

// The ordering service knows this context by the contract in the jar of :eshop-inventory-api (decision D234): its saga
// sends this context's commands, and reads its stock stream.
extra["wowService"] = "eshop-inventory-service"
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-inventory-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host checks each command's constraints with it (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator's health endpoint, which the host gives the node's readiness and liveness (review R3.5-3): the
    // deployment's probes read /actuator/health/readiness and /actuator/health/liveness.
    implementation("org.springframework.boot:spring-boot-health")
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    // The `kafka` profile: the commands the ordering context sends arrive on wow.eshop-inventory.commands, the receipts
    // of those this context rejects go back to it, and the stock stream is published.
    implementation(project(":kafka"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    // The `mongo` profile: the host runs on MongoDB when the Mongo URI is set (decision D318).
    implementation(project(":mongo"))
    // The `cosec` profile, behind the CoSec gateway (decisions D295, D296): CoSec's inject mode reads the principal
    // from the token the gateway verified, and wow-cosec's resolver makes it the caller (decision D301).
    implementation(project(":cosec"))
    implementation(libs.cosec.spring.boot.starter)
    implementation(libs.cosec.webflux)
    implementation(libs.cosec.jwt)
}

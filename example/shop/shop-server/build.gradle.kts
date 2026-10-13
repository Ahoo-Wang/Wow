description = "Wow V10 example: the shop served by the Spring Boot host"

// An application: `./gradlew :shop-server:run` starts it on the memory backend, and
// `./gradlew :shop-server:run --args='--spring.profiles.active=postgres'` on PostgreSQL (example/shop/README.md).
// Never published (decision D111): the root build keeps the example out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.shop.server.ShopServerKt")
}

// The service's Manifest for its peers, and the inventory's as its peer (decision D139): the order saga sends the
// inventory's commands by the type names its Manifest gives them.
extra["wowService"] = "shop-service"
apply(from = rootProject.file("example/wow-peers.gradle.kts"))

dependencies {
    implementation(project(":shop-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host then checks the commands' Jakarta constraints (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource (decision D110).
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    // The `kafka` profile: the order stream is published, and the order saga's commands reach the inventory, over
    // Kafka (decisions D58, D137).
    implementation(project(":kafka"))
    "wowPeers"(project(":inventory-server"))
    testImplementation("org.springframework:spring-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // The in-memory reference query backend, behind the snapshot queries of the memory backend's tests (6.2c).
    testImplementation(testFixtures(project(":query")))
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
}

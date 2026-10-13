description = "Wow V10 example: the inventory served by the Spring Boot host"

// An application: `./gradlew :inventory-server:run` starts it on the memory backend, reading and writing Kafka on
// localhost:9092 (example/inventory/README.md). Never published (decision D144): the root build keeps the example out of
// publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.inventory.server.InventoryServerKt")
}

// The service's Manifest for its peers, and the shop's as its peer (decision D139): its saga reads the order stream
// the shop publishes, which the node checks against the shop's Manifest when it starts.
extra["wowService"] = "inventory-service"
apply(from = rootProject.file("example/wow-peers.gradle.kts"))

dependencies {
    implementation(project(":inventory-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host then checks the commands' Jakarta constraints (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Kafka carries everything the inventory exchanges with the shop: the commands sent to it, the receipts of those it
    // rejects, and the order stream it reads (decisions D33, D134–D137).
    implementation(project(":kafka"))
    // The `postgres` profile, as the shop's.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    "wowPeers"(project(":shop-server"))
    testImplementation("org.springframework:spring-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

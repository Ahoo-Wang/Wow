description = "Wow V10 eShop: the inventory service on the Spring Boot host"

// An application: `./gradlew :eshop-inventory-server:run` starts it on the memory backend (example/eshop/README.md).
// Never published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt")
}

// The service's Manifest, which the ordering service takes as its peer: the ordering saga sends this context's
// commands, and reads its stock stream. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it,
// so the server applies the repository's script in its place (DX gap G6).
extra["wowService"] = "eshop-inventory-service"
apply(from = rootProject.file("example/wow-peers.gradle.kts"))

dependencies {
    implementation(project(":eshop-inventory-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the commands the ordering context sends arrive on wow.eshop-inventory.commands, the receipts
    // of those this context rejects go back to it, and the stock stream is published.
    implementation(project(":kafka"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
}

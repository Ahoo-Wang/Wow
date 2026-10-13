description = "Wow V10 eShop: the basket service on the Spring Boot host"

// An application: `./gradlew :eshop-basket-server:run --args='--spring.profiles.active=kafka'` starts it on the memory
// backend with Kafka on localhost (example/eshop/README.md). Never published (decision D159): the root build keeps the
// eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.basket.server.BasketServerKt")
}

// The service's Manifest, and the ordering service's as its peer: the checkout saga sends the ordering context's
// commands by the type names its Manifest gives them. The plugin `me.ahoo.wow` cannot be applied inside the build that
// produces it, so the server applies the repository's script in its place (DX gap G6).
extra["wowService"] = "eshop-basket-service"
apply(from = rootProject.file("example/wow-peers.gradle.kts"))

dependencies {
    implementation(project(":eshop-basket-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the checkout saga's orders reach the ordering context on wow.eshop-ordering.commands, and
    // the basket stream is published.
    implementation(project(":kafka"))
    "wowPeers"(project(":eshop-ordering-server"))
    // The basket sends nothing to the inventory or the payment, but a checkout's Wow-Wait follows the order through
    // them: a wait chain names only contexts the node knows from its peers' Manifests (DX gap G30).
    "wowPeers"(project(":eshop-inventory-server"))
    "wowPeers"(project(":eshop-payment-server"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
}

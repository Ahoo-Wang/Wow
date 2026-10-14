description = "Wow V10 eShop: the ordering service on the Spring Boot host"

// An application: `./gradlew :eshop-ordering-server:run --args='--spring.profiles.active=kafka'` starts it on the
// memory backend with Kafka on localhost (example/eshop/README.md). Never published (decision D159): the root build
// keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt")
}

// The service's Manifest, which the basket service takes as its peer, and the inventory's as this service's peer: the
// order-process saga sends the inventory's commands, and the stock-confirmation saga reads its stock stream. The plugin
// `me.ahoo.wow` cannot be applied inside the build that produces it, so the server applies the repository's script in
// its place (DX gap G6).
extra["wowService"] = "eshop-ordering-service"
apply(from = rootProject.file("example/wow-peers.gradle.kts"))
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-ordering-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the basket's orders arrive on wow.eshop-ordering.commands, the inventory's commands and the
    // order stream go out, and the inventory's receipts and stock stream come in.
    implementation(project(":kafka"))
    "wowPeers"(project(":eshop-inventory-server"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    // The `clickhouse` profile: the order stream is written to ClickHouse by the Sink, and the sales dataset of
    // META-INF/wow/dataset/eshop-ordering.sales.json is queried there (work package E.6).
    implementation(project(":clickhouse"))
    // The integration tests run the service in their JVM on PostgreSQL and ClickHouse in containers, with the test's
    // command transport in memory (InMemoryOrderingTopics): the snapshot queries and the sales dataset (decision D204).
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
}

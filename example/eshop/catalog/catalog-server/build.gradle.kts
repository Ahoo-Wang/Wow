description = "Wow V10 eShop: the catalog service on the Spring Boot host"

// An application: `./gradlew :eshop-catalog-server:run` starts it on the memory backend, and
// `./gradlew :eshop-catalog-server:run --args='--spring.profiles.active=postgres'` on PostgreSQL
// (example/eshop/README.md). Never published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.catalog.server.CatalogServerKt")
}

dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation(project(":spring-boot"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    // The in-memory reference query backend, so the memory backend's tests can query the snapshots (guide: Testing).
    testImplementation(testFixtures(project(":query")))
    testImplementation("org.springframework:spring-test")
}

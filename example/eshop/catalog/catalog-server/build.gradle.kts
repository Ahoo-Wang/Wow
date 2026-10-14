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

// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
// The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server applies the repository's
// script in its place (DX gap G6).
extra["wowService"] = "eshop-catalog-service"
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-catalog-domain"))
    implementation(project(":spring-boot"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource, and serves the
    // snapshot queries there, with the indexes the product's state declares.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    testImplementation("org.springframework:spring-test")
}

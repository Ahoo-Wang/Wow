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

// The ordering's contract comes in the jar of :eshop-ordering-api, which the domain depends on (decision D234): the
// checkout saga calls the ordering context's commands by the type names it gives them.
extra["wowService"] = "eshop-basket-service"
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-basket-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the basket stream is published (the checkout saga calls the ordering service over HTTP,
    // decision D193).
    implementation(project(":kafka"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
}

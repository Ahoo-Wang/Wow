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

dependencies {
    implementation(project(":shop-domain"))
    implementation(project(":spring-boot"))
    // A Bean Validation provider: the host then checks the commands' Jakarta constraints (decision D106).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource (decision D110).
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    // The Kafka Sink publishing the shop's state events (decision D58).
    integrationTestImplementation(project(":kafka"))
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
}

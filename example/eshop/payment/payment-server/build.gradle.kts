description = "Wow eShop: the payment service on the Spring Boot host"

// An application: `./gradlew :eshop-payment-server:run --args='--spring.profiles.active=kafka'` starts it on the
// memory backend with Kafka on localhost (example/eshop/README.md). Never published (decision D159): the root build
// keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.payment.server.PaymentServerKt")
}

// The ordering's contract comes in the jar of :eshop-ordering-api, which the domain depends on (decision D234): the
// order-payment saga reads the order stream and sends the ordering's commands.
extra["wowService"] = "eshop-payment-service"
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-payment-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the order stream comes in, the ordering's commands go out, and the receipts of those the
    // ordering rejects come back.
    implementation(project(":kafka"))
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
    // Tests refer to the ordering's orders by its generated contract.
    testImplementation(project(":eshop-ordering-api"))
}

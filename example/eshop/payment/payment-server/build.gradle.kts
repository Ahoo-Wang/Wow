description = "Wow V10 eShop: the payment service on the Spring Boot host"

// An application: `./gradlew :eshop-payment-server:run --args='--spring.profiles.active=kafka'` starts it on the
// memory backend with Kafka on localhost (example/eshop/README.md). Never published (decision D159): the root build
// keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.payment.server.PaymentServerKt")
}

// The ordering's Manifest as this service's peer: the order-payment saga reads the order stream and sends the
// ordering's commands. The plugin `me.ahoo.wow` cannot be applied inside the build that produces it, so the server
// applies the repository's script in its place (DX gap G6).
extra["wowService"] = "eshop-payment-service"
apply(from = rootProject.file("example/wow-peers.gradle.kts"))
// The Manifest compatibility gate (decision D76): `check` compares the service's Manifest with manifest/baseline.json.
apply(from = rootProject.file("example/eshop/wow-manifest-compatibility.gradle.kts"))

dependencies {
    implementation(project(":eshop-payment-domain"))
    implementation(project(":spring-boot"))
    // The `kafka` profile: the order stream comes in, the ordering's commands go out, and the receipts of those the
    // ordering rejects come back.
    implementation(project(":kafka"))
    // SPIKE D234: no wowPeers; the ordering's contract comes from :eshop-ordering-api's jar, on the runtime class
    // path through :eshop-payment-domain.
    // The `postgres` profile: the host runs on PostgreSQL when the application gives it a DataSource.
    implementation(project(":postgres"))
    implementation("com.zaxxer:HikariCP")
}

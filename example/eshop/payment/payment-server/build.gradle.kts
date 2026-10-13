description = "Wow V10 eShop: the payment service on the Spring Boot host"

// An application: `./gradlew :eshop-payment-server:run` starts it on the memory backend (example/eshop/README.md). Never
// published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.payment.server.PaymentServerKt")
}

dependencies {
    implementation(project(":eshop-payment-domain"))
    implementation(project(":spring-boot"))
}

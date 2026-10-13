description = "Wow V10 eShop: the ordering service on the Spring Boot host"

// An application: `./gradlew :eshop-ordering-server:run` starts it on the memory backend (example/eshop/README.md). Never
// published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt")
}

dependencies {
    implementation(project(":eshop-ordering-domain"))
    implementation(project(":spring-boot"))
}

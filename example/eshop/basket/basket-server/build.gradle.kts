description = "Wow V10 eShop: the basket service on the Spring Boot host"

// An application: `./gradlew :eshop-basket-server:run` starts it on the memory backend (example/eshop/README.md). Never
// published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.basket.server.BasketServerKt")
}

dependencies {
    implementation(project(":eshop-basket-domain"))
    implementation(project(":spring-boot"))
    // application.yaml needs SnakeYAML, which the host does not bring (DX-GAPS.md, G2).
    runtimeOnly("org.yaml:snakeyaml")
}

description = "Wow V10 eShop: the inventory service on the Spring Boot host"

// An application: `./gradlew :eshop-inventory-server:run` starts it on the memory backend (example/eshop/README.md). Never
// published (decision D159): the root build keeps the eShop out of publishing.
plugins {
    application
}

application {
    mainClass.set("me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt")
}

dependencies {
    implementation(project(":eshop-inventory-domain"))
    implementation(project(":spring-boot"))
}

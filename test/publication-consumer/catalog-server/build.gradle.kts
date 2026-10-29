plugins {
    kotlin("jvm")
    id("me.ahoo.wow.service")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.catalog.server.CatalogServerKt")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(project(":catalog-domain"))
    // The host: Spring Boot auto-configuration, the public routes and the internal port.
    implementation("me.ahoo.wow:wow-spring-boot")
    // A backend. Without one, the host runs in memory, on one node.
    implementation("me.ahoo.wow:wow-postgres")
    implementation("com.zaxxer:HikariCP")
    // A Bean Validation provider: the host then checks the commands' Jakarta constraints.
    implementation("org.springframework.boot:spring-boot-starter-validation")
}

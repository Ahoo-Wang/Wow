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
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${property("wow.consumer.springBoot")}"))
    implementation(project(":catalog-domain"))
    implementation("me.ahoo.wow:wow-spring-boot")
}

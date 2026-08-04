plugins {
    kotlin("jvm") version "2.4.10"
    id("org.springframework.boot") version "4.0.6"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:6.21.5")
}

kotlin {
    jvmToolchain(17)
}

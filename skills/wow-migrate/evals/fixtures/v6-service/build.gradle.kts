plugins {
    kotlin("jvm") version "2.3.20"
    id("org.springframework.boot") version "4.1.0"
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

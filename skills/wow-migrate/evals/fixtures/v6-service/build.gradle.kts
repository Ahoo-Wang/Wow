plugins {
    kotlin("jvm") version "1.9.25"
    id("org.springframework.boot") version "3.3.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:6.7.0")
}

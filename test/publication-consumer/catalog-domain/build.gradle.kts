plugins {
    kotlin("jvm")
    id("me.ahoo.wow")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":catalog-api"))
    // The aggregate test DSL, package me.ahoo.wow.test.
    testImplementation("me.ahoo.wow:wow-test")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

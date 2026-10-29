plugins {
    kotlin("jvm")
    id("me.ahoo.wow.api")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    wowContract(project(":catalog-domain"))
    api("jakarta.validation:jakarta.validation-api")
}

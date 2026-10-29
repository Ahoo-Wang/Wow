plugins {
    kotlin("jvm")
    id("me.ahoo.wow.api")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    wowContract(project(":catalog-domain"))
    // The commands' constraints: wow-bom constrains Wow's modules alone, so the api module gives its version.
    api("jakarta.validation:jakarta.validation-api:3.1.1")
}

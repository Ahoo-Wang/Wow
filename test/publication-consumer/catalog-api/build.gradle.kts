plugins {
    kotlin("jvm")
    id("me.ahoo.wow.api")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    wowContract(project(":catalog-domain"))
    // The commands' constraints (@field:Positive, @field:NotBlank). wow-bom constrains Wow's own modules alone, so
    // the api module names the version, Spring Boot's.
    api("jakarta.validation:jakarta.validation-api:3.1.1")
}

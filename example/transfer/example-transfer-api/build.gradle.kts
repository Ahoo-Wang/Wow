@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental

plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(platform(project(":wow-dependencies")))
    api("io.swagger.core.v3:swagger-core-jakarta")
    implementation(project(":wow-api"))
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    ksp(project(":wow-compiler"))
}
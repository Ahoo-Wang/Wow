plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(platform(project(":wow-dependencies")))
    api("io.swagger.core.v3:swagger-core-jakarta")
    implementation(project(":wow-api"))
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    ksp(project(":wow-compiler"))
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
}

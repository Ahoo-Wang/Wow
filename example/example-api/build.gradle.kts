plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(platform(project(":wow-dependencies")))
    api("io.swagger.core.v3:swagger-core-jakarta")
    implementation(project(":wow-api"))
    api(project(":wow-models"))
    implementation(project(":wow-apiclient"))
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    api("me.ahoo.coapi:coapi-api")
    implementation("org.springframework:spring-web")
    ksp(project(":wow-compiler"))
}

plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(platform(project(":wow-dependencies")))
    api(project(":wow-api"))
    api("io.swagger.core.v3:swagger-core-jakarta")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    ksp(project(":wow-compiler"))
    testImplementation("tools.jackson.module:jackson-module-kotlin")
}

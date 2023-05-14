description = "Wow Shared Kernel"

dependencies {
    api(project(":wow-api"))
    api("me.ahoo.cosid:cosid-core")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("jakarta.validation:jakarta.validation-api")
    api("com.google.guava:guava")
    api("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation(project(":wow-tck"))
}

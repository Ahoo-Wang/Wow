description = "Wow Shared Kernel"

dependencies {
    api(project(":wow-api"))
    api("me.ahoo.cosid:cosid-core")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("jakarta.validation:jakarta.validation-api")
    api("com.google.guava:guava")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("io.github.oshai:kotlin-logging-jvm")
    api(kotlin("reflect"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta")
    testImplementation(project(":wow-tck"))
    jmh(project(":example-domain"))
    jmh(project(":wow-test"))
}

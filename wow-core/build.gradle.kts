description = "Wow Shared Kernel"

dependencies {
    api(project(":wow-api"))
    api("me.ahoo.cosid:cosid-core")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("jakarta.validation:jakarta.validation-api")
    api("com.google.guava:guava")
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    api("io.github.oshai:kotlin-logging-jvm")
    api(kotlin("reflect"))
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation(project(":wow-tck"))
}

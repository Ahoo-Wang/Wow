description = "Wow HTTP: routes materialized from the Manifest, problem+json, SSE and the signal entry"

dependencies {
    api(project(":runtime"))
    api("org.springframework:spring-webflux")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // The wait signal's wire form on the internal port, read like the cluster messages (decision D105).
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.github.oshai:kotlin-logging-jvm")
    // The Jakarta Bean Validation adapter of CommandValidator; the host enables it where a provider is present (D106).
    compileOnly("jakarta.validation:jakarta.validation-api")
    // WebTestClient, bound to a router function or to a Reactor Netty server.
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    // The default handler strategies of a router function served without Spring Boot read the locale context.
    testImplementation("org.springframework:spring-context")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

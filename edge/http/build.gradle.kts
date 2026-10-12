description = "Wow HTTP: routes materialized from the Manifest, problem+json, SSE and the signal entry"

dependencies {
    api(project(":runtime"))
    api("org.springframework:spring-webflux")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // The Jakarta Bean Validation adapter of CommandValidator; the host enables it where a provider is present (D106).
    compileOnly("jakarta.validation:jakarta.validation-api")
    // WebTestClient, bound to a router function or to a Reactor Netty server.
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

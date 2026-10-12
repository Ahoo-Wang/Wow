description = "Wow Spring Boot: auto-configuration by classpath; wiring only"

dependencies {
    api(project(":runtime"))
    api(project(":http"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    // The public routes are served by the application's WebFlux server (decision D30), the internal port by a Reactor
    // Netty server of the host's own (D89).
    api("org.springframework.boot:spring-boot-webflux")
    implementation("org.springframework.boot:spring-boot-reactor-netty")
    implementation("io.projectreactor.netty:reactor-netty-http")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.github.oshai:kotlin-logging-jvm")
    // The backend follows what the application puts on the classpath (`wow.backend`, D110): the host wires either
    // and brings neither.
    compileOnly(project(":postgres"))
    compileOnly(project(":mongo"))
    // Activated only when the application has them: a Bean Validation provider (D106) and CosId (D114).
    compileOnly("jakarta.validation:jakarta.validation-api")
    compileOnly("me.ahoo.cosid:cosid-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-jackson")
    testImplementation("org.springframework:spring-test")
    testImplementation("me.ahoo.cosid:cosid-core")
    testImplementation(project(":postgres"))
    testImplementation(project(":mongo"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    integrationTestImplementation(sourceSets["test"].output)
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    integrationTestImplementation("org.testcontainers:testcontainers-mongodb")
    integrationTestImplementation("com.zaxxer:HikariCP")
}

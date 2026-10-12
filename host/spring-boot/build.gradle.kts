description = "Wow Spring Boot: auto-configuration by classpath; wiring only"

dependencies {
    api(project(":runtime"))
    api(project(":http"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    implementation("io.projectreactor.netty:reactor-netty-http")
    // The backend follows what the application puts on the classpath (`wow.backend`, D110): the host wires either
    // and brings neither.
    compileOnly(project(":postgres"))
    compileOnly(project(":mongo"))
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation(project(":postgres"))
    testImplementation(project(":mongo"))
}

description = "Wow CoSec: the caller from CoSec's security context, and command authorization"

dependencies {
    api(project(":http"))
    // CoSec itself is the host's: its WebFilter authorizes by path and method, and this module reads what it resolved.
    compileOnly(libs.cosec.webflux)
    // The configuration the host imports (AutoConfiguration.imports) is plain Spring: only the host imports Spring
    // Boot (decision D118's module rules).
    compileOnly("org.springframework:spring-context")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation(libs.cosec.webflux)
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework:spring-context")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // ApplicationContextRunner, and the import selector that reads this module's auto-configuration metadata.
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-test")
}

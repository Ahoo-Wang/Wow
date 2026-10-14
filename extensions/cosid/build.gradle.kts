description = "Wow CosId: CosId's generators as a node's global and aggregate id generators (decision D171)"

dependencies {
    api(project(":spi"))
    api("me.ahoo.cosid:cosid-core")
    // The configuration the host imports (AutoConfiguration.imports) is plain Spring: only the host imports Spring
    // Boot (decision D118's module rules), as in wow-cosec.
    compileOnly("org.springframework:spring-context")
    implementation("io.github.oshai:kotlin-logging-jvm")
    testImplementation("org.springframework:spring-context")
    // ApplicationContextRunner, and the import selector that reads this module's auto-configuration metadata.
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-test")
}

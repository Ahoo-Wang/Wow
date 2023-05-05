description = "Integration Testing"

dependencies {
    testImplementation(project(":wow-core"))
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("org.hamcrest:hamcrest")
    testImplementation(project(":wow-mongo"))
    testImplementation(project(":wow-kafka"))
    testImplementation(project(":wow-tck"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:kafka")
}

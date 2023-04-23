description = "Integration Testing"

dependencies {
    api(project(":wow-core"))
    api("io.projectreactor:reactor-test")
    api("me.ahoo.cosid:cosid-test")
    api("org.hamcrest:hamcrest")
    api(project(":wow-mongo"))
    api(project(":wow-kafka"))
    testImplementation(project(":wow-test"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:kafka")
}

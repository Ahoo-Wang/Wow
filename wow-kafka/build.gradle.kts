dependencies {
    api(project(":wow-core"))
    api("io.projectreactor.kafka:reactor-kafka")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation(project(":wow-tck"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
}

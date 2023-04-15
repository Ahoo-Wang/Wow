dependencies {
    api(project(":wow-core"))
    api("io.projectreactor.kafka:reactor-kafka")
    implementation("me.ahoo.cosid:cosid-core")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation(project(":wow-test"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
}

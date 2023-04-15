dependencies {
    api(project(":wow-core"))
    api("org.mongodb:mongodb-driver-reactivestreams")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.google.guava:guava")
    testImplementation(project(":wow-test"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
}

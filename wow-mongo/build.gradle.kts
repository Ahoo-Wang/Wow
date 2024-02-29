dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api("org.mongodb:mongodb-driver-reactivestreams")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.google.guava:guava")
    testImplementation(project(":wow-tck"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
}

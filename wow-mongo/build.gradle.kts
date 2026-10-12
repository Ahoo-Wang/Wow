dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api("org.mongodb:mongodb-driver-reactivestreams")
    testImplementation(project(":wow-tck"))
}

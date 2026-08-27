description = "Integration Testing"

dependencies {
    api(project(":wow-core"))
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation(project(":wow-tck"))
}

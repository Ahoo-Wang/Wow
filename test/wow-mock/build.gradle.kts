description = "Integration Testing"

dependencies {
    api(project(":wow-core"))
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("org.hamcrest:hamcrest")
    testImplementation(project(":wow-tck"))
}

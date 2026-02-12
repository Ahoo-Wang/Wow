dependencies {
    api(project(":wow-core"))
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(project(":wow-tck"))
}
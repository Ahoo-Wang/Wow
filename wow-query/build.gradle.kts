dependencies {
    api("io.projectreactor:reactor-core")
    api(project(":wow-core"))
    api("io.projectreactor:reactor-test")
    testImplementation(project(":wow-tck"))
}
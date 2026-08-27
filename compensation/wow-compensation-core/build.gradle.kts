dependencies {
    api(platform(project(":wow-dependencies")))
    implementation(project(":wow-compensation-api"))
    implementation(project(":wow-core"))
    testImplementation(project(":wow-compensation-domain"))
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
}

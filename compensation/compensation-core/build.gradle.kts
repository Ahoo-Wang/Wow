dependencies {
    api(platform(project(":wow-dependencies")))
    implementation(project(":compensation-api"))
    implementation(project(":wow-core"))
    testImplementation(project(":compensation-domain"))
    testImplementation(project(":wow-test"))
    testImplementation("io.projectreactor:reactor-test")
}

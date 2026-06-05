dependencies {
    api(project(":wow-core"))
    api(libs.reactor.kafka)
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation(project(":wow-tck"))
}

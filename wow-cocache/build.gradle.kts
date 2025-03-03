
dependencies {
    api(project(":wow-apiclient"))
    api(project(":wow-query"))
    api("me.ahoo.cocache:cocache-core")
    testImplementation(project(":wow-tck"))
}

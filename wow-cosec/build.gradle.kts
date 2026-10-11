dependencies{
    implementation(project(":wow-webflux"))
    // CoSec itself is the host's: only CoSecAbacQueryPolicy reads it, and only where the host brings it.
    compileOnly(libs.cosec.webflux)
    testImplementation(libs.cosec.webflux)
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-domain"))
    testImplementation("org.springframework:spring-test")
}
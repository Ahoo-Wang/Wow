description = "Wow CoSec: the caller from CoSec's security context, and command authorization"

dependencies {
    // The guard of the hand-written equals (finding R5.B-13).
    testImplementation(project(":tck"))
    api(project(":http"))
    // CoSec itself is the host's: its WebFilter authorizes by path and method, and this module reads what it resolved.
    compileOnly(libs.cosec.webflux)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation(libs.cosec.webflux)
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

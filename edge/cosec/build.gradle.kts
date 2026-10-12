description = "Wow CoSec: the caller from CoSec's security context, and command authorization"

dependencies {
    api(project(":http"))
    // CoSec itself is the host's: its WebFilter authorizes by path and method, and this module reads what it resolved.
    compileOnly(libs.cosec.webflux)
    testImplementation(libs.cosec.webflux)
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
}

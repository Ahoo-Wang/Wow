description = "Wow test server: a Wow Spring Boot application started for a test, on its own ports, with an HTTP client"

// The server-side test kit (06-modules.md): :test-dsl stays on the kernel only (D200), so a test that starts an
// application takes this module, which builds on the host. Its typed clients are wow-client's, which the host's API
// carries (D389 ①).
dependencies {
    api(project(":spring-boot"))
    // The quickstart's bank service, a domain the typed clients send to and read from: its api contract on the class
    // path, as a service test has its own.
    testImplementation(project(":quickstart"))
}

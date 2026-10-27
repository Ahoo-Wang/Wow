description = "Wow test server: a Wow Spring Boot application started for a test, on its own ports, with an HTTP client"

// The server-side test kit (06-modules.md): :test-dsl stays on the kernel only (D200), so a test that starts an
// application takes this module, which builds on the host.
dependencies {
    api(project(":spring-boot"))
}

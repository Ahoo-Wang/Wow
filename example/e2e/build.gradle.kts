description = "Wow V10 example: end-to-end tests across the example's services"

// Test-only and never published (decision D144): the root build keeps it out of publishing, like the shop. It builds
// on V10 modules and the V10 example only (decision D118). The two-context suite on Kafka and PostgreSQL comes with
// work package 6.3e; this is its skeleton (6.00).
dependencies {
    integrationTestImplementation(project(":shop-server"))
}

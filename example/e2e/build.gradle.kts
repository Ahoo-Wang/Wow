description = "Wow V10 example: end-to-end tests across the example's services"

// Test-only and never published (decision D144): the root build keeps it out of publishing, like the shop. It builds
// on V10 modules and the V10 example only (decision D118). The shop and the inventory each run in a JVM of their own,
// on the runtime class path of their server project alone: the host serves every binding index on its class path, so
// the two services could not share one. The class paths reach the tests as system properties
// (`example.<service>.classpath`), as the eShop's end-to-end tests pass theirs.
val servers = listOf("shop", "inventory").associateWith { service ->
    configurations.create("${service}Server") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.STANDARD_JVM),
            )
        }
    }
}

dependencies {
    "shopServer"(project(":shop-server"))
    "inventoryServer"(project(":inventory-server"))
    // The test drives the services over HTTP, and Kafka directly: it creates the topics and copies messages to
    // deliver them again.
    integrationTestImplementation("tools.jackson.core:jackson-databind")
    integrationTestImplementation("org.apache.kafka:kafka-clients")
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named<Test>("integrationTest") {
    servers.forEach { (service, classpath) ->
        inputs.files(classpath).withNormalizer(ClasspathNormalizer::class).withPropertyName("${service}Server")
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dexample.$service.classpath=${classpath.asPath}") })
    }
}

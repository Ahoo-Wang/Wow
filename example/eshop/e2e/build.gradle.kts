description = "Wow V10 eShop: end-to-end tests across the eShop's services"

// Test-only and never published (decision D159), like :example-e2e.
//
// - EshopE2eTest runs the catalog and inventory services each in a JVM of its own, on the runtime class path of its
//   server project alone; the class paths reach the tests as system properties (`eshop.<context>.classpath`).
// - EshopSharedJvmTest runs the catalog and basket services in the test's own JVM, on one class path that holds both
//   servers: each selects its own context with `wow.contexts.include` (decision D169), since the host serves every
//   binding index on its class path by default.
// - EshopOrderFlowTest runs the basket, ordering and inventory services each in a JVM of its own, on PostgreSQL and
//   Kafka in containers: the checkout, the order flow, the stock rejection's compensation and the payment grace
//   period. They cannot share one class path: the host reads every peer Manifest on it, and the ordering server's copy
//   of the inventory's Manifest makes the inventory its own peer (DX-GAPS.md).
//
// The suite with all five services, ClickHouse and the failure scenarios comes with work package E.6.
val servers = listOf("catalog", "basket", "ordering", "inventory").associateWith { context ->
    configurations.create("eshop${context.replaceFirstChar { it.uppercase() }}Server") {
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
    "eshopCatalogServer"(project(":eshop-catalog-server"))
    "eshopBasketServer"(project(":eshop-basket-server"))
    "eshopOrderingServer"(project(":eshop-ordering-server"))
    "eshopInventoryServer"(project(":eshop-inventory-server"))
    integrationTestImplementation("tools.jackson.core:jackson-databind")
    integrationTestImplementation(project(":eshop-catalog-server"))
    integrationTestImplementation(project(":eshop-basket-server"))
    // The in-memory command transport the basket needs without Kafka (InMemoryTopics, SourceTopics).
    integrationTestImplementation(project(":spring-boot"))
    integrationTestImplementation("org.springframework.boot:spring-boot")
    // The order flow's test creates the Kafka topics and runs PostgreSQL and Kafka in containers.
    integrationTestImplementation("org.apache.kafka:kafka-clients")
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
    // CatalogPostgresTest: the catalog's storefront queries on PostgreSQL (work package E.1).
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named<Test>("integrationTest") {
    servers.forEach { (context, classpath) ->
        inputs.files(classpath).withNormalizer(ClasspathNormalizer::class).withPropertyName("${context}Server")
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.$context.classpath=${classpath.asPath}") })
    }
}

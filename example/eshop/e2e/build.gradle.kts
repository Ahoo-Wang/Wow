description = "Wow V10 eShop: end-to-end tests across the eShop's services"

// Test-only and never published (decision D159), like :example-e2e. Each service runs in a JVM of its own, on the
// runtime class path of its server project alone: the host serves every binding index on its class path, so two
// services sharing one class path would each serve both contexts. The class paths reach the tests as system
// properties (`eshop.<context>.classpath`). The suite on PostgreSQL, Kafka and ClickHouse comes with work package E.6;
// this is its skeleton (E.0).
val servers = listOf("catalog", "basket").associateWith { context ->
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
    integrationTestImplementation("tools.jackson.core:jackson-databind")
}

tasks.named<Test>("integrationTest") {
    servers.forEach { (context, classpath) ->
        inputs.files(classpath).withNormalizer(ClasspathNormalizer::class).withPropertyName("${context}Server")
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.$context.classpath=${classpath.asPath}") })
    }
}

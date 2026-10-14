description = "Wow V10 eShop: end-to-end tests across the eShop's services"

// Test-only and never published (decision D159).
//
// - EshopE2eTest runs the catalog and inventory services each in a JVM of its own, on the runtime class path of its
//   server project alone; the class paths reach the tests as system properties (`eshop.<context>.classpath`).
// - EshopSharedJvmTest runs services in the test's own JVM, on one class path that holds their servers: each selects
//   its own context with `wow.contexts.include` (decision D169), since the host serves every binding index on its
//   class path by default, and leaves out another server's copy of its own Manifest from its peers (DX gap G21).
// - EshopOrderFlowTest runs the basket, ordering and inventory services each in a JVM of its own, on PostgreSQL and
//   Kafka in containers: the checkout, the order flow, the stock rejection's compensation and the payment grace
//   period. Each JVM reads its own server's `application.yaml`, as a deployment does; on one class path, Spring Boot
//   reads only one of them.
// - EshopPaymentFlowTest runs the ordering, inventory and payment services the same way: the payment the simulated
//   gateway approves pays the order, and the one it declines cancels it and releases its stock (work package E.5).
// - EshopFullChainTest runs all five services the same way through EshopStack, with ClickHouse for the ordering's
//   sales dataset (work package E.6): the full chain with one Wow-Wait from the checkout to the paid order, the short
//   stock's cancellation, the sales dataset, and an ordering node killed mid-flow and replaced.
// - EshopLoad, the load script, starts the same EshopStack (the task eshopLoad below; README.md holds its baseline).
val servers = listOf("catalog", "basket", "ordering", "inventory", "payment").associateWith { context ->
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
    "eshopPaymentServer"(project(":eshop-payment-server"))
    integrationTestImplementation("tools.jackson.core:jackson-databind")
    integrationTestImplementation(project(":eshop-catalog-server"))
    integrationTestImplementation(project(":eshop-basket-server"))
    integrationTestImplementation(project(":eshop-ordering-server"))
    integrationTestImplementation(project(":eshop-inventory-server"))
    integrationTestImplementation(project(":eshop-payment-server"))
    // The in-memory command transport the basket needs without Kafka (InMemoryTopics, SourceTopics).
    integrationTestImplementation(project(":spring-boot"))
    integrationTestImplementation("org.springframework.boot:spring-boot")
    // The order flow's test creates the Kafka topics and runs PostgreSQL and Kafka in containers.
    integrationTestImplementation("org.apache.kafka:kafka-clients")
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
    // CatalogPostgresTest: the catalog's storefront queries on PostgreSQL (work package E.1).
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    // ... and the storefront's query written with the query DSL and the catalog's generated ProductStateProperties (D204).
    integrationTestImplementation(project(":eshop-catalog-domain"))
    // EshopFullChainTest and EshopLoad: the ordering's order stream in ClickHouse, for the sales dataset (E.6).
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
    // EshopSharedJvmTest: a request's trace through a service (decision D174), its spans exported to memory.
    integrationTestImplementation(project(":opentelemetry"))
    integrationTestImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

/** The class paths of the servers, and the directory of their output, as the services' JVMs take them. */
fun JavaForkOptions.eshopServers() {
    servers.forEach { (context, classpath) ->
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.$context.classpath=${classpath.asPath}") })
    }
    // Each service's output, kept for a failed run.
    val logs = layout.buildDirectory.dir("eshop-logs")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.logs=${logs.get().asFile}") })
}

tasks.named<Test>("integrationTest") {
    servers.forEach { (context, classpath) ->
        inputs.files(classpath).withNormalizer(ClasspathNormalizer::class).withPropertyName("${context}Server")
    }
    eshopServers()
}

// The load script (EshopLoad.kt), outside every check: `./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLoad`, with the
// properties `-Peshop.load.checkouts=200 -Peshop.load.concurrency=8 -Peshop.load.warm-up=40` (e2e/README.md, its baseline).
tasks.register<JavaExec>("eshopLoad") {
    description = "Drives checkouts through the eShop's five services and reports their throughput and latency."
    classpath = project.the<SourceSetContainer>()["integrationTest"].runtimeClasspath
    mainClass.set("me.ahoo.wow.example.eshop.e2e.EshopLoadKt")
    servers.values.forEach { dependsOn(it) }
    eshopServers()
    listOf("eshop.load.checkouts", "eshop.load.concurrency", "eshop.load.warm-up").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

// The fault-injection and soak harness (work package C.1, decision D196), outside every check and CI workflow:
// `./gradlew -Pwow.scope=v10 :eshop-e2e:soakTest -Pwow.soak.duration=PT1H`. It has a source set of its own, on the
// integration tests' classes (EshopService), so integrationTest and v10IntegrationTest neither compile nor run it.
// The Gradle properties `wow.soak.*` (SoakConfig) reach the test JVM as system properties; the report and the
// services' output go to build/eshop-soak/.
val integrationTestSourceSet = sourceSets["integrationTest"]
val soakTestSourceSet = sourceSets.create("soakTest") {
    compileClasspath += integrationTestSourceSet.output + integrationTestSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath + integrationTestSourceSet.runtimeClasspath
}
configurations["soakTestImplementation"].extendsFrom(configurations["integrationTestImplementation"])
configurations["soakTestRuntimeOnly"].extendsFrom(configurations["integrationTestRuntimeOnly"])

dependencies {
    // PostgreSQL and Kafka of the TCK's images (WowContainers), bound to fixed host ports so they can be restarted.
    "soakTestImplementation"(project(":tck-containers"))
}

// detekt (and so v10Check) covers the harness, which no check compiles otherwise.
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
    source(files("src/soakTest/kotlin"))
}

tasks.register<Test>("soakTest") {
    description = "Runs the eShop's fault-injection soak (C.1): checkouts under node kills and backend restarts, " +
        "then its invariants."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = soakTestSourceSet.output.classesDirs
    classpath = soakTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    servers.values.forEach { dependsOn(it) }
    eshopServers()
    val soakDir = layout.buildDirectory.dir("eshop-soak")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.soak.dir=${soakDir.get().asFile}") })
    providers.gradlePropertiesPrefixedBy("wow.soak.").get().forEach { (name, value) -> systemProperty(name, value) }
    // A soak is never up to date, and never retried: a second try would hide the first one's violations.
    outputs.upToDateWhen { false }
    // The root build may set retries for CI on every Test task; set last, before the run.
    doFirst {
        extensions.getByType<org.gradle.testretry.TestRetryTaskExtension>().maxRetries.set(0)
    }
    testLogging {
        showStandardStreams = true
    }
}

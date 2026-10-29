description = "Wow eShop: end-to-end tests across the eShop's services"

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
//   The ordering and the payment run their `redis` profile on Redis in a container: the ordering writes its orders into
//   CoCache, and the payment's gateway saga reads them there (decision D364).
// - EshopFullChainTest runs all five services the same way through EshopStack, with ClickHouse for the ordering's
//   sales dataset (work package E.6): the full chain with one Wow-Wait from the checkout to the paid order, the short
//   stock's cancellation, the sales dataset, and an ordering node killed mid-flow and replaced.
// - EshopLedgerOpsTest runs the ordering, inventory and payment services the same way, and the ops service
//   (`:ops-server`, roadmap 7.1a) in a JVM of its own beside them: a payment the gateway fails parks, the ops service
//   shows it through its query routes, and an operator's resume there resolves it (6.3f).
// - EshopLoad, the load script, starts the same EshopStack (the task eshopLoad below; README.md holds its baseline).
// The platform's ops service (`ops`) runs beside them as one more server.
val servers = listOf("catalog", "basket", "ordering", "inventory", "payment", "ops").associateWith { context ->
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
    "eshopOpsServer"(project(":ops-server"))
    integrationTestImplementation("tools.jackson.core:jackson-databind")
    integrationTestImplementation(project(":eshop-catalog-server"))
    integrationTestImplementation(project(":eshop-basket-server"))
    integrationTestImplementation(project(":eshop-ordering-server"))
    integrationTestImplementation(project(":eshop-inventory-server"))
    integrationTestImplementation(project(":eshop-payment-server"))
    // The in-memory command transport the basket needs without Kafka (InMemoryTopics, SourceTopics).
    integrationTestImplementation(project(":spring-boot"))
    integrationTestImplementation("org.springframework.boot:spring-boot")
    // CatalogPostgresTest, EshopSharedJvmTest and EshopSyncCallTest start their services in this JVM (wowServer).
    integrationTestImplementation(project(":test-spring-boot"))
    // EshopPaymentFlowTest reads the ordering's orders from CoCache on Redis, as a third service does.
    integrationTestImplementation("me.ahoo.wow:wow-cocache")
    integrationTestImplementation("me.ahoo.cocache:cocache-redis")
    // The order flow's test creates the Kafka topics and runs PostgreSQL and Kafka in containers.
    integrationTestImplementation("org.apache.kafka:kafka-clients")
    integrationTestImplementation("org.testcontainers:testcontainers-kafka")
    // CatalogPostgresTest: the catalog's storefront queries on PostgreSQL (work package E.1).
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
    // ... and the storefront's query written with the query DSL and the catalog api's generated query fields (D204,
    // D259). The tests name every context's aggregates by the contract code of its api (EshopOrderingContract, ...).
    listOf("catalog", "basket", "ordering", "inventory", "payment").forEach {
        integrationTestImplementation(project(":eshop-$it-api"))
    }
    // ... read as the catalog's ProductState through QueryApi (R4-Q3).
    integrationTestImplementation(project(":eshop-catalog-domain"))
    // EshopFullChainTest and EshopLoad: the ordering's order stream in ClickHouse, for the sales dataset (E.6).
    integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")
    // EshopSharedJvmTest: a request's trace through a service (decision D174), its spans exported to memory.
    integrationTestImplementation(project(":observability"))
    integrationTestImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    // EshopLedgerOpsTest: the statuses of the ops view (work package 6.3f, D141).
    integrationTestImplementation(project(":ops-api"))
}

/**
 * The servers' class paths, one file each (`<context>.classpath` in build/eshop-servers): the test JVM reads them from
 * files rather than from six system properties of tens of kilobytes, which would make its command line too long on
 * Linux.
 */
val eshopServerClasspaths = tasks.register("eshopServerClasspaths") {
    description = "Writes the class path of each eShop server to build/eshop-servers/<context>.classpath."
    val output = layout.buildDirectory.dir("eshop-servers")
    servers.forEach { (context, classpath) ->
        inputs.files(classpath).withNormalizer(ClasspathNormalizer::class).withPropertyName("${context}Server")
    }
    val paths = servers.mapValues { (_, classpath) -> classpath }
    outputs.dir(output)
    doLast {
        paths.forEach { (context, classpath) ->
            output.get().file("$context.classpath").asFile.apply { parentFile.mkdirs() }.writeText(classpath.asPath)
        }
    }
}

/** The files of the servers' class paths, and the directory of their output, as the services' JVMs take them. */
fun JavaForkOptions.eshopServers() {
    (this as Task).dependsOn(eshopServerClasspaths)
    val classpaths = layout.buildDirectory.dir("eshop-servers")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.servers=${classpaths.get().asFile}") })
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

// The load script (EshopLoad.kt), outside every check: `./gradlew :eshop-e2e:eshopLoad`, with the
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

// The whole eShop in one JVM, on the memory backend and in-memory topics (EshopLocal.kt), for the web UI
// (`examples/eshop/web`, work package E.7) and for trying the shop out without Kafka:
// `./gradlew :eshop-e2e:eshopLocal`, on the README's ports. Outside every check.
tasks.register<JavaExec>("eshopLocal") {
    description = "Runs the eShop's five services in one JVM, in memory, on ports 8081 to 8085."
    classpath = project.the<SourceSetContainer>()["integrationTest"].runtimeClasspath
    mainClass.set("me.ahoo.wow.example.eshop.e2e.EshopLocalKt")
}

// The class path of EshopLocal, for the web UI's end-to-end test, which starts it as a JVM of its own and stops it.
tasks.register("eshopLocalClasspath") {
    description = "Writes the class path of EshopLocal to build/eshop-local/classpath.txt."
    val runtime = project.the<SourceSetContainer>()["integrationTest"].runtimeClasspath
    val output = layout.buildDirectory.file("eshop-local/classpath.txt")
    inputs.files(runtime).withNormalizer(ClasspathNormalizer::class).withPropertyName("runtime")
    outputs.file(output)
    doLast {
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(runtime.asPath)
    }
}

// The fault-injection and soak harness (work package C.1, decision D196), outside every check and CI workflow:
// `./gradlew :eshop-e2e:soakTest -Pwow.soak.duration=PT1H`. It has a source set of its own, on the
// integration tests' classes (EshopService), so integrationTest and :integrationTest neither compile nor run it.
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

// detekt (and so :check) covers the harness, which no check compiles otherwise.
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
    // The nodes' class path takes this source set's output too: SoakSignals and its auto-configuration (D224 note ⑦).
    val soakOutput = soakTestSourceSet.output
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Deshop.extra-classpath=${soakOutput.asPath}") })
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

// The GA end-to-end runner (roadmap 9.3g, decision D317, work package G.3), outside every check: the scenarios and
// the verdict against the eShop deployed on a kind cluster, which only .github/workflows/ga-e2e.yml creates
// (`./gradlew :eshop-e2e:clusterTest -Pga.backend=postgres` there). It reaches the shop through the CoSec gateway's
// node port and the cluster through kubectl, and reuses the soak's invariants and its reads of the logs
// (Invariants, SoakDatabase). The Gradle properties `ga.*` reach it as system properties; the report goes to
// build/ga-e2e/.
val soakTestOutput = soakTestSourceSet.output
val clusterTestSourceSet = sourceSets.create("clusterTest") {
    compileClasspath += soakTestOutput + soakTestSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath + soakTestSourceSet.runtimeClasspath
}
configurations["clusterTestImplementation"].extendsFrom(configurations["soakTestImplementation"])
configurations["clusterTestRuntimeOnly"].extendsFrom(configurations["soakTestRuntimeOnly"])

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
    source(files("src/clusterTest/kotlin"))
}

tasks.register<Test>("clusterTest") {
    description = "Runs the GA end-to-end scenarios against the eShop on the kind cluster ga-e2e.yml deploys (D317)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = clusterTestSourceSet.output.classesDirs
    classpath = clusterTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    val reportDir = layout.buildDirectory.dir("ga-e2e")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dga.report=${reportDir.get().asFile}") })
    providers.gradlePropertiesPrefixedBy("ga.").get().forEach { (name, value) -> systemProperty(name, value) }
    // A cluster run is never up to date, and never retried: a second try would hide the first one's violations.
    outputs.upToDateWhen { false }
    doFirst {
        extensions.getByType<org.gradle.testretry.TestRetryTaskExtension>().maxRetries.set(0)
    }
    testLogging {
        showStandardStreams = true
    }
}

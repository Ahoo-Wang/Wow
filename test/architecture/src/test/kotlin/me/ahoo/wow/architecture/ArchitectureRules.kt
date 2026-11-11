/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.architecture

import java.io.File

/** A Wow project: its Gradle project name and its directory relative to the repository root. */
data class WowProject(val name: String, val dir: String)

/**
 * The architecture rules (documentation/designs/06-modules.md §3 and 07-migration.md §4).
 */
object ArchitectureRules {
    /** The stability markers (decision D282): `InternalWowApi`, `ExperimentalWowApi`, `WowSpi`; no dependencies. */
    val ANNOTATIONS = WowProject("annotations", "api/annotations")
    val API = WowProject("api", "api/api")
    val MANIFEST = WowProject("manifest", "api/manifest")
    val QUERY_API = WowProject("query-api", "api/query-api")
    val SPI = WowProject("spi", "core/spi")
    val KERNEL = WowProject("kernel", "core/kernel")
    val RUNTIME = WowProject("runtime", "core/runtime")
    val REACTOR = WowProject("reactor", "core/reactor")
    val QUERY = WowProject("query", "query/query")
    val POSTGRES = WowProject("postgres", "backends/postgres")
    val MONGO = WowProject("mongo", "backends/mongo")
    val KAFKA = WowProject("kafka", "backends/kafka")
    val CLICKHOUSE = WowProject("clickhouse", "backends/clickhouse")

    /** Snapshots, their queries and event search on Elasticsearch, never a log (decisions D13, D167). */
    val ELASTICSEARCH = WowProject("elasticsearch", "backends/elasticsearch")
    val OPENAPI = WowProject("openapi", "edge/openapi")
    val HTTP = WowProject("http", "edge/http")

    /** The typed command and query clients over HTTP (decisions D257, D259), on the JDK's HttpClient alone. */
    val CLIENT = WowProject("client", "edge/client")
    val COSEC = WowProject("cosec", "edge/cosec")
    val OBSERVABILITY = WowProject("observability", "edge/observability")
    val SPRING_BOOT = WowProject("spring-boot", "host/spring-boot")
    val COSID = WowProject("cosid", "extensions/cosid")

    /** CoCache's shared state views (decision D364): the cache a domain injects, and what keeps it through CoCache. */
    val COCACHE_API = WowProject("cocache-api", "extensions/cocache-api")
    val COCACHE = WowProject("cocache", "extensions/cocache")

    /** A service's peers through a Spring Cloud registry, and a client's endpoints through its LoadBalancer (D396). */
    val SPRING_CLOUD = WowProject("spring-cloud", "extensions/spring-cloud")

    /** 9.x wow-models ported (decision D187): capability interfaces and the reusable Tree aggregate. */
    val MODELS = WowProject("models", "extensions/models")
    val MIGRATE_DATA = WowProject("migrate-data", "migration/migrate-data")
    val COMPILER = WowProject("compiler", "compiler/compiler")
    val GRADLE_PLUGIN = WowProject("gradle-plugin", "compiler/gradle-plugin")
    val MANIFEST_TOOLS = WowProject("manifest-tools", "compiler/manifest-tools")

    /** The test DSL (artifact `wow-test`; a project named `:test` would clash with the `test` task, decision D87). */
    val TEST_DSL = WowProject("test-dsl", "test/test-dsl")

    /** A real in-memory query side for the test DSL's processor specs: the bridge from [TEST_DSL] to the query engine. */
    val TEST_QUERY = WowProject("test-query", "test/test-query")

    /** A Wow application started for a test, on its own ports (`wowServer`): the server-side test kit, on the host. */
    val TEST_SPRING_BOOT = WowProject("test-spring-boot", "test/test-spring-boot")

    /** The SPI contract specs every backend runs (decision D196). */
    val TCK = WowProject("tck", "test/tck")

    /** The query backend contract specs every query backend runs (review R3.3-2). */
    val QUERY_TCK = WowProject("query-tck", "query/query-tck")

    /** The TCK's shared Testcontainers, apart from [TCK] so a spec needs no Docker. */
    val TCK_CONTAINERS = WowProject("tck-containers", "test/tck-containers")

    /**
     * The test modules (decision D196), the gate of every iteration: any module's tests may build on them, through a
     * test configuration (`testImplementation`, `integrationTestImplementation`, ...); no module's main code may.
     */
    val TEST_MODULES = listOf(TEST_DSL, TEST_QUERY, TEST_SPRING_BOOT, TCK, QUERY_TCK, TCK_CONTAINERS)

    /** The ops context's API: its commands and events (roadmap 6.3f, 7.1a; decision D141). */
    val OPS_API = WowProject("ops-api", "platform/ops/ops-api")

    /** The ops domain: the `parked` aggregate, the read model of every service's failure ledger (decision D141). */
    val OPS_DOMAIN = WowProject("ops-domain", "platform/ops/ops-domain")

    /** The view store's API: its commands, events and value types (roadmap 7.2a). */
    val VIEW_STORE_API = WowProject("view-store-api", "platform/view-store/view-store-api")

    /** The view store's domain: the view, view preferences and dataset wording aggregates (roadmap 7.2a). */
    val VIEW_STORE_DOMAIN = WowProject("view-store-domain", "platform/view-store/view-store-domain")

    /** The analytics catalog's API: the commands, events and value types of its data sources (roadmap 9.3b). */
    val ANALYTICS_CATALOG_API = WowProject("analytics-catalog-api", "platform/analytics/catalog/api")

    /** The analytics catalog's domain: the source aggregate and its warehouse catalog port (decisions D238, D241). */
    val ANALYTICS_CATALOG_DOMAIN =
        WowProject("analytics-catalog-domain", "platform/analytics/catalog/domain")

    /** The platform modules (roadmap M7): domains of their own, built on the kernel as an application's domain is. */
    val PLATFORM_MODULES =
        listOf(OPS_API, OPS_DOMAIN, VIEW_STORE_API, VIEW_STORE_DOMAIN, ANALYTICS_CATALOG_API, ANALYTICS_CATALOG_DOMAIN)

    /** The ops service (roadmap 7.1a): an application of the Wow modules, on the Spring Boot host. */
    val OPS_SERVER = WowProject("ops-server", "platform/ops/ops-server")

    /** The view store service (roadmap 7.2a): an application of the Wow modules, on the Spring Boot host. */
    val VIEW_STORE_SERVER = WowProject("view-store-server", "platform/view-store/view-store-server")

    /** The analytics catalog service (roadmap 9.3b): an application of the Wow modules, on the Spring Boot host. */
    val ANALYTICS_CATALOG_SERVER =
        WowProject("analytics-catalog-server", "platform/analytics/catalog/server")

    /**
     * The platform's services (roadmap M7): applications of the Wow modules, as the eShop's servers are, not modules
     * of the framework, so the rules of [MODULES] (Spring Boot only in the host, say) do not apply to them.
     */
    val PLATFORM_SERVERS = listOf(VIEW_STORE_SERVER, OPS_SERVER, ANALYTICS_CATALOG_SERVER)

    val MODULES = listOf(
        ANNOTATIONS, API, MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME, REACTOR, QUERY, POSTGRES, MONGO, KAFKA, CLICKHOUSE, ELASTICSEARCH,
        OPENAPI, HTTP, CLIENT, COSEC, OBSERVABILITY, SPRING_BOOT, COSID, COCACHE_API, COCACHE,
        SPRING_CLOUD, MODELS, MIGRATE_DATA, COMPILER, GRADLE_PLUGIN, MANIFEST_TOOLS,
    ) + TEST_MODULES + PLATFORM_MODULES

    /** The eShop's five contexts (decision D158), each an `-api`, a `-domain` and a `-server` project. */
    val ESHOP_CONTEXTS = listOf("catalog", "basket", "ordering", "inventory", "payment")

    /** The eShop (decision D159): `:eshop-<context>-{api,domain,server}` in examples/eshop/<context>, and `:eshop-e2e`. */
    val ESHOP = ESHOP_CONTEXTS.flatMap { context ->
        listOf("api", "domain", "server").map { WowProject("eshop-$context-$it", "examples/eshop/$context/$context-$it") }
    } + WowProject("eshop-e2e", "examples/eshop/e2e")

    /**
     * The quickstart (decision D217): the sample of the quickstart page, one module that is both the domain and the
     * server of a tiny service.
     */
    val QUICKSTART = WowProject("quickstart", "examples/quickstart")

    /** The Java smoke test (decision D229 G-8): a domain module written in Java, built on the kernel only. */
    val JAVA_SMOKE = WowProject("java-smoke", "examples/java-smoke")

    /**
     * The examples, the eShop (decisions D159, D204), the quickstart (D217) and the Java smoke test (D229 G-8):
     * applications of the Wow modules, not ones of them, and the end-to-end tests across them.
     */
    val EXAMPLES = ESHOP + QUICKSTART + JAVA_SMOKE

    /**
     * The projects that are neither modules, platform services nor examples (review R2.5-7): the BOM, these
     * rules, the spikes H2 and H6, the Wow side of the end-to-end benchmark (decision D224) and the Wow 10 host of
     * the blue-green migration rehearsal (roadmap 8c.1), and a third-party store on the public SPI alone. Spike H3, `:kernel-benchmarks`, ran the 9.x kernel beside
     * the Wow 10 one and left with the 9.x code (decision D262).
     */
    val TOOLS = listOf(
        WowProject("bom", "bom/bom"),
        WowProject("architecture", "test/architecture"),
        WowProject("routing-spike", "test/routing-spike"),
        WowProject("lease-spike", "test/lease-spike"),
        WowProject("benchmark-server", "test/benchmarks/server"),
        WowProject("benchmark-search-server", "test/benchmarks/search-server"),
        WowProject("migration-rehearsal", "test/migration-rehearsal"),
        WowProject("spi-consumer", "test/spi-consumer"),
    )

    /**
     * Every project of the settings' `//region wow`, each in one group whose rules apply to it (review R2.5-7): a
     * project in none would escape every rule of the module graph.
     */
    val WOW_PROJECTS: List<WowProject> = MODULES + PLATFORM_SERVERS + EXAMPLES + TOOLS

    /** The project every other one may build on: the dependency versions, which every published POM imports (D87). */
    val SHARED_9X: Set<String> = setOf("wow-dependencies")

    /** Whether [project] belongs to an example ([EXAMPLES]). */
    fun isExample(project: String): Boolean =
        project.startsWith("eshop-") || project == QUICKSTART.name || project == JAVA_SMOKE.name

    /**
     * Whether [project] is one of the examples' domain or API modules: an example project that is neither a server
     * (`*-server`), end-to-end tests (`*-e2e`) nor the quickstart, whose one module is its server too. Such a module is
     * what an application's domain is, and builds on the kernel only (decision D149).
     */
    fun isExampleDomain(project: String): Boolean =
        isExample(project) && !project.endsWith("-server") && !project.endsWith("-e2e") && project != QUICKSTART.name

    /**
     * The modules an application runs with; the compiler, the Gradle plugin and its tools run in the build only, the
     * test modules in its tests only.
     */
    val RUNTIME_MODULES = MODULES - COMPILER - GRADLE_PLUGIN - MANIFEST_TOOLS - TEST_MODULES.toSet()

    /**
     * The project dependencies each module may declare (06 §3): every module of [MODULES] has its entry, and
     * none names a 9.x module ([nineXReachViolations] checks the transitive graph too).
     */
    val ALLOWED_PROJECT_DEPENDENCIES: Map<WowProject, Set<String>> = mapOf(
        // The stability markers (decision D282) every module below builds on, through these three.
        ANNOTATIONS to emptySet(),
        // The query protocol of the QueryApi (decision D260).
        API to setOf("annotations", "query-api"),
        // api for the Retry of the error catalog, whose problems are the programming model's (decision D366 ②); its tests
        // hold the rest of the contract's vocabulary to the programming model's (decision D294).
        MANIFEST to setOf("annotations", "api"),
        QUERY_API to setOf("annotations"),
        // query-api: the query protocol whose JSON :spi holds (decision D293).
        SPI to setOf("api", "query-api"),
        KERNEL to setOf("spi"),
        // manifest: the node is built from the Manifest (decision D231 ③).
        RUNTIME to setOf("kernel", "manifest"),
        // The kernel only: a domain module with Reactor handlers depends on the kernel and this (decision D149).
        REACTOR to setOf("kernel"),
        // The query engine and its backend SPI (decision D126).
        QUERY to setOf("query-api", "spi", "manifest"),
        // query for the snapshot query backends (decision D126).
        POSTGRES to setOf("spi", "query"),
        MONGO to setOf("spi", "query"),
        KAFKA to setOf("spi"),
        // query for the dataset backend (decision D142).
        CLICKHOUSE to setOf("spi", "query"),
        // query for the snapshot and events query backends (decisions D131, D235).
        ELASTICSEARCH to setOf("spi", "query"),
        // The Manifest only (roadmap 5a.2); the eShop's domains only for the snapshot tests, which document its services.
        OPENAPI to setOf("manifest") + ESHOP_CONTEXTS.map { "eshop-$it-domain" },
        // query for the query routes (06 §3, decisions D118, D127); openapi for GET /wow/openapi; client for the tests
        // only, which call the routes as a peer does (the main code shares only `:api`'s wire grammar, decision D286:
        // [HTTP_IGNORES_CLIENT]).
        HTTP to setOf("runtime", "query", "openapi", "client"),
        // Wow's contracts and its ports, no engine (decision D293): the wire grammar and read API of :api, the peer
        // contracts of :manifest, the query protocol and :spi's JSON, ids and saga call port.
        CLIENT to setOf("api", "manifest", "query-api", "spi"),
        COSEC to setOf("http"),
        // The core's observation seams (decision D174) and the query engine's observer; http for the tests only, which
        // follow a trace from an HTTP request.
        OBSERVABILITY to setOf("runtime", "query", "http"),
        // postgres and mongo compileOnly: the backend follows the application's classpath (decision D110).
        // query, kafka, clickhouse, cosec and observability compileOnly too: each is wired when the application has it
        // (cosec as kafka is, review R5.C-12). openapi for the springdoc bridge (decision D233). elasticsearch for the
        // integration tests only, which run the store combinations with it (DG-6).
        SPRING_BOOT to setOf(
            "runtime",
            "http",
            "client",
            "cosec",
            "openapi",
            "postgres",
            "mongo",
            "query",
            "kafka",
            "clickhouse",
            "observability",
            "elasticsearch",
        ),
        // CosId's generators as the node's (decision D171): the id SPIs only; it wires itself before the host. The host
        // only for the tests, which generate the module's configuration metadata and check the host accepts its keys.
        COSID to setOf("spi", "spring-boot"),
        // The cache of the state views a domain injects (decision D364): no dependency, so a domain module that
        // depends on it pulls in neither CoCache nor Redis.
        COCACHE_API to emptySet(),
        // The views' cache through CoCache: the owner's sink subscriptions run on the runtime, the misses read
        // through the node's typed queries, the views are found in the peer contracts (manifest). The host only for
        // the tests, which generate the module's configuration metadata and check the host accepts its keys.
        COCACHE to setOf("cocache-api", "manifest", "runtime", "spring-boot"),
        // The peer discovery port and the client's endpoints (decision D396); the runtime's node, compileOnly, for the
        // address a registration carries. The host, the quickstart and the eShop's catalog domain only for the tests,
        // which start nodes of the bank service that register in a registry and a catalog node that finds them there.
        SPRING_CLOUD to setOf("spi", "client", "runtime", "spring-boot", "quickstart", "eshop-catalog-domain"),
        // Domain code's API only (decision D187); the compiler only for the tests, which compile a sample tree.
        MODELS to setOf("api", "compiler"),
        // The event log on either first-class backend, which the import writes to (roadmap 8b.1); runtime for the
        // node's assembly of a service's Manifest from its modules' fragments on the class path, and for the tests,
        // which list and retry the imported parked entries as the ops routes do (roadmap 8b.4).
        MIGRATE_DATA to setOf("kernel", "postgres", "mongo", "runtime"),
        // kernel and reactor only for the tests, which compile the generated models.
        COMPILER to setOf("api", "manifest", "kernel", "reactor"),
        // Only for the TestKit fixture and the tests: the plugin's own code depends on no Wow module, its tools run in
        // workers over wowTools (D363 ⑤), so the build can include it (build-logic/wow-gradle-plugin).
        GRADLE_PLUGIN to setOf("manifest", "manifest-tools", "kernel", "compiler", "wow-dependencies"),
        // spi for the state fingerprint (StateJson); kernel only for the tests, which check that the fingerprint it writes
        // is the one the kernel checks a snapshot against (finding R5.A-19).
        // openapi for generateWowOpenApi's document.
        MANIFEST_TOOLS to setOf("manifest", "spi", "query-api", "kernel", "openapi"),
        // The test DSL runs a domain on the kernel, and only on it (06 §1).
        TEST_DSL to setOf("kernel"),
        // The test DSL's specs over the query engine: the test DSL stays on the kernel, and this module bridges them.
        TEST_QUERY to setOf("test-dsl", "query"),
        // A test's application on the host: the test DSL stays on the kernel, and this kit starts what the host runs.
        // quickstart for the tests only: a real domain the typed clients send to ([ALLOWED_MAIN_PROJECT_DEPENDENCIES]).
        TEST_SPRING_BOOT to setOf("spring-boot", "test-dsl", "quickstart"),
        // The contract specs test the ports, and only them (06 §1).
        TCK to setOf("spi"),
        // The query backend specs test the query engine's backend SPI, and only it.
        QUERY_TCK to setOf("query"),
        TCK_CONTAINERS to emptySet(),
        // A platform domain is an application's domain (decision D149): its API on wow-api, its domain on the kernel,
        // compiled by the compiler; never the runtime. The ops domain declares its state's times to the query engine
        // (`@QueryTemporal`, roadmap 7.1c), as the eShop's catalog declares its indexes.
        OPS_API to setOf("api"),
        OPS_DOMAIN to setOf("ops-api", "kernel", "compiler", "query-api"),
        VIEW_STORE_API to setOf("api"),
        VIEW_STORE_DOMAIN to setOf("view-store-api", "kernel", "compiler"),
        // The sources' sensitivity is the query engine's `SensitivityLevel`, which a dataset's masks are compiled from.
        ANALYTICS_CATALOG_API to setOf("api", "query-api"),
        ANALYTICS_CATALOG_DOMAIN to setOf("analytics-catalog-api", "kernel", "compiler", "query-api"),
    )

    /**
     * The build-time processors a platform service may run on its own code: the compiler, which lists its service
     * routes in the Manifest and binds them (decision D359). A processor is no runtime dependency of the service.
     */
    private val SERVER_PROCESSORS: Set<String> = setOf("compiler")

    /**
     * The project dependencies each platform service may declare: its domain, the host and the backends it runs on
     * (decision D110: MongoDB, the default of deploy/, and PostgreSQL), CoSec and OpenTelemetry for its production shape (roadmap P.15), the query module's test
     * fixtures for its tests, and the compiler as its build-time processor ([SERVER_PROCESSORS]).
     */
    val ALLOWED_SERVER_DEPENDENCIES: Map<WowProject, Set<String>> = mapOf(
        OPS_SERVER to setOf("ops-domain", "spring-boot", "kafka", "mongo", "postgres", "query", "cosec", "observability"),
        VIEW_STORE_SERVER to setOf("view-store-domain", "spring-boot", "mongo", "postgres", "query", "cosec", "observability"),
        // ClickHouse: the warehouse its catalog reads (decision D241), whose client the host builds.
        ANALYTICS_CATALOG_SERVER to setOf(
            "analytics-catalog-domain", "spring-boot", "mongo", "postgres", "clickhouse", "query", "cosec", "observability",
        ),
    ).mapValues { (_, allowed) -> allowed + SERVER_PROCESSORS }

    /**
     * The platform's services reach the Wow modules and the platform's own projects only (decision D146), directly or
     * transitively, as the example does: never a 9.x module or a spike. The eShop's projects are reached only through
     * the snapshot tests of `:openapi` ([ALLOWED_PROJECT_DEPENDENCIES]); a service's own dependencies, which
     * [ALLOWED_SERVER_DEPENDENCIES] holds, name none of them.
     */
    fun platformOutsideWowViolations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        val allowed = (MODULES + PLATFORM_SERVERS).map { it.name }.toSet()
        return PLATFORM_SERVERS.map { it.name }.flatMap { server ->
            reachable(graph, server).filterNot { it in allowed || isExample(it) }.sorted()
                .map { "$server reaches :$it" }
        }
    }

    private val PROJECT_DEPENDENCY = Regex("""project\(\s*":([\w-]+)"\s*\)""")

    /** A Wow module declared by its coordinates, as a user's build does (D363 ⑤): `"me.ahoo.wow:wow-kernel"`. */
    private val WOW_COORDINATES = Regex(""""me\.ahoo\.wow:wow-([\w-]+)"""")

    /** The projects whose artifactId is not `wow-<name>` (the root build's `artifactIds`), by artifact. */
    private val PROJECT_OF_ARTIFACT = mapOf("test" to "test-dsl")

    /** An api module's pairing with a domain module, whose contract it carries: no dependency of its code. */
    private val CONTRACT_PAIRING = Regex("""\bwowContract\(\s*project\(\s*":[\w-]+"\s*\)\s*\)""")

    /**
     * The dependencies of a build script as the rules read them: a Wow module declared by its coordinates as the
     * project the root build substitutes for it, and an api module's [CONTRACT_PAIRING] left out.
     */
    private fun dependenciesOf(buildScript: File): String =
        buildScript.readText().replace(CONTRACT_PAIRING, "").replace(WOW_COORDINATES) { coordinates ->
            val artifact = coordinates.groupValues[1]
            "project(\":${PROJECT_OF_ARTIFACT[artifact] ?: artifact}\")"
        }

    /** A test module declared in a test configuration (`testImplementation`, `integrationTestImplementation`, ...). */
    private val TEST_SCOPED_TEST_MODULE = Regex(
        """\b(?:test\w*|\w+Test\w*)\(\s*project\(\s*":(?:${TEST_MODULES.joinToString("|") { it.name }})"\s*\)\s*\)""",
    )

    /**
     * The Manifest model declared in a test configuration: a domain's tests read the Manifest fragment the compiler
     * writes, which the kernel does not bring (decision D231). Every module whose main code reads a Manifest declares
     * `:manifest` in its main dependencies.
     */
    private val TEST_SCOPED_MANIFEST = Regex(
        """\b(?:test\w*|\w+Test\w*)\(\s*project\(\s*":${MANIFEST.name}"\s*\)\s*\)""",
    )

    /**
     * The project dependencies some modules' main code may declare, narrower than [ALLOWED_PROJECT_DEPENDENCIES], which
     * also covers their tests: the server's main code does not build on its client (decision D286).
     */
    val ALLOWED_MAIN_PROJECT_DEPENDENCIES: Map<WowProject, Set<String>> = mapOf(
        HTTP to setOf("runtime", "query", "openapi"),
        // The TestKit fixture's class paths alone: no Wow module reaches the plugin's own class path.
        GRADLE_PLUGIN to setOf("kernel", "compiler", "manifest-tools", "wow-dependencies"),
        // The test kit's published POM names no example: the quickstart is its tests' alone.
        TEST_SPRING_BOOT to setOf("spring-boot", "test-dsl"),
        // A non-Wow application takes the module for its `lb://` endpoints: its POM names neither the host nor a domain.
        SPRING_CLOUD to setOf("spi", "client", "runtime"),
    )

    private val CONFIGURED_PROJECT_DEPENDENCY =
        Regex("""\b(\w+)\(\s*(?:testFixtures\(\s*)?project\(\s*":([\w-]+)"\s*\)""")
    private val TEST_CONFIGURATION = Regex("""test\w*|\w+Test\w*""")

    /** The projects [module]'s build script depends on in a configuration of its main code, not of its tests. */
    fun mainProjectDependencies(repositoryRoot: File, module: WowProject): Set<String> =
        CONFIGURED_PROJECT_DEPENDENCY.findAll(dependenciesOf(File(repositoryRoot, "${module.dir}/build.gradle.kts")))
            .filterNot { TEST_CONFIGURATION.matches(it.groupValues[1]) }
            .map { it.groupValues[2] }
            .toSet()

    /**
     * The projects [module]'s build script depends on, in any configuration but for a test module its tests use
     * ([TEST_MODULES]) and the Manifest in a test configuration ([TEST_SCOPED_MANIFEST]):
     * those are allowed to every module.
     */
    fun projectDependencies(repositoryRoot: File, module: WowProject): Set<String> =
        PROJECT_DEPENDENCY.findAll(
            dependenciesOf(File(repositoryRoot, "${module.dir}/build.gradle.kts"))
                .replace(TEST_SCOPED_TEST_MODULE, "")
                .replace(TEST_SCOPED_MANIFEST, ""),
        )
            .map { it.groupValues[1] }
            .toSet()

    /**
     * The 9.x projects deleted from `main` (decisions D244, D262; they live on at `v10-9x-baseline` and on `v9`): the
     * `wow-*` modules, the 9.x test modules, platforms, examples and benchmarks, and spike H3, which ran the 9.x
     * kernel. No project of these names comes back, and no build script depends on one ([deletedNineXViolations]).
     */
    val DELETED_9X: Set<String> = setOf(
        "wow-bom", "wow-api", "wow-metadata", "wow-rest-contract", "wow-core", "wow-openapi", "wow-cocache",
        "wow-apiclient", "wow-compiler", "wow-kafka", "wow-mongo", "wow-redis", "wow-elasticsearch", "wow-spring",
        "wow-webflux", "wow-spring-boot-starter", "wow-observability", "wow-bi", "wow-clickhouse", "wow-query",
        "wow-models", "wow-schema", "wow-cosec", "wow-benchmarks",
        "wow-test", "wow-tck", "wow-mock", "wow-it",
        "wow-compensation-api", "wow-compensation-core", "wow-compensation-domain", "wow-compensation-server",
        "example-api", "example-domain", "example-server",
        "example-transfer-api", "example-transfer-domain", "example-transfer-server",
        "wow-view-store-api", "wow-view-store-domain", "wow-view-store-starter", "wow-view-store-server",
        "wow-dataset-registry-api", "wow-dataset-registry-domain", "wow-dataset-registry-starter",
        "wow-analytics-server", "e2e-bench-9x", "kernel-benchmarks",
    )

    private val SETTINGS_INCLUDE = Regex("""include\(\s*"(:?)([\w-]+)"\s*\)""")
    private val SETTINGS_PROJECT_DIR = Regex("""project\(":([\w-]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")

    /** Every project `settings.gradle.kts` includes, with its directory relative to the repository root. */
    fun settingsProjects(repositoryRoot: File): Map<String, String> {
        val settings = File(repositoryRoot, "settings.gradle.kts").readText()
        val dirs = SETTINGS_PROJECT_DIR.findAll(settings).associate { it.groupValues[1] to it.groupValues[2] }
        return SETTINGS_INCLUDE.findAll(settings)
            .map { it.groupValues[2] }
            .associateWith { dirs[it] ?: it }
    }

    /** The project dependencies every project's build script declares, in any configuration. */
    fun projectGraph(repositoryRoot: File): Map<String, Set<String>> =
        settingsProjects(repositoryRoot).mapValues { (_, dir) ->
            val buildScript = File(repositoryRoot, "$dir/build.gradle.kts")
            if (buildScript.isFile) {
                PROJECT_DEPENDENCY.findAll(dependenciesOf(buildScript)).map { it.groupValues[1] }.toSet()
            } else {
                emptySet()
            }
        }

    /** [project] and every project it reaches through [graph]. */
    fun reachable(graph: Map<String, Set<String>>, project: String): Set<String> {
        val reached = linkedSetOf(project)
        val pending = ArrayDeque(listOf(project))
        while (pending.isNotEmpty()) {
            graph[pending.removeFirst()].orEmpty().filter { reached.add(it) }.forEach { pending.addLast(it) }
        }
        return reached
    }

    /**
     * The settings include no deleted 9.x project ([DELETED_9X]) and no build script depends on one; each violation
     * reads `settings.gradle.kts: :<project>` or `<build script>: :<project>`.
     */
    fun deletedNineXViolations(repositoryRoot: File): List<String> {
        val included = settingsProjects(repositoryRoot)
        val includes = included.keys.filter { it in DELETED_9X }.sorted().map { "settings.gradle.kts: :$it" }
        val dependencies = included.toSortedMap().flatMap { (_, dir) ->
            val buildScript = File(repositoryRoot, "$dir/build.gradle.kts")
            if (buildScript.isFile) {
                PROJECT_DEPENDENCY.findAll(buildScript.readText()).map { it.groupValues[1] }
                    .filter { it in DELETED_9X }.distinct().sorted().map { "$dir/build.gradle.kts: :$it" }.toList()
            } else {
                emptyList()
            }
        }
        return includes + dependencies
    }

    /**
     * No project reaches one outside the settings' `//region wow` (decision D85; review R2.5-7), directly or
     * transitively, but the shared dependency versions ([SHARED_9X]).
     */
    fun nineXReachViolations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        val wow = wowSettingsProjects(repositoryRoot)
        return wow.sorted()
            .flatMap { project ->
                reachable(graph, project).filterNot { it in wow || it in SHARED_9X }.sorted()
                    .map { "$project reaches :$it" }
            }
    }

    private val WOW_REGION = Regex("""(?ms)^//region wow\b(.*?)^//endregion""")

    /** The projects `settings.gradle.kts` includes in its `//region wow`: the modules, platform services, examples, spikes and tests. */
    fun wowSettingsProjects(repositoryRoot: File): Set<String> {
        val settings = File(repositoryRoot, "settings.gradle.kts").readText()
        val region = requireNotNull(WOW_REGION.find(settings)) { "settings.gradle.kts has no //region wow." }
        return SETTINGS_INCLUDE.findAll(region.groupValues[1]).map { it.groupValues[2] }.toSet()
    }

    /**
     * The example builds on the Wow modules only (decisions D118, D159), directly or transitively: it shows Wow as an
     * application uses it, with no 9.x module, spike or test project on its classpath. Its projects, `:eshop-*`
     * ([isExample]), may build on each other, and its end-to-end tests run the platform's services
     * ([PLATFORM_SERVERS]) beside its own: the ops service shows the eShop's parked entries.
     */
    fun exampleOutsideWowViolations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        val examples = graph.keys.filter(::isExample).sorted()
        val modules = (MODULES + PLATFORM_SERVERS).map { it.name }.toSet()
        return examples.flatMap { example ->
            val allowed = modules + examples
            reachable(graph, example).filterNot { it in allowed }.sorted().map { "$example reaches :$it" }
        }
    }

    /**
     * The example's domain and API modules ([isExampleDomain]) do not reach `:runtime` (decision D149), directly or
     * transitively: a domain depends on the kernel, and only a server brings the runtime that runs it.
     */
    fun exampleDomainOnRuntimeViolations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        return graph.keys.filter(::isExampleDomain).sorted()
            .filter { RUNTIME.name in reachable(graph, it) }
            .map { "$it reaches :${RUNTIME.name}" }
    }

    private val COMPATIBILITY_MARKER = Regex("""@Deprecated\b|\bcompat\(""")

    /**
     * Wow keeps no compatibility code (decision D1): no `@Deprecated` declaration and no `compat(...)` marker in
     * any module's production sources. History is handled once, in the migration layer.
     */
    fun compatibilityMarkers(repositoryRoot: File): List<String> =
        productionSources(repositoryRoot).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (COMPATIBILITY_MARKER.containsMatchIn(line)) {
                    "${file.relativeTo(repositoryRoot).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

    /** The Kotlin and Java production sources of [MODULES]. */
    internal fun productionSources(repositoryRoot: File): List<File> =
        MODULES.flatMap { module ->
            listOf("src/main/kotlin", "src/main/java")
                .map { File(repositoryRoot, "${module.dir}/$it") }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
        }
}

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
    val OPENTELEMETRY = WowProject("opentelemetry", "edge/opentelemetry")
    val SPRING_BOOT = WowProject("spring-boot", "host/spring-boot")
    val COSID = WowProject("cosid", "extensions/cosid")

    /** 9.x wow-models ported (decision D187): capability interfaces and the reusable Tree aggregate. */
    val MODELS = WowProject("models", "extensions/models")
    val MIGRATE_DATA = WowProject("migrate-data", "migration/migrate-data")
    val COMPILER = WowProject("compiler", "compiler/compiler")
    val GRADLE_PLUGIN = WowProject("gradle-plugin", "compiler/gradle-plugin")
    val MANIFEST_TOOLS = WowProject("manifest-tools", "compiler/manifest-tools")

    /** The test DSL (artifact `wow-test`; a project named `:test` would clash with the `test` task, decision D87). */
    val TEST_DSL = WowProject("test-dsl", "test/test-dsl")

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
    val TEST_MODULES = listOf(TEST_DSL, TCK, QUERY_TCK, TCK_CONTAINERS)

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
        OPENAPI, HTTP, CLIENT, COSEC, OPENTELEMETRY, SPRING_BOOT, COSID, MODELS, MIGRATE_DATA, COMPILER, GRADLE_PLUGIN, MANIFEST_TOOLS,
    ) + TEST_MODULES + PLATFORM_MODULES

    /** The eShop's five contexts (decision D158), each an `-api`, a `-domain` and a `-server` project. */
    val ESHOP_CONTEXTS = listOf("catalog", "basket", "ordering", "inventory", "payment")

    /** The eShop (decision D159): `:eshop-<context>-{api,domain,server}` in example/eshop/<context>, and `:eshop-e2e`. */
    val ESHOP = ESHOP_CONTEXTS.flatMap { context ->
        listOf("api", "domain", "server").map { WowProject("eshop-$context-$it", "example/eshop/$context/$context-$it") }
    } + WowProject("eshop-e2e", "example/eshop/e2e")

    /**
     * The quickstart (decision D217): the sample of the quickstart page, one module that is both the domain and the
     * server of a tiny service.
     */
    val QUICKSTART = WowProject("quickstart", "example/quickstart")

    /** The Java smoke test (decision D229 G-8): a domain module written in Java, built on the kernel only. */
    val JAVA_SMOKE = WowProject("java-smoke", "example/java-smoke")

    /**
     * The examples, the eShop (decisions D159, D204), the quickstart (D217) and the Java smoke test (D229 G-8):
     * applications of the Wow modules, not ones of them, and the end-to-end tests across them.
     */
    val EXAMPLES = ESHOP + QUICKSTART + JAVA_SMOKE

    /** The [TOOLS] that are applications: they use Wow as a user does. */
    private val APPLICATION_TOOLS = setOf("benchmark-server", "migration-rehearsal")

    /**
     * The projects that are neither modules, platform services nor examples (review R2.5-7): the BOM, these
     * rules, the spikes H2 and H6, the Wow side of the end-to-end benchmark (decision D224) and the Wow 10 host of
     * the blue-green migration rehearsal (roadmap 8c.1). Spike H3, `:kernel-benchmarks`, ran the 9.x kernel beside
     * the Wow 10 one and left with the 9.x code (decision D262).
     */
    val TOOLS = listOf(
        WowProject("bom", "bom/bom"),
        WowProject("architecture", "test/architecture"),
        WowProject("routing-spike", "test/routing-spike"),
        WowProject("lease-spike", "test/lease-spike"),
        WowProject("benchmark-server", "test/benchmarks/server"),
        WowProject("migration-rehearsal", "test/migration-rehearsal"),
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
        MANIFEST to setOf("annotations"),
        QUERY_API to setOf("annotations"),
        SPI to setOf("api"),
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
        // The runtime for the calls' root context and the contracts (decision D234), query for the rows' records.
        CLIENT to setOf("runtime", "query"),
        COSEC to setOf("http"),
        // The core's observation seams (decision D174) and the query engine's observer; http for the tests only, which
        // follow a trace from an HTTP request.
        OPENTELEMETRY to setOf("runtime", "query", "http"),
        // postgres and mongo compileOnly: the backend follows the application's classpath (decision D110).
        // query, kafka, clickhouse and opentelemetry compileOnly too: each is wired when the application has it.
        // openapi for the springdoc bridge (decision D233).
        SPRING_BOOT to setOf(
            "runtime",
            "http",
            "client",
            "openapi",
            "postgres",
            "mongo",
            "query",
            "kafka",
            "clickhouse",
            "opentelemetry",
        ),
        // CosId's generators as the node's (decision D171): the id SPIs only; it wires itself before the host.
        COSID to setOf("spi"),
        // Domain code's API only (decision D187); the compiler only for the tests, which compile a sample tree.
        MODELS to setOf("api", "compiler"),
        // The event log on either first-class backend, which the import writes to (roadmap 8b.1); runtime only for the
        // tests, which list and resume the imported parked entries as the ops routes do (roadmap 8b.4).
        // elasticsearch only for the tags backfill's re-sink of an events index (decision D259).
        MIGRATE_DATA to setOf("kernel", "postgres", "mongo", "elasticsearch", "runtime"),
        // kernel and reactor only for the tests, which compile the generated models.
        COMPILER to setOf("api", "manifest", "kernel", "reactor"),
        // compiler, kernel, manifest-tools and wow-dependencies only for the TestKit fixture and tests; openapi for
        // generateWowOpenApi.
        GRADLE_PLUGIN to setOf("manifest", "openapi", "manifest-tools", "kernel", "compiler", "wow-dependencies"),
        // kernel for the state fingerprint.
        MANIFEST_TOOLS to setOf("manifest", "kernel", "query-api"),
        // The test DSL runs a domain on the kernel, and only on it (06 §1).
        TEST_DSL to setOf("kernel"),
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
     * The project dependencies each platform service may declare: its domain, the host and the backends it runs on
     * (decision D110), CoSec and OpenTelemetry for its production shape (roadmap P.15), and the query module's test
     * fixtures for its tests.
     */
    val ALLOWED_SERVER_DEPENDENCIES: Map<WowProject, Set<String>> = mapOf(
        OPS_SERVER to setOf("ops-domain", "spring-boot", "kafka", "postgres", "query", "cosec", "opentelemetry"),
        VIEW_STORE_SERVER to setOf("view-store-domain", "spring-boot", "postgres", "query", "cosec", "opentelemetry"),
        // ClickHouse: the warehouse its catalog reads (decision D241), whose client the host builds.
        ANALYTICS_CATALOG_SERVER to setOf(
            "analytics-catalog-domain",
            "spring-boot",
            "postgres",
            "clickhouse",
            "query",
            "cosec",
            "opentelemetry",
        ),
    )

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

    private val SPRING = listOf("org.springframework")
    private val JSON_LIBRARIES = listOf(
        "tools.jackson",
        "com.fasterxml",
        "kotlinx.serialization",
        "com.google.gson",
        "com.squareup.moshi",
        "org.json",
        "jakarta.json",
        "javax.json",
    )
    private val REACTOR_TYPES = listOf(
        "reactor.core",
        "reactor.util",
        "reactor.kotlin",
        "reactor.netty",
        "kotlinx.coroutines.reactor",
        "kotlinx.coroutines.reactive",
        "org.reactivestreams",
    )
    private val HTTP_TYPES = listOf("jakarta.servlet", "io.swagger")
    private val DRIVERS = listOf(
        "com.mongodb",
        "org.bson",
        "org.postgresql",
        "io.r2dbc",
        "io.vertx",
        "org.apache.kafka",
        "co.elastic",
        "org.elasticsearch",
        "com.clickhouse",
        "io.lettuce",
    )

    /** The contract and core modules carry no runtime framework: no Spring, Reactor, HTTP or driver types. */
    val CORE_IS_FRAMEWORK_FREE = DependencyRule(
        name = "contract and core modules import no Spring, Reactor, HTTP or driver types",
        modules = listOf(MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME, QUERY, OPENAPI).map { it.dir },
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS,
    )

    /**
     * wow-client is usable without Spring (decisions D257, D259): it speaks HTTP through the JDK's HttpClient, with no
     * Spring, Reactor, servlet or driver type.
     */
    val CLIENT_IS_FRAMEWORK_FREE = DependencyRule(
        name = "wow-client imports no Spring, Reactor, HTTP framework or driver types",
        modules = listOf(CLIENT.dir),
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS,
    )

    /**
     * The server does not build on its client (decision D286): the wire grammar both ends share, the Wow headers,
     * `Wow-Wait`, `Wow-Scope` and `Wow-Call`'s framing, and the service credential are `:api`'s (`me.ahoo.wow.api.wire`,
     * `me.ahoo.wow.api.client`).
     */
    val HTTP_IGNORES_CLIENT = DependencyRule(
        name = "wow-http imports nothing from wow-client",
        modules = listOf(HTTP.dir),
        forbidden = listOf("me.ahoo.wow.client"),
    )

    /** Nor does the client build on the server: it speaks the routes' wire grammar from `:api` (decision D286). */
    val CLIENT_IGNORES_HTTP = DependencyRule(
        name = "wow-client imports nothing from wow-http",
        modules = listOf(CLIENT.dir),
        forbidden = listOf("me.ahoo.wow.http"),
    )

    /**
     * The read side's scope authorization has one home, `:query`'s `QueryTargets` (review R5.C-1): which scope a
     * caller may name (`Caller.crossScope`), the cross-tenant switch and the fixed tenants. wow-http passes on what a
     * request names; it reads none of the members those rules rest on, and builds no `QueryCaller` itself: its callers
     * come from `QueryTargets.of`.
     */
    val HTTP_DECIDES_NO_READ_SCOPE = SourceTokenRule(
        name = "wow-http reads no crossScope, decides no tenant and builds no QueryCaller",
        modules = listOf(HTTP.dir),
        forbidden = listOf("crossScope", "fixedTenant", "tenantOf", "QueryCaller("),
    )

    val SOURCE_TOKEN_RULES = listOf(HTTP_DECIDES_NO_READ_SCOPE)

    /** The kernel knows nothing of clusters, subscriptions or waits: those are the runtime's. */
    val KERNEL_IGNORES_RUNTIME = DependencyRule(
        name = "wow-kernel imports nothing from the runtime",
        modules = listOf(KERNEL.dir),
        forbidden = listOf("me.ahoo.wow.runtime"),
    )

    /** The packages of wow-http's read routes: the query routes and the state reads. */
    private val HTTP_READ_PACKAGES = setOf("me.ahoo.wow.http.query", "me.ahoo.wow.http.read")

    /**
     * wow-http only adapts the read routes to HTTP (decision D231): the query semantics are wow-query's (the events
     * model, the state and dataset visibility) and the state point reads are the runtime's (`StateReads`), so every
     * entry shares one visibility and masking. Its read packages read neither the log, the snapshot store nor the
     * kernel's state replay.
     */
    val HTTP_READS_ADAPT_ONLY = DependencyRule(
        name = "wow-http's query and read packages import no log, snapshot store or kernel",
        modules = listOf(HTTP.dir),
        forbidden = listOf("me.ahoo.wow.spi.log", "me.ahoo.wow.spi.snapshot", "me.ahoo.wow.kernel"),
        appliesToPackage = { it in HTTP_READ_PACKAGES },
    )

    /**
     * The read side is composed once, in wow-query (decision D237, review R3.1-1): the Spring Boot host maps its
     * properties and the application's beans onto `QueryEngine.Builder` and decides no query policy itself, so a host
     * that is not Spring Boot (A10) cannot miss one. Its main code constructs no query gateway, policy, policy gateway
     * or visibility.
     */
    val HOST_DECIDES_NO_QUERY_POLICY = DependencyRule(
        name = "wow-spring-boot composes no query gateway, policy or visibility: QueryEngine.Builder does",
        modules = listOf(SPRING_BOOT.dir),
        forbidden = listOf(
            "me.ahoo.wow.query.AbacQueryPolicy",
            "me.ahoo.wow.query.ScopeQueryPolicy",
            "me.ahoo.wow.query.QueryOwnership",
            "me.ahoo.wow.query.DefaultQueryGateway",
            "me.ahoo.wow.query.state.SnapshotPolicyGateway",
            "me.ahoo.wow.query.state.StateVisibility",
            "me.ahoo.wow.query.events.EventVisibility",
            "me.ahoo.wow.query.events.EventQueryGateways",
        ),
    )

    /**
     * Every entry reads an aggregate's state through one path (decision D237): `ReadFacade` (read, visibility, masks)
     * of wow-query, which the query engine composes. An entry (HTTP, CoSec, the host, the runtime's call and ops paths)
     * never admits or hides a state itself, so no entry can read a state past its policies or unmasked.
     */
    val ENTRIES_READ_THROUGH_FACADE = DependencyRule(
        name = "the entries admit a state only through wow-query's ReadFacade",
        modules = listOf(RUNTIME, HTTP, COSEC, OPENTELEMETRY, SPRING_BOOT).map { it.dir },
        forbidden = listOf("me.ahoo.wow.query.state.StateVisibility", "me.ahoo.wow.query.events.EventVisibility"),
    )

    /**
     * Observability is wired at fixed seams (decision D174, 06 §3): only wow-opentelemetry and the host that wires it
     * import OpenTelemetry or Micrometer; the core exposes ports instead.
     */
    val ONLY_OPENTELEMETRY_OBSERVES = DependencyRule(
        name = "only wow-opentelemetry and wow-spring-boot import OpenTelemetry or Micrometer",
        modules = (MODULES - OPENTELEMETRY - SPRING_BOOT).map { it.dir },
        forbidden = listOf("io.opentelemetry", "io.micrometer"),
    )

    /** Only the host depends on Spring Boot. */
    val ONLY_HOST_USES_SPRING_BOOT = DependencyRule(
        name = "only wow-spring-boot imports Spring Boot",
        modules = (MODULES - SPRING_BOOT).map { it.dir },
        forbidden = listOf("org.springframework.boot"),
    )

    /** The Manifest is plain data (06 §3): JSON libraries read and write its tree, it imports none. */
    val MANIFEST_IS_PLAIN_DATA = DependencyRule(
        name = "wow-manifest imports no JSON library and no coroutines",
        modules = listOf(MANIFEST.dir),
        forbidden = listOf("tools.jackson", "com.fasterxml", "kotlinx"),
    )

    /**
     * The query protocol is plain data (decision D125): JSON libraries read and write it (`QueryJson` in wow-query), it
     * imports none.
     */
    val QUERY_API_IS_PLAIN_DATA = DependencyRule(
        name = "wow-query-api imports no JSON library",
        modules = listOf(QUERY_API.dir),
        forbidden = JSON_LIBRARIES,
    )

    /** Schemas are introspected at build time only (decision D73): no runtime module generates one. */
    val SCHEMA_GENERATION_AT_BUILD_TIME = DependencyRule(
        name = "only wow-manifest-tools imports the JSON Schema generator",
        modules = RUNTIME_MODULES.map { it.dir },
        forbidden = listOf("com.github.victools"),
    )

    /** The 9.x parts of 9.x wow-api that the Wow 10 wow-api leaves out (06 §2.1; `query` moves to wow-query-api in M9). */
    private val API_9X = listOf(
        "abac", "command", "event", "exception", "messaging", "modeling", "naming", "query", "serialization",
        "Copyable", "Identifier", "Ordered", "Version", "Wow",
    ).map { "me.ahoo.wow.api.$it" }

    /**
     * wow-api depends only on the query protocol and the coroutines' `Flow` (06 §3, decisions D66, D260: the `QueryApi`
     * of D259 streams its rows) and does not build on the 9.x parts of 9.x wow-api, which go with 9.x. The query
     * protocol keeps 9.x's package name, `me.ahoo.wow.api.query`: only wow-query-api can supply it, the one module
     * wow-api depends on.
     */
    val API_STANDS_ALONE = DependencyRule(
        name = "wow-api imports no framework, no JSON library, no kotlinx but its Flow and no 9.x part of wow-api",
        modules = listOf(API.dir),
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS + listOf("tools.jackson", "com.fasterxml", "kotlinx") +
            API_9X,
        allowed = listOf("kotlinx.coroutines.flow", "me.ahoo.wow.api.query"),
    )

    /**
     * The test DSL and the TCK carry no framework and no container (decision D196): a domain's tests and an in-memory
     * backend run them without Spring, Reactor, a driver or Docker; the containers are [TCK_CONTAINERS]'s.
     */
    val TEST_MODULES_ARE_FRAMEWORK_FREE = DependencyRule(
        name = "wow-test and wow-tck import no Spring, Reactor, HTTP, driver or Testcontainers types",
        modules = listOf(TEST_DSL, TCK, QUERY_TCK).map { it.dir },
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS + listOf("org.testcontainers"),
    )

    val DEPENDENCY_RULES = listOf(
        CORE_IS_FRAMEWORK_FREE,
        CLIENT_IS_FRAMEWORK_FREE,
        TEST_MODULES_ARE_FRAMEWORK_FREE,
        KERNEL_IGNORES_RUNTIME,
        HTTP_IGNORES_CLIENT,
        CLIENT_IGNORES_HTTP,
        HTTP_READS_ADAPT_ONLY,
        HOST_DECIDES_NO_QUERY_POLICY,
        ENTRIES_READ_THROUGH_FACADE,
        ONLY_OPENTELEMETRY_OBSERVES,
        ONLY_HOST_USES_SPRING_BOOT,
        MANIFEST_IS_PLAIN_DATA,
        QUERY_API_IS_PLAIN_DATA,
        SCHEMA_GENERATION_AT_BUILD_TIME,
        API_STANDS_ALONE,
    )

    private val PROJECT_DEPENDENCY = Regex("""project\(\s*":([\w-]+)"\s*\)""")

    /** A test module declared in a test configuration (`testImplementation`, `integrationTestImplementation`, ...). */
    private val TEST_SCOPED_TEST_MODULE = Regex(
        """\b(?:test\w*|\w+Test\w*)\(\s*project\(\s*":(?:${TEST_MODULES.joinToString("|") { it.name }})"\s*\)\s*\)""",
    )

    /**
     * The Manifest model, or its test fixtures (its `copyWith` helpers, decision D59: the model has no `copy`), declared
     * in a test configuration: a domain's tests read the Manifest fragment the compiler writes, which the kernel does not
     * bring (decision D231). Every module whose main code reads a Manifest declares `:manifest` in its main
     * dependencies.
     */
    private val TEST_SCOPED_MANIFEST_FIXTURES = Regex(
        """\b(?:test\w*|\w+Test\w*)\(\s*(?:testFixtures\(\s*)?project\(\s*":${MANIFEST.name}"\s*\)\s*\)?\s*\)""",
    )

    /**
     * The project dependencies some modules' main code may declare, narrower than [ALLOWED_PROJECT_DEPENDENCIES], which
     * also covers their tests: the server's main code does not build on its client (decision D286).
     */
    val ALLOWED_MAIN_PROJECT_DEPENDENCIES: Map<WowProject, Set<String>> = mapOf(
        HTTP to setOf("runtime", "query", "openapi"),
    )

    private val CONFIGURED_PROJECT_DEPENDENCY =
        Regex("""\b(\w+)\(\s*(?:testFixtures\(\s*)?project\(\s*":([\w-]+)"\s*\)""")
    private val TEST_CONFIGURATION = Regex("""test\w*|\w+Test\w*""")

    /** The projects [module]'s build script depends on in a configuration of its main code, not of its tests. */
    fun mainProjectDependencies(repositoryRoot: File, module: WowProject): Set<String> =
        CONFIGURED_PROJECT_DEPENDENCY.findAll(File(repositoryRoot, "${module.dir}/build.gradle.kts").readText())
            .filterNot { TEST_CONFIGURATION.matches(it.groupValues[1]) }
            .map { it.groupValues[2] }
            .toSet()

    /**
     * The projects [module]'s build script depends on, in any configuration but for a test module its tests use
     * ([TEST_MODULES]) and the Manifest or its test fixtures in a test configuration ([TEST_SCOPED_MANIFEST_FIXTURES]):
     * those are allowed to every module.
     */
    fun projectDependencies(repositoryRoot: File, module: WowProject): Set<String> =
        PROJECT_DEPENDENCY.findAll(
            File(repositoryRoot, "${module.dir}/build.gradle.kts").readText()
                .replace(TEST_SCOPED_TEST_MODULE, "")
                .replace(TEST_SCOPED_MANIFEST_FIXTURES, ""),
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
        "wow-webflux", "wow-spring-boot-starter", "wow-opentelemetry", "wow-bi", "wow-clickhouse", "wow-query",
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
                PROJECT_DEPENDENCY.findAll(buildScript.readText()).map { it.groupValues[1] }.toSet()
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

    /**
     * The projects that use Wow as an application does (decision D216; `userProjects` of the root build): the
     * examples, the platform's domains and services, the end-to-end benchmark and the migration rehearsal's host. They
     * compile against the public API as a user does, so they never opt in to `InternalWowApi` (an `ERROR`-level
     * opt-in).
     */
    val USER_PROJECTS: List<WowProject> =
        EXAMPLES + listOf(OPS_DOMAIN, VIEW_STORE_DOMAIN) + PLATFORM_SERVERS + TOOLS.filter { it.name in APPLICATION_TOOLS }

    private val INTERNAL_OPT_IN = Regex("""\bInternalWowApi\b""")

    /**
     * The lines of [USER_PROJECTS]' build scripts and production sources that name `InternalWowApi` (review R2.5-7):
     * an `@OptIn(InternalWowApi::class)` or a compiler `optIn`, which would build an application on a declaration Wow
     * promises nothing about. Their tests may opt in, to build a fixture as a module does.
     */
    fun internalOptIns(repositoryRoot: File): List<String> =
        USER_PROJECTS.flatMap { module ->
            val build = File(repositoryRoot, "${module.dir}/build.gradle.kts")
            val sources = listOf("src/main/kotlin", "src/main/java")
                .map { File(repositoryRoot, "${module.dir}/$it") }
                .filter { it.isDirectory }
                .flatMap { root ->
                    root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                }
            (listOf(build).filter { it.isFile } + sources).flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (INTERNAL_OPT_IN.containsMatchIn(line)) {
                        "${file.relativeTo(repositoryRoot).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
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

    /**
     * The modules whose ABI holds no data class (decisions D216, D227, D228): a type users, backends or Wow's tools
     * construct may gain fields within a format (D59), and a data class's `copy` and `componentN` change their
     * signatures when it does.
     */
    val PLAIN_CLASS_MODULES: List<WowProject> = listOf(API, SPI, MANIFEST)

    /**
     * The data classes the ABI of [PLAIN_CLASS_MODULES] keeps, by their JVM names, each with why: closed value
     * types, whose fields are their identity, so a new field would make another type.
     */
    val DATA_CLASS_EXCEPTIONS: Map<String, String> = mapOf(
        "me/ahoo/wow/api/aggregate/AggregateType" to "an aggregate's identity: its context and name",
        "me/ahoo/wow/api/aggregate/AggregateRef" to "an aggregate instance's identity: its type, tenant and id",
        "me/ahoo/wow/api/aggregate/Scope" to "the owner and space dimensions of authorization (D56, D189)",
        "me/ahoo/wow/api/decide/Causation" to "the causal link of a command: its correlation and causation ids",
        "me/ahoo/wow/api/subscribe/ExecutionId" to "a subscription's execution: its name, aggregate and version",
        "me/ahoo/wow/spi/log/LogPosition" to "a position in the log: a partition and a sequence",
        "me/ahoo/wow/spi/transport/PartitionOffsets" to "a range of offsets: its start and end",
    )

    private val ABI_CLASS = Regex("""^public (?:final |abstract |open |sealed )*class ([\w/$]+)""")
    private val DATA_CLASS_MEMBER = Regex("""\bfun (?:copy|copy\${'$'}default|component\d+) \(""")

    /**
     * The `copy` and `componentN` members of the ABI dumps of [PLAIN_CLASS_MODULES] outside [DATA_CLASS_EXCEPTIONS],
     * as `dump: class: member`, and each exception the dumps no longer have a data class for (a stale entry).
     */
    fun dataClassMembers(repositoryRoot: File): List<String> {
        val dataClasses = mutableSetOf<String>()
        val violations = PLAIN_CLASS_MODULES.flatMap { module ->
            val dump = File(repositoryRoot, "${module.dir}/api/${module.name}.api")
            var owner = ""
            dump.readLines().mapNotNull { line ->
                ABI_CLASS.find(line)?.let { owner = it.groupValues[1] }
                if (DATA_CLASS_MEMBER.containsMatchIn(line)) {
                    dataClasses += owner
                    if (owner in DATA_CLASS_EXCEPTIONS) null else "${module.dir}/api/${module.name}.api: $owner: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        return violations + (DATA_CLASS_EXCEPTIONS.keys - dataClasses).map { "stale exception: $it" }
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

    /** A decision (`D254`) or a review finding (`R2.3-1`, `A5-14`, `Q-13`, `X-4`, `M-2`, `R4-Q`). */
    private val DECISION_OR_REVIEW_ID =
        Regex("""\bD\d{2,3}\b|\b(?:R\d(?:\.\d+)?-[A-Z0-9]+|A\d-\d+|Q-\d+|X-\d+|M-\d+)\b""")

    /**
     * The string literals of the modules' production sources that name a decision or a review finding. Such a string
     * reaches users, in an exception, a rejection, an error description, a configuration description or generated
     * code, and they cannot look the ID up: the string says the reason instead. Comments may cite IDs.
     */
    fun idsInStringLiterals(repositoryRoot: File): List<String> =
        productionSources(repositoryRoot).flatMap { file ->
            stringLiterals(file.readText()).filter { (_, text) -> DECISION_OR_REVIEW_ID.containsMatchIn(text) }
                .map { (line, text) -> "${file.relativeTo(repositoryRoot).invariantSeparatorsPath}:$line: \"$text\"" }
        }

    /**
     * The string literals of Kotlin or Java [source], each with the line it starts on: plain and raw strings, a
     * template's expression read as part of its string. Comments and character literals are skipped.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun stringLiterals(source: String): List<Pair<Int, String>> {
        val literals = ArrayList<Pair<Int, String>>()
        var line = 1
        var i = 0
        fun skipTo(end: Int) {
            line += (i until end).count { source[it] == '\n' }
            i = end
        }
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> skipTo(source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length)
                source.startsWith(
                    "/*",
                    i
                ) -> skipTo((source.indexOf("*/", i + 2).takeIf { it >= 0 } ?: source.length) + 2)
                source.startsWith("\"\"\"", i) -> {
                    var end = source.indexOf("\"\"\"", i + 3).takeIf { it >= 0 } ?: source.length
                    while (end + 3 < source.length && source[end + 3] == '"') end++
                    literals += line to source.substring(i + 3, end)
                    skipTo(minOf(end + 3, source.length))
                }
                source[i] == '"' -> {
                    var end = i + 1
                    while (end < source.length && source[end] != '"' && source[end] != '\n') {
                        end = when {
                            source[end] == '\\' -> end + 2
                            source.startsWith("\${", end) -> templateEnd(source, end + 2)
                            else -> end + 1
                        }
                    }
                    literals += line to source.substring(i + 1, minOf(end, source.length))
                    skipTo(minOf(end + 1, source.length))
                }
                source[i] == '\'' -> {
                    val end = source.indexOf('\'', if (source.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                    i = if (end in i + 2..i + CHAR_LITERAL_MAX) end + 1 else i + 1
                }
                else -> skipTo(i + 1)
            }
        }
        return literals
    }

    private const val CHAR_LITERAL_MAX = 8

    /** The index after the `}` that closes the template expression starting at [start]. */
    private fun templateEnd(source: String, start: Int): Int {
        var depth = 1
        var end = start
        while (end < source.length && depth > 0) {
            when (source[end]) {
                '{' -> depth++
                '}' -> depth--
            }
            end++
        }
        return end
    }

    /** The Kotlin and Java production sources of [MODULES]. */
    private fun productionSources(repositoryRoot: File): List<File> =
        MODULES.flatMap { module ->
            listOf("src/main/kotlin", "src/main/java")
                .map { File(repositoryRoot, "${module.dir}/$it") }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
        }
}

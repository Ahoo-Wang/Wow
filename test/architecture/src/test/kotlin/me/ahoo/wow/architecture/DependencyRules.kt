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

import me.ahoo.wow.architecture.ArchitectureRules.API
import me.ahoo.wow.architecture.ArchitectureRules.CLIENT
import me.ahoo.wow.architecture.ArchitectureRules.COSEC
import me.ahoo.wow.architecture.ArchitectureRules.HTTP
import me.ahoo.wow.architecture.ArchitectureRules.KERNEL
import me.ahoo.wow.architecture.ArchitectureRules.MANIFEST
import me.ahoo.wow.architecture.ArchitectureRules.MANIFEST_TOOLS
import me.ahoo.wow.architecture.ArchitectureRules.MODULES
import me.ahoo.wow.architecture.ArchitectureRules.OBSERVABILITY
import me.ahoo.wow.architecture.ArchitectureRules.OPENAPI
import me.ahoo.wow.architecture.ArchitectureRules.QUERY
import me.ahoo.wow.architecture.ArchitectureRules.QUERY_API
import me.ahoo.wow.architecture.ArchitectureRules.QUERY_TCK
import me.ahoo.wow.architecture.ArchitectureRules.RUNTIME
import me.ahoo.wow.architecture.ArchitectureRules.RUNTIME_MODULES
import me.ahoo.wow.architecture.ArchitectureRules.SPI
import me.ahoo.wow.architecture.ArchitectureRules.SPRING_BOOT
import me.ahoo.wow.architecture.ArchitectureRules.TCK
import me.ahoo.wow.architecture.ArchitectureRules.TCK_CONTAINERS
import me.ahoo.wow.architecture.ArchitectureRules.TEST_DSL
import me.ahoo.wow.architecture.ArchitectureRules.TEST_QUERY
import me.ahoo.wow.architecture.ArchitectureRules.TEST_SPRING_BOOT

/** What the production sources of each module may import (06-modules.md §3): the [DEPENDENCY_RULES]. */
object DependencyRules {
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
     * wow-cosec is the Spring-free part of the CoSec integration (review R5.C-12): its resolvers and authorizer read
     * what CoSec resolved on a WebFlux exchange, and the host wires them, as it wires Kafka, reading `wow.cosec.*` once.
     */
    val COSEC_IS_WIRED_BY_THE_HOST = DependencyRule(
        name = "wow-cosec imports no Spring context, beans or Spring Boot: the host wires it",
        modules = listOf(COSEC.dir),
        forbidden = listOf("org.springframework.context", "org.springframework.beans", "org.springframework.boot"),
    )

    /**
     * The server does not build on its client (decision D286): the wire grammar both ends share, the Wow headers,
     * `Wow-Wait`, `Wow-Scope` and `Wow-Call`'s framing, and the service header are `:api`'s (`me.ahoo.wow.api.wire`,
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
     * What the Spring Boot host may name of wow-query (decision D237, reviews R3.1-1, R5.C-2), a positive list: the
     * engine's builder and what it is built of (the application's ports, the settings types), the storage and backend
     * ports a store provides, the read paths it hands out, and the values it reports. The read side is composed once,
     * in wow-query: the host maps its properties onto `QueryEngine.Builder` and `QueryStores.of`, and decides no query
     * policy, gateway, visibility or bound itself, so a host that is not Spring Boot (A10) misses none. A type the
     * host starts to name must be one of these, or a new port, added here with its reason.
     */
    private val HOST_QUERY_TYPES: List<String> = listOf(
        // The engine, its builder's inputs and its outputs.
        "engine.QueryEngine", "engine.ManifestTypes", "engine.CommittedStates", "engine.ReadFacade",
        "policy.QueryEntryPolicy", "admission.QueryBudget", "cache.QueryCountCache", "cache.QueryCountCacheOptions",
        "QueryGateway", "QueryModule", "CrossTenantAudit",
        // Which aggregates the engine's access policies govern, a resource the host gives the Sinks it opens.
        "policy.GovernedAggregates",
        // The application's ports the engine is built of.
        "policy.ReadAuthorizer", "observe.QueryObserver", "filter.QueryFilter", "schema.QuerySchemaSource", "schema.MaskStrategyResolver",
        "schema.QuerySensitivityPolicy", "dataset.DatasetVisibility",
        // Refused at startup: an application's QueryPolicy bean, which the node no longer applies.
        "policy.QueryPolicy",
        // The stores' query ports and the bounds wow-query composes from the host's settings (review R5.C-2).
        "store.StoreQueries", "store.StoreQueryOptions", "store.SnapshotQueries", "store.EventQueries",
        "store.QueryIndexReport", "store.QueryStores", "store.QueryStoreSettings", "store.QueryStoreLimits",
        "store.DatasetQueryLimits", "admission.QueryBulkhead", "admission.AdaptiveQueryLimit", "admission.AdaptiveQueryLimits", "admission.AdaptiveLimitOptions",
        "backend.QueryBackend", "backend.QueryBindings", "backend.QueryBackendBinding", "events.UnpinnedEvents",
        "memory.InMemoryQueryStorage", "memory.InMemoryEventQueryStorage",
        // The schemas, indexes and datasets the host loads and reports at startup.
        "schema.QuerySchemaCatalog", "schema.QueryModel", "schema.QueryModelSchema",
        "index.QueryIndexDeclaration", "index.QueryIndexPlan", "dataset.QueryDatasets", "dataset.QueryDatasetFiles", "dataset.QueryDataset",
        "dataset.QueryDatasetChange", "dataset.QueryDatasetListener", "dataset.DatasetQueryGateways",
        "dataset.QueryDatasetProvider", "dataset.QueryDatasetConfig",
        // The read paths it hands out.
        "read.InProcessQueryApi", "read.ReadModels", "mask.PayloadMasks",
    ).map { "me.ahoo.wow.query.$it" }

    val HOST_ASSEMBLES_THE_READ_SIDE = DependencyRule(
        name = "wow-spring-boot names of wow-query only the engine's builder, its ports and its settings",
        modules = listOf(SPRING_BOOT.dir),
        forbidden = listOf("me.ahoo.wow.query"),
        allowed = HOST_QUERY_TYPES,
    )

    /**
     * Every entry reads an aggregate's state through one path (decision D237): `ReadFacade` (read, visibility, masks)
     * of wow-query, which the query engine composes. An entry (HTTP, CoSec, the host, the runtime's call and ops paths)
     * never admits or hides a state itself, so no entry can read a state past its policies or unmasked.
     */
    val ENTRIES_READ_THROUGH_FACADE = DependencyRule(
        name = "the entries admit a state only through wow-query's ReadFacade",
        modules = listOf(RUNTIME, HTTP, COSEC, OBSERVABILITY, SPRING_BOOT).map { it.dir },
        forbidden = listOf("me.ahoo.wow.query.state.StateVisibility"),
    )

    /**
     * Observability is wired at fixed seams (decision D174, 06 §3): only wow-observability and the host that wires it
     * import OpenTelemetry or Micrometer; the core exposes ports instead.
     */
    val ONLY_OBSERVABILITY_OBSERVES = DependencyRule(
        name = "only wow-observability and wow-spring-boot import OpenTelemetry or Micrometer",
        modules = (MODULES - OBSERVABILITY - SPRING_BOOT).map { it.dir },
        forbidden = listOf("io.opentelemetry", "io.micrometer"),
    )

    /** Only the host, and the kit that starts it for a test, depend on Spring Boot. */
    val ONLY_HOST_USES_SPRING_BOOT = DependencyRule(
        name = "only wow-spring-boot and wow-test-spring-boot import Spring Boot",
        modules = (MODULES - SPRING_BOOT - TEST_SPRING_BOOT).map { it.dir },
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

    /**
     * The Manifest's generator builds on the contract and Wow's JSON, not on the write path (finding R5.A-19): the state
     * fingerprint it records is Wow's state JSON ([me.ahoo.wow.spi.json.StateJson]), which the kernel uses too.
     */
    val MANIFEST_TOOLS_WITHOUT_KERNEL = DependencyRule(
        name = "wow-manifest-tools imports nothing of the kernel",
        modules = listOf(MANIFEST_TOOLS.dir),
        forbidden = listOf("me.ahoo.wow.kernel"),
    )

    /** Schemas are introspected at build time only (decision D73): no runtime module generates one. */
    val SCHEMA_GENERATION_AT_BUILD_TIME = DependencyRule(
        name = "only wow-manifest-tools imports the JSON Schema generator",
        modules = RUNTIME_MODULES.map { it.dir },
        forbidden = listOf("com.github.victools"),
    )

    /**
     * The 9.x parts of 9.x wow-api that the Wow 10 wow-api leaves out (06 §2.1; `query` moves to wow-query-api in M9).
     * Wow 10 reuses `api.naming` for its naming of model types, so of that package the 9.x types are listed
     * ([PositiveRules.NINE_X_TYPES]).
     */
    private val API_9X = listOf(
        "abac", "command", "event", "exception", "messaging", "modeling", "query", "serialization",
        "Copyable", "Identifier", "Ordered", "Version", "Wow",
    ).map { "me.ahoo.wow.api.$it" } + PositiveRules.NINE_X_TYPES

    /**
     * wow-api depends only on the query protocol, the coroutines' `Flow` (06 §3, decisions D66, D260: the `QueryApi`
     * of D259 streams its rows) and Jackson's trees, the one shape of free-form content (D362, D366 ④: an untyped read
     * is an `ObjectNode`), and does not build on the 9.x parts of 9.x wow-api, which go with 9.x. The query protocol
     * keeps 9.x's package name, `me.ahoo.wow.api.query`: only wow-query-api can supply it, the one module wow-api
     * depends on.
     */
    val API_STANDS_ALONE = DependencyRule(
        name = "wow-api imports no framework, no JSON library but its trees, no kotlinx but its Flow and no 9.x part " +
            "of wow-api",
        modules = listOf(API.dir),
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS + listOf("tools.jackson", "com.fasterxml", "kotlinx") +
            API_9X,
        allowed = listOf("kotlinx.coroutines.flow", "me.ahoo.wow.api.query", PositiveRules.JACKSON_TREES),
    )

    /**
     * The test DSL and the TCK carry no framework and no container (decision D196): a domain's tests and an in-memory
     * backend run them without Spring, Reactor, a driver or Docker; the containers are [TCK_CONTAINERS]'s.
     */
    val TEST_MODULES_ARE_FRAMEWORK_FREE = DependencyRule(
        name = "wow-test and wow-tck import no Spring, Reactor, HTTP, driver or Testcontainers types",
        modules = listOf(TEST_DSL, TEST_QUERY, TCK, QUERY_TCK).map { it.dir },
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS + listOf("org.testcontainers"),
    )

    val DEPENDENCY_RULES = listOf(
        CORE_IS_FRAMEWORK_FREE,
        CLIENT_IS_FRAMEWORK_FREE,
        TEST_MODULES_ARE_FRAMEWORK_FREE,
        KERNEL_IGNORES_RUNTIME,
        COSEC_IS_WIRED_BY_THE_HOST,
        HTTP_IGNORES_CLIENT,
        CLIENT_IGNORES_HTTP,
        HTTP_READS_ADAPT_ONLY,
        HOST_ASSEMBLES_THE_READ_SIDE,
        ENTRIES_READ_THROUGH_FACADE,
        ONLY_OBSERVABILITY_OBSERVES,
        ONLY_HOST_USES_SPRING_BOOT,
        MANIFEST_IS_PLAIN_DATA,
        QUERY_API_IS_PLAIN_DATA,
        SCHEMA_GENERATION_AT_BUILD_TIME,
        MANIFEST_TOOLS_WITHOUT_KERNEL,
        API_STANDS_ALONE,
    )
}

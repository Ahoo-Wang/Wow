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
import me.ahoo.wow.architecture.ArchitectureRules.COMPILER
import me.ahoo.wow.architecture.ArchitectureRules.GRADLE_PLUGIN
import me.ahoo.wow.architecture.ArchitectureRules.KERNEL
import me.ahoo.wow.architecture.ArchitectureRules.MANIFEST
import me.ahoo.wow.architecture.ArchitectureRules.MIGRATE_DATA
import me.ahoo.wow.architecture.ArchitectureRules.MODULES
import me.ahoo.wow.architecture.ArchitectureRules.QUERY
import me.ahoo.wow.architecture.ArchitectureRules.QUERY_API
import me.ahoo.wow.architecture.ArchitectureRules.RUNTIME
import me.ahoo.wow.architecture.ArchitectureRules.SPI
import java.io.File

/**
 * The key boundaries as positive constraints (review R5.C-10): what the core modules may import, and the 9.x names
 * and configuration keys no module carries (06 §3, 07 §4).
 */
object PositiveRules {
    private const val JACKSON = "tools.jackson"
    private const val JACKSON_ANNOTATIONS = "com.fasterxml.jackson.annotation"
    private const val COROUTINES = "kotlinx.coroutines"
    private const val LOGGING = "io.github.oshai.kotlinlogging"
    private const val WOW = "me.ahoo.wow"

    /**
     * What each core module imports besides the Kotlin standard library and the JDK: the libraries 06 §3 grants it and
     * the packages of the modules it may depend on. The contracts carry no runtime dependency, the kernel and the
     * runtime only coroutines, Jackson and logging, and none of them Spring, Reactor, a driver or another edge.
     */
    val CORE_IMPORTS: List<ImportAllowList> = listOf(
        // The programming model: the query protocol and the coroutines' Flow (decision D260).
        ImportAllowList(
            "wow-api imports only its own packages and Flow",
            listOf(API.dir),
            listOf("$WOW.api", "$COROUTINES.flow")
        ),
        ImportAllowList("wow-query-api imports only its own packages", listOf(QUERY_API.dir), listOf("$WOW.api.query")),
        ImportAllowList("wow-manifest imports only its own packages", listOf(MANIFEST.dir), listOf("$WOW.manifest")),
        ImportAllowList(
            "wow-spi imports only wow-api, coroutines and Jackson",
            listOf(SPI.dir),
            listOf("$WOW.api", "$WOW.spi", COROUTINES, JACKSON, JACKSON_ANNOTATIONS)
        ),
        ImportAllowList(
            "wow-kernel imports only wow-spi, coroutines, Jackson and logging",
            listOf(KERNEL.dir),
            listOf("$WOW.api", "$WOW.spi", "$WOW.kernel", COROUTINES, JACKSON, JACKSON_ANNOTATIONS, LOGGING)
        ),
        ImportAllowList(
            "wow-runtime imports only the kernel, the Manifest, coroutines, Jackson and logging",
            listOf(RUNTIME.dir),
            listOf(
                "$WOW.api", "$WOW.spi", "$WOW.kernel", "$WOW.manifest", "$WOW.runtime", COROUTINES, JACKSON,
                JACKSON_ANNOTATIONS, LOGGING
            ),
        ),
        ImportAllowList(
            "wow-query imports only the query protocol, wow-spi, the Manifest, coroutines, Jackson and logging",
            listOf(QUERY.dir),
            listOf(
                "$WOW.api",
                "$WOW.spi",
                "$WOW.manifest",
                "$WOW.query",
                COROUTINES,
                JACKSON,
                JACKSON_ANNOTATIONS,
                LOGGING
            ),
        ),
        // The typed clients on the JDK's HttpClient (decisions D257, D259): no Spring, Reactor, server or edge. The
        // runtime, kernel and query packages it reads today narrow with R5.C-5's second step.
        ImportAllowList(
            "wow-client imports only Wow's contracts and core, coroutines, Jackson and logging",
            listOf(CLIENT.dir),
            listOf(
                "$WOW.api", "$WOW.spi", "$WOW.kernel", "$WOW.manifest", "$WOW.query", "$WOW.runtime",
                "$WOW.client", COROUTINES, JACKSON, JACKSON_ANNOTATIONS, LOGGING
            ),
        ),
    )

    /**
     * The packages only 9.x has (07 §4): each package of 9.x's `wow-*` modules that no Wow 10 package is, or
     * contains, taken at its shortest. Listed from `v10-9x-baseline` (`a67ece66`, the last commit with both lines,
     * decision D262); 9.x itself lives on `v9`.
     */
    val NINE_X_PACKAGES: List<String> = listOf(
        "annotation", "api.abac", "api.command", "api.event", "api.exception", "api.messaging", "api.modeling",
        "api.naming", "api.query.descriptor", "api.query.mask", "api.query.schema", "api.query.spec",
        "api.serialization", "apiclient", "batch", "benchmark.fixture", "benchmark.infrastructure",
        "benchmark.workload", "bi", "cache", "command", "compiler.aggregate", "compiler.metadata", "compiler.query",
        "configuration", "cosec.abac", "cosec.appender", "cosec.identity", "cosec.propagation",
        "elasticsearch.eventsourcing", "elasticsearch.query.event", "elasticsearch.query.snapshot", "event",
        "eventsourcing", "exception", "execution", "filter", "id", "identity", "infra", "ioc", "messaging",
        "metadata", "metrics", "modeling", "mongo.prepare", "mongo.query.aggregation", "mongo.query.event",
        "mongo.query.schema", "mongo.query.snapshot", "naming", "openapi.catalog", "openapi.component",
        "openapi.context", "openapi.contract", "openapi.contributor", "openapi.converter", "openapi.metadata",
        "openapi.render", "openapi.schema", "opentelemetry.aggregate", "opentelemetry.eventprocessor",
        "opentelemetry.eventsourcing", "opentelemetry.messaging", "opentelemetry.projection", "opentelemetry.saga",
        "opentelemetry.snapshot", "opentelemetry.wait", "processing", "projection", "query.dataset.filter",
        "query.dsl", "query.event", "query.snapshot", "redis", "rest", "runtime.internal", "saga", "schema",
        "serialization", "spring.boot.starter", "spring.event", "spring.prepare", "spring.projection", "spring.query",
        "spring.saga", "webflux",
    ).map { "$WOW.$it" }

    /**
     * The sources that name 9.x packages on purpose, to refuse 9.x leftovers in an application's build: the compiler's
     * check that a model uses no 9.x framework type, and the Gradle plugin's refusal of 9.x's KSP processors.
     */
    val NINE_X_DETECTORS: Set<String> = setOf(
        "${COMPILER.dir}/src/main/kotlin/me/ahoo/wow/compiler/model/ModelNames.kt",
        "${GRADLE_PLUGIN.dir}/src/main/kotlin/me/ahoo/wow/gradle/WowPlugin.kt",
    )

    /** No module but the 9.x import names a 9.x package, but in [NINE_X_DETECTORS] (06 §3, 07 §4). */
    val NINE_X_NAMES = DependencyRule(
        name = "no module but wow-migrate-data names a 9.x type",
        modules = (MODULES - MIGRATE_DATA).map { it.dir },
        forbidden = NINE_X_PACKAGES,
    )

    /** The violations of [NINE_X_NAMES] outside [NINE_X_DETECTORS]. */
    fun nineXNameViolations(repositoryRoot: File): List<String> =
        NINE_X_NAMES.violations(repositoryRoot).filterNot { violation -> NINE_X_DETECTORS.any(violation::startsWith) }

    /**
     * The configuration key groups only 9.x has (07 §4), from the `@ConfigurationProperties` prefixes of
     * `v10-9x-baseline` that no Wow 10 key is under: 9.x's buses and stores, its WebFlux and Redis modules, the
     * compensation, BI and OpenAPI services' keys, and its per-aggregate, metrics and message settings.
     */
    val NINE_X_KEYS: List<String> = listOf(
        "wow.command", "wow.event", "wow.eventsourcing", "wow.prepare", "wow.webflux", "wow.redis", "wow.compensation",
        "wow.bi", "wow.metrics", "wow.elasticsearch", "wow.openapi", "wow.message", "wow.aggregate",
        "wow.aggregate-tracing", "wow.query.clickhouse",
    )

    private const val METADATA = "host/spring-boot/src/main/resources/META-INF/spring-configuration-metadata.json"
    private val METADATA_NAME = Regex(""""name"\s*:\s*"([^"]+)"""")
    private val CONFIG_FILE = Regex("""application[\w-]*\.(ya?ml|properties)""")
    private const val MAIN_RESOURCES = "/src/main/resources/"
    private val SKIPPED = setOf("node_modules", "build", ".gradle")

    /**
     * Where Wow 10's configuration files live: the modules, the examples, the platform services, the end-to-end
     * benchmark's server and the migration rehearsal's host; not the 9.x inputs of the Skills' evaluations, nor the Axon
     * benchmark, which is no Wow application.
     */
    val CONFIG_DIRS: List<String> =
        MODULES.map { it.dir } + listOf("example", "platform", "test/benchmarks/server", "test/migration-rehearsal")

    /**
     * The 9.x keys Wow 10 knows or configures: a key of the host's configuration metadata, or of an
     * `application*.yml`, `.yaml` or `.properties` of a module, an example or a platform service, under one of
     * [NINE_X_KEYS], under [dirs] ([CONFIG_DIRS]). (The host refuses an unknown `wow.*` key at startup, so the metadata is what it accepts.)
     */
    fun nineXKeyViolations(repositoryRoot: File, dirs: List<String>): List<String> {
        val metadata = File(repositoryRoot, METADATA).takeIf { it.isFile }?.readText().orEmpty()
        val known = METADATA_NAME.findAll(metadata).map { it.groupValues[1] }.filter { it.startsWith("wow.") }
            .map { "$METADATA: $it" }
        val configured = dirs.map { File(repositoryRoot, it) }.filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().onEnter { it.name !in SKIPPED }
                    .filter { it.isFile && CONFIG_FILE.matches(it.name) && MAIN_RESOURCES in it.invariantSeparatorsPath }
            }
            .distinct()
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                keysOf(file).map { "$path: $it" }
            }
        return (known + configured).filter { entry ->
            val key = entry.substringAfterLast(": ")
            NINE_X_KEYS.any { key == it || key.startsWith("$it.") }
        }.toList()
    }

    /**
     * The dotted keys of a configuration file: a `.properties` file's keys, or a YAML file's mapping keys joined by
     * their nesting (list items and scalars aside), which is all a Spring application's configuration needs here.
     */
    internal fun keysOf(file: File): List<String> {
        val lines = file.readLines().filterNot { it.isBlank() || it.trimStart().startsWith("#") }
        if (file.extension == "properties") {
            return lines.map { it.substringBefore('=').substringBefore(':').trim() }
        }
        val path = ArrayDeque<Pair<Int, String>>()
        val keys = ArrayList<String>()
        lines.filterNot { it.trimStart().startsWith("- ") || it.trim() == "---" }.forEach { line ->
            val key = YAML_KEY.matchEntire(line) ?: return@forEach
            val indent = key.groupValues[1].length
            while (path.isNotEmpty() && path.last().first >= indent) path.removeLast()
            path.addLast(indent to key.groupValues[2].trim('"', '\'', '[', ']'))
            keys += path.joinToString(".") { it.second }
        }
        return keys
    }

    private val YAML_KEY = Regex("""^(\s*)([^\s:#][^:#]*?):(?:\s.*|$)""")
}

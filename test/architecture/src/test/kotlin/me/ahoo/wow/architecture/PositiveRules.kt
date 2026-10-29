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

    /** Jackson's tree nodes: the free-form content of an api module (D362), an untyped read's rows (D366 ④). */
    const val JACKSON_TREES = "tools.jackson.databind.node"

    /**
     * What each core module imports besides the Kotlin standard library and the JDK: the libraries 06 §3 grants it and
     * the packages of the modules it may depend on. The contracts carry no runtime dependency, the kernel and the
     * runtime only coroutines, Jackson and logging, and none of them Spring, Reactor, a driver or another edge.
     */
    val CORE_IMPORTS: List<ImportAllowList> = listOf(
        // The programming model: the query protocol, the coroutines' Flow (decision D260) and Jackson's trees, the
        // shape of an untyped read (D366 ④).
        ImportAllowList(
            "wow-api imports only its own packages, Flow and Jackson's trees",
            listOf(API.dir),
            listOf("$WOW.api", "$COROUTINES.flow", JACKSON_TREES)
        ),
        // The stability markers of wow-annotations (decision D282).
        ImportAllowList(
            "wow-query-api imports only its own packages and the stability markers",
            listOf(QUERY_API.dir),
            listOf("$WOW.api.query", "$WOW.api.annotation"),
        ),
        // And the stability markers of wow-annotations (D282); of wow-api, only the problem its error catalog declares
        // and maps a code to (Retry, WowProblemException of a command's result, D366 ②), nothing of the programming
        // model.
        ImportAllowList(
            "wow-manifest imports only its own packages, the stability markers and the problem vocabulary",
            listOf(MANIFEST.dir),
            listOf("$WOW.manifest", "$WOW.api.annotation", "$WOW.api.problem", "$WOW.api.result.CommandResult"),
        ),
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
     * decision D262); 9.x itself lives on `v9`. A package Wow 10 reuses with a meaning of its own is not listed: its
     * 9.x types are, in [NINE_X_TYPES].
     */
    val NINE_X_PACKAGES: List<String> = listOf(
        "annotation", "api.abac", "api.command", "api.event", "api.exception", "api.messaging", "api.modeling",
        "api.query.descriptor", "api.query.mask", "api.query.schema", "api.query.spec",
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
     * The 9.x types of the packages Wow 10 reuses with a meaning of its own, which [NINE_X_PACKAGES] so cannot list:
     * `api.naming`, Wow 10's naming of model types (`TypeNames`), was 9.x's naming capabilities. Listed from
     * `v10-9x-baseline` (`a67ece66`).
     */
    val NINE_X_TYPES: List<String> = listOf(
        "Named",
        "NamedBoundedContext",
        "QualifiedNamed",
        "Materialized",
        "CompletedCapable",
        "DescriptionCapable",
        "EnabledCapable",
        "SummaryCapable",
    ).map { "$WOW.api.naming.$it" }

    /**
     * The sources that name 9.x packages on purpose, to refuse 9.x leftovers in an application's build: the compiler's
     * check that a model uses no 9.x framework type.
     */
    val NINE_X_DETECTORS: Set<String> = setOf(
        "${COMPILER.dir}/src/main/kotlin/me/ahoo/wow/compiler/model/symbol/ModelNames.kt",
    )

    /** No module but the 9.x import names a 9.x package, but in [NINE_X_DETECTORS] (06 §3, 07 §4). */
    val NINE_X_NAMES = DependencyRule(
        name = "no module but wow-migrate-data names a 9.x type",
        modules = (MODULES - MIGRATE_DATA).map { it.dir },
        forbidden = NINE_X_PACKAGES + NINE_X_TYPES,
    )

    /** The violations of [NINE_X_NAMES] outside [NINE_X_DETECTORS]. */
    fun nineXNameViolations(repositoryRoot: File): List<String> =
        NINE_X_NAMES.violations(repositoryRoot).filterNot { violation -> NINE_X_DETECTORS.any(violation::startsWith) }

    private val METADATA_PROPERTY = Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"type"\s*:\s*"([^"]*)"""")
    private val OPEN_TYPE = Regex("""\b(?:Map|List|Set|Collection)\b""")
    private val CONFIG_FILE = Regex("""application[\w-]*\.(ya?ml|properties)""")
    private val INDEX = Regex("""\[[^]]*]""")
    private const val MAIN_RESOURCES = "/src/main/resources/"
    private const val MODULE_METADATA = "src/main/resources/META-INF/spring-configuration-metadata.json"
    private val SKIPPED = setOf("node_modules", "build", ".gradle")

    /**
     * Where Wow 10's configuration files live: the modules, the examples, the platform services, the end-to-end
     * benchmark's server and the migration rehearsal's host; not the 9.x inputs of the Skills' evaluations, nor the Axon
     * benchmark, which is no Wow application.
     */
    val CONFIG_DIRS: List<String> =
        MODULES.map { it.dir } + listOf("examples", "platform", "test/benchmarks/server", "test/migration-rehearsal")

    private fun relaxed(key: String): String = key.lowercase().replace("-", "").replace("_", "")

    /**
     * Whether [key] is one Wow 10 declares in [properties], the configuration metadata's `name` and `type` of each key
     * (the host's, the platform services' and Wow's own providers' options): a declared key, a group above one, or a
     * key under a declared map or list, whose entries are free. As the documentation's check reads its samples
     * (`docs-consistency.mjs`, `knownKeys`).
     */
    internal fun declared(properties: List<Pair<String, String>>): (String) -> Boolean {
        val keys = properties.map { (name, type) -> relaxed(name) to OPEN_TYPE.containsMatchIn(type.replace(Regex("[<.]"), " ")) }
        return { key ->
            val relaxedKey = relaxed(key.replace(INDEX, ""))
            keys.any { (name, open) ->
                relaxedKey == name || name.startsWith("$relaxedKey.") || open && relaxedKey.startsWith("$name.")
            }
        }
    }

    /**
     * The `wow.*` keys of an `application*.yml`, `.yaml` or `.properties` of a module, an example or a platform
     * service, under [dirs] ([CONFIG_DIRS]), that Wow 10 does not declare: the configuration metadata of every module
     * under [repositoryRoot] names the keys it reads. A key of an earlier version, a typo or a key Wow 10 removed alike:
     * the host refuses each at startup, so a configuration Wow ships must not carry one.
     */
    fun undeclaredConfigKeys(repositoryRoot: File, dirs: List<String>): List<String> {
        val properties = repositoryRoot.walkTopDown().onEnter { it.name !in SKIPPED }
            .filter { it.isFile && it.invariantSeparatorsPath.endsWith(MODULE_METADATA) }
            .flatMap { file ->
                METADATA_PROPERTY.findAll(
                    file.readText()
                ).map { it.groupValues[1] to it.groupValues[2] }
            }
            .toList()
        val known = declared(properties)
        return dirs.map { File(repositoryRoot, it) }.filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().onEnter { it.name !in SKIPPED }
                    .filter { it.isFile && CONFIG_FILE.matches(it.name) && MAIN_RESOURCES in it.invariantSeparatorsPath }
            }
            .distinct()
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                keysOf(file).filter { (it == "wow" || it.startsWith("wow.")) && !known(it) }.map { "$path: $it" }
            }
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

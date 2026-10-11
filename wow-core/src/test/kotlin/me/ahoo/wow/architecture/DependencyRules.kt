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

/**
 * A source dependency rule: production sources of [modules] (optionally narrowed by [appliesToPackage]) must not
 * reference any package in [forbidden].
 *
 * The check scans source text, not bytecode: every `import` and every fully-qualified reference outside comments
 * counts, including class names in string literals (reflective lookups are dependencies too).
 */
data class DependencyRule(
    val name: String,
    val modules: List<String>,
    val forbidden: List<String>,
    val appliesToPackage: (String) -> Boolean = { true },
) {
    fun violations(repositoryRoot: File): List<String> =
        modules.flatMap { module -> sourceFiles(File(repositoryRoot, module)) }
            .flatMap { file ->
                val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                fileViolations(file).map { "$path:$it" }
            }

    private fun fileViolations(file: File): List<String> {
        val code = stripComments(file.readText())
        val packageName = PACKAGE.find(code)?.groupValues?.get(1).orEmpty()
        if (!appliesToPackage(packageName)) {
            return emptyList()
        }
        return code.lines().flatMapIndexed { index, line ->
            QUALIFIED_NAME.findAll(line)
                .map { it.value }
                .filter { reference -> forbidden.any { reference == it || reference.startsWith("$it.") } }
                .map { "${index + 1}: $it" }
                .toList()
        }
    }

    companion object {
        private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        private val QUALIFIED_NAME = Regex("""(?<![\w.])[a-z][\w]*(?:\.\w+)+""")
        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""(?m)(^|[^:"])//.*$""")

        private fun sourceFiles(moduleDir: File): List<File> =
            listOf("src/main/kotlin", "src/main/java")
                .map { File(moduleDir, it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
                .sortedBy { it.path }

        /** Removes comments but keeps line breaks so line numbers stay correct. */
        private fun stripComments(source: String): String =
            source
                .replace(BLOCK_COMMENT) { match -> "\n".repeat(match.value.count { it == '\n' }) }
                .replace(LINE_COMMENT, "$1")
    }
}

/**
 * The dependency rules of the 9.3.0 target architecture (query design §9, refactor design G3).
 */
object DependencyRules {
    private val HTTP = listOf(
        "org.springframework.web",
        "org.springframework.http",
        "io.swagger",
        "jakarta.servlet",
        "reactor.netty",
        "me.ahoo.wow.webflux",
        "me.ahoo.wow.openapi",
    )

    private val BACKEND_DRIVERS: Map<String, List<String>> = mapOf(
        "wow-mongo" to listOf("me.ahoo.wow.mongo", "com.mongodb", "org.bson"),
        "wow-redis" to listOf("me.ahoo.wow.redis", "io.lettuce", "redis.clients", "org.springframework.data.redis"),
        "wow-kafka" to listOf("me.ahoo.wow.kafka", "org.apache.kafka", "reactor.kafka"),
        "wow-elasticsearch" to listOf(
            "me.ahoo.wow.elasticsearch",
            "co.elastic",
            "org.elasticsearch",
            "org.springframework.data.elasticsearch",
        ),
    )

    private val CLICKHOUSE = listOf("com.clickhouse", "ru.yandex.clickhouse")

    private val ALL_DRIVERS = BACKEND_DRIVERS.values.flatten() + CLICKHOUSE

    private val QUERY_PACKAGES = listOf(
        "me.ahoo.wow.query",
        "me.ahoo.wow.mongo.query",
        "me.ahoo.wow.elasticsearch.query",
    )

    val CORE_HAS_NO_HTTP_OR_DRIVERS = DependencyRule(
        name = "wow-core imports no HTTP, Spring or storage-driver types",
        modules = listOf("wow-core"),
        forbidden = HTTP + ALL_DRIVERS + "org.springframework",
    )

    val QUERY_CORE_HAS_NO_HTTP_OR_DRIVERS = DependencyRule(
        name = "wow-query imports no HTTP (webflux), Spring or storage-driver types",
        modules = listOf("wow-query"),
        forbidden = HTTP + ALL_DRIVERS + "org.springframework",
    )

    /** The query DSL builds `wow-api` queries only: it imports none of the query runtime it feeds. */
    val QUERY_DSL_BUILDS_API_ONLY = DependencyRule(
        name = "wow-query dsl imports no query runtime package",
        modules = listOf("wow-query"),
        forbidden = listOf("schema", "filter", "aggregation", "mask", "event", "snapshot")
            .map { "me.ahoo.wow.query.$it" },
        appliesToPackage = { it == "me.ahoo.wow.query.dsl" },
    )

    /**
     * The generic query engine is model-neutral: every snapshot and event-stream difference lives in the model's
     * `QueryModelProfile`, so the DSL, schema, filter, aggregation and mask packages import neither read model
     * package. The Catalog reads storages through a `QueryStorageLocator`, never the read models' backend factories.
     */
    val QUERY_ENGINE_IS_MODEL_NEUTRAL = DependencyRule(
        name = "wow-query dsl, schema, filter, aggregation and mask import no read model package",
        modules = listOf("wow-query"),
        forbidden = listOf("me.ahoo.wow.query.event", "me.ahoo.wow.query.snapshot"),
        appliesToPackage = { pkg ->
            listOf("dsl", "schema", "filter", "aggregation", "mask").any { inPackage("me.ahoo.wow.query.$it")(pkg) }
        },
    )

    val BACKENDS_ARE_INDEPENDENT: List<DependencyRule> = BACKEND_DRIVERS.keys.map { backend ->
        DependencyRule(
            name = "$backend imports no other backend module or driver",
            modules = listOf(backend),
            forbidden = BACKEND_DRIVERS.filterKeys { it != backend }.values.flatten(),
        )
    }

    /**
     * Event and snapshot stores (every package of a storage module outside its `query` package) do not use the
     * query runtime or the backend's query translation. Protocol types in `me.ahoo.wow.api.query` are allowed.
     */
    val STORES_HAVE_NO_QUERY: List<DependencyRule> = listOf(
        "wow-mongo" to "me.ahoo.wow.mongo",
        "wow-elasticsearch" to "me.ahoo.wow.elasticsearch",
    ).map { (module, basePackage) ->
        DependencyRule(
            name = "$module store packages import no query packages",
            modules = listOf(module),
            forbidden = QUERY_PACKAGES,
            appliesToPackage = { it != "$basePackage.query" && !it.startsWith("$basePackage.query.") },
        )
    }

    private val DOMAIN_PACKAGES = listOf(
        "me.ahoo.wow.command",
        "me.ahoo.wow.event",
        "me.ahoo.wow.eventsourcing",
        "me.ahoo.wow.projection",
        "me.ahoo.wow.saga",
    )

    private fun inPackage(basePackage: String): (String) -> Boolean =
        { it == basePackage || it.startsWith("$basePackage.") }

    /**
     * The generic messaging runtime (buses, dispatchers, transports, propagation, compensation matching) imports no
     * domain package; each domain package registers its specialisations, such as its transport bus or propagator.
     */
    val MESSAGING_HAS_NO_DOMAIN = DependencyRule(
        name = "wow-core messaging imports no domain package",
        modules = listOf("wow-core"),
        forbidden = DOMAIN_PACKAGES,
        appliesToPackage = inPackage("me.ahoo.wow.messaging"),
    )

    /** `infra` is the bottom layer of wow-core: it imports no domain, messaging or metrics package. */
    val INFRA_HAS_NO_UPPER_LAYERS = DependencyRule(
        name = "wow-core infra imports no domain, messaging or metrics package",
        modules = listOf("wow-core"),
        forbidden = DOMAIN_PACKAGES + "me.ahoo.wow.messaging" + "me.ahoo.wow.metrics",
        appliesToPackage = inPackage("me.ahoo.wow.infra"),
    )

    /**
     * The metrics port (`me.ahoo.wow.metrics`) is what the runtime instruments itself with, so it depends on no domain
     * package; the decorators that wrap buses, stores and handlers live in `me.ahoo.wow.metrics.decorator`.
     * Contracts in `me.ahoo.wow.api` are allowed.
     */
    val METRICS_PORT_HAS_NO_DOMAIN = DependencyRule(
        name = "wow-core metrics port imports no domain or messaging package",
        modules = listOf("wow-core"),
        forbidden = DOMAIN_PACKAGES + "me.ahoo.wow.modeling" + "me.ahoo.wow.messaging" + "me.ahoo.wow.metrics.decorator",
        appliesToPackage = { it == "me.ahoo.wow.metrics" },
    )

    val ALL: List<DependencyRule> =
        listOf(
            CORE_HAS_NO_HTTP_OR_DRIVERS,
            QUERY_CORE_HAS_NO_HTTP_OR_DRIVERS,
            QUERY_DSL_BUILDS_API_ONLY,
            QUERY_ENGINE_IS_MODEL_NEUTRAL,
            MESSAGING_HAS_NO_DOMAIN,
            INFRA_HAS_NO_UPPER_LAYERS,
            METRICS_PORT_HAS_NO_DOMAIN,
        ) +
            BACKENDS_ARE_INDEPENDENT +
            STORES_HAVE_NO_QUERY
}

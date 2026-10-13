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

/** A V10 module: its Gradle project name and its directory relative to the repository root. */
data class V10Module(val name: String, val dir: String)

/**
 * The V10 architecture rules (documentation/designs/v10/06-modules.md §3 and 07-migration.md §4).
 */
object V10Rules {
    val API = V10Module("api", "api/api")
    val MANIFEST = V10Module("manifest", "api/manifest")
    val QUERY_API = V10Module("query-api", "api/query-api")
    val SPI = V10Module("spi", "core/spi")
    val KERNEL = V10Module("kernel", "core/kernel")
    val RUNTIME = V10Module("runtime", "core/runtime")
    val REACTOR = V10Module("reactor", "core/reactor")
    val QUERY = V10Module("query", "query/query")
    val POSTGRES = V10Module("postgres", "backends/postgres")
    val MONGO = V10Module("mongo", "backends/mongo")
    val KAFKA = V10Module("kafka", "backends/kafka")
    val CLICKHOUSE = V10Module("clickhouse", "backends/clickhouse")
    val HTTP = V10Module("http", "edge/http")
    val COSEC = V10Module("cosec", "edge/cosec")
    val SPRING_BOOT = V10Module("spring-boot", "host/spring-boot")
    val MIGRATE_DATA = V10Module("migrate-data", "migration/migrate-data")
    val COMPILER = V10Module("compiler", "compiler/compiler")
    val GRADLE_PLUGIN = V10Module("gradle-plugin", "compiler/gradle-plugin")
    val MANIFEST_TOOLS = V10Module("manifest-tools", "compiler/manifest-tools")

    val MODULES = listOf(
        API, MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME, REACTOR, QUERY, POSTGRES, MONGO, KAFKA, CLICKHOUSE, HTTP,
        COSEC, SPRING_BOOT, MIGRATE_DATA, COMPILER, GRADLE_PLUGIN, MANIFEST_TOOLS,
    )

    val SHOP_API = V10Module("shop-api", "example/shop/shop-api")
    val SHOP_DOMAIN = V10Module("shop-domain", "example/shop/shop-domain")
    val SHOP_SERVER = V10Module("shop-server", "example/shop/shop-server")
    val EXAMPLE_E2E = V10Module("example-e2e", "example/e2e")

    /**
     * The V10 example (decisions D111, D144): applications of the V10 modules, not ones of them, and the end-to-end
     * tests across them.
     */
    val EXAMPLES = listOf(SHOP_API, SHOP_DOMAIN, SHOP_SERVER, EXAMPLE_E2E)

    /** Whether [project] belongs to the V10 example: the shop, the inventory context (decision D144) or its e2e tests. */
    fun isExample(project: String): Boolean =
        project.startsWith("shop-") || project.startsWith("inventory-") || project == EXAMPLE_E2E.name

    /** The modules an application runs with; the compiler, the Gradle plugin and its tools run in the build only. */
    val RUNTIME_MODULES = MODULES - COMPILER - GRADLE_PLUGIN - MANIFEST_TOOLS

    /**
     * The project dependencies each V10 module may declare (06 §3). A module not listed here, such as wow-query, is a
     * 9.x module V10 still builds on until its V10 module replaces it (decision D85).
     */
    val ALLOWED_PROJECT_DEPENDENCIES: Map<V10Module, Set<String>> = mapOf(
        API to emptySet(),
        MANIFEST to emptySet(),
        QUERY_API to emptySet(),
        SPI to setOf("api"),
        KERNEL to setOf("spi", "manifest"),
        RUNTIME to setOf("kernel"),
        REACTOR to setOf("runtime"),
        // The query engine and its backend SPI (decision D126).
        QUERY to setOf("query-api", "spi", "manifest"),
        // query for the snapshot query backends (decision D126).
        POSTGRES to setOf("spi", "query"),
        MONGO to setOf("spi", "query"),
        KAFKA to setOf("spi"),
        // query for the dataset backend (decision D142).
        CLICKHOUSE to setOf("spi", "query"),
        // runtime only until the V10 query module exists (decision D118).
        HTTP to setOf("runtime"),
        COSEC to setOf("http"),
        // postgres and mongo compileOnly: the backend follows the application's classpath (decision D110).
        // query, kafka and clickhouse compileOnly too: each is wired when the application has it.
        SPRING_BOOT to setOf("runtime", "http", "postgres", "mongo", "query", "kafka", "clickhouse"),
        MIGRATE_DATA to setOf("kernel"),
        // kernel and reactor only for the tests, which compile the generated models.
        COMPILER to setOf("api", "manifest", "kernel", "reactor"),
        // compiler, kernel, manifest-tools and wow-dependencies only for the TestKit fixture and tests.
        GRADLE_PLUGIN to setOf("manifest", "manifest-tools", "kernel", "compiler", "wow-dependencies"),
        // kernel for the state fingerprint.
        MANIFEST_TOOLS to setOf("manifest", "kernel"),
    )

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
        modules = listOf(MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME, QUERY).map { it.dir },
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS,
    )

    /** The kernel knows nothing of clusters, subscriptions or waits: those are the runtime's. */
    val KERNEL_IGNORES_RUNTIME = DependencyRule(
        name = "wow-kernel imports nothing from the runtime",
        modules = listOf(KERNEL.dir),
        forbidden = listOf("me.ahoo.wow.runtime"),
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

    /** The 9.x parts of 9.x wow-api that the V10 wow-api leaves out (06 §2.1; `query` moves to wow-query-api in M9). */
    private val API_9X = listOf(
        "abac", "command", "event", "exception", "messaging", "modeling", "naming", "query", "serialization",
        "Copyable", "Identifier", "Ordered", "Version", "Wow",
    ).map { "me.ahoo.wow.api.$it" }

    /**
     * wow-api has no runtime dependency (06 §3, decision D66) and does not build on the 9.x parts of 9.x wow-api,
     * which go with 9.x.
     */
    val API_STANDS_ALONE = DependencyRule(
        name = "wow-api imports no framework, no JSON library, no kotlinx and no 9.x part of wow-api",
        modules = listOf(API.dir),
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS + listOf("tools.jackson", "com.fasterxml", "kotlinx") +
            API_9X,
    )

    val DEPENDENCY_RULES = listOf(
        CORE_IS_FRAMEWORK_FREE,
        KERNEL_IGNORES_RUNTIME,
        ONLY_HOST_USES_SPRING_BOOT,
        MANIFEST_IS_PLAIN_DATA,
        QUERY_API_IS_PLAIN_DATA,
        SCHEMA_GENERATION_AT_BUILD_TIME,
        API_STANDS_ALONE,
    )

    private val PROJECT_DEPENDENCY = Regex("""project\(\s*":([\w-]+)"\s*\)""")

    /** The projects [module]'s build script depends on. */
    fun projectDependencies(repositoryRoot: File, module: V10Module): Set<String> =
        PROJECT_DEPENDENCY.findAll(File(repositoryRoot, "${module.dir}/build.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toSet()

    /**
     * The V10 modules that replace a 9.x module of the same artifact (decision D85): the V10 project and the 9.x one
     * it replaces. Both define the same packages, so no project may reach both.
     */
    val REPLACED_9X: Map<String, String> = mapOf(
        "api" to "wow-api",
        "query-api" to "wow-api",
        "query" to "wow-query",
        "kafka" to "wow-kafka",
        "clickhouse" to "wow-clickhouse",
        "compiler" to "wow-compiler",
        "mongo" to "wow-mongo",
        "cosec" to "wow-cosec",
    )

    /**
     * Projects allowed to reach both sides of [REPLACED_9X] (decision D90): spike H3 measures the V10 kernel against
     * the 9.x one on the same JVM.
     */
    val BOTH_SIDES_EXEMPT: Set<String> = setOf("kernel-benchmarks")

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
     * No project reaches both a V10 module and the 9.x module it replaces (decisions D85, D90), directly or
     * transitively: their classes share names, so one would shadow the other. Only [BOTH_SIDES_EXEMPT] may.
     */
    fun bothSidesViolations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        return graph.keys.sorted()
            .filterNot { it in BOTH_SIDES_EXEMPT }
            .flatMap { project ->
                val reached = reachable(graph, project)
                REPLACED_9X.filter { (v10, nineX) -> v10 in reached && nineX in reached }
                    .map { (v10, nineX) -> "$project reaches both :$v10 and :$nineX" }
            }
    }

    private val V10_REGION = Regex("""(?ms)^//region v10\b(.*?)^//endregion""")

    /** The projects `settings.gradle.kts` includes in its `//region v10`: the V10 modules, spikes and tests. */
    fun v10SettingsProjects(repositoryRoot: File): Set<String> {
        val settings = File(repositoryRoot, "settings.gradle.kts").readText()
        val region = requireNotNull(V10_REGION.find(settings)) { "settings.gradle.kts has no //region v10." }
        return SETTINGS_INCLUDE.findAll(region.groupValues[1]).map { it.groupValues[2] }.toSet()
    }

    /**
     * No 9.x project depends on a V10 project (decision D85), directly or transitively: 9.x builds, tests and
     * publishes as it does on main, and V10 can change without touching it.
     */
    fun nineXOnV10Violations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        val v10 = v10SettingsProjects(repositoryRoot)
        return graph.keys.sorted()
            .filterNot { it in v10 }
            .flatMap { project ->
                reachable(graph, project).filter { it in v10 }.sorted().map { "$project reaches :$it" }
            }
    }

    /**
     * The example builds on the V10 modules only (decisions D118, D144), directly or transitively: it shows V10 as an
     * application uses it, with no 9.x module, spike or test project on its classpath. The example is every project
     * [isExample] names (`:shop-*`, `:inventory-*` and `:example-e2e`), which may build on each other.
     */
    fun exampleOutsideV10Violations(repositoryRoot: File): List<String> {
        val graph = projectGraph(repositoryRoot)
        val examples = graph.keys.filter(::isExample).sorted()
        val allowed = MODULES.map { it.name }.toSet() + examples
        return examples.flatMap { example ->
            reachable(graph, example).filterNot { it in allowed }.sorted().map { "$example reaches :$it" }
        }
    }

    private val COMPATIBILITY_MARKER = Regex("""@Deprecated\b|\bcompat\(""")

    /**
     * V10 keeps no compatibility code (decision D1): no `@Deprecated` declaration and no `compat(...)` marker in
     * any V10 module's production sources. History is handled once, in the migration layer.
     */
    fun compatibilityMarkers(repositoryRoot: File): List<String> =
        MODULES.flatMap { module ->
            listOf("src/main/kotlin", "src/main/java")
                .map { File(repositoryRoot, "${module.dir}/$it") }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (COMPATIBILITY_MARKER.containsMatchIn(line)) {
                            "${file.relativeTo(repositoryRoot).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
        }
}

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
    val MANIFEST = V10Module("manifest", "api/manifest")
    val QUERY_API = V10Module("query-api", "api/query-api")
    val SPI = V10Module("spi", "core/spi")
    val KERNEL = V10Module("kernel", "core/kernel")
    val RUNTIME = V10Module("runtime", "core/runtime")
    val REACTOR = V10Module("reactor", "core/reactor")
    val POSTGRES = V10Module("postgres", "backends/postgres")
    val MONGO = V10Module("mongo", "backends/mongo")
    val HTTP = V10Module("http", "edge/http")
    val SPRING_BOOT = V10Module("spring-boot", "host/spring-boot")
    val MIGRATE_DATA = V10Module("migrate-data", "migration/migrate-data")
    val COMPILER = V10Module("compiler", "compiler/compiler")
    val GRADLE_PLUGIN = V10Module("gradle-plugin", "compiler/gradle-plugin")
    val MANIFEST_TOOLS = V10Module("manifest-tools", "compiler/manifest-tools")

    val MODULES = listOf(
        MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME, REACTOR, POSTGRES, MONGO, HTTP, SPRING_BOOT, MIGRATE_DATA,
        COMPILER, GRADLE_PLUGIN, MANIFEST_TOOLS,
    )

    /** The modules an application runs with; the compiler, the Gradle plugin and its tools run in the build only. */
    val RUNTIME_MODULES = MODULES - COMPILER - GRADLE_PLUGIN - MANIFEST_TOOLS

    /**
     * The project dependencies each V10 module may declare (06 §3). A module not listed here, such as wow-api, is a
     * 9.x module V10 still builds on until it is rewritten in place.
     */
    val ALLOWED_PROJECT_DEPENDENCIES: Map<V10Module, Set<String>> = mapOf(
        MANIFEST to emptySet(),
        QUERY_API to emptySet(),
        SPI to setOf("wow-api"),
        KERNEL to setOf("spi", "manifest"),
        RUNTIME to setOf("kernel"),
        REACTOR to setOf("runtime"),
        POSTGRES to setOf("spi", "wow-query"),
        MONGO to setOf("spi"),
        HTTP to setOf("runtime", "wow-query"),
        SPRING_BOOT to setOf("runtime", "http"),
        MIGRATE_DATA to setOf("kernel"),
        // kernel and reactor only for the tests, which compile the generated models.
        COMPILER to setOf("wow-api", "manifest", "kernel", "reactor"),
        // compiler, kernel, manifest-tools and wow-dependencies only for the TestKit fixture and tests.
        GRADLE_PLUGIN to setOf("manifest", "manifest-tools", "kernel", "compiler", "wow-dependencies"),
        // kernel for the state fingerprint.
        MANIFEST_TOOLS to setOf("manifest", "kernel"),
    )

    private val SPRING = listOf("org.springframework")
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
        modules = listOf(MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME).map { it.dir },
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

    /** Schemas are introspected at build time only (decision D73): no runtime module generates one. */
    val SCHEMA_GENERATION_AT_BUILD_TIME = DependencyRule(
        name = "only wow-manifest-tools imports the JSON Schema generator",
        modules = RUNTIME_MODULES.map { it.dir },
        forbidden = listOf("com.github.victools"),
    )

    /** The directory of wow-api, a 9.x module that V10 rewrites in place (06 §2.1, decision D66). */
    const val API_DIR = "wow-api"

    /** The V10 packages of wow-api; `annotation` is not one of them, since it is shared with 9.x until wow-core goes. */
    val V10_API_PACKAGES = listOf("aggregate", "decide", "subscribe", "result").map { "me.ahoo.wow.api.$it" }

    /** The 9.x parts of wow-api, deleted with wow-core (or, for `query`, moved to wow-query-api in M9). */
    private val API_9X = listOf(
        "abac", "command", "event", "exception", "messaging", "modeling", "naming", "query", "serialization",
        "Copyable", "Identifier", "Ordered", "Version", "Wow",
    ).map { "me.ahoo.wow.api.$it" }

    /**
     * wow-api's V10 packages have no runtime dependency and do not build on its 9.x parts (06 §3, decision D66): they
     * must outlive both.
     */
    val V10_API_STANDS_ALONE = DependencyRule(
        name = "the V10 packages of wow-api import no framework and no 9.x part of wow-api",
        modules = listOf(API_DIR),
        forbidden = SPRING + REACTOR_TYPES + HTTP_TYPES + DRIVERS +
            listOf("tools.jackson", "com.fasterxml", "kotlinx.coroutines") + API_9X,
        appliesToPackage = { it in V10_API_PACKAGES },
    )

    val DEPENDENCY_RULES = listOf(
        CORE_IS_FRAMEWORK_FREE,
        KERNEL_IGNORES_RUNTIME,
        ONLY_HOST_USES_SPRING_BOOT,
        MANIFEST_IS_PLAIN_DATA,
        SCHEMA_GENERATION_AT_BUILD_TIME,
        V10_API_STANDS_ALONE,
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
        "compiler" to "wow-compiler",
        "mongo" to "wow-mongo",
    )

    /** Projects allowed to reach both sides of [REPLACED_9X] (decision D90). */
    val BOTH_SIDES_EXEMPT: Set<String> = emptySet()

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

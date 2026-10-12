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
    val MANIFEST = V10Module("wow-manifest", "api/wow-manifest")
    val QUERY_API = V10Module("wow-query-api", "api/wow-query-api")
    val SPI = V10Module("wow-spi", "core/wow-spi")
    val KERNEL = V10Module("wow-kernel", "core/wow-kernel")
    val RUNTIME = V10Module("wow-runtime", "core/wow-runtime")
    val REACTOR = V10Module("wow-reactor", "core/wow-reactor")
    val POSTGRES = V10Module("wow-postgres", "backends/wow-postgres")
    val HTTP = V10Module("wow-http", "edge/wow-http")
    val SPRING_BOOT = V10Module("wow-spring-boot", "host/wow-spring-boot")
    val MIGRATE_DATA = V10Module("wow-migrate-data", "migration/wow-migrate-data")
    val GRADLE_PLUGIN = V10Module("wow-gradle-plugin", "compiler/wow-gradle-plugin")

    val MODULES = listOf(
        MANIFEST, QUERY_API, SPI, KERNEL, RUNTIME, REACTOR, POSTGRES, HTTP, SPRING_BOOT, MIGRATE_DATA, GRADLE_PLUGIN,
    )

    /** The modules an application runs with; the Gradle plugin runs in the build only. */
    val RUNTIME_MODULES = MODULES - GRADLE_PLUGIN

    /**
     * The project dependencies each V10 module may declare (06 §3). A module not listed here, such as wow-api, is a
     * 9.x module V10 still builds on until it is rewritten in place.
     */
    val ALLOWED_PROJECT_DEPENDENCIES: Map<V10Module, Set<String>> = mapOf(
        MANIFEST to emptySet(),
        QUERY_API to emptySet(),
        SPI to setOf("wow-api"),
        KERNEL to setOf("wow-spi", "wow-manifest"),
        RUNTIME to setOf("wow-kernel"),
        REACTOR to setOf("wow-runtime"),
        POSTGRES to setOf("wow-spi", "wow-query"),
        HTTP to setOf("wow-runtime", "wow-query"),
        SPRING_BOOT to setOf("wow-runtime", "wow-http"),
        MIGRATE_DATA to setOf("wow-kernel"),
        // wow-kernel for the state fingerprint; wow-compiler and wow-dependencies only as files of the
        // TestKit fixture.
        GRADLE_PLUGIN to setOf("wow-manifest", "wow-kernel", "wow-compiler", "wow-dependencies"),
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
        name = "only wow-gradle-plugin imports the JSON Schema generator",
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

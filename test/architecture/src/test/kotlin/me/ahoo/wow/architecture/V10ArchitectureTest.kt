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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Guards the V10 architecture rules. The Gradle `test` task passes the repository root (`wow.repository.root`)
 * and declares the scanned build scripts and sources as inputs.
 */
class V10ArchitectureTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `every V10 module and the example should exist`() {
        (V10Rules.MODULES + V10Rules.EXAMPLES).forEach { module ->
            File(repositoryRoot, "${module.dir}/build.gradle.kts").isFile.assert()
                .describedAs("${module.dir}/build.gradle.kts must exist under $repositoryRoot")
                .isTrue()
        }
    }

    @TestFactory
    fun `V10 modules should depend only on the projects the module graph allows`(): List<DynamicTest> =
        V10Rules.MODULES.map { module ->
            DynamicTest.dynamicTest(module.name) {
                val allowed = V10Rules.ALLOWED_PROJECT_DEPENDENCIES.getValue(module)
                V10Rules.projectDependencies(repositoryRoot, module).minus(allowed).assert()
                    .describedAs("${module.name} depends on projects outside $allowed")
                    .isEmpty()
            }
        }

    @TestFactory
    fun `V10 production sources should follow the dependency rules`(): List<DynamicTest> =
        V10Rules.DEPENDENCY_RULES.map { rule ->
            DynamicTest.dynamicTest(rule.name) {
                rule.violations(repositoryRoot).assert()
                    .describedAs("Rule violated: ${rule.name}")
                    .isEmpty()
            }
        }

    @Test
    fun `no project should reach both a V10 module and the 9x module it replaces`() {
        V10Rules.bothSidesViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `no 9x project should depend on a V10 project`() {
        V10Rules.nineXOnV10Violations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the example should reach V10 modules only`() {
        V10Rules.exampleOutsideV10Violations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `V10 production sources should carry no compatibility code`() {
        V10Rules.compatibilityMarkers(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `core rule should catch a Reactor import and a fully-qualified driver reference`(@TempDir root: File) {
        root.writeSource(
            V10Rules.KERNEL.dir,
            "me/ahoo/wow/kernel/Bad.kt",
            """
            package me.ahoo.wow.kernel

            import reactor.core.publisher.Mono

            // org.postgresql.Driver in a comment does not count
            val client = org.postgresql.Driver()
            """.trimIndent(),
        )
        V10Rules.CORE_IS_FRAMEWORK_FREE.violations(root).assert().containsExactly(
            "${V10Rules.KERNEL.dir}/src/main/kotlin/me/ahoo/wow/kernel/Bad.kt:3: reactor.core.publisher.Mono",
            "${V10Rules.KERNEL.dir}/src/main/kotlin/me/ahoo/wow/kernel/Bad.kt:6: org.postgresql.Driver",
        )
    }

    @Test
    fun `api rule should catch a 9x type, Jackson or kotlinx anywhere in wow-api`(@TempDir root: File) {
        root.writeSource(
            V10Rules.API.dir,
            "me/ahoo/wow/api/subscribe/Bad.kt",
            """
            package me.ahoo.wow.api.subscribe

            import me.ahoo.wow.api.messaging.Message
            import tools.jackson.databind.JsonNode
            """.trimIndent(),
        )
        root.writeSource(
            V10Rules.API.dir,
            "me/ahoo/wow/api/annotation/Old.kt",
            """
            package me.ahoo.wow.api.annotation

            import kotlinx.coroutines.flow.Flow
            import me.ahoo.wow.api.messaging.function.FunctionKind
            """.trimIndent(),
        )
        val api = "${V10Rules.API.dir}/src/main/kotlin/me/ahoo/wow/api"
        V10Rules.API_STANDS_ALONE.violations(root).assert().containsExactly(
            "$api/annotation/Old.kt:3: kotlinx.coroutines.flow.Flow",
            "$api/annotation/Old.kt:4: me.ahoo.wow.api.messaging.function.FunctionKind",
            "$api/subscribe/Bad.kt:3: me.ahoo.wow.api.messaging.Message",
            "$api/subscribe/Bad.kt:4: tools.jackson.databind.JsonNode",
        )
    }

    @Test
    fun `manifest and schema rules should catch a JSON library and the schema generator`(@TempDir root: File) {
        root.writeSource(
            V10Rules.MANIFEST.dir,
            "me/ahoo/wow/manifest/Bad.kt",
            """
            package me.ahoo.wow.manifest

            import com.github.victools.jsonschema.generator.SchemaGenerator
            import tools.jackson.databind.JsonNode
            """.trimIndent(),
        )
        val bad = "${V10Rules.MANIFEST.dir}/src/main/kotlin/me/ahoo/wow/manifest/Bad.kt"
        V10Rules.MANIFEST_IS_PLAIN_DATA.violations(root).assert()
            .containsExactly("$bad:4: tools.jackson.databind.JsonNode")
        V10Rules.SCHEMA_GENERATION_AT_BUILD_TIME.violations(root).assert()
            .containsExactly("$bad:3: com.github.victools.jsonschema.generator.SchemaGenerator")
    }

    @Test
    fun `query-api rule should catch a JSON library`(@TempDir root: File) {
        root.writeSource(
            V10Rules.QUERY_API.dir,
            "me/ahoo/wow/api/query/Bad.kt",
            """
            package me.ahoo.wow.api.query

            import com.fasterxml.jackson.annotation.JsonProperty
            import kotlinx.serialization.Serializable
            import tools.jackson.databind.JsonNode
            import java.time.Duration
            """.trimIndent(),
        )
        val bad = "${V10Rules.QUERY_API.dir}/src/main/kotlin/me/ahoo/wow/api/query/Bad.kt"
        V10Rules.QUERY_API_IS_PLAIN_DATA.violations(root).assert().containsExactly(
            "$bad:3: com.fasterxml.jackson.annotation.JsonProperty",
            "$bad:4: kotlinx.serialization.Serializable",
            "$bad:5: tools.jackson.databind.JsonNode",
        )
    }

    @Test
    fun `module graph should read the project dependencies of a build script`(@TempDir root: File) {
        File(root, V10Rules.KERNEL.dir).apply { mkdirs() }.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                api(project(":spi"))
                implementation(project( ":runtime" ))
            }
            """.trimIndent(),
        )
        V10Rules.projectDependencies(root, V10Rules.KERNEL).assert().containsExactlyInAnyOrder("spi", "runtime")
    }

    @Test
    fun `module graph should read the projects and their directories from the settings`(@TempDir root: File) {
        File(root, "settings.gradle.kts").writeText(
            """
            include(":wow-compiler")
            include("example-domain")
            include(":compiler")
            project(":compiler").projectDir = file("compiler/compiler")
            """.trimIndent(),
        )
        V10Rules.settingsProjects(root).assert().containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "wow-compiler" to "wow-compiler",
                "example-domain" to "example-domain",
                "compiler" to "compiler/compiler",
            ),
        )
    }

    @Test
    fun `both sides rule should catch a project reaching both sides transitively, but the exempt one`(
        @TempDir root: File
    ) {
        root.writeBuild(
            mapOf(
                "compiler" to "compiler/compiler",
                "wow-compiler" to "wow-compiler",
                "nine-x" to "nine-x",
                "app" to "app",
                "v10-only" to "v10-only",
                "kernel-benchmarks" to "test/kernel-benchmarks",
            ),
            mapOf(
                "nine-x" to listOf("wow-compiler"),
                "app" to listOf("compiler", "nine-x"),
                "v10-only" to listOf("compiler"),
                "kernel-benchmarks" to listOf("compiler", "wow-compiler"),
            ),
        )
        V10Rules.bothSidesViolations(root).assert().containsExactly("app reaches both :compiler and :wow-compiler")
    }

    @Test
    fun `9x rule should catch a 9x project reaching a V10 project transitively`(@TempDir root: File) {
        root.writeBuild(
            mapOf(
                "wow-mongo" to "wow-mongo",
                "wow-it" to "test/wow-it",
                "spi" to "core/spi",
                "mongo" to "backends/mongo",
            ),
            mapOf("wow-it" to listOf("wow-mongo"), "wow-mongo" to listOf("spi"), "mongo" to listOf("spi")),
            v10 = setOf("spi", "mongo"),
        )
        V10Rules.v10SettingsProjects(root).assert().containsExactlyInAnyOrder("spi", "mongo")
        V10Rules.nineXOnV10Violations(root).assert().containsExactly("wow-it reaches :spi", "wow-mongo reaches :spi")
    }

    @Test
    fun `example rule should catch the example reaching a 9x module or a spike transitively`(@TempDir root: File) {
        root.writeBuild(
            mapOf(
                "wow-spring" to "wow-spring",
                "api" to "api/api",
                "spring-boot" to "host/spring-boot",
                "routing-spike" to "test/routing-spike",
                "shop-api" to "example/shop/shop-api",
                "shop-domain" to "example/shop/shop-domain",
                "shop-server" to "example/shop/shop-server",
                "inventory-domain" to "example/inventory/inventory-domain",
                "example-e2e" to "example/e2e",
                "wow-core" to "wow-core",
                "lease-spike" to "test/lease-spike",
            ),
            mapOf(
                "spring-boot" to listOf("wow-spring"),
                "shop-api" to listOf("api"),
                "shop-domain" to listOf("shop-api", "routing-spike"),
                "shop-server" to listOf("shop-api", "spring-boot"),
                "inventory-domain" to listOf("api", "wow-core"),
                "example-e2e" to listOf("shop-api", "inventory-domain", "lease-spike"),
            ),
            v10 = setOf(
                "api", "spring-boot", "routing-spike", "lease-spike", "shop-api", "shop-domain", "shop-server",
                "inventory-domain", "example-e2e",
            ),
        )
        V10Rules.exampleOutsideV10Violations(root).assert().containsExactly(
            "example-e2e reaches :lease-spike",
            "example-e2e reaches :wow-core",
            "inventory-domain reaches :wow-core",
            "shop-domain reaches :routing-spike",
            "shop-server reaches :wow-spring",
        )
    }

    @Test
    fun `compatibility check should catch a deprecation and a compat marker`(@TempDir root: File) {
        root.writeSource(
            V10Rules.RUNTIME.dir,
            "me/ahoo/wow/runtime/Old.kt",
            """
            package me.ahoo.wow.runtime

            @Deprecated("old")
            fun old() = Unit // compat(wow<10): kept for 9.x
            """.trimIndent(),
        )
        V10Rules.compatibilityMarkers(root).assert().hasSize(2)
    }

    /**
     * Writes a settings script including [projects] (name to directory), the [v10] ones in its `//region v10`, and a
     * build script per project.
     */
    private fun File.writeBuild(
        projects: Map<String, String>,
        dependencies: Map<String, List<String>>,
        v10: Set<String> = emptySet(),
    ) {
        fun includes(names: Collection<String>) = names.joinToString("\n") { name ->
            "include(\":$name\")\nproject(\":$name\").projectDir = file(\"${projects.getValue(name)}\")"
        }
        File(this, "settings.gradle.kts").writeText(
            includes(projects.keys - v10) + "\n\n//region v10\n" + includes(v10) + "\n//endregion\n",
        )
        projects.forEach { (name, dir) ->
            File(this, dir).apply { mkdirs() }.resolve("build.gradle.kts").writeText(
                dependencies[name].orEmpty().joinToString("\n", "dependencies {\n", "\n}") {
                    "    implementation(project(\":$it\"))"
                },
            )
        }
    }

    private fun File.writeSource(moduleDir: String, path: String, content: String) {
        File(this, "$moduleDir/src/main/kotlin/$path").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}

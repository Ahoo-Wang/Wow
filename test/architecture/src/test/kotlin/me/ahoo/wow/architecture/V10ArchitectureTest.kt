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
        (V10Rules.MODULES + V10Rules.EXAMPLES + V10Rules.PLATFORM_SERVERS).forEach { module ->
            File(repositoryRoot, "${module.dir}/build.gradle.kts").isFile.assert()
                .describedAs("${module.dir}/build.gradle.kts must exist under $repositoryRoot")
                .isTrue()
        }
    }

    @Test
    fun `every example project the settings include should be listed among the examples`() {
        V10Rules.v10SettingsProjects(repositoryRoot).filter(V10Rules::isExample).toSet().assert()
            .describedAs("the example projects of settings.gradle.kts, as V10Rules.EXAMPLES lists them")
            .isEqualTo(V10Rules.EXAMPLES.map { it.name }.toSet())
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
    fun `platform services should depend only on the projects their rules allow`(): List<DynamicTest> =
        V10Rules.PLATFORM_SERVERS.map { server ->
            DynamicTest.dynamicTest(server.name) {
                val allowed = V10Rules.ALLOWED_SERVER_DEPENDENCIES.getValue(server)
                V10Rules.projectDependencies(repositoryRoot, server).minus(allowed).assert()
                    .describedAs("${server.name} depends on projects outside $allowed")
                    .isEmpty()
            }
        }

    @Test
    fun `platform services should reach V10 modules only`() {
        V10Rules.platformOutsideV10Violations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the platform's modules and services should be in the settings' V10 region`() {
        V10Rules.v10SettingsProjects(repositoryRoot).assert()
            .containsAll((V10Rules.PLATFORM_MODULES + V10Rules.PLATFORM_SERVERS).map { it.name })
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
    fun `the example's domain and API modules should not reach the runtime`() {
        V10Rules.exampleDomainOnRuntimeViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `V10 production sources should carry no compatibility code`() {
        V10Rules.compatibilityMarkers(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the ABI of the api, spi and manifest modules should hold no data class but the listed exceptions`() {
        V10Rules.dataClassMembers(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `data class rule should catch a copy or componentN and a stale exception`(@TempDir root: File) {
        val exceptions = V10Rules.DATA_CLASS_EXCEPTIONS.keys.toList()
        V10Rules.PLAIN_CLASS_MODULES.forEach { module ->
            File(root, "${module.dir}/api").mkdirs()
            File(root, "${module.dir}/api/${module.name}.api").writeText("")
        }
        File(root, "${V10Rules.API.dir}/api/${V10Rules.API.name}.api").writeText(
            (exceptions.drop(1) + "me/ahoo/wow/api/Record").joinToString("\n") { owner ->
                """
                public final class $owner {
                	public final fun component1 ()Ljava/lang/String;
                	public final fun copy (Ljava/lang/String;)L$owner;
                	public static synthetic fun copy${'$'}default (L$owner;Ljava/lang/String;ILjava/lang/Object;)L$owner;
                	public fun equals (Ljava/lang/Object;)Z
                }
                """.trimIndent()
            },
        )
        V10Rules.dataClassMembers(root).assert().containsExactlyInAnyOrder(
            "api/api/api/api.api: me/ahoo/wow/api/Record: public final fun component1 ()Ljava/lang/String;",
            "api/api/api/api.api: me/ahoo/wow/api/Record: public final fun copy (Ljava/lang/String;)Lme/ahoo/wow/api/Record;",
            "api/api/api/api.api: me/ahoo/wow/api/Record: public static synthetic fun copy${'$'}default " +
                "(Lme/ahoo/wow/api/Record;Ljava/lang/String;ILjava/lang/Object;)Lme/ahoo/wow/api/Record;",
            "stale exception: ${exceptions.first()}",
        )
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
    fun `http read rule should catch the log or the kernel in the query and read packages only`(@TempDir root: File) {
        root.writeSource(
            V10Rules.HTTP.dir,
            "me/ahoo/wow/http/read/Bad.kt",
            """
            package me.ahoo.wow.http.read

            import me.ahoo.wow.spi.log.EventLog

            val kernel: me.ahoo.wow.kernel.Kernel? = null
            """.trimIndent(),
        )
        root.writeSource(
            V10Rules.HTTP.dir,
            "me/ahoo/wow/http/query/Bad.kt",
            """
            package me.ahoo.wow.http.query

            import me.ahoo.wow.spi.snapshot.SnapshotStore
            """.trimIndent(),
        )
        // The rest of wow-http may read them: the command routes run the kernel's gateway.
        root.writeSource(
            V10Rules.HTTP.dir,
            "me/ahoo/wow/http/command/Fine.kt",
            """
            package me.ahoo.wow.http.command

            import me.ahoo.wow.spi.log.EventLog
            """.trimIndent(),
        )
        V10Rules.HTTP_READS_ADAPT_ONLY.violations(root).assert().containsExactly(
            "${V10Rules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/query/Bad.kt:3: me.ahoo.wow.spi.snapshot.SnapshotStore",
            "${V10Rules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:3: me.ahoo.wow.spi.log.EventLog",
            "${V10Rules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:5: me.ahoo.wow.kernel.Kernel",
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
    fun `module graph should allow the test modules and the Manifest to a module's tests only`(@TempDir root: File) {
        File(root, V10Rules.POSTGRES.dir).apply { mkdirs() }.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                api(project(":spi"))
                implementation(project(":tck-containers"))
                testImplementation(project(":test-dsl"))
                testImplementation(project(":manifest"))
                testImplementation(testFixtures(project(":manifest")))
                integrationTestImplementation(project( ":tck" ))
                integrationTestImplementation(project(":runtime"))
            }
            """.trimIndent(),
        )
        V10Rules.projectDependencies(root, V10Rules.POSTGRES).assert()
            .containsExactlyInAnyOrder("spi", "tck-containers", "runtime")
    }

    @Test
    fun `test module rule should catch Testcontainers or a driver in the TCK`(@TempDir root: File) {
        root.writeSource(
            V10Rules.TCK.dir,
            "me/ahoo/wow/spi/Bad.kt",
            """
            package me.ahoo.wow.spi

            import org.testcontainers.postgresql.PostgreSQLContainer

            val url = org.postgresql.Driver::class
            """.trimIndent(),
        )
        val bad = "${V10Rules.TCK.dir}/src/main/kotlin/me/ahoo/wow/spi/Bad.kt"
        V10Rules.TEST_MODULES_ARE_FRAMEWORK_FREE.violations(root).assert().containsExactly(
            "$bad:3: org.testcontainers.postgresql.PostgreSQLContainer",
            "$bad:5: org.postgresql.Driver",
        )
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
                "eshop-catalog-api" to "example/eshop/catalog/catalog-api",
                "eshop-catalog-domain" to "example/eshop/catalog/catalog-domain",
                "eshop-catalog-server" to "example/eshop/catalog/catalog-server",
                "eshop-inventory-domain" to "example/eshop/inventory/inventory-domain",
                "eshop-e2e" to "example/eshop/e2e",
                "wow-core" to "wow-core",
                "lease-spike" to "test/lease-spike",
            ),
            mapOf(
                "spring-boot" to listOf("wow-spring"),
                "eshop-catalog-api" to listOf("api"),
                "eshop-catalog-domain" to listOf("eshop-catalog-api", "routing-spike"),
                "eshop-catalog-server" to listOf("eshop-catalog-api", "spring-boot"),
                "eshop-inventory-domain" to listOf("api", "wow-core"),
                "eshop-e2e" to listOf("eshop-catalog-api", "eshop-inventory-domain", "lease-spike"),
            ),
            v10 = setOf(
                "api", "spring-boot", "routing-spike", "lease-spike", "eshop-catalog-api", "eshop-catalog-domain",
                "eshop-catalog-server", "eshop-inventory-domain", "eshop-e2e",
            ),
        )
        V10Rules.exampleOutsideV10Violations(root).assert().containsExactly(
            "eshop-catalog-domain reaches :routing-spike",
            "eshop-catalog-server reaches :wow-spring",
            "eshop-e2e reaches :lease-spike",
            "eshop-e2e reaches :wow-core",
            "eshop-inventory-domain reaches :wow-core",
        )
    }

    @Test
    fun `the eShop should be five contexts of an API, a domain and a server each, and its e2e tests`() {
        V10Rules.ESHOP.map { it.name }.assert().containsExactly(
            "eshop-catalog-api", "eshop-catalog-domain", "eshop-catalog-server",
            "eshop-basket-api", "eshop-basket-domain", "eshop-basket-server",
            "eshop-ordering-api", "eshop-ordering-domain", "eshop-ordering-server",
            "eshop-inventory-api", "eshop-inventory-domain", "eshop-inventory-server",
            "eshop-payment-api", "eshop-payment-domain", "eshop-payment-server",
            "eshop-e2e",
        )
        V10Rules.settingsProjects(repositoryRoot).assert()
            .containsAllEntriesOf(V10Rules.ESHOP.associate { it.name to it.dir })
        V10Rules.v10SettingsProjects(repositoryRoot).assert().containsAll(V10Rules.ESHOP.map { it.name })
    }

    @Test
    fun `eShop domain modules should be its APIs and domains, not its servers or e2e tests`() {
        listOf("eshop-catalog-api", "eshop-catalog-domain", "eshop-payment-api", "eshop-payment-domain").forEach {
            V10Rules.isExampleDomain(it).assert().describedAs(it).isTrue()
        }
        listOf("eshop-catalog-server", "eshop-payment-server", "eshop-e2e", "quickstart").forEach {
            V10Rules.isExample(it).assert().describedAs(it).isTrue()
            V10Rules.isExampleDomain(it).assert().describedAs(it).isFalse()
        }
    }

    @Test
    fun `example domain rule should catch an eShop domain or API module reaching the runtime`(@TempDir root: File) {
        root.writeBuild(
            mapOf(
                "kernel" to "core/kernel",
                "runtime" to "core/runtime",
                "spring-boot" to "host/spring-boot",
                "eshop-catalog-api" to "example/eshop/catalog/catalog-api",
                "eshop-catalog-domain" to "example/eshop/catalog/catalog-domain",
                "eshop-catalog-server" to "example/eshop/catalog/catalog-server",
                "eshop-payment-api" to "example/eshop/payment/payment-api",
                "eshop-payment-domain" to "example/eshop/payment/payment-domain",
                "eshop-e2e" to "example/eshop/e2e",
            ),
            mapOf(
                "runtime" to listOf("kernel"),
                "spring-boot" to listOf("runtime"),
                "eshop-catalog-domain" to listOf("eshop-catalog-api", "kernel"),
                "eshop-catalog-server" to listOf("eshop-catalog-domain", "spring-boot"),
                "eshop-payment-api" to listOf("runtime"),
                "eshop-payment-domain" to listOf("eshop-payment-api", "kernel"),
                "eshop-e2e" to listOf("eshop-catalog-server", "runtime"),
            ),
        )
        V10Rules.exampleDomainOnRuntimeViolations(root).assert().containsExactly(
            "eshop-payment-api reaches :runtime",
            "eshop-payment-domain reaches :runtime",
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

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
 * Guards the architecture rules. The Gradle `test` task passes the repository root (`wow.repository.root`)
 * and declares the scanned build scripts and sources as inputs.
 */
class ArchitectureTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `every module and the example should exist`() {
        (ArchitectureRules.MODULES + ArchitectureRules.EXAMPLES + ArchitectureRules.PLATFORM_SERVERS).forEach { module ->
            File(repositoryRoot, "${module.dir}/build.gradle.kts").isFile.assert()
                .describedAs("${module.dir}/build.gradle.kts must exist under $repositoryRoot")
                .isTrue()
        }
    }

    @Test
    fun `every example project the settings include should be listed among the examples`() {
        ArchitectureRules.wowSettingsProjects(repositoryRoot).filter(ArchitectureRules::isExample).toSet().assert()
            .describedAs("the example projects of settings.gradle.kts, as ArchitectureRules.EXAMPLES lists them")
            .isEqualTo(ArchitectureRules.EXAMPLES.map { it.name }.toSet())
    }

    @Test
    fun `every project of the settings' wow region should be in one group of the rules`() {
        val groups = ArchitectureRules.WOW_PROJECTS.map { it.name }
        groups.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.assert()
            .describedAs("projects in more than one group").isEmpty()
        ArchitectureRules.wowSettingsProjects(repositoryRoot).assert()
            .describedAs("the projects of the settings' wow region, as ArchitectureRules.WOW_PROJECTS lists them")
            .isEqualTo(groups.toSet())
        ArchitectureRules.TOOLS.forEach { tool ->
            File(repositoryRoot, "${tool.dir}/build.gradle.kts").isFile.assert().describedAs(tool.dir).isTrue()
        }
    }

    @Test
    fun `every module and platform service should have its allowed dependencies`() {
        ArchitectureRules.ALLOWED_PROJECT_DEPENDENCIES.keys.assert().isEqualTo(ArchitectureRules.MODULES.toSet())
        ArchitectureRules.ALLOWED_SERVER_DEPENDENCIES.keys.assert()
            .isEqualTo(ArchitectureRules.PLATFORM_SERVERS.toSet())
    }

    @Test
    fun `no project of the wow region should reach a 9x project`() {
        ArchitectureRules.nineXReachViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `no application of the Wow modules should opt in to their internal API`() {
        ArchitectureRules.internalOptIns(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `9x reach rule should catch a project reaching a 9x project, but the shared versions`(@TempDir root: File) {
        root.writeBuild(
            mapOf(
                "wow-core" to "wow-core",
                "wow-dependencies" to "wow-dependencies",
                "spi" to "core/spi",
                "routing-spike" to "test/routing-spike",
            ),
            mapOf(
                "spi" to listOf("wow-dependencies"),
                "routing-spike" to listOf("spi", "wow-core"),
            ),
            wow = setOf("spi", "routing-spike"),
        )
        ArchitectureRules.nineXReachViolations(root).assert().containsExactly("routing-spike reaches :wow-core")
    }

    @Test
    fun `internal opt-in rule should catch an application's opt-in in its sources or build only`(@TempDir root: File) {
        val server = ArchitectureRules.OPS_SERVER.dir
        root.writeSource(
            server,
            "me/ahoo/wow/ops/server/Bad.kt",
            "package me.ahoo.wow.ops.server\n\n@OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)\nfun bad() = Unit\n",
        )
        File(
            root,
            "$server/build.gradle.kts"
        ).writeText("kotlin { compilerOptions { optIn.add(\"InternalWowApi\") } }\n")
        File(root, "$server/src/test/kotlin/Fixture.kt").apply { parentFile.mkdirs() }
            .writeText("@OptIn(InternalWowApi::class)\nfun fixture() = Unit\n")
        // Wow's own modules opt in through the root build.
        root.writeSource(ArchitectureRules.RUNTIME.dir, "Own.kt", "@OptIn(InternalWowApi::class)\nfun own() = Unit\n")
        ArchitectureRules.internalOptIns(root).assert().containsExactly(
            "$server/build.gradle.kts:1: kotlin { compilerOptions { optIn.add(\"InternalWowApi\") } }",
            "$server/src/main/kotlin/me/ahoo/wow/ops/server/Bad.kt:3: " +
                "@OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)",
        )
    }

    @TestFactory
    fun `modules should depend only on the projects the module graph allows`(): List<DynamicTest> =
        ArchitectureRules.MODULES.map { module ->
            DynamicTest.dynamicTest(module.name) {
                val allowed = ArchitectureRules.ALLOWED_PROJECT_DEPENDENCIES.getValue(module)
                ArchitectureRules.projectDependencies(repositoryRoot, module).minus(allowed).assert()
                    .describedAs("${module.name} depends on projects outside $allowed")
                    .isEmpty()
            }
        }

    @TestFactory
    fun `platform services should depend only on the projects their rules allow`(): List<DynamicTest> =
        ArchitectureRules.PLATFORM_SERVERS.map { server ->
            DynamicTest.dynamicTest(server.name) {
                val allowed = ArchitectureRules.ALLOWED_SERVER_DEPENDENCIES.getValue(server)
                ArchitectureRules.projectDependencies(repositoryRoot, server).minus(allowed).assert()
                    .describedAs("${server.name} depends on projects outside $allowed")
                    .isEmpty()
            }
        }

    @Test
    fun `platform services should reach Wow modules only`() {
        ArchitectureRules.platformOutsideWowViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the platform's modules and services should be in the settings' wow region`() {
        ArchitectureRules.wowSettingsProjects(repositoryRoot).assert()
            .containsAll((ArchitectureRules.PLATFORM_MODULES + ArchitectureRules.PLATFORM_SERVERS).map { it.name })
    }

    @TestFactory
    fun `production sources should follow the dependency rules`(): List<DynamicTest> =
        ArchitectureRules.DEPENDENCY_RULES.map { rule ->
            DynamicTest.dynamicTest(rule.name) {
                rule.violations(repositoryRoot).assert()
                    .describedAs("Rule violated: ${rule.name}")
                    .isEmpty()
            }
        }

    @Test
    fun `no deleted 9x project should come back or be depended on`() {
        ArchitectureRules.deletedNineXViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the example should reach Wow modules only`() {
        ArchitectureRules.exampleOutsideWowViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the example's domain and API modules should not reach the runtime`() {
        ArchitectureRules.exampleDomainOnRuntimeViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `production sources should carry no compatibility code`() {
        ArchitectureRules.compatibilityMarkers(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the ABI of the api, spi and manifest modules should hold no data class but the listed exceptions`() {
        ArchitectureRules.dataClassMembers(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `data class rule should catch a copy or componentN and a stale exception`(@TempDir root: File) {
        val exceptions = ArchitectureRules.DATA_CLASS_EXCEPTIONS.keys.toList()
        ArchitectureRules.PLAIN_CLASS_MODULES.forEach { module ->
            File(root, "${module.dir}/api").mkdirs()
            File(root, "${module.dir}/api/${module.name}.api").writeText("")
        }
        File(root, "${ArchitectureRules.API.dir}/api/${ArchitectureRules.API.name}.api").writeText(
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
        ArchitectureRules.dataClassMembers(root).assert().containsExactlyInAnyOrder(
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
            ArchitectureRules.KERNEL.dir,
            "me/ahoo/wow/kernel/Bad.kt",
            """
            package me.ahoo.wow.kernel

            import reactor.core.publisher.Mono

            // org.postgresql.Driver in a comment does not count
            val client = org.postgresql.Driver()
            """.trimIndent(),
        )
        ArchitectureRules.CORE_IS_FRAMEWORK_FREE.violations(root).assert().containsExactly(
            "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/me/ahoo/wow/kernel/Bad.kt:3: reactor.core.publisher.Mono",
            "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/me/ahoo/wow/kernel/Bad.kt:6: org.postgresql.Driver",
        )
    }

    @Test
    fun `http read rule should catch the log or the kernel in the query and read packages only`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/read/Bad.kt",
            """
            package me.ahoo.wow.http.read

            import me.ahoo.wow.spi.log.EventLog

            val kernel: me.ahoo.wow.kernel.Kernel? = null
            """.trimIndent(),
        )
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/query/Bad.kt",
            """
            package me.ahoo.wow.http.query

            import me.ahoo.wow.spi.snapshot.SnapshotStore
            """.trimIndent(),
        )
        // The rest of wow-http may read them: the command routes run the kernel's gateway.
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/command/Fine.kt",
            """
            package me.ahoo.wow.http.command

            import me.ahoo.wow.spi.log.EventLog
            """.trimIndent(),
        )
        ArchitectureRules.HTTP_READS_ADAPT_ONLY.violations(root).assert().containsExactly(
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/query/Bad.kt:3: me.ahoo.wow.spi.snapshot.SnapshotStore",
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:3: me.ahoo.wow.spi.log.EventLog",
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:5: me.ahoo.wow.kernel.Kernel",
        )
    }

    @Test
    fun `api rule should catch a 9x type, Jackson or kotlinx but Flow anywhere in wow-api`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.API.dir,
            "me/ahoo/wow/api/subscribe/Bad.kt",
            """
            package me.ahoo.wow.api.subscribe

            import me.ahoo.wow.api.messaging.Message
            import tools.jackson.databind.JsonNode
            """.trimIndent(),
        )
        root.writeSource(
            ArchitectureRules.API.dir,
            "me/ahoo/wow/api/annotation/Old.kt",
            """
            package me.ahoo.wow.api.annotation

            import kotlinx.coroutines.CoroutineScope
            import me.ahoo.wow.api.messaging.function.FunctionKind
            import kotlinx.coroutines.flow.Flow
            import me.ahoo.wow.api.query.IListQuery
            """.trimIndent(),
        )
        val api = "${ArchitectureRules.API.dir}/src/main/kotlin/me/ahoo/wow/api"
        ArchitectureRules.API_STANDS_ALONE.violations(root).assert().containsExactly(
            "$api/annotation/Old.kt:3: kotlinx.coroutines.CoroutineScope",
            "$api/annotation/Old.kt:4: me.ahoo.wow.api.messaging.function.FunctionKind",
            "$api/subscribe/Bad.kt:3: me.ahoo.wow.api.messaging.Message",
            "$api/subscribe/Bad.kt:4: tools.jackson.databind.JsonNode",
        )
    }

    @Test
    fun `manifest and schema rules should catch a JSON library and the schema generator`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.MANIFEST.dir,
            "me/ahoo/wow/manifest/Bad.kt",
            """
            package me.ahoo.wow.manifest

            import com.github.victools.jsonschema.generator.SchemaGenerator
            import tools.jackson.databind.JsonNode
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.MANIFEST.dir}/src/main/kotlin/me/ahoo/wow/manifest/Bad.kt"
        ArchitectureRules.MANIFEST_IS_PLAIN_DATA.violations(root).assert()
            .containsExactly("$bad:4: tools.jackson.databind.JsonNode")
        ArchitectureRules.SCHEMA_GENERATION_AT_BUILD_TIME.violations(root).assert()
            .containsExactly("$bad:3: com.github.victools.jsonschema.generator.SchemaGenerator")
    }

    @Test
    fun `query-api rule should catch a JSON library`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.QUERY_API.dir,
            "me/ahoo/wow/api/query/Bad.kt",
            """
            package me.ahoo.wow.api.query

            import com.fasterxml.jackson.annotation.JsonProperty
            import kotlinx.serialization.Serializable
            import tools.jackson.databind.JsonNode
            import java.time.Duration
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.QUERY_API.dir}/src/main/kotlin/me/ahoo/wow/api/query/Bad.kt"
        ArchitectureRules.QUERY_API_IS_PLAIN_DATA.violations(root).assert().containsExactly(
            "$bad:3: com.fasterxml.jackson.annotation.JsonProperty",
            "$bad:4: kotlinx.serialization.Serializable",
            "$bad:5: tools.jackson.databind.JsonNode",
        )
    }

    @Test
    fun `module graph should read the project dependencies of a build script`(@TempDir root: File) {
        File(root, ArchitectureRules.KERNEL.dir).apply { mkdirs() }.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                api(project(":spi"))
                implementation(project( ":runtime" ))
            }
            """.trimIndent(),
        )
        ArchitectureRules.projectDependencies(root, ArchitectureRules.KERNEL).assert()
            .containsExactlyInAnyOrder("spi", "runtime")
    }

    @Test
    fun `module graph should allow the test modules and the Manifest to a module's tests only`(@TempDir root: File) {
        File(root, ArchitectureRules.POSTGRES.dir).apply { mkdirs() }.resolve("build.gradle.kts").writeText(
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
        ArchitectureRules.projectDependencies(root, ArchitectureRules.POSTGRES).assert()
            .containsExactlyInAnyOrder("spi", "tck-containers", "runtime")
    }

    @Test
    fun `test module rule should catch Testcontainers or a driver in the TCK`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.TCK.dir,
            "me/ahoo/wow/spi/Bad.kt",
            """
            package me.ahoo.wow.spi

            import org.testcontainers.postgresql.PostgreSQLContainer

            val url = org.postgresql.Driver::class
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.TCK.dir}/src/main/kotlin/me/ahoo/wow/spi/Bad.kt"
        ArchitectureRules.TEST_MODULES_ARE_FRAMEWORK_FREE.violations(root).assert().containsExactly(
            "$bad:3: org.testcontainers.postgresql.PostgreSQLContainer",
            "$bad:5: org.postgresql.Driver",
        )
    }

    @Test
    fun `entry rule should catch a state admitted past the read facade`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/read/Bad.kt",
            """
            package me.ahoo.wow.http.read

            import me.ahoo.wow.query.engine.ReadFacade
            import me.ahoo.wow.query.state.StateVisibility
            """.trimIndent(),
        )
        ArchitectureRules.ENTRIES_READ_THROUGH_FACADE.violations(root).assert().containsExactly(
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:4: me.ahoo.wow.query.state.StateVisibility",
        )
    }

    @Test
    fun `host rule should catch a query policy decided in the host`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.SPRING_BOOT.dir,
            "me/ahoo/wow/spring/boot/Bad.kt",
            """
            package me.ahoo.wow.spring.boot

            import me.ahoo.wow.query.AbacQueryPolicy
            import me.ahoo.wow.query.engine.QueryEngine

            val gateway = me.ahoo.wow.query.state.SnapshotPolicyGateway::class
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.SPRING_BOOT.dir}/src/main/kotlin/me/ahoo/wow/spring/boot/Bad.kt"
        ArchitectureRules.HOST_DECIDES_NO_QUERY_POLICY.violations(root).assert().containsExactly(
            "$bad:3: me.ahoo.wow.query.AbacQueryPolicy",
            "$bad:6: me.ahoo.wow.query.state.SnapshotPolicyGateway",
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
        ArchitectureRules.settingsProjects(root).assert().containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "wow-compiler" to "wow-compiler",
                "example-domain" to "example-domain",
                "compiler" to "compiler/compiler",
            ),
        )
    }

    @Test
    fun `deleted 9x rule should catch an include of or a dependency on a deleted 9x project`(@TempDir root: File) {
        root.writeBuild(
            mapOf(
                "wow-core" to "wow-core",
                "wow-dependencies" to "wow-dependencies",
                "spi" to "core/spi",
                "app" to "app",
            ),
            mapOf(
                "spi" to listOf("wow-dependencies"),
                "app" to listOf("spi", "example-domain", "wow-core"),
            ),
            wow = setOf("spi"),
        )
        ArchitectureRules.deletedNineXViolations(root).assert().containsExactly(
            "settings.gradle.kts: :wow-core",
            "app/build.gradle.kts: :example-domain",
            "app/build.gradle.kts: :wow-core",
        )
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
            wow = setOf(
                "api", "spring-boot", "routing-spike", "lease-spike", "eshop-catalog-api", "eshop-catalog-domain",
                "eshop-catalog-server", "eshop-inventory-domain", "eshop-e2e",
            ),
        )
        ArchitectureRules.exampleOutsideWowViolations(root).assert().containsExactly(
            "eshop-catalog-domain reaches :routing-spike",
            "eshop-catalog-server reaches :wow-spring",
            "eshop-e2e reaches :lease-spike",
            "eshop-e2e reaches :wow-core",
            "eshop-inventory-domain reaches :wow-core",
        )
    }

    @Test
    fun `the eShop should be five contexts of an API, a domain and a server each, and its e2e tests`() {
        ArchitectureRules.ESHOP.map { it.name }.assert().containsExactly(
            "eshop-catalog-api", "eshop-catalog-domain", "eshop-catalog-server",
            "eshop-basket-api", "eshop-basket-domain", "eshop-basket-server",
            "eshop-ordering-api", "eshop-ordering-domain", "eshop-ordering-server",
            "eshop-inventory-api", "eshop-inventory-domain", "eshop-inventory-server",
            "eshop-payment-api", "eshop-payment-domain", "eshop-payment-server",
            "eshop-e2e",
        )
        ArchitectureRules.settingsProjects(repositoryRoot).assert()
            .containsAllEntriesOf(ArchitectureRules.ESHOP.associate { it.name to it.dir })
        ArchitectureRules.wowSettingsProjects(repositoryRoot).assert()
            .containsAll(ArchitectureRules.ESHOP.map { it.name })
    }

    @Test
    fun `eShop domain modules should be its APIs and domains, not its servers or e2e tests`() {
        listOf(
            "eshop-catalog-api",
            "eshop-catalog-domain",
            "eshop-payment-api",
            "eshop-payment-domain",
            "java-smoke"
        ).forEach {
            ArchitectureRules.isExampleDomain(it).assert().describedAs(it).isTrue()
        }
        listOf("eshop-catalog-server", "eshop-payment-server", "eshop-e2e", "quickstart").forEach {
            ArchitectureRules.isExample(it).assert().describedAs(it).isTrue()
            ArchitectureRules.isExampleDomain(it).assert().describedAs(it).isFalse()
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
        ArchitectureRules.exampleDomainOnRuntimeViolations(root).assert().containsExactly(
            "eshop-payment-api reaches :runtime",
            "eshop-payment-domain reaches :runtime",
        )
    }

    @Test
    fun `compatibility check should catch a deprecation and a compat marker`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.RUNTIME.dir,
            "me/ahoo/wow/runtime/Old.kt",
            """
            package me.ahoo.wow.runtime

            @Deprecated("old")
            fun old() = Unit // compat(wow<10): kept for 9.x
            """.trimIndent(),
        )
        ArchitectureRules.compatibilityMarkers(root).assert().hasSize(2)
    }

    /**
     * Writes a settings script including [projects] (name to directory), the [wow] ones in its `//region wow`, and a
     * build script per project.
     */
    private fun File.writeBuild(
        projects: Map<String, String>,
        dependencies: Map<String, List<String>>,
        wow: Set<String> = emptySet(),
    ) {
        fun includes(names: Collection<String>) = names.joinToString("\n") { name ->
            "include(\":$name\")\nproject(\":$name\").projectDir = file(\"${projects.getValue(name)}\")"
        }
        File(this, "settings.gradle.kts").writeText(
            includes(projects.keys - wow) + "\n\n//region wow\n" + includes(wow) + "\n//endregion\n",
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

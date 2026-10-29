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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * The peer contract in the api jar (decision D234), built with the plugin (Gradle TestKit):
 *
 * - `shop-api` (`me.ahoo.wow.api`) declares `wowContract(project(":shop-domain"))` (context `shop`) and
 *   `wowContract(project(":audit-domain"))` (context `audit`), which depend on it: it holds the commands and events of
 *   both, so its jar carries both contexts, and is published;
 * - `billing-domain` (context `billing`) depends on `shop-api` and on `billing-api`, which declares it and holds its
 *   classes: only `billing-api` carries `billing`;
 * - `broken-domain` keeps an event of its context outside `broken-api`: assembling `broken-api`'s contract fails;
 * - `orders` (context `orders`), a single-module service (`me.ahoo.wow.service`), depends on `shop-api` alone, and
 *   merges its own Manifest; the servers (`me.ahoo.wow.service`) host the contexts of the domain modules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WowContractFunctionalTest {
    private lateinit var projectDir: File

    private lateinit var result: BuildResult

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(path: String, content: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    /** A module of the Kotlin plugin and the Wow plugin [id]: a domain, a service, or a published api module. */
    private fun module(name: String, id: String, dependencies: String = "", extra: String = "") {
        val plugins = if (id == WowPlugin.API_ID) """id("$id"); `maven-publish`""" else """id("$id")"""
        val tools = if (id != WowPlugin.API_ID) {
            """
            ksp(files(${classpath("wow.fixture.processorClasspath")}))
            """
        } else {
            ""
        }
        write(
            "$name/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                $plugins
            }

            group = "sample"
            version = "1.0.0"

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                $tools
                $dependencies
            }
            // An api module runs the contract code generator of the tools too.
            configurations.matching { it.name == "wowTools" }.configureEach {
                project.dependencies.add(name, files(${classpath("wow.fixture.toolsClasspath")}))
            }
            $extra
            """,
        )
    }

    /** The command and event of [type] in package [pkg] of [module]. */
    private fun messages(module: String, pkg: String, type: String) = write(
        "$module/src/main/kotlin/$pkg/$type.kt",
        """
        package $pkg

        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Create$type(val name: String)
        """,
    ).also {
        write(
            "$module/src/main/kotlin/$pkg/${type}Created.kt",
            """
            package $pkg

            data class ${type}Created(val name: String)
            """,
        )
    }

    /** The context [context] of [module], whose aggregate [type] decides the messages of package [pkg]. */
    private fun aggregate(module: String, context: String, type: String, pkg: String) = write(
        "$module/src/main/kotlin/${module.replace('-', '_')}/$type.kt",
        """
        package ${module.replace('-', '_')}

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.BoundedContext
        import $pkg.Create$type
        import $pkg.${type}Created

        @BoundedContext(name = "$context")
        object ${type}Context

        @AggregateRoot
        class $type(private val state: ${type}State) {
            fun onCommand(command: Create$type): ${type}Created = ${type}Created(command.name)
        }

        class ${type}State(val id: String) {
            var name: String? = null

            fun onSourcing(event: ${type}Created) { name = event.name }
        }
        """,
    )

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    @BeforeAll
    fun build(@TempDir directory: File) {
        projectDir = directory
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "services"
            include("shop-api", "shop-domain", "audit-domain", "billing-api", "billing-domain", "orders")
            include("broken-api", "broken-domain")
            include("shop-server", "mixed-server", "named-server")
            """,
        )
        write(
            "gradle.properties",
            """
            kotlin.compiler.execution.strategy=in-process
            org.gradle.jvmargs=-Xmx1g
            """,
        )
        module(
            "shop-api",
            WowPlugin.API_ID,
            dependencies = """wowContract(project(":shop-domain")); wowContract(project(":audit-domain"))""",
            extra = """
                publishing {
                    publications { create<MavenPublication>("api") { from(components["java"]) } }
                    repositories { maven { url = uri(rootDir.resolve("repo")) } }
                }
            """,
        )
        messages("shop-api", "shopapi", "Cart")
        messages("shop-api", "shopapi.audit", "Record")
        module("shop-domain", WowPlugin.ID, dependencies = """api(project(":shop-api"))""", extra = HOSTED)
        aggregate("shop-domain", "shop", "Cart", "shopapi")
        module("audit-domain", WowPlugin.ID, dependencies = """api(project(":shop-api"))""", extra = HOSTED)
        aggregate("audit-domain", "audit", "Record", "shopapi.audit")
        module("billing-api", WowPlugin.API_ID, dependencies = """wowContract(project(":billing-domain"))""")
        messages("billing-api", "billingapi", "Invoice")
        module(
            "billing-domain",
            WowPlugin.ID,
            dependencies = """api(project(":billing-api")); api(project(":shop-api"))""",
            extra = """wow { contract { service = "billing-service" } }""",
        )
        aggregate("billing-domain", "billing", "Invoice", "billingapi")
        module("orders", WowPlugin.SERVICE_ID, dependencies = """implementation(project(":shop-api"))""")
        messages("orders", "orders.api", "Order")
        aggregate("orders", "orders", "Order", "orders.api")
        broken()
        servers()
        result = runner(
            ":shop-api:publish",
            ":billing-api:jar",
            ":orders:${WowPlugin.MERGE_TASK}",
            ":shop-server:${WowPlugin.MERGE_TASK}",
            ":named-server:${WowPlugin.MERGE_TASK}",
            ":shop-domain:jar",
            "--configuration-cache",
        ).build()
    }

    /** Servers that host the contexts of other modules, with no code of their own. */
    private fun servers() {
        val shopAndAudit = """implementation(project(":shop-domain")); implementation(project(":audit-domain"))"""
        module("shop-server", WowPlugin.SERVICE_ID, dependencies = shopAndAudit)
        val shopAndBilling = """implementation(project(":shop-domain")); implementation(project(":billing-domain"))"""
        module("mixed-server", WowPlugin.SERVICE_ID, dependencies = shopAndBilling)
        module(
            "named-server",
            WowPlugin.SERVICE_ID,
            dependencies = shopAndBilling,
            extra = """wow { serviceName = "edge" }"""
        )
    }

    /** `broken-domain`, whose event is not in `broken-api`. */
    private fun broken() {
        module("broken-api", WowPlugin.API_ID, dependencies = """wowContract(project(":broken-domain"))""")
        write(
            "broken-api/src/main/kotlin/brokenapi/CreateThing.kt",
            """
            package brokenapi

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class CreateThing(val name: String)
            """,
        )
        module("broken-domain", WowPlugin.ID, dependencies = """api(project(":broken-api"))""")
        write(
            "broken-domain/src/main/kotlin/brokenapi/ThingCreated.kt",
            """
            package brokenapi

            data class ThingCreated(val name: String)
            """,
        )
        aggregate("broken-domain", "broken", "Thing", "brokenapi")
    }

    private fun contractOf(jar: File): Manifest? = ZipFile(jar).use { zip ->
        zip.getEntry(PeerContracts.RESOURCE)?.let { entry -> zip.getInputStream(entry).use(ManifestJson::read) }
    }

    @Test
    fun `the api jar should carry the contracts of the domain modules that declare it`() {
        result.task(":shop-domain:${WowPlugin.GENERATE_CONTRACT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        result.task(":shop-api:${WowPlugin.ASSEMBLE_CONTRACT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        val contract = contractOf(File(projectDir, "shop-api/build/libs/shop-api-1.0.0.jar"))!!
        contract.service!!.name.assert().isEqualTo("shop-service")
        contract.contexts.map { it.name }.assert().containsExactly("audit", "shop")
        contract.contexts.last().aggregates.single().domainCommands.single().schema
            .assert().isEqualTo(Manifest.schemaRef("shopapi.CreateCart"))
        contract.defs.keys.assert().contains("shopapi.CreateCart", "shopapi.CartCreated", "shop_domain.CartState")
        // The contract keeps the commands' routes, which the clients send them by.
        contract.contexts.flatMap { it.aggregates }.flatMap { it.domainCommands }.flatMap { it.routes }.assert()
            .isNotEmpty()
        // The api module declares its domain modules and generates the code of their contexts (decision D363 ①).
        ZipFile(File(projectDir, "shop-api/build/libs/shop-api-1.0.0.jar")).use { jar ->
            listOf("shopapi/ShopContract.class", "shopapi/audit/AuditContract.class")
                .forEach { jar.getEntry(it).assert().isNotNull() }
        }
    }

    @Test
    fun `a context should be carried by the api module that holds its classes only`() {
        contractOf(File(projectDir, "billing-api/build/libs/billing-api-1.0.0.jar"))!!.contexts.map { it.name }
            .assert().containsExactly("billing")
    }

    @Test
    fun `the published api should carry the contract and name no domain module`() {
        val published = File(projectDir, "repo/sample/shop-api/1.0.0")
        contractOf(File(published, "shop-api-1.0.0.jar"))!!.contexts.map { it.name }
            .assert().containsExactly("audit", "shop")
        listOf("shop-api-1.0.0.pom", "shop-api-1.0.0.module").forEach { metadata ->
            File(published, metadata).readText().assert().doesNotContain("domain", "wow-contract", "api-view")
        }
    }

    @Test
    fun `completing a domain's fragment should not need its api's jar`() {
        // The domain module compiles against the api's classes (the plugin makes the api a java-library), and its
        // fragment completion reads them, so the api's jar, which waits for the domain's contract, is no input of
        // either: the build has no cycle, and the consumer's Manifest holds its context alone.
        result.task(":shop-domain:${WowPlugin.GENERATE_FRAGMENT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        ManifestJson.read(File(projectDir, "orders/build/wow/manifest.json")).contexts.map { it.name }
            .assert().containsExactly("orders")
    }

    private fun serviceOf(module: String): String? =
        ManifestJson.read(File(projectDir, "$module/build/wow/manifest.json")).service?.name

    @Test
    fun `a service should be named by the contracts of the contexts it hosts`() {
        serviceOf("shop-server").assert().isEqualTo("shop-service")
    }

    @Test
    fun `a service whose contexts' contracts name none should be named after its project`() {
        // The shop's contract, of a peer here, names its own service, which does not count.
        serviceOf("orders").assert().isEqualTo("orders")
    }

    @Test
    fun `a service whose contexts' contracts name several should fail asking for its name`() {
        runner(":mixed-server:${WowPlugin.MERGE_TASK}").buildAndFail().output.assert().contains(
            "The contracts of the service's contexts name different services",
            "shop-service",
            "billing-service",
            "wow { serviceName",
        )
    }

    @Test
    fun `a service's explicit name should win over its contexts' contracts`() {
        serviceOf("named-server").assert().isEqualTo("edge")
    }

    @Test
    fun `the configuration cache should be reused`() {
        runner(":shop-api:jar", ":billing-api:jar", "--configuration-cache").build()
            .output.assert().contains("Configuration cache entry stored")
        val again = runner(":shop-api:jar", ":billing-api:jar", "--configuration-cache").build()
        again.output.assert().contains("Configuration cache entry reused")
        again.task(":shop-api:${WowPlugin.ASSEMBLE_CONTRACT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `a context with classes outside its api module should fail the api's build naming them`() {
        val failed = runner(":broken-api:jar").buildAndFail()
        failed.output.assert().contains(
            "The contract of project ':broken-api' cannot be assembled",
            "context broken of project ':broken-domain' has classes that are not in project ':broken-api': " +
                "brokenapi.ThingCreated",
        )
    }

    private companion object {
        /** The service that hosts the shop's contexts, which its contract names (decision D330). */
        const val HOSTED = """wow { contract { service = "shop-service" } }"""
    }
}

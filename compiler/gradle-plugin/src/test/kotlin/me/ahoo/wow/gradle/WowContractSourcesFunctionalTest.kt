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
import org.gradle.testkit.runner.BuildResult
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.util.GradleVersion
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * The code generated from the peer contract in the api jar (decisions D256 ①, D259; spike CG-0), built with the
 * plugin (Gradle TestKit):
 *
 * - `shop-domain` (context `shop`, aggregate `cart`) declares `api(project(":shop-api"))`: `shop-api` compiles
 *   `shopapi.ShopContract` from the contract it carries, and `shop-domain`'s tests use it;
 * - `shop-server` depends on `shop-domain`, `orders` (another Wow domain module, context `orders`) and `client` (no
 *   Wow plugin) on `shop-api`: each compiles against `ShopContract`;
 * - `external`, a build of its own, compiles against the published `shop-api`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WowContractSourcesFunctionalTest {
    private lateinit var projectDir: File

    private lateinit var result: BuildResult

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(path: String, content: String, root: File = projectDir) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun module(name: String, plugins: String, dependencies: String, extra: String = "") {
        val wow = plugins.contains("me.ahoo.wow")
        val tools = if (wow) {
            """
            ksp(files(${classpath("wow.fixture.processorClasspath")}))
            wowManifestTools(files(${classpath("wow.fixture.toolsClasspath")}))
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

            repositories { mavenCentral() }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                $tools
                $dependencies
            }
            ${if (wow) "wow { addCompilerDependency = false }" else ""}
            $extra
            """,
        )
    }

    /** The aggregate [type] of `shop-domain`, whose messages are in `shop-api`. */
    private fun aggregate(type: String) {
        write(
            "shop-api/src/main/kotlin/shopapi/$type.kt",
            """
            package shopapi

            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Create$type(val name: String)

            data class ${type}Created(val name: String)
            """,
        )
        write(
            "shop-domain/src/main/kotlin/shopdomain/$type.kt",
            """
            package shopdomain

            import me.ahoo.wow.api.annotation.AggregateRoot
            import shopapi.Create$type
            import shopapi.${type}Created

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
    }

    /** Code of [module] in source set [sourceSet] that uses the generated `ShopContract`. */
    private fun usage(module: String, sourceSet: String = "main", body: String = "ShopContract.Cart.ref(\"t\", \"1\")") =
        write(
            "$module/src/$sourceSet/kotlin/${module.replace('-', '_')}/Usage.kt",
            """
            package ${module.replace('-', '_')}

            import shopapi.ShopContract

            object Usage {
                val ref = $body
                val name = ShopContract.NAME
            }
            """,
        )

    private fun runner(vararg arguments: String, dir: File = projectDir): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    @BeforeAll
    fun build(@TempDir directory: File) {
        projectDir = directory
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "services"
            include("shop-api", "shop-domain", "shop-server", "orders", "client")
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
            plugins = "`maven-publish`",
            dependencies = "",
            extra = """
                publishing {
                    publications { create<MavenPublication>("api") { from(components["java"]) } }
                    repositories { maven { url = uri(rootDir.resolve("repo")) } }
                }
            """,
        )
        module("shop-domain", plugins = """id("me.ahoo.wow")""", dependencies = """api(project(":shop-api"))""")
        write(
            "shop-domain/src/main/kotlin/shopdomain/ShopContext.kt",
            """
            package shopdomain

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "shop")
            object ShopContext
            """,
        )
        aggregate("Cart")
        usage("shop-domain", sourceSet = "test")
        module("shop-server", plugins = """id("me.ahoo.wow")""", dependencies = """implementation(project(":shop-domain"))""")
        usage("shop-server")
        module("orders", plugins = """id("me.ahoo.wow")""", dependencies = """implementation(project(":shop-api"))""")
        write(
            "orders/src/main/kotlin/orders/Order.kt",
            """
            package orders

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate

            @BoundedContext(name = "orders")
            object OrdersContext

            @CreateAggregate
            data class CreateOrder(val name: String)

            data class OrderCreated(val name: String)

            @AggregateRoot
            class Order(private val state: OrderState) {
                fun onCommand(command: CreateOrder): OrderCreated = OrderCreated(command.name)
            }

            class OrderState(val id: String) {
                var name: String? = null

                fun onSourcing(event: OrderCreated) { name = event.name }
            }
            """,
        )
        usage("orders")
        module("client", plugins = "", dependencies = """implementation(project(":shop-api"))""")
        usage("client")
        result = runner(
            ":shop-api:publish",
            ":shop-domain:compileTestKotlin",
            ":shop-server:compileKotlin",
            ":orders:${WowPlugin.MERGE_TASK}",
            ":client:compileKotlin",
        ).build()
    }

    @Test
    @Order(1)
    fun `the api jar should carry the generated contract code`() {
        result.task(":shop-api:${WowPlugin.GENERATE_CONTRACT_SOURCES_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        File(projectDir, "shop-api/build/generated/wow/contract/kotlin/shopapi/ShopContract.kt").readText()
            .assert().contains("object ShopContract", "const val NAME: String = \"shop\"", "object Cart")
        ZipFile(File(projectDir, "repo/sample/shop-api/1.0.0/shop-api-1.0.0.jar")).use { jar ->
            listOf("shopapi/ShopContract.class", "shopapi/ShopContract\$Cart.class", "shopapi/CreateCart.class")
                .forEach { jar.getEntry(it).assert().isNotNull() }
        }
    }

    @Test
    @Order(2)
    fun `the domain should compile before the contract code it gives`() {
        val order = result.tasks.map { it.path }
        order.indexOf(":shop-domain:kspKotlin").assert()
            .isLessThan(order.indexOf(":shop-api:compileWowContractKotlin"))
        order.indexOf(":shop-domain:compileKotlin").assert()
            .isLessThan(order.indexOf(":shop-api:compileWowContractKotlin"))
        // Every consumer but the domain compiles against the generated code: the domain's tests, the server, the
        // in-build peers with and without the Wow plugin.
        listOf(":shop-domain:compileTestKotlin", ":shop-server:compileKotlin", ":orders:compileKotlin", ":client:compileKotlin")
            .forEach { result.task(it)?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS) }
    }

    @Test
    @Order(3)
    fun `an external peer should compile against the published api`(@TempDir external: File) {
        write("settings.gradle.kts", """rootProject.name = "external"""", external)
        write(
            "build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.jvm") }

            repositories {
                maven { url = uri("${File(projectDir, "repo").path.replace("\\", "/")}") }
                mavenCentral()
            }

            dependencies {
                implementation("sample:shop-api:1.0.0")
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
            }
            """,
            external,
        )
        write(
            "src/main/kotlin/external/Usage.kt",
            """
            package external

            import shopapi.ShopContract

            val ref = ShopContract.Cart.ref("t", "1")
            """,
            external,
        )
        runner("compileKotlin", dir = external).build()
            .task(":compileKotlin")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    @Order(4)
    fun `the IDE model should resolve without running the build`() {
        // What an IDE sync asks for (Gradle's Tooling API), with the plugin under test on the root build script's
        // class path: the paired domain module still depends on its api module.
        val metadata = Properties().apply {
            WowContractSourcesFunctionalTest::class.java.getResourceAsStream("/plugin-under-test-metadata.properties")!!.use(::load)
        }
        val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }
        val settings = File(projectDir, "settings.gradle.kts")
        val original = settings.readText()
        settings.writeText("buildscript { dependencies { classpath(files($classpath)) } }\n$original")
        try {
            GradleConnector.newConnector().forProjectDirectory(projectDir)
                .useGradleVersion(GradleVersion.current().version)
                .connect().use { connection ->
                    val modules = connection.getModel(IdeaProject::class.java).modules.associateBy { it.name }
                    modules.keys.assert().contains("shop-api", "shop-domain", "shop-server", "orders", "client")
                    modules.getValue("shop-domain").dependencies.filterIsInstance<IdeaModuleDependency>()
                        .map { it.targetModuleName }.assert().contains("shop-api")
                }
        } finally {
            settings.writeText(original)
        }
    }

    @Test
    @Order(5)
    fun `a new aggregate should regenerate the contract code and reuse the configuration cache`() {
        aggregate("Wishlist")
        usage("shop-server", body = "ShopContract.Wishlist.ref(\"t\", \"1\")")
        val incremental = runner(":shop-server:compileKotlin", ":client:compileKotlin").build()
        incremental.task(":shop-api:compileWowContractKotlin")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        incremental.task(":shop-server:compileKotlin")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        val again = runner(":shop-server:compileKotlin", ":client:compileKotlin").build()
        again.output.assert().contains("Configuration cache entry reused")
        again.task(":shop-api:${WowPlugin.GENERATE_CONTRACT_SOURCES_TASK}")?.outcome.assert()
            .isEqualTo(TaskOutcome.UP_TO_DATE)
        again.task(":shop-server:compileKotlin")?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)
    }
}

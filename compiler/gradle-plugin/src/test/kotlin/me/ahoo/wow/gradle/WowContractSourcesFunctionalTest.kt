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
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.util.GradleVersion
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
 * - `shop-api` (`me.ahoo.wow.api`) declares `wowContract(project(":shop-domain"))` (context `shop`, aggregate
 *   `cart`), which depends on it in `api(...)`: `shop-api` compiles `shopapi.ShopContract` from the contract it
 *   carries, and `shop-domain`'s tests use it;
 * - `shop-server` (`me.ahoo.wow.service`, Kotlin and Java sources) depends on `shop-domain`, `orders` (a
 *   single-module service, context `orders`), `client` (no Wow plugin) and `java-client` (Java only) on `shop-api`:
 *   each compiles against `ShopContract`;
 * - `external`, a build of its own, compiles against the published `shop-api`.
 *
 * `wow-query-api` is on every class path, so the compiler writes `shopdomain.CartStateProperties` and the contract
 * code holds the same query fields. `shop-api` also compiles `shopapi.CartStateView` (decision D362), the view `client`
 * and `shop-server` read the cart's snapshots as.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WowContractSourcesFunctionalTest {
    private lateinit var projectDir: File

    private lateinit var result: BuildResult

    /**
     * A build of [dir]; a deprecation fails it (`--warning-mode=fail`), so the names the plugin gives Gradle (the
     * contract feature's variants, `wowContractApiElements`) hold for the next Gradle too.
     */
    private fun runner(vararg arguments: String, dir: File = projectDir): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--stacktrace", "--warning-mode=fail")
        .forwardOutput()

    private val fixture: ContractSourcesFixture by lazy { ContractSourcesFixture(projectDir) }

    @BeforeAll
    fun build(@TempDir directory: File) {
        projectDir = directory
        fixture.writeBuild()
        result = runner(
            ":shop-api:publish",
            ":shop-domain:compileTestKotlin",
            ":shop-server:compileKotlin",
            ":orders:${WowPlugin.MERGE_TASK}",
            ":client:compileKotlin",
            ":shop-server:compileJava",
            ":java-client:compileJava",
        ).build()
    }

    @Test
    @Order(1)
    fun `the contract jar should carry the generated contract code, the api's jar its types alone`() {
        result.task(
            ":shop-api:${WowPlugin.GENERATE_CONTRACT_SOURCES_TASK}"
        )?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        File(projectDir, "shop-api/build/generated/wow/contract/kotlin/shopapi/ShopContract.kt").readText()
            .assert().contains(
                "object ShopContract",
                "const val NAME: kotlin.String = \"shop\"",
                "object Cart : me.ahoo.wow.api.query.SnapshotProperties()",
                "QUERY_MODELS: kotlin.collections.List<kotlin.String> = kotlin.collections.listOf(\"snapshot\", \"events\")",
            )
        ZipFile(File(projectDir, "shop-api/build/libs/shop-api-1.0.0-contract.jar")).use { jar ->
            listOf("shopapi/ShopContract.class", "shopapi/ShopContract\$Cart.class", "META-INF/wow/contract.json")
                .forEach { jar.getEntry(it).assert().describedAs(it).isNotNull() }
            jar.getEntry("shopapi/CreateCart.class").assert().isNull()
        }
        ZipFile(File(projectDir, "repo/sample/shop-api/1.0.0/shop-api-1.0.0.jar")).use { jar ->
            jar.getEntry("shopapi/CreateCart.class").assert().isNotNull()
            listOf("shopapi/ShopContract.class", "META-INF/wow/contract.json")
                .forEach { jar.getEntry(it).assert().describedAs(it).isNull() }
        }
    }

    @Test
    @Order(1)
    fun `the api should carry a view of the cart's state, copying the domain's own types`() {
        val view = File(projectDir, "shop-api/build/generated/wow/contract/kotlin/shopapi/CartStateView.kt").readText()
        view.assert().contains(
            "@me.ahoo.wow.api.read.StateView(\"shop.cart\")",
            "public data class CartStateView(",
            "val id: kotlin.String,",
            "val name: kotlin.String? = null,",
            "val status: CartStateView.CartStatus,",
            "val lines: kotlin.collections.List<CartStateView.CartLine>,",
            "val address: CartStateView.CartAddress? = null,",
            "val attributes: kotlin.collections.Map<kotlin.String, kotlin.String>,",
            "val extras: tools.jackson.databind.node.ObjectNode,",
            "val totalURLCount: kotlin.Int,",
            "public enum class CartStatus {",
            "val parent: CartStateView.CartLine? = null,",
            "val zip: kotlin.String,",
            // A computed property Jackson writes into the published state: the view holds it too.
            "val computed: kotlin.Int,",
        ).doesNotContain("secret")
        ZipFile(File(projectDir, "shop-api/build/libs/shop-api-1.0.0-contract.jar")).use { jar ->
            jar.getEntry("shopapi/CartStateView.class").assert().isNotNull()
        }
        // The api's sources jar holds its own sources alone.
        ZipFile(File(projectDir, "repo/sample/shop-api/1.0.0/shop-api-1.0.0-sources.jar")).use { sources ->
            sources.getEntry("shopapi/Cart.kt").assert().isNotNull()
            sources.getEntry("shopapi/ShopContract.kt").assert().isNull()
        }
    }

    @Test
    @Order(1)
    fun `the published api should be its types and its contract feature, nothing of its in-build variants`() {
        val published = File(projectDir, "repo/sample/shop-api/1.0.0")
        val module = File(published, "shop-api-1.0.0.module").readText()
        module.assert().doesNotContain("me.ahoo.wow.api-view", "wow-pairing", "shop-domain")
        // The variants a java-library publishes with its sources, and the contract feature's beside them.
        Regex("""\{\s*"name": "([^"]+)",\s*"attributes"""").findAll(module).map { it.groupValues[1] }.toList()
            .assert().containsExactlyInAnyOrder(
                "apiElements",
                "runtimeElements",
                ContractRole.CONTRACT_API_ELEMENTS,
                ContractRole.CONTRACT_RUNTIME_ELEMENTS,
                "sourcesElements",
                ContractRole.CONTRACT_SOURCES_ELEMENTS,
            )
        // The types name the feature that holds the contract; the contract variants are of its capability.
        Regex(""""me\.ahoo\.wow\.contract": "([^"]+)"""").findAll(module).map { it.groupValues[1] }.toList()
            .assert().containsExactly(ContractRole.FEATURE, ContractRole.FEATURE)
        module.assert().contains(""""name": "shop-api-contract"""")
        val jars = listOf(
            "shop-api-1.0.0.jar",
            "shop-api-1.0.0-sources.jar",
            "shop-api-1.0.0-contract.jar",
            "shop-api-1.0.0-contract-sources.jar",
        )
        Regex(""""name": "([^"]+\.jar)"""").findAll(module).map { it.groupValues[1] }.toSet()
            .assert().containsExactlyInAnyOrderElementsOf(jars)
        published.list()!!.filter { it.endsWith(".jar") }.assert().containsExactlyInAnyOrderElementsOf(jars)
        ZipFile(File(published, "shop-api-1.0.0-contract.jar")).use { jar ->
            jar.getEntry("META-INF/wow/contract.json").assert().isNotNull()
        }
        ZipFile(File(published, "shop-api-1.0.0-contract-sources.jar")).use { sources ->
            sources.getEntry("shopapi/ShopContract.kt").assert().isNotNull()
        }
        File(published, "shop-api-1.0.0.pom").readText().assert().doesNotContain("wow-core", "shop-domain")
    }

    @Test
    @Order(1)
    fun `the contract's query fields should be named as the compiler names the state's`() {
        val compiler = File(projectDir, "shop-domain/build/generated/ksp/main/kotlin/shopdomain/CartStateProperties.kt")
        val contract = File(projectDir, "shop-api/build/generated/wow/contract/kotlin/shopapi/ShopContract.kt")
        val generated = queryFields(contract.readText())
        generated.assert().isEqualTo(queryFields(compiler.readText()))
        generated.assert().containsEntry("STATE__LINES__PRODUCT_ID", "state.lines.productId")
            .containsEntry("STATE__ADDRESS__ZIP", "state.address.zip")
            .containsEntry("STATE__LINES__PARENT", "state.lines.parent")
            .doesNotContainKey("STATE__SECRET")
            .doesNotContainKey("STATE__ATTRIBUTES__KEY")
            // A computed property is a query field, as the compiler names it too.
            .containsEntry("STATE__COMPUTED", "state.computed")
    }

    /** The `QueryField` constants of a generated source, name to path, whatever each is typed as. */
    private fun queryFields(source: String): Map<String, String> =
        Regex(
            """val (\w+): (?:me\.ahoo\.wow\.api\.query\.)?QueryField<.+?> = """ +
                """(?:me\.ahoo\.wow\.api\.query\.)?QueryField\.of\("([^"]+)"\)"""
        )
            .findAll(source).associate { it.groupValues[1] to it.groupValues[2] }

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
        listOf(
            ":shop-domain:compileTestKotlin",
            ":shop-server:compileKotlin",
            ":orders:compileKotlin",
            ":client:compileKotlin",
            ":shop-server:compileJava",
            ":java-client:compileJava",
        )
            .forEach { result.task(it)?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS) }
    }

    @Test
    @Order(2)
    fun `the domain should compile against its api's types jar when compile class paths resolve to jars`() {
        // What an IDE delegating to Gradle asks for: jars on the compile class paths. The api's jar is its types,
        // which wait for no domain; the contract jar waits for the domain.
        val jars = runner(
            "-Dorg.gradle.java.compile-classpath-packaging=true",
            ":shop-domain:compileTestKotlin",
            ":shop-server:compileKotlin",
            ":orders:compileKotlin",
            ":client:compileKotlin",
            ":shop-server:compileJava",
            ":java-client:compileJava",
        ).build()
        val order = jars.tasks.map { it.path }
        order.indexOf(":shop-api:jar").assert().isLessThan(order.indexOf(":shop-domain:compileKotlin"))
        order.indexOf(":shop-domain:compileKotlin").assert()
            .isLessThan(order.indexOf(":shop-api:${WowPlugin.CONTRACT_JAR_TASK}"))
        listOf(":client:compileKotlin", ":java-client:compileJava", ":shop-server:compileKotlin")
            .forEach { jars.task(it)?.outcome.assert().isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE) }
    }

    @Test
    @Order(3)
    fun `an external peer should compile against the published api's contract, which it asks for`(
        @TempDir external: File,
    ) {
        fixture.write("settings.gradle.kts", """rootProject.name = "external"""", external)
        fixture.write(
            "build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.jvm") }

            repositories {
                maven { url = uri("${File(projectDir, "repo").path.replace("\\", "/")}") }
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
                mavenCentral()
            }

            dependencies {
                implementation("sample:shop-api:1.0.0") { capabilities { requireFeature("contract") } }
                implementation(files(${fixture.classpath("wow-test.fixture.compileClasspath")}))
            }
            """,
            external,
        )
        fixture.write(
            "src/main/kotlin/external/Usage.kt",
            """
            package external

            import shopapi.CreateCart
            import shopapi.ShopContract

            val create = CreateCart::class
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
            WowContractSourcesFunctionalTest::class.java.getResourceAsStream(
                "/plugin-under-test-metadata.properties"
            )!!.use(::load)
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
        fixture.aggregate("Wishlist")
        fixture.usage("shop-server", body = "ShopContract.Wishlist.ref(\"t\", \"1\")")
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

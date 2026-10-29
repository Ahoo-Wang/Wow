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

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(path: String, content: String, root: File = projectDir) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun module(name: String, plugins: String, dependencies: String, extra: String = "") {
        // A domain or service module runs the compiler; an api module does not.
        val wow = plugins.contains("""id("${WowPlugin.ID}")""") || plugins.contains(WowPlugin.SERVICE_ID)
        val tools = if (wow) {
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

    /** The aggregate [type] of `shop-domain`, whose messages are in `shop-api`; [state] adds to its state. */
    private fun aggregate(type: String, state: String = "", types: String = "") {
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
                $state

                fun onSourcing(event: ${type}Created) { name = event.name }
            }
            $types
            """,
        )
    }

    /** The shapes of state a query field walk meets: nested, nullable, listed, renamed, ignored, mapped, recursive. */
    private val cartState = """
                var status: CartStatus = CartStatus.OPEN
                var lines: List<CartLine> = emptyList()
                var address: CartAddress? = null
                var attributes: Map<String, String> = emptyMap()
                var extras: Map<String, Any?> = emptyMap()
                var totalURLCount: Int = 0
                @com.fasterxml.jackson.annotation.JsonIgnore
                var secret: String = ""
                val computed: Int get() = 1
    """
    private val cartTypes = """
            enum class CartStatus { OPEN, CLOSED }

            @JvmInline
            value class Sku(val value: String)

            data class CartLine(val sku: Sku, val productId: String, val quantity: Int, val parent: CartLine? = null)

            data class CartAddress(
                val city: String,
                @com.fasterxml.jackson.annotation.JsonProperty("zip") val postalCode: String,
            )
    """

    /** Code of [module] in source set [sourceSet] that uses the generated `ShopContract`. */
    private fun usage(
        module: String,
        sourceSet: String = "main",
        body: String = "ShopContract.Cart.ref(\"t\", \"1\")"
    ) =
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

    /** Code of [module] that reads the cart's state as the generated `CartStateView`. */
    private fun viewUsage(module: String) {
        val pkg = module.replace('-', '_')
        write(
            "$module/src/main/kotlin/$pkg/ViewUsage.kt",
            """
            package $pkg

            import me.ahoo.wow.api.read.Queries
            import me.ahoo.wow.api.read.TenantSnapshotQueries
            import me.ahoo.wow.api.read.snapshots
            import shopapi.CartStateView

            object ViewUsage {
                fun carts(queries: Queries): TenantSnapshotQueries<CartStateView> = queries.snapshots<CartStateView>()

                fun isOpen(cart: CartStateView): Boolean =
                    cart.status == CartStateView.CartStatus.OPEN && cart.lines.all { it.quantity > 0 } &&
                        cart.address?.zip != null && cart.attributes.isNotEmpty() && cart.totalURLCount >= 0 &&
                        cart.extras.path("note").isMissingNode
            }
            """,
        )
    }

    /** Java code of [module] that reads the generated `ShopContract` as static members. */
    private fun javaUsage(module: String) {
        val pkg = module.replace('-', '_')
        write(
            "$module/src/main/java/$pkg/JavaUsage.java",
            """
            package $pkg;

            import java.util.List;
            import me.ahoo.wow.api.aggregate.AggregateIdSource;
            import me.ahoo.wow.api.aggregate.AggregateRef;
            import me.ahoo.wow.api.aggregate.AggregateType;
            import me.ahoo.wow.api.aggregate.Ownership;
            import me.ahoo.wow.api.query.QueryField;
            import shopapi.ShopContract;

            public final class JavaUsage {
                public static final String CONTEXT = ShopContract.NAME;
                public static final String AGGREGATE = ShopContract.Cart.NAME;
                public static final AggregateType TYPE = ShopContract.Cart.TYPE;
                public static final AggregateRef REF = ShopContract.Cart.ref("t", "1");
                public static final Ownership OWNERSHIP = ShopContract.Cart.OWNERSHIP;
                public static final AggregateIdSource ID_SOURCE = ShopContract.Cart.ID_SOURCE;
                public static final List<String> MODELS = ShopContract.Cart.QUERY_MODELS;
                public static final QueryField PRODUCT = ShopContract.Cart.STATE__LINES__PRODUCT_ID;
                public static final QueryField VERSION = ShopContract.Cart.INSTANCE.VERSION;

                private JavaUsage() {
                }
            }
            """,
        )
    }

    private fun runner(vararg arguments: String, dir: File = projectDir): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    /** `orders`, a Wow domain module of the context `orders`. */
    private fun orders() {
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
    }

    /** The Java consumers: Java sources in `shop-server`, and `java-client`, a Java library on `shop-api`. */
    private fun javaClients() {
        javaUsage("shop-server")
        write(
            "java-client/build.gradle.kts",
            """
            plugins { `java-library` }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow.fixture.repository")}") }
            }

            dependencies {
                implementation(project(":shop-api"))
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
            }
            """,
        )
        javaUsage("java-client")
    }

    /** `shop-api`, published with its sources into `repo`. */
    private fun shopApi() = module(
        "shop-api",
        plugins = """id("${WowPlugin.API_ID}"); `maven-publish`""",
        dependencies = """wowContract(project(":shop-domain"))""",
        extra = """
            java { withSourcesJar() }
            publishing {
                publications { create<MavenPublication>("api") { from(components["java"]) } }
                repositories { maven { url = uri(rootDir.resolve("repo")) } }
            }
        """,
    )

    @BeforeAll
    fun build(@TempDir directory: File) {
        projectDir = directory
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "services"
            include("shop-api", "shop-domain", "shop-server", "orders", "client", "java-client")
            """,
        )
        write("gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g")
        shopApi()
        module("shop-domain", plugins = """id("${WowPlugin.ID}")""", dependencies = """api(project(":shop-api"))""")
        write(
            "shop-domain/src/main/kotlin/shopdomain/ShopContext.kt",
            """
            package shopdomain

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "shop")
            object ShopContext
            """,
        )
        aggregate("Cart", cartState, cartTypes)
        usage("shop-domain", sourceSet = "test")
        module(
            "shop-server",
            plugins = """id("${WowPlugin.SERVICE_ID}")""",
            dependencies = """implementation(project(":shop-domain"))"""
        )
        usage("shop-server")
        viewUsage("shop-server")
        module(
            "orders",
            plugins = """id("${WowPlugin.SERVICE_ID}")""",
            dependencies = """implementation(project(":shop-api"))""",
        )
        orders()
        usage("orders")
        module("client", plugins = "", dependencies = """implementation(project(":shop-api"))""")
        usage("client")
        viewUsage("client")
        javaClients()
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
    fun `the api jar should carry the generated contract code`() {
        result.task(
            ":shop-api:${WowPlugin.GENERATE_CONTRACT_SOURCES_TASK}"
        )?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        File(projectDir, "shop-api/build/generated/wow/contract/kotlin/shopapi/ShopContract.kt").readText()
            .assert().contains(
                "object ShopContract",
                "const val NAME: String = \"shop\"",
                "object Cart : SnapshotProperties()",
                "QUERY_MODELS: List<String> = listOf(\"snapshot\", \"events\")",
            )
        ZipFile(File(projectDir, "repo/sample/shop-api/1.0.0/shop-api-1.0.0.jar")).use { jar ->
            listOf("shopapi/ShopContract.class", "shopapi/ShopContract\$Cart.class", "shopapi/CreateCart.class")
                .forEach { jar.getEntry(it).assert().isNotNull() }
        }
    }

    @Test
    @Order(1)
    fun `the api should carry a view of the cart's state, copying the domain's own types`() {
        val view = File(projectDir, "shop-api/build/generated/wow/contract/kotlin/shopapi/CartStateView.kt").readText()
        view.assert().contains(
            "@StateView(\"shop.cart\")",
            "public data class CartStateView(",
            "val id: String,",
            "val name: String? = null,",
            "val status: CartStateView.CartStatus,",
            "val lines: List<CartStateView.CartLine>,",
            "val address: CartStateView.CartAddress? = null,",
            "val attributes: Map<String, String>,",
            "val extras: tools.jackson.databind.node.ObjectNode,",
            "val totalURLCount: Int,",
            "public enum class CartStatus {",
            "val parent: CartStateView.CartLine? = null,",
            "val zip: String,",
        ).doesNotContain("secret", "computed")
        ZipFile(File(projectDir, "repo/sample/shop-api/1.0.0/shop-api-1.0.0.jar")).use { jar ->
            jar.getEntry("shopapi/CartStateView.class").assert().isNotNull()
        }
        // The sources jar holds the generated sources beside the module's own.
        ZipFile(File(projectDir, "repo/sample/shop-api/1.0.0/shop-api-1.0.0-sources.jar")).use { sources ->
            listOf("shopapi/CartStateView.kt", "shopapi/ShopContract.kt", "shopapi/Cart.kt")
                .forEach { sources.getEntry(it).assert().describedAs(it).isNotNull() }
        }
    }

    @Test
    @Order(1)
    fun `the published api should be its jar and its sources, contract included, without the core view`() {
        val published = File(projectDir, "repo/sample/shop-api/1.0.0")
        ZipFile(File(published, "shop-api-1.0.0.jar")).use { jar ->
            jar.getEntry("META-INF/wow/contract.json").assert().isNotNull()
        }
        val module = File(published, "shop-api-1.0.0.module").readText()
        module.assert().doesNotContain("wow-core", "wowCore", "me.ahoo.wow.api-view")
        // The variants a java-library publishes with its sources, as before the core view: nothing of it is published.
        Regex("""\{\s*"name": "([^"]+)",\s*"attributes"""").findAll(module).map { it.groupValues[1] }.toList()
            .assert().containsExactly("apiElements", "runtimeElements", "sourcesElements")
        val jars = listOf("shop-api-1.0.0.jar", "shop-api-1.0.0-sources.jar")
        Regex(""""name": "([^"]+\.jar)"""").findAll(module).map { it.groupValues[1] }.toSet()
            .assert().containsExactlyInAnyOrderElementsOf(jars)
        File(published, "shop-api-1.0.0.pom").readText().assert().doesNotContain("classifier", "wow-core")
        published.list()!!.filter { it.endsWith(".jar") }.assert().containsExactlyInAnyOrderElementsOf(jars)
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
            .doesNotContainKey("STATE__COMPUTED")
    }

    /** The `QueryField` constants of a generated source, name to path. */
    private fun queryFields(source: String): Map<String, String> =
        Regex(
            """val (\w+): (?:me\.ahoo\.wow\.api\.query\.)?QueryField = (?:me\.ahoo\.wow\.api\.query\.)?QueryField\("([^"]+)"\)"""
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
    fun `the domain should compile against its api's core jar when compile class paths resolve to jars`() {
        // What an IDE delegating to Gradle asks for: jars on the compile class paths, where the api's jar waits for
        // the domain's contract. The domain takes the core jar instead; every other module the api's own jar.
        val jars = runner(
            "-Dorg.gradle.java.compile-classpath-packaging=true",
            ":shop-domain:compileTestKotlin",
            ":shop-server:compileKotlin",
            ":orders:compileKotlin",
            ":client:compileKotlin",
            ":shop-server:compileJava",
            ":java-client:compileJava",
        ).build()
        jars.task(":shop-api:${WowPlugin.CORE_JAR_TASK}")?.outcome.assert()
            .isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
        val order = jars.tasks.map { it.path }
        order.indexOf(":shop-domain:compileKotlin").assert().isLessThan(order.indexOf(":shop-api:jar"))
        listOf(":client:compileKotlin", ":java-client:compileJava", ":shop-server:compileKotlin")
            .forEach { jars.task(it)?.outcome.assert().isIn(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE) }
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
                maven { url = uri("${System.getProperty("wow.fixture.repository")}") }
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

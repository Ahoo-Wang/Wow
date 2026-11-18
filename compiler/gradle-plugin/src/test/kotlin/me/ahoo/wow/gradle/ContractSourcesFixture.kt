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

import java.io.File

/**
 * The build [WowContractSourcesFunctionalTest] runs, in [projectDir]: `shop-api`, `shop-domain`, `shop-server`,
 * `orders`, `client` and `java-client`, as that test describes them, and the code each writes against the contract.
 */
internal class ContractSourcesFixture(val projectDir: File) {
    fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    fun write(path: String, content: String, root: File = projectDir) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    fun module(name: String, plugins: String, dependencies: String, extra: String = "") {
        // A domain or service module runs the compiler; an api module does not.
        val wow = plugins.contains("""id("${WowPlugin.ID}")""") || plugins.contains(WowPlugin.SERVICE_ID)
        val tools = if (wow) {
            """
            ksp(files(${classpath("wow-test.fixture.processorClasspath")}))
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
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
                $tools
                $dependencies
            }
            // An api module runs the contract code generator of the tools too.
            configurations.matching { it.name == "wowTools" }.configureEach {
                project.dependencies.add(name, files(${classpath("wow-test.fixture.toolsClasspath")}))
            }
            $extra
            """,
        )
    }

    /** The aggregate [type] of `shop-domain`, whose messages are in `shop-api`; [state] adds to its state. */
    fun aggregate(type: String, state: String = "", types: String = "") {
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
    val cartState = """
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
    val cartTypes = """
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
    fun usage(
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
    fun viewUsage(module: String) {
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
    fun javaUsage(module: String) {
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
                public static final QueryField<?> PRODUCT = ShopContract.Cart.STATE__LINES__PRODUCT_ID;
                public static final QueryField<?> VERSION = ShopContract.Cart.INSTANCE.VERSION;

                private JavaUsage() {
                }
            }
            """,
        )
    }

    /** `orders`, a Wow domain module of the context `orders`. */
    fun orders() {
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
    fun javaClients() {
        javaUsage("shop-server")
        write(
            "java-client/build.gradle.kts",
            """
            plugins { `java-library` }

            repositories {
                mavenCentral()
                maven { url = uri("${System.getProperty("wow-test.fixture.repository")}") }
            }

            dependencies {
                // A module of no Wow plugin asks for the api's contract feature itself.
                implementation(project(":shop-api")) { capabilities { requireFeature("contract") } }
                implementation(files(${classpath("wow-test.fixture.compileClasspath")}))
            }
            """,
        )
        javaUsage("java-client")
    }

    /** `shop-api`, published with its sources into `repo`. */
    fun shopApi() = module(
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

    /** Writes the whole build. */
    fun writeBuild() {
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
        module(
            "client",
            plugins = "",
            dependencies = """implementation(project(":shop-api")) { capabilities { requireFeature("contract") } }""",
        )
        usage("client")
        viewUsage("client")
        javaClients()
    }
}

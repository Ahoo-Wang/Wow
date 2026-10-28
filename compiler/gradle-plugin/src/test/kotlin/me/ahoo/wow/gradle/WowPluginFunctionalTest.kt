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

import groovy.json.JsonSlurper
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.tools.ManifestJson
import me.ahoo.wow.spi.log.EventRecord
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * Builds a two-module service with the plugin (Gradle TestKit): `shop` (an aggregate and a projection) depends on
 * `billing` (another bounded context). The Wow modules are not published yet, so the fixture compiles against and
 * processes with them as files, and turns off the plugin's own `wow-compiler` dependency.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WowPluginFunctionalTest {
    private companion object {
        /** A class of each tool the workers run: victools, Jackson, the kernel, and the plugin's own tools. */
        val PROBED = listOf(
            "com.github.victools.jsonschema.generator.SchemaGenerator",
            "tools.jackson.databind.ObjectMapper",
            "tools.jackson.module.kotlin.KotlinModule",
            "me.ahoo.wow.kernel.EventCodec",
            "me.ahoo.wow.manifest.tools.ManifestTools",
        )
    }

    private lateinit var projectDir: File

    private lateinit var result: BuildResult

    private fun classpath(property: String): String =
        requireNotNull(System.getProperty(property)) { "System property $property is not set." }
            .split(File.pathSeparator)
            .joinToString(", ") { "\"${it.replace("\\", "/")}\"" }

    private fun write(path: String, content: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    }

    private fun module(name: String, extra: String = "", dependencies: String = "") {
        write(
            "$name/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("me.ahoo.wow")
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation(files(${classpath("wow.fixture.compileClasspath")}))
                ksp(files(${classpath("wow.fixture.processorClasspath")}))
                wowManifestTools(files(${classpath("wow.fixture.toolsClasspath")}))
                $dependencies
            }

            // What the plugin brings to the build script's class path: none of the tools the workers run.
            val toolClasses = listOf(${PROBED.joinToString { "\"$it\"" }})
            val pluginLoader = me.ahoo.wow.gradle.WowPlugin::class.java.classLoader
            val visibleTools = toolClasses.filter { runCatching { Class.forName(it, false, pluginLoader) }.isSuccess }
            val buildscriptJars = buildscript.configurations.getByName("classpath").files.map { it.name }
            tasks.register("probeBuildClasspath") {
                doLast {
                    println("tools on the build classpath: " + visibleTools)
                    println("buildscript jars: " + buildscriptJars)
                }
            }

            wow {
                addCompilerDependency = false
                $extra
            }
            """,
        )
    }

    @BeforeAll
    fun build(@TempDir directory: File) {
        projectDir = directory
        write("settings.gradle.kts", """rootProject.name = "store"; include("shop", "billing")""")
        write(
            "gradle.properties",
            """
            kotlin.compiler.execution.strategy=in-process
            org.gradle.jvmargs=-Xmx1g
            """,
        )
        writeBilling()
        writeShop()
        result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                ":shop:${WowPlugin.MERGE_TASK}",
                ":shop:${WowPlugin.OPENAPI_TASK}",
                ":shop:jar",
                ":billing:jar",
                ":shop:probeBuildClasspath",
                "--stacktrace",
            )
            .forwardOutput()
            .build()
    }

    private fun writeBilling() {
        module("billing")
        write(
            "billing/src/main/kotlin/billing/Account.kt",
            """
            package billing

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.lifecycle.DeleteAggregate

            @BoundedContext(name = "billing")
            object BillingContext

            @CreateAggregate
            data class OpenAccount(val holder: String)
            data class AccountOpened(val holder: String)

            /** A domain service the host provides. */
            interface Holders {
                fun normalize(holder: String): String
            }

            @AggregateRoot(lifecycle = [DeleteAggregate::class])
            class Account(private val state: AccountState) {
                fun onCommand(command: OpenAccount, holders: Holders): AccountOpened =
                    AccountOpened(holders.normalize(command.holder))
            }

            class AccountState(val id: String) {
                var holder: String? = null

                fun onSourcing(event: AccountOpened) { holder = event.holder }
            }
            """,
        )
    }

    @Suppress("LongMethod") // The fixture sources.
    private fun writeShop() {
        module(
            "shop",
            extra = """
                serviceVersion = "1.0.0"
                schemaOverrides.put("sample.Money", ${"\"\"\""}{"type":"string","pattern":"^[0-9]+${'$'}"}${"\"\"\""})
            """,
            dependencies = """implementation(project(":billing"))""",
        )
        write(
            "shop/src/main/kotlin/sample/Cart.kt",
            """
            package sample

            import com.fasterxml.jackson.annotation.JsonProperty
            import com.fasterxml.jackson.annotation.JsonValue
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Alias
            import me.ahoo.wow.api.annotation.BoundedContext
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.ErrorCode
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnCommand
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Revision
            import me.ahoo.wow.api.annotation.Upgrade
            import me.ahoo.wow.api.decide.Decision
            import me.ahoo.wow.api.subscribe.StateEvent
            import tools.jackson.databind.node.ObjectNode

            @BoundedContext(name = "shop", description = "The shop")
            object ShopContext

            data class Money(val cents: Long) {
                @JsonValue
                fun text(): String = cents.toString()
            }

            @CreateAggregate
            data class CreateCart(val owner: String)

            @Name("PutItem")
            @Alias("AddItem")
            data class AddItem(@JsonProperty("sku") val productId: String, val quantity: Int = 1, val price: Money)

            data class CartCreated(val owner: String)

            @Name("ItemPut")
            @Revision(2)
            @Alias("ItemAdded")
            data class ItemAdded(@JsonProperty("sku") val productId: String, val quantity: Int)

            /** Revision 1 wrote the product as `productId` and could leave the quantity out, meaning one. */
            @Upgrade(event = ItemAdded::class, from = 1)
            fun itemAddedFrom1(node: ObjectNode): ObjectNode {
                node.remove("productId")?.let { node.set("sku", it) }
                if (!node.has("quantity")) {
                    node.put("quantity", 1)
                }
                return node
            }

            object CartErrors {
                @ErrorCode(description = "The cart is full", status = 409)
                const val FULL = "shop.cart.full"
            }

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: CreateCart): CartCreated = CartCreated(command.owner)

                @OnCommand(produces = [ItemAdded::class])
                fun onCommand(command: AddItem): Decision = if (state.items.size >= 10) {
                    Decision.reject(CartErrors.FULL, "The cart is full.")
                } else {
                    Decision.events(ItemAdded(command.productId, command.quantity))
                }
            }

            class CartState(val id: String) {
                var owner: String? = null
                val items = mutableMapOf<String, Int>()

                fun onSourcing(event: CartCreated) { owner = event.owner }

                fun onSourcing(event: ItemAdded) { items[event.productId] = event.quantity }
            }

            @Projection
            class CartSummary {
                @OnEvent
                fun onEvent(event: StateEvent<CartState>) = Unit
            }
            """,
        )
    }

    private fun manifest(): Manifest = ManifestJson.read(File(projectDir, "shop/build/wow/manifest.json"))

    @Test
    fun `the fragment should be completed in an isolated worker and replace the compiler's in the jar`() {
        result.task(":shop:${WowPlugin.GENERATE_FRAGMENT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        result.task(":billing:${WowPlugin.GENERATE_FRAGMENT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.SUCCESS)
        val jar = File(projectDir, "shop/build/libs/shop.jar")
        val fragment = ZipFile(jar).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.filter {
                it.startsWith(
                    Manifest.FRAGMENT_DIRECTORY
                )
            }
            entries.toList().assert().containsExactly(
                "${Manifest.FRAGMENT_DIRECTORY}/",
                "${Manifest.FRAGMENT_DIRECTORY}/shop.json"
            )
            zip.getInputStream(zip.getEntry("${Manifest.FRAGMENT_DIRECTORY}/shop.json")).use(ManifestJson::read)
        }
        fragment.service.assert().isNull()
        fragment.unresolvedSchemaRefs.assert().isEmpty()
        fragment.contexts.single().aggregates.single().state.fingerprint.assert().isNotNull()
    }

    @Test
    fun `a module on wow-kernel alone should get its subscription bindings`() {
        // Decision D149: the fixture compiles against wow-kernel, not the runtime.
        result.output.assert().doesNotContain("not bound")
        File(projectDir, "shop/build/generated/ksp/main/kotlin/sample/CartSummarySubscriptions.kt").readText()
            .assert().contains("me.ahoo.wow.kernel.ProcessorBinding.project<sample.CartState>(")
        File(projectDir, "shop/build/generated/ksp/main/kotlin/sample/ShopModelProvider.kt").readText()
            .assert().contains("override fun processors(", "sample.cartSummaryHandlers(dependencies),")
    }

    @Test
    fun `the merged manifest should hold both modules' contexts`() {
        val manifest = manifest()
        manifest.service.assert().isEqualTo(ServiceInfo("shop", "1.0.0"))
        manifest.contexts.map { it.name }.assert().containsExactly("billing", "shop")
        manifest.unresolvedSchemaRefs.assert().isEmpty()

        val shop = manifest.contexts.single { it.name == "shop" }
        shop.description.assert().isEqualTo("The shop")
        val cart = shop.aggregates.single()
        cart.name.assert().isEqualTo("cart")
        cart.domainCommands.map { it.name to it.aliases }.assert()
            .containsExactly("CreateCart" to emptyList(), "PutItem" to listOf("AddItem"))
        cart.commands.single { it.name == "CreateCart" }.let {
            it.creation.assert().isEqualTo(Creation.REQUIRED)
            it.produces.assert().isEqualTo(listOf("CartCreated"))
        }
        // A Decision says nothing of its events: @OnCommand(produces) declares them, by the event's name.
        cart.commands.single { it.name == "PutItem" }.produces.assert().isEqualTo(listOf("ItemPut"))
        cart.events.map { Triple(it.name, it.revision, it.aliases) }.assert().containsExactly(
            Triple("CartCreated", 1, emptyList()),
            Triple("ItemPut", 2, listOf("ItemAdded")),
        )
        shop.subscriptions.single().let {
            it.name.assert().isEqualTo("CartSummary.onEvent")
            it.kind.assert().isEqualTo(SubscriptionKind.PROJECTION)
            it.source.aggregates.assert().containsExactly("cart")
        }
        manifest.contexts.single { it.name == "billing" }.aggregates.single().name.assert().isEqualTo("account")
    }

    @Test
    fun `the merged manifest should hold the command routes, the framework's errors and the context's own`() {
        val manifest = manifest()
        manifest.contexts.flatMap { context ->
            context.aggregates.flatMap { aggregate -> aggregate.domainCommands.flatMap { it.routes } }
        }.assert().containsExactly(
            RouteManifest("POST", "/billing/account/{id}/open-account"),
            RouteManifest("POST", "/billing/account/open-account"),
            RouteManifest("POST", "/shop/cart/{id}/create-cart"),
            RouteManifest("POST", "/shop/cart/create-cart"),
            RouteManifest("POST", "/shop/cart/{id}/put-item"),
        )
        // The system commands are routed in the Manifest too: the lifecycle commands @AggregateRoot narrows to, and
        // the snapshot rebuild.
        manifest.contexts.single { it.name == "billing" }.aggregates.single().commands.filter { it.system }
            .flatMap { it.routes }.assert().containsExactly(
                RouteManifest("DELETE", "/billing/account/{id}"),
                RouteManifest("POST", "/billing/account/wow.snapshot.rebuild"),
            )
        manifest.errors.assert().isEqualTo(FrameworkErrors.ALL)
        manifest.contexts.associate { it.name to it.errors }.assert().isEqualTo(
            mapOf(
                "billing" to emptyList(),
                "shop" to listOf(ErrorManifest("shop.cart.full", 409, "The cart is full")),
            ),
        )
    }

    @Test
    fun `the OpenAPI document should be written from the merged manifest`() {
        val document = JsonSlurper().parse(File(projectDir, "shop/build/wow/openapi.json")) as Map<*, *>
        document["openapi"].assert().isEqualTo("3.1.0")
        val paths = (document["paths"] as Map<*, *>).keys
        paths.assert().contains("/shop/cart/{id}/create-cart", "/billing/account/open-account")
        val schemas = ((document["components"] as Map<*, *>)["schemas"] as Map<*, *>).keys.map { it.toString() }
        schemas.assert().anyMatch { it.endsWith("CreateCart") }
    }

    @Test
    fun `the merged manifest should list every aggregate's lifecycle commands, as @AggregateRoot narrows them`() {
        val lifecycle = Lifecycle.COMMANDS.keys.sorted()
        manifest().contexts.flatMap { context -> context.aggregates.map { "${context.name}.${it.name}" to it.lifecycle } }
            .assert().containsExactly(
                "billing.account" to listOf(Lifecycle.DELETE_AGGREGATE),
                "shop.cart" to lifecycle,
            )
    }

    /** The modules' jars, as the service's class path holds them. */
    private fun <T> withJars(block: (ClassLoader) -> T): T {
        val jars = listOf("shop", "billing").map { File(projectDir, "$it/build/libs/$it.jar").toURI().toURL() }
        return URLClassLoader(jars.toTypedArray(), javaClass.classLoader).use(block)
    }

    @Test
    fun `the jars' model providers should build their models through ServiceLoader`() {
        val (loaded, account) = withJars { loader ->
            val providers = ModelProvider.load(loader)
            // The domain service the billing model takes: it upper-cases the holder.
            val dependencies = Dependencies { type, _ ->
                Proxy.newProxyInstance(
                    loader,
                    arrayOf(type)
                ) { _, _, arguments -> (arguments[0] as String).uppercase() }
            }
            val models = providers.associate { it.fragment to it.models(dependencies).map { model -> model.type.toString() } }
            val billing = providers.single { it.context == "billing" }.models(dependencies).single()
            models to billing.lifecycleCommands.map { it.simpleName }
        }
        loaded.assert().isEqualTo(
            mapOf(
                "${Manifest.FRAGMENT_DIRECTORY}/billing.json" to listOf("billing.account"),
                "${Manifest.FRAGMENT_DIRECTORY}/shop.json" to listOf("shop.cart"),
            ),
        )
        account.assert().containsExactly("DeleteAggregate")
    }

    @Test
    fun `the jars' model providers should list their processors`() {
        val processors = withJars { loader ->
            ModelProvider.load(loader).associate { it.context to it.processors(Dependencies.NONE).map { p -> p.name } }
        }
        processors.assert().isEqualTo(mapOf("billing" to emptyList(), "shop" to listOf("CartSummary.onEvent")))
    }

    @Test
    fun `a model provider should fail without the domain service its models take`() {
        val error = withJars { loader ->
            val billing = ModelProvider.load(loader).single { it.context == "billing" }
            runCatching { billing.models(Dependencies.NONE) }.exceptionOrNull()
        }
        error.assert().isInstanceOf(MissingDependencyException::class.java)
        (error as MissingDependencyException).type.name.assert().isEqualTo("billing.Holders")
    }

    @Test
    fun `the generated model should upgrade a revision 1 payload with the fixture's @Upgrade`() {
        val event = withShopClasses { loader ->
            val model = loader.loadClass("sample.CartModelKt")
                .getMethod("cartModel", String::class.java, Function1::class.java)
                .invoke(null, "shop", { _: Any? -> }) as AggregateModel<*>
            EventCodec().decode(model, EventRecord("shop.cart.ItemPut", 1, "{\"productId\":\"pen\"}"))
        }
        event.toString().assert().isEqualTo("ItemAdded(productId=pen, quantity=1)")
    }

    private fun <T> withShopClasses(block: (ClassLoader) -> T): T {
        val classes = File(projectDir, "shop/build/classes/kotlin/main").toURI().toURL()
        return URLClassLoader(arrayOf(classes), javaClass.classLoader).use(block)
    }

    @Test
    fun `schemas should follow Jackson, a renamed property included`() {
        val defs = manifest().defs
        val addItem = defs["sample.AddItem"] as Map<*, *>
        (addItem["properties"] as Map<*, *>).keys.assert().containsExactlyInAnyOrder("sku", "quantity", "price")
        (addItem["required"] as List<*>).assert().containsExactlyInAnyOrder("sku", "price")
        ((defs["sample.ItemAdded"] as Map<*, *>)["properties"] as Map<*, *>).keys.assert()
            .containsExactlyInAnyOrder("sku", "quantity")
        // Money is written by @JsonValue: its schema is the declared override.
        (addItem["properties"] as Map<*, *>)["price"].assert().isEqualTo(
            mapOf("type" to "string", "pattern" to "^[0-9]+$")
        )
        defs.keys.assert().contains("sample.CartState", "billing.AccountState", "billing.OpenAccount")
    }

    @Test
    fun `the state fingerprint should be the kernel's for the compiled state class`() {
        val fingerprint = withShopClasses { loader ->
            @Suppress("UNCHECKED_CAST")
            val stateType = loader.loadClass("sample.CartState") as Class<Any>
            EventCodec().stateSchemaFingerprint(
                AggregateModel(AggregateType("shop", "cart"), stateType, {
                    error("unused")
                }, emptyMap(), emptyMap(), emptyList(), emptyList()),
            )
        }
        manifest().contexts.single { it.name == "shop" }.aggregates.single().state.fingerprint.assert().isEqualTo(
            fingerprint
        )
    }

    @Test
    fun `the tools should stay off the build script's class path`() {
        // The plugin under test comes in through TestKit's injected class path, as a published plugin's own
        // dependencies would: the classes the workers use are not visible from it.
        result.output.assert().contains("tools on the build classpath: []")
        val jars = result.output.lines().single { it.startsWith("buildscript jars: ") }
        jars.assert().doesNotContain(
            "jsonschema",
            "jackson-databind",
            "jackson-module-kotlin",
            "kotlin-reflect",
            "wow-kernel"
        )
        PROBED.forEach { name ->
            // They are on the worker's class path instead, which is how the fragment got its schemas.
            Class.forName(name, false, javaClass.classLoader).assert().isNotNull()
        }
    }

    @Test
    fun `a second build should be up to date`() {
        val again = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":shop:${WowPlugin.MERGE_TASK}")
            .build()
        again.task(":shop:${WowPlugin.GENERATE_FRAGMENT_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)
        again.task(":shop:${WowPlugin.MERGE_TASK}")?.outcome.assert().isEqualTo(TaskOutcome.UP_TO_DATE)
    }
}

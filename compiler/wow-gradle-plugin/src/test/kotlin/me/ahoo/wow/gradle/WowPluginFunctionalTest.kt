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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
            .withArguments(":shop:${WowPlugin.MERGE_TASK}", ":shop:jar", ":shop:probeBuildClasspath", "--stacktrace")
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

            @BoundedContext(name = "billing")
            object BillingContext

            @CreateAggregate
            data class OpenAccount(val holder: String)
            data class AccountOpened(val holder: String)

            @AggregateRoot
            class Account(private val state: AccountState) {
                fun onCommand(command: OpenAccount): AccountOpened = AccountOpened(command.holder)
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
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Revision
            import me.ahoo.wow.api.subscribe.StateEvent

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

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: CreateCart): CartCreated = CartCreated(command.owner)

                fun onCommand(command: AddItem): ItemAdded = ItemAdded(command.productId, command.quantity)
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
    fun `the merged manifest should hold both modules' contexts`() {
        val manifest = manifest()
        manifest.service.assert().isEqualTo(ServiceInfo("shop", "1.0.0"))
        manifest.contexts.map { it.name }.assert().containsExactly("billing", "shop")
        manifest.unresolvedSchemaRefs.assert().isEmpty()

        val shop = manifest.contexts.single { it.name == "shop" }
        shop.description.assert().isEqualTo("The shop")
        val cart = shop.aggregates.single()
        cart.name.assert().isEqualTo("cart")
        cart.commands.map { it.name to it.aliases }.assert()
            .containsExactly("CreateCart" to emptyList(), "PutItem" to listOf("AddItem"))
        cart.commands.single { it.name == "CreateCart" }.let {
            it.creation.assert().isEqualTo(Creation.REQUIRED)
            it.produces.assert().isEqualTo(listOf("CartCreated"))
        }
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
        val classes = File(projectDir, "shop/build/classes/kotlin/main").toURI().toURL()
        val fingerprint = URLClassLoader(arrayOf(classes), javaClass.classLoader).use { loader ->
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

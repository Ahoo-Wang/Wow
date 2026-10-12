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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** Roadmap 5a.1: the model processor writes the module's Manifest fragment (decisions D73, D74). */
@OptIn(ExperimentalCompilerApi::class)
class ManifestFragmentTest {
    private val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop", description = "The shop")
        object ShopContext
        """.trimIndent(),
    )

    private val cart = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Alias
        import me.ahoo.wow.api.annotation.AllowCreate
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Description
        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnSourcing
        import me.ahoo.wow.api.annotation.Revision
        import me.ahoo.wow.api.decide.Decision

        @CreateAggregate
        data class CreateCart(val owner: String)
        @Name("PutItem")
        @Alias("AddItem")
        data class AddItem(val productId: String, val quantity: Int)
        @AllowCreate
        data class Checkout(val note: String)
        data class Inspect(val note: String)
        class Commands {
            data class Clear(val all: Boolean)
        }

        data class CartCreated(val owner: String)
        @Name("ItemPut")
        @Revision(2)
        @Alias("ItemAdded", "shop.basket.ItemAdded")
        data class ItemAdded(val productId: String, val quantity: Int)
        sealed interface CheckoutEvent
        data class CheckedOut(val total: Long) : CheckoutEvent
        data class CheckoutFailed(val reason: String) : CheckoutEvent
        data class Audited(val command: String)

        @AggregateRoot
        @Description("A shopping cart")
        class Cart(private val state: CartState) {
            fun onCommand(command: CreateCart): CartCreated = CartCreated(command.owner)

            fun onCommand(command: AddItem): Any =
                if (command.quantity < 1) Decision.reject("cart.empty", "empty") else ItemAdded(command.productId, command.quantity)

            fun onCommand(command: Checkout): CheckoutEvent = CheckedOut(state.items.size.toLong())

            fun onCommand(command: Commands.Clear): List<ItemAdded> = emptyList()

            suspend fun onCommand(command: Inspect) = Unit

            @AfterCommand(include = [Checkout::class, Inspect::class])
            fun afterCommand(command: Any): Audited = Audited(command.javaClass.simpleName)
        }

        class CartState(val id: String) {
            val items = mutableMapOf<String, Int>()

            @OnSourcing
            fun onCreated(event: CartCreated) = Unit

            fun onSourcing(event: ItemAdded) { items[event.productId] = event.quantity }

            fun onSourcing(event: CheckedOut) = Unit

            fun onSourcing(event: CheckoutFailed) = Unit

            fun onSourcing(event: Audited) = Unit
        }
        """.trimIndent(),
    )

    private val processors = SourceFile.kotlin(
        "Processors.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent

        @Projection
        class CartSummary {
            @OnEvent
            fun onEvent(event: StateEvent<CartState>) = Unit
        }

        @Saga
        @Name("checkout")
        class CheckoutSaga {
            fun onEvent(event: StateEvent<*>, context: SagaContext): List<Command<*>> = emptyList()
        }
        """.trimIndent(),
    )

    private fun fragments(compilation: KotlinCompilation): List<File> =
        compilation.workingDir.walkTopDown()
            .filter { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .toList()

    private fun read(file: File): Manifest = ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))

    @Suppress("LongMethod") // The expected fragment, whole.
    @Test
    fun `the fragment should hold the module's aggregates and subscriptions under its bounded context`() {
        val (compilation, result) = compileModel("generate", context, cart, processors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = fragments(compilation).single()
        fragment.name.assert().isEqualTo("shop.json")

        val expected = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    description = "The shop",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "cart",
                            description = "A shopping cart",
                            state = StateManifest(schemaRef("sample.CartState")),
                            commands = listOf(
                                CommandManifest(
                                    name = "Checkout",
                                    schema = schemaRef("sample.Checkout"),
                                    creation = Creation.ALLOWED,
                                    produces = listOf("Audited", "CheckedOut", "CheckoutFailed"),
                                ),
                                CommandManifest(
                                    name = "Clear",
                                    schema = schemaRef("sample.Commands\$Clear"),
                                    produces = listOf("ItemPut"),
                                ),
                                CommandManifest(
                                    name = "CreateCart",
                                    schema = schemaRef("sample.CreateCart"),
                                    creation = Creation.REQUIRED,
                                    produces = listOf("CartCreated"),
                                ),
                                CommandManifest(
                                    name = "Inspect",
                                    schema = schemaRef("sample.Inspect"),
                                    produces = listOf("Audited"),
                                ),
                                CommandManifest(
                                    name = "PutItem",
                                    aliases = listOf("AddItem"),
                                    schema = schemaRef("sample.AddItem"),
                                    produces = null,
                                ),
                            ),
                            events = listOf(
                                EventManifest("Audited", schema = schemaRef("sample.Audited")),
                                EventManifest("CartCreated", schema = schemaRef("sample.CartCreated")),
                                EventManifest("CheckedOut", schema = schemaRef("sample.CheckedOut")),
                                EventManifest("CheckoutFailed", schema = schemaRef("sample.CheckoutFailed")),
                                EventManifest(
                                    name = "ItemPut",
                                    aliases = listOf("ItemAdded", "shop.basket.ItemAdded"),
                                    revision = 2,
                                    schema = schemaRef("sample.ItemAdded"),
                                ),
                            ),
                        ),
                    ),
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = "CartSummary.onEvent",
                            kind = SubscriptionKind.PROJECTION,
                            source = SubscriptionSource(context = "shop", aggregates = listOf("cart")),
                            function = FunctionManifest(FunctionKind.PROJECT, "shop", "CartSummary", "onEvent"),
                        ),
                        SubscriptionManifest(
                            name = "checkout.onEvent",
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource(),
                            function = FunctionManifest(FunctionKind.SAGA, "shop", "checkout", "onEvent"),
                        ),
                    ),
                ),
            ),
        )
        read(fragment).assert().isEqualTo(expected)
        read(fragment).unresolvedSchemaRefs.assert().hasSize(11)
    }

    @Test
    fun `the options should name the context and the fragment`() {
        val options = mapOf(
            ModelSymbolProcessorProvider.CONTEXT_OPTION to "store",
            ModelSymbolProcessorProvider.MODULE_OPTION to "store-domain",
        )
        val (compilation, result) = compileModel("generate", options, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = fragments(compilation).single()
        fragment.name.assert().isEqualTo("store-domain.json")
        read(fragment).contexts.single().let {
            it.name.assert().isEqualTo("store")
            it.description.assert().isNull()
        }
    }

    @Test
    fun `without a bounded context there should be no fragment, and a warning`() {
        val (compilation, result) = compileModel("generate", cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).assert().isEmpty()
        result.messages.assert().contains("No Manifest fragment: the module declares 0 @BoundedContext")
    }

    @Test
    fun `report mode should write no fragment`() {
        val (compilation, result) = compileModel("report", context, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).assert().isEmpty()
    }
}

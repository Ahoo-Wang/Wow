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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.cart
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.context
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.fragments
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.read
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart
import me.ahoo.wow.manifest.SystemCommands
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** What the fragment holds ([ManifestFragmentBuilder]): the aggregates and subscriptions, and what each command produces. */
@OptIn(ExperimentalCompilerApi::class)
class ManifestFragmentBuilderTest {
    private val processors = SourceFile.kotlin(
        "Processors.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.client.CommandRequest
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
            @OnEvent
            fun onEvent(event: StateEvent<*>, context: SagaContext) = Unit
        }
        """.trimIndent(),
    )

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
                                    routes = listOf(
                                        RouteManifest("POST", "/shop/cart/{id}/checkout"),
                                        RouteManifest("POST", "/shop/cart/checkout"),
                                    ),
                                ),
                                CommandManifest(
                                    name = "Clear",
                                    schema = schemaRef("sample.Commands\$Clear"),
                                    produces = listOf("ItemPut"),
                                    routes = listOf(RouteManifest("POST", "/shop/cart/{id}/clear")),
                                ),
                                CommandManifest(
                                    name = "CreateCart",
                                    schema = schemaRef("sample.CreateCart"),
                                    creation = Creation.REQUIRED,
                                    produces = listOf("CartCreated"),
                                    routes = listOf(
                                        RouteManifest("POST", "/shop/cart/{id}/create-cart"),
                                        RouteManifest("POST", "/shop/cart/create-cart"),
                                    ),
                                ),
                                CommandManifest(
                                    name = "Inspect",
                                    schema = schemaRef("sample.Inspect"),
                                    produces = listOf("Audited"),
                                    routes = listOf(RouteManifest("POST", "/shop/cart/{id}/inspect")),
                                ),
                                CommandManifest(
                                    name = "PutItem",
                                    aliases = listOf("AddItem"),
                                    schema = schemaRef("sample.AddItem"),
                                    produces = null,
                                    // Named by its @Name, not its class.
                                    routes = listOf(RouteManifest("POST", "/shop/cart/{id}/put-item")),
                                ),
                            ) + listOf(
                                "wow.lifecycle.DeleteAggregate",
                                "wow.lifecycle.RestoreAggregate",
                                "wow.lifecycle.TransferOwner",
                                "wow.lifecycle.TransferSpace",
                            ).map { SystemCommands.lifecycle("shop", "cart", it) } + SystemCommands.rebuild("shop", "cart"),
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
                            queries = listOf(
                                QueryManifest(
                                    "snapshot",
                                    listOf(
                                        RouteManifest("POST", "/shop/cart/snapshot/query"),
                                        RouteManifest("GET", "/shop/cart/snapshot/query"),
                                    ),
                                ),
                                QueryManifest(
                                    "events",
                                    listOf(
                                        RouteManifest("POST", "/shop/cart/events/query"),
                                        RouteManifest("GET", "/shop/cart/events/query"),
                                    ),
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
                            start = SubscriptionStart.BEGINNING,
                        ),
                        SubscriptionManifest(
                            name = "checkout.onEvent",
                            kind = SubscriptionKind.SAGA,
                            // StateEvent<*> takes any state, null included (decision D140).
                            source = SubscriptionSource(nullableState = true),
                            function = FunctionManifest(FunctionKind.SAGA, "shop", "checkout", "onEvent"),
                            // List<Command<*>> does not say which commands.
                            derives = emptyList(),
                            start = SubscriptionStart.END,
                        ),
                    ),
                    published = listOf(PublishedManifest("cart", "wow.shop.cart.state")),
                ),
            ),
        )
        read(fragment).assert().isEqualTo(expected)
        read(fragment).unresolvedSchemaRefs.assert().hasSize(16)
    }

    private val order = SourceFile.kotlin(
        "Order.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.BoundedContext
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.subscribe.StateEvent
        import reactor.core.publisher.Flux

        @BoundedContext(name = "orders")
        object OrdersContext

        @CreateAggregate
        data class PlaceOrder(val id: String)
        data class Ship(val note: String)
        data class Rename(val name: String)
        data class Archive(val all: Boolean)
        data class Audit(val note: String)

        data class OrderPlaced(val id: String)
        sealed interface ShipEvent
        sealed interface Shipped : ShipEvent
        data class ShippedByAir(val flight: String) : Shipped
        data class ShippedBySea(val vessel: String) : Shipped
        data class ShipFailed(val reason: String) : ShipEvent
        data class Renamed(val name: String)
        data class Noted(val note: String)

        @AggregateRoot
        class Order(private val state: OrderState) {
            fun onCommand(command: PlaceOrder): Array<OrderPlaced> = arrayOf(OrderPlaced(command.id))

            fun onCommand(command: Ship): ShipEvent = ShipFailed(command.note)

            fun onCommand(command: Rename): Any = Renamed(command.name)

            fun onCommand(command: Archive): List<*> = emptyList<Any>()

            fun onCommand(command: Audit): Flux<Renamed> = Flux.just(Renamed(command.note))

            @AfterCommand(exclude = [PlaceOrder::class])
            fun afterCommand(command: Any): Noted = Noted(command.javaClass.simpleName)
        }

        class OrderState(val id: String) {
            fun onSourcing(event: OrderPlaced) = Unit

            fun onSourcing(event: ShippedByAir) = Unit

            fun onSourcing(event: ShippedBySea) = Unit

            fun onSourcing(event: ShipFailed) = Unit

            fun onSourcing(event: Renamed) = Unit

            fun onSourcing(event: Noted) = Unit
        }

        @Reactor
        class Notifier {
            @OnEvent
            fun onEvent(event: StateEvent<OrderState>) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `produces should follow containers, sealed types and excluded hooks, and routes the creation of each command`() {
        val (compilation, result) = compileModel("generate", order)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val context = read(fragments(compilation).single()).contexts.single()
        context.description.assert().isNull()
        context.aggregates.single().domainCommands.associate { it.name to it.produces }.assert().isEqualTo(
            mapOf(
                "Archive" to null,
                "Audit" to listOf("Noted", "Renamed"),
                "PlaceOrder" to listOf("OrderPlaced"),
                "Rename" to null,
                "Ship" to listOf("Noted", "ShipFailed", "ShippedByAir", "ShippedBySea"),
            ),
        )
        context.aggregates.single().domainCommands.associate { it.name to it.routes.map { route -> route.path } }
            .assert().isEqualTo(
                mapOf(
                    "Archive" to listOf("/orders/order/{id}/archive"),
                    "Audit" to listOf("/orders/order/{id}/audit"),
                    "PlaceOrder" to listOf("/orders/order/{id}/place-order", "/orders/order/place-order"),
                    "Rename" to listOf("/orders/order/{id}/rename"),
                    "Ship" to listOf("/orders/order/{id}/ship"),
                ),
            )
        context.subscriptions.single().let {
            it.kind.assert().isEqualTo(SubscriptionKind.REACTOR)
            it.function.kind.assert().isEqualTo(FunctionKind.REACT)
            it.source.aggregates.assert().containsExactly("order")
        }
    }
}

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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith

/** The Manifest the compatibility tests compare, and the helpers that change one part of it. */
internal object CompatibilityFixtures {
    const val CONTEXT = "contexts[shop]"
    const val CART = "$CONTEXT.aggregates[cart]"
    const val DELETE = "wow.lifecycle.DeleteAggregate"
    const val TRANSFER_SPACE = "wow.lifecycle.TransferSpace"
    val STRING = mapOf("type" to "string")
    val ROUTE = RouteManifest("POST", "/shop/cart/{id}/add-item")
    val ERROR = ErrorManifest("shop.cart.full", 409)

    fun schema(vararg properties: Pair<String, Any?>, required: List<String> = emptyList()) =
        mapOf("type" to "object", "properties" to mapOf(*properties), "required" to required)

    val ADD_ITEM = CommandManifest(
        name = "AddItem",
        schema = Manifest.schemaRef("shop.AddItem"),
        produces = listOf("ItemAdded"),
        routes = listOf(ROUTE),
    )
    private val ITEM_ADDED = EventManifest(name = "ItemAdded", schema = Manifest.schemaRef("shop.ItemAdded"))
    private val SUBSCRIPTION = SubscriptionManifest(
        name = "CartProjector.onItemAdded",
        kind = SubscriptionKind.PROJECTION,
        source = SubscriptionSource(context = "shop", aggregates = listOf("cart")),
        function = FunctionManifest(FunctionKind.PROJECT, "shop", "CartProjector", "onItemAdded"),
        derives = listOf("shop.cart.Checkout"),
    )

    val COMMITTED = SubscriptionManifest(
        name = "CheckoutSaga.onCommitted",
        kind = SubscriptionKind.SAGA,
        source = SubscriptionSource(),
        function = FunctionManifest(FunctionKind.SAGA, "shop", "CheckoutSaga", "onCommitted"),
        committed = listOf("shop.cart.Checkout", "shop.cart.Clear"),
    )

    val BASELINE = Manifest(
        service = ServiceInfo("shop-service", "10.1.0"),
        contexts = listOf(
            ContextManifest(
                name = "shop",
                aggregates = listOf(
                    AggregateManifest(
                        name = "cart",
                        state = StateManifest(Manifest.schemaRef("shop.CartState"), fingerprint = "a"),
                        commands = listOf(ADD_ITEM, SystemCommands.lifecycle("shop", "cart", DELETE)),
                        events = listOf(ITEM_ADDED),
                        queries = listOf(
                            QueryManifest("snapshot", listOf(RouteManifest("POST", "/shop/cart/snapshot/query")))
                        ),
                    ),
                ),
                subscriptions = listOf(SUBSCRIPTION),
                published = listOf(PublishedManifest("cart", "wow.shop.cart.state")),
                errors = listOf(ERROR),
            ),
        ),
        errors = listOf(ErrorManifest("wow.command.invalid", 400)),
        defs = mapOf(
            "shop.CartState" to schema("items" to mapOf("type" to "array", "items" to STRING)),
            "shop.AddItem" to schema("productId" to STRING, required = listOf("productId")),
            "shop.ItemAdded" to schema("productId" to STRING, required = listOf("productId")),
            DELETE to mapOf("type" to "object"),
        ),
    )

    fun Manifest.context(change: (ContextManifest) -> ContextManifest): Manifest =
        copyWith(contexts = contexts.map(change))

    fun Manifest.cart(change: (AggregateManifest) -> AggregateManifest): Manifest =
        context { it.copyWith(aggregates = it.aggregates.map(change)) }

    fun Manifest.addItem(change: (CommandManifest) -> CommandManifest): Manifest =
        cart {
            it.copyWith(
                commands = it.commands.map { command -> if (command.system) command else change(command) }
            )
        }

    fun Manifest.itemAdded(change: (EventManifest) -> EventManifest): Manifest =
        cart { it.copyWith(events = it.events.map(change)) }

    fun Manifest.subscription(change: (SubscriptionManifest) -> SubscriptionManifest): Manifest =
        context { it.copyWith(subscriptions = it.subscriptions.map(change)) }

    fun Manifest.def(key: String, schema: Map<String, Any?>): Manifest =
        copyWith(defs = defs + (key to schema))

    val INTEGER = mapOf("type" to "integer")

    val NARROWED_EVENT =
        schema("productId" to STRING, "quantity" to STRING, required = listOf("productId", "quantity"))
}

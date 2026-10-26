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

package me.ahoo.wow.runtime.binding

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import java.util.concurrent.CopyOnWriteArrayList
import me.ahoo.wow.manifest.Creation as ManifestCreation

const val SHOP = "shop"
const val SHOP_SERVICE = "shop-service"
const val TENANT = "t"
val ORDER: AggregateType = AggregateType(SHOP, "order")
val CART: AggregateType = AggregateType(SHOP, "cart")

/** Places an order of [items]; it creates the order. */
data class Place(val items: List<String>)
data class Placed(val items: List<String>)

class OrderState {
    var items: List<String> = emptyList()
}

/** Adds [items] to a cart, creating it. */
data class AddItems(val items: List<String>)
data class ItemsAdded(val items: List<String>)

/** Removes [items] from an existing cart; the item `forbidden` is rejected. */
data class RemoveItems(val items: List<String>)
data class ItemsRemoved(val items: List<String>)

class CartState {
    var items: Set<String> = emptySet()
}

const val FORBIDDEN_ITEM = "forbidden"
const val FORBIDDEN = "cart.forbidden"

fun orderModel(): AggregateModel<OrderState> = aggregate<OrderState>(SHOP, "order") {
    initial { OrderState() }
    decide<Place>(Creation.REQUIRED) { _, body -> Decision.events(Placed(body.items)) }
    evolve<Placed> { items = it.items }
}

/** The cart; every command its decide function decides is recorded in [decided], by id. */
fun cartModel(decided: MutableList<String> = CopyOnWriteArrayList()): AggregateModel<CartState> =
    aggregate<CartState>(SHOP, "cart") {
        initial { CartState() }
        decide<AddItems>(Creation.ALLOWED) { _, body -> Decision.events(ItemsAdded(body.items)) }
        decide<RemoveItems> { state, body ->
            decided += command.id
            when {
                FORBIDDEN_ITEM in body.items -> Decision.reject(FORBIDDEN, "$FORBIDDEN_ITEM cannot be removed.")
                body.items.none { it in state.items } -> Decision.NoOp
                else -> Decision.events(ItemsRemoved(body.items.filter { it in state.items }))
            }
        }
        evolve<ItemsAdded> { items = items + it.items }
        evolve<ItemsRemoved> { items = items - it.items.toSet() }
    }

private fun aggregateManifest(name: String, commands: List<Pair<String, ManifestCreation>>, events: List<String>) =
    AggregateManifest(
        name,
        state = StateManifest(Manifest.schemaRef("${name}State")),
        commands = commands.map { (command, creation) ->
            CommandManifest(command, schema = Manifest.schemaRef(command), creation = creation)
        },
        events = events.map { EventManifest(it, schema = Manifest.schemaRef(it)) },
        lifecycle = Lifecycle.COMMANDS.keys.toList(),
    )

/** The Manifest of [orderModel] and [cartModel]. */
fun shopManifest(): Manifest = Manifest(
    service = ServiceInfo(SHOP_SERVICE),
    contexts = listOf(
        ContextManifest(
            SHOP,
            aggregates = listOf(
                aggregateManifest("order", listOf("Place" to ManifestCreation.REQUIRED), listOf("Placed")),
                aggregateManifest(
                    "cart",
                    listOf("AddItems" to ManifestCreation.ALLOWED, "RemoveItems" to ManifestCreation.NONE),
                    listOf("ItemsAdded", "ItemsRemoved"),
                ),
            ),
        ),
    ),
)

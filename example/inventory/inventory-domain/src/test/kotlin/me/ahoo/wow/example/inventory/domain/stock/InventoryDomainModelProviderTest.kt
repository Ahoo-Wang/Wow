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

package me.ahoo.wow.example.inventory.domain.stock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import me.ahoo.wow.manifest.FunctionKind as ManifestFunctionKind

/** What the compiler generated for the inventory: its binding index (decision D107) and its Manifest fragment. */
class InventoryDomainModelProviderTest {
    private val provider = ModelProvider.load().single()

    private fun fragment(): Manifest {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment)) {
            "No ${provider.fragment} on the class path."
        }
        return ManifestTree.read(resource.use { JsonMapper.builder().build().readValue(it, Map::class.java) })
    }

    @Test
    fun `the binding index should build the stock and bind the saga's handlers to the shop's orders`() {
        provider.assert().isInstanceOf(InventoryDomainModelProvider::class.java)
        provider.context.assert().isEqualTo("inventory")
        provider.fragment.assert().isEqualTo("META-INF/wow/manifest/inventory-domain.json")
        provider.models(Dependencies.NONE).map { it.type.toString() }.assert().containsExactly("inventory.stock")
        val handlers = provider.processors(Dependencies.NONE).associateBy { it.name }
        handlers.keys.assert().containsExactlyInAnyOrder(
            "OrderStockSaga.onOrderShipped",
            "OrderStockSaga.onOrderCancelled"
        )
        handlers.values.forEach {
            it.kind.assert().isEqualTo(FunctionKind.SAGA)
            it.aggregates.assert().containsExactly("example.order")
            it.isSourced("inventory").assert().isTrue()
            it.nullableState.assert().isFalse()
            it.stateType.assert().isEqualTo(OrderView::class.java)
        }
    }

    @Test
    fun `the fragment should route the stock's commands and publish its stream`() {
        val context = fragment().contexts.single()
        context.name.assert().isEqualTo("inventory")
        val stock = context.aggregates.single()
        stock.name.assert().isEqualTo("stock")
        stock.commands.associate { it.name to it.creation }.assert().isEqualTo(
            mapOf(
                "CommitReservation" to Creation.NONE,
                "ReceiveStock" to Creation.ALLOWED,
                "ReleaseReservation" to Creation.NONE,
                "ReserveStock" to Creation.NONE,
            ),
        )
        stock.commands.single { it.name == "ReserveStock" }.schema
            .assert().isEqualTo("#/\$defs/me.ahoo.wow.example.inventory.api.ReserveStock")
        context.published.assert().containsExactly(PublishedManifest("stock", "wow.inventory.stock.state", true))
        context.errors.map { it.code }.assert().containsExactly(
            StockRejections.INSUFFICIENT,
            StockRejections.INVALID_QUANTITY,
            StockRejections.NO_RESERVATION,
        )
    }

    @Test
    fun `the fragment should list the saga's handlers as subscriptions of the shop's order stream`() {
        fragment().contexts.single().subscriptions.assert().containsExactly(
            subscription("onOrderCancelled", "inventory.stock.ReleaseReservation"),
            subscription("onOrderShipped", "inventory.stock.CommitReservation"),
        )
    }

    private fun subscription(function: String, derives: String) = SubscriptionManifest(
        name = "OrderStockSaga.$function",
        kind = SubscriptionKind.SAGA,
        source = SubscriptionSource(context = "example", aggregates = listOf("order")),
        function = FunctionManifest(ManifestFunctionKind.SAGA, "inventory", "OrderStockSaga", function),
        derives = listOf(derives),
        // Decision D194: another context's stream is read from its beginning, without any configuration.
        start = SubscriptionStart.BEGINNING,
    )
}

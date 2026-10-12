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

package me.ahoo.wow.example.shop.domain

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.example.shop.domain.order.InventoryService
import me.ahoo.wow.example.shop.domain.order.OrderFixtures
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.createOrder
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.orderCreated
import me.ahoo.wow.example.shop.domain.order.OrderState
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.test.aggregateSpec
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * What the compiler generated for the module: the binding index (decision D107), found through `ServiceLoader`, and
 * the Manifest fragment it names.
 */
class ShopDomainModelProviderTest {
    private val provider = ModelProvider.load().single()

    private fun fragment(): Manifest {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment)) {
            "No ${provider.fragment} on the class path."
        }
        val tree = resource.use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        return ManifestTree.read(tree)
    }

    @Test
    fun `the module's binding index should be registered for ServiceLoader`() {
        provider.assert().isInstanceOf(ShopDomainModelProvider::class.java)
        provider.context.assert().isEqualTo("example")
        provider.fragment.assert().isEqualTo("META-INF/wow/manifest/shop-domain.json")
    }

    @Test
    fun `the binding index should build both models with the host's services`() {
        val models = provider.models(Dependencies.of(OrderFixtures.inventory, OrderFixtures.pricing))
        models.map { it.type.toString() }.assert().containsExactly("example.cart", "example.order")

        // The order model runs with the services it was given.
        @Suppress("UNCHECKED_CAST")
        val order = models.single { it.type.toString() == "example.order" } as AggregateModel<OrderState>
        aggregateSpec(order, id = OrderFixtures.ORDER_ID) {
            whenCommand(createOrder) { expectEvents(orderCreated) }
        }
    }

    @Test
    fun `a missing service should fail building the models, naming it`() {
        assertThrownBy<MissingDependencyException> {
            provider.models(Dependencies.of(OrderFixtures.pricing))
        }.hasMessageContaining(InventoryService::class.java.name)
    }

    @Test
    fun `the fragment should hold both aggregates of the context, with their snapshots and lifecycle`() {
        val context = fragment().contexts.single()
        context.name.assert().isEqualTo("example")
        context.aggregates.map { it.name }.assert().containsExactly("cart", "order")
        context.aggregates.forEach { aggregate ->
            aggregate.snapshot.enabled.assert().isTrue()
            aggregate.lifecycle.assert().containsExactly(
                "wow.lifecycle.ApplyTags",
                "wow.lifecycle.DeleteAggregate",
                "wow.lifecycle.RestoreAggregate",
                "wow.lifecycle.TransferOwner",
                "wow.lifecycle.TransferSpace",
            )
        }
    }

    @Test
    fun `the fragment should route every command, the creating ones without an id too`() {
        val aggregates = fragment().contexts.single().aggregates.associateBy { it.name }
        val routes = aggregates.mapValues { (_, aggregate) ->
            aggregate.commands.associate { it.name to it.routes.map(RouteManifest::path) }
        }
        routes.getValue("cart").assert().isEqualTo(
            mapOf(
                "AddCartItem" to listOf("/example/cart/{id}/add-cart-item", "/example/cart/add-cart-item"),
                "ChangeQuantity" to listOf("/example/cart/{id}/change-quantity"),
                "RemoveCartItem" to listOf("/example/cart/{id}/remove-cart-item"),
            ),
        )
        routes.getValue("order").assert().isEqualTo(
            mapOf(
                "ChangeAddress" to listOf("/example/order/{id}/change-address"),
                "CreateOrder" to listOf("/example/order/{id}/create-order", "/example/order/create-order"),
                "PayOrder" to listOf("/example/order/{id}/pay-order"),
                "ReceiptOrder" to listOf("/example/order/{id}/receipt-order"),
                "ShipOrder" to listOf("/example/order/{id}/ship-order"),
            ),
        )
        aggregates.getValue("cart").commands.single { it.name == "AddCartItem" }.creation
            .assert().isEqualTo(Creation.ALLOWED)
        aggregates.getValue("order").commands.single { it.name == "CreateOrder" }.creation
            .assert().isEqualTo(Creation.REQUIRED)
    }

    @Test
    fun `the fragment should list the order's events with their revisions`() {
        val order = fragment().contexts.single().aggregates.single { it.name == "order" }
        order.events.associate { it.name to it.revision }.assert().isEqualTo(
            mapOf(
                "AddressChanged" to 1,
                "OrderCreated" to 2,
                "OrderOverPaid" to 1,
                "OrderPaid" to 1,
                "OrderPayDuplicated" to 1,
                "OrderReceived" to 1,
                "OrderShipped" to 1,
            ),
        )
        // A sealed return type tells the compiler every event a command can produce.
        order.commands.single { it.name == "PayOrder" }.produces
            .assert().isEqualTo(listOf("OrderOverPaid", "OrderPaid", "OrderPayDuplicated"))
    }
}

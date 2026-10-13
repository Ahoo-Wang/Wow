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
import me.ahoo.wow.example.shop.domain.cart.CartRejections
import me.ahoo.wow.example.shop.domain.customer.CustomerRejections
import me.ahoo.wow.example.shop.domain.order.OrderFixtures
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.createOrder
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.orderCreated
import me.ahoo.wow.example.shop.domain.order.OrderRejections
import me.ahoo.wow.example.shop.domain.order.OrderState
import me.ahoo.wow.example.shop.domain.order.PricingService
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.test.aggregateSpec
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart
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
    fun `the binding index should build every model with the host's services`() {
        val models = provider.models(Dependencies.of(OrderFixtures.pricing))
        models.map { it.type.toString() }.assert().containsExactly("example.cart", "example.customer", "example.order")

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
            provider.models(Dependencies.NONE)
        }.hasMessageContaining(PricingService::class.java.name)
    }

    @Test
    fun `the fragment should hold every aggregate of the context, with their snapshots and lifecycle`() {
        val context = fragment().contexts.single()
        context.name.assert().isEqualTo("example")
        context.aggregates.map { it.name }.assert().containsExactly("cart", "customer", "order")
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
    fun `the fragment should route every command, the creating and the mounted ones without an id too`() {
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
                "CancelOrder" to listOf("/example/order/{id}/cancel-order"),
                "ChangeAddress" to listOf("/example/order/{id}/change-address"),
                "CreateOrder" to listOf("/example/order/{id}/create-order", "/example/order/create-order"),
                "PayOrder" to listOf("/example/order/{id}/pay-order"),
                "PaymentNotified" to listOf("/example/order/{id}/payment-notified", "/example/order/payment-notified"),
                "ReceiptOrder" to listOf("/example/order/{id}/receipt-order"),
                "ShipOrder" to listOf("/example/order/{id}/ship-order"),
            ),
        )
        // Decision D182: ChangePassword gets its route without an id from the server's transformer, not the fragment.
        routes.getValue("customer").assert().isEqualTo(
            mapOf(
                "ChangePassword" to listOf("/example/customer/{id}/change-password"),
                "ImportCustomer" to listOf(
                    "/example/customer/{id}/import-customer",
                    "/example/customer/import-customer",
                ),
                "RegisterCustomer" to listOf(
                    "/example/customer/{id}/register-customer",
                    "/example/customer/register-customer",
                ),
            ),
        )
        val customer = aggregates.getValue("customer").commands.associateBy { it.name }
        customer.getValue("ImportCustomer").mounted.assert().isTrue()
        customer.getValue("ImportCustomer").transformsTo.assert().containsExactly("example.customer.RegisterCustomer")
        customer.getValue("RegisterCustomer").mounted.assert().isFalse()
        aggregates.getValue("order").commands.single { it.name == "PaymentNotified" }.transformsTo
            .assert().containsExactly("example.order.PayOrder")
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
                "OrderCancelled" to 1,
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

    @Test
    fun `the fragment should list what each command produces, @OnCommand(produces) declaring a Decision's events`() {
        val produces = fragment().contexts.single().aggregates.associate { aggregate ->
            aggregate.name to aggregate.commands.associate { it.name to it.produces }
        }
        produces.assert().isEqualTo(
            mapOf(
                "cart" to mapOf(
                    "AddCartItem" to listOf("CartItemAdded", "CartQuantityChanged"),
                    "ChangeQuantity" to listOf("CartQuantityChanged"),
                    "RemoveCartItem" to listOf("CartItemRemoved"),
                ),
                // A mounted command produces nothing itself: the command it becomes does.
                "customer" to mapOf(
                    "ChangePassword" to listOf("PasswordChanged"),
                    "ImportCustomer" to null,
                    "RegisterCustomer" to listOf("CustomerRegistered"),
                ),
                "order" to mapOf(
                    "CancelOrder" to listOf("OrderCancelled"),
                    "ChangeAddress" to listOf("AddressChanged"),
                    "CreateOrder" to listOf("OrderCreated"),
                    "PayOrder" to listOf("OrderOverPaid", "OrderPaid", "OrderPayDuplicated"),
                    "PaymentNotified" to null,
                    "ReceiptOrder" to listOf("OrderReceived"),
                    "ShipOrder" to listOf("OrderShipped"),
                ),
            ),
        )
    }

    @Test
    fun `the fragment should route the query models of each aggregate and publish its state events`() {
        val context = fragment().contexts.single()
        context.aggregates.forEach { aggregate ->
            aggregate.queries.assert().isEqualTo(
                listOf("snapshot", "events").map { model ->
                    val path = "/example/${aggregate.name}/$model/query"
                    QueryManifest(model, listOf(RouteManifest("POST", path), RouteManifest("GET", path)))
                },
            )
        }
        context.published.assert().containsExactly(
            PublishedManifest("cart", "wow.example.cart.state", includeState = true),
            PublishedManifest("order", "wow.example.order.state", includeState = true),
        )
    }

    @Test
    fun `the fragment should list the sagas with the commands they derive, where they start and their rejections`() {
        fragment().contexts.single().subscriptions.assert().containsExactly(
            SubscriptionManifest(
                name = "CartSaga.onOrderCreated",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(context = "example", aggregates = listOf("order")),
                function = FunctionManifest(FunctionKind.SAGA, "example", "CartSaga", "onOrderCreated"),
                derives = listOf("example.cart.RemoveCartItem"),
                start = SubscriptionStart.END,
            ),
            // Decisions D136, D153: the handler of the cart's refusals, which reads no log.
            SubscriptionManifest(
                name = "CartSaga.onRemoveRejected",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(),
                function = FunctionManifest(FunctionKind.SAGA, "example", "CartSaga", "onRemoveRejected"),
                rejected = listOf("example.cart.RemoveCartItem"),
            ),
            // Decision D144: the inventory's command, another context's, is referred to by its schema.
            SubscriptionManifest(
                name = "OrderSaga.onOrderCreated",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(context = "example", aggregates = listOf("order")),
                function = FunctionManifest(FunctionKind.SAGA, "example", "OrderSaga", "onOrderCreated"),
                derives = listOf(RESERVE_STOCK),
                start = SubscriptionStart.END,
            ),
            SubscriptionManifest(
                name = "OrderSaga.onReserveRejected",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(),
                function = FunctionManifest(FunctionKind.SAGA, "example", "OrderSaga", "onReserveRejected"),
                derives = listOf("example.order.CancelOrder"),
                rejected = listOf(RESERVE_STOCK),
            ),
        )
    }

    @Test
    fun `the fragment should list the context's error codes from @ErrorCode`() {
        fragment().contexts.single().errors.map { Triple(it.code, it.status, it.description) }.assert().containsExactly(
            Triple(CartRejections.FULL, 422, "The cart already holds its maximum number of products."),
            Triple(CartRejections.ITEM_NOT_FOUND, 422, "The product is not in the cart."),
            Triple(
                CartRejections.NOT_OWNER,
                403,
                "The caller is not the customer whose cart this is: a cart's id is its customer's user id.",
            ),
            Triple(CustomerRejections.USERNAME_TAKEN, 409, "Another customer holds the user name."),
            Triple(OrderRejections.NO_ITEMS, 422, "The order has no item."),
            Triple(OrderRejections.PRICE_CHANGED, 422, "A product's price is no longer the one the customer saw."),
            Triple(
                OrderRejections.WRONG_STATUS,
                409,
                "The order's status does not allow the command, such as shipping an order that is not paid.",
            ),
        )
    }

    private companion object {
        const val RESERVE_STOCK = "#/\$defs/me.ahoo.wow.example.inventory.api.ReserveStock"
    }
}

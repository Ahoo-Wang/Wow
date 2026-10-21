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

package me.ahoo.wow.example.eshop.ordering.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.ordering.domain.order.BuyerNotifications
import me.ahoo.wow.example.eshop.ordering.domain.order.OrderSummaries
import me.ahoo.wow.example.eshop.ordering.domain.order.PaymentPolicy
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.time.Duration.Companion.minutes

/** What the compiler generated for the module: its binding index, found through `ServiceLoader`, and its fragment. */
class OrderingDomainModelProviderTest {
    private val provider = ModelProvider.load().single()

    // region provider
    @Test
    fun `the binding index should name the context and the fragment and build the order model`() {
        provider.context.assert().isEqualTo("eshop-ordering")
        provider.fragment.assert().isEqualTo("META-INF/wow/manifest/eshop-ordering-domain.json")
        provider.models(Dependencies.of(PaymentPolicy(30.minutes))).map { it.type.toString() }
            .assert().containsExactly("eshop-ordering.order")
        val ports = Dependencies.of(OrderSummaries { _, _ -> }, BuyerNotifications { _, _, _ -> })
        provider.processors(ports).map { it.name }.assert().containsExactlyInAnyOrder(
            "OrderProcessSaga.onOrderSubmitted",
            "OrderProcessSaga.onOrderAwaitingValidation",
            "OrderProcessSaga.onOrderCancelled",
            "OrderProcessSaga.onOrderShipped",
            "OrderSummaryProjection.onEvent",
            "ShipmentNotifier.onOrderShipped",
        )
    }
    // endregion

    @Test
    fun `the fragment should declare the scheduled cancellation and the sagas' commands and sources`() {
        val fragment = JsonMapper.builder().build().readTree(
            javaClass.classLoader.getResource(provider.fragment)!!.readBytes()
        )
        val context = fragment["contexts"].single()
        val order = context["aggregates"].single()
        order["schedules"].values().map { it.asString() }.assert().containsExactly("eshop-ordering.order.CancelOrder")
        val subscriptions = context["subscriptions"].values().associateBy { it["name"].asString() }
        subscriptions.getValue("OrderProcessSaga.onOrderAwaitingValidation")["derives"].single().asString()
            .assert().endsWith("ReserveStock")
        // Decision D192: the success receipts of the reservations go to the saga's @OnCommitted handler.
        val committed = subscriptions.getValue("OrderProcessSaga.onReserveCommitted")
        committed["committed"].single().asString().assert().endsWith("ReserveStock")
        committed["derives"].single().asString().assert().isEqualTo("eshop-ordering.order.ConfirmOrderStock")
        subscriptions.keys.assert().noneMatch { it.startsWith("StockConfirmationSaga") }
        context["errors"].values().map { it["code"].asString() }.assert().contains(
            "eshop-ordering.order.invalid-items",
            "eshop-ordering.order.wrong-status",
            "eshop-ordering.order.wrong-amount",
        )
    }
}

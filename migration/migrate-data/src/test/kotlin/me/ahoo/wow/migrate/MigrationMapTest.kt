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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MigrationMapTest {
    @Test
    fun `the example's map reads`() {
        val map = NineXExample.map
        map.aggregates.map { it.from }.assert().containsExactly("example-service.order", "example-service.cart")
        val order = map.aggregates.first()
        order.source().assert().isEqualTo(NineXAggregate("example-service", "order"))
        order.events.first().assert().isEqualTo(
            EventMapping("order_created", "0.0.1", "OrderCreated", 1, upgrade = "upgradeOrderCreatedFrom1"),
        )
        map.headers.assert().isEmpty()
        order.state.assert().isEqualTo(StateMapping())
    }

    @Test
    fun `format 1 maps the state fields of an aggregate when told`() {
        val map = MigrationMap.read(
            """{"format":1,"aggregates":[{"from":"s.order","to":"c.order",
               "state":{"fields":{"orderStatus":"status"},"ignore":["items.totalPrice"]}}]}""",
        )
        map.aggregates.single().state.assert()
            .isEqualTo(StateMapping(mapOf("orderStatus" to "status"), listOf("items.totalPrice")))
        assertThrows<Exception> {
            MigrationMap.read("""{"format":1,"aggregates":[{"from":"s.order","to":"c.order","state":{"rename":{}}}]}""")
        }
    }

    @Test
    fun `another format, or a property format 1 does not define, is refused`() {
        assertThrows<Exception> { MigrationMap.read("""{"format":2,"aggregates":[]}""") }
            .message!!.assert().contains("format 2")
        assertThrows<Exception> { MigrationMap.read("""{"format":1,"aggregates":[],"extra":true}""") }
    }

    @Test
    fun `headers keep request facts only, under a name`() {
        MigrationMap(1, emptyList(), mapOf("app_id" to "app")).headers.assert().containsEntry("app_id", "app")
        assertThrows<IllegalArgumentException> { MigrationMap(1, emptyList(), mapOf("trace_id" to "trace")) }
        assertThrows<IllegalArgumentException> { MigrationMap(1, emptyList(), mapOf("command_wait_stage" to "x")) }
        assertThrows<IllegalArgumentException> { MigrationMap(1, emptyList(), mapOf("app_id" to " ")) }
    }

    @Test
    fun `aggregate names have both parts`() {
        AggregateMapping("a.b.order", "ctx.order").source().assert().isEqualTo(NineXAggregate("a.b", "order"))
        assertThrows<IllegalArgumentException> { AggregateMapping("order", "ctx.order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.", "ctx.order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.order", "order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.order", ".order") }
        assertThrows<IllegalArgumentException> { AggregateMapping("ctx.order", "ctx.order", defaultCommand = " ") }
    }

    @Test
    fun `an event mapping names its target or drops it`() {
        EventMapping("retired", drop = true).to.assert().isNull()
        assertThrows<IllegalArgumentException> { EventMapping(" ", to = "A") }
        assertThrows<IllegalArgumentException> { EventMapping("a") }
        assertThrows<IllegalArgumentException> { EventMapping("a", to = "A", drop = true) }
        assertThrows<IllegalArgumentException> { EventMapping("a", to = " ") }
        assertThrows<IllegalArgumentException> { EventMapping("a", to = "A", toRevision = 0) }
        assertThrows<IllegalArgumentException> { EventMapping("a", drop = true, fields = mapOf("x" to "y")) }
    }

    @Test
    fun `an event mapping's owner field is named, and not on a dropped event`() {
        EventMapping(
            "order_handed_over",
            to = "OrderHandedOver",
            owner = "toOwnerId"
        ).owner.assert().isEqualTo("toOwnerId")
        assertThrows<IllegalArgumentException> { EventMapping("e", to = "E", owner = " ") }
        assertThrows<IllegalArgumentException> { EventMapping("e", drop = true, owner = "toOwnerId") }
    }

    @Test
    fun `a retiring event mapping names the retired name and renames no field`() {
        val text = NineXExample.archivingMapText(""""to": "OrderArchived", "retire": true""")
        MigrationMap.read(text).aggregates.first().events.single { it.from == "order_archived" }.also {
            it.retire.assert().isTrue()
            it.to.assert().isEqualTo("OrderArchived")
        }
        assertThrows<IllegalArgumentException> { EventMapping("a", retire = true) }
        assertThrows<IllegalArgumentException> { EventMapping("a", drop = true, retire = true) }
        assertThrows<IllegalArgumentException> {
            EventMapping("a", to = "A", retire = true, fields = mapOf("x" to "y"))
        }
    }

    @Test
    fun `a reservation mapping names its PrepareKey, namespace and tenancy`() {
        val map = MigrationMap.read(
            NineXExample.MAP_TEXT.replace(
                "\"to\": \"example.order\",",
                "\"to\": \"example.order\", \"reservations\": [ { \"from\": \"order_no\", \"namespace\": \"order.no\" }, " +
                    "{ \"from\": \"order_code\", \"namespace\": \"order.code\", \"tenancy\": \"SYSTEM\", " +
                    "\"ownerId\": \"owner.id\" } ],",
            ),
        )
        map.aggregates.first().reservations.assert().containsExactly(
            ReservationMapping("order_no", "order.no"),
            ReservationMapping("order_code", "order.code", ReservationTenancy.SYSTEM, "owner.id"),
        )
        ReservationMapping("a", "n").tenancy.assert().isEqualTo(ReservationTenancy.TENANT)
        assertThrows<IllegalArgumentException> { ReservationMapping(" ", "n") }
        assertThrows<IllegalArgumentException> { ReservationMapping("a", " ") }
        assertThrows<IllegalArgumentException> { ReservationMapping("a", "n", ownerId = "owner..id") }
    }

    @Test
    fun `a command mapping names both commands`() {
        assertThrows<IllegalArgumentException> { CommandMapping(" ", "A") }
        assertThrows<IllegalArgumentException> { CommandMapping("a", " ") }
    }

    @Test
    fun `format 1 maps the 9x processors to V10 subscriptions when told`() {
        val map = MigrationMap.read(
            """{"format":1,"aggregates":[],"subscriptions":[{"from":"s.CartSaga"},
               {"from":"s.OrderSaga","to":"Orders","functions":{"onEvent":"onCreated","onPaid":"Payments.onPaid"}}]}""",
        )
        val (cart, order) = map.subscriptions
        cart.source().assert().isEqualTo(NineXProcessor("s", "CartSaga"))
        cart.subscriptionOf("onOrderCreated").assert().isEqualTo("CartSaga.onOrderCreated")
        order.subscriptionOf("onEvent").assert().isEqualTo("Orders.onCreated")
        order.subscriptionOf("onPaid").assert().isEqualTo("Payments.onPaid")
        order.subscriptionOf("onShipped").assert().isEqualTo("Orders.onShipped")
        NineXExample.map.subscriptions.assert().isEmpty()
    }

    @Test
    fun `a subscription mapping names a 9x processor and V10 names`() {
        assertThrows<IllegalArgumentException> { SubscriptionMapping("CartSaga") }
        assertThrows<IllegalArgumentException> { SubscriptionMapping("s.CartSaga", to = "a.b") }
        assertThrows<IllegalArgumentException> { SubscriptionMapping("s.CartSaga", to = " ") }
        assertThrows<IllegalArgumentException> { SubscriptionMapping("s.CartSaga", functions = mapOf("on" to "")) }
        assertThrows<IllegalArgumentException> { SubscriptionMapping("s.CartSaga", functions = mapOf(" " to "on")) }
        assertThrows<IllegalArgumentException> { SubscriptionMapping("s.CartSaga", functions = mapOf("on" to "a.b.c")) }
        assertThrows<IllegalArgumentException> { SubscriptionMapping("s.CartSaga", functions = mapOf("on" to "a.")) }
    }
}

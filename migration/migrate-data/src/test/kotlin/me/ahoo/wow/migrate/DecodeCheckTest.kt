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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

class DecodeCheckTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val models = MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel))
    private val reported = mutableListOf<DecodeFailure>()

    private fun decoding(source: NineXEventSource, plan: MigrationPlan = NineXExample.plan) =
        DecodeCheck(source, plan, models, ImportOptions(pageSize = 1, streamBatch = 2), { reported += it })

    @Test
    fun `every 9x event of the example should read with the V10 models`() = runTest {
        val decoding = decoding(InMemoryNineXEventSource(NineXExample.streams()))
        decoding.check(order).also {
            it.passed.assert().isTrue()
            it.streams.assert().isEqualTo(4)
            it.events.assert().isEqualTo(4)
            it.render().assert().isEqualTo("example-service.order decoded: 4 streams, 4 events, every one read")
        }
        decoding.check(cart).passed.assert().isTrue()
        reported.assert().isEmpty()
    }

    @Test
    fun `every event that does not read should be reported, and the walk go on`() = runTest {
        val source = InMemoryNineXEventSource(
            NineXExample.streams() +
                NineXExample.cartRemoving("cart-null", "[null]") +
                NineXExample.cartRemoving("cart-nulls", """["product-2",null]""") +
                NineXExample.cartRemoving("cart-excluded", "[null]"),
        )
        val excluding = MigrationPlan(
            NineXExample.map.copy(
                excluded = listOf(
                    Exclusion(aggregate = "example-service.cart", reason = "A 9.x defect", stream = "cart-excluded"),
                ),
            ),
            NineXExample.manifest,
        )
        val report = decoding(source, excluding).check(cart)
        report.passed.assert().isFalse()
        report.streams.assert().isEqualTo(12)
        report.failed.assert().isEqualTo(mapOf(NineXEventType("cart_item_removed", "0.0.1") to 2L))
        report.render().assert()
            .isEqualTo(
                "example-service.cart decoded: 12 streams, 12 events, 2 events do not read; cart_item_removed@0.0.1: 2"
            )
        reported.map { it.stream }.assert().containsExactly(
            "9.x stream 0VY3GwaC4OmV00C-cart-null (example-service.cart/(0)/cart-null v4)",
            "9.x stream 0VY3GwaC4OmV00C-cart-nulls (example-service.cart/(0)/cart-nulls v4)",
        )
        reported.first().render().assert().startsWith(
            "PROBLEM decode: the 9.x event cart_item_removed@0.0.1 of 9.x stream 0VY3GwaC4OmV00C-cart-null " +
                "(example-service.cart/(0)/cart-null v4), read as example.cart.CartItemRemoved revision 1: ",
        )
        // Jackson 3 refuses the null element 9.x's Jackson 2 read, saying where it is.
        reported.first().reason.assert().isEqualTo(
            "Invalid `null` value encountered for property \"productIds\" (at " +
                "me.ahoo.wow.migrate.example.CartItemRemoved[\"productIds\"]->java.util.LinkedHashSet[0])",
        )
    }

    @Test
    fun `a stream that does not convert should be reported, and the walk go on`() = runTest {
        val unmapped = NineXExample.cartRemoving("cart-unmapped", "[]").map { stream ->
            if (stream.version == 4) {
                stream.copy(events = listOf(stream.events.single().copy(name = "cart_cleared")))
            } else {
                stream
            }
        }
        val report = decoding(InMemoryNineXEventSource(NineXExample.streams() + unmapped)).check(cart)
        report.unconverted.assert().isEqualTo(1)
        report.failed.assert().isEmpty()
        report.passed.assert().isFalse()
        report.render().assert().endsWith(", 0 events do not read; 1 streams do not convert")
        reported.single().render().assert().isEqualTo(
            "PROBLEM decode: 9.x stream 0VY3GwaC4OmV00C-cart-unmapped (example-service.cart/(0)/cart-unmapped v4) " +
                "does not convert: 9.x stream 0VY3GwaC4OmV00C-cart-unmapped (example-service.cart/(0)/cart-unmapped " +
                "v4): the 9.x event cart_cleared@0.0.1 has no mapping.",
        )
    }

    @Test
    fun `a reason should be one line, its class when the failure says nothing`() {
        reasonOf(IllegalStateException("first\nsecond")).assert().isEqualTo("first")
        reasonOf(IllegalStateException()).assert().isEqualTo(IllegalStateException::class.java.name)
    }
}

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

package me.ahoo.wow.example.eshop.inventory.domain.stock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.eshop.inventory.api.stock.EshopInventoryContract
import me.ahoo.wow.example.eshop.inventory.api.stock.ReceiveStock
import me.ahoo.wow.test.scheduleSpec
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** The delivery job receives the suppliers' deliveries of the day before into stock, every morning at 06:00 UTC. */
class SupplierDeliverySagaTest {
    private val feed = mapOf(
        LocalDate.parse("2026-10-19") to listOf(Delivery("mug", 40), Delivery("cap", 12)),
        LocalDate.parse("2026-10-21") to listOf(Delivery("mug", 8)),
    )
    private val asked = mutableListOf<LocalDate>()
    private val handlers = supplierDeliverySagaHandlers(
        deliveries = SupplierDeliveries { _, day ->
            asked += day
            feed[day].orEmpty()
        },
    )
    private val stock = EshopInventoryContract.Stock.TYPE

    // region test
    @Test
    fun `the morning job should receive yesterday's deliveries`() = scheduleSpec(
        handlers,
        start = Instant.parse("2026-10-20T00:00:00Z"),
    ) {
        enable("receive-deliveries")
        advanceTime(6.hours) {
            commands.map { it.ref to it.body }.assert().containsExactly(
                stock.ref(TENANT, "mug") to ReceiveStock(40),
                stock.ref(TENANT, "cap") to ReceiveStock(12),
            )
        }
        asked.assert().containsExactly(LocalDate.parse("2026-10-19"))
    }
    // endregion

    @Test
    fun `a tick after missed mornings should receive each missed day's deliveries`() = scheduleSpec(
        handlers,
        start = Instant.parse("2026-10-20T00:00:00Z"),
    ) {
        enable("receive-deliveries")
        advanceTime(3.days) {
            expectCommands(ReceiveStock(40), ReceiveStock(12), ReceiveStock(8))
        }
        ticks.single().skipped.assert().isEqualTo(2)
        asked.assert().containsExactly(
            LocalDate.parse("2026-10-19"),
            LocalDate.parse("2026-10-20"),
            LocalDate.parse("2026-10-21"),
        )
    }

    private companion object {
        const val TENANT = Caller.DEFAULT_TENANT
    }
}

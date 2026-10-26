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

package me.ahoo.wow.runtime.reservation

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** Each type's reservations in its log group's store (decision D307). */
class ReservationRouterTest {
    private val default = InMemoryReservationStore()
    private val invoices = InMemoryReservationStore()

    @Test
    fun `each call goes to the store of the scope's type`(): Unit = runBlocking {
        val router = ReservationRouter.of(default, mapOf(INVOICE to invoices))!!
        val invoice = ReservationScope.of(INVOICE, "t", "number")
        val order = ReservationScope.of(ORDER, "t", "number")
        val i1 = INVOICE.ref("t", "i-1")
        val o1 = ORDER.ref("t", "o-1")
        router.reserve(invoice, "n-1", i1, "h", 1.minutes).assert().isNotNull()
        router.reserve(order, "n-1", o1, "h", 1.minutes).assert().isNotNull()
        invoices.find(invoice, "n-1")?.owner.assert().isEqualTo(i1)
        default.find(invoice, "n-1").assert().isNull()
        default.find(order, "n-1")?.owner.assert().isEqualTo(o1)
        router.confirm(invoice, "n-1", i1, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        router.find(invoice, "n-1")?.confirmed.assert().isEqualTo(true)
        router.abandon(order, "n-1", o1, "h").assert().isTrue()
        router.release(invoice, "n-1", i1, 1).assert().isTrue()
        router.find(invoice, "n-1").assert().isNull()
        router.toString().assert().contains("ReservationRouter")
    }

    @Test
    fun `without a group's own store the default is used as it is, and a type with none is refused`(): Unit =
        runBlocking {
            ReservationRouter.of(default, emptyMap()).assert().isSameAs(default)
            ReservationRouter.of(default, mapOf(INVOICE to default)).assert().isSameAs(default)
            ReservationRouter.of(null, emptyMap()).assert().isNull()
            val router = ReservationRouter.of(null, mapOf(INVOICE to invoices))!!
            assertThrownBy<IllegalStateException> {
                runBlocking { router.find(ReservationScope.of(ORDER, "t", "number"), "n-1") }
            }
            router.find(ReservationScope.of(INVOICE, "t", "number"), "n-1").assert().isNull()
        }

    private companion object {
        val ORDER = AggregateType("sales", "order")
        val INVOICE = AggregateType("sales", "invoice")
    }
}

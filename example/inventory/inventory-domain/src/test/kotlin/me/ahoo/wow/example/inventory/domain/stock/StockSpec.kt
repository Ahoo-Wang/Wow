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
import me.ahoo.wow.example.inventory.api.CommitReservation
import me.ahoo.wow.example.inventory.api.InventoryContext
import me.ahoo.wow.example.inventory.api.ReceiveStock
import me.ahoo.wow.example.inventory.api.ReleaseReservation
import me.ahoo.wow.example.inventory.api.ReservationCommitted
import me.ahoo.wow.example.inventory.api.ReservationReleased
import me.ahoo.wow.example.inventory.api.ReserveStock
import me.ahoo.wow.example.inventory.api.StockReceived
import me.ahoo.wow.example.inventory.api.StockReserved
import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.kernel.test.AggregateSpec
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test

/** A product's stock on the kernel, through the generated model. */
class StockSpec {
    private val model = stockModel(InventoryContext.NAME)

    private fun stockSpec(block: AggregateSpec<StockState>.() -> Unit) = aggregateSpec(
        model,
        id = PRODUCT,
        block = block
    )

    @Test
    fun `receiving stock should create it and make the units available`() = stockSpec {
        whenCommand(ReceiveStock(10)) {
            expectEvents(StockReceived(10))
            expectState {
                onHand.assert().isEqualTo(10)
                available.assert().isEqualTo(10)
                reservations.assert().isEmpty()
            }
        }
    }

    @Test
    fun `receiving more should add to the stock`() = stockSpec {
        given(StockReceived(10))
        whenCommand(ReceiveStock(5)) {
            expectEvents(StockReceived(5))
            expectState { onHand.assert().isEqualTo(15) }
        }
    }

    @Test
    fun `a quantity that is not positive should be rejected`() = stockSpec {
        given(StockReceived(10))
        whenCommand(ReceiveStock(0)) { expectRejected(StockRejections.INVALID_QUANTITY) }
        whenCommand(ReserveStock(ORDER, -1)) { expectRejected(StockRejections.INVALID_QUANTITY) }
    }

    @Test
    fun `reserving should hold the units for the order`() = stockSpec {
        given(StockReceived(10))
        whenCommand(ReserveStock(ORDER, 3)) {
            expectEvents(StockReserved(ORDER, 3))
            expectState {
                onHand.assert().isEqualTo(10)
                available.assert().isEqualTo(7)
                reservations.assert().isEqualTo(mapOf(ORDER to 3))
            }
        }
    }

    @Test
    fun `reserving more than is available should be rejected`() = stockSpec {
        given(StockReceived(10), StockReserved("order-0", 8))
        whenCommand(ReserveStock(ORDER, 3)) { expectRejected(StockRejections.INSUFFICIENT) }
    }

    @Test
    fun `reserving again for an order already reserved should change nothing`() = stockSpec {
        given(StockReceived(10), StockReserved(ORDER, 3))
        whenCommand(ReserveStock(ORDER, 3)) {
            expectUnchanged()
            expectState { available.assert().isEqualTo(7) }
        }
    }

    @Test
    fun `reserving a product never received should be rejected as not found`() = stockSpec {
        whenCommand(ReserveStock(ORDER, 1)) { expectRejected(KernelRejections.NOT_FOUND) }
    }

    @Test
    fun `committing should take the reserved units out of the stock`() = stockSpec {
        given(StockReceived(10), StockReserved(ORDER, 3))
        whenCommand(CommitReservation(ORDER)) {
            expectEvents(ReservationCommitted(ORDER, 3))
            expectState {
                onHand.assert().isEqualTo(7)
                available.assert().isEqualTo(7)
                reservations.assert().isEmpty()
            }
        }
    }

    @Test
    fun `committing without a reservation should be rejected`() = stockSpec {
        given(StockReceived(10))
        whenCommand(CommitReservation(ORDER)) { expectRejected(StockRejections.NO_RESERVATION) }
    }

    @Test
    fun `releasing should make the reserved units available again`() = stockSpec {
        given(StockReceived(10), StockReserved(ORDER, 3))
        whenCommand(ReleaseReservation(ORDER)) {
            expectEvents(ReservationReleased(ORDER, 3))
            expectState {
                onHand.assert().isEqualTo(10)
                available.assert().isEqualTo(10)
                reservations.assert().isEmpty()
            }
        }
    }

    @Test
    fun `releasing what the order does not hold should change nothing`() = stockSpec {
        given(StockReceived(10))
        whenCommand(ReleaseReservation(ORDER)) { expectUnchanged() }
    }

    private companion object {
        const val PRODUCT = "apple"
        const val ORDER = "order-1"
    }
}

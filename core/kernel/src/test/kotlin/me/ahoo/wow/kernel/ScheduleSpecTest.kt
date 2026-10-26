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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.test.DEFAULT_START
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The test DSL's time (decision D166): scheduled commands are sent when the specification's time reaches them. */
class ScheduleSpecTest {
    data class PlaceOrder(val total: Int)
    data class PayOrder(val amount: Int)
    data class CancelOrder(val reason: String)
    data class OrderPlaced(val total: Int)
    data class OrderPaid(val amount: Int)
    data class OrderCancelled(val reason: String)

    class Order(val id: String) {
        var status = "NEW"
    }

    private val model = aggregate<Order>("shop", "order") {
        initial { Order(it.id) }
        decide<PlaceOrder>(Creation.REQUIRED) { _, body ->
            schedule("pay-timeout", ScheduleRule.after(30.minutes), CancelOrder("unpaid"))
            Decision.events(OrderPlaced(body.total))
        }
        decide<PayOrder> { state, body ->
            if (state.status != "PLACED") {
                Decision.reject("order.not-placed", state.status)
            } else {
                cancelSchedule("pay-timeout")
                Decision.events(OrderPaid(body.amount))
            }
        }
        decide<CancelOrder> { state, body ->
            if (state.status != "PLACED") {
                Decision.reject("order.not-placed", state.status)
            } else {
                Decision.events(OrderCancelled(body.reason))
            }
        }
        evolve<OrderPlaced> { status = "PLACED" }
        evolve<OrderPaid> { status = "PAID" }
        evolve<OrderCancelled> { status = "CANCELLED" }
    }

    @Test
    fun `an unpaid order cancels itself when its schedule is due`() {
        aggregateSpec(model) {
            whenCommand(PlaceOrder(10)) {
                expectEvents(OrderPlaced(10))
                expectScheduled("pay-timeout", CancelOrder("unpaid"), ScheduleRule.after(30.minutes))
                expectScheduled("pay-timeout", CancelOrder("unpaid"))
            }
            advanceTime(29.minutes)
            timer("pay-timeout")?.status.assert().isEqualTo(TimerStatus.PENDING)
            advanceTime(1.minutes) {
                expectEvents(OrderCancelled("unpaid"))
                expectState { status.assert().isEqualTo("CANCELLED") }
            }
            timer("pay-timeout")?.status.assert().isEqualTo(TimerStatus.FIRED)
            now.assert().isEqualTo(DEFAULT_START.plusSeconds(1800))
        }
    }

    @Test
    fun `paying cancels the schedule, so nothing is due`() {
        aggregateSpec(model, start = Instant.parse("2026-05-01T08:00:00Z")) {
            whenCommand(PlaceOrder(10)) { expectEvents(OrderPlaced(10)) }
            whenCommand(PayOrder(10)) {
                expectScheduleCancelled("pay-timeout")
                assertThrownBy<AssertionError> { expectScheduleCancelled("other") }
            }
            timer("pay-timeout")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
            assertThrownBy<AssertionError> { advanceTime(1.minutes.times(60)) { expectEvents() } }
        }
    }

    @Test
    fun `a schedule fired after the state moved on is a normal rejection`() {
        aggregateSpec(model) {
            whenCommand(PlaceOrder(10)) {}
            whenCommand(CancelOrder("customer")) { expectEvents(OrderCancelled("customer")) }
            advanceTime(30.minutes) { expectRejected("order.not-placed") }
        }
    }

    @Test
    fun `deleting the aggregate cancels its timers`() {
        aggregateSpec(model) {
            whenCommand(PlaceOrder(10)) {}
            whenCommand(DeleteAggregate) {}
            timer("pay-timeout")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
            advanceTime(1.seconds)
        }
    }

    @Test
    fun `a schedule that does not match fails the expectation`() {
        aggregateSpec(model) {
            whenCommand(PlaceOrder(10)) {
                assertThrownBy<AssertionError> { expectScheduled("other", CancelOrder("unpaid")) }
                assertThrownBy<AssertionError> { expectScheduled("pay-timeout", CancelOrder("other")) }
                assertThrownBy<AssertionError> {
                    expectScheduled("pay-timeout", CancelOrder("unpaid"), ScheduleRule.after(1.minutes))
                }
                assertThrownBy<AssertionError> {
                    expectScheduled("pay-timeout", CancelOrder("unpaid"), target = model.type.ref("tenant", "other"))
                }
            }
        }
    }
}

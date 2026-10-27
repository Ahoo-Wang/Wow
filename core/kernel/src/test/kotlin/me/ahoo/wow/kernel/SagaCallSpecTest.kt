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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.test.committedResult
import me.ahoo.wow.test.failedResult
import me.ahoo.wow.test.rejectedResult
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** The test DSL's answers to a saga's synchronous calls (decision D193): `answerCalls` and the call expectations. */
class SagaCallSpecTest {
    data class Placed(val total: Int)
    data class Submit(val total: Int)
    data class Confirm(val orderId: String)

    class Basket

    private val basket = AggregateType("basket", "basket").ref("t", "alice")
    private val order = AggregateType("ordering", "order")

    /** Submits the order synchronously, then confirms the order number the ordering gave it on the basket. */
    private val handlers = listOf(
        ProcessorBinding.saga(
            "CheckoutSaga",
            "onPlaced",
            Basket::class.java,
            calls = listOf(Submit::class.java),
        ) { event, context ->
            val placed = event.events.filterIsInstance<Placed>().firstOrNull() ?: return@saga emptyList()
            val submitted = context.call(CommandRequest.to(order.unassigned(event.ref.tenantId), Submit(placed.total)))
            listOf(CommandRequest.to(event.ref, Confirm(submitted.ref.id)))
        },
    )

    private fun event(vararg events: Any) = stateEvent(basket, Basket(), *events, version = 2)

    @Test
    fun `a call should be answered committed by default and listed with the commands`() = sagaSpec(handlers) {
        whenEvent(event(Placed(3))) {
            expectCalls(Submit(3))
            calls.single().ref.isAssigned.assert().isFalse()
            // The default answer gives a creation without an id the aggregate's first id.
            expectCommand(Confirm("order-1"), to = basket)
            expectDeterministic()
        }
        whenEvent(event("other")) {
            expectNoCall()
            expectNoCommand()
        }
    }

    @Test
    fun `an answer should be what the target would answer`() = sagaSpec(handlers) {
        answerCalls { committedResult(it, version = 4, aggregateId = "SO-1", duplicate = true) }
        whenEvent(event(Placed(3))) { expectCommand(Confirm("SO-1")) }
        answerCalls { rejectedResult(it, "ordering.order.closed") }
        whenEvent(event(Placed(3))) {
            expectError<CommandRejectedException> { code.assert().isEqualTo("ordering.order.closed") }
        }
        answerCalls { failedResult(it, "wow.storage.unavailable") }
        whenEvent(event(Placed(3))) {
            expectError<WowProblemException> { result!!.error!!.code.assert().isEqualTo("wow.storage.unavailable") }
        }
    }

    @Test
    fun `a call expectation should fail when the calls differ`() = sagaSpec(handlers) {
        whenEvent(event(Placed(3))) {
            assertThrownBy<AssertionError> { expectCalls(Submit(4)) }
            assertThrownBy<AssertionError> { expectNoCall() }
        }
    }

    @Test
    fun `a saga that calls differently when handled again should not be deterministic`() {
        val counter = AtomicInteger()
        val changing = listOf(
            ProcessorBinding.saga(
                "CheckoutSaga",
                "onPlaced",
                Basket::class.java,
                calls = listOf(Submit::class.java),
            ) { event, context ->
                context.call(
                    CommandRequest.to(order.ref(event.ref.tenantId, "o-${counter.incrementAndGet()}"), Submit(1))
                )
                emptyList()
            },
        )
        sagaSpec(changing) {
            whenEvent(event(Placed(3))) {
                assertThrownBy<AssertionError> { expectDeterministic() }
            }
        }
    }

    @Test
    fun `a result of a call should name the command it answers`() {
        val command = Command(
            "c",
            "r",
            order.ref("t", "o-1"),
            Submit(1),
            causation = Causation("root", "x"),
        )
        committedResult(command).assert().satisfies({
            it.ref.id.assert().isEqualTo("o-1")
            it.correlationId.assert().isEqualTo("root")
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        })
        rejectedResult(Command("c", "r", order.ref("t", "o-1"), Submit(1)), "x").correlationId.assert().isEqualTo("c")
    }
}

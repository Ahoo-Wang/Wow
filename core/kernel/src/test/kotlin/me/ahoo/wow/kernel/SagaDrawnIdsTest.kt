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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.spi.id.IdGenerators
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes

/**
 * Decision D482 ⑦: a saga that names the target of a command it sends by an id it drew from `SagaContext.ids` in the
 * same handling sends another request to another aggregate each time the commit is handled again, so the handling
 * fails unrecoverable, naming the two ways that are safe. An id drawn into a command's body is not refused.
 */
class SagaDrawnIdsTest {
    private val order = AggregateType("shop", "order")
    private val shipment = AggregateType("shop", "shipment")

    data class CreateShipment(val orderId: String, val shipmentId: String? = null)
    data class Dispatch(val orderId: String)

    private val execution = ExecutionId(order.context, "OrderSaga.onOrderPaid", order.ref("acme", "o-1"), 3)
    private val event = StateEvent(
        order.ref("acme", "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Pay",
        1L,
        listOf("paid"),
        "state",
        scope = Scope("alice"),
        causation = Causation("root", "shop.order/acme/o-0@1"),
        operator = Operator("alice", "acme"),
    )

    private val called = mutableListOf<String>()

    private fun saga() = ExecutionSagaContext(
        execution,
        event,
        IdGenerators.DEFAULT,
        CommandTypes.SIMPLE_NAMES,
        calls = CommandCalls {
            called += it.id
            error("not reached")
        },
    )

    private fun receipt() = ExecutionReceiptContext(
        execution,
        "c-9",
        Causation("root", "c-9"),
        Operator("alice", "acme"),
        Scope("alice"),
    )

    @Test
    fun `a command whose target is named by a drawn aggregate id should fail the handling unrecoverable, saying what is safe`() {
        val context = saga()
        val drawn = context.ids.next(shipment)

        val refused = assertThrows<Misuse.Argument> {
            context.derive(CommandRequest.to(shipment.ref("acme", drawn), CreateShipment("o-1")))
        }

        refused.message.assert().contains(
            "OrderSaga.onOrderPaid",
            CreateShipment::class.java.name,
            "SagaContext.ids",
            "@OnCommitted",
            "an id computed from what is handled",
        )
    }

    @Test
    fun `a command whose target is named by a drawn global id should fail the handling as well`() {
        val context = saga()
        val drawn = context.ids.next()

        assertThrows<Misuse.Argument> { context.derive(CommandRequest.of(Dispatch("o-1"), drawn, shipment)) }
    }

    @Test
    fun `a drawn id in a command's body should not be refused`() {
        val context = saga()
        val drawn = context.ids.next(shipment)

        val command = context.derive(CommandRequest.of(CreateShipment("o-1", drawn), type = shipment))

        command.ref.assert().isEqualTo(shipment.unassigned("acme"))
        (command.body as CreateShipment).shipmentId.assert().isEqualTo(drawn)
    }

    @Test
    fun `a target named by an id computed from the commit should be derived, whatever the handling drew`() {
        val context = saga()
        context.ids.next(shipment)

        val command = context.derive(CommandRequest.to(shipment.ref("acme", "o-1"), Dispatch("o-1")))

        command.ref.assert().isEqualTo(shipment.ref("acme", "o-1"))
    }

    @Test
    fun `a schedule on a target named by a drawn id should fail the handling`() {
        val context = saga()
        val drawn = context.ids.next(shipment)

        assertThrows<Misuse.Argument> {
            context.schedule(
                "remind",
                ScheduleRule.after(5.minutes),
                CommandRequest.to(shipment.ref("acme", drawn), Dispatch("o-1")),
            )
        }
    }

    @Test
    fun `a cancelled schedule of a target named by a drawn id should fail the handling`() {
        val context = saga()
        val drawn = context.ids.next(shipment)

        assertThrows<Misuse.Argument> { context.cancelSchedule("remind", shipment.ref("acme", drawn)) }
    }

    @Test
    fun `a call to a target named by a drawn id should fail before it is sent`(): Unit = runBlocking {
        val context = saga()
        val drawn = context.ids.next(shipment)

        assertThrows<Misuse.Argument> { context.call(CommandRequest.to(shipment.ref("acme", drawn), Dispatch("o-1"))) }
        called.assert().isEmpty()
    }

    @Test
    fun `a receipt's handler should be refused a drawn target as a commit's is`() {
        val context = receipt()
        val drawn = context.ids.next(shipment)

        assertThrows<Misuse.Argument> {
            context.derive(CommandRequest.to(shipment.ref("acme", drawn), Dispatch("o-1")))
        }
    }

    @Test
    fun `the ids of one handling should not refuse the targets of another`() {
        val drawn = saga().ids.next(shipment)

        saga().derive(CommandRequest.to(shipment.ref("acme", drawn), Dispatch("o-1"))).ref.id.assert().isEqualTo(drawn)
    }
}

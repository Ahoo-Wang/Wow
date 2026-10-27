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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.test.scheduleSpec
import org.junit.jupiter.api.Test

/** Decision D234 ⑤: a saga's handler leaves a command's ref out, and the contracts name its aggregate type. */
class OmittedRefsTest {
    private val order = AggregateType("shop", "order")
    private val stock = AggregateType("inventory", "stock")
    private val ledger = AggregateType("billing", "ledger")

    data class Reserve(val orderId: String)
    data class Shared(val note: String)
    data class Unknown(val note: String)

    /** Reserve goes to the stock, Shared to two aggregate types, any other class to none. */
    private val types = object : CommandTypes {
        override fun nameOf(target: AggregateType, command: Class<*>): String? = null

        override fun aggregatesOf(command: Class<*>): List<AggregateType> = when (command) {
            Reserve::class.java -> listOf(stock, stock)
            Shared::class.java -> listOf(stock, ledger)
            else -> emptyList()
        }
    }

    private val execution = ExecutionId("OrderSaga.onOrderPlaced", order.ref("acme", "o-1"), 3)
    private val event = StateEvent(
        order.ref("acme", "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Place",
        1L,
        listOf("placed"),
        "state",
        scope = Scope("alice", "space-a"),
        causation = Causation("root", "shop.order/acme/o-0@1"),
        operator = Operator("alice", "acme"),
    )

    private fun saga(commandTypes: CommandTypes = types) =
        ExecutionSagaContext(execution, event, IdGenerators.DEFAULT, commandTypes)

    @Test
    fun `an execution's command without its type should go to the aggregate the contracts assign, in the commit's tenant`() {
        val context = saga()
        val reserve = context.command("product-1", Reserve("o-1"))
        reserve.ref.assert().isEqualTo(stock.ref("acme", "product-1"))
        reserve.id.assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, stock.ref("acme", "product-1"), Reserve::class.java),
        )
        reserve.scope.assert().isEqualTo(Scope("alice", "space-a"))
        reserve.operator.assert().isEqualTo(Operator("alice", "acme"))
        // A creation without an id leaves the id to the target (decision D191), as command(type, body) does.
        val created = context.command(Reserve("o-2"))
        created.ref.assert().isEqualTo(stock.unassigned("acme"))
        created.id.assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, stock.unassigned("acme"), Reserve::class.java)
        )
        // It is the same request as the full form's.
        saga().command(stock.ref("acme", "product-1"), Reserve("o-1")).assert().isEqualTo(reserve)
    }

    @Test
    fun `leaving the ref out should fail when no contract or several aggregate types decide the class`() {
        val context = saga()
        assertThrownBy<IllegalArgumentException> { context.command("x", Unknown("n")) }
            .hasMessageContaining("OrderSaga.onOrderPlaced sends ${Unknown::class.java.name} without its target")
            .hasMessageContaining("no contract assigns it an aggregate")
            .hasMessageContaining("Add the api module of the bounded context")
        assertThrownBy<IllegalArgumentException> { context.command(Shared("n")) }
            .hasMessageContaining("several aggregate types decide it: inventory.stock, billing.ledger")
        assertThrownBy<IllegalArgumentException> { context.command("o-1", DeleteAggregate) }
            .hasMessageContaining("the lifecycle command")
            .hasMessageContaining("every aggregate type takes it")
        // Outside a node, a context built without the contracts knows none.
        assertThrownBy<IllegalArgumentException> { ExecutionSagaContext(execution, event).command("x", Reserve("o")) }
            .hasMessageContaining("no contract assigns it an aggregate")
    }

    @Test
    fun `a receipt's handler should leave the ref out in the tenant of the commit that derived the command`() {
        val rejected = CommandRejected<Reserve>(
            "c-1",
            "inventory.stock.Reserve",
            stock.ref("acme", "product-1"),
            ErrorInfo("inventory.stock.insufficient", "No."),
            Causation("root", "shop.order/acme/o-1@3"),
            execution,
            Operator("alice", "acme"),
            Scope("alice", "space-a"),
        )
        val onRejected = RejectionBinding.of("OrderSaga", "onReserveRejected", Reserve::class.java) { _, context ->
            listOf(context.command("product-2", Reserve("o-1")), context.command(Reserve("o-1")))
        }
        val compensations = runBlocking {
            onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected, IdGenerators.DEFAULT, types))
        }
        compensations.map { it.ref }.assert().containsExactly(stock.ref("acme", "product-2"), stock.unassigned("acme"))
        compensations.first().causation.assert().isEqualTo(Causation("root", "c-1"))
        assertThrownBy<IllegalArgumentException> {
            runBlocking { onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)) }
        }.hasMessageContaining("OrderSaga.onReserveRejected sends")

        val committed = CommandCommitted<Reserve>(
            commandId = "c-1",
            commandType = "inventory.stock.Reserve",
            ref = stock.ref("acme", "product-1"),
            version = 2,
            result = CommandResult(
                commandId = "c-1",
                requestId = "c-1",
                correlationId = "root",
                ref = stock.ref("acme", "product-1"),
                hop = Hop.committed("inventory"),
                function = FunctionInfo(
                    FunctionKind.COMMAND,
                    "inventory",
                    "stock",
                    "Reserve",
                ),
                status = CommandResult.Status.SUCCEEDED,
                version = 2,
                signalTime = 0L,
            ),
            causation = Causation("root", "shop.order/acme/o-1@3"),
            executionId = execution,
            operator = Operator("alice", "acme"),
            scope = Scope("alice", "space-a"),
        )
        val onCommitted = CommittedBinding.of("OrderSaga", "onReserved", Reserve::class.java) { _, context ->
            listOf(context.command("product-3", Reserve("o-1")))
        }
        val confirmations = runBlocking {
            onCommitted.invoke(committed, committedContextOf(onCommitted, committed, IdGenerators.DEFAULT, types))
        }
        confirmations.single().ref.assert().isEqualTo(stock.ref("acme", "product-3"))
        assertThrownBy<IllegalArgumentException> {
            runBlocking { onCommitted.invoke(committed, committedContextOf(onCommitted, committed)) }
        }.hasMessageContaining("OrderSaga.onReserved sends")
    }

    @Test
    fun `an OnSchedule job should write each command's full ref`() {
        val job = ScheduleJob("nightly", ScheduleRule.Cron("0 0 3 * * *"), JobScope.TENANT)
        val omitted = ProcessorBinding.schedule("StockSaga", "onNightly", job) { tick, context ->
            listOf(context.command(tick.tenantId, Reserve("o-1")))
        }
        val created = ProcessorBinding.schedule("StockSaga", "onNightly", job) { _, context ->
            listOf(context.command(Reserve("o-1")))
        }
        val full = ProcessorBinding.schedule("StockSaga", "onNightly", job) { tick, context ->
            listOf(context.command(stock.ref(tick.tenantId, "all"), Reserve("o-1")))
        }
        val tick = ScheduleTick("acme", "nightly", slot = 1)
        listOf(omitted, created).forEach { handlers ->
            scheduleSpec(listOf(handlers)) {
                whenTick(tick) {
                    expectError<IllegalArgumentException> {
                        message.assert().contains("StockSaga.onNightly is an @OnSchedule job")
                        message.assert().contains("writes each command's full ref")
                    }
                }
            }
        }
        scheduleSpec(listOf(full)) {
            whenTick(tick) { expectCommand(Reserve("o-1"), to = stock.ref("acme", "all")) }
        }
    }
}

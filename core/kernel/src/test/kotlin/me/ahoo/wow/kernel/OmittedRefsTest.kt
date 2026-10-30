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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

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
        val reserve = context.derive(CommandRequest.of(Reserve("o-1"), "product-1"))
        reserve.ref.assert().isEqualTo(stock.ref("acme", "product-1"))
        reserve.id.assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, stock.ref("acme", "product-1"), Reserve::class.java),
        )
        reserve.scope.assert().isEqualTo(Scope("alice", "space-a"))
        reserve.operator.assert().isEqualTo(Operator("alice", "acme"))
        // A creation without an id leaves the id to the target (decision D191).
        val created = context.derive(CommandRequest.of(Reserve("o-2")))
        created.ref.assert().isEqualTo(stock.unassigned("acme"))
        created.id.assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, stock.unassigned("acme"), Reserve::class.java)
        )
        // It is the same request as the full form's.
        saga().derive(CommandRequest.to(stock.ref("acme", "product-1"), Reserve("o-1"))).requestId.assert()
            .isEqualTo(reserve.requestId)
    }

    @Test
    fun `leaving the ref out should fail when no contract or several aggregate types decide the class`() {
        val context = saga()
        assertThrownBy<IllegalArgumentException> { context.derive(CommandRequest.of(Unknown("n"), "x")) }
            .hasMessageContaining("OrderSaga.onOrderPlaced sends ${Unknown::class.java.name} without its target")
            .hasMessageContaining("no contract assigns it an aggregate")
            .hasMessageContaining("Add the api module of the bounded context")
        assertThrownBy<IllegalArgumentException> { context.derive(CommandRequest.of(Shared("n"))) }
            .hasMessageContaining("several aggregate types decide it: inventory.stock, billing.ledger")
        assertThrownBy<IllegalArgumentException> { context.derive(CommandRequest.of(DeleteAggregate, "o-1")) }
            .hasMessageContaining("the lifecycle command")
            .hasMessageContaining("every aggregate type takes it")
        // Outside a node, a context built without the contracts knows none.
        assertThrownBy<IllegalArgumentException> {
            ExecutionSagaContext(execution, event).derive(CommandRequest.of(Reserve("o"), "x"))
        }
            .hasMessageContaining("no contract assigns it an aggregate")
    }

    @Test
    fun `a rejection's handler should leave the ref out in the tenant of the commit that derived the command`() {
        val rejected = CommandReceipt.Rejected<Reserve>(
            "c-1",
            "inventory.stock.Reserve",
            stock.ref("acme", "product-1"),
            ErrorInfo("inventory.stock.insufficient", "No."),
            Causation("root", "shop.order/acme/o-1@3"),
            execution,
            Operator("alice", "acme"),
            Scope("alice", "space-a"),
        )
        val onRejected = RejectionBinding.of(
            "OrderSaga",
            "onReserveRejected",
            Reserve::class.java,
            derives = listOf(Reserve::class.java),
        ) { _, _ ->
            listOf(CommandRequest.of(Reserve("o-1"), "product-2"), CommandRequest.of(Reserve("o-1")))
        }
        val compensations = runBlocking {
            onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected, IdGenerators.DEFAULT, types))
        }
        compensations.map { it.ref }.assert().containsExactly(stock.ref("acme", "product-2"), stock.unassigned("acme"))
        compensations.first().causation.assert().isEqualTo(Causation("root", "c-1"))
        assertThrownBy<IllegalArgumentException> {
            runBlocking { onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)) }
        }.hasMessageContaining("OrderSaga.onReserveRejected sends")
    }

    @Test
    fun `a commit's handler should leave the ref out in the tenant of the commit that derived the command`() {
        val committed = CommandReceipt.Committed<Reserve>(
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
        val onCommitted = CommittedBinding.of(
            "OrderSaga",
            "onReserved",
            Reserve::class.java,
            derives = listOf(Reserve::class.java),
        ) { _, _ ->
            listOf(CommandRequest.of(Reserve("o-1"), "product-3"))
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
    fun `an OnSchedule job's command of an id or a body should go to its class's aggregate, in the instance's tenant`() {
        val instance = AggregateType("inventory", "job").ref("acme", "nightly")
        val ticked = StateEvent(instance, 2, "c-2", "r-2", "wow.schedule.Tick", 1L, listOf("ticked"), "state")
        // A job's handler gets the saga context of the job instance's commit, as an @OnEvent handler does.
        val saga =
            ExecutionSagaContext(ExecutionId("StockSaga.onNightly", instance, 2), ticked, IdGenerators.DEFAULT, types)
        saga.derive(CommandRequest.of(Reserve("o-1"), "mug")).ref.assert().isEqualTo(stock.ref("acme", "mug"))
        saga.derive(CommandRequest.of(Reserve("o-2"))).ref.assert().isEqualTo(stock.unassigned("acme"))
        saga.derive(CommandRequest.to(stock.ref("acme", "all"), Reserve("o-3"))).ref.assert()
            .isEqualTo(stock.ref("acme", "all"))
        assertThrownBy<UnsupportedOperationException> { saga.queries }
    }

    @Test
    fun `a derived request's fields should shape its command, and a wait or a timeout should be refused`() {
        val context = saga()
        val other = context.derive(
            CommandRequest.builder(Reserve("o-1")).id("product-1").targetTenant("other")
                .targetScope(Scope("bob")).expectedVersion(4).build(),
        )
        other.ref.assert().isEqualTo(stock.ref("other", "product-1"))
        other.scope.assert().isEqualTo(Scope("bob"))
        other.expectedVersion.assert().isEqualTo(4)
        // A business key names the request, whatever else the execution derives.
        val keyed = context.derive(CommandRequest.builder(Reserve("o-1")).id("product-1").requestId("k-1").build())
        keyed.id.assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, stock.ref("acme", "product-1"), Reserve::class.java, "k-1"),
        )
        saga().derive(CommandRequest.builder(Reserve("o-9")).id("product-1").requestId("k-1").build()).id.assert()
            .isEqualTo(keyed.id)
        // A defect in the saga, which no retry changes: the aggregate parks at once.
        val waiting = assertThrownBy<InvalidDerivedRequestException> {
            context.derive(CommandRequest.builder(Reserve("o-1")).id("p").wait(WaitPlan.COMMITTED).build())
        }.hasMessageContaining(
            "OrderSaga.onOrderPlaced derives ${Reserve::class.java.name} with a wait (CommandRequest.wait)"
        ).actual()
        FailureClassification.DEFAULT.classify(waiting, HandlerRetry(maxAttempts = 5))
            .assert().isEqualTo(FailureClass.UNRECOVERABLE)
        assertThrownBy<InvalidDerivedRequestException> {
            context.derive(CommandRequest.builder(Reserve("o-1")).id("p").timeout(1.seconds).build())
        }.hasMessageContaining("with a timeout (CommandRequest.timeout)")
    }
}

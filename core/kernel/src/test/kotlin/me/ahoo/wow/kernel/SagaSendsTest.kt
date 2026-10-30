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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.kernel.schedule.JobState
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.test.committedResult
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours

/**
 * D372 S11 and S12: every handler of a saga, of an event, a receipt or a job, gets one `SagaContext`, and sends only
 * what its `@Sends` declares; an undeclared send fails the handling unrecoverable, before the command goes anywhere.
 */
class SagaSendsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")
    private val stock = AggregateType("stock", "item")

    sealed interface CartCommand
    data class Add(val item: String) : CartCommand
    data class Remove(val item: String) : CartCommand
    data class Reserve(val quantity: Int)
    class OrderState

    private val placed = stateEvent(order.ref("t", "o-1"), OrderState(), "placed", version = 2)
    private val called = CopyOnWriteArrayList<Command<*>>()
    private val calls = CommandCalls { command ->
        called += command
        committedResult(command)
    }

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        return failure as? T ?: throw AssertionError("Expected a ${T::class.simpleName} but got $failure.", failure)
    }

    private fun contextOf(handler: ProcessorBinding<*>) =
        contextOf(FunctionKind.SAGA, handler.name, placed, calls = calls, sends = handler.sends)

    private suspend fun SagaContext.sendEach(event: Any) {
        schedule("remind", ScheduleRule.after(1.hours), CommandRequest.to(cart.ref("t", "alice"), Remove("mug")))
        call(CommandRequest.to(stock.ref("t", "mug"), Reserve(1)))
        cancel("stale", cart.ref("t", "alice"))
        check(event.toString().isNotEmpty())
    }

    @Test
    fun `a handler should send what it declares, a declared class standing for its subclasses`(): Unit = runBlocking {
        val saga = ProcessorBinding.saga(
            "CartSaga",
            "onPlaced",
            OrderState::class.java,
            derives = listOf(CartCommand::class.java),
            schedules = listOf(Remove::class.java),
            calls = listOf(Reserve::class.java),
        ) { event, context ->
            context.sendEach(event)
            listOf(CommandRequest.to(cart.ref("t", "alice"), Add("mug")))
        }
        saga.sends.derives.assert().containsExactly(CartCommand::class.java)
        val commands = saga.invoke(placed, contextOf(saga))
        commands.map { it.body::class }
            .assert().containsExactly(Add::class, SetSchedule::class, CancelSchedule::class)
        called.single().body.assert().isEqualTo(Reserve(1))
    }

    @Test
    fun `an undeclared derive, schedule or call should fail unrecoverable and send nothing`(): Unit = runBlocking {
        fun saga(
            derives: List<Class<*>> = emptyList(),
            schedules: List<Class<*>> = emptyList(),
            calls: List<Class<*>> = emptyList(),
        ) = ProcessorBinding.saga(
            "CartSaga",
            "onPlaced",
            OrderState::class.java,
            derives = derives,
            schedules = schedules,
            calls = calls,
        ) { event, context ->
            context.sendEach(event)
            listOf(CommandRequest.to(cart.ref("t", "alice"), Add("mug")))
        }

        val undeclared = listOf(
            saga(schedules = listOf(Remove::class.java), calls = listOf(Reserve::class.java)) to "derives",
            saga(derives = listOf(Add::class.java), calls = listOf(Reserve::class.java)) to "schedules",
            saga(derives = listOf(Add::class.java), schedules = listOf(Remove::class.java)) to "calls",
        )
        undeclared.forEach { (handler, member) ->
            called.clear()
            val thrown = thrown<UndeclaredSendException> {
                runBlocking { handler.invoke(placed, contextOf(handler)) }
            }
            thrown.subscription.assert().isEqualTo("CartSaga.onPlaced")
            thrown.member.assert().isEqualTo(member)
            thrown.message.assert().contains("which its @Sends($member) does not declare: add")
            // No retry sends it: the aggregate parks at once.
            FailureClassification.DEFAULT.classify(thrown, HandlerRetry(maxAttempts = 5))
                .assert().isEqualTo(FailureClass.UNRECOVERABLE)
        }
        // The undeclared call never reached its target.
        called.assert().isEmpty()
    }

    @Test
    fun `a receipt's handler and a job's should get the saga's context, sending what they declare`(): Unit =
        runBlocking {
            val rejected = CommandReceipt.Rejected<Add>(
                "c-1",
                "shop.cart.Add",
                cart.ref("t", "alice"),
                ErrorInfo("cart.full", "Full."),
                Causation("root", "shop.order/t/o-1@2"),
                ExecutionId("CartSaga.onPlaced", order.ref("t", "o-1"), 2),
            )
            val onRejected = RejectionBinding.of(
                "CartSaga",
                "onAddRejected",
                Add::class.java,
                schedules = listOf(Remove::class.java),
                calls = listOf(Reserve::class.java),
            ) { receipt, context ->
                context.sendEach(receipt)
                emptyList()
            }
            val context =
                rejectionContextOf(onRejected, rejected, IdGenerators.DEFAULT, CommandTypes.SIMPLE_NAMES, calls = calls)
            onRejected.invoke(rejected, context).map { it.body::class }
                .assert().containsExactly(SetSchedule::class, CancelSchedule::class)
            called.single().body.assert().isEqualTo(Reserve(1))
            // What it does not declare, it does not send.
            val silent = RejectionBinding.of("CartSaga", "onAddRejected", Add::class.java) { receipt, context ->
                context.sendEach(receipt)
                emptyList()
            }
            thrown<UndeclaredSendException> {
                runBlocking { silent.invoke(rejected, rejectionContextOf(silent, rejected)) }
            }.member.assert().isEqualTo("schedules")

            val nightly = ScheduleJob("nightly", ScheduleRule.Cron("0 0 3 * * *"), JobScope.SYSTEM)
            val job = ProcessorBinding.schedule(
                "CartSaga",
                "onNightly",
                nightly,
                schedules = listOf(Remove::class.java),
                calls = listOf(Reserve::class.java),
            ) { tick, context ->
                context.sendEach(tick)
                emptyList()
            }
            val tick = stateEvent(
                ScheduleJobModel.refOf("shop", "nightly"),
                JobState().apply { this.job = "nightly" },
                Ticked("t", 1, 0, 0),
            )
            job.invoke(tick, contextOf(FunctionKind.SAGA, job.name, tick, calls = calls, sends = job.sends))
                .map { it.body::class }.assert().containsExactly(SetSchedule::class, CancelSchedule::class)
            called.assert().hasSize(2)
        }
}

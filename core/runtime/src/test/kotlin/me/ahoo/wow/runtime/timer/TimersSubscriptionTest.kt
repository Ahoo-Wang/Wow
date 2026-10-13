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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.kernel.SagaSchedule
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.ScheduleChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.Test

/** The `timers` subscription and the saga timers write the timer store from the facts (decision D166). */
class TimersSubscriptionTest {
    private val store = InMemoryTimerStore()
    private val timers = TimersSubscription(SHOP, store, PARTITIONS)
    private val owner = Timers.ownerOf(order("o-1"))

    private fun stream(version: Int, vararg schedules: ScheduleChange, events: List<String> = emptyList()) =
        EventStreamRecord(
            ref = order("o-1"),
            version = version,
            commandId = "c-$version",
            requestId = "r-$version",
            commandType = "shop.order.PlaceOrder",
            commitTime = 1_000L * version,
            events = events.map { EventRecord(it, 1, "{}") },
            scope = Scope.NONE,
            causation = Causation("root", "c-0"),
            headers = mapOf("app" to "web"),
            operator = Operator("alice", "t"),
            schedules = schedules.toList(),
        )

    private fun put(key: String, dueAt: Long) =
        ScheduleChange.Put(key, dueAt, order("o-1"), "shop.order.CancelOrder", """{"reason":"$key"}""")

    private suspend fun handle(stream: EventStreamRecord) {
        timers.subscription.takes(stream).assert().isTrue()
        timers.subscription.handle(SubscribedEntry(stream, null, stream.events))
    }

    @Test
    fun `the subscription is the built-in timers one, and takes streams with no event`() {
        timers.subscription.name.assert().isEqualTo(Hop.TIMERS_SUBSCRIPTION)
        timers.subscription.kind.assert().isEqualTo(FunctionKind.TIMERS)
        val eventless = stream(1, put("pay", 5))
        timers.subscription.takes(eventless).assert().isTrue()
        // An application's subscription passes it over.
        Subscription("projection", SubscriptionFilter(SHOP)) {}.takes(eventless).assert().isFalse()
        assertThrownBy<IllegalArgumentException> { TimersSubscription(SHOP, store, 0) }
    }

    @Test
    fun `a put writes the timer of the stream, a cancel cancels it, and a replay changes nothing`(): Unit = runBlocking {
        val placed = stream(1, put("pay", 5_000), events = listOf("shop.order.OrderPlaced"))
        handle(placed)
        val timer = checkNotNull(store.find(owner, "pay"))
        timer.assert().isEqualTo(Timers.of(placed, put("pay", 5_000), PARTITIONS))
        timer.id.assert().isEqualTo("${order("o-1")}#pay@1")
        timer.partition.assert().isEqualTo(Partitioner.partitionOf(order("o-1"), PARTITIONS))
        timer.causation.assert().isEqualTo(Causation("root", "${order("o-1")}@1"))
        timer.headers.assert().isEqualTo(mapOf("app" to "web"))
        // The scheduled command keeps the operator of the command that scheduled it (decisions D163, D166).
        timer.operator.assert().isEqualTo(Operator("alice", "t"))
        handle(stream(2, ScheduleChange.Cancel("pay")))
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        // A replay of the log from the start writes nothing back.
        handle(placed)
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    @Test
    fun `deleting the aggregate cancels its pending timers before the stream's own changes`(): Unit = runBlocking {
        handle(stream(1, put("pay", 5_000), put("remind", 6_000)))
        handle(stream(2, put("after", 7_000), events = listOf(Lifecycle.AGGREGATE_DELETED)))
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "remind")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "after")?.status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `a saga's timers are written idempotently, by the saga and target`(): Unit = runBlocking {
        val sagas =
            SagaTimers(store, ContextCommandTypes(listOf(orderModel()), ContractDirectory(shopManifest())), PARTITIONS)
        val event =
            StateEvent(
                order("o-1"),
                1,
                "c-1",
                "r-1",
                "shop.order.PlaceOrder",
                1_000,
                listOf(OrderPlaced(1)),
                OrderState()
            )
        val execution = ExecutionId("ReminderSaga.onOrderPlaced", order("o-1"), 1)
        val put = SagaSchedule.Put(Timers.sagaTimerId(execution, 0), "remind", order("o-1"), 1_100, CancelOrder("x"))
        sagas.write(execution.subscription, event, listOf(put))
        sagas.write(execution.subscription, event, listOf(put))
        val sagaOwner = Timers.sagaOwner(execution.subscription, order("o-1"))
        val timer = checkNotNull(store.find(sagaOwner, "remind"))
        timer.id.assert().isEqualTo("$execution#0")
        timer.commandType.assert().isEqualTo("shop.order.CancelOrder")
        timer.body.assert().isEqualTo("""{"reason":"x"}""")
        timer.version.assert().isEqualTo(1_000)
        timer.causation.assert().isEqualTo(Causation("c-1", "${order("o-1")}@1"))
        val later = event.copy(version = 2, commitTime = 2_000)
        sagas.write(execution.subscription, later, listOf(SagaSchedule.Cancel("$execution#1", "remind", order("o-1"))))
        store.find(sagaOwner, "remind")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        val unnamed = listOf(SagaSchedule.Put("$execution#2", "other", order("o-1"), 1_100, "not a command"))
        runCatching { sagas.write(execution.subscription, event, unnamed) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val PARTITIONS = 4
    }
}

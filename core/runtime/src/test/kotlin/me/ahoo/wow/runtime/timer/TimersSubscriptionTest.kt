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
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.Test

/** The `timers` subscription writes the timer store from the schedule events (decisions D166, D198). */
class TimersSubscriptionTest {
    private val store = InMemoryTimerStore()
    private val timers = TimersSubscription(SHOP, store, PARTITIONS)
    private val owner = Timers.ownerOf(order("o-1"))

    private fun stream(version: Int, vararg events: EventRecord, headers: Map<String, String> = mapOf("app" to "web")) =
        EventStreamRecord(
            ref = order("o-1"),
            version = version,
            commandId = "c-$version",
            requestId = "r-$version",
            commandType = "shop.order.PlaceOrder",
            commitTime = 1_000L * version,
            events = events.toList(),
            scope = Scope("alice", "space-a"),
            causation = Causation("root", "c-0"),
            headers = headers,
            operator = Operator("alice", "t"),
        )

    private fun set(key: String, rule: ScheduleRule) =
        ScheduleSet(key, rule, order("o-1"), "shop.order.CancelOrder", """{"reason":"$key"}""")

    private fun event(name: String, payload: Any) = EventRecord(name, 1, WowJson.mapper.writeValueAsString(payload))

    private fun put(key: String, rule: ScheduleRule) = event(Schedules.SCHEDULE_SET, set(key, rule))

    private fun cancel(key: String) = event(Schedules.SCHEDULE_CANCELLED, ScheduleCancelled(key))

    private suspend fun handle(stream: EventStreamRecord) {
        timers.subscription.takes(stream).assert().isTrue()
        timers.subscription.handle(SubscribedEntry(stream, null, stream.events))
    }

    @Test
    fun `the subscription is the built-in timers one, and takes streams that only schedule`() {
        timers.subscription.name.assert().isEqualTo(Hop.TIMERS_SUBSCRIPTION)
        timers.subscription.kind.assert().isEqualTo(FunctionKind.TIMERS)
        val onlySchedules = stream(1, put("pay", ScheduleRule.At(5)))
        timers.subscription.takes(onlySchedules).assert().isTrue()
        // An application's subscription passes it over.
        Subscription("projection", SubscriptionFilter(SHOP)) {}.takes(onlySchedules).assert().isFalse()
        Subscription("projection", SubscriptionFilter(SHOP)) {}
            .takes(stream(1, put("pay", ScheduleRule.At(5)), EventRecord("shop.order.OrderPlaced", 1, "{}")))
            .assert().isTrue()
        assertThrownBy<IllegalArgumentException> { TimersSubscription(SHOP, store, 0) }
    }

    @Test
    fun `a set writes the timer of the stream, a cancel cancels it, and a replay changes nothing`(): Unit = runBlocking {
        val placed = stream(
            1,
            EventRecord("shop.order.OrderPlaced", 1, "{}"),
            put("pay", ScheduleRule.After(4_000)),
            headers = mapOf("app" to "web") + ScheduleContext("old#k@1", "k", 2, 5).headers(),
        )
        handle(placed)
        val timer = checkNotNull(store.find(owner, "pay"))
        timer.assert().isEqualTo(Timers.of(placed, set("pay", ScheduleRule.After(4_000)), PARTITIONS))
        timer.id.assert().isEqualTo("${order("o-1")}#pay@1")
        // An `after` rule counts from the commit that sets it.
        timer.dueAt.assert().isEqualTo(5_000)
        timer.slot.assert().isEqualTo(1)
        timer.requestId.assert().isEqualTo("${order("o-1")}#pay@1#1")
        timer.partition.assert().isEqualTo(Partitioner.partitionOf(order("o-1"), PARTITIONS))
        timer.causation.assert().isEqualTo(Causation("root", "${order("o-1")}@1"))
        // The headers of the command that set it, without the slot it was itself sent for (decision D198).
        timer.headers.assert().isEqualTo(mapOf("app" to "web"))
        // The scheduled command keeps the operator of the command that scheduled it (decisions D163, D166).
        timer.operator.assert().isEqualTo(Operator("alice", "t"))
        // And the scope of the commit that scheduled it (decision D189).
        timer.scope.assert().isEqualTo(Scope("alice", "space-a"))
        handle(stream(2, cancel("pay")))
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        // A replay of the log from the start writes nothing back.
        handle(placed)
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    @Test
    fun `a cron rule's first slot is its first time after the commit`(): Unit = runBlocking {
        handle(stream(1, put("report", ScheduleRule.cron("0 0 9 * * *"))))
        val timer = checkNotNull(store.find(owner, "report"))
        timer.rule.assert().isEqualTo(ScheduleRule.Cron("0 0 9 * * *"))
        // Committed at 1970-01-01T00:00:01Z: due at 09:00 that day.
        timer.dueAt.assert().isEqualTo(9 * 3_600_000L)
        timer.slotDueAt.assert().isEqualTo(timer.dueAt)
        handle(stream(2, put("never", ScheduleRule.cron("0 0 0 30 2 *"))))
        store.find(owner, "never")?.dueAt.assert().isEqualTo(Timers.NOT_EVALUATED)
    }

    @Test
    fun `each timer this node writes is handed to its runner, a write that changes nothing is not`(): Unit =
        runBlocking {
            val written = ArrayList<String>()
            val local = TimersSubscription(SHOP, store, PARTITIONS) { written += it.key }
            val stream = stream(1, put("pay", ScheduleRule.At(5)))
            local.subscription.handle(SubscribedEntry(stream, null, stream.events))
            local.subscription.handle(SubscribedEntry(stream, null, stream.events))
            written.assert().containsExactly("pay")
        }

    @Test
    fun `a job instance's tick is sent as system schedule in its tenant, without the setting command's facts`(): Unit =
        runBlocking {
            val job = ScheduleJobs.typeOf(SHOP).ref("acme", "invoice")
            val set = ScheduleSet(ScheduleJobs.TICK_KEY, ScheduleRule.Cron("0 0 0 1 * *"), job, ScheduleJobs.TICK, "{}")
            val stream = stream(1, event(Schedules.SCHEDULE_SET, set)).copy(ref = job)
            timers.subscription.handle(SubscribedEntry(stream, null, stream.events))
            val timer = checkNotNull(store.find(Timers.ownerOf(job), ScheduleJobs.TICK_KEY))
            timer.operator.assert().isEqualTo(Operator.system(ScheduleJobs.COMPONENT, "acme"))
            timer.headers.assert().isEmpty()
            timer.scope.assert().isEqualTo(Scope.NONE)
            timer.causation.assert().isNotNull()
        }

    @Test
    fun `deleting the aggregate cancels its pending timers before the stream's own changes`(): Unit = runBlocking {
        handle(stream(1, put("pay", ScheduleRule.At(5_000)), put("remind", ScheduleRule.At(6_000))))
        handle(
            stream(
                2,
                EventRecord(Lifecycle.AGGREGATE_DELETED, 1, "{}"),
                put("after", ScheduleRule.At(7_000)),
            ),
        )
        store.find(owner, "pay")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "remind")?.status.assert().isEqualTo(TimerStatus.CANCELLED)
        store.find(owner, "after")?.status.assert().isEqualTo(TimerStatus.PENDING)
    }

    private companion object {
        const val PARTITIONS = 4
    }
}

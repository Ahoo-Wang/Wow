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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decision D198: a decide function's schedules are the system events `wow.ScheduleSet` and `wow.ScheduleCancelled`
 * committed with its events, in the same stream, or not at all; every aggregate decides the system commands
 * `wow.schedule.Set` and `wow.schedule.Cancel`; the kernel keeps the rules beside the state, and a scheduled command
 * is not decided before its slot's due time.
 */
class KernelScheduleTest {
    data class Place(val refuse: Boolean = false)
    data class Pay(val alsoRemind: Boolean = false)
    data class Remind(val at: Long)
    data class Prepare(val at: Long)
    data class Report(val expression: String)
    data class CancelOrder(val reason: String)
    data class Notify(val text: String)
    data class Placed(val at: Long)
    data object Paid

    class Order(val id: String) {
        var paid = false
    }

    private val decided = ArrayList<ScheduleContext?>()
    private val shipment = AggregateType("test", "shipment")
    private val model = aggregate<Order>("test", "order") {
        initial { Order(it.id) }
        decide<Place>(Creation.REQUIRED) { _, body ->
            schedule("pay-timeout", ScheduleRule.after(30.minutes), CancelOrder("unpaid"))
            if (body.refuse) Decision.reject("refused", "refused") else Decision.events(Placed(0))
        }
        decide<Pay> { _, body ->
            cancelSchedule("pay-timeout")
            if (body.alsoRemind) {
                schedule(
                    "remind",
                    ScheduleRule.at(Instant.ofEpochMilli(NOW + 5)),
                    Notify("paid"),
                    shipment.ref("t", "s-1")
                )
            }
            Decision.events(Paid)
        }
        decide<Remind> { _, body ->
            schedule("remind", ScheduleRule.At(body.at), CancelOrder("first"))
            schedule("remind", ScheduleRule.At(body.at + 1), CancelOrder("second"))
            schedule("gone", ScheduleRule.At(body.at), CancelOrder("gone"))
            cancelSchedule("gone")
            Decision.NoOp
        }
        decide<Prepare>(Creation.ALLOWED) { _, body ->
            schedule("prepare", ScheduleRule.At(body.at), CancelOrder("prepared"))
            Decision.NoOp
        }
        decide<Report> { _, body ->
            schedule("report", ScheduleRule.cron(body.expression), Notify("report"), shipment.ref("t", "s-1"))
            Decision.NoOp
        }
        decide<CancelOrder> { state, _ ->
            decided += command.schedule
            if (state.paid) Decision.reject("order.paid", "paid") else Decision.NoOp
        }
        evolve<Placed> { }
        evolve<Paid> { paid = true }
    }
    private val log = InMemoryEventLog(partitions = 1)
    private val names = CommandTypes { target, command ->
        if (target == shipment && command == Notify::class.java) "test.shipment.Notify" else null
    }
    private val now = AtomicLong(NOW)
    private val kernel = Kernel(log, clock = { now.get() }, commandTypes = names)
    private val codec = EventCodec()
    private val ref = model.type.ref("t", "o-1")
    private var sequence = 0

    private suspend fun send(body: Any, requestId: String = "r-${++sequence}", headers: Map<String, String> = emptyMap()) =
        kernel.execute(model, Command("c-$requestId", requestId, ref, body, headers = headers))

    private suspend fun streams(): List<EventStreamRecord> = log.read(ref).toList()

    private fun EventStreamRecord.decoded(): List<Any> = events.map { codec.decode(model, it) }

    @Test
    fun `a schedule commits as a system event after the command's events`(): Unit = runTest {
        val committed = send(Place()) as CommandOutcome.Committed
        committed.stream.events.map { it.name }.assert().containsExactly("test.order.Placed", Schedules.SCHEDULE_SET)
        committed.stream.decoded().last().assert().isEqualTo(
            ScheduleSet(
                key = "pay-timeout",
                rule = ScheduleRule.After(30.minutes.inWholeMilliseconds),
                target = ref,
                commandType = "test.order.CancelOrder",
                command = """{"reason":"unpaid"}""",
            ),
        )
        committed.schedules.assert().isEqualTo(mapOf("pay-timeout" to ScheduleRule.After(1_800_000)))
        streams().single().assert().isEqualTo(committed.stream)
    }

    @Test
    fun `a rejected command commits neither events nor schedules`(): Unit = runTest {
        send(Place(refuse = true)).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        streams().assert().isEmpty()
    }

    @Test
    fun `an append that does not commit takes the schedules with it`(): Unit = runTest {
        val failing = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
                AppendOutcome.Unknown(IllegalStateException("down"))
        }
        Kernel(failing, clock = { NOW }).execute(model, Command("c-1", "r-1", ref, Place()))
            .assert().isInstanceOf(CommandOutcome.Unknown::class.java)
        streams().assert().isEmpty()
    }

    @Test
    fun `a cancel and a schedule of another aggregate type commit with the events`(): Unit = runTest {
        send(Place())
        val paid = send(Pay(alsoRemind = true)) as CommandOutcome.Committed
        paid.stream.decoded().assert().containsExactly(
            Paid,
            ScheduleCancelled("pay-timeout"),
            ScheduleSet(
                "remind",
                ScheduleRule.At(NOW + 5),
                shipment.ref("t", "s-1"),
                "test.shipment.Notify",
                """{"text":"paid"}""",
            ),
        )
        paid.schedules.assert().isEqualTo(mapOf("remind" to ScheduleRule.At(NOW + 5)))
    }

    @Test
    fun `a decision that only schedules commits its schedule events, the last change of each key`(): Unit = runTest {
        send(Place())
        val reminded = send(Remind(at = NOW + 100)) as CommandOutcome.Committed
        reminded.stream.version.assert().isEqualTo(2)
        // `gone` was set and cancelled in the decision, and was not held before: nothing to cancel.
        reminded.stream.decoded().assert().containsExactly(
            ScheduleSet("remind", ScheduleRule.At(NOW + 101), ref, "test.order.CancelOrder", """{"reason":"second"}"""),
        )
        // The state is as it was: the next command decides on it.
        (send(Pay()) as CommandOutcome.Committed).stream.version.assert().isEqualTo(3)
        send(CancelOrder("late")).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
    }

    @Test
    fun `a cron rule is stored with its schedule`(): Unit = runTest {
        send(Place())
        val reported = send(Report("0 0 9 * * MON")) as CommandOutcome.Committed
        (reported.stream.decoded().single() as ScheduleSet).rule.assert().isEqualTo(ScheduleRule.Cron("0 0 9 * * MON"))
    }

    @Test
    fun `a retried request is answered from the log and schedules nothing again`(): Unit = runTest {
        val first = send(Place(), requestId = "place") as CommandOutcome.Committed
        (send(Place(), requestId = "place") as CommandOutcome.AlreadyCommitted).stream.assert().isEqualTo(first.stream)
        streams().size.assert().isEqualTo(1)
    }

    @Test
    fun `a decision that only schedules needs an aggregate that exists`(): Unit = runTest {
        send(Remind(at = NOW)).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        runCatching {
            send(
                Prepare(at = NOW)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        streams().assert().isEmpty()
    }

    @Test
    fun `a command of another aggregate type needs its name`(): Unit = runTest {
        send(Place())
        runCatching {
            Kernel(log, clock = { NOW }).execute(model, Command("c-x", "r-x", ref, Pay(alsoRemind = true)))
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        streams().size.assert().isEqualTo(1)
    }

    @Test
    fun `every aggregate decides the system commands of a saga's schedules`(): Unit = runTest {
        send(Place())
        val set = SetSchedule("ping", ScheduleRule.At(NOW + 9), "test.order.CancelOrder", """{"reason":"ping"}""")
        val committed = send(set) as CommandOutcome.Committed
        committed.stream.commandType.assert().isEqualTo(Schedules.SET)
        committed.stream.decoded().assert()
            .containsExactly(ScheduleSet("ping", set.rule, ref, set.commandType, set.command))
        val cancelled = send(CancelSchedule("ping")) as CommandOutcome.Committed
        cancelled.stream.decoded().assert().containsExactly(ScheduleCancelled("ping"))
        cancelled.schedules.keys.assert().containsExactly("pay-timeout")
        // A key the aggregate does not keep: nothing to cancel.
        send(CancelSchedule("ping")).assert().isInstanceOf(CommandOutcome.Unchanged::class.java)
        model.findCommandType(Schedules.SET)?.type.assert().isEqualTo(SetSchedule::class.java)
    }

    @Test
    fun `a schedule's system command to an aggregate that does not exist is rejected`(): Unit = runTest {
        val outcome = send(SetSchedule("ping", ScheduleRule.At(NOW), "test.order.CancelOrder", "{}"))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.NOT_FOUND)
    }

    @Test
    fun `the kernel keeps the rules beside the state, and a deletion drops them`(): Unit = runTest {
        send(Place())
        send(Remind(at = NOW + 100))
        val state = kernel.stateAt(model, ref, Instant.ofEpochMilli(NOW))!!
        state.schedules.assert().isEqualTo(
            mapOf("pay-timeout" to ScheduleRule.After(1_800_000), "remind" to ScheduleRule.At(NOW + 101)),
        )
        val deleted = send(DeleteAggregate) as CommandOutcome.Committed
        deleted.schedules.assert().isEmpty()
        // The rules come back with a snapshot of them.
        val snapshot = codec.snapshotOf(
            model,
            ref,
            Order("o-1"),
            deleted.stream.version,
            deleted.stream.scope,
            NOW,
            NOW,
            schedules = state.schedules,
        )
        snapshot.schedules.assert().isEqualTo(state.schedules)
        Kernel(log, clock = { NOW }, snapshots = InMemorySnapshotStore().also { it.save(snapshot) })
            .stateAt(model, ref, Instant.ofEpochMilli(NOW), snapshot)!!.schedules.assert().isEqualTo(state.schedules)
    }

    @Test
    fun `a scheduled command waits for its due time on this node's clock`(): Unit = runBlocking {
        send(Place())
        val due = NOW + 300
        val headers = ScheduleContext("$ref#remind@2", "remind", 1, due).headers()
        val started = System.nanoTime()
        withContext(Dispatchers.Default) { send(CancelOrder("due"), headers = headers) }
        val waited = (System.nanoTime() - started) / 1_000_000
        waited.assert().isGreaterThanOrEqualTo(250)
        decided.single()?.slot.assert().isEqualTo(1L)
        // Once due, it is decided at once.
        now.set(due)
        val again = System.nanoTime()
        send(CancelOrder("now"), headers = headers.plus(ScheduleContext.SLOT to "2"))
        ((System.nanoTime() - again) / 1_000_000).assert().isLessThan(250)
    }

    @Test
    fun `a scheduled command due later than the maximum early wait fails at once, then waits within it`(): Unit =
        runTest {
            send(Place())
            val due = NOW + 5.seconds.inWholeMilliseconds + 1
            val headers = ScheduleContext("$ref#remind@1", "remind", 1, due).headers()
            val failure = runCatching { send(CancelOrder("early"), "timer#1", headers) }.exceptionOrNull()
            failure.assert().isInstanceOf(ScheduleNotDueException::class.java)
            failure as ScheduleNotDueException
            failure.ref.assert().isEqualTo(ref)
            failure.requestId.assert().isEqualTo("timer#1")
            failure.due.assert().isEqualTo(due)
            failure.now.assert().isEqualTo(NOW)
            failure.message.assert().contains("5001 ms")
            // Not decided, and nothing waited for.
            decided.assert().isEmpty()
            currentTime.assert().isEqualTo(0L)
            streams().size.assert().isEqualTo(1)
            // Sent again within the maximum early wait: it waits for its due time and is decided once.
            now.set(due - 5.seconds.inWholeMilliseconds)
            send(CancelOrder("again"), "timer#1", headers).assert().isEqualTo(CommandOutcome.Unchanged(1))
            currentTime.assert().isEqualTo(5.seconds.inWholeMilliseconds)
            decided.single()?.slot.assert().isEqualTo(1L)
        }

    @Test
    fun `a scheduled command already committed is answered as a duplicate however early it arrives`(): Unit = runTest {
        send(Place())
        val headers = ScheduleContext("$ref#remind@1", "remind", 1, NOW).headers()
        send(Pay(), "timer#1", headers).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        now.set(NOW - 1.minutes.inWholeMilliseconds)
        send(Pay(), "timer#1", headers).assert().isInstanceOf(CommandOutcome.AlreadyCommitted::class.java)
    }

    @Test
    fun `a negative maximum early wait is refused`() {
        runCatching { Kernel(log, maxEarlyWait = (-1).seconds) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a stream without an event is refused`() {
        runCatching {
            EventStreamRecord(
                ref,
                1,
                "c",
                "r",
                "test.order.Pay",
                NOW,
                emptyList(),
                me.ahoo.wow.api.aggregate.Scope.NONE
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

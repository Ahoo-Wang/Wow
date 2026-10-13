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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.ScheduleChange
import me.ahoo.wow.spi.log.Transport
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** Decision D166: a decide function's schedules commit with its events, in the same stream, or not at all. */
class KernelScheduleTest {
    data class Place(val refuse: Boolean = false)
    data class Pay(val alsoRemind: Boolean = false)
    data class Remind(val at: Long)
    data class Prepare(val at: Long)
    data class CancelOrder(val reason: String)
    data class Notify(val text: String)
    data class Placed(val at: Long)
    data object Paid

    class Order(val id: String) {
        var paid = false
    }

    private val shipment = AggregateType("test", "shipment")
    private val model = aggregate<Order>("test", "order") {
        initial { Order(it.id) }
        decide<Place>(Creation.REQUIRED) { _, body ->
            schedule("pay-timeout", 30.minutes, CancelOrder("unpaid"))
            if (body.refuse) Decision.reject("refused", "refused") else Decision.events(Placed(0))
        }
        decide<Pay> { _, body ->
            cancelSchedule("pay-timeout")
            if (body.alsoRemind) {
                schedule("remind", Instant.ofEpochMilli(NOW + 5), Notify("paid"), shipment.ref("t", "s-1"))
            }
            Decision.events(Paid)
        }
        decide<Remind> { _, body ->
            schedule("remind", Instant.ofEpochMilli(body.at), CancelOrder("first"))
            schedule("remind", Instant.ofEpochMilli(body.at + 1), CancelOrder("second"))
            schedule("gone", Instant.ofEpochMilli(body.at), CancelOrder("gone"))
            cancelSchedule("gone")
            Decision.NoOp
        }
        decide<Prepare>(Creation.ALLOWED) { _, body ->
            schedule("prepare", Instant.ofEpochMilli(body.at), CancelOrder("prepared"))
            Decision.NoOp
        }
        decide<CancelOrder> { state, _ -> if (state.paid) Decision.reject("order.paid", "paid") else Decision.NoOp }
        evolve<Placed> { }
        evolve<Paid> { paid = true }
    }
    private val log = InMemoryEventLog(partitions = 1)
    private val names = CommandTypes { target, command ->
        if (target == shipment && command == Notify::class.java) "test.shipment.Notify" else null
    }
    private val kernel = Kernel(log, clock = { NOW }, commandTypes = names)
    private val ref = model.type.ref("t", "o-1")
    private var sequence = 0

    private suspend fun send(body: Any, requestId: String = "r-${++sequence}"): CommandOutcome =
        kernel.execute(model, Command("c-$requestId", requestId, ref, body))

    private suspend fun streams(): List<EventStreamRecord> = log.read(ref).toList()

    @Test
    fun `a schedule commits in the stream of the command's events`(): Unit = runTest {
        val committed = send(Place()) as CommandOutcome.Committed
        committed.stream.schedules.assert().containsExactly(
            ScheduleChange.Put(
                key = "pay-timeout",
                dueAt = NOW + 30.minutes.inWholeMilliseconds,
                target = ref,
                commandType = "test.order.CancelOrder",
                body = """{"reason":"unpaid"}""",
            ),
        )
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
        paid.stream.schedules.assert().containsExactly(
            ScheduleChange.Cancel("pay-timeout"),
            ScheduleChange.Put(
                "remind",
                NOW + 5,
                shipment.ref("t", "s-1"),
                "test.shipment.Notify",
                """{"text":"paid"}"""
            ),
        )
    }

    @Test
    fun `a decision that only schedules commits a stream with no event`(): Unit = runTest {
        send(Place())
        val reminded = send(Remind(at = NOW + 100)) as CommandOutcome.Committed
        reminded.stream.version.assert().isEqualTo(2)
        reminded.stream.events.assert().isEmpty()
        reminded.stream.schedules.assert().containsExactly(
            ScheduleChange.Put("remind", NOW + 101, ref, "test.order.CancelOrder", """{"reason":"second"}"""),
            ScheduleChange.Cancel("gone"),
        )
        // The state is as it was: the next command decides on it.
        (send(Pay()) as CommandOutcome.Committed).stream.version.assert().isEqualTo(3)
        send(CancelOrder("late")).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
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
    fun `a stream with neither an event nor a schedule is refused`() {
        assertThrownBy<IllegalArgumentException> {
            EventStreamRecord(ref, 1, "c", "r", "test.order.Pay", NOW, emptyList(), Scope.NONE)
        }
        assertThrownBy<IllegalArgumentException> {
            EventStreamRecord(
                ref,
                1,
                "c",
                "r",
                "test.order.Pay",
                NOW,
                emptyList(),
                Scope.NONE,
                schedules = listOf(ScheduleChange.Cancel("a"), ScheduleChange.Cancel("a")),
            )
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

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

package me.ahoo.wow.spi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.transport.Envelope
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import kotlin.time.Duration.Companion.seconds

/**
 * The SPI records are plain classes (decision D228): built through their factories, they keep a data class's
 * equality, hash and string, and Wow's own copy replaces the fields it is given.
 */
class RecordValuesTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")
    private val operator = Operator.of("alice", "tenant")
    private val causation = Causation("c-0", "c-0")
    private val json = JsonNodeFactory.instance
    private val origin = Origin.of("node-1", "10.0.0.1:8081", "sales-service")
    private val wait = WaitContext.of("w-1", origin, listOf(Hop.handled("OrderSaga.onPlaced")), 9L)
    private val key = ReservationKey.of("email", "a@b.c")

    private fun stream(version: Int = 1) = EventStreamRecord.of(
        ref = ref,
        version = version,
        commandId = "c-1",
        requestId = "r-1",
        commandType = "sales.order.Place",
        commitTime = 7L,
        events = listOf(EventRecord.of("sales.order.Placed", 1, "{}")),
        scope = Scope.NONE,
        causation = causation,
        headers = mapOf("h" to "v"),
        operator = operator,
    )

    private fun snapshot(version: Int = 1) = SnapshotRecord.of(
        ref = ref,
        version = version,
        schemaFingerprint = "f",
        state = "{}",
        firstEventTime = 1L,
        lastEventTime = 2L,
        operator = operator,
        schedules = mapOf("pay" to ScheduleRule.At.of(3L)),
    )

    private fun timer(slot: Long = 1) = Timer.of(
        id = "t-1",
        owner = ref,
        key = "pay",
        version = 1L,
        partition = 0,
        rule = ScheduleRule.At.of(3L),
        dueAt = 3L,
        target = ref,
        commandType = "sales.order.Pay",
        body = "{}",
        slot = slot,
        causation = causation,
        operator = operator,
    )

    private fun parked(retries: Int = 0) = ParkedAggregate.of(
        subscription = "OrderSaga.onPlaced",
        ref = ref,
        fromVersion = 1,
        reason = "down",
        failedAt = 1L,
        retries = retries,
        retrySpec = ParkedRetrySpec.of(3, 1.seconds, 2.seconds),
        request = LedgerRequest.RESUME,
    )

    private fun stateEvent(version: Int = 1) = StateEventRecord.of(
        ref = ref,
        version = version,
        commandId = "c-1",
        requestId = "r-1",
        commandType = "sales.order.Place",
        commitTime = 7L,
        events = listOf(StateEventPayload.of("sales.order.Placed", 1, json.objectNode())),
        state = StateView.of(version, "f", json.objectNode()),
        transport = Transport.of(wait),
    )

    private fun reservation(key: String = "a@b.c") =
        Reservation.of(ReservationScope.of(ref.type, "tenant", "email"), key, ref, 9L, setOf("c-1"))

    @Test
    fun `the log records are values`() {
        valueSemantics({ EventRecord.of("a.b.C", 1, "{}") }, EventRecord.of("a.b.C", 2, "{}"))
        EventRecord.of(
            "a.b.C",
            1,
            "{}"
        ).toString().assert().isEqualTo("EventRecord(name=a.b.C, revision=1, payload={})")
        valueSemantics({ stream() }, stream(2))
        stream().copyWith(version = 2).assert().isEqualTo(stream(2))
        valueSemantics(
            { LogEntry.of(LogPosition(0, 1), stream()) },
            LogEntry.of(LogPosition(0, 1), stream(), TransportCodec.encode(Transport.of()))
        )
        // The transport is compared by its bytes, not their identity.
        LogEntry.of(LogPosition(0, 1), stream(), byteArrayOf(1)).assert()
            .isEqualTo(LogEntry.of(LogPosition(0, 1), stream(), byteArrayOf(1)))
            .hasSameHashCodeAs(LogEntry.of(LogPosition(0, 1), stream(), byteArrayOf(1)))
        LogEntry.of(LogPosition(0, 1), stream()).copyWith(stream = stream(2)).stream.assert().isEqualTo(stream(2))
        valueSemantics({ Transport.of(wait, listOf(key), mapOf("traceparent" to "t"), listOf(key)) }, Transport.of())
        valueSemantics({ AppendOutcome.Committed.of(LogPosition(0, 1)) }, AppendOutcome.Committed.of(LogPosition(0, 2)))
        valueSemantics({ AppendOutcome.AlreadyCommitted.of(stream()) }, AppendOutcome.AlreadyCommitted.of(stream(2)))
        valueSemantics({ AppendOutcome.Conflict.of(1) }, AppendOutcome.Conflict.of(2))
        val cause = IllegalStateException("down")
        valueSemantics({ AppendOutcome.Unknown.of(cause) }, AppendOutcome.Unknown.of(IllegalStateException("down")))
        valueSemantics({ AppendOutcome.Rejected.of(cause) }, AppendOutcome.Rejected.of(IllegalStateException("down")))
    }

    @Test
    fun `the snapshot, state and wait records are values`() {
        valueSemantics({ snapshot() }, snapshot(2))
        snapshot().copyWith(version = 2).assert().isEqualTo(snapshot(2))
        valueSemantics({ SnapshotTable.of(1) }, SnapshotTable.of(1, rebuilding = true))
        SnapshotTable.of(1).copyWith(revision = 2).assert().isEqualTo(SnapshotTable.of(2))
        valueSemantics({ stateEvent() }, stateEvent(2))
        stateEvent().copyWith(version = 2, state = StateView.of(2, "f", json.objectNode())).assert()
            .isEqualTo(stateEvent(2))
        valueSemantics({ StateView.of(1, "f", json.objectNode()) }, StateView.of(1, "f", json.arrayNode()))
        valueSemantics(
            { StateEventPayload.of("a.b.C", 1, json.objectNode()) },
            StateEventPayload.of("a.b.C", 2, json.objectNode()),
        )
        valueSemantics(
            { Origin.of("node-1", "10.0.0.1:8081", "sales-service") },
            Origin.of("node-2", "", "sales-service")
        )
        valueSemantics(
            { WaitContext.of("w-1", origin, emptyList(), 9L) },
            WaitContext.of("w-2", origin, emptyList(), 9L)
        )
        valueSemantics(
            { Envelope.of(EnvelopeKind.COMMAND, "c-1", "sales.order.Place", json.objectNode()) },
            Envelope.of(EnvelopeKind.COMMAND, "c-1", "sales.order.Place", json.objectNode(), Transport.of()),
        )
    }

    @Test
    fun `the timer, reservation, ledger and subscription records are values`() {
        valueSemantics({ timer() }, timer(2))
        timer().copyWith(slot = 2).assert().isEqualTo(timer(2))
        timer().slotDueAt.assert().isEqualTo(3L)
        valueSemantics({ TimerChange.of(timer(), timer(2)) }, TimerChange.of(timer(), timer()))
        timer().copyWith(status = TimerStatus.FIRED).status.assert().isEqualTo(TimerStatus.FIRED)
        valueSemantics({ reservation() }, reservation("d@e.f"))
        reservation().copyWith(key = "d@e.f").assert().isEqualTo(reservation("d@e.f"))
        valueSemantics(
            { ReservationScope.of(ref.type, "tenant", "email") },
            ReservationScope.of(ref.type, "other", "email")
        )
        ReservationScope.of(
            ref.type,
            "tenant",
            "email"
        ).copyWith(tenantId = "other").tenantId.assert().isEqualTo("other")
        valueSemantics({ ReservationKey.of("email", "a@b.c") }, ReservationKey.of("email", "a@b.c", system = true))
        valueSemantics({ ConfirmOutcome.Taken.of(reservation()) }, ConfirmOutcome.Taken.of(reservation("d@e.f")))
        valueSemantics({ parked() }, parked(1))
        parked().copyWith(retries = 1).assert().isEqualTo(parked(1))
        valueSemantics({ ParkedRetrySpec.of() }, ParkedRetrySpec.of(maxRetries = 1))
        ParkedRetrySpec.of().copyWith(maxRetries = 1).assert().isEqualTo(ParkedRetrySpec.of(maxRetries = 1))
        valueSemantics({ ParkedAmendment.ClaimRetry.of(1, 2L) }, ParkedAmendment.ClaimRetry.of(2, 2L))
        valueSemantics(
            { ParkedAmendment.MarkRecoverable.of(Recoverable.RECOVERABLE) },
            ParkedAmendment.MarkRecoverable.of(Recoverable.UNRECOVERABLE),
        )
        valueSemantics(
            { ParkedAmendment.ApplyRetrySpec.of(ParkedRetrySpec.of()) },
            ParkedAmendment.ApplyRetrySpec.of(null)
        )
        valueSemantics(
            { ParkedAmendment.Request.of(LedgerRequest.RESUME) },
            ParkedAmendment.Request.of(LedgerRequest.SKIP)
        )
        valueSemantics(
            { ParkedAmendment.TakeRequest.of(LedgerRequest.RESUME) },
            ParkedAmendment.TakeRequest.of(LedgerRequest.SKIP),
        )
        valueSemantics({ Lease.of("g", 0, "node-1", 1L, 9L) }, Lease.of("g", 0, "node-1", 2L, 9L))
        Lease.of("g", 0, "node-1", 1L, 9L).copyWith(epoch = 2L).assert().isEqualTo(Lease.of("g", 0, "node-1", 2L, 9L))
        valueSemantics({ Member.of("g", "node-1", "10.0.0.1:8081", 9L) }, Member.of("g", "node-2", "10.0.0.1:8081", 9L))
        valueSemantics({ Checkpoint.of("saga", 1, 9L) }, Checkpoint.of("saga", 1, 10L))
        valueSemantics({ IdGenerators.of() }, IdGenerators.of(GlobalIdGenerator { "g" }, AggregateIdGenerator { "a" }))
    }

    private fun valueSemantics(make: () -> Any, other: Any) {
        val value = make()
        val same = make()
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}

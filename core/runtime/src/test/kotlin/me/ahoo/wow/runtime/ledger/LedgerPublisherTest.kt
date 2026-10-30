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

package me.ahoo.wow.runtime.ledger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.subscription.Appender
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.cart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.LedgerAggregate
import me.ahoo.wow.spi.ledger.LedgerEntry
import me.ahoo.wow.spi.ledger.LedgerResolution
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Publishing the failure ledger to the ops context (decision D141): what a scan, a notice and an action send. */
class LedgerPublisherTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val ledger = InMemoryFailureLedger()
    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { entry ->
        error("${entry.ref.id} is broken")
    }
    private val spec = ParkedRetrySpec(maxRetries = 2, initialBackoff = 1.hours, claimTtl = 1.seconds)
    private val runtime = SubscriptionRuntime(
        "group",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(subscription),
        testOptions(maxAttempts = 1).copyWith(parked = ParkedRetryOptions(spec, scanInterval = 1.hours)),
    )
    private val ops = ParkedOps { listOf(ParkedOps.Scope.of(runtime)) }
    private val sent = CopyOnWriteArrayList<CommandWire>()
    private val clock = TickingClock()
    private val publisher = LedgerPublisher(SERVICE, LedgerPublishing(), ops, { sent += it }, clock)

    private suspend fun parkAll(vararg ids: String) {
        ids.forEach { appender.append(it) }
        runtime.start(setOf(0, 1))
        eventually { ledger.list(NAME).size == ids.size }
    }

    @Test
    fun `a scan publishes each entry of the partitions this node runs as a command of its ops aggregate`(): Unit =
        runBlocking {
            parkAll("x1")
            publisher.scan()
            val wire = sent.single()
            val target = Ledger.parkedId(SERVICE, "shop", NAME, cart("x1"))
            wire.type.assert().isEqualTo("ops.parked.RecordParked")
            wire.ref.assert().isEqualTo(
                me.ahoo.wow.api.aggregate.AggregateType("ops", "parked").ref(Caller.DEFAULT_TENANT, target)
            )
            wire.operator.assert().isEqualTo(LedgerPublisher.OPERATOR)
            wire.requestId.assert().startsWith("wow.ledger:")
            val record = ClusterCodec.mapper.treeToValue(wire.record, LedgerEntry::class.java)
            record.service.assert().isEqualTo(SERVICE)
            record.context.assert().isEqualTo("shop")
            record.subscription.assert().isEqualTo(NAME)
            record.aggregate.assert().isEqualTo(LedgerAggregate.of(cart("x1")))
            record.fromVersion.assert().isEqualTo(1)
            record.reason.assert().contains("x1 is broken")
            record.status.assert().isEqualTo("scheduled")
            record.recoverable.assert().isEqualTo("unknown")
            record.request.assert().isNull()
            record.retrySpec.maxRetries.assert().isEqualTo(2)
            record.retrySpec.initialBackoff.assert().isEqualTo("PT1H")
            record.retrySpec.applied.assert().isFalse()
            record.nextRetryAt.assert().isEqualTo(record.failedAt + 1.hours.inWholeMilliseconds)
            runtime.stop()
        }

    @Test
    fun `an entry sent again is the same request, and one back to an earlier content a new one`(): Unit = runBlocking {
        parkAll("x1")
        publisher.scan()
        publisher.scan()
        val (first, again) = sent
        again.requestId.assert().isEqualTo(first.requestId)
        again.id.assert().isNotEqualTo(first.id)
        ops.markRecoverable(ID, cart("x1"), Recoverable.UNRECOVERABLE)
        publisher.scan()
        ClusterCodec.mapper.treeToValue(sent.last().record, LedgerEntry::class.java).status
            .assert().isEqualTo("unrecoverable")
        ops.markRecoverable(ID, cart("x1"), Recoverable.UNKNOWN)
        publisher.scan()
        sent.map { it.requestId }.toSet().size.assert().isEqualTo(3)
        runtime.stop()
    }

    @Test
    fun `an entry that left the ledger is published resolved, one another node runs now is not`(): Unit = runBlocking {
        parkAll("x1", "x2")
        publisher.scan()
        sent.clear()
        ops.skip(
            ID,
            cart("x1"),
            ledger.parked(NAME, cart("x1"))!!.failedAt
        ).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        publisher.scan()
        val resolved = sent.single { it.type == "ops.parked.RecordResolved" }
        val record = ClusterCodec.mapper.treeToValue(resolved.record, LedgerResolution::class.java)
        record.outcome.assert().isEqualTo(LedgerResolution.RESOLVED)
        record.aggregate.id.assert().isEqualTo("x1")
        record.context.assert().isEqualTo("shop")
        record.failedAt.assert().isNotNull()
        // The node hands the partitions off: x2 is still parked, and its new owner publishes it.
        runtime.stop()
        sent.clear()
        publisher.scan()
        sent.assert().isEmpty()
        publisher.scan()
        sent.assert().isEmpty()
    }

    @Test
    fun `an entry two actions act on at once is theirs to publish until both are done`(): Unit = runBlocking {
        parkAll("x1")
        publisher.scan()
        sent.clear()
        ops.skip(
            ID,
            cart("x1"),
            ledger.parked(NAME, cart("x1"))!!.failedAt
        ).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        publisher.acting(ID, cart("x1")) {
            publisher.acting(ID, cart("x1")) { publisher.scan() }
            publisher.scan()
        }.assert().isEqualTo(Unit)
        sent.assert().isEmpty()
        publisher.scan()
        sent.single().record["outcome"].asString().assert().isEqualTo(LedgerResolution.RESOLVED)
        runtime.stop()
    }

    @Test
    fun `a notice and an action publish at once`(): Unit = runBlocking {
        parkAll("x1")
        val entry = ledger.parked(NAME, cart("x1"))!!
        publisher.notifier.notify(ParkedNotice(ParkedNotice.Kind.EXHAUSTED, "group", "shop", entry, spec, 0))
        ClusterCodec.mapper.treeToValue(
            sent.single().record,
            LedgerEntry::class.java
        ).context.assert().isEqualTo("shop")
        publisher.resolved(ID, cart("x1"), LedgerResolution.RETRIED)
        val known = ClusterCodec.mapper.treeToValue(sent.last().record, LedgerResolution::class.java)
        known.failedAt.assert().isEqualTo(entry.failedAt)
        known.context.assert().isEqualTo("shop")
        known.outcome.assert().isEqualTo(LedgerResolution.RETRIED)
        // An entry it never published: its subscription's context.
        publisher.resolved(ID, cart("y1"), LedgerResolution.NOT_PARKED)
        val unknown = ClusterCodec.mapper.treeToValue(sent.last().record, LedgerResolution::class.java)
        unknown.failedAt.assert().isNull()
        unknown.context.assert().isEqualTo("shop")
        publisher.resolved(SubscriptionId("sales", NAME), cart("y2"), LedgerResolution.SKIPPED, failedAt = 3)
        val sales = ClusterCodec.mapper.treeToValue(sent.last().record, LedgerResolution::class.java)
        sales.failedAt.assert().isEqualTo(3L)
        sales.context.assert().isEqualTo("sales")
        runtime.stop()
    }

    @Test
    fun `a record the broker refuses is logged, and the others are still sent`(): Unit = runBlocking {
        parkAll("x1", "x2")
        val attempts = AtomicLong()
        val failing = LedgerPublisher(SERVICE, LedgerPublishing(), ops, { wire ->
            attempts.incrementAndGet()
            if (wire.record["aggregate"]["id"].asString() == "x1") error("The broker is down.")
            sent += wire
        }, clock)
        failing.scan()
        attempts.get().assert().isEqualTo(2L)
        sent.single().record["aggregate"]["id"].asString().assert().isEqualTo("x2")
        runtime.stop()
    }

    @Test
    fun `the reconciliation loop scans until it is stopped, a failed scan logged`(): Unit = runBlocking {
        parkAll("x1")
        val looping = LedgerPublisher(SERVICE, LedgerPublishing(interval = 10.milliseconds), ops, { sent += it }, clock)
        looping.start()
        assertThrownBy<IllegalStateException> { looping.start() }
        eventually { sent.size >= 2 }
        looping.stop()
        looping.stop()
        val scans = AtomicLong()
        val broken = LedgerPublisher(
            SERVICE,
            LedgerPublishing(interval = 10.milliseconds),
            ParkedOps {
                scans.incrementAndGet()
                error("The ledger is unreachable.")
            },
            { sent += it },
            clock
        )
        broken.start()
        eventually { scans.get() >= 2 }
        broken.stop()
        runtime.stop()
    }

    @Test
    fun `an automatic publishing sends nothing until the ops context takes the ledger, then catches up`(): Unit =
        runBlocking {
            // Decision D312: the ops command topic stands for ops being deployed; until it exists, the ledger waits.
            parkAll("x1", "x2")
            val deployed = AtomicBoolean(false)
            val gated = LedgerPublisher(
                SERVICE,
                LedgerPublishing(automatic = true),
                ops,
                { sent += it },
                clock,
                available = { deployed.get() },
            )
            gated.open.assert().isFalse()
            gated.scan()
            gated.parked(ops.list(ID).entries.first())
            gated.resolved(ID, cart("x1"), LedgerResolution.RESOLVED)
            sent.assert().isEmpty()
            gated.refresh { deployed.get() }
            sent.assert().isEmpty()
            // Once ops is deployed, the next check opens the gate and publishes every entry at once.
            deployed.set(true)
            gated.refresh { deployed.get() }
            gated.open.assert().isTrue()
            sent.map { it.record["aggregate"]["id"].asString() }.assert().containsExactlyInAnyOrder("x1", "x2")
            // A scan that fails on opening is logged; the gate stays open, and the loop scans again.
            val unreadable = LedgerPublisher(
                SERVICE,
                LedgerPublishing(automatic = true),
                ParkedOps { error("The ledger is unreachable.") },
                { sent += it },
                clock,
                available = { true },
            )
            unreadable.refresh { true }
            unreadable.open.assert().isTrue()
            // A failed check keeps the gate as it is; one that finds the topic gone closes it.
            gated.refresh { error("The broker is unreachable.") }
            gated.open.assert().isTrue()
            gated.refresh { false }
            gated.open.assert().isFalse()
            runtime.stop()
        }

    @Test
    fun `a cancelled check or catch-up is not taken for a failure`(): Unit = runBlocking {
        val cancelled = LedgerPublisher(
            SERVICE,
            LedgerPublishing(automatic = true),
            ParkedOps { throw CancellationException("The node stops.") },
            { sent += it },
            clock,
            available = { true },
        )
        assertThrows<CancellationException> { cancelled.refresh { throw CancellationException("The node stops.") } }
        cancelled.open.assert().isFalse()
        assertThrows<CancellationException> { cancelled.refresh { true } }
        sent.assert().isEmpty()
    }

    @Test
    fun `the gate is asked at start and every check interval until stopped`(): Unit = runBlocking {
        val asks = AtomicLong()
        val gated = LedgerPublisher(
            SERVICE,
            LedgerPublishing(interval = 1.hours, automatic = true),
            ops,
            { sent += it },
            clock,
            available = { asks.incrementAndGet() > 2 },
            availability = 10.milliseconds,
        )
        gated.start()
        eventually { gated.open }
        gated.stop()
        asks.get().assert().isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `publishing names a context and scans at a positive interval`() {
        LedgerPublishing().context.assert().isEqualTo(Ledger.OPS_CONTEXT)
        LedgerPublishing().interval.assert().isEqualTo(LedgerPublishing.DEFAULT_INTERVAL)
        assertThrownBy<IllegalArgumentException> { LedgerPublishing(" ") }
        assertThrownBy<IllegalArgumentException> { LedgerPublishing(interval = Duration.ZERO) }
    }

    /** A clock that moves a millisecond each time it is read. */
    private class TickingClock(private val now: AtomicLong = AtomicLong(1_000)) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now.incrementAndGet())
    }

    private companion object {
        const val SERVICE = "shop-service"
        const val NAME = "Projector.onCart"
        val ID = SubscriptionId("shop", NAME)
    }
}

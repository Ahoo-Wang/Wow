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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.timer.TestClock
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The long-horizon retries of parked entries (roadmap P.1): by their spec's backoff, on the partition's owner. */
class ParkedRetriesTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()
    private val clock = TestClock()
    private val handled = Handled()
    private val notices = CopyOnWriteArrayList<ParkedNotice>()
    private val notifier = ParkedNotifier { notices += it }

    /** The attempts at x's version 2, and how many of them fail before one succeeds. */
    private val attempts = AtomicInteger()
    private val failures = AtomicInteger(Int.MAX_VALUE)

    /** While set, an attempt at x's version 2 waits for it to complete. */
    private val gate = AtomicReference<CompletableDeferred<Unit>?>()
    private val waiting = AtomicBoolean()

    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { entry ->
        if (entry.ref.id == "x" && entry.version == 2) {
            val attempt = attempts.incrementAndGet()
            gate.get()?.let {
                waiting.set(true)
                it.await()
            }
            if (attempt <= failures.get()) error("x-2 fails (attempt $attempt)")
        }
        handled.record(entry)
    }

    private val spec = ParkedRetrySpec(maxRetries = 3, initialBackoff = 1.seconds, claimTtl = 1.seconds)

    private fun runtime(spec: ParkedRetrySpec = this.spec, ledger: FailureLedger = this.ledger) = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        ledger,
        listOf(subscription),
        testOptions(maxAttempts = 1).copyWith(parked = ParkedRetryOptions(spec, scanInterval = 5.milliseconds)),
        clock,
        notifier = notifier,
    )

    private val all = setOf(0, 1)

    private suspend fun parked(): ParkedAggregate? = ledger.parked(SubscriptionId("shop", NAME), cart("x"))

    /** x1 x2 y1: x parks at version 2 after its one in-process attempt. */
    private suspend fun parkX(runtime: SubscriptionRuntime, partitions: Set<Int> = all) {
        listOf("x", "x", "y").forEach { appender.append(it) }
        runtime.start(partitions)
        eventually { parked() != null && attempts.get() == 1 }
    }

    /** Waits a few scans, then asserts x's version 2 was attempted [expected] times in all. */
    private suspend fun attemptedAfterScans(expected: Int) {
        delay(60)
        attempts.get().assert().isEqualTo(expected)
    }

    @Test
    fun `a handler that fails and then succeeds recovers automatically by the backoff after parking`(): Unit =
        runBlocking {
            failures.set(3)
            val runtime = runtime()
            parkX(runtime)
            parked()!!.retries.assert().isEqualTo(0)
            // The first retry is due a second after the failure: the scans schedule it, and pass it over until then.
            attemptedAfterScans(1)
            parked()!!.let { it.nextAttemptAt.assert().isEqualTo(it.failedAt + 1_000) }

            clock.advance(1.seconds)
            eventually { attempts.get() == 2 && parked()?.retries == 1 }
            val first = parked()!!
            first.retryAt.assert().isEqualTo(clock.millis())
            first.nextRetryAt(spec).assert().isEqualTo(clock.millis() + 2_000)

            // The second is due two seconds after the first started, not one.
            clock.advance(1.seconds)
            attemptedAfterScans(2)
            parked()!!.nextAttemptAt.assert().isEqualTo(first.retryAt!! + 2_000)
            clock.advance(1.seconds)
            eventually { attempts.get() == 3 && parked()?.retries == 2 }

            clock.advance(4.seconds)
            eventually { parked() == null }
            attempts.get().assert().isEqualTo(4)
            handled.versionsOf("x").assert().isEqualTo(listOf(1, 2))

            // Resolved: its later entries are handled again.
            appender.append("x")
            eventually { handled.versionsOf("x") == listOf(1, 2, 3) }
            runtime.stop()
            notices.map { it.kind }.assert().containsExactly(ParkedNotice.Kind.PARKED)
        }

    @Test
    fun `an entry whose retries are spent waits for an operator, and is told once`(): Unit = runBlocking {
        val runtime = runtime(spec.copyWith(maxRetries = 2))
        parkX(runtime)
        eventually { notices.size == 1 }
        val parkedNotice = notices.single()
        parkedNotice.kind.assert().isEqualTo(ParkedNotice.Kind.PARKED)
        parkedNotice.group.assert().isEqualTo("group")
        parkedNotice.entry.ref.assert().isEqualTo(cart("x"))
        parkedNotice.nextRetryAt.assert().isEqualTo(parkedNotice.at + 1_000)

        clock.advance(1.seconds)
        eventually { attempts.get() == 2 }
        clock.advance(2.seconds)
        eventually { attempts.get() == 3 && notices.size == 2 }
        val exhausted = notices.last()
        exhausted.kind.assert().isEqualTo(ParkedNotice.Kind.EXHAUSTED)
        exhausted.entry.retries.assert().isEqualTo(2)
        exhausted.nextRetryAt.assert().isNull()
        parked()!!.exhausted(spec.copyWith(maxRetries = 2)).assert().isTrue()

        clock.advance(1_000.seconds)
        attemptedAfterScans(3)
        // Spent: scheduled never, so the scans no longer read it until an operator changes it.
        parked()!!.nextAttemptAt.assert().isEqualTo(ParkedAggregate.NEVER)

        // A spec with more retries an operator applies takes it up again.
        ledger.amend(
            SubscriptionId("shop", NAME),
            cart("x"),
            ParkedAmendment.ApplyRetrySpec(spec.copyWith(maxRetries = 4))
        )
        eventually { attempts.get() == 4 }
        runtime.stop()
        parked()!!.retries.assert().isEqualTo(3)
    }

    @Test
    fun `an entry marked unrecoverable is not retried until it is marked recoverable again`(): Unit = runBlocking {
        failures.set(1)
        val runtime = runtime()
        parkX(runtime)
        ledger.amend(
            SubscriptionId("shop", NAME),
            cart("x"),
            ParkedAmendment.MarkRecoverable(Recoverable.UNRECOVERABLE)
        )
        clock.advance(10.seconds)
        attemptedAfterScans(1)
        ledger.amend(SubscriptionId("shop", NAME), cart("x"), ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE))
        eventually { parked() == null }
        attempts.get().assert().isEqualTo(2)
        runtime.stop()
    }

    @Test
    fun `a scan reads only the entries due in the partitions it runs`(): Unit = runBlocking {
        val runtime = runtime()
        parkX(runtime)
        attemptedAfterScans(1)
        val scheduled = parked()!!
        scheduled.nextAttemptAt.assert().isEqualTo(scheduled.failedAt + 1_000)
        // Not due yet: no scan reads it, so none rewrites it.
        ledger.due(SubscriptionId("shop", NAME), all, clock.millis()).assert().isEmpty()
        clock.advance(1.seconds)
        eventually { attempts.get() == 2 }
        runtime.stop()
    }

    @Test
    fun `automatic retries can be turned off`(): Unit = runBlocking {
        val runtime = runtime(spec.copyWith(maxRetries = 0))
        parkX(runtime)
        clock.advance(1_000.seconds)
        attemptedAfterScans(1)
        runtime.stop()
        parked()!!.retries.assert().isEqualTo(0)
    }

    @Test
    fun `the owner runs an operator's requests once, without counting a retry`(): Unit = runBlocking {
        val runtime = runtime(spec.copyWith(maxRetries = 0))
        parkX(runtime)
        ledger.amend(SubscriptionId("shop", NAME), cart("x"), ParkedAmendment.Request(LedgerRequest.RETRY))
        eventually { attempts.get() == 2 && parked()?.request == null }
        parked()!!.retries.assert().isEqualTo(0)

        ledger.amend(SubscriptionId("shop", NAME), cart("x"), ParkedAmendment.Request(LedgerRequest.SKIP))
        eventually { parked() == null }
        attempts.get().assert().isEqualTo(2)
        appender.append("x")
        eventually { handled.versionsOf("x") == listOf(1, 3) }
        runtime.stop()
    }

    @Test
    fun `a handoff in the middle of a retry leaves it to the next owner, after its backoff`(): Unit = runBlocking {
        val first = runtime()
        listOf("x", "x", "y").forEach { appender.append(it) }
        val xPartition = log.positionOf(cart("x"), 1)!!.partition
        first.start(all)
        eventually { parked() != null && attempts.get() == 1 }

        // The first retry hangs in the handler while the partition is handed off.
        gate.set(CompletableDeferred())
        clock.advance(1.seconds)
        eventually { waiting.get() }
        parked()!!.retries.assert().isEqualTo(1)
        first.stopPartition(xPartition)
        gate.set(null)
        failures.set(2)

        val second = runtime()
        second.start(setOf(xPartition))
        // The retry claimed by the former owner counts: the next one is due its backoff after it started.
        clock.advance(1.seconds)
        attemptedAfterScans(2)
        parked()!!.retries.assert().isEqualTo(1)
        clock.advance(1.seconds)
        eventually { parked() == null }
        attempts.get().assert().isEqualTo(3)
        handled.versionsOf("x").assert().isEqualTo(listOf(1, 2))
        second.stop()
        first.stop()
    }

    /**
     * A ledger whose next scan that finds entries due, once [armed], lets an operator change each of them right after
     * the owner read it: the owner then claims, takes or schedules at a revision the entry is no longer at.
     */
    private inner class ChangedAfterRead : FailureLedger by ledger {
        val armed = AtomicBoolean()

        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> = ledger.due(subscription, partitions, now, after, limit).also { page ->
            if (page.isNotEmpty() && armed.compareAndSet(true, false)) {
                val change = ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE)
                page.forEach { ledger.amend(it.subscription, it.ref, change) }
            }
        }
    }

    @Test
    fun `an operator's request whose take lost to a later change is run by the next scan`(): Unit = runBlocking {
        failures.set(1)
        val changing = ChangedAfterRead()
        val runtime = runtime(ledger = changing)
        parkX(runtime)
        attemptedAfterScans(1)
        changing.armed.set(true)
        ledger.amend(SubscriptionId("shop", NAME), cart("x"), ParkedAmendment.Request(LedgerRequest.RETRY))
        eventually { parked() == null }
        changing.armed.get().assert().isFalse()
        attempts.get().assert().isEqualTo(2)
        runtime.stop()
    }

    @Test
    fun `an operator's skip whose take lost to a later change is run by the next scan`(): Unit = runBlocking {
        val changing = ChangedAfterRead()
        val runtime = runtime(ledger = changing)
        parkX(runtime)
        attemptedAfterScans(1)
        changing.armed.set(true)
        ledger.amend(SubscriptionId("shop", NAME), cart("x"), ParkedAmendment.Request(LedgerRequest.SKIP))
        eventually { parked() == null }
        changing.armed.get().assert().isFalse()
        attempts.get().assert().isEqualTo(1)
        runtime.stop()
    }

    @Test
    fun `an automatic retry whose claim lost to a later change is claimed by the next scan, counted once`(): Unit =
        runBlocking {
            failures.set(2)
            val changing = ChangedAfterRead()
            val runtime = runtime(ledger = changing)
            parkX(runtime)
            attemptedAfterScans(1)
            changing.armed.set(true)
            clock.advance(1.seconds)
            eventually { attempts.get() == 2 && parked()?.retries == 1 }
            changing.armed.get().assert().isFalse()
            parked()!!.recoverable.assert().isEqualTo(Recoverable.RECOVERABLE)
            runtime.stop()
        }

    @Test
    fun `an operator's retry accepted while the owner computes a schedule is run by the next scan`(): Unit =
        runBlocking {
            // #5506: the owner's schedule, read before the request, must not hide it at NEVER.
            ownerScheduleRace { ops, id, ref ->
                ops.retry(id, ref).assert().isEqualTo(ParkedOps.Outcome.REQUESTED)
            }
        }

    @Test
    fun `a retry spec applied while the owner computes a schedule is retried by the next scan`(): Unit =
        runBlocking {
            // #5506, as HostParkedOpsTest met it: a spent entry given another retry while the owner scheduled it NEVER.
            ownerScheduleRace { ops, id, ref ->
                ops.applyRetrySpec(id, ref, ParkedRetrySpec(1, Duration.ZERO, Duration.ZERO)).assert().isNotNull()
            }
        }

    /**
     * Parks an aggregate of a subscription not retried automatically, holds the owner's first schedule of it (NEVER)
     * until [change], an operator's action through another node, is stored, then lets it land: it must not, and the
     * next scan must run the aggregate again.
     */
    private suspend fun ownerScheduleRace(change: suspend (ParkedOps, SubscriptionId, AggregateRef) -> Unit) {
        val name = "Audit.resume"
        val ref = cart("parked")
        val log = InMemoryEventLog(partitions = 1)
        val stored = InMemoryFailureLedger()
        val scheduling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = AtomicBoolean()
        val stale = AtomicReference<ParkedAggregate?>()
        val ledger = object : FailureLedger by stored {
            override suspend fun amend(
                subscription: SubscriptionId,
                ref: AggregateRef,
                amendment: ParkedAmendment,
            ): ParkedAggregate? {
                if (amendment is ParkedAmendment.Schedule && amendment.at == ParkedAggregate.NEVER &&
                    held.compareAndSet(false, true)
                ) {
                    // Only delays the owner's write; the condition is the store's.
                    scheduling.complete(Unit)
                    release.await()
                    return stored.amend(subscription, ref, amendment).also { stale.set(it) }
                }
                return stored.amend(subscription, ref, amendment)
            }
        }
        val broken = AtomicBoolean(true)
        val tried = AtomicInteger()
        val subscription = Subscription(name, SubscriptionFilter("shop")) {
            tried.incrementAndGet()
            check(!broken.get()) { "the downstream is temporarily broken" }
        }
        val options = testOptions(maxAttempts = 1).copyWith(
            parked = ParkedRetryOptions(spec = ParkedRetrySpec(maxRetries = 0), scanInterval = 5.milliseconds),
        )
        val owner = SubscriptionRuntime("race", log, InMemoryCheckpointStore(), ledger, listOf(subscription), options)
        // Another node: the same ledger, without the partition.
        val other = SubscriptionRuntime("race", log, InMemoryCheckpointStore(), stored, listOf(subscription), options)
        try {
            Appender(log).append(ref.id)
            owner.start(setOf(0))
            withTimeout(10.seconds) { scheduling.await() }
            broken.set(false)
            change(ParkedOps.of(listOf(other)), SubscriptionId("shop", name), ref)
            release.complete(Unit)
            // The schedule lost to the change, after which the entry is due: the next scan runs it.
            eventually { stored.parked(SubscriptionId("shop", name), ref) == null }
            stale.get().assert().isNull()
            tried.get().assert().isEqualTo(2)
        } finally {
            release.complete(Unit)
            owner.stop()
            other.stop()
        }
    }

    private companion object {
        const val NAME = "retrying"
    }
}

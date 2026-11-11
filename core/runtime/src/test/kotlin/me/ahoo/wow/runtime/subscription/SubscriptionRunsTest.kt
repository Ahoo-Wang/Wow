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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.HandlerTimeoutException
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * A projection's subscription made [Subscription.batching] (decision D413): its lane hands the run handler the run of
 * an aggregate's waiting entries, and the worker's invariants hold through runs, each test naming its invariant (the
 * runtime's design note `d413-worker-design.md`, rows 1–13).
 */
class SubscriptionRunsTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val id = SubscriptionId("shop", NAME)
    private val observer = RecordingObserver()
    private val options = testOptions(maxAttempts = 2, stopTimeout = 200.milliseconds).copyWith(
        systemic = SystemicFailureOptions(initialBackoff = 2.milliseconds, maxBackoff = 10.milliseconds),
    )

    /** Each entry the tail handed to the workers, once they took it: they take an entry inline. */
    private val admitted = CopyOnWriteArrayList<String>()
    private val watched = object : EventLog by log {
        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = log.tail(partition, after).transform {
            emit(it)
            admitted += key(it.stream)
        }
    }

    /** Each run the run handler got, as its entries' keys in order. */
    private val runs = CopyOnWriteArrayList<List<String>>()

    /** Each entry the handler got alone, in order. */
    private val alone = CopyOnWriteArrayList<String>()

    /** Whether x was parked when each run of x began. */
    private val parkedAtRun = CopyOnWriteArrayList<Boolean>()

    /** An aggregate's runs, and its entries handled alone, wait for its hold while it has one. */
    private val holds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val aloneHolds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** The entry a run of x fails at, and whose handling alone fails while [failing]. */
    @Volatile
    private var failAt = ""

    @Volatile
    private var failing = true

    /** What a run of the aggregate answers instead of every entry done, up to [failAt]. */
    private val answers = ConcurrentHashMap<String, suspend (List<RunEntry>) -> RunHandled>()

    private val subscription = batching()

    private fun batching(decoder: EntryDecoder = EntryDecoder.RECORDS, maxRun: Int = 16) = Subscription.batching(
        NAME,
        SubscriptionFilter("shop"),
        maxRun = maxRun,
        decoder = decoder,
        runHandler = { entries ->
            val first = entries.first().entry.ref.id
            runs += entries.map { key(it.entry) }
            if (first == "x") {
                parkedAtRun += ledger.isParked(id, cart("x"))
            }
            holds[first]?.await()
            answers[first]?.invoke(entries) ?: answered(entries)
        },
    ) { entry ->
        alone += key(entry)
        aloneHolds[entry.ref.id]?.await()
        if (key(entry) == failAt && failing) {
            error("$failAt is broken alone")
        }
    }

    private fun answered(entries: List<RunEntry>): RunHandled {
        val at = entries.indexOfFirst { key(it.entry) == failAt }
        if (at < 0) {
            return RunHandled(entries.size, null)
        }
        return RunHandled(at, IllegalStateException("$failAt is broken"))
    }

    private fun runtime(subscription: Subscription = this.subscription, source: EntrySource = LogSource(watched)) =
        SubscriptionRuntime(
            "group",
            source,
            checkpoints,
            ledger,
            listOf(subscription),
            options,
            clock,
            observer = observer
        )

    private var started: SubscriptionRuntime? = null

    @AfterEach
    fun stop(): Unit = runBlocking {
        (holds.values + aloneHolds.values).forEach { it.complete(Unit) }
        started?.stop()
    }

    /** Starts [runtime] with x's first entry, its run held, and appends [more] of x behind it, each taken. */
    private suspend fun holdX(runtime: SubscriptionRuntime, more: Int): List<Long> {
        holds["x"] = CompletableDeferred()
        val sequences = arrayListOf(appender.append("x"))
        started = runtime.also { it.start(setOf(0)) }
        eventually { runs.isNotEmpty() }
        repeat(more) { sequences += appender.append("x") }
        eventually { "x@${more + 1}" in admitted }
        return sequences
    }

    private fun releaseX() {
        holds.remove("x")?.complete(Unit)
    }

    private fun watermark(runtime: SubscriptionRuntime): Long = runtime.positions().single().watermark

    @Test
    fun `a hot aggregate's waiting entries are one run, in version order, and the checkpoint passes them all`(): Unit =
        runBlocking {
            val runtime = runtime()
            val sequences = holdX(runtime, more = 4)
            releaseX()
            eventually { watermark(runtime) == sequences.last() }
            runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3", "x@4", "x@5"))
            alone.assert().isEmpty()
        }

    @Test
    fun `a run holds at most maxRun entries, and the entries behind it are the next run`(): Unit = runBlocking {
        val runtime = runtime(batching(maxRun = 2))
        val sequences = holdX(runtime, more = 5)
        releaseX()
        eventually { watermark(runtime) == sequences.last() }
        runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3"), listOf("x@4", "x@5"), listOf("x@6"))
    }

    @Test
    fun `row 9 - a run of five whose third fails - the checkpoint passes the first two only, until the third parks`(): Unit =
        runBlocking {
            failAt = "x@4"
            aloneHolds["x"] = CompletableDeferred()
            val runtime = runtime()
            val sequences = holdX(runtime, more = 5)
            releaseX()
            eventually { "x@4" in alone }
            eventually { watermark(runtime) == sequences[2] }
            // x@4 is handled alone, held: the checkpoint stays after x@3, the run's two entries done.
            watermark(runtime).assert().isEqualTo(sequences[2])
            aloneHolds.remove("x")!!.complete(Unit)
            eventually { watermark(runtime) == sequences.last() }
            ledger.parked(id, cart("x"))!!.fromVersion.assert().isEqualTo(4)
            // x@5 and x@6, behind the parked entry, are never handed again.
            runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3", "x@4", "x@5", "x@6"))
            alone.assert().containsExactly("x@4", "x@4")
        }

    @Test
    fun `row 3 - a skip queued while a run is handled resolves after its failing entry parks and before the rest`(): Unit =
        runBlocking {
            failAt = "x@3"
            val runtime = runtime()
            val sequences = holdX(runtime, more = 4)
            // The run of x@2..x@5 is held too, so the skip is queued while it is handled.
            val first = holds.getValue("x")
            holds["x"] = CompletableDeferred()
            first.complete(Unit)
            eventually { runs.size == 2 }
            val skip = async(start = CoroutineStart.UNDISPATCHED) { runtime.skip(NAME, cart("x")) }
            releaseX()
            skip.await().assert().isTrue()
            eventually { watermark(runtime) == sequences.last() }
            // x@3 parked alone, the skip resolved it, then x@4 and x@5 ran: as when an entry alone fails.
            runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3", "x@4", "x@5"), listOf("x@4", "x@5"))
            parkedAtRun.last().assert().isFalse()
            alone.assert().containsExactly("x@3", "x@3")
            ledger.isParked(id, cart("x")).assert().isFalse()
        }

    @Test
    fun `row 3 - a retry queued while a run's failing entry parks runs before the rest, which are handled once`(): Unit =
        runBlocking {
            failAt = "x@3"
            val parking = CompletableDeferred<Unit>()
            val parked = CompletableDeferred<Unit>()
            val gated = object : FailureLedger by ledger {
                override suspend fun park(entry: ParkedAggregate): Boolean = ledger.park(entry).also {
                    parking.complete(Unit)
                    parked.await()
                }
            }
            val runtime = SubscriptionRuntime(
                "group",
                LogSource(watched),
                checkpoints,
                gated,
                listOf(subscription),
                options,
                clock,
            )
            val sequences = holdX(runtime, more = 4)
            releaseX()
            // x@3 failed alone and its ledger entry is written; the worker has not marked it parked yet.
            parking.await()
            val retry = async(start = CoroutineStart.UNDISPATCHED) { runtime.retry(NAME, cart("x")) }
            failing = false
            parked.complete(Unit)
            retry.await().assert().isTrue()
            eventually { watermark(runtime) == sequences.last() }
            // The retry handled x@3..x@5 alone, in order; x@4 and x@5, queued behind it, were skipped as its copies.
            runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3", "x@4", "x@5"))
            alone.assert().containsExactly("x@3", "x@3", "x@3", "x@4", "x@5")
        }

    @Test
    fun `row 5 - a paused partition handles a batching lane's first entry alone, and parks nothing`(): Unit =
        runBlocking {
            val down = CompletableDeferred<Unit>()
            answers["y"] = { RunHandled(0, StorageUnavailableException("The sink is unavailable.")) }
            val outage = Subscription.batching(
                NAME,
                SubscriptionFilter("shop"),
                maxRun = 16,
                runHandler = { entries ->
                    runs += entries.map { key(it.entry) }
                    answers[entries.first().entry.ref.id]?.invoke(entries) ?: RunHandled(entries.size, null)
                },
            ) { entry ->
                alone += key(entry)
                if (entry.ref.id == "y" && !down.isCompleted) {
                    throw StorageUnavailableException("The sink is unavailable.")
                }
            }
            val runtime = runtime(outage)
            started = runtime.also { it.start(setOf(0)) }
            appender.append("y")
            eventually { runtime.paused().isNotEmpty() }
            val sequence = appender.append("x")
            eventually { "x@1" in admitted }
            down.complete(Unit)
            eventually { watermark(runtime) == sequence }
            // While paused, x's lane took no run: its entry was handled alone, behind the pause's probe.
            runs.assert().containsExactly(listOf("y@1"))
            alone.assert().contains("x@1")
            ledger.isParked(id, cart("x")).assert().isFalse()
            ledger.isParked(id, cart("y")).assert().isFalse()
        }

    @Test
    fun `row 7 - a stop during a run leaves its entries above the checkpoint, their observations ended`(): Unit =
        runBlocking {
            val runtime = runtime()
            val x = holdX(runtime, more = 0).single()
            runtime.stop()
            started = null
            checkpoints.savesOf(NAME).assert().allMatch { it < x }
            observer.ended.assert().containsExactly("x@1")
        }

    @Test
    fun `row 8 - a fatal error in a run stops its lane with the run incomplete, another aggregate's lane goes on`(): Unit =
        runBlocking {
            answers["x"] = { throw OutOfMemoryError("A fatal error in the run.") }
            val runtime = runtime()
            started = runtime.also { it.start(setOf(0)) }
            val x = appender.append("x")
            appender.append("y")
            // y@1 completes above x@1, which stays incomplete: the progress counts it, the watermark stays below x@1.
            eventually { runtime.progress(NAME, 1) == x }
            watermark(runtime).assert().isLessThan(x)
            alone.assert().isEmpty()
        }

    @Test
    fun `row 10 - two hot aggregates' runs are each in order, neither waiting on the other's next run`(): Unit =
        runBlocking {
            val runtime = runtime()
            holdX(runtime, more = 2)
            holds["y"] = CompletableDeferred()
            appender.append("y")
            eventually { runs.size == 2 }
            appender.append("y")
            appender.append("y")
            eventually { "y@3" in admitted }
            holds.remove("y")!!.complete(Unit)
            // y's next run runs while x's first is still held.
            eventually { listOf("y@2", "y@3") in runs }
            runs.assert().doesNotContain(listOf("x@2", "x@3"))
            releaseX()
            eventually { listOf("x@2", "x@3") in runs }
        }

    @Test
    fun `row 10 - a run that outlasts the budget's timeout fails as one, and its first entry is handled alone`(): Unit =
        runBlocking {
            answers["x"] = { entries ->
                if (entries.first().entry.version == 1) awaitCancellation() else RunHandled(entries.size, null)
            }
            val runtime = SubscriptionRuntime(
                "group",
                LogSource(watched),
                checkpoints,
                ledger,
                listOf(subscription),
                options.copyWith(
                    retry = RetryPolicy(maxAttempts = 2, initialBackoff = 1.milliseconds, timeout = 100.milliseconds)
                ),
                clock,
                observer = observer,
            )
            started = runtime.also { it.start(setOf(0)) }
            val sequence = appender.append("x")
            eventually { watermark(runtime) == sequence }
            alone.assert().containsExactly("x@1")
            observer.failed.getValue("x@1").assert().isInstanceOf(HandlerTimeoutException::class.java)
        }

    @Test
    fun `row 11 - a run handler's own cancellation or stack overflow is its failure, and the lane goes on`(): Unit =
        runBlocking {
            answers["x"] = { throw CancellationException("The run handler's own timeout.") }
            answers["y"] = { throw StackOverflowError() }
            val runtime = runtime()
            started = runtime.also { it.start(setOf(0)) }
            appender.append("x")
            val sequence = appender.append("y")
            eventually { watermark(runtime) == sequence }
            alone.assert().containsExactlyInAnyOrder("x@1", "y@1")
            ledger.isParked(id, cart("x")).assert().isFalse()
        }

    @Test
    fun `a run holds at least one entry`() {
        assertThrows<IllegalArgumentException> {
            Subscription.batching(NAME, SubscriptionFilter("shop"), 0, runHandler = { RunHandled(0, null) }) {}
        }.message.assert().isEqualTo("A run holds at least one entry, not 0.")
    }

    @Test
    fun `row 13 - a repeating source's copy inside a run reaches the run handler once`(): Unit = runBlocking {
        val runtime = runtime(source = RepeatingSource(LogSource(watched), copies = setOf("x@2")))
        holdX(runtime, more = 2)
        releaseX()
        eventually { "x@3" in observer.ended }
        runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3"))
    }

    @Test
    fun `row 13 - a copy of what a retry handled is skipped in a run`(): Unit = runBlocking {
        failAt = "x@2"
        val reading = CompletableDeferred<Unit>()
        val read = CompletableDeferred<Unit>()
        val retried = object : EventLog by watched {
            override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
                reading.complete(Unit)
                read.await()
                emitAll(watched.readEntries(ref, fromVersion, toVersion))
            }
        }
        val runtime = runtime(source = LogSource(retried))
        started = runtime.also { it.start(setOf(0)) }
        appender.append("x")
        appender.append("x")
        eventually { ledger.isParked(id, cart("x")) }
        failing = false
        val retry = async { runtime.retry(NAME, cart("x")) }
        reading.await()
        // Taken while the retry runs: queued behind it, and read by it too.
        appender.append("x")
        val last = appender.append("x")
        eventually { "x@4" in admitted }
        read.complete(Unit)
        retry.await().assert().isTrue()
        eventually { watermark(runtime) == last }
        runs.flatten().assert().doesNotContain("x@3", "x@4")
        alone.filter { it != "x@2" }.assert().containsExactly("x@3", "x@4")
    }

    @Test
    fun `a run handler's answer that stops short without a failure fails its next entry with a reason that says so`(): Unit =
        runBlocking {
            answers["x"] = { entries -> RunHandled(if (entries.size > 1) 1 else entries.size, null) }
            val runtime = runtime()
            val sequences = holdX(runtime, more = 3)
            releaseX()
            eventually { watermark(runtime) == sequences.last() }
            alone.assert().containsExactly("x@3")
            observer.failed.getValue("x@3").message.assert()
                .isEqualTo("The run handler of $NAME answered 1 of 3 entries without a failure.")
            runs.assert().containsExactly(listOf("x@1"), listOf("x@2", "x@3", "x@4"), listOf("x@4"))
        }

    @Test
    fun `an entry that does not decode ends the run before it, and its handling alone parks it`(): Unit = runBlocking {
        val decoder = EntryDecoder { stream, position, transport ->
            check(key(stream) != "x@3") { "x@3 does not decode." }
            EntryDecoder.RECORDS.decode(stream, position, transport)
        }
        val runtime = runtime(batching(decoder))
        val sequences = holdX(runtime, more = 3)
        releaseX()
        eventually { watermark(runtime) == sequences.last() }
        runs.assert().containsExactly(listOf("x@1"), listOf("x@2"))
        ledger.parked(id, cart("x"))!!.reason.assert().contains("x@3 does not decode.")
    }

    @Test
    fun `each entry of a run is observed in its own trace, the failing one failed`(): Unit = runBlocking {
        val traces = CopyOnWriteArrayList<String>()
        failAt = "x@3"
        failing = false
        answers["x"] = { entries ->
            entries.forEach { traces += "${key(it.entry)} in ${it.context[CoroutineName]?.name}" }
            answered(entries)
        }
        val runtime = runtime()
        val sequences = holdX(runtime, more = 3)
        releaseX()
        eventually { watermark(runtime) == sequences.last() }
        traces.assert().contains("x@2 in x@2", "x@3 in x@3", "x@4 in x@4")
        observer.failed.getValue("x@3").message.assert().isEqualTo("x@3 is broken")
        observer.failed.keys.assert().containsExactly("x@3")
        observer.ended.size.assert().isEqualTo(observer.opened.size)
    }

    /** Observes each handling in a trace named after its entry, recording which ended and which failed. */
    private class RecordingObserver : Observer {
        val opened = CopyOnWriteArrayList<String>()
        val ended = CopyOnWriteArrayList<String>()
        val failed = ConcurrentHashMap<String, Throwable>()

        override fun handling(
            group: String,
            subscription: String,
            context: String,
            stream: EventStreamRecord,
            trace: Map<String, String>,
            topic: String?,
        ): Observation {
            val key = key(stream)
            opened += key
            return object : Observation {
                override val context: CoroutineContext = CoroutineName(key)

                override fun failed(error: Throwable) {
                    failed[key] = error
                }

                override fun end() {
                    ended += key
                }
            }
        }
    }

    /** The log, an entry of [copies] delivered twice, at a sequence of its own, as a topic may deliver a record. */
    private class RepeatingSource(
        private val delegate: EntrySource,
        private val copies: Set<String>
    ) : EntrySource by delegate {
        override val repeats: Boolean get() = true

        override fun tail(partition: Int, after: Long?): Flow<SourcedEntry> =
            delegate.tail(partition, after?.let { it / 2 }).transform {
                emit(at(it, it.position.sequence * 2))
                if (key(it.stream) in copies) {
                    emit(at(it, it.position.sequence * 2 + 1))
                }
            }

        private fun at(entry: SourcedEntry, sequence: Long) =
            SourcedEntry(LogPosition(entry.position.partition, sequence), entry.stream, entry.transport)
    }

    private companion object {
        const val NAME = "runs"

        fun key(stream: EventStreamRecord): String = "${stream.ref.id}@${stream.version}"

        fun key(entry: SubscribedEntry): String = "${entry.ref.id}@${entry.version}"
    }
}

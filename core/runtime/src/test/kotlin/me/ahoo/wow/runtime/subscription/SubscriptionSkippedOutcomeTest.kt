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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * An entry a run of a [Subscription.batching] subscription started to observe and that its lane then skipped, behind
 * its aggregate's park, ends that observation as skipped ([ObservedOutcome.SKIPPED]): its `handle` span says it was
 * passed over, not that it was handled.
 */
class SubscriptionSkippedOutcomeTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val ledger = InMemoryFailureLedger()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val id = SubscriptionId("shop", NAME)
    private val observer = OutcomeObserver()
    private val options = testOptions(maxAttempts = 2, stopTimeout = 200.milliseconds).copyWith(
        systemic = SystemicFailureOptions(initialBackoff = 2.milliseconds, maxBackoff = 10.milliseconds),
    )

    /** Each entry the tail handed to the workers, once they took it: they take an entry inline. */
    private val admitted = CopyOnWriteArrayList<String>()
    private val watched = object : EventLog by log {
        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = log.tail(partition, after).transform {
            emit(it)
            admitted += "${it.stream.ref.id}@${it.stream.version}"
        }
    }

    /** Each run the run handler got, its first entry's key. */
    private val runs = CopyOnWriteArrayList<String>()

    /** x's first run waits for this hold. */
    private val hold = CompletableDeferred<Unit>()

    /** The aggregates whose handling alone finds its sink unavailable, a systemic failure that pauses the partition. */
    private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val subscription = Subscription.batching(
        NAME,
        SubscriptionFilter("shop"),
        maxRun = 16,
        runHandler = { entries ->
            val first = key(entries.first().entry)
            runs += first
            if (first == "x@1") {
                hold.await()
            }
            val at = entries.indexOfFirst { key(it.entry) == FAIL_AT }
            when {
                entries.first().entry.ref.id in unavailable ->
                    RunHandled(0, StorageUnavailableException("The sink is unavailable."))
                at < 0 -> RunHandled(entries.size, null)
                else -> RunHandled(at, IllegalStateException("$FAIL_AT is broken"))
            }
        },
    ) { entry ->
        if (entry.ref.id in unavailable) {
            throw StorageUnavailableException("The sink is unavailable.")
        }
        if (key(entry) == FAIL_AT) {
            error("$FAIL_AT is broken alone")
        }
    }

    private var started: SubscriptionRuntime? = null

    private fun runtime(ledger: FailureLedger = this.ledger) = SubscriptionRuntime(
        "group",
        LogSource(watched),
        RecordingCheckpoints(),
        ledger,
        listOf(subscription),
        options,
        clock,
        observer = observer,
    ).also { started = it }

    @AfterEach
    fun stop(): Unit = runBlocking {
        hold.complete(Unit)
        started?.stop()
    }

    /** Starts [runtime] with x's first entry, its run held, appends [more] of x behind it, then releases the run. */
    private suspend fun runX(runtime: SubscriptionRuntime, more: Int): List<Long> {
        val sequences = arrayListOf(appender.append("x"))
        runtime.start(setOf(0))
        eventually { runs.isNotEmpty() }
        repeat(more) { sequences += appender.append("x") }
        eventually { "x@${more + 1}" in admitted }
        hold.complete(Unit)
        return sequences
    }

    private fun watermark(runtime: SubscriptionRuntime): Long = runtime.positions().single().watermark

    @Test
    fun `entries a run did not reach, then skipped behind the park in the next run, end their observations as skipped`():
        Unit = runBlocking {
        val runtime = runtime()
        val sequences = runX(runtime, more = 4)
        eventually { watermark(runtime) == sequences.last() }

        // x@3 fails alone and parks; x@4 and x@5, which the run of x@2 to x@5 did not reach, are skipped behind it.
        ledger.isParked(id, cart("x")).assert().isTrue()
        observer.outcomes.assert().isEqualTo(
            mapOf(
                "x@1" to ObservedOutcome.OK,
                "x@2" to ObservedOutcome.OK,
                "x@3" to ObservedOutcome.ERROR,
                "x@4" to ObservedOutcome.SKIPPED,
                "x@5" to ObservedOutcome.SKIPPED,
            )
        )
    }

    @Test
    fun `an entry a run did not reach, handed alone while the partition is paused and skipped, ends as skipped`():
        Unit = runBlocking {
        val parks = HeldParks(ledger, "x")
        val runtime = runtime(ledger = parks)
        runX(runtime, more = 3)
        // x@3 fails alone and parks, its ledger write held, while y's sink pauses the partition.
        eventually { parks.reached.isCompleted }
        unavailable += "y"
        val y = appender.append("y")
        eventually { runtime.paused().isNotEmpty() }
        parks.release.complete(Unit)

        // Paused, x's lane hands x@4 alone, which is skipped behind the park: it ends the observation the run started.
        eventually { observer.outcomes.containsKey("x@4") }
        unavailable -= "y"
        eventually { watermark(runtime) == y }
        observer.outcomes.filterKeys { it.startsWith("x@") }.assert().isEqualTo(
            mapOf(
                "x@1" to ObservedOutcome.OK,
                "x@2" to ObservedOutcome.OK,
                "x@3" to ObservedOutcome.ERROR,
                "x@4" to ObservedOutcome.SKIPPED,
            )
        )
    }

    /**
     * Observes each handling, recording the outcome each ended with as the node's tracing reads it: the one set,
     * [ObservedOutcome.ERROR] after a failure, [ObservedOutcome.OK] when none was.
     */
    private class OutcomeObserver : Observer {
        val outcomes = ConcurrentHashMap<String, ObservedOutcome>()

        override fun handling(
            group: String,
            subscription: String,
            context: String,
            stream: EventStreamRecord,
            trace: Map<String, String>,
            topic: String?,
        ): Observation {
            val key = "${stream.ref.id}@${stream.version}"
            return object : Observation {
                @Volatile
                private var outcome: ObservedOutcome? = null

                override fun outcome(outcome: ObservedOutcome) {
                    this.outcome = outcome
                }

                override fun failed(error: Throwable) {
                    outcome = ObservedOutcome.ERROR
                }

                override fun end() {
                    outcomes.put(key, outcome ?: ObservedOutcome.OK)?.let { error("$key ended twice") }
                }
            }
        }
    }

    /** [delegate], whose park of [aggregate] is held at the ledger, once it has [reached] it, until [release]. */
    private class HeldParks(private val delegate: FailureLedger, private val aggregate: String) :
        FailureLedger by delegate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun park(entry: ParkedAggregate): Boolean {
            if (entry.ref.id == aggregate) {
                reached.complete(Unit)
                release.await()
            }
            return delegate.park(entry)
        }
    }

    private companion object {
        const val NAME = "skipped"
        const val FAIL_AT = "x@3"

        fun key(entry: SubscribedEntry): String = "${entry.ref.id}@${entry.version}"
    }
}

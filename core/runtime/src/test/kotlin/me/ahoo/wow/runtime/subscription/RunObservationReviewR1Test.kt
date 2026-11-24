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
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deep review round 1 of the host, configuration and observability: how a batching subscription's entries are observed
 * when its run handler answers that the run failed part way. An entry's handling is observed once, its retries
 * included (`Observer.handling`): its span, and the lag recorded when it starts.
 */
class RunObservationReviewR1Test {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val observer = RecordingObserver()
    private val options = testOptions(maxAttempts = 2, stopTimeout = 200.milliseconds).copyWith(
        systemic = SystemicFailureOptions(initialBackoff = 2.milliseconds, maxBackoff = 10.milliseconds),
    )
    private val admitted = CopyOnWriteArrayList<String>()
    private val watched = object : EventLog by log {
        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = log.tail(partition, after).transform {
            emit(it)
            admitted += key(it.stream)
        }
    }
    private val runs = CopyOnWriteArrayList<List<String>>()

    /** x's first run waits for this, so the entries behind it make one run. */
    private val hold = CompletableDeferred<Unit>()

    /** A projection's run of x: done up to x@3, which fails; alone, every entry succeeds. */
    private val subscription = Subscription.batching(
        NAME,
        SubscriptionFilter("shop"),
        maxRun = 16,
        decoder = EntryDecoder.RECORDS,
        runHandler = { entries ->
            runs += entries.map { key(it.entry) }
            hold.await()
            val at = entries.indexOfFirst { key(it.entry) == "x@3" }
            if (at < 0) RunHandled(entries.size, null) else RunHandled(at, IllegalStateException("x@3 is broken"))
        },
    ) { }

    private val runtime = SubscriptionRuntime(
        "group",
        LogSource(watched),
        RecordingCheckpoints(),
        InMemoryFailureLedger(),
        listOf(subscription),
        options,
        Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC),
        observer = observer,
    )

    @AfterEach
    fun stop(): Unit = runBlocking {
        hold.complete(Unit)
        runtime.stop()
    }

    @Test
    fun `each entry of a run that fails part way should be observed once, the entries after it not ended as handled`():
        Unit = runBlocking {
        val sequences = arrayListOf(appender.append("x"))
        runtime.start(setOf(0))
        eventually { runs.isNotEmpty() }
        repeat(3) { sequences += appender.append("x") }
        eventually { "x@4" in admitted }
        hold.complete(Unit)
        eventually { runtime.positions().single().watermark == sequences.last() }

        runs.assert().contains(listOf("x@2", "x@3", "x@4"))
        observer.opened.groupingBy { it }.eachCount().assert()
            .isEqualTo(mapOf("x@1" to 1, "x@2" to 1, "x@3" to 1, "x@4" to 1))
    }

    /** Records each handling observed, by its entry. */
    private class RecordingObserver : Observer {
        val opened = CopyOnWriteArrayList<String>()

        override fun handling(
            group: String,
            subscription: String,
            context: String,
            stream: EventStreamRecord,
            trace: Map<String, String>,
            topic: String?,
        ): Observation {
            opened += key(stream)
            return Observation.NONE
        }
    }

    private companion object {
        const val NAME = "runs"

        fun key(stream: EventStreamRecord): String = "${stream.ref.id}@${stream.version}"

        fun key(entry: SubscribedEntry): String = "${entry.ref.id}@${entry.version}"
    }
}

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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Decision D154: where a subscription without a checkpoint starts, as the host overrides its handler's default. */
class SubscriptionStartTest {
    data class Create(val slow: Boolean)
    data class Created(val slow: Boolean)
    class Cart {
        var created = false
    }

    /** The time the kernel reads, which the test moves. */
    private val clock = AtomicLong(1_000)
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** A cart whose slow creation waits, once it read its time, until the test releases it. */
    private val model = aggregate<Cart>("shop", "cart") {
        initial { Cart() }
        decideSuspending<Create>(Creation.REQUIRED) { _, body ->
            if (body.slow) {
                entered.complete(Unit)
                release.await()
            }
            Decision.events(Created(body.slow))
        }
        evolve<Created> { created = true }
    }

    private val log = InMemoryEventLog(partitions = 1)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()

    /** Appends [count] streams to `old`, the n-th (from 1) committed at n seconds. */
    private suspend fun append(count: Int, from: Int = 1) {
        (from until from + count).forEach { n ->
            log.append(
                EventStreamRecord(
                    cart("old"),
                    n,
                    "c-$n",
                    "r-$n",
                    "shop.cart.Act",
                    n * 1_000L,
                    listOf(EventRecord("shop.cart.Added", 1, "{}")),
                    Scope.NONE,
                ),
            )
        }
    }

    private fun second(n: Long): Instant = Instant.ofEpochMilli(n * 1_000)

    @Test
    fun `a start should be read from the beginning, the end or an instant`() {
        SubscriptionStart.parse("beginning").assert().isEqualTo(SubscriptionStart.Beginning)
        SubscriptionStart.parse(" END ").assert().isEqualTo(SubscriptionStart.End)
        SubscriptionStart.parse("2026-10-01T00:00:00Z")
            .assert().isEqualTo(SubscriptionStart.At(Instant.parse("2026-10-01T00:00:00Z")))
        SubscriptionStart.parse("2026-10-01T08:00:00+08:00")
            .assert().isEqualTo(SubscriptionStart.At(Instant.parse("2026-10-01T00:00:00Z")))
        assertThrownBy<IllegalArgumentException> { SubscriptionStart.parse("yesterday") }
            .hasMessageContaining("not \"yesterday\"")
        SubscriptionStart.of(StartFrom.BEGINNING).assert().isEqualTo(SubscriptionStart.Beginning)
        SubscriptionStart.of(StartFrom.END).assert().isEqualTo(SubscriptionStart.End)
        SubscriptionStart.of(StartFrom.JOIN).assert().isEqualTo(SubscriptionStart.Beginning)
        SubscriptionStart.of(StartFrom.JOIN, joined = true).assert().isEqualTo(SubscriptionStart.End)
        SubscriptionStart.Beginning.toString().assert().isEqualTo("beginning")
        SubscriptionStart.End.toString().assert().isEqualTo("end")
        SubscriptionStart.At(second(1)).toString().assert().isEqualTo("1970-01-01T00:00:01Z")
    }

    /** Appends one stream to [id] at [version], committed at [second] seconds. */
    private suspend fun appendAt(id: String, version: Int, second: Long) {
        log.append(
            EventStreamRecord(
                cart(id),
                version,
                "c-$id-$version",
                "r-$id-$version",
                "shop.cart.Act",
                second * 1_000L,
                listOf(EventRecord("shop.cart.Added", 1, "{}")),
                Scope.NONE,
            ),
        )
    }

    @Test
    fun `an instant should start at the first entry committed at or after it, after the head when none is`(): Unit =
        runBlocking {
            // An empty partition: from its start, as from the end.
            SubscriptionStart.At(second(5)).checkpointIn(log, 0).assert().isEqualTo(0L)
            SubscriptionStart.End.checkpointIn(log, 0).assert().isEqualTo(0L)
            append(10)
            SubscriptionStart.Beginning.checkpointIn(log, 0).assert().isEqualTo(0L)
            SubscriptionStart.End.checkpointIn(log, 0).assert().isEqualTo(10L)
            // The entry at sequence 5 is committed at 5 s: the start is after 4.
            SubscriptionStart.At(second(5)).checkpointIn(log, 0).assert().isEqualTo(4L)
            SubscriptionStart.At(Instant.ofEpochMilli(5_500)).checkpointIn(log, 0).assert().isEqualTo(5L)
            // Before every entry: from the first.
            SubscriptionStart.At(second(0)).checkpointIn(log, 0).assert().isEqualTo(0L)
            SubscriptionStart.At(second(1)).checkpointIn(log, 0).assert().isEqualTo(0L)
            SubscriptionStart.At(second(10)).checkpointIn(log, 0).assert().isEqualTo(9L)
            // After every entry: after the head, as from the end; the read stops there, never waiting for more.
            SubscriptionStart.At(second(11)).checkpointIn(log, 0).assert().isEqualTo(10L)
        }

    @Test
    fun `an instant should start at the first entry in log order committed at or after it, whatever the times after it`():
        Unit = runBlocking {
        // History appended out of time order, as an import of 9.x streams appends it: 5 s, 1 s, 7 s, 3 s, 9 s.
        listOf(5L, 1L, 7L, 3L, 9L).forEachIndexed { n, at -> appendAt("c-$n", 1, at) }
        SubscriptionStart.At(second(4)).checkpointIn(log, 0).assert().isEqualTo(0L)
        SubscriptionStart.At(second(6)).checkpointIn(log, 0).assert().isEqualTo(2L)
        SubscriptionStart.At(second(8)).checkpointIn(log, 0).assert().isEqualTo(4L)
        SubscriptionStart.At(second(10)).checkpointIn(log, 0).assert().isEqualTo(5L)
    }

    @Test
    fun `an instant should start before an entry decided earlier but appended later than one committed after it`():
        Unit = runBlocking {
        val executor = Executor(Kernel(log, clock = clock::get))
        try {
            withTimeout(5.seconds) {
                // A decides at 1 s and is appended after B, which decides at 2 s.
                val slow = async { executor.execute(model, Command("slow", "slow", cart("a"), Create(true))) }
                entered.await()
                clock.set(2_000)
                executor.execute(model, Command("fast", "fast", cart("b"), Create(false)))
                    .assert().isInstanceOf(CommandOutcome.Committed::class.java)
                release.complete(Unit)
                slow.await().assert().isInstanceOf(CommandOutcome.Committed::class.java)
            }
        } finally {
            release.complete(Unit)
            executor.close(1.seconds)
        }
        val commits = log.tail(0).take(2).toList().map { it.stream.commitTime }
        commits.assert().isEqualTo(listOf(2_000L, 1_000L))
        // B, at sequence 1, is the first committed at or after 1.5 s: A after it is handled too.
        SubscriptionStart.At(Instant.ofEpochMilli(1_500)).checkpointIn(log, 0).assert().isEqualTo(0L)
    }

    @Test
    fun `an instant's search should log where it begins and what it found`(): Unit = runBlocking {
        append(3)
        val lines = logged { SubscriptionStart.At(second(2)).checkpointIn(log, 0).assert().isEqualTo(1L) }
        lines.assert().hasSize(2)
        lines[0].assert().contains("partition 0 of the log").contains("1970-01-01T00:00:02Z").contains("up to 3")
        lines[1].assert().contains("Read 2 entries of partition 0 of the log in ").contains("entry 2 is the first")
            .contains("starting after 1")
    }

    @Test
    fun `an instant's search should stop when it is cancelled`(): Unit = runBlocking {
        append(3)
        // A log whose reads hang after its first entry, as a store that stops answering.
        val hanging = object : EventLog by log {
            override fun tail(partition: Int, after: Long?): Flow<LogEntry> =
                log.tail(partition, after).take(1).onCompletion { awaitCancellation() }
        }
        val search = async { SubscriptionStart.At(second(9)).checkpointIn(hanging, 0) }
        yield()
        search.cancel()
        withTimeout(5.seconds) { search.join() }
        search.isCancelled.assert().isTrue()
    }

    /** The lines the start's search logs while [block] runs. */
    private suspend fun logged(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SubscriptionStart::class.java.name) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `the host's start should override the handler's only for a subscription without a checkpoint`(): Unit =
        runBlocking {
            append(10)
            val fromStart = Handled()
            val fromInstant = Handled()
            val options = testOptions().copyWith(
                starts = mapOf(
                    SubscriptionId("shop", "saga") to SubscriptionStart.Beginning,
                    SubscriptionId("shop", "reactor") to SubscriptionStart.At(second(7)),
                ),
            )
            val runtime = SubscriptionRuntime(
                "group",
                log,
                checkpoints,
                ledger,
                listOf(
                    Subscription("saga", SubscriptionFilter("shop"), StartFrom.END) { fromStart.record(it) },
                    Subscription("reactor", SubscriptionFilter("shop"), StartFrom.END) { fromInstant.record(it) },
                ),
                options,
            )
            runtime.start(setOf(0))
            // Saved at once, as a start from the end is, so a restart does not move it.
            checkpoints.savesOf("reactor").first().assert().isEqualTo(6L)
            eventually { fromStart.size == 10 && fromInstant.size == 4 }
            runtime.stop()
            fromStart.sequences().assert().isEqualTo((1L..10L).toList())
            fromInstant.sequences().assert().isEqualTo((7L..10L).toList())

            // With a checkpoint, the override no longer applies.
            append(2, from = 11)
            val again = Handled()
            val restarted = SubscriptionRuntime(
                "group",
                log,
                checkpoints,
                ledger,
                listOf(Subscription("reactor", SubscriptionFilter("shop"), StartFrom.END) { again.record(it) }),
                testOptions().copyWith(
                    starts = mapOf(SubscriptionId("shop", "reactor") to SubscriptionStart.Beginning)
                ),
            )
            restarted.start(setOf(0))
            eventually { again.size == 2 }
            restarted.stop()
            again.sequences().assert().isEqualTo(listOf(11L, 12L))
        }
}

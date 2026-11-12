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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * A cache miss reads the snapshot with the tail through its type's reader, and reads apart, counting the fallback,
 * when the reader fails (findings R5.B-9, R5.B-16).
 */
class SnapshotTailReadsTest {
    private val type = AggregateType("shop", "cart")
    private val ref = type.ref("tenant", "c-1")
    private val memory = InMemoryEventLog()
    private val snapshots = InMemorySnapshotStore()
    private val fallbacks =
        ArrayList<Triple<String, ObservedEvent.StoreFellBack.Capability, ObservedEvent.StoreFellBack.Reason>>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            when (event) {
                is ObservedEvent.StoreFellBack -> fallbacks += Triple(event.context, event.capability, event.reason)
                else -> Unit
            }
        }
    }
    private val served = SnapshotTail.of(null, AggregateTail.of(emptyList(), emptyList()))

    private fun reads(reader: SnapshotTailReader): SnapshotTailReads =
        SnapshotTailReads(memory, { if (it == type) reader else null }, observer)

    private suspend fun appendOne() {
        memory.append(
            EventStreamRecord.of(
                ref,
                1,
                "c",
                "r",
                "shop.cart.Add",
                0,
                listOf(EventRecord.of("shop.cart.Added", 1, "{}")),
                Scope.NONE
            ),
        )
    }

    @Test
    fun `a type without a reader is read apart, which is no fallback`() = runTest {
        appendOne()
        SnapshotTailReads(memory, { null }, observer).read(ref, snapshots, 2).tail.streams.map { it.version }
            .assert().containsExactly(1)
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `a reader that serves is read through, with no fallback`() = runTest {
        reads { _, _ -> served }.read(ref, snapshots, 2).assert().isSameAs(served)
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `a reader that fails is read apart and counted, never failing the load`() = runTest {
        appendOne()
        val reads = reads { _, _ -> throw IOException("The statement failed.") }
        repeat(2) { reads.read(ref, snapshots, 2).tail.streams.map { it.version }.assert().containsExactly(1) }
        fallbacks.assert().containsExactly(
            Triple(
                "shop",
                ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ,
                ObservedEvent.StoreFellBack.Reason.FAILED
            ),
            Triple(
                "shop",
                ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ,
                ObservedEvent.StoreFellBack.Reason.FAILED
            ),
        )
    }

    @Test
    fun `a reader that gives no answer within the timeout is cancelled, and the log is read alone`() = runTest {
        appendOne()
        var cancelled = false
        // The reader's store is the log's: a snapshot that would be loaded apart is on it as well, so none is loaded.
        val loads = object : me.ahoo.wow.spi.snapshot.SnapshotStore by snapshots {
            var count = 0

            override suspend fun load(ref: me.ahoo.wow.api.aggregate.AggregateRef) = snapshots.load(
                ref
            ).also { count++ }
        }
        val reads = SnapshotTailReads(
            memory,
            {
                SnapshotTailReader { _, _ ->
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            },
            observer,
            2.seconds,
        )
        val read = reads.read(ref, loads, 2)
        currentTime.assert().isEqualTo(2_000L)
        cancelled.assert().isTrue()
        read.snapshot.assert().isNull()
        read.tail.streams.map { it.version }.assert().containsExactly(1)
        loads.count.assert().isZero()
        fallbacks.assert().containsExactly(
            Triple(
                "shop",
                ObservedEvent.StoreFellBack.Capability.SNAPSHOT_TAIL_READ,
                ObservedEvent.StoreFellBack.Reason.TIMED_OUT
            ),
        )
    }

    @Test
    fun `the first failure of each outage is a warning, and a read that answers ends the outage`() = runTest {
        appendOne()
        var down = true
        val reads = SnapshotTailReads(
            memory,
            {
                SnapshotTailReader { _, _ ->
                    if (down) throw IOException("The statement failed.") else served
                }
            },
            observer,
            2.seconds,
        )
        val logger = LoggerFactory.getLogger("me.ahoo.wow.kernel") as Logger
        val level = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            repeat(2) { reads.read(ref, snapshots, 2) }
            down = false
            reads.read(ref, snapshots, 2)
            down = true
            reads.read(ref, snapshots, 2)
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        val logged = appender.list.filter { it.formattedMessage.startsWith("Reading the snapshot of") }
        logged.map { it.level }.assert().containsExactly(Level.WARN, Level.DEBUG, Level.WARN)
        logged.first().formattedMessage.assert().isEqualTo(
            "Reading the snapshot of $ref with its tail in one round trip failed: reading them apart. Later failures " +
                "are counted (wow.store.fallbacks), and logged at debug level until a read answers.",
        )
    }

    @Test
    fun `a caller giving up on a read is no timeout of the read`() = runTest {
        val reads =
            SnapshotTailReads(memory, { SnapshotTailReader { _, _ -> awaitCancellation() } }, observer, 2.seconds)
        assertThrows<TimeoutCancellationException> { withTimeout(1.seconds) { reads.read(ref, snapshots, 2) } }
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `a cancelled read is cancelled, never read apart`() = runTest {
        val reads = reads { _, _ -> throw CancellationException("cancelled") }
        assertThrows<CancellationException> { reads.read(ref, snapshots, 2) }
        fallbacks.assert().isEmpty()
    }
}

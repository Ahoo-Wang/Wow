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

package me.ahoo.wow.runtime.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.ObservedEvent.TailRead.Reason
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The node's probe of its tails (HandoffProber, #6702): one call of the log a round for every tail, the tails of the
 * partitions it names reading the store, a report kept until a read of the store succeeds, and the backstop's exact
 * heads finding, and logging, what the probe did not name.
 */
class HandoffProberTest : HandoffLogFixtures() {
    /** The store as the probe sees it: every call recorded, its answers replaced by [named] when given. */
    private class ProbedLog(
        private val delegate: EventLog,
        @Volatile var named: ((Map<Int, Long>) -> Set<Int>)? = null,
    ) : EventLog by delegate {
        val asked = CopyOnWriteArrayList<Map<Int, Long>>()
        val headsRead = AtomicInteger()
        private val headReads = ConcurrentHashMap<Int, AtomicInteger>()

        /** The reads of [partition]'s head so far, its tails' openings included. */
        fun headReads(partition: Int): Int = headReads[partition]?.get() ?: 0

        @Volatile
        var failHeadOnce = false

        override suspend fun appendedElsewhere(cursors: Map<Int, Long>): Set<Int> {
            asked += cursors
            return named?.invoke(cursors) ?: delegate.appendedElsewhere(cursors)
        }

        override suspend fun heads(partitions: Collection<Int>): Map<Int, Long?> =
            delegate.heads(partitions).also { headsRead.incrementAndGet() }

        override suspend fun head(partition: Int): Long? {
            if (failHeadOnce) {
                failHeadOnce = false
                throw IllegalStateException("The store is down for this read.")
            }
            return delegate.head(partition).also {
                headReads.computeIfAbsent(partition) { AtomicInteger() }.incrementAndGet()
            }
        }
    }

    /** A tail as the prober sees it, idle for as long as the test says, at the cursor the test sets. */
    private class FakeTail(
        override val partition: Int,
        @Volatile override var cursor: Long = 0,
    ) : HandoffProber.Probed {
        @Volatile
        var idle: Duration = Duration.ZERO

        override val idleFor: Duration get() = idle

        val wakes = CopyOnWriteArrayList<Reason>()

        override fun wake(reason: Reason) {
            wakes += reason
        }
    }

    private fun probed(log: EventLog, backstop: Duration = 10.seconds) = HandoffLog(
        log,
        "bank",
        HandoffOptions(probeInterval = PROBE, backstopInterval = backstop),
        reads,
    )

    @Test
    fun `one round asks the log once for every tail, and reads no head while nothing is named`(): Unit = runBlocking {
        val log = ProbedLog(store)
        val handoff = probed(log)
        val jobs = (0 until PARTITIONS).map { partition ->
            launch(Dispatchers.Default) { handoff.tail(partition).collect { } }
        }
        eventually { log.asked.size >= 3 }
        // Every partition of every round, in one call each; the tails' own head reads were their openings' alone.
        log.asked.assert().allMatch { it.keys == (0 until PARTITIONS).toSet() }
        store.heads.get().assert().isEqualTo(PARTITIONS)
        reads.reasons.assert().isEmpty()
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test
    fun `a partition the probe names has its tail read the store, and the others read nothing`(): Unit = runBlocking {
        val log = ProbedLog(store)
        val handoff = probed(log)
        val (seen, job) = collect(handoff)
        val other = launch(Dispatchers.Default) { handoff.tail(1).collect { } }
        eventually { log.asked.size >= 1 }
        store.append(next(refs[0]))
        seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        // Three rounds more, each a probe interval apart: a wake of the other tail would have read its head by then.
        val asked = log.asked.size
        eventually { log.asked.size >= asked + 3 }
        reads.reasons.assert().containsExactly(Reason.IDLE)
        log.headReads(1).assert().describedAs("partition 1's head reads, its opening's included").isEqualTo(1)
        job.cancelAndJoin()
        other.cancelAndJoin()
    }

    @Test
    fun `a report whose read of the store fails is read when the tail opens again, without a second report`(): Unit =
        runBlocking {
            val armed = java.util.concurrent.atomic.AtomicBoolean()
            val reports = AtomicInteger()
            val log = ProbedLog(store)
            // The probe names partition 0 once, once armed, and never again: the store remembers what it reported.
            log.named = { cursors ->
                if (0 in cursors && armed.compareAndSet(true, false)) setOf(0).also { reports.incrementAndGet() } else emptySet()
            }
            val handoff = probed(log)
            val seen = Channel<LogEntry>(Channel.UNLIMITED)
            // As a partition's runner does: a tail that fails is collected again.
            val job = launch(Dispatchers.Default) { handoff.tail(0).retry(1).collect { seen.send(it) } }
            eventually { log.asked.size >= 2 }
            // Another node appends; the read of the store the report calls for fails once.
            log.failHeadOnce = true
            store.append(next(refs[0]))
            armed.set(true)
            seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
            reports.get().assert().isEqualTo(1)
            log.failHeadOnce.assert().isFalse()
            job.cancelAndJoin()
        }

    @Test
    fun `a tail busy when its partition is named reads once idle, and every round asks about it, busy or idle`(): Unit =
        runTest {
            val calls = AtomicInteger()
            // Another node's block appears in partition 0: named at the first round alone, as MongoDB's log remembers.
            val log = ProbedLog(store, named = { if (calls.incrementAndGet() == 1) setOf(0) else emptySet() })
            val prober = HandoffProber(log, "bank", HandoffOptions(probeInterval = PROBE), backgroundScope)
            val tail = FakeTail(partition = 0)
            prober.register(tail)
            // Three rounds while this node's own commits keep the tail busy: the report is kept, not read yet.
            advanceTimeBy(PROBE / 2 * 3 + 1.milliseconds)
            log.asked.assert().hasSize(3).allMatch { it == mapOf(0 to 0L) }
            tail.wakes.assert().isEmpty()
            // Idle again: the next round wakes it, once, though the log named the partition at the first round alone.
            tail.idle = PROBE / 2
            advanceTimeBy(PROBE / 2)
            tail.wakes.assert().containsExactly(Reason.IDLE)
            // Its read brought the other node's entry.
            tail.cursor = 1
            advanceTimeBy(PROBE * 2)
            tail.wakes.assert().containsExactly(Reason.IDLE)
            log.asked.assert().allMatch { 0 in it }
            prober.unregister(tail)
        }

    @Test
    fun `another node's entry that lands after the probe named its reservation is read within a probe round`(): Unit =
        runBlocking {
            // #6760: MongoDB lists another node's block, and names its partition, before that node's stream is in the
            // head, and names the block no more. The read the probe calls for finds nothing; the next round reads again.
            val reserved = java.util.concurrent.atomic.AtomicBoolean()
            val log = ProbedLog(store, named = { cursors ->
                if (0 in cursors && reserved.compareAndSet(true, false)) setOf(0) else emptySet()
            })
            val handoff = probed(log)
            val (seen, job) = collect(handoff)
            awaitOpen(handoff)
            handoff.append(next(refs[0]))
            seen.take(1)
            val headsBefore = log.headReads(0)
            // Another node reserves its sequence: the probe names the partition, and the woken tail's read finds nothing.
            reserved.set(true)
            eventually { log.headReads(0) > headsBefore }
            // The other node's stream lands.
            store.append(next(refs[1]))
            // Within the backstop's ten seconds it would come anyway: a few probe rounds is the bound held here.
            withTimeoutOrNull(PROBE * 10) { seen.receive() }.assert()
                .describedAs("another node's entry, past the read that found nothing").isNotNull()
            job.cancelAndJoin()
        }

    @Test
    fun `a tail whose read brought nothing is woken once more, and no more`(): Unit = runTest {
        val calls = AtomicInteger()
        // A block named once, whose stream never lands (its node stopped between the two writes).
        val log = ProbedLog(store, named = { if (calls.incrementAndGet() == 1) setOf(0) else emptySet() })
        val prober = HandoffProber(log, "bank", HandoffOptions(probeInterval = PROBE), backgroundScope)
        val tail = FakeTail(partition = 0).apply { idle = PROBE }
        prober.register(tail)
        advanceTimeBy(PROBE / 2 + 1.milliseconds)
        tail.wakes.assert().containsExactly(Reason.IDLE)
        // Its cursor did not move: one more read the next round, then nothing; the backstop's interval is far off.
        advanceTimeBy(PROBE * 3)
        tail.wakes.assert().containsExactly(Reason.IDLE, Reason.IDLE)
        prober.unregister(tail)
    }

    @Test
    fun `a probe or a backstop read that fails is the next round's to do`(): Unit = runTest {
        val probes = AtomicInteger()
        val backstops = AtomicInteger()
        val log = object : EventLog by store {
            override suspend fun appendedElsewhere(cursors: Map<Int, Long>): Set<Int> =
                when (probes.incrementAndGet()) {
                    2 -> throw IllegalStateException("The store is down for this probe.")
                    3 -> setOf(0)
                    else -> emptySet()
                }

            override suspend fun heads(partitions: Collection<Int>): Map<Int, Long?> =
                when (backstops.incrementAndGet()) {
                    1 -> throw IllegalStateException("The store is down for this backstop.")
                    2 -> mapOf(0 to null)
                    else -> mapOf(0 to 5L)
                }
        }
        // A round every 20 ms, the backstop due every 40 ms from the last one that read the heads.
        val options = HandoffOptions(probeInterval = PROBE, backstopInterval = PROBE)
        val prober = HandoffProber(log, "bank", options, backgroundScope, testTimeSource)
        val tail = FakeTail(partition = 0).apply { idle = PROBE }
        prober.register(tail)
        // 20 ms: nothing named. 40 ms: the backstop is due, and the probe fails before it: nothing is woken or read.
        advanceTimeBy(PROBE + 1.milliseconds)
        tail.wakes.assert().isEmpty()
        backstops.get().assert().isZero()
        // 60 ms: the probe names the partition, and the backstop, still due, is read, and fails.
        advanceTimeBy(PROBE / 2)
        tail.wakes.assert().containsExactly(Reason.IDLE)
        backstops.get().assert().isEqualTo(1)
        // Its read brought the other node's entry: no second read of it.
        tail.cursor = 1
        // 80 ms: still due, the backstop finds no head, so nothing past the cursor.
        advanceTimeBy(PROBE / 2)
        backstops.get().assert().isEqualTo(2)
        tail.wakes.assert().containsExactly(Reason.IDLE)
        // 100 ms: not due again until 120 ms, where it finds a head past the cursor that the probe did not name.
        advanceTimeBy(PROBE / 2)
        backstops.get().assert().isEqualTo(2)
        advanceTimeBy(PROBE / 2)
        tail.wakes.assert().containsExactly(Reason.IDLE, Reason.BACKSTOP)
        probes.get().assert().isEqualTo(6)
        backstops.get().assert().isEqualTo(3)
        prober.unregister(tail)
    }

    @Test
    fun `a call that starts failing warns once, and says when it answers again`(): Unit = runTest {
        val lines = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(HandoffProber::class.java) as Logger
        logger.addAppender(lines)
        try {
            val probes = AtomicInteger()
            val backstops = AtomicInteger()
            val log = object : EventLog by store {
                override suspend fun appendedElsewhere(cursors: Map<Int, Long>): Set<Int> {
                    check(probes.incrementAndGet() > 3) { "The counters are down." }
                    return emptySet()
                }

                override suspend fun heads(partitions: Collection<Int>): Map<Int, Long?> {
                    check(backstops.incrementAndGet() > 2) { "The heads are down." }
                    return emptyMap()
                }
            }
            val options = HandoffOptions(probeInterval = PROBE, backstopInterval = PROBE)
            val prober = HandoffProber(log, "ledger", options, backgroundScope, testTimeSource)
            val tail = FakeTail(partition = 0)
            prober.register(tail)
            // The probe fails at 20, 40 and 60 ms and answers at 80; the backstop, due since 40, fails at 80 and 100 ms
            // and answers at 120.
            advanceTimeBy(PROBE * 3 + 1.milliseconds)
            backstops.get().assert().isEqualTo(3)
            val said = lines.list.filter { it.level.isGreaterOrEqual(Level.INFO) && "of ledger" in it.formattedMessage }
            said.map { it.level }.assert().containsExactly(Level.WARN, Level.INFO, Level.WARN, Level.INFO)
            said[0].formattedMessage.assert().startsWith("The probe of the tails of ledger fails")
            said[0].throwableProxy.message.assert().isEqualTo("The counters are down.")
            said[1].formattedMessage.assert().startsWith("The probe of the tails of ledger answers again, after 60")
            said[2].formattedMessage.assert().startsWith("The backstop read of the heads of the tails of ledger fails")
            said[2].throwableProxy.message.assert().isEqualTo("The heads are down.")
            said[3].formattedMessage.assert()
                .startsWith("The backstop read of the heads of the tails of ledger answers again, after 40")
            prober.unregister(tail)
        } finally {
            logger.detachAppender(lines)
        }
    }

    @Test
    fun `the backstop finds what the probe did not name, and says so once`(): Unit = runBlocking {
        val warnings = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(HandoffLog::class.java) as Logger
        logger.addAppender(warnings)
        try {
            val log = ProbedLog(store, named = { emptySet() })
            val handoff = probed(log, backstop = 200.milliseconds)
            val (seen, job) = collect(handoff)
            eventually { log.asked.size >= 1 }
            store.append(next(refs[0]))
            seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
            reads.reasons.assert().containsExactly(Reason.BACKSTOP)
            log.headsRead.get().assert().isGreaterThanOrEqualTo(1)
            job.cancelAndJoin()
            warnings.list.filter { it.level == Level.WARN }.map { it.formattedMessage }.assert().singleElement()
                .asString().contains("partition 0 of bank").contains("a node wrote to a partition it no longer owned")
        } finally {
            logger.detachAppender(warnings)
        }
    }

    @Test
    fun `the probe stops with the node's last tail`(): Unit = runBlocking {
        val handoff = probed(ProbedLog(store))
        val (_, job) = collect(handoff)
        eventually { handoff.probeRounds >= 2 }
        job.cancelAndJoin()
        val stopped = handoff.probeRounds
        kotlinx.coroutines.delay(PROBE * 3)
        handoff.probeRounds.assert().isLessThanOrEqualTo(stopped + 1)
    }

    private companion object {
        val PROBE = 40.milliseconds
    }
}

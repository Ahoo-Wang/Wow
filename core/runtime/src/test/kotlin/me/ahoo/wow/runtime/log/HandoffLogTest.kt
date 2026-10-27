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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HandoffLogTest {
    private val type = AggregateType("bank", "account")
    private val store = RecordingLog(InMemoryEventLog(PARTITIONS))
    private val reads = ReadsObserver()
    private val versions = ConcurrentHashMap<AggregateRef, AtomicInteger>()

    private fun handoff(
        capacity: Int = 1_024,
        // Long enough that only a real gap or an idle partition reads the store, however loaded the machine is.
        gapWait: Duration = 10.seconds,
        probeInterval: Duration = 10.seconds,
        log: EventLog = store,
    ) = HandoffLog(log, "bank", HandoffOptions(capacity, gapWait, probeInterval), reads)

    /** Aggregate ids that all land in partition 0. */
    private val refs: List<AggregateRef> = generateSequence(0) { it + 1 }
        .map { type.ref("tenant", "account-$it") }
        .filter { Partitioner.partitionOf(it, PARTITIONS) == 0 }
        .take(8)
        .toList()

    private fun next(ref: AggregateRef = refs[0]): EventStreamRecord {
        val version = versions.computeIfAbsent(ref) { AtomicInteger() }.incrementAndGet()
        return EventStreamRecord(
            ref = ref,
            version = version,
            commandId = "command-${ref.id}-$version",
            requestId = "${ref.id}-$version",
            commandType = "bank.account.Deposit",
            commitTime = 1L,
            events = listOf(EventRecord("bank.account.Deposited", 1, """{"amount":$version}""")),
            scope = Scope.NONE,
        )
    }

    private fun transport(n: Int) = TransportCodec.encode(Transport(trace = mapOf("traceparent" to "trace-$n")))

    /** Collects partition 0's tail of [log] from [after] into a channel, on another dispatcher. */
    private fun CoroutineScope.collect(log: EventLog, after: Long? = null): Pair<Channel<LogEntry>, Job> {
        val seen = Channel<LogEntry>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { log.tail(0, after).collect { seen.send(it) } }
        return seen to job
    }

    private suspend fun Channel<LogEntry>.take(count: Int): List<LogEntry> =
        withTimeout(10.seconds) { List(count) { receive() } }

    /** Waits until [log] has a tail open in partition 0, so what is appended next is handed to it. */
    private suspend fun awaitOpen(log: HandoffLog, tails: Int = 1) = withTimeout(10.seconds) {
        while (store.heads.get() < tails) {
            delay(1)
        }
        log.storeReads
    }

    @Test
    fun `a node's own commits reach an open tail from memory, without reading the store`(): Unit = runBlocking {
        val log = handoff()
        val (seen, job) = collect(log)
        awaitOpen(log)
        val appended = (1..20).map { n ->
            val stream = next(refs[n % refs.size])
            log.append(stream, transport(n))
            stream
        }
        val entries = seen.take(20)
        job.cancelAndJoin()

        entries.map { it.position.sequence }.assert().isEqualTo((1L..20L).toList())
        entries.map { it.stream }.assert().isEqualTo(appended)
        entries.map { it.decodedTransport?.trace }.assert()
            .isEqualTo((1..20).map { mapOf("traceparent" to "trace-$it") })
        store.tails.get().assert().isEqualTo(0)
        log.storeReads.assert().isEqualTo(0L)
        log.delivered.assert().isEqualTo(20L)
        reads.reasons.assert().isEmpty()
    }

    @Test
    fun `an entry handed over is what a read of the store gives`(): Unit = runBlocking {
        val log = handoff()
        val (seen, job) = collect(log)
        awaitOpen(log)
        log.appendAll(listOf(next(refs[0]) to transport(1), next(refs[1]) to null))
        val handed = seen.take(2)
        job.cancelAndJoin()

        handed.assert().isEqualTo(withTimeout(10.seconds) { store.tail(0).takeTwo() })
    }

    @Test
    fun `a tail opened behind the head reads the store up to it, then goes on from memory`(): Unit = runBlocking {
        repeat(5) { store.append(next(refs[it])) }
        val log = handoff()
        val (seen, job) = collect(log, after = 2)
        seen.take(3).map { it.position.sequence }.assert().isEqualTo(listOf(3L, 4L, 5L))
        repeat(5) { log.append(next(refs[it])) }
        seen.take(5).map { it.position.sequence }.assert().isEqualTo((6L..10L).toList())
        job.cancelAndJoin()

        store.tails.get().assert().isEqualTo(1)
        reads.reasons.assert().containsExactly(HandoffLog.START)
        log.delivered.assert().isEqualTo(5L)
    }

    @Test
    fun `another node's append leaves a gap that is read from the store, in sequence order`(): Unit = runBlocking {
        val log = handoff(gapWait = 20.milliseconds)
        val (seen, job) = collect(log)
        awaitOpen(log)
        log.append(next(refs[0]))
        // Another node appends to the partition during a hand-off (decision D71): this one hands nothing over.
        store.append(next(refs[1]))
        log.append(next(refs[2]))
        log.append(next(refs[3]))
        val entries = seen.take(4)
        job.cancelAndJoin()

        entries.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L, 3L, 4L))
        entries[1].stream.ref.assert().isEqualTo(refs[1])
        reads.reasons.assert().containsExactly(HandoffLog.GAP)
    }

    @Test
    fun `a commit whose outcome was unknown is read from the store when the next commit leaves a gap`(): Unit =
        runBlocking {
            val unknown = UnknownOnce(store)
            val log = handoff(gapWait = 20.milliseconds, log = unknown)
            val (seen, job) = collect(log)
            awaitOpen(log)
            unknown.next = true
            log.append(next(refs[0])).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
            log.append(next(refs[1]))
            val entries = seen.take(2)
            job.cancelAndJoin()

            entries.map { it.stream.ref }.assert().isEqualTo(listOf(refs[0], refs[1]))
            reads.reasons.assert().containsExactly(HandoffLog.GAP)
        }

    @Test
    fun `an idle tail finds what other nodes appended with a probe of the head`(): Unit = runBlocking {
        val log = handoff(probeInterval = 20.milliseconds)
        val (seen, job) = collect(log)
        awaitOpen(log)
        store.append(next(refs[0]))
        seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        job.cancelAndJoin()

        reads.reasons.assert().containsExactly(HandoffLog.IDLE)
        // Probes that find the head where the tail is read nothing.
        store.tails.get().assert().isEqualTo(1)
    }

    @Test
    fun `an idle probe that finds this node's own commit before its append returns should wait for its hand-over`(): Unit =
        runBlocking {
            // The commit shows in the head while its append is still on its way back (a store's answer in flight): the
            // probe must not read what the append is about to hand over.
            val gated = GateAfterCommit(store)
            val log = handoff(probeInterval = 20.milliseconds, log = gated)
            val (seen, job) = collect(log)
            awaitOpen(log)
            val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
            gated.committed.await()
            // Several probes run while the append is held.
            delay(200)
            gated.release.complete(Unit)
            held.await()
            seen.take(1).single().stream.ref.assert().isEqualTo(refs[0])
            job.cancelAndJoin()

            log.storeReads.assert().isEqualTo(0L)
            log.delivered.assert().isEqualTo(1L)
            reads.reasons.assert().isEmpty()
        }

    @Test
    fun `commits handed over out of order are delivered in sequence order from memory`(): Unit = runBlocking {
        // Appends without a queue run side by side: the first commits, the second commits and returns before it.
        val held = HoldFirst(store)
        val log = handoff(gapWait = 5.seconds, log = held)
        val (seen, job) = collect(log)
        awaitOpen(log)
        val first = async(Dispatchers.Default) { log.append(next(refs[0])) }
        held.committed.await()
        log.append(next(refs[1]))
        held.release.complete(Unit)
        first.await()
        val entries = seen.take(2)
        job.cancelAndJoin()

        entries.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
        store.tails.get().assert().isEqualTo(0)
        reads.reasons.assert().isEmpty()
    }

    @Test
    fun `a tail too slow for its hand-overs drops them and reads the store, never holding up an append`(): Unit =
        runBlocking {
            val log = handoff(capacity = 2)
            val gate = CompletableDeferred<Unit>()
            val seen = CopyOnWriteArrayList<Long>()
            val job = launch(Dispatchers.Default) {
                log.tail(0).collect {
                    gate.await()
                    seen += it.position.sequence
                }
            }
            awaitOpen(log)
            // The tail takes the first and waits: the appends complete all the same.
            withTimeout(10.seconds) { repeat(10) { log.append(next(refs[it % refs.size])) } }
            gate.complete(Unit)
            withTimeout(10.seconds) {
                while (seen.size < 10) {
                    delay(1)
                }
            }
            job.cancelAndJoin()

            seen.assert().isEqualTo((1L..10L).toList())
            reads.reasons.assert().contains(HandoffLog.OVERFLOW)
        }

    @Test
    fun `a pending run longer than the capacity overflows to the store`(): Unit = runBlocking {
        val gated = GateFirst(store)
        val log = handoff(capacity = 2, log = gated)
        val (seen, job) = collect(log)
        awaitOpen(log)
        // An append of this node in flight, not committed yet: the tail waits for it while the gap lasts.
        val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
        gated.entered.await()
        store.append(next(refs[1]))
        // Three entries past the missing one: more than the tail holds while it waits for the gap to fill.
        repeat(3) { log.append(next(refs[it + 2])) }
        seen.take(4).map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L, 3L, 4L))
        gated.release.complete(Unit)
        held.await()
        seen.take(1).single().position.sequence.assert().isEqualTo(5L)
        job.cancelAndJoin()

        reads.reasons.assert().containsExactly(HandoffLog.OVERFLOW)
    }

    @Test
    fun `a commit whose append returns long after the next ones is still delivered from memory`(): Unit =
        runBlocking {
            // A pause of the process (a GC, a starved CPU) holds an append's return past any short timer (OV-2):
            // the tail waits for the append itself, not the clock.
            val held = HoldFirst(store)
            val log = handoff(gapWait = 1.seconds, log = held)
            val (seen, job) = collect(log)
            awaitOpen(log)
            val first = async(Dispatchers.Default) { log.append(next(refs[0])) }
            held.committed.await()
            repeat(20) { log.append(next(refs[it % (refs.size - 1) + 1])) }
            delay(400)
            held.release.complete(Unit)
            first.await()
            seen.take(21).map { it.position.sequence }.assert().isEqualTo((1L..21L).toList())
            job.cancelAndJoin()

            reads.reasons.assert().isEmpty()
            store.tails.get().assert().isEqualTo(0)
        }

    @Test
    fun `a gap no append of this node can fill is read from the store at once, without the gap wait`(): Unit =
        runBlocking {
            val log = handoff(gapWait = 30.seconds)
            val (seen, job) = collect(log)
            awaitOpen(log)
            store.append(next(refs[0]))
            log.append(next(refs[1]))
            withTimeout(5.seconds) { seen.take(2) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
            job.cancelAndJoin()

            reads.reasons.assert().containsExactly(HandoffLog.GAP)
        }

    @Test
    fun `a gap is read at once even when the append after it closes only once the tail waits`(): Unit = runBlocking {
        // The tail may take an append's entry before that append marks itself returned: its return wakes the tail.
        repeat(ROUNDS) { round ->
            val store = InMemoryEventLog(PARTITIONS)
            val log = HandoffLog(store, "bank", HandoffOptions(gapWait = 30.seconds))
            val (seen, job) = collect(log)
            delay(2)
            store.append(next(refs[0]).copyWith(version = 1, requestId = "other-$round"))
            log.append(next(refs[1]).copyWith(version = 1, requestId = "own-$round"))
            withTimeout(5.seconds) { List(2) { seen.receive() } }.map { it.position.sequence }
                .assert().isEqualTo(listOf(1L, 2L))
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a gap waits for an append of this node in flight, and is read once it returns without it`(): Unit =
        runBlocking {
            val gated = GateFirst(store)
            val log = handoff(gapWait = 30.seconds, log = gated)
            val (seen, job) = collect(log)
            awaitOpen(log)
            val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
            gated.entered.await()
            store.append(next(refs[1]))
            log.append(next(refs[2]))
            delay(200)
            // The append in flight may still hand the missing sequence over: nothing read yet.
            reads.reasons.assert().isEmpty()
            gated.release.complete(Unit)
            held.await()
            withTimeout(5.seconds) { seen.take(3) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L, 3L))
            job.cancelAndJoin()

            reads.reasons.assert().containsExactly(HandoffLog.GAP)
        }

    @Test
    fun `a gap is capped by the gap wait while an append of this node does not return`(): Unit = runBlocking {
        val gated = GateFirst(store)
        val log = handoff(gapWait = 50.milliseconds, log = gated)
        val (seen, job) = collect(log)
        awaitOpen(log)
        val held = async(Dispatchers.Default) { log.append(next(refs[0])) }
        gated.entered.await()
        store.append(next(refs[1]))
        log.append(next(refs[2]))
        withTimeout(5.seconds) { seen.take(2) }.map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
        reads.reasons.assert().containsExactly(HandoffLog.GAP)
        gated.release.complete(Unit)
        held.await()
        job.cancelAndJoin()
    }

    @Test
    fun `a group whose outcomes are not all commits wakes a tail waiting on a gap`(): Unit = runBlocking {
        val gated = GateFirst(store, armed = false)
        val log = handoff(gapWait = 30.seconds, log = gated)
        val (seen, job) = collect(log)
        awaitOpen(log)
        val stream = next(refs[0])
        log.append(stream)
        seen.take(1)
        gated.arm()
        // A group held in flight, then answered with a duplicate and a commit, while another node leaves a gap.
        val group = async(Dispatchers.Default) { log.appendAll(listOf(stream to null, next(refs[1]) to null)) }
        gated.entered.await()
        store.append(next(refs[2]))
        log.append(next(refs[3]))
        gated.release.complete(Unit)
        group.await().map { it::class }.assert()
            .containsExactly(AppendOutcome.AlreadyCommitted::class, AppendOutcome.Committed::class)
        withTimeout(5.seconds) { seen.take(3) }.map { it.position.sequence }.assert().isEqualTo(listOf(2L, 3L, 4L))
        job.cancelAndJoin()

        reads.reasons.assert().containsExactly(HandoffLog.GAP)
    }

    @Test
    fun `a tail closed and opened again, as a partition handed off and acquired, reads only what it missed`(): Unit =
        runBlocking {
            val log = handoff()
            val (first, job) = collect(log)
            awaitOpen(log)
            repeat(3) { log.append(next(refs[it])) }
            val last = first.take(3).last().position.sequence
            job.cancelAndJoin()
            // Appended while no tail is open here: no one to hand it to.
            repeat(2) { log.append(next(refs[it])) }

            val (again, rejoined) = collect(log, after = last)
            again.take(2).map { it.position.sequence }.assert().isEqualTo(listOf(4L, 5L))
            log.append(next(refs[5]))
            again.take(1).single().position.sequence.assert().isEqualTo(6L)
            rejoined.cancelAndJoin()

            reads.reasons.assert().containsExactly(HandoffLog.START)
            store.tails.get().assert().isEqualTo(1)
        }

    @Test
    fun `every open tail of the partition gets each commit, those of other partitions none`(): Unit = runBlocking {
        val log = handoff()
        val (one, first) = collect(log)
        val (two, second) = collect(log)
        awaitOpen(log, tails = 2)
        val other = generateSequence(0) { it + 1 }.map { type.ref("tenant", "x-$it") }
            .first { Partitioner.partitionOf(it, PARTITIONS) == 1 }
        log.append(next(other))
        log.append(next(refs[0]))
        one.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        two.take(1).single().stream.ref.assert().isEqualTo(refs[0])
        first.cancelAndJoin()
        second.cancelAndJoin()

        store.tails.get().assert().isEqualTo(0)
    }

    @Test
    fun `outcomes other than committed hand nothing over`(): Unit = runBlocking {
        val log = handoff()
        val (seen, job) = collect(log)
        awaitOpen(log)
        val stream = next(refs[0])
        log.append(stream)
        log.append(stream).assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
        log.append(stream.copyWith(requestId = "other")).assert().isInstanceOf(AppendOutcome.Conflict::class.java)
        log.append(next(refs[0]))
        seen.take(2).map { it.position.sequence }.assert().isEqualTo(listOf(1L, 2L))
        job.cancelAndJoin()

        reads.reasons.assert().isEmpty()
    }

    @Test
    fun `a tail of a partition the log does not have is refused`() {
        assertThrows<IllegalArgumentException> { handoff().tail(PARTITIONS, null) }
    }

    @Test
    fun `options are checked and compared by value`() {
        assertThrows<IllegalArgumentException> { HandoffOptions(capacity = 0) }
        assertThrows<IllegalArgumentException> { HandoffOptions(gapWait = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { HandoffOptions(probeInterval = Duration.ZERO) }
        HandoffOptions().assert().isEqualTo(HandoffOptions())
        HandoffOptions().hashCode().assert().isEqualTo(HandoffOptions().hashCode())
        HandoffOptions(capacity = 1).assert().isNotEqualTo(HandoffOptions())
        HandoffOptions().toString().assert().contains("capacity=1024")
    }

    private suspend fun Flow<LogEntry>.takeTwo(count: Int = 2): List<LogEntry> = take(count).toList()

    private companion object {
        const val PARTITIONS = 4
        const val ROUNDS = 200
    }
}

/** A log that counts the tails opened on it and the heads read. */
private class RecordingLog(private val delegate: EventLog) : EventLog by delegate {
    val tails = AtomicInteger()
    val heads = AtomicInteger()

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> {
        tails.incrementAndGet()
        return delegate.tail(partition, after)
    }

    /** Counted once the read returned: a commit after it is handed to the tail that read it. */
    override suspend fun head(partition: Int): Long? = delegate.head(partition).also { heads.incrementAndGet() }
}

/** A log whose append commits and answers [AppendOutcome.Unknown] when [next] is set, as after a timeout. */
private class UnknownOnce(private val delegate: EventLog) : EventLog by delegate {
    @Volatile
    var next = false

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        val outcome = delegate.append(stream, transport)
        if (next) {
            next = false
            return AppendOutcome.Unknown(IllegalStateException("timed out"))
        }
        return outcome
    }
}

/** A log whose first append commits, then returns only once [release] completes. */
private class HoldFirst(private val delegate: EventLog) : EventLog by delegate {
    val committed = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        val outcome = delegate.append(stream, transport)
        if (calls.incrementAndGet() == 1) {
            committed.complete(Unit)
            release.await()
        }
        return outcome
    }
}

/** Records the reason of every read of the store a tail made. */
private class ReadsObserver : Observer {
    val reasons = CopyOnWriteArrayList<String>()

    override fun tailRead(context: String, partition: Int, reason: String) {
        reasons += reason
    }
}

/**
 * A log whose first append (or group) once [armed] waits for [release] before it reaches the store: in flight, not
 * committed.
 */
/** Holds the first append after the store committed it, until [release]: its commit shows in the head meanwhile. */
private class GateAfterCommit(private val delegate: EventLog) : EventLog by delegate {
    val committed = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
        delegate.append(stream, transport).also {
            if (calls.incrementAndGet() == 1) {
                committed.complete(Unit)
                release.await()
            }
        }
}

private class GateFirst(private val delegate: EventLog, armed: Boolean = true) : EventLog by delegate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val calls = AtomicInteger(if (armed) 0 else Int.MIN_VALUE)

    fun arm() = calls.set(0)

    private suspend fun gate() {
        if (calls.incrementAndGet() == 1) {
            entered.complete(Unit)
            release.await()
        }
    }

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        gate()
        return delegate.append(stream, transport)
    }

    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        gate()
        return delegate.appendAll(streams)
    }
}

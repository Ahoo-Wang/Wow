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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.Observer
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a node hands its own commits to its tails in memory ([enabled], decision D249), and how:
 *
 * - [capacity]: the most entries one tail holds that it has not taken yet; a tail that falls further behind drops what
 *   comes next and reads the store instead, so the append path never waits for it;
 * - [gapWait]: how long a tail waits for a missing sequence before it reads the store from its position
 *   (`wow.log.handoff.gap-wait`). Appends without a queue (PostgreSQL's preset) hand their commits over in the order
 *   their callers return, so a sequence is often missing for a moment; a pause of the process (a GC, a starved CPU)
 *   stretches that moment. A real gap (another node's append during a hand-off, an unknown outcome) is rare, and the
 *   wait only delays what follows it, so it is long enough to ride out a pause: 200 ms, below the longest poll
 *   interval that bounds how late another node's entries are read anyway;
 * - [probeInterval]: how long a tail waits without a commit of this node before it checks the partition's head for
 *   entries other nodes appended: the log's longest poll interval (`wow.log.max-poll-interval`).
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class HandoffOptions @InternalWowApi constructor(
    val enabled: Boolean = true,
    val capacity: Int = DEFAULT_CAPACITY,
    val gapWait: Duration = 200.milliseconds,
    val probeInterval: Duration = 1.seconds,
) {
    init {
        require(capacity >= 1) { "A tail holds at least one entry handed over." }
        require(gapWait.isPositive()) { "The gap wait is positive." }
        require(probeInterval.isPositive()) { "The probe interval is positive." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is HandoffOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "HandoffOptions(enabled=$enabled, capacity=$capacity, gapWait=$gapWait, probeInterval=$probeInterval)"

    private fun valueFields(): List<Any?> = listOf(enabled, capacity, gapWait, probeInterval)

    companion object {
        private const val DEFAULT_CAPACITY = 1_024
    }
}

/**
 * A log whose own commits reach its tails in memory (decision D249): each stream an append of this node commits is
 * handed, as the entry [EventLog.tail] would read (its position, the stream and its transport as appended), to the
 * tails of its partition open on this node, so the partition owner's subscriptions do not read back what it has just
 * written.
 *
 * The log stays the only source of truth. A tail delivers entries in sequence order and only the run contiguous with
 * its position: it reads the store from that position whenever memory cannot continue the run, and drops what it has
 * delivered already, so losing a hand-over costs a read of the store and never an entry:
 *
 * - on opening (a start, a restart, a partition acquired): up to the partition's head;
 * - on a gap left for [HandoffOptions.gapWait] (another node's append during a hand-off, decision D71; a commit whose
 *   outcome was [AppendOutcome.Unknown]);
 * - on an overflow: a tail holding [HandoffOptions.capacity] entries it has not taken drops the next ones, so a slow
 *   subscription never holds up an append;
 * - after [HandoffOptions.probeInterval] without a hand-over, when the head is past its position: entries other nodes
 *   appended.
 *
 * Each read of the store is reported to [observer] ([Observer.tailRead]) with its reason. A node that does not own a
 * partition opens no tail of it, so what it appends there (Local execution, D71) is handed to no one.
 */
@InternalWowApi
class HandoffLog(
    private val log: EventLog,
    private val context: String,
    private val options: HandoffOptions = HandoffOptions(),
    private val observer: Observer = Observer.NONE,
) : EventLog by log {
    private val listeners = List(log.partitions) { CopyOnWriteArrayList<Listener>() }
    private val reads = AtomicLong()
    private val handedOver = AtomicLong()

    /** The reads of the store the tails made: one each time memory could not continue a tail's run. */
    val storeReads: Long get() = reads.get()

    /** The entries the tails delivered from memory, not read from the store. */
    val delivered: Long get() = handedOver.get()

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        log.append(stream, transport).also { handOver(it, stream, transport) }

    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> =
        log.appendAll(streams).also { outcomes ->
            outcomes.forEachIndexed { index, outcome ->
                streams.getOrNull(index)?.let { (stream, transport) -> handOver(outcome, stream, transport) }
            }
        }

    private fun handOver(outcome: AppendOutcome, stream: EventStreamRecord, transport: Transport?) {
        if (outcome !is AppendOutcome.Committed) {
            return
        }
        val tails = listeners[outcome.position.partition]
        if (tails.isEmpty()) {
            return
        }
        val entry = LogEntry(outcome.position, stream, transport)
        tails.forEach { it.offer(entry) }
    }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> {
        if (!options.enabled) {
            return log.tail(partition, after)
        }
        require(partition in 0 until log.partitions) { "No partition $partition among ${log.partitions}." }
        return flow {
            val listener = Listener(options.capacity)
            listeners[partition] += listener
            try {
                Tail(partition, after ?: 0L, listener).run(this)
            } finally {
                listeners[partition] -= listener
            }
        }
    }

    /** One tail's hand-overs: whatever does not fit is dropped, and the tail told to read the store. */
    private class Listener(capacity: Int) {
        val entries = Channel<LogEntry>(capacity)
        val overflowed = AtomicBoolean()

        fun offer(entry: LogEntry) {
            if (!entries.trySend(entry).isSuccess) {
                overflowed.set(true)
            }
        }
    }

    /** One open tail: alternates reading the store up to the head and continuing its run from memory. */
    private inner class Tail(private val partition: Int, private var cursor: Long, private val listener: Listener) {
        /** Entries handed over past the next one the run needs, by sequence. */
        private val pending = TreeMap<Long, LogEntry>()

        suspend fun run(collector: FlowCollector<LogEntry>) {
            var reason = START
            while (true) {
                collector.fromStore(reason)
                reason = collector.fromMemory()
            }
        }

        /** Reads the store from the cursor up to the partition's head, when the head is past it. */
        private suspend fun FlowCollector<LogEntry>.fromStore(reason: String) {
            // Cleared before the head is read: an entry dropped before this was committed before it, so at or below it.
            listener.overflowed.set(false)
            val head = log.head(partition) ?: 0L
            if (head > cursor) {
                reads.incrementAndGet()
                observer.tailRead(context, partition, reason)
                log.tail(partition, cursor.takeIf { it > 0 })
                    .transformWhile {
                        emit(it)
                        it.position.sequence < head
                    }
                    .collect { deliver(it) }
            }
            pending.headMap(cursor, true).clear()
        }

        /** Continues the run from memory; returns why the store must be read. */
        private suspend fun FlowCollector<LogEntry>.fromMemory(): String {
            while (true) {
                while (true) {
                    val next = pending.remove(cursor + 1) ?: break
                    deliver(next)
                    handedOver.incrementAndGet()
                }
                if (listener.overflowed.get()) {
                    return OVERFLOW
                }
                if (pending.isEmpty()) {
                    val entry = withTimeoutOrNull(options.probeInterval) { listener.entries.receive() } ?: return IDLE
                    hold(entry)
                } else {
                    withTimeoutOrNull(options.gapWait) {
                        while (!pending.containsKey(cursor + 1) && !listener.overflowed.get()) {
                            hold(listener.entries.receive())
                        }
                    } ?: return GAP
                }
            }
        }

        private fun hold(entry: LogEntry) {
            val sequence = entry.position.sequence
            if (sequence <= cursor) {
                return
            }
            pending[sequence] = entry
            if (pending.size > options.capacity) {
                pending.clear()
                listener.overflowed.set(true)
            }
        }

        private suspend fun FlowCollector<LogEntry>.deliver(entry: LogEntry) {
            if (entry.position.sequence > cursor) {
                emit(entry)
                cursor = entry.position.sequence
            }
        }
    }

    companion object {
        /** A tail opened: it reads what is committed before it continues from memory. */
        const val START = "start"

        /** A sequence missing from memory past the gap wait. */
        const val GAP = "gap"

        /** More entries handed over than the tail holds. */
        const val OVERFLOW = "overflow"

        /** No hand-over for the probe interval, and entries past the tail in the store. */
        const val IDLE = "idle"
    }
}

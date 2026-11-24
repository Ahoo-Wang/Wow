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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.TailRead.Reason
import me.ahoo.wow.spi.observation.Observer
import java.util.TreeMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How a node hands its own commits to its tails in memory, always: the constants of the hand-off,
 * not settings:
 *
 * - [capacity]: the most entries one tail holds that it has not taken yet; a tail that falls further behind drops what
 *   comes next and reads the store instead, so the append path never waits for it;
 * - [gapWait]: the longest a tail waits for a missing sequence that an append of this node still in flight may
 *   hold, before it reads the store from its position. Appends
 *   hand their commits over in the order their callers return, so a sequence is often missing
 *   for a moment, and a pause of the process (a GC, a starved CPU) stretches that moment past any timer.
 * So a tail does not wait on the clock: it waits for the appends that may hold the missing sequence, those
 *   in flight when the gap appeared, and reads the store as soon as every one of them returned without it (another
 *   node's append during a hand-off; an unknown outcome). The wait only bounds an append that does not
 *   return: 1 s, the longest poll interval, which bounds how late another node's entries are read anyway;
 * - [probeInterval]: how late a tail may see an entry another node appended through a block or reservation this node
 *   has not seen: the log's longest poll interval (`wow.log.max-poll-interval`). The node probes all its tails at once
 *   every half of it ([HandoffProber]);
 * - [backstopInterval]: how often the node also reads the exact heads of its tails' partitions, for what the probe's
 *   answer may leave out (an entry appended into a block already seen, by a node that went on writing after the
 *   partition moved): 10 s, the node sampler's cadence, at which one batched read is noise, or the probe interval
 *   when that is longer;
 * - [scheduledProbes]: how long after it opens a tail checks the head every [probeInterval] whatever this node
 *   commits ([ObservedEvent.TailRead.Reason.SCHEDULED]). A tail opens as its node acquires the partition, while other
 *   nodes may still append there without the lease, their routes not naming the new owner yet; this node keeps
 *   committing, so without it the first probe could wait for a pause, or for the end of its block. Zero, the default,
 *   for none: a node without a cluster ([me.ahoo.wow.runtime.node.ContextAssembly] sets it for one).
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class HandoffOptions @InternalWowApi constructor(
    val capacity: Int = DEFAULT_CAPACITY,
    val gapWait: Duration = 1.seconds,
    val probeInterval: Duration = 1.seconds,
    val scheduledProbes: Duration = Duration.ZERO,
    val backstopInterval: Duration = maxOf(DEFAULT_BACKSTOP, probeInterval),
) {
    init {
        require(capacity >= 1) { "A tail holds at least one entry handed over." }
        require(gapWait.isPositive()) { "The gap wait is positive." }
        require(probeInterval.isPositive()) { "The probe interval is positive." }
        require(!scheduledProbes.isNegative()) { "The scheduled probes last zero or more." }
        require(backstopInterval >= probeInterval) { "The backstop reads no more often than the probe." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is HandoffOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "HandoffOptions(capacity=$capacity, gapWait=$gapWait, probeInterval=$probeInterval, " +
            "scheduledProbes=$scheduledProbes, backstopInterval=$backstopInterval)"

    private fun valueFields(): List<Any?> = listOf(capacity, gapWait, probeInterval, scheduledProbes, backstopInterval)

    /** These options with [scheduledProbes] in place of their own. */
    @InternalWowApi
    fun copyWith(scheduledProbes: Duration): HandoffOptions =
        HandoffOptions(capacity, gapWait, probeInterval, scheduledProbes, backstopInterval)

    companion object {
        private const val DEFAULT_CAPACITY = 1_024

        /** The backstop's interval unless the probe's is longer: the node sampler's, at which one batched read is noise. */
        private val DEFAULT_BACKSTOP = 10.seconds
    }
}

/**
 * A log whose own commits reach its tails in memory: each stream an append of this node commits is
 * handed, as the entry [EventLog.tail] would read (its position, the stream and its transport as appended), to the
 * tails of its partition open on this node, so the partition owner's subscriptions do not read back what it has just
 * written.
 *
 * The log stays the only source of truth. A tail delivers entries in sequence order and only the run contiguous with
 * its position: it reads the store from that position whenever memory cannot continue the run, and drops what it has
 * delivered already, so losing a hand-over costs a read of the store and never an entry:
 *
 * - on opening (a start, a restart, a partition acquired): up to the partition's head;
 * - on a gap no append of this node can fill any more: every append in flight when the gap appeared returned, and
 *   none handed the missing sequence over (another node's append during a hand-off; a commit whose
 *   outcome was [AppendOutcome.Unknown]), or [HandoffOptions.gapWait] passed while one of them is still in flight;
 * - on an overflow: a tail holding [HandoffOptions.capacity] entries it has not taken drops the next ones, so a slow
 *   subscription never holds up an append;
 * - when the node's probe names its partition ([HandoffProber]: one call of the log for every tail, every half
 *   [HandoffOptions.probeInterval], [EventLog.appendedElsewhere]), or when the probe's backstop, an exact read of the
 *   heads every [HandoffOptions.backstopInterval], finds the head past its position while it has been idle a probe
 *   interval: entries other nodes appended; and, for [HandoffOptions.scheduledProbes] after it opens, every probe
 *   interval whatever the hand-overs, by a read of its own head,
 *   for the entries other nodes appended without the lease while their routes did not name this node yet. The head may also show a commit of this node whose append has not returned yet (the store's answer on its
 *   way back): while an append of this node is in flight, the probe waits for it to hand its entry over, as on a gap,
 *   delivers it, and reads the store at once for what the head shows past it (other nodes' entries), or when the
 *   append returns without the entry the tail needs.
 *
 * So another node's commit reaches a tail only at one of these reads: through a block or reservation the probe has
 * not seen, within a probe interval; appended into a block already seen (a node that went on writing after the
 * partition moved and the scheduled probes ended), within the backstop's interval, and its tail logs it, as a node
 * writing to a partition it no longer owned or a case the probe's answer missed. A command that would commit there
 * without the lease is sent to the partition's owner when its routes, read once more, name one
 * ([me.ahoo.wow.runtime.cluster.RouteLookup]), so such a commit takes an acquisition the read did not see; it comes
 * within a route refresh of the acquisition, while the tail's probes are still on schedule
 * ([HandoffOptions.scheduledProbes]), and is read at the next one. Past that window the floor above holds again.
 *
 * Each read of the store is reported to [observer] ([ObservedEvent.TailRead]) with its reason, and each entry it delivers
 * to [readBack]: among them are the commits of other nodes, which no hand-over brings. A node that does not own a
 * partition opens no tail of it, so what it appends there (Local execution) is handed to no one; its owner's tails
 * read it from the store.
 */
@InternalWowApi
class HandoffLog(
    private val log: EventLog,
    private val context: String,
    private val options: HandoffOptions = HandoffOptions(),
    private val observer: Observer = Observer.NONE,
    /** Where the node's probe of its tails runs while it has tails open ([HandoffProber]). */
    probes: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Called, from the tail's coroutine, for each entry a tail delivers from the store rather than from memory:
     * quick, and never throwing.
     */
    private val readBack: (LogEntry) -> Unit = {},
) : EventLog by log {
    private val prober = HandoffProber(log, context, options, probes)

    /** The rounds of the node's probe so far: for the tests. */
    internal val probeRounds: Long get() = prober.rounds

    private val listeners = List(log.partitions) { CopyOnWriteArrayList<Listener>() }
    private val inFlight = List(log.partitions) { InFlight() }
    private val reads = AtomicLong()
    private val handedOver = AtomicLong()

    /** The reads of the store the tails made: one each time memory could not continue a tail's run. */
    val storeReads: Long get() = reads.get()

    /** The entries the tails delivered from memory, not read from the store. */
    val delivered: Long get() = handedOver.get()

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        val partition = Partitioner.partitionOf(stream.ref, log.partitions)
        val ticket = inFlight[partition].open()
        var handed = false
        try {
            return log.append(stream, transport).also { handed = handOver(it, stream, transport) }
        } finally {
            settle(partition, ticket, handed)
        }
    }

    override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
        val tickets = streams.map { Partitioner.partitionOf(it.stream.ref, log.partitions) }.distinct()
            .map { it to inFlight[it].open() }
        var handed = false
        try {
            return log.appendAll(streams).also { outcomes ->
                handed = outcomes.size == streams.size
                outcomes.forEachIndexed { index, outcome ->
                    streams.getOrNull(index)?.let { entry ->
                        val stream = entry.stream
                        val transport = entry.transport
                        handed = handOver(outcome, stream, transport) && handed
                    }
                }
            }
        } finally {
            tickets.forEach { (partition, ticket) -> settle(partition, ticket, handed) }
        }
    }

    /**
     * Closes [ticket], after its commits were handed over, and nudges the tails waiting on a gap: one may be waiting for
     * this very append, which handed its entry over before it closed. One that handed nothing over (an outcome other
     * than committed, a failure) nudges every tail of the partition.
     */
    private fun settle(partition: Int, ticket: Long, handed: Boolean) {
        inFlight[partition].close(ticket)
        listeners[partition].forEach { if (!handed || it.waiting) it.nudge() }
    }

    /** Hands [outcome] over when it is a commit; whether it was one. */
    private fun handOver(outcome: AppendOutcome, stream: EventStreamRecord, transport: ByteArray?): Boolean {
        if (outcome !is AppendOutcome.Committed) {
            return false
        }
        val tails = listeners[outcome.position.partition]
        if (tails.isNotEmpty()) {
            val entry = LogEntry(outcome.position, stream, transport)
            tails.forEach { it.offer(entry) }
        }
        return true
    }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> {
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

    /**
     * The appends of one partition in flight on this node, by ticket in the order they started. A sequence missing from
     * a tail was committed before the entries after it, so only an append that started before the gap appeared can
     * hold it: once every ticket issued before then is closed, no append of this node will hand it over.
     */
    private class InFlight {
        private val issued = AtomicLong()
        private val open = ConcurrentSkipListSet<Long>()

        fun open(): Long = issued.getAndIncrement().also { open += it }

        fun close(ticket: Long) {
            open -= ticket
        }

        /** The tickets issued so far: those below it are the appends started until now. */
        fun horizon(): Long = issued.get()

        /** Whether every append started before [horizon] returned. */
        fun returnedBefore(horizon: Long): Boolean = open.ceiling(0L)?.let { it >= horizon } ?: true
    }

    /** One tail's hand-overs: whatever does not fit is dropped, and the tail told to read the store. */
    private class Listener(capacity: Int) {
        val entries = Channel<LogEntry>(capacity)
        val overflowed = AtomicBoolean()

        /** An append returned: a tail waiting on a gap looks again. */
        val nudges = Channel<Unit>(Channel.CONFLATED)

        /** Whether the tail is waiting on a gap, so that every append that returns nudges it. */
        @Volatile
        var waiting = false

        fun offer(entry: LogEntry) {
            if (!entries.trySend(entry).isSuccess) {
                overflowed.set(true)
            }
        }

        fun nudge() {
            nudges.trySend(Unit)
        }
    }

    /**
     * One open tail: alternates reading the store up to the head and continuing its run from memory, and reads the
     * store when the node's probe wakes it ([HandoffProber]).
     */
    private inner class Tail(
        override val partition: Int,
        cursor: Long,
        private val listener: Listener,
    ) : HandoffProber.Probed {
        /** Entries handed over past the next one the run needs, by sequence. */
        private val pending = TreeMap<Long, LogEntry>()

        @Volatile
        override var cursor: Long = cursor
            private set

        /** When memory or the store last brought it an entry. */
        @Volatile
        private var active = TimeSource.Monotonic.markNow()

        override val idleFor: Duration get() = active.elapsedNow()

        @Volatile
        override var owed: Boolean = false

        /** The probe's wakes: the latest, kept until the tail can take it, so none is lost. */
        private val wakes = Channel<Reason>(Channel.CONFLATED)

        override fun wake(reason: Reason) {
            wakes.trySend(reason)
        }

        /** When it opened, and last read the store: its probes are on schedule for a while after it opens. */
        private val opened = TimeSource.Monotonic.markNow()
        private var lastRead = opened
        private var scheduling = options.scheduledProbes.isPositive()

        /** Whether its probe is due on schedule now; false for good once [HandoffOptions.scheduledProbes] passed. */
        private fun scheduledProbeDue(): Boolean {
            if (!scheduling) return false
            if (opened.elapsedNow() >= options.scheduledProbes) {
                scheduling = false
                return false
            }
            return lastRead.elapsedNow() >= options.probeInterval
        }

        suspend fun run(collector: FlowCollector<LogEntry>) {
            prober.register(this)
            try {
                var reason = Reason.START
                while (true) {
                    collector.fromStore(reason)
                    reason = collector.fromMemory()
                }
            } finally {
                prober.unregister(this)
            }
        }

        /**
         * Reads the store from the cursor up to the partition's head, when the head is past it and memory does not
         * continue the run that far: what this node's appends handed over by then, even while the head was read, is
         * taken first. For a probe ([ObservedEvent.TailRead.Reason.IDLE]), not while an append of this node in flight
         * hands over what the head shows ([ownCommitAhead]).
         */
        private suspend fun FlowCollector<LogEntry>.fromStore(reason: Reason) {
            // Cleared before the head is read: an entry dropped before this was committed before it, so at or below it.
            listener.overflowed.set(false)
            lastRead = TimeSource.Monotonic.markNow()
            var readBackCount = 0
            val head = log.head(partition) ?: 0L
            if (head > cursor) {
                // An append of this node may have committed, handed its entry over and returned while the head was
                // read: its commit is in the head and no longer in flight, so only what was handed over shows it.
                drain()
                deliverRun()
            }
            val probe = reason == Reason.IDLE || reason == Reason.SCHEDULED || reason == Reason.BACKSTOP
            if (head > cursor && probe && ownCommitAhead()) {
                // This node's own commits first, from memory; what the head shows past them is other nodes', read now
                // rather than a probe interval later.
                deliverRun()
            }
            if (head > cursor) {
                reads.incrementAndGet()
                observer.observed(ObservedEvent.TailRead(context, partition, reason))
                log.tail(partition, cursor.takeIf { it > 0 })
                    .transformWhile {
                        emit(it)
                        it.position.sequence < head
                    }
                    .collect { entry ->
                        if (entry.position.sequence > cursor) {
                            readBack(entry)
                            readBackCount++
                        }
                        deliver(entry)
                    }
            }
            if (reason == Reason.BACKSTOP && readBackCount > 0) {
                logger.warn {
                    "The backstop read $readBackCount entries of partition $partition of $context that the probe did " +
                        "not name: a node wrote to a partition it no longer owned, after its routes moved and the " +
                        "scheduled probes ended, or the probe's answer missed a case of another node's appends."
                }
            }
            pending.headMap(cursor, true).clear()
        }

        /**
         * Whether an append of this node in flight hands over the entry the run needs next: the head a probe read may
         * show its commit before its append returned. Waits for it as for a gap ([awaitGap]); false at once when no
         * append of this node is in flight, so the entries past the cursor are other nodes'.
         */
        private suspend fun ownCommitAhead(): Boolean {
            val appends = inFlight[partition]
            return !appends.returnedBefore(appends.horizon()) && awaitGap()
        }

        /** Delivers the run of entries handed over that continues from the cursor. */
        private suspend fun FlowCollector<LogEntry>.deliverRun() {
            while (true) {
                val next = pending.remove(cursor + 1) ?: return
                deliver(next)
                handedOver.incrementAndGet()
            }
        }

        /** Continues the run from memory; returns why the store must be read. */
        private suspend fun FlowCollector<LogEntry>.fromMemory(): Reason {
            while (true) {
                deliverRun()
                if (listener.overflowed.get()) {
                    return Reason.OVERFLOW
                }
                if (scheduledProbeDue()) {
                    return Reason.SCHEDULED
                }
                if (pending.isEmpty()) {
                    when (val next = nextOrWake()) {
                        is LogEntry -> hold(next)
                        is Reason -> return next
                        // The scheduled probe's interval passed: the loop's next turn takes it.
                        else -> Unit
                    }
                } else if (!awaitGap()) {
                    return Reason.GAP
                }
            }
        }

        /**
         * The next entry handed over, the probe's wake ([Reason]), or, while the tail's probes are on schedule, null
         * once [HandoffOptions.probeInterval] passes without either. A select clause either takes an entry or leaves
         * it, whichever completes; a `receive` cancelled by a timeout may take one and still be cancelled, losing it to
         * a read of the store.
         */
        @OptIn(ExperimentalCoroutinesApi::class) // onTimeout: the scheduled probes' interval.
        private suspend fun nextOrWake(): Any? = select {
            listener.entries.onReceive { it }
            wakes.onReceive { it }
            if (scheduling) onTimeout(options.probeInterval) { null }
        }

        /**
         * Waits until the sequence the run needs next is handed over, or the tail overflows: true. False when no
         * append of this node can hand it over any more (every one in flight when the gap appeared returned without
         * it), or [HandoffOptions.gapWait] passed first.
         */
        private suspend fun awaitGap(): Boolean {
            val appends = inFlight[partition]
            val horizon = appends.horizon()
            // Set before the appends are looked at: one that closes after the look nudges this tail.
            listener.waiting = true
            return try {
                gapWithin(appends, horizon)
            } finally {
                listener.waiting = false
            }
        }

        // An entry the timeout takes from the channel and loses costs nothing: the tail reads the store for the gap.
        private suspend fun gapWithin(appends: InFlight, horizon: Long): Boolean =
            withTimeoutOrNull(options.gapWait) {
                while (!pending.containsKey(cursor + 1) && !listener.overflowed.get()) {
                    if (appends.returnedBefore(horizon)) {
                        // Their commits were handed over before they returned: take what they handed, then look.
                        drain()
                        if (!pending.containsKey(cursor + 1)) {
                            return@withTimeoutOrNull false
                        }
                    } else {
                        select {
                            listener.entries.onReceive { hold(it) }
                            listener.nudges.onReceive {}
                        }
                    }
                }
                true
            } ?: false

        private fun drain() {
            while (true) {
                hold(listener.entries.tryReceive().getOrNull() ?: return)
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
                active = TimeSource.Monotonic.markNow()
            }
        }
    }
}

private val logger = KotlinLogging.logger(HandoffLog::class.java.name)

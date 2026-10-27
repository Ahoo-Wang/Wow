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

package me.ahoo.wow.kafka

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.transport.SourceMessage
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The tails of one [topic] that a node reads, through one consumer (one consumer per node and topic):
 * each partition [join]s with the offset to read from and gets a channel its messages are put in, in offset order.
 *
 * One coroutine owns the consumer, which is not safe for concurrent use: it applies the joins and leaves as a manual
 * `assign()`, seeks each partition that joined, polls, and moves each partition's records into its channel. A join or
 * leave wakes the consumer only while it polls, and the next round applies every change made meanwhile at once:
 * a wakeup that interrupted the offsets read of a placement would leave its reply to arrive for a
 * partition no longer assigned, which the client logs as a warning, and partitions joining or leaving one by one as
 * the leases settle would assign again for each. A partition whose channel is full
 * keeps what did not fit and is paused until it has room, so a slow reader never holds up the others. The coroutine
 * runs while some partition is joined: the last [leave] stops it and closes the consumer. A consumer that fails closes
 * every joined channel with the failure; their readers read again.
 *
 * A partition that does not hold the offset its reader is to read from, when it joins or later (the retention deleted
 * what a paused partition had yet to read; the consumers reset no offset on their own), is handled by [gaps]' policy:
 * that reader alone fails with an [OffsetGapException], or reads on from the earliest offset retained.
 *
 * Each run of the coroutine is a [Session] of its own: the last [leave] ends the session at once, so a partition that
 * joins right after it starts a new session, with a consumer of its own, instead of reviving the one stopping, which
 * the leave waits for.
 */
internal class SharedTopicConsumer(
    private val topic: String,
    private val consumers: () -> Consumer<String, ByteArray>,
    private val pollTimeout: Duration,
    dispatcher: CoroutineDispatcher,
    private val buffer: Int = DEFAULT_BUFFER,
    private val gaps: OffsetGaps = OffsetGaps(),
) : AutoCloseable {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    /** Guards [readers], [session] and each session's changes and consumer. */
    private val lock = Any()
    private val readers = HashMap<Int, Reader>()
    private var session: Session? = null

    /** One joined partition: the channel its reader takes from, and the records polled that did not fit yet. */
    inner class Reader(val partition: Int, from: Long) {
        val channel = Channel<SourceMessage>(buffer)
        internal val pending = ArrayDeque<SourceMessage>()

        /** The next offset to move into [pending]; what lies before it was taken or is skipped. */
        internal var next: Long = from

        /** Whether the consumer was sought to [next] since the partition joined: assigned again, it keeps its place. */
        internal var placed = false

        @Volatile
        internal var left = false
    }

    /**
     * One run of the polling coroutine, from the first join to the last leave: the joins and leaves it has yet to
     * apply, and its consumer once made. Guarded by `lock`.
     */
    private class Session {
        val changes = ArrayList<SharedTopicConsumer.Reader>()
        var consumer: Consumer<String, ByteArray>? = null

        /** Whether the consumer is in a poll: only then does a change wake it. */
        var polling = false

        /** Wakes the consumer when it polls, so that the change just made is applied; called under `lock`. */
        fun wake() {
            if (polling) consumer?.wakeup()
        }
        lateinit var job: Job
    }

    /** Joins [partition] from offset [from]; null when it is joined already, so its reader needs a consumer of its own. */
    fun join(partition: Int, from: Long): Reader? = synchronized(lock) {
        if (partition in readers) {
            return null
        }
        val reader = Reader(partition, from)
        readers[partition] = reader
        val current = session ?: Session().also { started ->
            session = started
            started.job = scope.launch { run(started) }
        }
        current.changes += reader
        current.wake()
        reader
    }

    /**
     * Leaves [reader]'s partition; when no partition is left, ends the session and waits until its consumer is
     * closed.
     */
    suspend fun leave(reader: Reader) {
        val stopping = synchronized(lock) {
            if (readers[reader.partition] !== reader) {
                return
            }
            readers.remove(reader.partition)
            reader.left = true
            val current = checkNotNull(session) { "A joined partition of $topic has no session." }
            current.changes += reader
            current.wake()
            if (readers.isNotEmpty()) {
                return
            }
            session = null
            current.job
        }
        withContext(NonCancellable) { stopping.join() }
    }

    /**
     * Polls for [session] until it ends. Whatever fails, making the consumer included (a bootstrap address that does
     * not resolve yet), ends the session: every joined reader fails with it and reads again, which joins
     * a new session with a new consumer.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the consumer throws ends every read, which reads again.
    private suspend fun run(session: Session) {
        var consumer: Consumer<String, ByteArray>? = null
        val assigned = HashMap<Int, Reader>()
        // Whether the assignment is to be applied again: a wakeup interrupted it.
        var reassigning = false
        try {
            consumer = consumers()
            synchronized(lock) { session.consumer = consumer }
            while (true) {
                val changed = synchronized(lock) {
                    if (this.session !== session) {
                        return
                    }
                    session.changes.toList().also { session.changes.clear() }
                }
                try {
                    if (changed.isNotEmpty() || reassigning) {
                        reassigning = true
                        reassign(consumer, assigned, changed)
                        reassigning = false
                    }
                    deliver(poll(consumer, session), assigned)
                    flush(consumer, assigned)
                } catch (_: WakeupException) {
                    // Another join or leave woke the consumer: the next round applies it.
                } catch (outOfRange: OffsetOutOfRangeException) {
                    if (!placeOutOfRange(consumer, assigned, outOfRange)) {
                        reassigning = true
                    }
                }
                if (endedEmpty(session)) {
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("The consumer of {} failed; its tails read again.", topic, failure)
            end(session, failure)
        } catch (failure: Error) {
            // Fails the readers too, which would wait forever otherwise, then goes on failing.
            end(session, failure)
            throw failure
        } finally {
            consumer?.let { withContext(NonCancellable) { it.close() } }
        }
    }

    /** Ends [session], if it is still the current one: every joined reader fails with [failure]. */
    private fun end(session: Session, failure: Throwable) = synchronized(lock) {
        if (this.session === session) {
            readers.values.forEach { it.channel.close(failure) }
            readers.clear()
            this.session = null
        }
    }

    /**
     * Assigns the joined partitions and seeks each one to its next offset: a new one to the offset it joined at, a kept
     * one to where it was ([place]).
     */
    private fun reassign(consumer: Consumer<String, ByteArray>, assigned: HashMap<Int, Reader>, changed: List<Reader>) {
        changed.forEach { reader ->
            if (reader.left) {
                assigned.remove(reader.partition, reader)
            } else {
                assigned[reader.partition] = reader
            }
        }
        consumer.assign(assigned.keys.map { TopicPartition(topic, it) })
        // A partition kept in the assignment keeps its position: only those that joined are sought.
        place(consumer, assigned, assigned.values.filterNot { it.placed })
    }

    /**
     * Seeks each of [placing] to its next offset, when its partition holds it; otherwise [gaps] decides:
     * the reader reads on from the earliest offset retained, or fails alone and leaves the assignment.
     */
    private fun place(consumer: Consumer<String, ByteArray>, assigned: HashMap<Int, Reader>, placing: List<Reader>) {
        if (placing.isEmpty()) {
            return
        }
        val partitions = placing.map { TopicPartition(topic, it.partition) }
        val starts = consumer.beginningOffsets(partitions)
        val ends = consumer.endOffsets(partitions)
        val failed = ArrayList<Pair<Reader, OffsetGapException>>()
        placing.forEach { reader ->
            val partition = TopicPartition(topic, reader.partition)
            try {
                reader.next = gaps.resolve(
                    topic,
                    reader.partition,
                    reader.next,
                    starts.getValue(partition),
                    ends.getValue(partition),
                )
                consumer.seek(partition, reader.next)
                reader.placed = true
            } catch (gap: OffsetGapException) {
                failed += reader to gap
            }
        }
        if (failed.isEmpty()) {
            return
        }
        failed.forEach { (reader, gap) ->
            assigned.remove(reader.partition, reader)
            synchronized(lock) { readers.remove(reader.partition, reader) }
            reader.channel.close(gap)
        }
        consumer.assign(assigned.keys.map { TopicPartition(topic, it) })
    }

    /**
     * Places the partitions [outOfRange] names again ([place]); false when a join or leave woke the consumer meanwhile,
     * so that the next round assigns and places every partition again, as for any other wakeup,
     * instead of ending the session.
     */
    private fun placeOutOfRange(
        consumer: Consumer<String, ByteArray>,
        assigned: HashMap<Int, Reader>,
        outOfRange: OffsetOutOfRangeException,
    ): Boolean = try {
        place(consumer, assigned, outOfRange.partitions().mapNotNull { assigned[it.partition()] })
        true
    } catch (_: WakeupException) {
        false
    }

    /** Ends [session] when its readers have all failed alone ([place]): nothing is left to poll for. */
    private fun endedEmpty(session: Session): Boolean = synchronized(lock) {
        if (this.session === session && readers.isEmpty() && session.changes.isEmpty()) {
            this.session = null
            true
        } else {
            false
        }
    }

    /**
     * Polls [consumer] for [session], waking to a change only now: none when a change is waiting already, which the
     * next round applies first.
     */
    private fun poll(consumer: Consumer<String, ByteArray>, session: Session): ConsumerRecords<String, ByteArray> {
        if (consumer.assignment().isEmpty()) return ConsumerRecords.empty()
        val waiting = synchronized(lock) {
            session.changes.isNotEmpty().also { if (!it) session.polling = true }
        }
        if (waiting) return ConsumerRecords.empty()
        return try {
            consumer.poll(pollTimeout.toJavaDuration())
        } finally {
            synchronized(lock) { session.polling = false }
        }
    }

    private fun deliver(records: ConsumerRecords<String, ByteArray>, assigned: Map<Int, Reader>) {
        for (partition in records.partitions()) {
            val reader = assigned[partition.partition()] ?: continue
            for (record in records.records(partition)) {
                if (record.offset() >= reader.next) {
                    reader.pending += SourceMessage.of(
                        record.offset(),
                        record.key(),
                        KafkaHeaders.of(record.headers()),
                        record.value(),
                    )
                    reader.next = record.offset() + 1
                }
            }
        }
    }

    /** Moves what fits into each channel; pauses the partitions that keep some, resumes the others. */
    private fun flush(consumer: Consumer<String, ByteArray>, assigned: Map<Int, Reader>) {
        val full = ArrayList<TopicPartition>()
        val free = ArrayList<TopicPartition>()
        assigned.values.forEach { reader ->
            while (reader.pending.isNotEmpty() && reader.channel.trySend(reader.pending.first()).isSuccess) {
                reader.pending.removeFirst()
            }
            (if (reader.pending.isEmpty()) free else full) += TopicPartition(topic, reader.partition)
        }
        consumer.pause(full)
        consumer.resume(free)
    }

    /** Stops the consumer; the readers still joined fail. */
    override fun close() {
        synchronized(lock) {
            readers.values.forEach { it.channel.close(IllegalStateException("The source of $topic is closed.")) }
            readers.clear()
            session = null
        }
        scope.cancel()
    }

    private companion object {
        const val DEFAULT_BUFFER = 512
        val logger = LoggerFactory.getLogger(SharedTopicConsumer::class.java)
    }
}

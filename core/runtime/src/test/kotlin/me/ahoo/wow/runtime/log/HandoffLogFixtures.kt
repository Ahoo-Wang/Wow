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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A hand-off log over a recording store, its tails collected, and the logs that hold or answer an append or a head
 * read on demand: what the hand-off log's tests share, each test with its own.
 */
abstract class HandoffLogFixtures {
    protected val type = AggregateType("bank", "account")
    protected val store = RecordingLog(InMemoryEventLog(PARTITIONS))
    protected val reads = ReadsObserver()
    protected val versions = ConcurrentHashMap<AggregateRef, AtomicInteger>()

    protected fun handoff(
        capacity: Int = 1_024,
        // Long enough that only a real gap or an idle partition reads the store, however loaded the machine is.
        gapWait: Duration = 10.seconds,
        probeInterval: Duration = 10.seconds,
        log: EventLog = store,
        readBack: (LogEntry) -> Unit = {},
    ) = HandoffLog(log, "bank", HandoffOptions(capacity, gapWait, probeInterval), reads, readBack)

    /** Aggregate ids that all land in partition 0. */
    protected val refs: List<AggregateRef> = generateSequence(0) { it + 1 }
        .map { type.ref("tenant", "account-$it") }
        .filter { Partitioner.partitionOf(it, PARTITIONS) == 0 }
        .take(8)
        .toList()

    protected fun next(ref: AggregateRef = refs[0]): EventStreamRecord {
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

    protected fun transport(n: Int) = TransportCodec.encode(Transport(trace = mapOf("traceparent" to "trace-$n")))

    /** Collects partition 0's tail of [log] from [after] into a channel, on another dispatcher. */
    protected fun CoroutineScope.collect(log: EventLog, after: Long? = null): Pair<Channel<LogEntry>, Job> {
        val seen = Channel<LogEntry>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { log.tail(0, after).collect { seen.send(it) } }
        return seen to job
    }

    protected suspend fun Channel<LogEntry>.take(count: Int): List<LogEntry> =
        withTimeout(10.seconds) { List(count) { receive() } }

    /** Waits until [log] has a tail open in partition 0, so what is appended next is handed to it. */
    protected suspend fun awaitOpen(log: HandoffLog, tails: Int = 1) = withTimeout(10.seconds) {
        while (store.heads.get() < tails) {
            delay(1)
        }
        log.storeReads
    }

    protected suspend fun Flow<LogEntry>.takeTwo(count: Int = 2): List<LogEntry> = take(count).toList()

    /** A log that counts the tails opened on it and the heads read. */
    protected class RecordingLog(private val delegate: EventLog) : EventLog by delegate {
        val tails = AtomicInteger()
        val heads = AtomicInteger()

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> {
            tails.incrementAndGet()
            return delegate.tail(partition, after)
        }

        /** Counted once the read returned: a commit after it is handed to the tail that read it. */
        override suspend fun head(partition: Int): Long? = delegate.head(partition).also { heads.incrementAndGet() }
    }

    /** A log whose next head read, once [arm]ed, waits for [release] before it reads the store's head. */
    protected class GateHead(private val delegate: EventLog) : EventLog by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        private var armed = false

        fun arm() {
            armed = true
        }

        override suspend fun head(partition: Int): Long? {
            if (armed) {
                armed = false
                entered.complete(Unit)
                release.await()
            }
            return delegate.head(partition)
        }
    }

    /** A log whose append commits and answers [AppendOutcome.Unknown] when [next] is set, as after a timeout. */
    protected class UnknownOnce(private val delegate: EventLog) : EventLog by delegate {
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
    protected class HoldFirst(private val delegate: EventLog) : EventLog by delegate {
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
    protected class ReadsObserver : Observer {
        val reasons = CopyOnWriteArrayList<ObservedEvent.TailRead.Reason>()

        override fun observed(event: ObservedEvent) {
            when (event) {
                is ObservedEvent.TailRead -> reasons += event.reason
                else -> Unit
            }
        }
    }

    /**
     * A log whose first append (or group) once [armed] waits for [release] before it reaches the store: in flight, not
     * committed.
     */
    /** Holds the first append after the store committed it, until [release]: its commit shows in the head meanwhile. */
    protected class GateAfterCommit(private val delegate: EventLog) : EventLog by delegate {
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

    protected class GateFirst(private val delegate: EventLog, armed: Boolean = true) : EventLog by delegate {
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

    protected companion object {
        const val PARTITIONS = 4
        const val ROUNDS = 200
    }
}

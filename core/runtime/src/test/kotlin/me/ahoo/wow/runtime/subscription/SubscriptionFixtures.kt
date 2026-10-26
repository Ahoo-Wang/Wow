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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val CART = AggregateType("shop", "cart")
const val ADDED = "shop.cart.Added"

fun cart(id: String): AggregateRef = CART.ref("t", id)

/** Options short enough for tests. */
fun testOptions(
    maxInFlight: Int = 1_024,
    maxAttempts: Int = 3,
    checkpointInterval: Duration = 10.milliseconds,
    stopTimeout: Duration = 2.seconds,
): SubscriptionOptions = SubscriptionOptions(
    maxInFlight = maxInFlight,
    retry = RetryPolicy(maxAttempts = maxAttempts, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
    checkpointInterval = checkpointInterval,
    stopTimeout = stopTimeout,
    readRetry = RetryPolicy(maxAttempts = 1, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
)

/** Appends streams of one event each, numbering each aggregate's versions. */
class Appender(private val log: EventLog) {
    private val versions = ConcurrentHashMap<AggregateRef, AtomicInteger>()
    private val requests = AtomicInteger()

    /** The last sequence appended to each partition. */
    val ends = ConcurrentHashMap<Int, Long>()

    suspend fun append(
        id: String,
        type: AggregateType = CART,
        events: List<EventRecord> = listOf(EventRecord("$type.Added", 1, "{}")),
        transport: Transport? = null,
    ): Long {
        val ref = type.ref("t", id)
        val version = versions.computeIfAbsent(ref) { AtomicInteger() }.incrementAndGet()
        val n = requests.incrementAndGet()
        val stream = EventStreamRecord(ref, version, "c-$n", "r-$n", "$type.Act", 0L, events, Scope.NONE)
        val outcome = log.append(stream, transport?.let(TransportCodec::encode))
        val position = (outcome as AppendOutcome.Committed).position
        ends.merge(position.partition, position.sequence, ::maxOf)
        return position.sequence
    }
}

/** An [EventLog] that counts the tail reads opened on it. */
class CountingLog(private val delegate: EventLog) : EventLog by delegate {
    val tails = AtomicInteger()

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> {
        tails.incrementAndGet()
        return delegate.tail(partition, after)
    }
}

/** A [CheckpointStore] that records every save asked of it. */
class RecordingCheckpoints(
    private val delegate: CheckpointStore = InMemoryCheckpointStore()
) : CheckpointStore by delegate {
    val saves = CopyOnWriteArrayList<Triple<String, Int, Long>>()

    /** The size of each [saveAll] asked, in order. */
    val batches = CopyOnWriteArrayList<Int>()

    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        saves += Triple(subscription, partition, position)
        delegate.save(group, subscription, partition, position)
    }

    override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
        batches += checkpoints.size
        checkpoints.forEach { save(group, it.subscription, it.partition, it.position) }
    }

    fun savesOf(subscription: String, partition: Int = 0): List<Long> =
        saves.filter { it.first == subscription && it.second == partition }.map { it.third }
}

/** What a handler saw: (aggregate id, version, sequence), in the order handled. */
class Handled {
    val entries = CopyOnWriteArrayList<Triple<String, Int, Long?>>()

    fun record(entry: SubscribedEntry) {
        entries += Triple(entry.ref.id, entry.version, entry.position?.sequence)
    }

    fun versionsOf(id: String): List<Int> = entries.filter { it.first == id }.map { it.second }

    fun sequences(): List<Long> = entries.mapNotNull { it.third }

    val size: Int get() = entries.size
}

/** Waits until [condition] holds, failing after [timeout]. */
suspend fun eventually(timeout: Duration = 10.seconds, condition: suspend () -> Boolean) {
    withTimeout(timeout) {
        while (!condition()) {
            delay(2)
        }
    }
}

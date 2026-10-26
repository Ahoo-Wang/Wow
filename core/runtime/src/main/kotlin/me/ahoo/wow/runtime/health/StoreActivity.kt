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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import kotlin.time.Duration

/**
 * What a node saw of one store it calls (review R3.5-3): when a call last succeeded, since when the calls fail, and
 * how many failed lately. The node records it on every call of its event logs and lease stores
 * ([trackedLog], [trackedLeases]); [NodeHealth] reads it, so a probe never calls a store itself.
 *
 * A log's call fails when it throws [StorageUnavailableException] or its append's outcome is
 * [AppendOutcome.Unknown] (the backend's contract for an unreachable store); any other exception is a defect, not an
 * outage, and is not counted. A lease store's call fails on any exception: the ownership retries them all as transient.
 *
 * Times are `System.nanoTime()` values, [NONE] when there is none.
 */
@InternalWowApi
class StoreActivity internal constructor(
    /** What the store is to the node: [LOG] or [LEASES]. */
    val kind: String,
    /** Which one: the context, and the store instance of a log group other than the default (`context#store`). */
    val name: String,
    private val nanos: () -> Long = System::nanoTime,
) {
    @Volatile
    var lastSuccess: Long = NONE
        private set

    /** The first failure since the last success; [NONE] while the calls succeed. */
    @Volatile
    var failingSince: Long = NONE
        private set

    @Volatile
    var lastFailure: Long = NONE
        private set

    /** The class of the last failure's exception, never its message (it may name hosts). */
    @Volatile
    var lastError: String? = null
        private set

    /** When the probe [NodeHealth] runs now started; [NONE] when none runs. */
    @Volatile
    var probing: Long = NONE
        private set

    /** The times of the latest failures, at most [MAX_RECENT]; guarded by itself. */
    private val recent = ArrayDeque<Long>()

    internal fun succeeded() {
        lastSuccess = nanos()
        if (failingSince != NONE) {
            failingSince = NONE
        }
    }

    internal fun failed(error: Throwable) {
        val now = nanos()
        if (failingSince == NONE) {
            failingSince = now
        }
        lastFailure = now
        lastError = error::class.java.simpleName
        synchronized(recent) {
            if (recent.size == MAX_RECENT) {
                recent.removeFirst()
            }
            recent.addLast(now)
        }
    }

    /** The failures in the last [window], counted up to [MAX_RECENT]. */
    fun failuresWithin(window: Duration): Int {
        val since = nanos() - window.inWholeNanoseconds
        return synchronized(recent) { recent.count { it > since } }
    }

    internal fun probeStarted() {
        probing = nanos()
    }

    internal fun probeEnded() {
        probing = NONE
    }

    /** Runs [block] as one call of the store: a success unless it throws a failure [counts]. */
    @Suppress("TooGenericExceptionCaught") // Recorded, then rethrown unchanged.
    internal suspend inline fun <T> call(counts: (Throwable) -> Boolean, block: () -> T): T {
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (counts(failure)) {
                failed(failure)
            }
            throw failure
        }
        succeeded()
        return result
    }

    override fun toString(): String = "StoreActivity($kind, $name)"

    @InternalWowApi
    companion object {
        const val LOG: String = "log"
        const val LEASES: String = "leases"
        const val NONE: Long = Long.MIN_VALUE

        /** The latest failures remembered, so [failuresWithin] counts at most this many. */
        const val MAX_RECENT: Int = 256
    }
}

/** Whether a failure of a log's call is an outage: the backend says the store is unavailable. */
private fun unavailable(failure: Throwable): Boolean = failure is StorageUnavailableException

/** Any failure of a lease store's call: the ownership retries them all. */
private fun anyFailure(failure: Throwable): Boolean = failure !is CancellationException

/** [log], every call recorded in [activity], and each append's latency in [pressure] when there is one. */
internal fun trackedLog(
    log: EventLog,
    activity: StoreActivity,
    pressure: StoreWritePressure? = null,
): EventLog = TrackedEventLog(log, activity, pressure)

/** [leases], every call recorded in [activity]. */
internal fun trackedLeases(
    leases: LeaseStore,
    activity: StoreActivity
): LeaseStore = TrackedLeaseStore(leases, activity)

/** The record of [log]'s calls, when it is tracked. */
internal fun activityOf(log: EventLog): StoreActivity? = (log as? TrackedEventLog)?.activity

/** The record of [leases]' calls, when it is tracked. */
internal fun activityOf(leases: LeaseStore): StoreActivity? = (leases as? TrackedLeaseStore)?.activity

/** The lease store [leases] tracks, or [leases] itself: what two contexts' tracked lease stores share. */
internal fun untracked(leases: LeaseStore): LeaseStore = (leases as? TrackedLeaseStore)?.leases ?: leases

private class TrackedEventLog(
    private val log: EventLog,
    val activity: StoreActivity,
    private val pressure: StoreWritePressure?,
) : EventLog {
    override val partitions: Int get() = log.partitions

    override val appendsAcrossPartitions: Boolean get() = log.appendsAcrossPartitions

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        timed { activity.call(::unavailable) { log.append(stream, transport) } }.also(::record)

    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> =
        timed {
            activity.call(
                ::unavailable
            ) { log.appendAll(streams) }
        }.also { outcomes -> outcomes.forEach(::record) }

    /** [block], its latency recorded in [pressure] whether it succeeds or fails (decision D252). */
    private inline fun <T> timed(block: () -> T): T {
        if (pressure == null) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            pressure.record(System.nanoTime() - start)
        }
    }

    /** An append whose outcome is unknown did not reach the store, or did not hear back from it. */
    private fun record(outcome: AppendOutcome) {
        if (outcome is AppendOutcome.Unknown) {
            activity.failed(outcome.cause)
        }
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        log.read(ref, fromVersion, toVersion).tracked()

    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail =
        activity.call(::unavailable) { log.readTail(ref, fromVersion, earlier) }

    override suspend fun readWithSnapshot(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail =
        activity.call(::unavailable) { log.readWithSnapshot(ref, snapshots, earlier) }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        log.readEntries(ref, fromVersion, toVersion).tracked()

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        activity.call(::unavailable) { log.positionOf(ref, version) }

    override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        activity.call(::unavailable) { log.findByRequestId(ref, requestId) }

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        activity.call(::unavailable) { log.findByRequestId(type, tenantId, requestId) }

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        activity.call(::unavailable) { log.findByRequestId(tenantId, requestId) }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = log.tail(partition, after).tracked()

    override suspend fun head(partition: Int): Long? = activity.call(::unavailable) { log.head(partition) }

    override suspend fun lineage(partition: Int): PartitionLineage =
        activity.call(::unavailable) { log.lineage(partition) }

    /** Each element read, and the end of the read, is a success; an unavailable store's failure is recorded. */
    private fun <T> Flow<T>.tracked(): Flow<T> = onEach { activity.succeeded() }
        .onCompletion { if (it == null) activity.succeeded() }
        .catch { failure ->
            if (unavailable(failure)) {
                activity.failed(failure)
            }
            throw failure
        }

    override fun toString(): String = log.toString()
}

private class TrackedLeaseStore(val leases: LeaseStore, val activity: StoreActivity) : LeaseStore {
    override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
        activity.call(::anyFailure) { leases.acquire(group, partition, owner, ttl) }

    override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease> =
        activity.call(::anyFailure) { leases.renew(group, partitions, owner, ttl) }

    override suspend fun release(group: String, partition: Int, owner: String): Boolean =
        activity.call(::anyFailure) { leases.release(group, partition, owner) }

    override suspend fun owners(group: String): List<Lease> = activity.call(::anyFailure) { leases.owners(group) }

    override suspend fun heartbeat(
        service: String,
        node: String,
        address: String,
        ttl: Duration,
        groups: Set<String>,
    ): Member = activity.call(::anyFailure) { leases.heartbeat(service, node, address, ttl, groups) }

    override suspend fun members(service: String): List<Member> =
        activity.call(::anyFailure) { leases.members(service) }

    override suspend fun leave(service: String, node: String) =
        activity.call(::anyFailure) { leases.leave(service, node) }

    override fun toString(): String = leases.toString()
}

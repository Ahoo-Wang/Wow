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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord

/**
 * Where the kernel keeps aggregates' states between commands, so a command does not replay the log.
 *
 * The kernel [take]s a state out for one attempt and [put]s it back only when the attempt leaves it valid: committed
 * (the evolved state at the new version), or decided without evolving (rejected, unchanged). Any other end drops it
 * and the next command reloads. With mutable states evolving changes the cached instance itself, so a state
 * must never stay cached through an attempt that may have changed it and did not commit.
 *
 * Commands of one aggregate must not run concurrently on one cache (the executor serializes them); across nodes, a
 * stale state only costs a version conflict and a reload. A state carries the aggregate's recent requests,
 * which must never be behind the log while the node owns the partition: a node drops the states of a partition
 * whenever it acquires or releases it ([invalidate]).
 */
@InternalWowApi
interface StateCache {
    /** Removes and returns the state of [ref], or null when none is cached. */
    fun take(ref: AggregateRef): CachedState?

    /** Caches [state] of [ref]. */
    fun put(ref: AggregateRef, state: CachedState)

    /**
     * Drops the states of the aggregates in [partition] of [partitions], as
     * [me.ahoo.wow.spi.log.Partitioner.partitionOf] places them: the node acquired or released the partition, so
     * another node may have committed to them. A state taken out meanwhile is not put back by a command that runs
     * without the partition's lease ([ExecutionLease.NOT_HELD]). Drops nothing by default.
     */
    fun invalidate(partition: Int, partitions: Int) = Unit

    /** Caches nothing. */
    @InternalWowApi
    object None : StateCache {
        override fun take(ref: AggregateRef): CachedState? = null

        override fun put(ref: AggregateRef, state: CachedState) = Unit
    }
}

/**
 * An aggregate's [state] after its event stream [version], with the metadata the kernel keeps beside it: its [scope],
 * the owner and space it has after that stream, which the next stream carries forward; whether it is
 * [deleted]; and its [tags]. Only the lifecycle commands change them, but for the tag keys the state
 * derives, which follow the state: [tags] are the effective tags. Its [schedules] are the rules
 * of the schedules it keeps, by key: those its streams set and did not cancel.
 */
@InternalWowApi
class CachedState(
    val state: Any,
    val version: Int,
    val scope: Scope = Scope.NONE,
    val deleted: Boolean = false,
    val tags: Map<String, List<String>> = emptyMap(),
    val schedules: Map<String, ScheduleRule> = emptyMap(),
) {
    internal val metadata: KernelLifecycle.Metadata get() = KernelLifecycle.Metadata(scope, deleted, tags, schedules)

    /**
     * The requests the aggregate committed most recently, derived from the log when the kernel loaded
     * it and added to by each commit; null when it was not derived (a state handed to the kernel, say).
     */
    internal var requests: RecentRequests? = null

    /**
     * The estimated bytes it takes in a cache before its class's size is measured ([StateSizes]): [STATE_BYTES] for the
     * state with its metadata, and what its recent requests' rings take.
     */
    val bytes: Long get() = STATE_BYTES + (requests?.bytes ?: 0)

    /**
     * What the aggregate's first stream recorded (its commit time and operator), when the kernel loaded or created the
     * aggregate: the snapshot's, the replayed first stream's, or the creating stream's. A state
     * view carries it, so the snapshot subscription writes the snapshot without reading the stored one for it.
     */
    var first: FirstCommit? = null
        internal set

    internal fun with(state: Any, version: Int, metadata: KernelLifecycle.Metadata): CachedState =
        CachedState(state, version, metadata.scope, metadata.deleted, metadata.tags, metadata.schedules)
            .also {
                it.requests = requests
                it.first = first
            }

    @InternalWowApi
    companion object {
        /**
         * The estimated bytes of a state object with its metadata, its recent requests aside, until its class's size
         * is measured ([StateSizes]); a domain whose states are much larger and never measured bounds its cache by
         * their number instead.
         */
        const val STATE_BYTES: Long = 1_024

        /** The bytes one recent request takes in its rings: its 64-bit hash and its version. */
        const val REQUEST_BYTES: Long = RecentRequests.REQUEST_BYTES
    }
}

/** What an aggregate's first stream recorded: its commit time ([eventTime]) and its [operator], the creator. */
@InternalWowApi
data class FirstCommit(val eventTime: Long, val operator: Operator?) {
    @InternalWowApi
    companion object {
        /** What [stream], an aggregate's first, recorded. */
        fun of(stream: EventStreamRecord): FirstCommit = FirstCommit(stream.commitTime, stream.operator)

        /** What [snapshot] keeps of its aggregate's first stream. */
        fun of(snapshot: SnapshotRecord): FirstCommit = FirstCommit(snapshot.firstEventTime, snapshot.firstOperator)
    }
}

/** The outcomes of [me.ahoo.wow.spi.observation.ObservedEvent.StateCache]. */
@InternalWowApi
object StateCacheOutcome {
    const val HIT: String = "hit"
    const val MISS: String = "miss"
    const val EVICTED: String = "evicted"
}

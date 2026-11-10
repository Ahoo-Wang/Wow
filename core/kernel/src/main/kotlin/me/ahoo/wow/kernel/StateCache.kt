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
 * Commands of one aggregate must not run concurrently on one cache (the executor serializes them). Across nodes, a
 * state goes stale when another node commits to its aggregate without the partition's lease (a command it ran
 * locally because its forward surely did not reach the owner): the owner drops it as soon as its tail of the
 * partition reads that commit from the log ([dropBefore]), and until then the stale state costs a version
 * conflict and a reload. A state carries the aggregate's recent requests, which must never be behind the log
 * while the node owns the partition: a node drops the states of a partition whenever it acquires or releases it
 * ([invalidate]).
 */
@InternalWowApi
interface StateCache {
    /** Removes and returns the state of [ref], or null when none is cached. */
    fun take(ref: AggregateRef): CachedState?

    /** Caches [state] of [ref]. */
    fun put(ref: AggregateRef, state: CachedState)

    /**
     * Drops the state of [ref] when it is cached at a version before [version], atomically with [take] and [put]:
     * a stream of the aggregate at [version] is in the log, so the state is behind it. Whether it dropped one. A
     * state taken out for a command is not in the cache and is left alone: that command's append meets the version
     * and conflicts. Drops nothing by default: a cache that cannot drop atomically leaves its stale states to their
     * conflicts.
     */
    fun dropBefore(ref: AggregateRef, version: Int): Boolean = false

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
 * [deleted]. Only the lifecycle commands change them. Its [schedules] are the rules
 * of the schedules it keeps, by key: those its streams set and did not cancel.
 */
@InternalWowApi
class CachedState(
    val state: Any,
    val version: Int,
    val scope: Scope = Scope.NONE,
    val deleted: Boolean = false,
    val schedules: Map<String, ScheduleRule> = emptyMap(),
) {
    internal val metadata: KernelLifecycle.Metadata get() = KernelLifecycle.Metadata(scope, deleted, schedules)

    /**
     * The requests the aggregate committed most recently, derived from the log when the kernel loaded
     * it and added to by each commit; null when it was not derived (a state handed to the kernel, say).
     */
    internal var requests: RecentRequests? = null

    /** The version that committed [requestId], when the recent requests hold it; null when they do not, or are none. */
    internal fun recentVersionOf(requestId: String): Int? = requests?.versionOf(requestId)

    /**
     * The values of the aggregate's declared access attributes in [state] ([AggregateModel.accessOf]), once the kernel
     * read them ([declared]) or committed the state; null until then.
     */
    internal var access: Map<String, Any?>? = null

    /**
     * The values of the declared access attributes of [model], this state's: read once, before the next decision's
     * events evolve the state, which may change it in place.
     */
    internal fun declared(model: AggregateModel<*>): Map<String, Any?> {
        access?.let { return it }
        @Suppress("UNCHECKED_CAST") // The state is the model's, as the kernel holds it.
        val read = (model as AggregateModel<Any>).accessOf(state)
        return read.also { access = it }
    }

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
        CachedState(state, version, metadata.scope, metadata.deleted, metadata.schedules)
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

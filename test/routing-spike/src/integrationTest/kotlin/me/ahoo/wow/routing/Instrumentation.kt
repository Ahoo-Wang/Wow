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

package me.ahoo.wow.routing

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/** The counters of one run, shared by its nodes. */
class Counters {
    val dedupReads = LongAdder()
    val logReads = LongAdder()
    val snapshotLoads = LongAdder()
    val snapshotSaves = LongAdder()
    val appends = LongAdder()
    val conflicts = LongAdder()
    val cacheTakes = LongAdder()
    val cacheHits = LongAdder()
    val executed = LongAdder()
    val forwards = LongAdder()
    val forwardNanos = LongAdder()

    fun read(): Map<String, Long> = mapOf(
        "dedupReads" to dedupReads.sum(),
        "logReads" to logReads.sum(),
        "snapshotLoads" to snapshotLoads.sum(),
        "snapshotSaves" to snapshotSaves.sum(),
        "appends" to appends.sum(),
        "conflicts" to conflicts.sum(),
        "cacheTakes" to cacheTakes.sum(),
        "cacheHits" to cacheHits.sum(),
        "executed" to executed.sum(),
        "forwards" to forwards.sum(),
        "forwardNanos" to forwardNanos.sum(),
    )
}

/** Counts what the kernel asks of the log: the request-id lookup, the stream reads, appends and their conflicts. */
class CountingEventLog(private val delegate: EventLog, private val counters: Counters) : EventLog by delegate {
    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        counters.appends.increment()
        return delegate.append(stream, transport).also {
            if (it is AppendOutcome.Conflict) {
                counters.conflicts.increment()
            }
        }
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
        counters.logReads.increment()
        return delegate.read(ref, fromVersion, toVersion)
    }

    override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? {
        counters.dedupReads.increment()
        return delegate.findByRequestId(ref, requestId)
    }

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? {
        counters.dedupReads.increment()
        return delegate.findByRequestId(type, tenantId, requestId)
    }
}

/** Counts snapshot loads and saves. */
class CountingSnapshotStore(private val delegate: SnapshotStore, private val counters: Counters) :
    SnapshotStore by delegate {
    override suspend fun save(snapshot: SnapshotRecord): Boolean {
        counters.snapshotSaves.increment()
        return delegate.save(snapshot)
    }

    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
        counters.snapshotSaves.add(snapshots.size.toLong())
        return delegate.saveAll(snapshots)
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? {
        counters.snapshotLoads.increment()
        return delegate.load(ref)
    }
}

/**
 * A node's [LruStateCache] that counts hits, and serializes the snapshot of a committed state while the kernel still
 * holds the aggregate's lane (a state is mutable, D7: once the lane is released the next command may change it). The
 * node saves the stashed snapshot after the command, standing in for roadmap 4.6.
 */
class SnapshottingCache<S : Any>(
    capacity: Int,
    private val counters: Counters,
    private val model: AggregateModel<S>,
    private val codec: EventCodec,
    private val shape: StateShape,
) : StateCache {
    private val states = LruStateCache(capacity)
    private val stashed = ConcurrentHashMap<Pair<AggregateRef, Int>, SnapshotRecord>()

    override fun take(ref: AggregateRef): CachedState? {
        counters.cacheTakes.increment()
        return states.take(ref)?.also { counters.cacheHits.increment() }
    }

    override fun put(ref: AggregateRef, state: CachedState) {
        states.put(ref, state)
        if (state.version > 0 && shape.snapshots(state.version) && stashed.size < STASH_LIMIT) {
            @Suppress("UNCHECKED_CAST") // The cache serves one model.
            val snapshot = codec.snapshotOf(
                model,
                ref,
                state.state as S,
                state.version,
                state.scope,
                firstEventTime = 0,
                lastEventTime = System.currentTimeMillis(),
            )
            stashed[ref to state.version] = snapshot
        }
    }

    /** The snapshot stashed when [ref] committed [version], if that version is due one. */
    fun takeSnapshot(ref: AggregateRef, version: Int): SnapshotRecord? = stashed.remove(ref to version)

    private companion object {
        const val STASH_LIMIT = 100_000
    }
}

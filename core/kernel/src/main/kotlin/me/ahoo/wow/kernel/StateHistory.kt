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

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import java.time.Instant

/**
 * The reads of an aggregate's committed state outside the write path: the state at a version or a
 * time, evolved from a base, folded from given streams, and the trace of its commits. They read the log and the
 * snapshot handed in, never the state cache; the [Kernel] gives them, with its defaults, as [Kernel.stateAt],
 * [Kernel.replayed], [Kernel.evolved] and [Kernel.trace].
 */
internal class StateHistory(
    private val kernel: Kernel,
    private val log: EventLog,
    private val codec: EventCodec,
) {
    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        version: Int,
        snapshot: SnapshotRecord?,
        maxReplay: Int,
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(version >= 1) { "A committed state has a version from 1." }
        require(maxReplay >= 1) { "A replay folds at least one stream." }
        if (snapshot != null && snapshot.ref == ref && snapshot.version <= version) {
            if (version - snapshot.version > maxReplay) {
                throw ReplayLimitExceededException(ref, maxReplay)
            }
            kernel.fromSnapshot(model, snapshot, version, maxReplay = maxReplay)?.let {
                return it.takeIf { state -> state.version == version }
            }
        }
        if (version > maxReplay) {
            throw ReplayLimitExceededException(ref, maxReplay)
        }
        return kernel.replay(model, ref, CachedState(model.initialState(ref), 0), log.read(ref, 1, version), maxReplay)
            .takeIf { it.version == version }
    }

    fun <S : Any> replayed(
        model: AggregateModel<S>,
        ref: AggregateRef,
        streams: List<EventStreamRecord>
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        if (streams.isEmpty() || streams.withIndex().any { (index, stream) -> stream.version != index + 1 }) {
            return null
        }
        return kernel.replay(model, CachedState(model.initialState(ref), 0), streams)
    }

    fun <S : Any> evolved(model: AggregateModel<S>, base: CachedState, stream: EventStreamRecord): CachedState? {
        require(stream.ref.type == model.type) { "${stream.ref} is not a ${model.type}." }
        if (stream.version != base.version + 1) {
            return null
        }
        return kernel.evolve(model, base, stream)
    }

    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        version: Int,
        base: CachedState,
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(base.version in 0..version) { "The base at version ${base.version} is past $version." }
        if (base.version == version) {
            return kernel.derived(model, base)
        }
        val streams = log.read(ref, base.version + 1, version).toList()
        if (streams.firstOrNull()?.version != base.version + 1 || streams.last().version != version) {
            return null
        }
        return kernel.replay(model, base, streams)
    }

    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        at: Instant,
        snapshot: SnapshotRecord?,
        maxReplay: Int,
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(maxReplay >= 1) { "A replay folds at least one stream." }
        val by = at.epochMillis()
        if (snapshot != null && snapshot.ref == ref && snapshot.lastEventTime <= by) {
            kernel.fromSnapshot(model, snapshot, committedBy = by, maxReplay = maxReplay)?.let { return it }
        }
        val streams = log.read(ref).takeWhile { it.commitTime <= by }
        return kernel.replay(model, ref, CachedState(model.initialState(ref), 0), streams, maxReplay)
            .takeIf { it.version > 0 }
    }

    @Suppress("LongParameterList")
    suspend fun <S : Any> trace(
        model: AggregateModel<S>,
        ref: AggregateRef,
        fromVersion: Int,
        toVersion: Int,
        limit: Int,
        snapshot: SnapshotRecord?,
        maxReplay: Int,
    ): List<CommitTrace> {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(fromVersion >= 1) { "A commit has a version from 1." }
        require(toVersion >= fromVersion) { "A trace ends at or after its first version." }
        require(limit >= 1) { "A trace holds at least one commit." }
        var current = if (fromVersion == 1) {
            CachedState(model.initialState(ref), 0)
        } else {
            stateAt(model, ref, fromVersion - 1, snapshot, maxReplay) ?: return emptyList()
        }
        val last = minOf(toVersion.toLong(), fromVersion.toLong() + limit - 1).toInt()
        val traces = ArrayList<CommitTrace>()
        log.read(ref, fromVersion, last).collect { stream ->
            val events = ArrayList<Any>(stream.events.size)
            current = kernel.evolve(model, current, stream, events)
            // The state may be mutable and evolve further: the trace keeps a tree of it as it is now.
            traces += CommitTrace(stream, events, codec.stateTree(current.state), current.deleted, current.tags)
        }
        return traces
    }

    private companion object {
        /** [this] in epoch milliseconds, the unit of commit times; an instant beyond them is the first or the last. */
        fun Instant.epochMillis(): Long = try {
            toEpochMilli()
        } catch (_: ArithmeticException) {
            if (isBefore(Instant.EPOCH)) Long.MIN_VALUE else Long.MAX_VALUE
        }
    }
}

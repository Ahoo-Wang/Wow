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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * Where the [Kernel] gets an aggregate's committed state, and how the log's streams fold into it. On a cache miss, the
 * state comes from the aggregate's snapshot in [snapshots] and the log's tail after it when the snapshot fits the
 * model's state schema (03-log-and-snapshot.md §3), else from replaying the whole log. A snapshot is only a cache: one
 * that does not fit, or a store that fails, is logged and costs a full replay, never the command. The fold is the one
 * [StateHistory]'s reads use too.
 */
internal class StateSourcing(
    private val log: EventLog,
    private val codec: EventCodec,
    private val observer: Observer,
    snapshots: SnapshotStore?,
    snapshotTailReaders: (AggregateType) -> SnapshotTailReader?,
) {
    /** The snapshots a cache miss reads with the log's tail, a failing load read as none. */
    private val loading: SnapshotStore? = snapshots?.let(::TolerantSnapshots)

    /** How a cache miss reads the snapshot with the tail: in one round trip where the log can. */
    private val snapshotTails = SnapshotTailReads(log, snapshotTailReaders, observer)

    /**
     * The aggregate's committed state, version and scope: cached, else from its snapshot and tail, else replayed; and
     * whether it came from the cache. A state loaded from the log carries the aggregate's recent requests,
     * which [requests] derives from what was read; see [RequestCheck.cached] for when a cached state is used.
     */
    internal suspend fun <S : Any> load(model: AggregateModel<S>, requests: RequestCheck): Pair<CachedState, Boolean> {
        val ref = requests.ref
        requests.cached()?.let {
            observer.observed(ObservedEvent.StateCache(ref.type.context, ObservedEvent.StateCache.Outcome.HIT))
            return it to true
        }
        observer.observed(ObservedEvent.StateCache(ref.type.context, ObservedEvent.StateCache.Outcome.MISS))
        val read = loading?.let { snapshotTails.read(ref, it, requests.earlier) }
        read?.snapshot?.let { snapshot ->
            codec.fitting(model, snapshot)?.let { state ->
                fromSnapshot(model, snapshot, state, read.tail.streams.asFlow())?.let { loaded ->
                    loaded.first = FirstCommit.of(snapshot)
                    return requests.derived(loaded, read.tail) to false
                }
            }
        }
        // Without a snapshot the read holds every stream; with one that does not fit, the log is read again whole.
        val streams = read?.takeIf { it.snapshot == null }?.tail?.streams ?: log.read(ref).toList()
        val replayed = replay(model, CachedState(model.initialState(ref), 0), streams)
        replayed.first = streams.firstOrNull()?.let(FirstCommit::of)
        return requests.derived(replayed, AggregateTail.of(streams, emptyList())) to false
    }

    /**
     * The state of [snapshot] evolved with the log's tail after it, or null when the snapshot does not fit: written
     * at another state revision or for another state schema, not decodable, or newer than the log.
     * The tail is read from the snapshot's own version, so the same read proves the log holds that version; that
     * stream is already folded and is skipped. The
     * tail stops at [until], and before the first stream committed after [committedBy] (epoch milliseconds): a
     * snapshot whose own stream committed after it does not fit either. The tail is folded as it is read, at most
     * [maxReplay] streams of it ([ReplayLimitExceededException] past them).
     */
    internal suspend fun <S : Any> fromSnapshot(
        model: AggregateModel<S>,
        snapshot: SnapshotRecord,
        until: Int = Int.MAX_VALUE,
        committedBy: Long = Long.MAX_VALUE,
        maxReplay: Int = Int.MAX_VALUE,
    ): CachedState? {
        val state = codec.fitting(model, snapshot) ?: return null
        return fromSnapshot(
            model,
            snapshot,
            state,
            log.read(snapshot.ref, snapshot.version, until),
            committedBy,
            maxReplay
        )
    }

    /**
     * [state], decoded from [snapshot], evolved with [tail], the log's streams of its aggregate from the snapshot's
     * own version on, or null when the tail does not start with the snapshot's own stream (see the other
     * [fromSnapshot]).
     */
    internal suspend fun <S : Any> fromSnapshot(
        model: AggregateModel<S>,
        snapshot: SnapshotRecord,
        state: S,
        tail: Flow<EventStreamRecord>,
        committedBy: Long = Long.MAX_VALUE,
        maxReplay: Int = Int.MAX_VALUE,
    ): CachedState? {
        val restored =
            CachedState(state, snapshot.version, snapshot.scope, snapshot.deleted, snapshot.schedules)
        var current: CachedState? = null
        var replayed = 0
        tail
            // The first stream must be the snapshot's own: else the snapshot is beyond the log, and nothing is folded.
            .takeWhile { it.commitTime <= committedBy && (current != null || it.version == snapshot.version) }
            .collect { stream ->
                val base = current
                current = if (base == null) {
                    // Its own stream is already folded.
                    restored
                } else {
                    if (++replayed > maxReplay) {
                        throw ReplayLimitExceededException(snapshot.ref, maxReplay)
                    }
                    evolve(model, base, stream)
                }
            }
        return current ?: run {
            logger.warn { "The snapshot of ${snapshot.ref} is at version ${snapshot.version}, beyond its log; replaying it." }
            null
        }
    }

    /**
     * Evolves [initial] with the events of [streams]: the state, and the deleted mark and schedules by the
     * lifecycle and schedule events; the version and the scope are the last stream's, or [initial]'s when there is none.
     */
    internal fun <S : Any> replay(
        model: AggregateModel<S>,
        initial: CachedState,
        streams: List<EventStreamRecord>,
    ): CachedState = streams.fold(initial) { current, stream -> evolve(model, current, stream) }

    /**
     * Evolves [initial] with [streams] of [ref] as they are read, never holding them, as the other
     * [replay] does; [ReplayLimitExceededException] before folding a stream past [maxReplay].
     */
    internal suspend fun <S : Any> replay(
        model: AggregateModel<S>,
        ref: AggregateRef,
        initial: CachedState,
        streams: Flow<EventStreamRecord>,
        maxReplay: Int,
    ): CachedState {
        var current = initial
        var replayed = 0
        streams.collect { stream ->
            if (++replayed > maxReplay) {
                throw ReplayLimitExceededException(ref, maxReplay)
            }
            current = evolve(model, current, stream)
        }
        return current
    }

    /**
     * Evolves [current] with the events of [stream], adding each event, read as the model's current schema, to
     * [events] when given: the state, and the deleted mark and schedules by the lifecycle and schedule events; the
     * version and the scope become the stream's.
     */
    internal fun <S : Any> evolve(
        model: AggregateModel<S>,
        current: CachedState,
        stream: EventStreamRecord,
        events: MutableList<Any>? = null,
    ): CachedState {
        @Suppress("UNCHECKED_CAST") // The state is built by this model, or read as its state type.
        var state = current.state as S
        var metadata = current.metadata
        // A retired event is skipped: the stream still counts as a version, the state stays as it is.
        val decoded = stream.events.mapNotNull { record ->
            try {
                codec.decodeUnlessRetired(model, record)
            } catch (@Suppress("TooGenericExceptionCaught") unreadable: RuntimeException) {
                // An event the model cannot read, an upgrade that refuses a record among them, names its aggregate.
                throw UnreadableEventException(stream.ref, stream.version, record.name, record.revision, unreadable)
            }
        }
        // The commit as the log records it, which an evolve function that reads it reads alike on every rebuild.
        val commit = if (model.readsCommits) {
            model.commitOf(
                stream.ref,
                stream.version,
                stream.commitTime,
                stream.commandId,
                stream.requestId,
                stream.commandType,
                stream.scope,
                stream.operator,
                decoded,
            )
        } else {
            null
        }
        decoded.forEach { event ->
            events?.add(event)
            state = model.evolve(state, event, commit)
            metadata = KernelLifecycle.evolve(metadata, event)
        }
        return current.with(state, stream.version, metadata.copy(scope = stream.scope))
    }
}

// The kernel's category, which the moved log lines keep.
private val logger = KotlinLogging.logger(Kernel::class.java.name)

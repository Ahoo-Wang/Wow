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

package me.ahoo.wow.runtime.state

import kotlinx.coroutines.delay
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ReplayLimitExceededException
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.databind.JsonNode
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The state point reads of the node's aggregates (decisions D40, D112, D231): the public port's
 * `GET /{context}/{aggregate}/{id}/state` reads [latest], `?version=` and `?at=` read [at], `GET …/{id}/state/trace`
 * reads [trace]; the events query model decides which aggregates a caller sees on [committed] (reviews A3-11,
 * R2.3-6). They read what is committed, for no caller: an entry checks the caller's scope against the state's and
 * admits and masks the state as the snapshot queries do (`me.ahoo.wow.query.state.StateVisibility`).
 */
@InternalWowApi
interface StateReads {
    /**
     * The latest state of [ref]: the one the read model holds, else, before its first snapshot is written (or with
     * its snapshots off), the state its log holds (decision D246 ⑥); null when no commit of [ref] is known. A read
     * that replays the log runs under the node's [ReplayGate] (its log store's query bulkhead, review R3.4-2).
     */
    suspend fun latest(ref: AggregateRef): StateReadView?

    /**
     * The latest state of [ref] at version [minVersion] or later (review R4-Q, Q-13: read your writes): its snapshot
     * when it has reached [minVersion], else its log replayed from the snapshot to its head, as [at] a version is; and
     * while the log too is below [minVersion] (the command has not committed yet), read again until a bounded wait
     * runs out.
     *
     * @throws MinVersionNotReachedException when the aggregate stays below [minVersion] through the wait.
     */
    suspend fun latest(ref: AggregateRef, minVersion: Int): StateReadView?

    /**
     * The latest committed state of [ref], as [latest] reads it (review R2.3-6) but outside any [ReplayGate]: the
     * events model reads it while its query holds a permit of its store's bulkhead, which it must not wait for again.
     */
    suspend fun committed(ref: AggregateRef): StateReadView?

    /**
     * The committed state of [ref] at [version], or null when the log does not hold that version. Its replay runs
     * under the node's [ReplayGate] and folds a bounded number of streams (review R4-Q, Q-1): past the bound, it
     * throws [StateReplayLimitException].
     */
    suspend fun at(ref: AggregateRef, version: Int): StateReadView?

    /**
     * The committed state of [ref] as of [instant]: after the last commit at or before it by commit time; null when
     * none had committed by then. Replayed as [at] a version is.
     */
    suspend fun at(ref: AggregateRef, instant: Instant): StateReadView?

    /**
     * The commits of [ref] from version [fromVersion] up to [toVersion], at most [limit], in version order, each with
     * its events and the state after it; empty when the log holds none of those versions. Replayed as [at] a version
     * is, the state before [fromVersion] included.
     */
    suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView>

    @InternalWowApi
    companion object {
        /** Reads nothing: every state is not found. */
        @JvmField
        val NONE: StateReads = object : StateReads {
            override suspend fun latest(ref: AggregateRef): StateReadView? = null

            override suspend fun latest(ref: AggregateRef, minVersion: Int): StateReadView? =
                throw MinVersionNotReachedException(ref, minVersion, 0)

            override suspend fun committed(ref: AggregateRef): StateReadView? = null

            override suspend fun at(ref: AggregateRef, version: Int): StateReadView? = null

            override suspend fun at(ref: AggregateRef, instant: Instant): StateReadView? = null

            override suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView> =
                emptyList()
        }
    }
}

/**
 * How a node's state reads that replay the log run (reviews R3.4-2, R4-Q Q-1): [KernelStateReads] replays an
 * aggregate's latest state without a snapshot, a state at a version or a time, and a trace inside [replay], which the
 * host runs under the query bulkhead of the aggregate's log store, so that point reads cannot take every connection
 * the store's appends draw from.
 */
@InternalWowApi
interface ReplayGate {
    /** Runs [read], the replay of [ref]'s log, or throws to refuse it (the bulkhead's `wow.query.busy`). */
    suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T

    @InternalWowApi
    companion object {
        /** Runs every replay at once. */
        @JvmField
        val OPEN: ReplayGate = object : ReplayGate {
            override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T = read()

            override fun toString(): String = "ReplayGate.OPEN"
        }
    }
}

/**
 * A state read of [ref] that would replay more than [limit] streams of its log (review R4-Q, Q-1): refused before the
 * stream past [limit] is folded. The state exists; it is only too far from the snapshot, or from version 1, to be
 * replayed for a point read, which an entry answers as the client's request asking too much.
 */
@InternalWowApi
class StateReplayLimitException(val ref: AggregateRef, val limit: Int, cause: Throwable? = null) :
    RuntimeException("Reading this state of $ref replays more than $limit commits of its log.", cause)

/** An aggregate's state at [version] with what the kernel keeps beside it, as a state read answers it. */
@InternalWowApi
data class StateReadView(
    val ref: AggregateRef,
    val version: Int,
    val scope: Scope,
    val deleted: Boolean,
    val tags: Map<String, List<String>>,
    val state: JsonNode,
)

/**
 * One commit of a trace (decision D40): the stream at [version], committed at [commitTime] (epoch milliseconds) for
 * the command [commandId] of type [commandType] sent with [requestId] in the name of [operator] (decision D163), its
 * [events], and the aggregate after it: its [scope], [deleted] mark, [tags] and [state].
 */
@InternalWowApi
data class CommitView(
    val version: Int,
    val commitTime: Long,
    val commandId: String,
    val requestId: String,
    val commandType: String,
    val events: List<EventView>,
    val scope: Scope,
    val deleted: Boolean,
    val tags: Map<String, List<String>>,
    val state: JsonNode,
    val operator: Operator? = null,
)

/** An event of a commit: its [name] and [body] as the model reads it now, upgraded to [revision] (decision D82). */
@InternalWowApi
data class EventView(val name: String, val revision: Int, val body: JsonNode)

/**
 * [StateReads] of the contexts a node serves: the latest state is the snapshot ([SnapshotStore], the default read
 * model, design 03 §3), else, while an aggregate has none, its log replayed by the context's [Kernel] from version 1
 * ([Kernel.stateAt], as the write side loads a state without a snapshot; decision D246 ⑥) under [gate]; a state at a
 * version or a time, and a trace, are the log replayed ([Kernel.stateAt], [Kernel.trace]) from the snapshot when it
 * comes before what is asked, else from version 1, under [gate] too (review R4-Q, Q-1). Every replay but [committed]'s
 * folds the log as it reads it and at most [maxReplay] streams of it: a read that needs more is refused with
 * [StateReplayLimitException].
 *
 * A snapshot behind the log's head is the latest state as it is: the snapshot is the read model, which the default
 * wait (`SNAPSHOT`, decision D69) has caught up before a command's answer. A read that asks for a minimum version
 * ([latest] of a version, review R4-Q, Q-13) catches a lagging snapshot up with the log instead, under [gate] and
 * [maxReplay] too, and waits at most [minVersionWait] for a version not committed yet. An aggregate whose model [registry] does
 * not know, or whose context has no [ContextReads], is not found, and so is one with no commit. States are trees of
 * Wow's JSON (decision D122), so a decimal keeps its digits and its scale.
 */
@InternalWowApi
class KernelStateReads(
    private val registry: ModelRegistry,
    contexts: Map<String, ContextReads>,
    private val gate: ReplayGate = ReplayGate.OPEN,
    private val maxReplay: Int = DEFAULT_MAX_REPLAY,
    private val minVersionWait: Duration = DEFAULT_MIN_VERSION_WAIT,
) : StateReads {
    init {
        require(maxReplay >= 1) { "A state read replays at least one stream." }
        require(!minVersionWait.isNegative()) { "A state read waits no negative time for its minimum version." }
    }

    private val contexts: Map<String, ContextReads> = contexts.toMap()

    /** What [KernelStateReads] reads a context from: its kernel and its snapshot store. */
    @InternalWowApi
    class ContextReads(val kernel: Kernel, val snapshots: SnapshotStore)

    override suspend fun latest(ref: AggregateRef): StateReadView? = latest(ref, gate, maxReplay)

    override suspend fun committed(ref: AggregateRef): StateReadView? = latest(ref, ReplayGate.OPEN, Int.MAX_VALUE)

    override suspend fun latest(ref: AggregateRef, minVersion: Int): StateReadView? {
        require(minVersion >= 1) { "A committed state has a version from 1." }
        val reads = contexts[ref.type.context] ?: return null
        val model = registry.find(ref.type)?.erased() ?: return null
        val deadline = TimeSource.Monotonic.markNow() + minVersionWait
        var snapshot = reads.snapshots.load(ref)
        while (true) {
            snapshot?.takeIf { it.version >= minVersion }?.let { return it.view() }
            val caughtUp = gate.replay(ref) {
                bounded { reads.kernel.stateAt(model, ref, Instant.MAX, snapshot, maxReplay) }
            }
            if (caughtUp != null && caughtUp.version >= minVersion) return caughtUp.view(ref)
            if (deadline.hasPassedNow()) {
                throw MinVersionNotReachedException(ref, minVersion, caughtUp?.version ?: 0)
            }
            delay(MIN_VERSION_POLL.coerceAtMost(-deadline.elapsedNow()))
            snapshot = reads.snapshots.load(ref)
        }
    }

    /** The snapshot of [ref], else its log replayed under [gate], at most [maxReplay] streams of it. */
    private suspend fun latest(ref: AggregateRef, gate: ReplayGate, maxReplay: Int): StateReadView? {
        val reads = contexts[ref.type.context] ?: return null
        val model = registry.find(ref.type)?.erased() ?: return null
        reads.snapshots.load(ref)?.let { return it.view() }
        return gate.replay(ref) {
            bounded { reads.kernel.stateAt(model, ref, Instant.MAX, maxReplay = maxReplay) }
        }?.view(ref)
    }

    override suspend fun at(ref: AggregateRef, version: Int): StateReadView? {
        require(version >= 1) { "A committed state has a version from 1." }
        return replayed(ref) { kernel, model, snapshot -> kernel.stateAt(model, ref, version, snapshot, maxReplay) }
    }

    override suspend fun at(ref: AggregateRef, instant: Instant): StateReadView? =
        replayed(ref) { kernel, model, snapshot -> kernel.stateAt(model, ref, instant, snapshot, maxReplay) }

    override suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView> {
        val reads = contexts[ref.type.context] ?: return emptyList()
        val model = registry.find(ref.type)?.erased() ?: return emptyList()
        val snapshot = reads.snapshots.load(ref)
        val commits = gate.replay(ref) {
            bounded { reads.kernel.trace(model, ref, fromVersion, toVersion, limit, snapshot, maxReplay) }
        }
        return commits.map { commit ->
            val stream = commit.stream
            CommitView(
                version = commit.version,
                commitTime = stream.commitTime,
                commandId = stream.commandId,
                requestId = stream.requestId,
                commandType = stream.commandType,
                events = commit.events.map { event ->
                    val type = model.eventType(event.javaClass)
                    EventView(type.name, type.revision, WowJson.mapper.valueToTree(event))
                },
                scope = commit.scope,
                deleted = commit.deleted,
                tags = commit.tags,
                state = commit.state,
                operator = stream.operator,
            )
        }
    }

    /**
     * The view of the state [read] gives under [gate] from the context's kernel, its model and the aggregate's
     * snapshot.
     */
    private suspend fun replayed(
        ref: AggregateRef,
        read: suspend (Kernel, AggregateModel<Any>, snapshot: SnapshotRecord?) -> CachedState?,
    ): StateReadView? {
        val reads = contexts[ref.type.context] ?: return null
        val model = registry.find(ref.type)?.erased() ?: return null
        val snapshot = reads.snapshots.load(ref)
        return gate.replay(ref) { bounded { read(reads.kernel, model, snapshot) } }?.view(ref)
    }

    /** [replay]'s result; a replay past the kernel's bound is this read's [StateReplayLimitException]. */
    private inline fun <T> bounded(replay: () -> T): T = try {
        replay()
    } catch (tooLong: ReplayLimitExceededException) {
        throw StateReplayLimitException(tooLong.ref, tooLong.limit, tooLong)
    }

    @InternalWowApi
    companion object {
        /**
         * The most streams a state read replays by default (review R4-Q, Q-1): a point read of a history longer than
         * this, counted from the snapshot when it fits, else from version 1, is refused rather than replayed.
         */
        const val DEFAULT_MAX_REPLAY: Int = 10_000

        /**
         * How long a state read waits by default for an aggregate to reach the minimum version it asks for (review
         * R4-Q, Q-13): a command answered at `ACCEPTED` commits within it.
         */
        val DEFAULT_MIN_VERSION_WAIT: Duration = 2.seconds

        /** How often a state read waiting for its minimum version reads again. */
        private val MIN_VERSION_POLL: Duration = 50.milliseconds

        @Suppress("UNCHECKED_CAST") // The kernel takes the model and its state together; nothing here names the type.
        private fun AggregateModel<*>.erased(): AggregateModel<Any> = this as AggregateModel<Any>

        private fun SnapshotRecord.view(): StateReadView = StateReadView(
            ref = ref,
            version = version,
            scope = scope,
            deleted = deleted,
            tags = tags,
            state = WowJson.mapper.readTree(state),
        )

        private fun CachedState.view(ref: AggregateRef): StateReadView = StateReadView(
            ref = ref,
            version = version,
            scope = scope,
            deleted = deleted,
            tags = tags,
            state = WowJson.mapper.valueToTree(state),
        )
    }
}

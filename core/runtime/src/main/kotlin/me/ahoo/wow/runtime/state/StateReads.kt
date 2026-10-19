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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * The state point reads of the node's aggregates (decisions D40, D112, D231): the public port's
 * `GET /{context}/{aggregate}/{id}/state` reads [latest], `?version=` and `?at=` read [at], `GET …/{id}/state/trace`
 * reads [trace]; the events query model decides which aggregates a caller sees on [committed] (reviews A3-11,
 * R2.3-6). They read what is committed, for no caller: an entry checks the caller's scope against the state's and
 * admits and masks the state as the snapshot queries do (`me.ahoo.wow.query.state.StateVisibility`).
 */
@InternalWowApi
interface StateReads {
    /** The latest state of [ref] the read model holds, or null when it holds none. */
    suspend fun latest(ref: AggregateRef): StateReadView?

    /**
     * The latest committed state of [ref]: the one the read model holds ([latest]), else, before its first snapshot
     * is written, the state its log holds (review R2.3-6); null when no commit of [ref] is known.
     */
    suspend fun committed(ref: AggregateRef): StateReadView?

    /** The committed state of [ref] at [version], or null when the log does not hold that version. */
    suspend fun at(ref: AggregateRef, version: Int): StateReadView?

    /**
     * The committed state of [ref] as of [instant]: after the last commit at or before it by commit time; null when
     * none had committed by then.
     */
    suspend fun at(ref: AggregateRef, instant: Instant): StateReadView?

    /**
     * The commits of [ref] from version [fromVersion] up to [toVersion], at most [limit], in version order, each with
     * its events and the state after it; empty when the log holds none of those versions.
     */
    suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView>

    @InternalWowApi
    companion object {
        /** Reads nothing: every state is not found. */
        @JvmField
        val NONE: StateReads = object : StateReads {
            override suspend fun latest(ref: AggregateRef): StateReadView? = null

            override suspend fun committed(ref: AggregateRef): StateReadView? = null

            override suspend fun at(ref: AggregateRef, version: Int): StateReadView? = null

            override suspend fun at(ref: AggregateRef, instant: Instant): StateReadView? = null

            override suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView> =
                emptyList()
        }
    }
}

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
 * model, design 03 §3); a state at a version or a time, and a trace, are the log replayed by the context's [Kernel]
 * ([Kernel.stateAt], [Kernel.trace]) from the snapshot when it comes before what is asked, else from version 1.
 *
 * An aggregate whose model [registry] does not know, or whose context has no [ContextReads], is not found. So is the
 * latest state of an aggregate without a snapshot: reads come from the read model, not the log; its [committed] state
 * is the log replayed then. States are trees of Wow's JSON (decision D122), so a decimal keeps its digits and its
 * scale.
 */
@InternalWowApi
class KernelStateReads(private val registry: ModelRegistry, contexts: Map<String, ContextReads>) : StateReads {
    private val contexts: Map<String, ContextReads> = contexts.toMap()

    /** What [KernelStateReads] reads a context from: its kernel and its snapshot store. */
    @InternalWowApi
    class ContextReads(val kernel: Kernel, val snapshots: SnapshotStore)

    override suspend fun latest(ref: AggregateRef): StateReadView? {
        val reads = contexts[ref.type.context] ?: return null
        registry.find(ref.type) ?: return null
        return reads.snapshots.load(ref)?.view()
    }

    override suspend fun committed(ref: AggregateRef): StateReadView? {
        val reads = contexts[ref.type.context] ?: return null
        val model = registry.find(ref.type)?.erased() ?: return null
        reads.snapshots.load(ref)?.let { return it.view() }
        return reads.kernel.stateAt(model, ref, Instant.MAX)?.view(ref)
    }

    override suspend fun at(ref: AggregateRef, version: Int): StateReadView? {
        require(version >= 1) { "A committed state has a version from 1." }
        return replayed(ref) { kernel, model, snapshot -> kernel.stateAt(model, ref, version, snapshot) }
    }

    override suspend fun at(ref: AggregateRef, instant: Instant): StateReadView? =
        replayed(ref) { kernel, model, snapshot -> kernel.stateAt(model, ref, instant, snapshot) }

    override suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView> {
        val reads = contexts[ref.type.context] ?: return emptyList()
        val model = registry.find(ref.type)?.erased() ?: return emptyList()
        return reads.kernel.trace(model, ref, fromVersion, toVersion, limit, reads.snapshots.load(ref)).map { commit ->
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

    /** The view of the state [read] gives from the context's kernel, its model and the aggregate's snapshot. */
    private suspend inline fun replayed(
        ref: AggregateRef,
        read: (Kernel, AggregateModel<Any>, snapshot: SnapshotRecord?) -> CachedState?,
    ): StateReadView? {
        val reads = contexts[ref.type.context] ?: return null
        val model = registry.find(ref.type)?.erased() ?: return null
        return read(reads.kernel, model, reads.snapshots.load(ref))?.view(ref)
    }

    private companion object {
        @Suppress("UNCHECKED_CAST") // The kernel takes the model and its state together; nothing here names the type.
        fun AggregateModel<*>.erased(): AggregateModel<Any> = this as AggregateModel<Any>

        fun SnapshotRecord.view(): StateReadView = StateReadView(
            ref = ref,
            version = version,
            scope = scope,
            deleted = deleted,
            tags = tags,
            state = WowJson.mapper.readTree(state),
        )

        fun CachedState.view(ref: AggregateRef): StateReadView = StateReadView(
            ref = ref,
            version = version,
            scope = scope,
            deleted = deleted,
            tags = tags,
            state = WowJson.mapper.valueToTree(state),
        )
    }
}

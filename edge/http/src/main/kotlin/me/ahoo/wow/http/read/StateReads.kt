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

package me.ahoo.wow.http.read

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
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
 * The state reads of the public port (decisions D40, D112): `GET /{context}/{aggregate}/{id}/state` reads [latest],
 * `?version=` and `?at=` read [at], `GET …/{id}/state/trace` reads [trace]. The edge checks the caller's scope against
 * the state's.
 */
interface StateReads {
    /** The latest state of [ref] the read model holds, or null when it holds none. */
    suspend fun latest(ref: AggregateRef): StateView?

    /** The committed state of [ref] at [version], or null when the log does not hold that version. */
    suspend fun at(ref: AggregateRef, version: Int): StateView?

    /**
     * The committed state of [ref] as of [instant]: after the last commit at or before it by commit time; null when
     * none had committed by then.
     */
    suspend fun at(ref: AggregateRef, instant: Instant): StateView?

    /**
     * The commits of [ref] from version [fromVersion] up to [toVersion], at most [limit], in version order, each with
     * its events and the state after it; empty when the log holds none of those versions.
     */
    suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView>

    companion object {
        /** Reads nothing: every state is not found. */
        @JvmField
        val NONE: StateReads = object : StateReads {
            override suspend fun latest(ref: AggregateRef): StateView? = null

            override suspend fun at(ref: AggregateRef, version: Int): StateView? = null

            override suspend fun at(ref: AggregateRef, instant: Instant): StateView? = null

            override suspend fun trace(ref: AggregateRef, fromVersion: Int, toVersion: Int, limit: Int): List<CommitView> =
                emptyList()
        }
    }
}

/** An aggregate's state at [version] with what the kernel keeps beside it, as a state read answers it. */
data class StateView(
    val ref: AggregateRef,
    val version: Int,
    val scope: Scope,
    val deleted: Boolean,
    val tags: Map<String, List<String>>,
    val state: JsonNode,
)

/**
 * One commit of a trace (decision D40): the stream at [version], committed at [commitTime] (epoch milliseconds) for
 * the command [commandId] of type [commandType] sent with [requestId], its [events], and the aggregate after it: its
 * [scope], [deleted] mark, [tags] and [state].
 */
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
)

/** An event of a commit: its [name] and [body] as the model reads it now, upgraded to [revision] (decision D82). */
data class EventView(val name: String, val revision: Int, val body: JsonNode)

/**
 * [StateReads] of the contexts a node serves: the latest state is the snapshot ([SnapshotStore], the default read
 * model, design 03 §3); a state at a version or a time, and a trace, are the log replayed by the context's [Kernel]
 * ([Kernel.stateAt], [Kernel.trace]) from the snapshot when it comes before what is asked, else from version 1.
 *
 * An aggregate whose model [registry] does not know, or whose context has no [ContextReads], is not found. So is the
 * latest state of an aggregate without a snapshot: reads come from the read model, not the log. States are trees of
 * Wow's JSON (decision D122), so a decimal keeps its digits and its scale.
 */
class KernelStateReads(private val registry: ModelRegistry, contexts: Map<String, ContextReads>) : StateReads {
    private val contexts: Map<String, ContextReads> = contexts.toMap()

    /** What [KernelStateReads] reads a context from: its kernel and its snapshot store. */
    class ContextReads(val kernel: Kernel, val snapshots: SnapshotStore)

    override suspend fun latest(ref: AggregateRef): StateView? {
        val reads = contexts[ref.type.context] ?: return null
        registry.find(ref.type) ?: return null
        val snapshot = reads.snapshots.load(ref) ?: return null
        return StateView(
            ref = snapshot.ref,
            version = snapshot.version,
            scope = snapshot.scope,
            deleted = snapshot.deleted,
            tags = snapshot.tags,
            state = WowJson.mapper.readTree(snapshot.state),
        )
    }

    override suspend fun at(ref: AggregateRef, version: Int): StateView? {
        require(version >= 1) { "A committed state has a version from 1." }
        return replayed(ref) { kernel, model, snapshot -> kernel.stateAt(model, ref, version, snapshot) }
    }

    override suspend fun at(ref: AggregateRef, instant: Instant): StateView? =
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
            )
        }
    }

    /** The view of the state [read] gives from the context's kernel, its model and the aggregate's snapshot. */
    private suspend inline fun replayed(
        ref: AggregateRef,
        read: (Kernel, AggregateModel<Any>, snapshot: SnapshotRecord?) -> CachedState?,
    ): StateView? {
        val reads = contexts[ref.type.context] ?: return null
        val model = registry.find(ref.type)?.erased() ?: return null
        val state = read(reads.kernel, model, reads.snapshots.load(ref)) ?: return null
        return StateView(
            ref = ref,
            version = state.version,
            scope = state.scope,
            deleted = state.deleted,
            tags = state.tags,
            state = WowJson.mapper.valueToTree(state.state),
        )
    }

    private companion object {
        @Suppress("UNCHECKED_CAST") // The kernel takes the model and its state together; nothing here names the type.
        fun AggregateModel<*>.erased(): AggregateModel<Any> = this as AggregateModel<Any>
    }
}

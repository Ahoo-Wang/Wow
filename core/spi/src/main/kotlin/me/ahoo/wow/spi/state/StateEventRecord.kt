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

package me.ahoo.wow.spi.state

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.log.Transport
import tools.jackson.databind.JsonNode

/**
 * One event of a [StateEventRecord]: its type [name] (`context.aggregate.Event`) and the [payload] as a JSON tree,
 * both of the current [revision] of its schema: a stored event of an older revision is upgraded before it is
 * published, and one stored under a former name is published under the current one.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class StateEventPayload @InternalWowApi constructor(val name: String, val revision: Int, val payload: JsonNode) {
    init {
        require(name.isNotBlank()) { "An event needs a name." }
        require(revision >= 1) { "An event revision starts at 1." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is StateEventPayload && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "StateEventPayload(name=$name, revision=$revision, payload=$payload)"

    private fun valueFields(): List<Any?> = listOf(name, revision, payload)

    companion object {
        /** A StateEventPayload of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(name: String, revision: Int, payload: JsonNode): StateEventPayload =
            StateEventPayload(name, revision, payload)
    }
}

/**
 * The published unit of a context: one committed event stream of an aggregate
 * with the aggregate's state after it, what the Kafka Sink writes to the aggregate type's topic and what another
 * context's subscription reads. It lives in `:spi` because the backends that write or read it depend on `:spi` only.
 *
 * - [ref] and [version] identify the commit; [id] (`ref@version`) is what a reader deduplicates by
 *   (publishing is at least once).
 * - [commandId], [requestId], [commandType], [commitTime], [scope], [causation], [headers] and [operator] are the
 *   stream's facts; [events] are its events at their current revision.
 * - [state] is the aggregate's state after the stream; null when the sink publishes the aggregate type without it
 *   (`PublishedManifest.includeState`). [deleted] and [tags] are the aggregate's after the stream, as
 *   the kernel evolved them, published either way.
 * - [transport] is the stream's transport metadata: the wait context and the trace. It is not a fact:
 *   the envelope carries it beside the record ([me.ahoo.wow.spi.transport.Envelope.transport]), never inside it.
 * - [imported] marks a commit migrate-data imported from 9.x, at or before the context's cut-over
 *   ([me.ahoo.wow.spi.subscription.ImportCutover]): 9.x already acted on it, so a peer's saga or reactor passes over
 *   it, while a projection or a sink takes it. False for every commit a V10 service made.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class StateEventRecord @InternalWowApi constructor(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val commandType: String,
    val commitTime: Long,
    val events: List<StateEventPayload>,
    val state: PublishedState? = null,
    val scope: Scope = Scope.NONE,
    val deleted: Boolean = false,
    val tags: Map<String, List<String>> = emptyMap(),
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val operator: Operator? = null,
    val transport: Transport? = null,
    val imported: Boolean = false,
) {
    init {
        require(version >= 1) { "A committed version starts at 1." }
        require(commandId.isNotBlank()) { "A state event names its command." }
        require(commandType.isNotBlank()) { "A state event names the type of its command." }
        require(events.isNotEmpty()) { "A state event holds at least one event." }
        require(state == null || state.version == version) {
            "The state of $ref@$version is of version ${state?.version}."
        }
    }

    /** The identity of the commit, `ref@version`: what a reader deduplicates by. */
    val id: String get() = idOf(ref, version)

    /** This StateEventRecord with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        ref: AggregateRef = this.ref,
        version: Int = this.version,
        commandId: String = this.commandId,
        requestId: String = this.requestId,
        commandType: String = this.commandType,
        commitTime: Long = this.commitTime,
        events: List<StateEventPayload> = this.events,
        state: PublishedState? = this.state,
        scope: Scope = this.scope,
        deleted: Boolean = this.deleted,
        tags: Map<String, List<String>> = this.tags,
        causation: Causation? = this.causation,
        headers: Map<String, String> = this.headers,
        operator: Operator? = this.operator,
        transport: Transport? = this.transport,
        imported: Boolean = this.imported,
    ): StateEventRecord =
        StateEventRecord(
            ref,
            version,
            commandId,
            requestId,
            commandType,
            commitTime,
            events,
            state,
            scope,
            deleted,
            tags,
            causation,
            headers,
            operator,
            transport,
            imported,
        )

    override fun equals(other: Any?): Boolean =
        this === other || other is StateEventRecord && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "StateEventRecord(ref=$ref, version=$version, commandId=$commandId, requestId=$requestId, " +
            "commandType=$commandType, commitTime=$commitTime, events=$events, state=$state, scope=$scope, " +
            "deleted=$deleted, tags=$tags, causation=$causation, headers=$headers, operator=$operator, " +
            "transport=$transport, imported=$imported)"

    private fun valueFields(): List<Any?> = listOf(
        ref,
        version,
        commandId,
        requestId,
        commandType,
        commitTime,
        events,
        state,
        scope,
        deleted,
        tags,
        causation,
        headers,
        operator,
        transport,
        imported,
    )

    companion object {
        /** The identity of [ref]'s commit at [version]: `ref@version`. */
        fun idOf(ref: AggregateRef, version: Int): String = "$ref@$version"

        /** A StateEventRecord of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            ref: AggregateRef,
            version: Int,
            commandId: String,
            requestId: String,
            commandType: String,
            commitTime: Long,
            events: List<StateEventPayload>,
            state: PublishedState? = null,
            scope: Scope = Scope.NONE,
            deleted: Boolean = false,
            tags: Map<String, List<String>> = emptyMap(),
            causation: Causation? = null,
            headers: Map<String, String> = emptyMap(),
            operator: Operator? = null,
            transport: Transport? = null,
            imported: Boolean = false,
        ): StateEventRecord =
            StateEventRecord(
                ref,
                version,
                commandId,
                requestId,
                commandType,
                commitTime,
                events,
                state,
                scope,
                deleted,
                tags,
                causation,
                headers,
                operator,
                transport,
                imported,
            )
    }
}

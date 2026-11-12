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
import me.ahoo.wow.spi.log.EventStreamRecord
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
 *   (`PublishedManifest.includeState`). [deleted] is the aggregate's after the stream, as the kernel evolved it, and
 *   [access] its access attributes, the built-in owner and space among them, in their stored form
 *   ([me.ahoo.wow.spi.access.AccessValues]): the stream's, which a stamp keeps current; null when the stream has none
 *   and the state is not at hand. Both are published either way, [access] with [accessVersion], the version it is of
 *   (the stream's own by default; a later one's when a stamp wrote them), null with it.
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
    val access: Map<String, Any?>? = null,
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val operator: Operator? = null,
    val transport: Transport? = null,
    val imported: Boolean = false,
    val accessVersion: Int? = if (access == null) null else version,
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

    /**
     * When the commit was actually written (epoch milliseconds): [commitTime], unless its creation command declared
     * the commit's time ([EventStreamRecord.WRITTEN_AT] among [headers]), which the time it was written then follows.
     */
    val writtenAt: Long
        get() = headers[EventStreamRecord.WRITTEN_AT]?.toLongOrNull() ?: commitTime

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
        access: Map<String, Any?>? = this.access,
        causation: Causation? = this.causation,
        headers: Map<String, String> = this.headers,
        operator: Operator? = this.operator,
        transport: Transport? = this.transport,
        imported: Boolean = this.imported,
        accessVersion: Int? = this.accessVersion,
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
            access,
            causation,
            headers,
            operator,
            transport,
            imported,
            accessVersion,
        )

    override fun equals(other: Any?): Boolean =
        this === other || other is StateEventRecord && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "StateEventRecord(ref=$ref, version=$version, commandId=$commandId, requestId=$requestId, " +
            "commandType=$commandType, commitTime=$commitTime, events=$events, state=$state, scope=$scope, " +
            "deleted=$deleted, access=$access, causation=$causation, headers=$headers, operator=$operator, " +
            "transport=$transport, imported=$imported, accessVersion=$accessVersion)"

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
        access,
        causation,
        headers,
        operator,
        transport,
        imported,
        accessVersion,
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
            access: Map<String, Any?>? = null,
            causation: Causation? = null,
            headers: Map<String, String> = emptyMap(),
            operator: Operator? = null,
            transport: Transport? = null,
            imported: Boolean = false,
            accessVersion: Int? = if (access == null) null else version,
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
                access,
                causation,
                headers,
                operator,
                transport,
                imported,
                accessVersion,
            )
    }
}

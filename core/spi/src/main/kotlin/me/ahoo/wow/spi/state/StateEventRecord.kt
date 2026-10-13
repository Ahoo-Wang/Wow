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
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.log.Transport
import tools.jackson.databind.JsonNode

/**
 * One event of a [StateEventRecord]: its type [name] (`context.aggregate.Event`) and the [payload] as a JSON tree,
 * both of the current [revision] of its schema: a stored event of an older revision is upgraded before it is
 * published (design 04 §4, finding F10), and one stored under a former name is published under the current one.
 */
data class StateEventPayload(val name: String, val revision: Int, val payload: JsonNode) {
    init {
        require(name.isNotBlank()) { "An event needs a name." }
        require(revision >= 1) { "An event revision starts at 1." }
    }
}

/**
 * The published unit of a context (design 08 §5, decisions D58, D132): one committed event stream of an aggregate
 * with the aggregate's state after it, what the Kafka Sink writes to the aggregate type's topic and what another
 * context's subscription reads. It lives in `:spi` because the backends that write or read it depend on `:spi` only.
 *
 * - [ref] and [version] identify the commit; [id] (`ref@version`) is what a reader deduplicates by (decision D45:
 *   publishing is at least once).
 * - [commandId], [requestId], [commandType], [commitTime], [scope], [causation] and [headers] are the stream's facts
 *   (design 08 §4); [events] are its events at their current revision.
 * - [state] is the aggregate's state after the stream; null when the sink publishes the aggregate type without it
 *   (`PublishedManifest.includeState`, decision D140). [deleted] and [tags] are the aggregate's after the stream, as
 *   the kernel evolved them, published either way.
 * - [transport] is the stream's transport metadata (decision D25): the wait context and the trace. It is not a fact:
 *   the envelope carries it beside the record ([me.ahoo.wow.spi.transport.Envelope.transport]), never inside it.
 */
data class StateEventRecord(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val commandType: String,
    val commitTime: Long,
    val events: List<StateEventPayload>,
    val state: StateView? = null,
    val scope: Scope = Scope.NONE,
    val deleted: Boolean = false,
    val tags: Map<String, List<String>> = emptyMap(),
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val transport: Transport? = null,
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

    /** The identity of the commit, `ref@version` (design 08 §2): what a reader deduplicates by. */
    val id: String get() = idOf(ref, version)

    companion object {
        /** The identity of [ref]'s commit at [version]: `ref@version`. */
        fun idOf(ref: AggregateRef, version: Int): String = "$ref@$version"
    }
}

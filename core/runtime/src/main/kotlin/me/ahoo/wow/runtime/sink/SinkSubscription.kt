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

package me.ahoo.wow.runtime.sink

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.runtime.state.StateViewReader
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView

/**
 * The subscription that writes the commits of one aggregate [type] to a [sink] (design 04 §4, decisions D58, D133):
 * a subscription like any other, named [name], so it runs on each partition's owner, checkpoints, retries, parks
 * and signals as every subscription does; the sink only writes.
 *
 * - **What it writes**: one [StateEventRecord] per commit, in version order per aggregate: the stream's facts, its
 *   events at their current revision (an event stored at an older revision or under a former name is upgraded
 *   through the model, finding F10), and the aggregate's state after the commit, deleted mark and tags. The state is
 *   left out when [includeState] is false (decision D140); the deleted mark and tags are written either way.
 * - **The state**: read through the context's shared decoder ([EntryDecoder.stateEvents]) by [reader], whose name
 *   must be [name], so the sink is one of the context's state consumers: the view the executor took when the stream
 *   committed, else the nearest view or snapshot evolved with the log's tail, else the log replayed. It is mapped to
 *   the published form, a JSON tree with its schema's fingerprint ([StateView]).
 * - **Transport**: the entry's wait context and trace go with the record (decision D25); its reservations stay in
 *   the context.
 * - **At least once** (decision D45): an entry is done, and the checkpoint may pass it, only once [Sink.write]
 *   returned, which is once the records are durable (the broker acknowledged them). A crash, a retry or an old owner
 *   beside the new one writes a record again; a reader drops it by [StateEventRecord.id].
 */
class SinkSubscription(
    val name: String,
    val type: AggregateType,
    private val sink: Sink,
    private val reader: StateViewReader,
    private val includeState: Boolean = true,
    startFrom: StartFrom = StartFrom.BEGINNING,
) {
    private val model: AggregateModel<*> = reader.modelOf(type)
    private val codec = reader.codec

    init {
        require(reader.reader == name) { "The sink $name reads the state views as ${reader.reader}, not as itself." }
    }

    /** The subscription to register: of [type]'s streams only, every event of them. */
    val subscription: Subscription = Subscription(
        name = name,
        filter = SubscriptionFilter(type.context, setOf(type.name)),
        startFrom = startFrom,
        decoder = EntryDecoder.stateEvents(reader, decodeEvents = false),
        kind = FunctionKind.PROJECT,
        handler = ::write,
    )

    private suspend fun write(entry: SubscribedEntry) {
        sink.write(listOf(recordOf(entry)))
    }

    /** The record of [entry]. */
    internal suspend fun recordOf(entry: SubscribedEntry): StateEventRecord {
        val stream = entry.stream
        val view = entry.state()
        return StateEventRecord(
            ref = stream.ref,
            version = stream.version,
            commandId = stream.commandId,
            requestId = stream.requestId,
            commandType = stream.commandType,
            commitTime = stream.commitTime,
            events = stream.events.map(::payloadOf),
            state = if (includeState) {
                StateView(view.version, codec.stateSchemaFingerprint(model), WowJson.mapper.readTree(view.state))
            } else {
                null
            },
            scope = stream.scope,
            deleted = view.deleted,
            tags = view.tags,
            causation = stream.causation,
            headers = stream.headers,
            transport = entry.transport?.let(::published),
        )
    }

    /** [record] at its event type's current name and revision: as stored when it is, upgraded through the model else. */
    private fun payloadOf(record: EventRecord): StateEventPayload {
        val current = model.eventType(record.name)
        val text = if (record.name == current.name && record.revision == current.revision) {
            record.payload
        } else {
            codec.encode(model, codec.decode(model, record)).payload
        }
        return StateEventPayload(current.name, current.revision, WowJson.mapper.readTree(text))
    }

    /** What of [transport] goes with a published record: the wait context and the trace; null when neither. */
    private fun published(transport: Transport): Transport? =
        Transport(wait = transport.wait, trace = transport.trace)
            .takeIf { it.wait != null || it.trace.isNotEmpty() }

    override fun toString(): String = "SinkSubscription($name)"
}

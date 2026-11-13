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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.state.SharedStateReader
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.RunEntry
import me.ahoo.wow.runtime.subscription.RunHandled
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord

/**
 * The subscription that writes the commits of one aggregate [type] to a [sink]:
 * a subscription like any other, named [name], so it runs on each partition's owner, checkpoints, retries, parks
 * and signals as every subscription does; the sink only writes.
 *
 * - **What it writes**: one [StateEventRecord] per commit, in version order per aggregate: the stream's facts, its
 *   events at their current revision (an event stored at an older revision or under a former name is upgraded
 *   through the model), and the aggregate's state after the commit and deleted mark. The state is
 *   left out when [includeState] is false; the deleted mark is written either way.
 * - **The state**: read through the context's shared decoder ([EntryDecoder.stateEvents]) by [reader], whose name
 *   must be [name], so the sink is one of the context's state consumers: the view the executor took when the stream
 *   committed, else the nearest view or snapshot evolved with the log's tail, else the log replayed. It is mapped to
 *   the published form, a JSON tree with its schema's fingerprint ([PublishedState]).
 * - **Transport**: the entry's wait context and trace go with the record, the trace of the sink's
 *   handling instead when it is observed; its reservations stay in the context.
 * - **Imported history**: an entry migrate-data imported from 9.x, at or before the context's cut-over, is written
 *   marked [StateEventRecord.imported], so a peer's saga or reactor passes over it and its projection takes it.
 * - **At least once**: an entry is done, and the checkpoint may pass it, only once [Sink.write]
 *   returned, which is once the records are durable (the broker acknowledged them). A crash, a retry or an old owner
 *   beside the new one writes a record again; a reader drops it by [StateEventRecord.id].
 * - **Batches**: a sink of [Sink.maxBatch] above 1 is handed runs: the subscription is a batching one
 *   ([Subscription.batching]), whose lane hands [writeRun] the run of an aggregate's entries waiting together, at
 *   most [Sink.maxBatch]; each entry's record is built in its own observation, and the runs of all lanes waiting
 *   together, of every partition, are written with one [Sink.write] ([SinkWrites], one per sink, one write in flight).
 *   A run answers how many of its entries are done: those whose records are durable,
 *   and those that have none. A sink of [Sink.maxBatch] 1 is handed one record a call, its entries handled one by one.
 */
internal class SinkSubscription(
    val name: String,
    val type: AggregateType,
    private val sink: Sink,
    private val reader: SharedStateReader,
    private val includeState: Boolean = true,
    startFrom: StartFrom = StartFrom.BEGINNING,
) {
    private val model: AggregateModel<*> = reader.modelOf(type)
    private val codec = reader.codec

    init {
        require(
            reader.reader == name
        ) { "The sink $name reads the shared states as ${reader.reader}, not as itself." }
    }

    /** The batched writes of a sink of [Sink.maxBatch] above 1, of all its partitions; null for a sink of 1. */
    private val writes: SinkWrites? = if (sink.maxBatch > 1) SinkWrites(sink, sink.maxBatch) else null

    /** The subscription to register: of [type]'s streams only, every event of them. */
    val subscription: Subscription = if (writes == null) {
        Subscription(
            name = name,
            filter = SubscriptionFilter(type.context, setOf(type.name)),
            startFrom = startFrom,
            decoder = EntryDecoder.stateEvents(reader, decodeEvents = false),
            kind = FunctionKind.SYSTEM,
            handler = ::write,
        )
    } else {
        Subscription.batching(
            name = name,
            filter = SubscriptionFilter(type.context, setOf(type.name)),
            maxRun = sink.maxBatch,
            startFrom = startFrom,
            decoder = EntryDecoder.stateEvents(reader, decodeEvents = false),
            runHandler = ::writeRun,
            handler = ::write,
        )
    }

    private suspend fun write(entry: SubscribedEntry) {
        recordOf(entry)?.let { sink.write(listOf(it)) }
    }

    /**
     * Writes [run]'s records, each built in its entry's observation, with the runs waiting in its lane; how many of its
     * entries are done. An entry whose record cannot be built fails there, after the records before it are written.
     */
    @Suppress("TooGenericExceptionCaught") // A record that cannot be built is its entry's failure.
    private suspend fun writeRun(run: List<RunEntry>): RunHandled {
        val records = ArrayList<StateEventRecord>(run.size)
        // The run's index of the entry each record is of.
        val owners = ArrayList<Int>(run.size)
        var built = run.size
        var unbuilt: Throwable? = null
        for ((index, item) in run.withIndex()) {
            val record = try {
                withContext(item.context) { recordOf(item.entry) }
            } catch (failure: Throwable) {
                if (NodeFatals.isFatal(failure)) {
                    throw failure
                }
                currentCoroutineContext().ensureActive()
                built = index
                unbuilt = failure
                break
            }
            if (record != null) {
                records += record
                owners += index
            }
        }
        val written = checkNotNull(writes) { "A sink of maxBatch 1 runs no run." }.write(records)
        val failure = written.failure ?: return RunHandled(built, unbuilt)
        if (NodeFatals.isFatal(failure)) {
            throw failure
        }
        return RunHandled(owners[written.done], failure)
    }

    /**
     * The record of [entry], its events at their current names and revisions without those the model retired;
     * null when every event of the stream is retired: nothing is written, and the sink moves past it.
     */
    internal suspend fun recordOf(entry: SubscribedEntry): StateEventRecord? {
        val events = entry.stream.events.filterNot { model.isRetired(it.name) }
        if (events.isEmpty()) {
            return null
        }
        val stream = entry.stream
        val view = entry.state()
        val (access, accessVersion) = accessOf(stream, view.state)
        return StateEventRecord(
            ref = stream.ref,
            version = stream.version,
            commandId = stream.commandId,
            requestId = stream.requestId,
            commandType = stream.commandType,
            commitTime = stream.commitTime,
            events = events.map { payloadOf(it, entry.imported) },
            // The view's text is the codec's own: written as it is, parsed only by a sink that reads the tree.
            state = if (includeState) {
                PublishedState.ofText(view.version, codec.stateSchemaFingerprint(model), view.state)
            } else {
                null
            },
            scope = stream.scope,
            deleted = view.deleted,
            access = access,
            causation = stream.causation,
            headers = stream.headers,
            operator = stream.operator,
            transport = published(entry.transport, currentCoroutineContext().trace),
            imported = entry.imported,
            accessVersion = accessVersion,
        )
    }

    /**
     * The access attributes after [stream], the built-in ones among them, and the version they are of: the stream's
     * own, of the version its stamp says, when it holds the model's current ones; else those of the state [stateText]
     * it left, with the stream's scope, of the stream's version.
     */
    private fun accessOf(stream: EventStreamRecord, stateText: String): Pair<Map<String, Any?>, Int?> {
        val current = stream.access?.takeIf { AccessValues.declared(it).keys == model.accessNames }
        if (current != null) {
            return current to stream.accessVersion
        }
        return AccessValues.ofStream(codec.accessAfter(model, stream, stateText), stream.scope) to stream.version
    }

    /**
     * [record] at its event type's current name and revision: as stored when it is, upgraded through the model else.
     * Its text is the codec's own, the stored payload as Wow's mapper wrote it, so it is published as it is and parsed
     * only by a sink that reads the tree. An [imported] commit's payload is the one text this node's codec did not
     * write (migrate-data's, from 9.x): it is parsed here, so a text that is not JSON fails its entry and is never
     * written into an envelope.
     */
    private fun payloadOf(record: EventRecord, imported: Boolean): StateEventPayload {
        val current = model.eventType(record.name)
        val text = if (record.name == current.name && record.revision == current.revision) {
            record.payload
        } else {
            codec.encode(model, codec.decode(model, record)).payload
        }
        return if (imported) {
            StateEventPayload(current.name, current.revision, WowJson.mapper.readTree(text))
        } else {
            StateEventPayload.ofText(current.name, current.revision, text)
        }
    }

    /**
     * What of [transport] goes with a published record: the wait context and the trace, the one the sink writes in
     * ([current]) when it has one; null when neither.
     */
    private fun published(transport: Transport?, current: Map<String, String>): Transport? {
        val trace = current.ifEmpty { transport?.trace.orEmpty() }
        val wait = transport?.wait
        return if (wait == null && trace.isEmpty()) null else Transport(wait = wait, trace = trace)
    }

    override fun toString(): String = "SinkSubscription($name)"
}

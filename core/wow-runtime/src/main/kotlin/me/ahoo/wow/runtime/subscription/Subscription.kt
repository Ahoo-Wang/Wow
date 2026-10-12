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

package me.ahoo.wow.runtime.subscription

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.runtime.wait.subscriptionFunction
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport

/**
 * A subscription (design 04 §1): a [name], a [filter] and exactly one handler. The checkpoint belongs to the
 * name, so one handler never shares a checkpoint with another; a replay or a blue-green rebuild is a new name
 * starting from the beginning (04 §3).
 *
 * [startFrom] applies only when the subscription has no checkpoint yet in a partition. [decoder] turns a committed
 * stream into what the handler receives.
 *
 * When [models] are given, the filter's event names are resolved through them ([SubscriptionFilter.resolve]): an
 * event listed under its current name also matches the streams that stored it under a former one, and the other
 * way round.
 *
 * [kind] says what the handler is (design 08 §7): it is reported as the [function] that handled a commit, and a wait
 * chain may continue only after a saga's hop (decision D62). A saga reports the commands it derived from each entry,
 * so it is made with [saga]; the constructor makes every other kind.
 *
 * A subscription that [coalesces] is handed only the latest of an aggregate's entries that wait together in its lane
 * (a batch): it keeps the latest of something per aggregate, so the earlier ones add nothing. Every entry of the
 * batch completes, and is signalled, only once the latest is handled, so a wait on an earlier version reaches its
 * hop when what holds the later one is written (design 03 §3: the snapshot subscription's merged writes).
 */
class Subscription private constructor(
    val name: String,
    filter: SubscriptionFilter,
    val startFrom: StartFrom,
    val decoder: EntryDecoder,
    models: Collection<AggregateModel<*>>,
    val kind: FunctionKind,
    private val handler: EntryHandler,
    internal val coalesces: Boolean = false,
) {
    constructor(
        name: String,
        filter: SubscriptionFilter,
        startFrom: StartFrom = StartFrom.BEGINNING,
        decoder: EntryDecoder = EntryDecoder.RECORDS,
        models: Collection<AggregateModel<*>> = emptyList(),
        kind: FunctionKind = FunctionKind.PROJECT,
        handler: suspend (SubscribedEntry) -> Unit,
    ) : this(
        name,
        filter,
        startFrom,
        decoder,
        models,
        kind.also { require(it != FunctionKind.SAGA) { "A saga reports what it derived: make it with saga()." } },
        EntryHandler { entry ->
            handler(entry)
            emptyList()
        },
    )

    val filter: SubscriptionFilter = filter.resolve(models)

    /** The function a result names when this subscription handled a commit. */
    val function: FunctionInfo = subscriptionFunction(kind, filter.context, name)

    init {
        require(name.isNotBlank()) { "A subscription needs a name." }
        require(kind != FunctionKind.COMMAND) { "A subscription handles commits, not commands." }
    }

    /** Handles [entry]; returns the ids of the commands it derived, a saga's only. */
    internal suspend fun handle(entry: SubscribedEntry): List<String> = handler.handle(entry)

    override fun toString(): String = "Subscription($name)"

    companion object {
        /**
         * A saga: [handler] returns the ids of the commands it derived from the entry (decision D62). A wait whose
         * chain continues after this saga's hop then waits for each of them; the saga passes the entry's wait context
         * on to them ([me.ahoo.wow.spi.wait.WaitContext.derived]) when it executes them.
         */
        fun saga(
            name: String,
            filter: SubscriptionFilter,
            startFrom: StartFrom = StartFrom.BEGINNING,
            decoder: EntryDecoder = EntryDecoder.RECORDS,
            models: Collection<AggregateModel<*>> = emptyList(),
            handler: suspend (SubscribedEntry) -> List<String>,
        ): Subscription = Subscription(
            name,
            filter,
            startFrom,
            decoder,
            models,
            FunctionKind.SAGA,
            EntryHandler { handler(it) },
        )

        /** A subscription that [coalesces]: [handler] gets the latest entry of each aggregate's batch only. */
        internal fun coalescing(
            name: String,
            filter: SubscriptionFilter,
            kind: FunctionKind,
            handler: suspend (SubscribedEntry) -> Unit,
        ): Subscription = Subscription(
            name,
            filter,
            StartFrom.BEGINNING,
            EntryDecoder.RECORDS,
            emptyList(),
            kind,
            EntryHandler { entry ->
                handler(entry)
                emptyList()
            },
            coalesces = true,
        )
    }
}

/** What a subscription runs for an entry: it returns the ids of the commands it derived. */
internal fun interface EntryHandler {
    suspend fun handle(entry: SubscribedEntry): List<String>
}

/** Where a subscription without a checkpoint starts. */
enum class StartFrom {
    /** From the first entry of the partition: a new projection, or a replay under a new name. */
    BEGINNING,

    /** After the entries already committed when the subscription first starts in the partition (its log head). */
    END,
}

/**
 * The streams a subscription handles: those of one bounded [context], of the [aggregates] named (every aggregate of
 * the context when empty), holding at least one event whose stored name is in [events] (any event when empty).
 * Event names are the names the log stores (`context.aggregate.Event`). Without models a renamed event must be listed
 * under its former names too; [resolve] adds them from the models. An entry outside the filter still moves the
 * checkpoint.
 */
data class SubscriptionFilter(
    val context: String,
    val aggregates: Set<String> = emptySet(),
    val events: Set<String> = emptySet(),
) {
    init {
        require(context.isNotBlank()) { "A subscription filter needs a context." }
    }

    fun matches(stream: EventStreamRecord): Boolean {
        val type = stream.ref.type
        return type.context == context &&
            (aggregates.isEmpty() || type.name in aggregates) &&
            (events.isEmpty() || stream.events.any { it.name in events })
    }

    /**
     * This filter with each listed event name widened to every name its event type has among [models] of the
     * filter's context: the current name and the former ones (aliases), so a rename on either side keeps matching.
     * A name no model knows stays as it is.
     */
    fun resolve(models: Collection<AggregateModel<*>>): SubscriptionFilter {
        if (events.isEmpty()) {
            return this
        }
        val inContext = models.filter {
            it.type.context == context && (aggregates.isEmpty() || it.type.name in aggregates)
        }
        if (inContext.isEmpty()) {
            return this
        }
        val resolved = events.flatMapTo(LinkedHashSet()) { name ->
            val types = inContext.mapNotNull { it.findEventType(name) }
            listOf(name) + types.flatMap { it.aliases + it.name }
        }
        return copy(events = resolved)
    }
}

/**
 * What a handler receives: one committed [stream] (its ref, version, scope and events), at its log [position], with
 * its [events] as the subscription's [EntryDecoder] read them and the [transport] metadata appended with it.
 *
 * [position] and [transport] are null when the stream is handled again by [SubscriptionRuntime.resume]: resuming
 * reads the aggregate's streams by version, and the log returns facts only there. A handler that needs an
 * idempotency key uses (`ref`, `version`), which is stable across both paths.
 */
data class SubscribedEntry(
    val stream: EventStreamRecord,
    val position: LogPosition?,
    val events: List<Any>,
    val transport: Transport? = null,
) {
    val ref: AggregateRef get() = stream.ref
    val version: Int get() = stream.version
}

/**
 * Turns a committed stream into the entry a handler receives. It runs inside the handler's retries, so a stream
 * that cannot be decoded is retried and then parks its aggregate like a failing handler.
 *
 * This is the extension point for the post-commit state of design 04 §1 (`StateEvent`): a decoder that also loads
 * the aggregate's state at the stream's version (snapshot plus tail) hands it to the handler with the events.
 */
fun interface EntryDecoder {
    suspend fun decode(stream: EventStreamRecord, position: LogPosition?, transport: Transport?): SubscribedEntry

    companion object {
        /** Hands over the stored [me.ahoo.wow.spi.log.EventRecord]s as they are, for sinks and generic handlers. */
        val RECORDS: EntryDecoder = EntryDecoder { stream, position, transport ->
            SubscribedEntry(stream, position, stream.events, transport)
        }

        /**
         * Decodes each event into its class through [codec] and the model of the stream's aggregate among
         * [models], upgrading older revisions on the way. A stream of an aggregate with no model fails to decode.
         */
        fun models(models: Collection<AggregateModel<*>>, codec: EventCodec = EventCodec()): EntryDecoder {
            val byType: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
            return EntryDecoder { stream, position, transport ->
                val model = checkNotNull(byType[stream.ref.type]) { "No model of ${stream.ref.type} to decode with." }
                SubscribedEntry(stream, position, stream.events.map { codec.decode(model, it) }, transport)
            }
        }
    }
}

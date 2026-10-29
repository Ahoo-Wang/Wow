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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.runtime.state.SerializedState
import me.ahoo.wow.runtime.state.PublishedStateReader
import me.ahoo.wow.runtime.wait.subscriptionFunction
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.state.StateEventRecord
import kotlin.time.Duration

/**
 * A subscription: a [name], a [filter] and exactly one handler. The checkpoint belongs to the
 * name, so one handler never shares a checkpoint with another; a replay or a blue-green rebuild is a new name
 * starting from the beginning.
 *
 * [startFrom] applies only when the subscription has no checkpoint yet in a partition. [decoder] turns a committed
 * stream into what the handler receives.
 *
 * When [models] are given, the filter's event names are resolved through them ([SubscriptionFilter.resolve]): an
 * event listed under its current name also matches the streams that stored it under a former one, and the other
 * way round.
 *
 * [kind] says what the handler is: it is reported as the [function] that handled a commit, and a wait
 * chain may continue only after a saga's hop. A saga reports what it did with each entry ([HandledEntry]:
 * the commands it derived, and the rejection that ended its hop), so it is made with [saga]; the constructor makes
 * every other kind.
 *
 * [context] is the bounded context the subscription belongs to: its filter's, except for a subscription that reads
 * another context's published stream, which belongs to the context that declares it. A wait's hop
 * names it in that context, and it signals as that context.
 *
 * A subscription that [coalesces] is handed only the latest of an aggregate's entries that wait together in its lane
 * (a batch): it keeps the latest of something per aggregate, so the earlier ones add nothing. Every entry of the
 * batch completes, and is signalled, only once the latest is handled, so a wait on an earlier version reaches its
 * hop when what holds the later one is written (the snapshot subscription's merged writes).
 *
 * A stream that only changes schedules, its events all the system events `wow.ScheduleSet` and `wow.ScheduleCancelled`,
 * is the application's to see only as a position: its subscriptions pass it over like an entry
 * outside their filter. The built-in subscriptions ([builtIn], and the snapshot's [coalescing] one) take it, so the
 * timers are written, the snapshot keeps the aggregate's version and schedules, and the reservations it made are
 * confirmed.
 */
@InternalWowApi
class Subscription private constructor(
    val name: String,
    filter: SubscriptionFilter,
    val startFrom: StartFrom,
    val decoder: EntryDecoder,
    models: Collection<AggregateModel<*>>,
    val kind: FunctionKind,
    private val handler: EntryHandler,
    internal val coalesces: Boolean = false,
    /** Whether every entry is the subscription's, whatever its filter: one of a topic of commands or receipts. */
    private val takesAll: Boolean = false,
    /** Whether a stream that only changes schedules is the subscription's: a built-in one's. */
    private val takesEventless: Boolean = false,
    /** The bounded context the subscription belongs to: its filter's, unless it reads another context's stream. */
    val context: String = filter.context,
    /**
     * The handler's own in-process retry budget (`@Retry`), under the host's for this subscription and
     * over its global one ([SubscriptionOptions.retryOf]); null when the handler gives none.
     */
    val retry: HandlerRetry? = null,
    /**
     * The hop a wait names for this subscription's handling of an aggregate of a type, null when none does: its
     * [name] when not given (a snapshot target answers the `snapshot` hop for the types whose primary it is, and none
     * for the others).
     */
    private val answers: ((AggregateType) -> String?)? = null,
) {
    constructor(
        name: String,
        filter: SubscriptionFilter,
        startFrom: StartFrom = StartFrom.BEGINNING,
        decoder: EntryDecoder = EntryDecoder.RECORDS,
        models: Collection<AggregateModel<*>> = emptyList(),
        kind: FunctionKind = FunctionKind.PROJECT,
        context: String = filter.context,
        retry: HandlerRetry? = null,
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
            HandledEntry.NOTHING
        },
        context = context,
        retry = retry,
    )

    val filter: SubscriptionFilter = filter.resolve(models)

    /**
     * The function a result names when this subscription handled a commit: of the subscription's own [context], which
     * a wait's hop names and the signal's source is, even when it reads another context's stream.
     */
    val function: FunctionInfo = subscriptionFunction(kind, context, name)

    init {
        require(name.isNotBlank()) { "A subscription needs a name." }
        require(context.isNotBlank()) { "A subscription belongs to a context." }
        require(kind != FunctionKind.COMMAND) { "A subscription handles commits, not commands." }
    }

    /** The name a wait's hop gives this subscription for an aggregate of [type], null when no hop names it. */
    internal fun hopOf(type: AggregateType): String? = if (answers == null) name else answers.invoke(type)

    /** Handles [entry]; returns what it did: a saga's derived commands and the rejection that ended its hop. */
    internal suspend fun handle(entry: SubscribedEntry): HandledEntry = handler.handle(entry)

    /**
     * Whether [stream] is this subscription's to handle: its [filter] matches it, or it takes every entry. The entry of
     * an undecodable message ([UndecodableMessage]) is taken by the aggregate it names, whatever events it held.
     */
    internal fun takes(stream: EventStreamRecord): Boolean = takesAll ||
        if (UndecodableMessage.isUndecodable(stream)) {
            filter.copy(events = emptySet()).matches(stream)
        } else {
            (takesEventless || stream.events.any { it.name !in Schedules.EVENTS }) && filter.matches(stream)
        }

    override fun toString(): String = "Subscription($name)"

    @InternalWowApi
    companion object {
        /**
         * A saga: [handler] returns what it did with the entry ([HandledEntry]): the ids of the commands it derived,
         * and the rejection of one of them that it handled. A wait whose chain
         * continues after this saga's hop then waits for each of them, or ends at the hop with the rejection; the saga
         * passes the entry's wait context on to them ([me.ahoo.wow.spi.wait.WaitContext.derived]) when it executes
         * them.
         */
        fun saga(
            name: String,
            filter: SubscriptionFilter,
            startFrom: StartFrom = StartFrom.BEGINNING,
            decoder: EntryDecoder = EntryDecoder.RECORDS,
            models: Collection<AggregateModel<*>> = emptyList(),
            context: String = filter.context,
            retry: HandlerRetry? = null,
            handler: suspend (SubscribedEntry) -> HandledEntry,
        ): Subscription = Subscription(
            name,
            filter,
            startFrom,
            decoder,
            models,
            FunctionKind.SAGA,
            EntryHandler { handler(it) },
            context = context,
            retry = retry,
        )

        /**
         * A subscription that [coalesces]: [handler] gets the latest entry of each aggregate's batch only. A wait's
         * hop names it as [answers] says for an aggregate's type, by its [name] when not given.
         */
        internal fun coalescing(
            name: String,
            filter: SubscriptionFilter,
            kind: FunctionKind,
            decoder: EntryDecoder = EntryDecoder.RECORDS,
            answers: ((AggregateType) -> String?)? = null,
            handler: suspend (SubscribedEntry) -> Unit,
        ): Subscription = Subscription(
            name,
            filter,
            StartFrom.BEGINNING,
            decoder,
            emptyList(),
            kind,
            EntryHandler { entry ->
                handler(entry)
                HandledEntry.NOTHING
            },
            coalesces = true,
            takesEventless = true,
            answers = answers,
        )

        /**
         * A built-in subscription of the log (the `reservation` and `timers` ones): it takes the streams that change
         * only schedules too, from the beginning of the log.
         */
        internal fun builtIn(
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
                HandledEntry.NOTHING
            },
            takesEventless = true,
        )

        /**
         * A subscription of the messages of a topic of commands or receipts (–): it takes every
         * entry its source reads, whatever aggregate it names, and [handler] gets the message as
         * [SubscribedEntry.message]. It starts from the beginning of the topic, so every message is taken, unless
         * [start] says otherwise.
         */
        internal fun messages(
            name: String,
            context: String,
            start: StartFrom = StartFrom.BEGINNING,
            handler: suspend (SubscribedEntry) -> Unit,
        ): Subscription = Subscription(
            name,
            SubscriptionFilter(context),
            start,
            EntryDecoder.RECORDS,
            emptyList(),
            FunctionKind.REACT,
            EntryHandler { entry ->
                handler(entry)
                HandledEntry.NOTHING
            },
            takesAll = true,
        )
    }
}

/** What a subscription runs for an entry: it returns what it did with it. */
internal fun interface EntryHandler {
    suspend fun handle(entry: SubscribedEntry): HandledEntry
}

/**
 * What a subscription's handler did with an entry, as its hop reports it: the ids of the commands a
 * saga [derived], and the [rejection] of one of them, a business outcome the saga handled:
 * the entry is done with, and the wait at the hop ends REJECTED with it instead of going on.
 */
@InternalWowApi
data class HandledEntry(val derived: List<String> = emptyList(), val rejection: ErrorInfo? = null) {
    @InternalWowApi
    companion object {
        /** Handled, nothing derived. */
        @JvmField
        val NOTHING: HandledEntry = HandledEntry()
    }
}

/**
 * A handling that failed for a business reason, which no retry changes: the subscription does not
 * retry it but parks the aggregate at once, and a wait at the hop ends REJECTED with [error].
 */
internal open class HandlingRejectedException(val error: ErrorInfo, message: String) : IllegalStateException(message)

/**
 * An attempt of [subscription]'s handler at [ref]'s [version] that ran longer than its budget's [timeout]
 * ([RetryPolicy.timeout]): it was cancelled and counts as a failed attempt, retried like any other failure.
 */
internal class HandlerTimeoutException(
    val subscription: String,
    val ref: AggregateRef,
    val version: Int,
    val timeout: Duration,
) : IllegalStateException("$subscription did not handle $ref@$version within $timeout.")

/**
 * The streams a subscription handles: those of one bounded [context], of the [aggregates] named (every aggregate of
 * the context when empty), holding at least one event whose stored name is in [events] (any event when empty).
 * Event names are the names the log stores (`context.aggregate.Event`). Without models a renamed event must be listed
 * under its former names too; [resolve] adds them from the models. An entry outside the filter still moves the
 * checkpoint.
 */
@InternalWowApi
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
 * [SubscriptionRuntime.resume] hands over the same [position] and [transport] as the tail: it reads the aggregate's
 * entries by version with [me.ahoo.wow.spi.log.EventLog.readEntries], so the `reservation` subscription confirms the
 * keys of a parked aggregate too. [position] is null only for an entry made by hand. A handler that
 * needs an idempotency key uses (`ref`, `version`).
 *
 * An entry made by [EntryDecoder.stateEvents] also reads the aggregate's [state] after the stream, when its handler
 * asks for it: a handler that does not need it (the snapshot subscription, when the store already holds the version)
 * never rebuilds it.
 *
 * An entry read from another context's published topic carries the record it was read as,
 * [published]: the state after the stream (null when the upstream publishes without it) and the
 * events as published. [stream] holds the same facts; [position] is then the record's partition and its offset plus
 * one.
 *
 * An entry of a topic of commands or receipts (–) carries the [message] it was read as: the command
 * the context's intake executes, or the receipt of a command another context rejected. Its [stream] only names the
 * aggregate whose lane it runs in; nothing was committed.
 */
@InternalWowApi
data class SubscribedEntry(
    val stream: EventStreamRecord,
    val position: LogPosition?,
    val events: List<Any>,
    val transport: Transport? = null,
    private val states: PublishedStateReader? = null,
    val published: StateEventRecord? = null,
    val message: Any? = null,
) {
    val ref: AggregateRef get() = stream.ref
    val version: Int get() = stream.version

    /** The state after the stream ([PublishedStateReader.read]): only an entry decoded by [EntryDecoder.stateEvents]. */
    suspend fun state(): SerializedState = reader().read(stream)

    /** The state after the stream, from [snapshot], the aggregate's snapshot the handler already loaded, if any. */
    internal suspend fun state(snapshot: SnapshotRecord?): SerializedState = reader().read(stream, snapshot)

    private fun reader(): PublishedStateReader =
        checkNotNull(states) { "The entry ${stream.ref}@${stream.version} was not decoded with its state." }
}

/**
 * Turns a committed stream into the entry a handler receives. It runs inside the handler's retries, so a stream
 * that cannot be decoded is retried and then parks its aggregate like a failing handler.
 *
 * The post-commit state (`StateEvent`) comes with [stateEvents], the decoder every state consumer
 * of a context shares: the same code, not the same result. Each subscription decodes an entry's events
 * itself, as each reader decodes a published state afresh: the events are the application's classes, which may be mutable
 * (a Java bean, a Kotlin class with a `var`), so one decoded instance handed to every processor would let one handler's
 * change reach the others. The snapshot subscription and the sinks take the stored records and decode
 * no event; what a processor's subscription decodes is that processor's alone.
 */
@InternalWowApi
fun interface EntryDecoder {
    suspend fun decode(stream: EventStreamRecord, position: LogPosition?, transport: Transport?): SubscribedEntry

    @InternalWowApi
    companion object {
        /** Hands over the stored [me.ahoo.wow.spi.log.EventRecord]s as they are, for sinks and generic handlers. */
        val RECORDS: EntryDecoder = EntryDecoder { stream, position, transport ->
            SubscribedEntry(stream, position, stream.events, transport)
        }

        /**
         * Decodes each event into its class through [codec] and the model of the stream's aggregate among
         * [models], upgrading older revisions on the way, and leaves out the events the model retired.
         * A stream of an aggregate with no model fails to decode.
         */
        fun models(models: Collection<AggregateModel<*>>, codec: EventCodec = EventCodec()): EntryDecoder {
            val byType: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
            return EntryDecoder { stream, position, transport ->
                val model = checkNotNull(byType[stream.ref.type]) { "No model of ${stream.ref.type} to decode with." }
                SubscribedEntry(stream, position, codec.decodeEvents(model, stream.events), transport)
            }
        }

        /**
         * The decoder of the state consumers: each event decoded into its class through
         * [states]' codec and models, upgraded on the way, the events the model retired left out
         * (unless [decodeEvents] is false: the stored records are handed over, all of them), and the aggregate's state
         * after the stream read through [states] when the handler asks for it ([SubscribedEntry.state]): the
         * context's shared view, else the nearest view or snapshot evolved with the log's tail, else the log
         * replayed. Each subscription decodes the events afresh, so no handler sees another's instances (see
         * [EntryDecoder]).
         */
        fun stateEvents(states: PublishedStateReader, decodeEvents: Boolean = true): EntryDecoder =
            EntryDecoder { stream, position, transport ->
                val events = if (decodeEvents) {
                    val model = states.modelOf(stream.ref.type)
                    states.codec.decodeEvents(model, stream.events)
                } else {
                    stream.events
                }
                SubscribedEntry(stream, position, events, transport, states)
            }
    }
}

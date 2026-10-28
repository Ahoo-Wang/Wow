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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.WaitContext

/**
 * One event as the log stores it: its type [name] (`context.aggregate.event`, not a class name), the [revision] of
 * its schema, and its serialized [payload].
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class EventRecord @InternalWowApi constructor(val name: String, val revision: Int, val payload: String) {
    init {
        require(name.isNotBlank()) { "An event record needs a name." }
        require(revision >= 1) { "An event revision starts at 1." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is EventRecord && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "EventRecord(name=$name, revision=$revision, payload=$payload)"

    private fun valueFields(): List<Any?> = listOf(name, revision, payload)

    companion object {
        /** An EventRecord of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(name: String, revision: Int, payload: String): EventRecord = EventRecord(name, revision, payload)
    }
}

/**
 * The events one command produced, committed together by one append: the unit of fact
 * (documentation/designs/08-data-protocols.md §4).
 *
 * - [version] is the aggregate's version after this stream; the first stream of an aggregate is version 1 and each
 *   next one is exactly one more.
 * - [commandType] is the model's name of the command that produced it (`context.aggregate.Command`, not a class
 *   name): a request id reused by a command of another type is told apart from a retry by it.
 * - [scope] is the aggregate's owner and space **after** this commit, not the caller's: every fact carries it,
 *   so projections and query authorization read it without replaying history.
 * - [causation] and [headers] come from the command: the chain it belongs to and the request facts admission kept.
 * - [operator] comes from the command too: whose name the commit is recorded in, the primary record of
 *   an audit; null for an anonymous caller.
 * - [tags] are the aggregate's tags **after** this commit, as [scope] is its owner and space: the
 *   events model filters by them, so ABAC restricts an event timeline as each stream stood when it was committed. Null
 *   when not recorded (a stream appended before format 1 learned them, until `migrate-data` backfills it); empty when
 *   the aggregate had none.
 * - The schedules a decision set or cancelled are among its [events], as the system events `wow.ScheduleSet` and
 *   `wow.ScheduleCancelled`, from which the built-in `timers` subscription derives the timer store.
 * - [rejection] is the rejection whose events this stream holds, when a decision rejected its command with events,
 *   from the headers under [REJECTION_PREFIX] (`wow.rejection.code`, `.message`, `.extensions`); null for a commit.
 *   Its events are ordinary events of the aggregate, which projections and sagas handle as any; a retry of the
 *   command is answered with the same rejection.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class EventStreamRecord @InternalWowApi constructor(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val commandType: String,
    val commitTime: Long,
    val events: List<EventRecord>,
    val scope: Scope,
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val operator: Operator? = null,
    val tags: Map<String, List<String>>? = null,
) {
    init {
        require(version >= 1) { "An event stream's version starts at 1." }
        require(commandType.isNotBlank()) { "An event stream names the type of its command." }
        require(events.isNotEmpty()) { "An event stream holds at least one event." }
    }

    /** This EventStreamRecord with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        ref: AggregateRef = this.ref,
        version: Int = this.version,
        commandId: String = this.commandId,
        requestId: String = this.requestId,
        commandType: String = this.commandType,
        commitTime: Long = this.commitTime,
        events: List<EventRecord> = this.events,
        scope: Scope = this.scope,
        causation: Causation? = this.causation,
        headers: Map<String, String> = this.headers,
        operator: Operator? = this.operator,
        tags: Map<String, List<String>>? = this.tags,
    ): EventStreamRecord =
        EventStreamRecord(
            ref,
            version,
            commandId,
            requestId,
            commandType,
            commitTime,
            events,
            scope,
            causation,
            headers,
            operator,
            tags,
        )

    override fun equals(other: Any?): Boolean =
        this === other || other is EventStreamRecord && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "EventStreamRecord(ref=$ref, version=$version, commandId=$commandId, requestId=$requestId, " +
            "commandType=$commandType, commitTime=$commitTime, events=$events, scope=$scope, causation=$causation, " +
            "headers=$headers, operator=$operator, tags=$tags)"

    private fun valueFields(): List<Any?> = listOf(
        ref,
        version,
        commandId,
        requestId,
        commandType,
        commitTime,
        events,
        scope,
        causation,
        headers,
        operator,
        tags,
    )

    /** The rejection whose events this stream holds; null for a commit ([REJECTION_PREFIX]). */
    val rejection: Rejection?
        get() = headers[REJECTION_CODE]?.let { code ->
            Rejection(
                code,
                headers[REJECTION_MESSAGE].orEmpty(),
                headers[REJECTION_EXTENSIONS]?.let(::extensionsOf).orEmpty(),
            )
        }

    companion object {
        /**
         * The prefix of the headers a rejection's stream records its rejection under: the framework's, so a command's
         * headers may not set any (admission refuses them).
         */
        const val REJECTION_PREFIX: String = "wow.rejection."

        /** The rejection's code. */
        const val REJECTION_CODE: String = REJECTION_PREFIX + "code"

        /** The rejection's message. */
        const val REJECTION_MESSAGE: String = REJECTION_PREFIX + "message"

        /** The rejection's extensions, as a JSON object; absent when it has none. */
        const val REJECTION_EXTENSIONS: String = REJECTION_PREFIX + "extensions"

        /** The headers that record [rejection] on the stream of its events. */
        @InternalWowApi
        @JvmStatic
        fun rejectionHeaders(rejection: Rejection): Map<String, String> = buildMap {
            put(REJECTION_CODE, rejection.code)
            put(REJECTION_MESSAGE, rejection.message)
            if (rejection.extensions.isNotEmpty()) {
                put(REJECTION_EXTENSIONS, WowJson.mapper.writeValueAsString(rejection.extensions))
            }
        }

        @Suppress("UNCHECKED_CAST") // A JSON object reads as a map of its members.
        private fun extensionsOf(json: String): Map<String, Any?> =
            WowJson.mapper.readValue(json, Map::class.java) as Map<String, Any?>

        /** An EventStreamRecord of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            ref: AggregateRef,
            version: Int,
            commandId: String,
            requestId: String,
            commandType: String,
            commitTime: Long,
            events: List<EventRecord>,
            scope: Scope,
            causation: Causation? = null,
            headers: Map<String, String> = emptyMap(),
            operator: Operator? = null,
            tags: Map<String, List<String>>? = null,
        ): EventStreamRecord =
            EventStreamRecord(
                ref,
                version,
                commandId,
                requestId,
                commandType,
                commitTime,
                events,
                scope,
                causation,
                headers,
                operator,
                tags,
            )
    }
}

/** A position in the log: a partition and a sequence within it, in the order commits became visible. */
data class LogPosition(val partition: Int, val sequence: Long) {
    init {
        require(partition >= 0) { "A partition is never negative." }
        require(sequence >= 1) { "A log sequence starts at 1." }
    }
}

/**
 * A committed event stream at its [position] in the log, with the [transport] section appended with it, if any.
 * Only [EventLog.tail] and [EventLog.readEntries] return entries: the transport serves the subscriptions and signals
 * that read the log, never the domain.
 *
 * [transport] is the bytes [TransportCodec] wrote, which a backend stores as they are and gives back unchanged:
 * it never reads them, so a field a newer version added survives a backend that does not know it.
 * The runtime reads them as [decodedTransport].
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class LogEntry @InternalWowApi constructor(
    val position: LogPosition,
    val stream: EventStreamRecord,
    val transport: ByteArray? = null,
) {
    /** [transport] decoded, once per entry however many subscriptions read it: Wow's own reading. */
    @InternalWowApi
    val decodedTransport: Transport? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        transport?.let(TransportCodec::decode)
    }

    /** This LogEntry with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        position: LogPosition = this.position,
        stream: EventStreamRecord = this.stream,
        transport: ByteArray? = this.transport,
    ): LogEntry = LogEntry(position, stream, transport)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is LogEntry &&
            position == other.position &&
            stream == other.stream &&
            transport.contentEquals(other.transport)

    override fun hashCode(): Int = 31 * (31 * position.hashCode() + stream.hashCode()) + transport.contentHashCode()

    override fun toString(): String =
        "LogEntry(position=$position, stream=$stream, transport=${transport?.decodeToString()})"

    companion object {
        /** A LogEntry of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(position: LogPosition, stream: EventStreamRecord, transport: ByteArray? = null): LogEntry =
            LogEntry(position, stream, transport)
    }
}

/**
 * What an append carries for delivery, not as fact (§4): domain code and queries never
 * see it, and a migration or a replay drops it. The runtime appends it encoded ([TransportCodec]), and a backend stores
 * those bytes apart from the stream (its own column or field), never reading them.
 *
 * - [wait] is the wait context of a command whose caller waits beyond COMMITTED: the subscription named
 *   by its next hop signals the waiting node once it has handled the entry.
 * - [reservations] are the keys this attempt reserved pending: the `reservation` subscription confirms them
 *   from the log, so a key is confirmed even when the after-append fast path never ran.
 * - [trace] is tracing context such as the W3C `traceparent`.
 * - [releases] are the keys this attempt released: the kernel releases them once the append commits,
 *   and the `reservation` subscription releases them again from the log, after the keys of [reservations] are
 *   confirmed, so a release is not lost to a crash after the append.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class Transport @InternalWowApi constructor(
    val wait: WaitContext? = null,
    val reservations: List<ReservationKey> = emptyList(),
    val trace: Map<String, String> = emptyMap(),
    val releases: List<ReservationKey> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Transport && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Transport(wait=$wait, reservations=$reservations, trace=$trace, releases=$releases)"

    private fun valueFields(): List<Any?> = listOf(wait, reservations, trace, releases)

    companion object {
        /** A Transport of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            wait: WaitContext? = null,
            reservations: List<ReservationKey> = emptyList(),
            trace: Map<String, String> = emptyMap(),
            releases: List<ReservationKey> = emptyList(),
        ): Transport = Transport(wait, reservations, trace, releases)
    }
}

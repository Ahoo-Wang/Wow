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

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation

/**
 * What a subscription handler receives: one committed event stream of an aggregate,
 * its [events] decoded (and upgraded to their current revision), with the aggregate's [state] after the commit. The
 * events of another context's published stream are the classes of that context's api module, or a [PublishedEvent]
 * for one the subscriber has no class of at its revision.
 *
 * - [ref] and [version] identify the commit: [version] is the aggregate's version after it. [id] (`ref@version`) is
 *   the causation id of the commands a saga derives from it.
 * - [commandId], [requestId] and [commandType] (the model's name of the command) say which command
 *   produced it; [commitTime] is when, in epoch milliseconds.
 * - [scope] is the aggregate's owner and space after the commit, not the caller's; the commands a saga
 *   derives from it carry it on.
 * - [causation] and [headers] come from the command: the chain it belongs to and the request facts admission kept.
 * - [operator] is whose name the commit is recorded in; the commands a saga derives from it keep it.
 *
 * [state] is a copy read from the commit's shared state, never the live instance: changing it affects no one. It is the
 * state after the whole commit, also for a [StateTypedEvent] of one of its events. The runtime
 * decodes it on its first read, so a handler that only reads [events] costs no decoding; a state that
 * does not decode fails that read, and a handler that catches every exception there swallows the failure with it. A handler of
 * another context's published stream declares a nullable state, `StateEvent<OrderStateView?>`, when the upstream
 * publishes the stream without it: [state] is then null. `S` is covariant, so a
 * `StateEvent<OrderStateView>` is a `StateEvent<OrderStateView?>`.
 *
 * A handler that takes a `StateEvent<S>` is called once per stream; one that takes a [StateTypedEvent] of an event
 * type, or the event itself, once per event of that type in the stream.
 *
 * Members may be added: the runtime builds it, and a test builds one with the test DSL's `stateEvent`;
 * building one is internal to Wow.
 */
interface StateEvent<out S> {
    val ref: AggregateRef
    val version: Int
    val commandId: String
    val requestId: String
    val commandType: String
    val commitTime: Long
    val events: List<Any>
    val state: S
    val scope: Scope
    val causation: Causation?
    val headers: Map<String, String>
    val operator: Operator?

    /** The identity of the commit: `ref@version`. */
    val id: String get() = "$ref@$version"

    /** The id of the root command of the chain: this commit's own command when it has no causation. */
    val correlationId: String get() = causation?.correlationId ?: commandId

    /** This stream with the given fields replaced, a whole stream again: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        ref: AggregateRef = this.ref,
        version: Int = this.version,
        commandId: String = this.commandId,
        requestId: String = this.requestId,
        commandType: String = this.commandType,
        commitTime: Long = this.commitTime,
        events: List<Any> = this.events,
        state: @UnsafeVariance S = this.state,
        scope: Scope = this.scope,
        causation: Causation? = this.causation,
        headers: Map<String, String> = this.headers,
        operator: Operator? = this.operator,
    ): StateEvent<S> = CommittedStateEvent(
        ref,
        version,
        commandId,
        requestId,
        commandType,
        commitTime,
        events,
        lazyOf(state),
        scope,
        causation,
        headers,
        operator,
    )

    companion object {
        /** A committed stream; the runtime and the test DSL build them. */
        @InternalWowApi
        @Suppress("LongParameterList")
        operator fun <S> invoke(
            ref: AggregateRef,
            version: Int,
            commandId: String,
            requestId: String,
            commandType: String,
            commitTime: Long,
            events: List<Any>,
            state: S,
            scope: Scope = Scope.NONE,
            causation: Causation? = null,
            headers: Map<String, String> = emptyMap(),
            operator: Operator? = null,
        ): StateEvent<S> = CommittedStateEvent(
            ref,
            version,
            commandId,
            requestId,
            commandType,
            commitTime,
            events,
            lazyOf(state),
            scope,
            causation,
            headers,
            operator,
        )

        /**
         * A committed stream whose state [decode] gives on its first read, once: a handler that never
         * reads [StateEvent.state] costs no decoding, and one that does gets its own copy, as from [invoke]. A failure
         * of [decode] is thrown by that read. The runtime builds them.
         */
        @InternalWowApi
        @Suppress("LongParameterList")
        fun <S> deferred(
            ref: AggregateRef,
            version: Int,
            commandId: String,
            requestId: String,
            commandType: String,
            commitTime: Long,
            events: List<Any>,
            scope: Scope = Scope.NONE,
            causation: Causation? = null,
            headers: Map<String, String> = emptyMap(),
            operator: Operator? = null,
            decode: () -> S,
        ): StateEvent<S> = CommittedStateEvent(
            ref,
            version,
            commandId,
            requestId,
            commandType,
            commitTime,
            events,
            lazy(decode),
            scope,
            causation,
            headers,
            operator,
        )
    }
}

/**
 * One event of a committed stream, typed: the stream it belongs to, as a [StateEvent], with its
 * [eventIndex]-th event as [event], an `E`. A handler that takes a `StateTypedEvent<S, E>` is called once for each
 * event of the stream that is an `E`, in their order, all with the stream's [state] after the whole commit; a handler
 * that takes an `E` itself is its shorthand. It is a view of the stream, not a copy: two are equal when their streams
 * are and they are of the same event.
 */
interface StateTypedEvent<out S, out E : Any> : StateEvent<S> {
    /** The event, decoded: `events[eventIndex]`. */
    val event: E

    /** Where [event] is in [events], from 0. */
    val eventIndex: Int

    companion object {
        /** The [eventIndex]-th event of [stream], which must be an `E`; the runtime and the test DSL build them. */
        @InternalWowApi
        fun <S, E : Any> of(stream: StateEvent<S>, eventIndex: Int): StateTypedEvent<S, E> =
            TypedStateEvent(stream, eventIndex)
    }
}

/** The committed stream behind [StateEvent]'s factory. */
private class CommittedStateEvent<out S>(
    override val ref: AggregateRef,
    override val version: Int,
    override val commandId: String,
    override val requestId: String,
    override val commandType: String,
    override val commitTime: Long,
    override val events: List<Any>,
    private val stateOf: Lazy<S>,
    override val scope: Scope,
    override val causation: Causation?,
    override val headers: Map<String, String>,
    override val operator: Operator?,
) : StateEvent<S> {
    init {
        require(version >= 1) { "A committed version starts at 1." }
        require(commandId.isNotBlank()) { "A state event names its command." }
        require(commandType.isNotBlank()) { "A state event names the type of its command." }
        require(events.isNotEmpty()) { "A state event holds at least one event." }
    }

    override val state: S get() = stateOf.value

    override fun equals(other: Any?): Boolean =
        this === other || other is CommittedStateEvent<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "StateEvent(ref=$ref, version=$version, commandId=$commandId, requestId=$requestId, " +
            "commandType=$commandType, commitTime=$commitTime, events=$events, state=$state, scope=$scope, " +
            "causation=$causation, headers=$headers, operator=$operator)"

    private fun valueFields(): List<Any?> = listOf(
        ref, version, commandId, requestId, commandType, commitTime, events, state, scope, causation, headers,
        operator
    )
}

/** [StateTypedEvent]'s view of the [eventIndex]-th event of [stream]. */
private class TypedStateEvent<out S, out E : Any>(
    private val stream: StateEvent<S>,
    override val eventIndex: Int,
) : StateTypedEvent<S, E>, StateEvent<S> by stream {
    init {
        require(eventIndex in stream.events.indices) { "${stream.id} has no event $eventIndex." }
    }

    @Suppress("UNCHECKED_CAST") // The binding hands over only the events of the handler's type.
    override val event: E get() = stream.events[eventIndex] as E

    override val id: String get() = stream.id

    override val correlationId: String get() = stream.correlationId

    override fun equals(other: Any?): Boolean =
        this === other || other is TypedStateEvent<*, *> && eventIndex == other.eventIndex && stream == other.stream

    override fun hashCode(): Int = 31 * stream.hashCode() + eventIndex

    override fun toString(): String = "StateTypedEvent(eventIndex=$eventIndex, event=$event, stream=$stream)"
}

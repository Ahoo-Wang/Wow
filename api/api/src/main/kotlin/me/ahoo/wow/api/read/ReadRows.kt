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

package me.ahoo.wow.api.read

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule

/**
 * An aggregate's latest committed state as a snapshot query and a state read answer it (decision D348 ⑦): the
 * aggregate [ref] at [version], its [scope] (owner and space), its [tags], whether it is [deleted], the [operator] of
 * its last commit and the [firstOperator] of its first, the one that created it (each null for an anonymous caller),
 * the times of its first and last events (epoch milliseconds), the [schedules] it keeps, each rule by its key, and
 * its [state], masked as the schema says.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class Snapshot<out S : Any> @InternalWowApi constructor(
    val ref: AggregateRef,
    val version: Int,
    val scope: Scope,
    val tags: Map<String, List<String>>,
    val deleted: Boolean,
    val operator: Operator?,
    val firstEventTime: Long,
    val lastEventTime: Long,
    val state: S,
    val firstOperator: Operator? = null,
    val schedules: Map<String, ScheduleRule> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Snapshot<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Snapshot(ref=$ref, version=$version, scope=$scope, tags=$tags, deleted=$deleted, operator=$operator, " +
            "firstEventTime=$firstEventTime, lastEventTime=$lastEventTime, state=$state, " +
            "firstOperator=$firstOperator, schedules=$schedules)"

    private fun valueFields(): List<Any?> =
        listOf(
            ref,
            version,
            scope,
            tags,
            deleted,
            operator,
            firstEventTime,
            lastEventTime,
            state,
            firstOperator,
            schedules,
        )

    companion object {
        /** A Snapshot of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        @Suppress("LongParameterList") // One parameter per field of the record.
        fun <S : Any> of(
            ref: AggregateRef,
            version: Int,
            scope: Scope,
            tags: Map<String, List<String>>,
            deleted: Boolean,
            operator: Operator?,
            firstEventTime: Long,
            lastEventTime: Long,
            state: S,
            firstOperator: Operator? = null,
            schedules: Map<String, ScheduleRule> = emptyMap(),
        ): Snapshot<S> = Snapshot(
            ref,
            version,
            scope,
            tags,
            deleted,
            operator,
            firstEventTime,
            lastEventTime,
            state,
            firstOperator,
            schedules,
        )
    }
}

/**
 * One row of an events query: the commit of [ref] at [version], at [commitTime] (epoch
 * milliseconds), of the command [commandId] of type [commandType] sent with [requestId] in the name of [operator],
 * in the aggregate's [scope], and its [events] in order.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class EventStream @InternalWowApi constructor(
    val ref: AggregateRef,
    val version: Int,
    val commitTime: Long,
    val commandId: String,
    val requestId: String,
    val commandType: String,
    val scope: Scope,
    val operator: Operator?,
    val events: List<CommittedEvent>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is EventStream && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "EventStream(ref=$ref, version=$version, commitTime=$commitTime, commandId=$commandId, " +
            "requestId=$requestId, commandType=$commandType, scope=$scope, operator=$operator, events=$events)"

    private fun valueFields(): List<Any?> =
        listOf(ref, version, commitTime, commandId, requestId, commandType, scope, operator, events)

    companion object {
        /** An EventStream of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @Suppress("LongParameterList") // One parameter per field of the record.
        fun of(
            ref: AggregateRef,
            version: Int,
            commitTime: Long,
            commandId: String,
            requestId: String,
            commandType: String,
            scope: Scope,
            operator: Operator?,
            events: List<CommittedEvent>,
        ): EventStream =
            EventStream(ref, version, commitTime, commandId, requestId, commandType, scope, operator, events)
    }
}

/**
 * One event of an [EventStream]: its [name] and [revision] as the model reads it now (upgraded) and its
 * [body], masked as the Manifest declares: an instance of the model's event class, or a `Map` of an event the reader
 * has no class for.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class CommittedEvent @InternalWowApi constructor(val name: String, val revision: Int, val body: Any) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommittedEvent && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CommittedEvent(name=$name, revision=$revision, body=$body)"

    private fun valueFields(): List<Any?> = listOf(name, revision, body)

    companion object {
        /** A CommittedEvent of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(name: String, revision: Int, body: Any): CommittedEvent = CommittedEvent(name, revision, body)
    }
}

/**
 * A row of a resumable stream and its [position]: the opaque token of its place in the stream's
 * order, after which the same query streams on. `null` in a stream that cannot be resumed.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class Positioned<out R : Any> @InternalWowApi constructor(val row: R, val position: String?) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Positioned<*> && row == other.row && position == other.position

    override fun hashCode(): Int = listOf(row, position).hashCode()

    override fun toString(): String = "Positioned(row=$row, position=$position)"

    companion object {
        /** A Positioned of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun <R : Any> of(row: R, position: String?): Positioned<R> = Positioned(row, position)
    }
}

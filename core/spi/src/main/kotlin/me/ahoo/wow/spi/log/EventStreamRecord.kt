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
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.WaitContext

/**
 * One event as the log stores it: its type [name] (`context.aggregate.event`, not a class name), the [revision] of
 * its schema, and its serialized [payload].
 */
data class EventRecord(val name: String, val revision: Int, val payload: String) {
    init {
        require(name.isNotBlank()) { "An event record needs a name." }
        require(revision >= 1) { "An event revision starts at 1." }
    }
}

/**
 * The events one command produced, committed together by one append: the unit of fact
 * (documentation/designs/v10/08-data-protocols.md §4).
 *
 * - [version] is the aggregate's version after this stream; the first stream of an aggregate is version 1 and each
 *   next one is exactly one more.
 * - [commandType] is the model's name of the command that produced it (`context.aggregate.Command`, not a class
 *   name, decision D55): a request id reused by a command of another type is told apart from a retry by it.
 * - [scope] is the aggregate's owner and space **after** this commit, not the caller's (D56): every fact carries it,
 *   so projections and query authorization read it without replaying history.
 * - [causation] and [headers] come from the command: the chain it belongs to and the request facts admission kept.
 * - [operator] comes from the command too: whose name the commit is recorded in (decision D163), the primary record of
 *   an audit; null for an anonymous caller.
 * - The schedules a decision set or cancelled are among its [events], as the system events `wow.ScheduleSet` and
 *   `wow.ScheduleCancelled` (decision D198), from which the built-in `timers` subscription derives the timer store.
 */
data class EventStreamRecord(
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
) {
    init {
        require(version >= 1) { "An event stream's version starts at 1." }
        require(commandType.isNotBlank()) { "An event stream names the type of its command." }
        require(events.isNotEmpty()) { "An event stream holds at least one event." }
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
 * A committed event stream at its [position] in the log, with the [transport] metadata appended with it, if any.
 * Only [EventLog.tail] returns entries: the transport serves the subscriptions and signals that read the log, never
 * the domain.
 */
data class LogEntry(val position: LogPosition, val stream: EventStreamRecord, val transport: Transport? = null)

/**
 * What an append carries for delivery, not as fact (design 08 §1, §4; decision D25): domain code and queries never
 * see it, and a migration or a replay drops it. A backend stores it apart from the stream (its own column or field).
 *
 * - [wait] is the wait context of a command whose caller waits beyond COMMITTED (D25, D69): the subscription named
 *   by its next hop signals the waiting node once it has handled the entry.
 * - [reservations] are the keys this attempt reserved pending (D50): the `reservation` subscription confirms them
 *   from the log, so a key is confirmed even when the after-append fast path never ran.
 * - [trace] is tracing context such as the W3C `traceparent`.
 */
data class Transport(
    val wait: WaitContext? = null,
    val reservations: List<ReservationKey> = emptyList(),
    val trace: Map<String, String> = emptyMap(),
)

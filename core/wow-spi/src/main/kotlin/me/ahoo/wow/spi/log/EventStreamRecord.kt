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
 * The events one command produced, committed together by one append: the unit of fact.
 *
 * [version] is the aggregate's version after this stream; the first stream of an aggregate is version 1 and each
 * next one is exactly one more. [fingerprint] identifies the command (its type and a hash of its body), so that a
 * request id reused by a different command is told apart from a retry of the same one.
 */
data class EventStreamRecord(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val fingerprint: String,
    val commitTime: Long,
    val events: List<EventRecord>,
) {
    init {
        require(version >= 1) { "An event stream's version starts at 1." }
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

/** A committed event stream at its [position] in the log. */
data class LogEntry(val position: LogPosition, val stream: EventStreamRecord)

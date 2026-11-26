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

import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.state.StateEventRecord

/**
 * The stream of a published record that holds no event: a commit whose events its model all retired, which the
 * transport sink publishes so that an aggregate's versions on its state topic are contiguous. It stands for the
 * commit's version alone: every subscription passes over it ([Subscription.takes]), so no handler is called, as none
 * is for such a commit of the context's own log.
 */
internal object RetiredOnly {
    /** The event name of such a stream: no model has it. */
    const val EVENT: String = "wow.retired-only"

    /** The stream of [record], a record that holds no event, as the version it stands for. */
    fun streamOf(record: StateEventRecord): EventStreamRecord {
        require(record.events.isEmpty()) { "${record.id} holds events." }
        return EventStreamRecord(
            ref = record.ref,
            version = record.version,
            commandId = record.commandId,
            requestId = record.requestId,
            commandType = record.commandType,
            commitTime = record.commitTime,
            events = EVENTS,
            scope = record.scope,
            causation = record.causation,
            headers = record.headers,
            operator = record.operator,
            access = record.access,
            accessVersion = record.accessVersion,
        )
    }

    /**
     * Whether [stream] stands for a published record that holds no event: one of [EVENTS] itself, so the check every
     * subscription makes of every entry ([Subscription.takes]) is one comparison of references.
     */
    fun isRetiredOnly(stream: EventStreamRecord): Boolean = stream.events === EVENTS

    /** The events of every such stream: one list, which [isRetiredOnly] tells by its identity. */
    private val EVENTS: List<EventRecord> = listOf(EventRecord(EVENT, 1, "{}"))
}

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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition

/**
 * A message of a topic this version cannot decode (another format or kind, a malformed value, a payload of a shape it
 * cannot read), whose key names the aggregate it is about: failure is data, not a skip. The source
 * hands it to the subscriptions as the entry [entryOf] makes, in the aggregate's lane at the message's position; a
 * subscription that takes the aggregate fails on it ([UndecodableEntryException]) and parks the aggregate there, so an
 * operator sees it and decides: retry it once a version reads it, or skip it. A message whose key names no aggregate
 * cannot be parked: its source passes over it, counted ([me.ahoo.wow.spi.observation.ObservedEvent.Undecodable]).
 */
internal class UndecodableMessage(val reason: String) {
    companion object {
        /** The event name of the entry of an undecodable message: no model has it. */
        const val EVENT: String = "wow.undecodable"

        /**
         * The aggregate a message's [key] names, the text its ref writes ([AggregateRef.parse]), or null: no key, or
         * one no ref writes (a foreign or hand-published message, a malformed escape), whose message is passed over.
         */
        fun refOf(key: String?): AggregateRef? = key?.let(AggregateRef::parse)

        /** The entry of an undecodable message about [ref] at [position], failing for [reason]. */
        fun entryOf(ref: AggregateRef, position: LogPosition, reason: String): SourcedEntry = SourcedEntry(
            position,
            EventStreamRecord(
                ref = ref,
                version = 1,
                commandId = UNDECODABLE,
                requestId = UNDECODABLE,
                commandType = UNDECODABLE,
                commitTime = 0L,
                events = listOf(EventRecord(EVENT, 1, "{}")),
                scope = Scope.NONE,
            ),
            null,
            message = UndecodableMessage(reason),
        )

        /** Whether [entry] is that of an undecodable message. */
        fun isUndecodable(entry: SourcedEntry): Boolean = entry.message is UndecodableMessage

        /** Whether [stream] is the stream of an undecodable message's entry. */
        fun isUndecodable(stream: EventStreamRecord): Boolean = stream.events.singleOrNull()?.name == EVENT

        private const val UNDECODABLE = "(undecodable)"
    }
}

/** A subscription met an undecodable message about an aggregate it takes: it parks the aggregate there. */
internal class UndecodableEntryException(reason: String) :
    IllegalStateException("The message cannot be decoded by this version: $reason")

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

package me.ahoo.wow.event.upgrader

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.event.DomainEventRecord

/**
 * Utility object for creating dropped event records.
 *
 * This object provides functionality to convert domain event records into
 * "dropped" event records, which are used when events are intentionally
 * discarded during event upgrading or processing.
 *
 * @see DomainEventRecord
 * @see MutableDomainEventRecord
 */
object DroppedEvent {
    private val TYPE: String = DroppedEvent::class.java.name
    private const val NAME: String = "dropped_event"

    /**
     * Converts a domain event record to a dropped event record.
     *
     * This extension function creates a mutable copy of the event record and
     * modifies it to represent a dropped event with empty body content.
     *
     * @receiver The domain event record to convert
     * @return A new domain event record marked as dropped
     *
     * @see DomainEventRecord.toMutableDomainEventRecord
     * @see MutableDomainEventRecord
     * @see ObjectNode
     * @see JsonSerializer
     */
    fun DomainEventRecord.toDroppedEventRecord(): DomainEventRecord {
        val mutableDomainEventRecord = toMutableDomainEventRecord()
        mutableDomainEventRecord.bodyType = TYPE
        mutableDomainEventRecord.name = NAME
        mutableDomainEventRecord.body = ObjectNode(JsonSerializer.nodeFactory)
        return mutableDomainEventRecord
    }
}

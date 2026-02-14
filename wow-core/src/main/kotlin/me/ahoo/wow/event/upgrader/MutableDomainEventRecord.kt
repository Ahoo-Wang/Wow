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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.serialization.MessageRecords.BODY
import me.ahoo.wow.serialization.MessageRecords.BODY_TYPE
import me.ahoo.wow.serialization.MessageRecords.NAME
import me.ahoo.wow.serialization.event.DomainEventRecord
import me.ahoo.wow.serialization.event.DomainEventRecords.REVISION
import tools.jackson.databind.node.ObjectNode

/**
 * Mutable wrapper for domain event records.
 *
 * This class provides a mutable interface to domain event records stored as
 * Jackson ObjectNode instances. It allows modification of event properties
 * during the upgrading process.
 *
 * @property actual The underlying ObjectNode containing the event data
 *
 * @constructor Creates a new MutableDomainEventRecord wrapping the given ObjectNode
 *
 * @param actual The ObjectNode to wrap
 *
 * @see DomainEventRecord
 * @see ObjectNode
 */
class MutableDomainEventRecord(
    override val actual: ObjectNode,
    private val actualAggregateId: AggregateId,
    private val actualHeader: Header,
    override val version: Int,
    override val ownerId: String,
    override val spaceId: SpaceId,
    override val commandId: String,
    override val sequence: Int,
    override val isLast: Boolean,
    override val createTime: Long
) : DomainEventRecord {

    override val contextName: String
        get() = actualAggregateId.contextName
    override val aggregateName: String
        get() = actualAggregateId.aggregateName

    override val aggregateId: String
        get() = actualAggregateId.id

    override val tenantId: String
        get() = actualAggregateId.tenantId

    /**
     * The body type of the event, mutable for upgrading.
     */
    override var bodyType: String
        get() = super.bodyType
        set(value) {
            actual.put(BODY_TYPE, value)
        }

    /**
     * The name of the event, mutable for upgrading.
     */
    override var name: String
        get() = super.name
        set(value) {
            actual.put(NAME, value)
        }

    /**
     * The revision of the event, mutable for upgrading.
     */
    override var revision: String
        get() = super.revision
        set(value) {
            actual.put(REVISION, value)
        }

    /**
     * The body of the event as an ObjectNode, mutable for upgrading.
     */
    override var body: ObjectNode
        get() = super.body as ObjectNode
        set(value) {
            actual.set(BODY, value)
        }

    override fun toMessageHeader(): Header {
        return actualHeader
    }

    override fun toAggregateId(): AggregateId {
        return actualAggregateId
    }

    companion object {
        /**
         * Extension function to convert a DomainEventRecord to a MutableDomainEventRecord.
         *
         * If the record is already mutable, returns it as-is. Otherwise, creates
         * a new MutableDomainEventRecord wrapping the underlying ObjectNode.
         *
         * @receiver The domain event record to convert
         * @return A mutable version of the domain event record
         *
         * @see DomainEventRecord
         * @see MutableDomainEventRecord
         */
        fun DomainEventRecord.toMutableDomainEventRecord(): MutableDomainEventRecord {
            if (this is MutableDomainEventRecord) {
                return this
            }
            return MutableDomainEventRecord(
                actual = actual,
                actualAggregateId = toAggregateId(),
                actualHeader = toMessageHeader(),
                version = version,
                ownerId = ownerId,
                spaceId = spaceId,
                commandId = commandId,
                sequence = sequence,
                isLast = isLast,
                createTime = createTime
            )
        }
    }
}

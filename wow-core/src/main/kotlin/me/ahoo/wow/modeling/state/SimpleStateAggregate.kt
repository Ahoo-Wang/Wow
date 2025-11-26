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
package me.ahoo.wow.modeling.state

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.api.event.AggregateRecovered
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.event.OwnerTransferred
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.TypedAggregate
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.ignoreSourcing
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata

class SimpleStateAggregate<S : Any>(
    override val aggregateId: AggregateId,
    val metadata: StateAggregateMetadata<S>,
    override val state: S,
    override var ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override var version: Int = Version.UNINITIALIZED_VERSION,
    override var eventId: String = "",
    override var firstOperator: String = "",
    override var operator: String = "",
    override var firstEventTime: Long = 0,
    override var eventTime: Long = 0,
    override var deleted: Boolean = false,
) : StateAggregate<S>,
    TypedAggregate<S> by metadata {

    private val sourcingRegistry = metadata.toMessageFunctionRegistry(state)

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun onSourcing(eventStream: DomainEventStream): StateAggregate<S> {
        log.debug {
            "onSourcing $eventStream."
        }

        if (eventStream.ignoreSourcing()) {
            return this
        }

        require(aggregateId == eventStream.aggregateId) {
            "Failed to Sourcing eventStream[${eventStream.id}]: Current StateAggregate's AggregateId[$this] is inconsistent with the DomainEventStream's AggregateId[${eventStream.aggregateId}]."
        }

        if (expectedNextVersion != eventStream.version) {
            throw SourcingVersionConflictException(
                eventStream = eventStream,
                expectVersion = expectedNextVersion,
            )
        }

        for (domainEvent in eventStream) {
            sourcing(domainEvent)
        }
        version = eventStream.version
        if (eventStream.ownerId.isNotBlank()) {
            ownerId = eventStream.ownerId
        }
        eventId = eventStream.id
        operator = eventStream.header.operator.orEmpty()
        eventTime = eventStream.createTime
        if (isInitialVersion) {
            firstOperator = operator
            firstEventTime = eventTime
        }
        return this
    }

    private fun sourcing(domainEvent: DomainEvent<*>) {
        val domainEventBody = domainEvent.body
        if (domainEventBody is AggregateDeleted) {
            deleted = true
        }
        if (domainEventBody is AggregateRecovered) {
            deleted = false
        }
        if (domainEventBody is OwnerTransferred) {
            ownerId = domainEventBody.targetOwnerId
        }
        val sourcingFunction = sourcingRegistry[domainEvent.body.javaClass]
        if (sourcingFunction != null) {
            sourcingFunction.invoke(SimpleDomainEventExchange(domainEvent))
        } else {
            log.debug {
                "Sourcing $domainEvent Ignore this domain event because onSourcing does not exist."
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimpleStateAggregate<*>) return false

        if (aggregateId != other.aggregateId) return false
        return version == other.version
    }

    override fun hashCode(): Int {
        var result = aggregateId.hashCode()
        result = 31 * result + version
        return result
    }

    override fun toString(): String {
        return "SimpleStateAggregate(aggregateId=$aggregateId, version=$version)"
    }
}

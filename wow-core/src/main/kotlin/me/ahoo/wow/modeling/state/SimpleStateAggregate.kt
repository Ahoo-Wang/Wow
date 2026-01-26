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
import me.ahoo.wow.api.event.SpaceTransferred
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TypedAggregate
import me.ahoo.wow.api.modeling.aware.VersionAware
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.ignoreSourcing
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata

/**
 * A simple implementation of [StateAggregate] that manages aggregate state through event sourcing.
 *
 * This class applies domain events to update the aggregate's state, handles ownership transfers,
 * deletion, and recovery events, and maintains versioning for consistency.
 *
 * @param S The type of the aggregate state.
 * @property aggregateId The unique identifier of the aggregate.
 * @property metadata Metadata describing the state aggregate, including sourcing functions.
 * @property state The current state of the aggregate.
 * @property ownerId The identifier of the current owner of the aggregate. Defaults to [OwnerId.DEFAULT_OWNER_ID].
 * @property version The current version of the aggregate. Defaults to [Version.UNINITIALIZED_VERSION].
 * @property eventId The ID of the last processed event. Defaults to an empty string.
 * @property firstOperator The operator who initiated the first event. Defaults to an empty string.
 * @property operator The operator who initiated the last event. Defaults to an empty string.
 * @property firstEventTime The timestamp of the first event. Defaults to 0.
 * @property eventTime The timestamp of the last event. Defaults to 0.
 * @property deleted Indicates whether the aggregate has been deleted. Defaults to false.
 */
class SimpleStateAggregate<S : Any>(
    override val aggregateId: AggregateId,
    val metadata: StateAggregateMetadata<S>,
    override val state: S,
    override var ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override var spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
    override var version: Int = Version.UNINITIALIZED_VERSION,
    override var eventId: String = "",
    override var firstOperator: String = "",
    override var operator: String = "",
    override var firstEventTime: Long = 0,
    override var eventTime: Long = 0,
    override var deleted: Boolean = false
) : StateAggregate<S>,
    TypedAggregate<S> by metadata {
    private val sourcingRegistry = metadata.toMessageFunctionRegistry(state)

    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Applies a stream of domain events to update the aggregate's state.
     *
     * This method validates the event stream against the current aggregate state, applies each event
     * to the state using registered sourcing functions, and updates metadata such as version and ownership.
     *
     * @param eventStream The domain event stream to source from.
     * @return This aggregate instance after sourcing.
     * @throws IllegalArgumentException If the aggregate ID does not match the event stream's aggregate ID.
     * @throws SourcingVersionConflictException If the expected next version does not match the event stream's version.
     *
     * Example usage:
     * ```
     * val aggregate = SimpleStateAggregate(...)
     * val eventStream = DomainEventStream(...)
     * aggregate.onSourcing(eventStream)
     * ```
     */
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
        if (eventStream.spaceId.isNotBlank()) {
            spaceId = eventStream.spaceId
        }
        eventId = eventStream.id
        operator = eventStream.header.operator.orEmpty()
        eventTime = eventStream.createTime
        if (isInitialVersion) {
            firstOperator = operator
            firstEventTime = eventTime
        }
        processAware(eventStream)
        return this
    }

    private fun processAware(eventStream: DomainEventStream) {
        if (state is VersionAware) {
            state.version = eventStream.version
        }
    }

    /**
     * Applies a single domain event to the aggregate's state.
     *
     * Handles special events like [AggregateDeleted], [AggregateRecovered], and [OwnerTransferred],
     * and invokes registered sourcing functions for other events.
     *
     * @param domainEvent The domain event to apply.
     */
    private fun sourcing(domainEvent: DomainEvent<*>) {
        val domainEventBody = domainEvent.body
        if (domainEventBody is AggregateDeleted) {
            deleted = true
        }
        if (domainEventBody is AggregateRecovered) {
            deleted = false
        }
        if (domainEventBody is OwnerTransferred) {
            ownerId = domainEventBody.toOwnerId
        }
        if (domainEventBody is SpaceTransferred) {
            spaceId = domainEventBody.toSpaceId
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

    override fun toString(): String = "SimpleStateAggregate(aggregateId=$aggregateId, version=$version)"
}

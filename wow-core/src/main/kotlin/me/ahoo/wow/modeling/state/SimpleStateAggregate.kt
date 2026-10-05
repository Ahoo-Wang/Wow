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
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.EMPTY_ABAC_TAGS
import me.ahoo.wow.api.abac.ResourceTagsApplied
import me.ahoo.wow.api.annotation.InternalWowApi
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
import me.ahoo.wow.event.ignoreSourcing
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata

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
 * @property spaceId The identifier of the current space of the aggregate. Defaults to [SpaceIdCapable.DEFAULT_SPACE_ID].
 * @property version The current version of the aggregate. Defaults to [Version.UNINITIALIZED_VERSION].
 * @property eventId The ID of the last processed event. Defaults to an empty string.
 * @property firstOperator The operator who initiated the first event. Defaults to an empty string.
 * @property operator The operator who initiated the last event. Defaults to an empty string.
 * @property firstEventTime The timestamp of the first event. Defaults to 0.
 * @property eventTime The timestamp of the last event. Defaults to 0.
 * @property tags The ABAC tags associated with the aggregate.
 * @property deleted Indicates whether the aggregate has been deleted. Defaults to false.
 */
@InternalWowApi
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
    override var tags: AbacTags = EMPTY_ABAC_TAGS,
    override var deleted: Boolean = false
) : StateAggregate<S>,
    TypedAggregate<S> by metadata {
    private val sourcingTable = metadata.sourcingTable

    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Applies a stream of domain events to update the aggregate's state.
     *
     * The stream is checked first (aggregate ID, next version). Then every event is applied to the state: the
     * user's sourcing functions run, and the framework's system events (delete, recover, owner, space, tags) are noted.
     * Only after all of them succeeded does the aggregate take the stream's version, event ID, operator, time and the
     * noted metadata. When a sourcing function throws, the aggregate's metadata stays at the previous version; the
     * state object may hold part of the stream and must be discarded.
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
        val metadata = SourcedMetadata(
            ownerId = eventStream.ownerId.ifBlank { ownerId },
            spaceId = eventStream.spaceId.ifBlank { spaceId },
            deleted = deleted,
            tags = tags,
        )
        for (domainEvent in eventStream) {
            sourcing(domainEvent, metadata)
        }
        commit(eventStream, metadata)
        return this
    }

    /** Advances the aggregate to [eventStream] once every event of it has been applied. */
    private fun commit(eventStream: DomainEventStream, metadata: SourcedMetadata) {
        version = eventStream.version
        ownerId = metadata.ownerId
        spaceId = metadata.spaceId
        deleted = metadata.deleted
        tags = metadata.tags
        eventId = eventStream.id
        operator = eventStream.header.operator.orEmpty()
        eventTime = eventStream.createTime
        if (isInitialVersion) {
            firstOperator = operator
            firstEventTime = eventTime
        }
        if (state is VersionAware) {
            state.version = eventStream.version
        }
        if (state is StateAggregateTagsExtractor<*>) {
            @Suppress("UNCHECKED_CAST")
            val extractor = state as StateAggregateTagsExtractor<S>
            tags = extractor.extract(this)
        }
    }

    /**
     * Applies a single domain event to the aggregate's state.
     *
     * Notes the effect of the system events [AggregateDeleted], [AggregateRecovered], [OwnerTransferred],
     * [SpaceTransferred] and [ResourceTagsApplied] on [metadata], and invokes the registered sourcing function.
     *
     * @param domainEvent The domain event to apply.
     * @param metadata The metadata the stream will set.
     */
    private fun sourcing(domainEvent: DomainEvent<*>, metadata: SourcedMetadata) {
        val domainEventBody = domainEvent.body
        if (domainEventBody is AggregateDeleted) {
            metadata.deleted = true
        }
        if (domainEventBody is AggregateRecovered) {
            metadata.deleted = false
        }
        if (domainEventBody is OwnerTransferred) {
            metadata.ownerId = domainEventBody.toOwnerId
        }
        if (domainEventBody is SpaceTransferred) {
            metadata.spaceId = domainEventBody.toSpaceId
        }
        if (domainEventBody is ResourceTagsApplied) {
            metadata.tags = domainEventBody.tags
        }
        val sourcingFunction = sourcingTable[domainEventBody.javaClass]
        if (sourcingFunction != null) {
            sourcingFunction.invoke(state, domainEvent)
        } else {
            log.debug {
                "Sourcing $domainEvent Ignore this domain event because onSourcing does not exist."
            }
        }
    }

    /** The aggregate metadata a stream being sourced will set, applied only when the whole stream was sourced. */
    private class SourcedMetadata(
        var ownerId: String,
        var spaceId: SpaceId,
        var deleted: Boolean,
        var tags: AbacTags
    )

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

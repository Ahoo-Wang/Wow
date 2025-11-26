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

package me.ahoo.wow.api.event

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.naming.Named

const val DEFAULT_EVENT_SEQUENCE = 1

/**
 * Represents a domain event published by an aggregate in response to a command.
 *
 * Domain events capture significant business occurrences that have happened within
 * an aggregate. They are immutable facts about past actions and serve as the
 * primary mechanism for communicating state changes to other parts of the system.
 *
 * Domain events follow declarative design principles (idempotent, similar to
 * Kubernetes apply or Docker image layers). During event sourcing, aggregates
 * should simply apply domain events as overlay layers without complex logic
 * or conditional checks, eliminating the need for explicit sourcing functions.
 *
 * Key characteristics:
 * - **Immutable**: Events represent facts that cannot be changed
 * - **Named**: Each event has a specific business meaning
 * - **Versioned**: Events carry version information for compatibility
 * - **Sequenced**: Events maintain order within an aggregate's lifecycle
 *
 * @param T The type of the event body payload
 *
 * @property aggregateId The identifier of the aggregate that published this event
 * @property sequence The sequence number of this event within the aggregate's event stream
 * @property revision The version of the aggregate when this event was published
 * @property isLast Whether this is the final event in the current event stream
 *
 * @see me.ahoo.wow.api.messaging.NamedMessage for base messaging capabilities
 * @see me.ahoo.wow.api.modeling.AggregateId for aggregate identification
 * @see Revision for versioning information
 *
 * @author ahoo wang
 */
interface DomainEvent<T : Any> :
    EventMessage<DomainEvent<T>, T>,
    Named,
    Revision {
    /**
     * The unique identifier of the aggregate that published this domain event.
     *
     * This ID links the event to its originating aggregate and enables proper
     * event routing, correlation, and state reconstruction during event sourcing.
     */
    override val aggregateId: AggregateId

    /**
     * The sequence number of this event within the aggregate's event stream.
     *
     * Sequence numbers provide ordering guarantees and help detect missing or
     * duplicate events. They start from 1 and increment for each event published
     * by the aggregate. Defaults to [DEFAULT_EVENT_SEQUENCE] for single events.
     */
    val sequence: Int
        get() = DEFAULT_EVENT_SEQUENCE

    /**
     * The revision/version of the aggregate when this event was published.
     *
     * The revision indicates the schema version of the aggregate at the time
     * the event was created. This enables backward compatibility and proper
     * event deserialization across different versions of the domain model.
     * Defaults to [DEFAULT_REVISION].
     */
    override val revision: String
        get() = DEFAULT_REVISION

    /**
     * Indicates whether this is the last event in the current event stream.
     *
     * When `true`, this signals that no more events will be published in the
     * current command processing cycle. This is useful for optimization and
     * determining when event processing is complete. Defaults to `true` for
     * single events or the last event in a batch.
     */
    val isLast: Boolean
        get() = true
}

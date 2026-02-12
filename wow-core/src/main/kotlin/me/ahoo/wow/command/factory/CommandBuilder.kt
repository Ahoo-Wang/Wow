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

package me.ahoo.wow.command.factory

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.command.factory.CommandBuilder.Companion.builder
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.withLocalFirst

/**
 * Builder interface for constructing command messages.
 *
 * CommandBuilder provides a fluent API for building command messages with all
 * necessary properties like aggregate targeting, headers, and timing information.
 * It supports method chaining for easy construction of complex command messages.
 *
 * @see MutableCommandBuilder
 * @see Identifier
 */

/**
 * Builder interface for constructing command messages.
 *
 * CommandBuilder provides a fluent API for building command messages with all
 * necessary properties like aggregate targeting, headers, and timing information.
 * It supports method chaining for easy construction of complex command messages.
 *
 * @property body the command payload object
 * @property requestId unique identifier for the request (optional)
 * @property aggregateId the target aggregate instance identifier (optional)
 * @property tenantId the tenant identifier for multi-tenancy (optional)
 * @property ownerId the owner identifier of the aggregate (optional)
 * @property aggregateVersion expected version for optimistic concurrency (optional)
 * @property namedAggregate the named aggregate information (optional)
 * @property header message headers for additional metadata
 * @property createTime timestamp when the command was created
 * @property upstream the domain event that triggered this command (optional)
 * @property ownerIdSameAsAggregateId whether owner ID should match aggregate ID
 * @see MutableCommandBuilder
 * @see Identifier
 */
interface CommandBuilder : Identifier {
    val body: Any
    val requestId: String?
    val aggregateId: String?
    val tenantId: String?
    val ownerId: String?
    val spaceId: SpaceId?
    val aggregateVersion: Int?
    val namedAggregate: NamedAggregate?
    val header: Header
    val createTime: Long
    val upstream: DomainEvent<*>?
    val ownerIdSameAsAggregateId: Boolean

    /**
     * Sets the command body/payload.
     *
     * @param body the command payload object
     * @return this builder for method chaining
     */
    fun body(body: Any): CommandBuilder

    /**
     * Casts the command body to the specified type.
     *
     * This is a convenience method for type-safe access to the command body.
     * Use with caution as it performs an unchecked cast.
     *
     * @param C the desired type to cast to
     * @return the command body cast to type C
     * @throws ClassCastException if the body cannot be cast to type C
     */
    fun <C> bodyAs(): C {
        @Suppress("UNCHECKED_CAST")
        return body as C
    }

    /**
     * Sets the unique identifier for the command message.
     *
     * @param id the command message identifier
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.id
     */
    fun id(id: String): CommandBuilder

    /**
     * Sets the request identifier for tracking related commands.
     *
     * @param requestId the request identifier, or null
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.requestId
     */
    fun requestId(requestId: String?): CommandBuilder

    /**
     * Sets the request ID only if it's not already set.
     *
     * This method is useful for setting a default request ID without
     * overriding an existing one.
     *
     * @param requestId the request ID to set if absent
     * @return this builder for method chaining
     * @see requestId
     */
    fun requestIdIfAbsent(requestId: String): CommandBuilder {
        if (this.requestId == null) {
            requestId(requestId)
        }
        return this
    }

    /**
     * Sets the target aggregate instance identifier.
     *
     * @param aggregateId the aggregate instance ID, or null for commands that create aggregates
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.id
     */
    fun aggregateId(aggregateId: String?): CommandBuilder

    /**
     * Sets the tenant identifier for multi-tenancy support.
     *
     * @param tenantId the tenant identifier, or null
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.tenantId
     */
    fun tenantId(tenantId: String?): CommandBuilder

    /**
     * Sets the owner identifier of the aggregate.
     *
     * @param ownerId the owner identifier, or null
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.modeling.AggregateId.ownerId
     */
    fun ownerId(ownerId: String?): CommandBuilder

    fun spaceId(spaceId: SpaceId?): CommandBuilder

    /**
     * Sets the tenant ID only if it's not already set.
     *
     * This method is useful for setting a default tenant ID without
     * overriding an existing one.
     *
     * @param tenantId the tenant ID to set if absent
     * @return this builder for method chaining
     * @see tenantId
     */
    fun tenantIdIfAbsent(tenantId: String): CommandBuilder {
        if (this.tenantId == null) {
            tenantId(tenantId)
        }
        return this
    }

    fun spaceIdIfAbsent(spaceId: SpaceId): CommandBuilder {
        if (this.spaceId == null) {
            spaceId(spaceId)
        }
        return this
    }

    /**
     * Sets the expected aggregate version for optimistic concurrency control.
     *
     * @param aggregateVersion the expected version, or null to skip version checking
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateVersion
     */
    fun aggregateVersion(aggregateVersion: Int?): CommandBuilder

    /**
     * Sets the named aggregate information for the command target.
     *
     * @param namedAggregate the named aggregate containing context and aggregate names
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.namedAggregate
     * @see NamedAggregate
     */
    fun namedAggregate(namedAggregate: NamedAggregate): CommandBuilder

    /**
     * Sets the message header containing additional metadata.
     *
     * @param header the header object with metadata
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.header
     * @see Header
     */
    fun header(header: Header): CommandBuilder

    /**
     * Customizes the message header using a lambda function.
     *
     * @param customize function to modify the existing header
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.command.CommandMessage.header
     * @see Header
     */
    fun header(customize: (header: Header) -> Unit): CommandBuilder

    /**
     * Enables or disables local-first processing mode.
     *
     * When enabled, commands will be processed locally before being sent to
     * distributed buses, which can improve performance for local operations.
     *
     * @param localFirst whether to enable local-first mode (default: true)
     * @return this builder for method chaining
     * @see me.ahoo.wow.messaging.LocalFirstMessageBus
     */
    fun localFirst(localFirst: Boolean = true): CommandBuilder =
        header {
            it.withLocalFirst(localFirst)
        }

    /**
     * Sets the creation timestamp for the command message.
     *
     * @param createTime the creation time as a Unix timestamp in milliseconds
     * @return this builder for method chaining
     * @see me.ahoo.wow.api.messaging.NamedMessage.createTime
     */
    fun createTime(createTime: Long): CommandBuilder

    /**
     * Sets the upstream domain event that triggered this command.
     *
     * This is useful for tracking the event sourcing chain and maintaining
     * causality between events and commands.
     *
     * @param upstream the domain event that triggered this command
     * @return this builder for method chaining
     * @see DomainEvent
     */
    fun upstream(upstream: DomainEvent<*>): CommandBuilder

    /**
     * Sets whether the owner ID should be the same as the aggregate ID.
     *
     * This is useful for aggregates where the owner is the aggregate itself,
     * such as user aggregates where the user ID serves as both identifiers.
     *
     * @param ownerIdSameAsAggregateId whether owner ID equals aggregate ID
     * @return this builder for method chaining
     */
    fun ownerIdSameAsAggregateId(ownerIdSameAsAggregateId: Boolean): CommandBuilder

    companion object {
        /**
         * Extension function to create a command builder from any object.
         *
         * This convenience method allows any object to be used as a command body
         * when building a command message.
         *
         * @receiver the object to use as the command body
         * @return a new CommandBuilder with this object as the body
         * @see builder
         */
        fun Any.commandBuilder(): CommandBuilder = builder(this)

        /**
         * Creates a new command builder with the specified body.
         *
         * @param body the command payload object
         * @return a new MutableCommandBuilder instance
         * @see MutableCommandBuilder
         */
        fun builder(body: Any): CommandBuilder = MutableCommandBuilder(body)
    }
}

/**
 * Mutable implementation of CommandBuilder.
 *
 * This class provides a concrete implementation of the CommandBuilder interface
 * with mutable properties that can be set through the fluent API methods.
 * All properties have sensible defaults and can be modified through method chaining.
 *
 * @param body the initial command payload object
 * @see CommandBuilder
 */
class MutableCommandBuilder(
    body: Any
) : CommandBuilder {
    override var body: Any = body
        private set
    override var id: String = generateGlobalId()
        private set
    override var requestId: String? = null
        private set
    override var aggregateId: String? = null
        private set
    override var tenantId: String? = null
        private set
    override var ownerId: String? = null
        private set
    override var spaceId: SpaceId? = null
        private set
    override var aggregateVersion: Int? = null
        private set
    override var namedAggregate: NamedAggregate? = null
        private set
    override var header: Header = DefaultHeader.empty()
        private set
    override var createTime: Long = System.currentTimeMillis()
        private set
    override var upstream: DomainEvent<*>? = null
        private set
    override var ownerIdSameAsAggregateId: Boolean = false
        private set

    override fun body(body: Any): CommandBuilder {
        this.body = body
        return this
    }

    override fun id(id: String): CommandBuilder {
        this.id = id
        return this
    }

    override fun requestId(requestId: String?): CommandBuilder {
        this.requestId = requestId
        return this
    }

    override fun aggregateId(aggregateId: String?): CommandBuilder {
        this.aggregateId = aggregateId
        return this
    }

    override fun tenantId(tenantId: String?): CommandBuilder {
        this.tenantId = tenantId
        return this
    }

    override fun ownerId(ownerId: String?): CommandBuilder {
        this.ownerId = ownerId
        return this
    }

    override fun spaceId(spaceId: SpaceId?): CommandBuilder {
        this.spaceId = spaceId
        return this
    }

    override fun aggregateVersion(aggregateVersion: Int?): CommandBuilder {
        this.aggregateVersion = aggregateVersion
        return this
    }

    override fun namedAggregate(namedAggregate: NamedAggregate): CommandBuilder {
        this.namedAggregate = namedAggregate
        return this
    }

    override fun header(header: Header): CommandBuilder {
        this.header = header
        return this
    }

    override fun header(customize: (header: Header) -> Unit): CommandBuilder {
        customize(header)
        return this
    }

    override fun createTime(createTime: Long): CommandBuilder {
        this.createTime = createTime
        return this
    }

    override fun upstream(upstream: DomainEvent<*>): CommandBuilder {
        this.upstream = upstream
        return this
    }

    override fun ownerIdSameAsAggregateId(ownerIdSameAsAggregateId: Boolean): CommandBuilder {
        this.ownerIdSameAsAggregateId = ownerIdSameAsAggregateId
        return this
    }
}

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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.naming.annotation.toName
import me.ahoo.wow.test.dsl.InjectServiceCapable
import kotlin.reflect.KType
import kotlin.reflect.full.defaultType

/**
 * Defines the stage for setting up test preconditions in aggregate testing.
 *
 * This interface provides methods to configure the initial state of aggregates
 * before command execution, including injecting services, setting owner IDs,
 * and providing initial events or state.
 *
 * @param S the type of the aggregate state
 */
interface GivenStage<S : Any> : InjectServiceCapable<GivenStage<S>> {
    /**
     * Injects a service instance into the test context.
     *
     * This method allows registering mock or test implementations of services
     * that the aggregate depends on during command execution.
     *
     * @param SERVICE the type of the service
     * @param service the service instance to inject
     * @param serviceName the name to register the service under (auto-generated from class name by default)
     * @param serviceType the KType of the service (auto-generated from class by default)
     * @return this GivenStage for method chaining
     */
    @Deprecated(
        "Use inject {} instead.",
        replaceWith = ReplaceWith(
            """
        inject {
            register<SERVICE>(service)
        }
    """,
            "me.ahoo.wow.ioc.register"
        )
    )
    fun <SERVICE : Any> inject(
        service: SERVICE,
        serviceName: String = service.javaClass.toName(),
        serviceType: KType = service.javaClass.kotlin.defaultType
    ): GivenStage<S> {
        inject {
            register(service, serviceName, serviceType)
        }
        return this
    }

    /**
     * Sets the owner ID for the aggregate in this test.
     *
     * The owner ID is used to determine access permissions and may affect
     * command execution behavior in multi-tenant scenarios.
     *
     * @param ownerId the owner identifier to set
     * @return this GivenStage for method chaining
     */
    fun givenOwnerId(ownerId: String): GivenStage<S>
    fun givenSpaceId(spaceId: SpaceId): GivenStage<S>

    /**
     * Sets up the aggregate by replaying the given domain events.
     *
     * This method initializes the aggregate state by applying the provided events
     * in order, simulating previous command executions.
     *
     * @param events the domain events to replay on the aggregate
     * @return a WhenStage for specifying the command to execute
     */
    fun given(vararg events: Any): WhenStage<S> {
        return givenEvent(*events)
    }

    fun givenEvent(vararg events: Any): WhenStage<S>

    fun givenState(state: S, version: Int): WhenStage<S>

    fun givenState(state: StateAggregate<S>): WhenStage<S>
}

/**
 * Extension function that allows executing a command directly from the Given stage.
 *
 * This convenience method combines the given setup (with no events) and command execution
 * in a single call, useful for testing commands on empty aggregates.
 *
 * @param command the command to execute
 * @param header optional command header (defaults to empty)
 * @param ownerId optional owner ID override (defaults to previously set owner)
 * @return an ExpectStage for defining expectations
 */
fun <S : Any> GivenStage<S>.whenCommand(
    command: Any,
    header: Header = DefaultHeader.empty(),
    ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID
): ExpectStage<S> = this.givenEvent().whenCommand(command, header, ownerId, spaceId)

/**
 * Alias for whenCommand, providing a more natural language interface.
 *
 * This method is equivalent to whenCommand but uses the keyword 'when' which
 * reads more naturally in test specifications.
 *
 * @param command the command to execute
 * @param header optional command header (defaults to empty)
 * @param ownerId optional owner ID override (defaults to previously set owner)
 * @return an ExpectStage for defining expectations
 */
@Deprecated(
    "use whenCommand instead.",
    replaceWith = ReplaceWith("whenCommand(command)", "me.ahoo.wow.test.aggregate.whenCommand")
)
fun <S : Any> GivenStage<S>.`when`(
    command: Any,
    header: Header = DefaultHeader.empty(),
    ownerId: String = OwnerId.DEFAULT_OWNER_ID
): ExpectStage<S> = this.whenCommand(command, header, ownerId)

/**
 * Abstract base class for GivenStage implementations.
 *
 * This class provides common functionality for setting up aggregate test preconditions,
 * including owner ID management and service injection.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 */
internal abstract class AbstractGivenStage<C : Any, S : Any> : GivenStage<S> {
    /** The aggregate identifier for this test context. */
    abstract val aggregateId: AggregateId

    /** Metadata describing the aggregate structure and behavior. */
    abstract val metadata: AggregateMetadata<C, S>

    /** Factory for creating state aggregates. */
    abstract val stateAggregateFactory: StateAggregateFactory

    /** Factory for creating command aggregates. */
    abstract val commandAggregateFactory: CommandAggregateFactory
    abstract val serviceProvider: ServiceProvider

    /** The owner ID for this test context, defaults to system owner. */
    protected var ownerId: String = OwnerId.DEFAULT_OWNER_ID
        private set

    protected var spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID
        private set

    /**
     * Injects services into the test context using a configuration block.
     *
     * @param inject a lambda that configures the service provider
     * @return this GivenStage for method chaining
     */
    override fun inject(inject: ServiceProvider.() -> Unit): GivenStage<S> {
        inject(serviceProvider)
        return this
    }

    /**
     * Sets the owner ID for this test context.
     *
     * @param ownerId the new owner ID to set
     * @return this GivenStage for method chaining
     */
    override fun givenOwnerId(ownerId: String): GivenStage<S> {
        this.ownerId = ownerId
        return this
    }

    override fun givenSpaceId(spaceId: SpaceId): GivenStage<S> {
        this.spaceId = spaceId
        return this
    }

    /**
     * Sets up the aggregate by replaying the given domain events.
     *
     * This method initializes the aggregate state by applying the provided events
     * in order, simulating previous command executions.
     *
     * @param events the domain events to replay on the aggregate
     * @return a WhenStage for specifying the command to execute
     */
    override fun givenEvent(vararg events: Any): WhenStage<S> =
        DefaultWhenStage(
            aggregateId = aggregateId,
            ownerId = ownerId,
            spaceId = spaceId,
            events = events,
            metadata = metadata,
            stateAggregateFactory = stateAggregateFactory,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )

    /**
     * Sets up the aggregate with the specified state and version.
     *
     * @param state the state object to initialize the aggregate with
     * @param version the version number for the aggregate
     * @return a WhenStage for command execution
     */
    override fun givenState(
        state: S,
        version: Int
    ): WhenStage<S> {
        val stateAggregate =
            metadata.toStateAggregate(
                state = state,
                version = version,
                ownerId = ownerId,
                spaceId = spaceId,
                aggregateId = aggregateId.id,
                tenantId = aggregateId.tenantId,
            )
        return givenState(stateAggregate)
    }

    /**
     * Sets up the aggregate with a complete StateAggregate instance.
     *
     * @param state the StateAggregate to use for initialization
     * @return a WhenStage for command execution
     */
    override fun givenState(state: StateAggregate<S>): WhenStage<S> =
        GivenStateWhenStage(
            metadata = metadata,
            stateAggregate = state,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )
}

/**
 * Default implementation of GivenStage for aggregate testing.
 *
 * This class provides the standard implementation for setting up aggregate test preconditions,
 * using the constructor state aggregate factory by default.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 * @property aggregateId the aggregate identifier for this test
 * @property metadata metadata about the aggregate
 * @property stateAggregateFactory factory for creating state aggregates (defaults to constructor factory)
 * @property commandAggregateFactory factory for creating command aggregates
 * @property serviceProvider provider for service dependencies
 */
internal class DefaultGivenStage<C : Any, S : Any>(
    override val aggregateId: AggregateId,
    override val metadata: AggregateMetadata<C, S>,
    override val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
    override val commandAggregateFactory: CommandAggregateFactory,
    override val serviceProvider: ServiceProvider
) : AbstractGivenStage<C, S>()

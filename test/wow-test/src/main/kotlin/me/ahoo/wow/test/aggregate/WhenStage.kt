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
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.validation.validate
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

/**
 * Defines the stage for specifying commands to execute in aggregate testing.
 *
 * This interface provides methods to execute commands on aggregates that have been
 * set up in the Given stage, transitioning to the Expect stage for result validation.
 *
 * @param S the type of the aggregate state
 */
interface WhenStage<S : Any> {
    /**
     * Executes a command with a custom header.
     *
     * @param command the command to execute
     * @param header the command header to use
     * @return an ExpectStage for defining expectations on the results
     */
    fun `when`(
        command: Any,
        header: Header
    ): ExpectStage<S> = this.whenCommand(command = command, header = header)

    /**
     * Executes a command with default header.
     *
     * @param command the command to execute
     * @return an ExpectStage for defining expectations on the results
     */
    fun `when`(command: Any): ExpectStage<S> =
        this.whenCommand(command = command, header = DefaultHeader.Companion.empty())

    /**
     * Executes a command with full parameter control.
     *
     * @param command the command to execute
     * @param header optional command header (defaults to empty)
     * @param ownerId optional owner ID override (defaults to previously set owner)
     * @return an ExpectStage for defining expectations on the results
     */
    fun whenCommand(
        command: Any,
        header: Header = DefaultHeader.empty(),
        ownerId: String = OwnerId.DEFAULT_OWNER_ID
    ): ExpectStage<S>
}

/**
 * Default implementation of WhenStage that executes commands on aggregates.
 *
 * This class handles the command execution phase, setting up the aggregate state
 * (either from events or initial state) and processing the command to produce results.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 * @property aggregateId the aggregate identifier
 * @property ownerId the owner ID for the command
 * @property events the events to replay for state setup
 * @property metadata aggregate metadata
 * @property stateAggregateFactory factory for creating state aggregates
 * @property commandAggregateFactory factory for creating command aggregates
 * @property serviceProvider provider for service dependencies
 */
internal class DefaultWhenStage<C : Any, S : Any>(
    private val aggregateId: AggregateId,
    private val ownerId: String,
    private val events: Array<out Any>,
    private val metadata: AggregateMetadata<C, S>,
    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider
) : WhenStage<S> {
    @Suppress("UseRequire", "LongMethod")
    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String
    ): ExpectStage<S> {
        val commandMessage = command.toCommandMessage(
            aggregateId = aggregateId.id,
            namedAggregate = aggregateId.namedAggregate,
            tenantId = aggregateId.tenantId,
            ownerId = ownerId.ifBlank { this.ownerId },
            header = header,
        )

        if (commandMessage.isCreate && events.isNotEmpty()) {
            throw IllegalArgumentException("Create aggregate command[$command] can not given sourcing event.")
        }
        val serverCommandExchange = SimpleServerCommandExchange(
            message = commandMessage,
        )
        serverCommandExchange.setServiceProvider(serviceProvider)
        val commandAggregateId = commandMessage.aggregateId
        val expectedResultMono = stateAggregateFactory
            .createAsMono(
                metadata.state,
                commandAggregateId,
            ).map {
                try {
                    commandMessage.body.validate()
                } catch (throwable: Throwable) {
                    return@map ExpectedResult(exchange = serverCommandExchange, stateAggregate = it, error = throwable)
                }

                if (commandMessage.isCreate) {
                    return@map ExpectedResult(exchange = serverCommandExchange, stateAggregate = it)
                }

                if (events.isEmpty()) {
                    if (it.initialized || commandMessage.allowCreate) {
                        return@map ExpectedResult(exchange = serverCommandExchange, stateAggregate = it)
                    }
                    return@map ExpectedResult(
                        exchange = serverCommandExchange,
                        stateAggregate = it,
                        error = IllegalArgumentException(
                            "Non-create aggregate command[$command] given at least one sourcing event.",
                        ),
                    )
                }

                val initializationCommand = GivenInitializationCommand(
                    aggregateId = commandAggregateId,
                    ownerId = this.ownerId,
                )

                val domainEventStream = events.toDomainEventStream(
                    upstream = initializationCommand,
                    aggregateVersion = it.version,
                )
                try {
                    it.onSourcing(domainEventStream)
                } catch (throwable: Throwable) {
                    return@map ExpectedResult(exchange = serverCommandExchange, stateAggregate = it, error = throwable)
                }
                ExpectedResult(exchange = serverCommandExchange, stateAggregate = it)
            }.flatMap { expectedResult ->
                if (expectedResult.hasError) {
                    return@flatMap expectedResult.toMono()
                }
                val commandAggregate = commandAggregateFactory.create(metadata, expectedResult.stateAggregate)
                commandAggregate.process(serverCommandExchange)
                    .map {
                        expectedResult.copy(
                            domainEventStream = serverCommandExchange.getEventStream(),
                            error = serverCommandExchange.getError(),
                        )
                    }.onErrorResume {
                        expectedResult.copy(error = it).toMono()
                    }
            }.switchIfEmpty {
                IllegalArgumentException("A command generates at least one event.").toMono()
            }
        return DefaultExpectStage(
            metadata = metadata,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
            expectedResultMono = expectedResultMono,
        )
    }
}

/**
 * WhenStage implementation that uses a pre-existing StateAggregate.
 *
 * This class is used when testing commands on aggregates that have already been
 * initialized with specific state, bypassing the event replay process.
 *
 * @param C the type of the command aggregate
 * @param S the type of the aggregate state
 * @property metadata aggregate metadata
 * @property stateAggregate the pre-initialized state aggregate
 * @property commandAggregateFactory factory for creating command aggregates
 * @property serviceProvider provider for service dependencies
 */
internal class GivenStateWhenStage<C : Any, S : Any>(
    private val metadata: AggregateMetadata<C, S>,
    private val stateAggregate: StateAggregate<S>,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider
) : WhenStage<S> {
    /**
     * Executes a command on the pre-existing state aggregate.
     *
     * This method creates a command message, processes it through the command aggregate,
     * and returns the results wrapped in an ExpectStage for validation.
     *
     * @param command the command to execute
     * @param header the command header
     * @param ownerId the owner ID for the command
     * @return an ExpectStage containing the execution results
     */
    override fun whenCommand(
        command: Any,
        header: Header,
        ownerId: String
    ): ExpectStage<S> {
        val commandMessage = command.toCommandMessage(
            aggregateId = stateAggregate.aggregateId.id,
            namedAggregate = stateAggregate.aggregateId.namedAggregate,
            tenantId = stateAggregate.aggregateId.tenantId,
            ownerId = ownerId,
            header = header,
        )
        val commandAggregate = commandAggregateFactory.create(metadata, stateAggregate)
        val serverCommandExchange = SimpleServerCommandExchange(
            message = commandMessage,
        ).setServiceProvider(serviceProvider)

        val expectedResultMono = commandAggregate.process(serverCommandExchange)
            .map {
                ExpectedResult(
                    exchange = serverCommandExchange,
                    stateAggregate = stateAggregate,
                    domainEventStream = serverCommandExchange.getEventStream(),
                    error = serverCommandExchange.getError(),
                )
            }.onErrorResume {
                ExpectedResult(
                    exchange = serverCommandExchange,
                    stateAggregate = stateAggregate,
                    domainEventStream = serverCommandExchange.getEventStream(),
                    error = it,
                ).toMono()
            }
        return DefaultExpectStage(
            metadata = metadata,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
            expectedResultMono = expectedResultMono,
        )
    }
}

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
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.test.validation.validate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.function.Consumer

internal class DefaultGivenStage<C : Any, S : Any>(
    private val aggregateId: AggregateId,
    private val metadata: AggregateMetadata<C, S>,
    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider
) : GivenStage<S> {
    override fun <SERVICE : Any> inject(service: SERVICE, serviceName: String): GivenStage<S> {
        serviceProvider.register(serviceName, service)
        return this
    }

    override fun given(vararg events: Any): WhenStage<S> {
        return DefaultWhenStage(
            aggregateId,
            events,
            metadata,
            stateAggregateFactory,
            commandAggregateFactory,
            serviceProvider,
        )
    }
}

internal class DefaultWhenStage<C : Any, S : Any>(
    private val aggregateId: AggregateId,
    private val events: Array<out Any>,
    private val metadata: AggregateMetadata<C, S>,
    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider
) : WhenStage<S> {
    @Suppress("UseRequire", "LongMethod")
    override fun `when`(command: Any, header: Header): ExpectStage<S> {
        val commandMessage = command.toCommandMessage(
            aggregateId = aggregateId.id,
            namedAggregate = aggregateId.namedAggregate,
            tenantId = aggregateId.tenantId,
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
        val expectedResultMono = stateAggregateFactory.create(
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

            val initializationCommand =
                GivenInitializationCommand(
                    aggregateId = commandAggregateId,
                )

            val domainEventStream = events.toDomainEventStream(
                command = initializationCommand,
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
            commandAggregate.process(serverCommandExchange).map {
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

internal class DefaultVerifiedStage<C : Any, S : Any>(
    override val verifiedResult: ExpectedResult<S>,
    private val metadata: AggregateMetadata<C, S>,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider
) : VerifiedStage<S>, GivenStage<S> {
    override fun <SERVICE : Any> inject(service: SERVICE, serviceName: String): GivenStage<S> {
        serviceProvider.register(serviceName, service)
        return this
    }

    override fun given(vararg events: Any): WhenStage<S> {
        return DefaultWhenStage(
            aggregateId = verifiedResult.stateAggregate.aggregateId,
            events = events,
            metadata = metadata,
            stateAggregateFactory = object : StateAggregateFactory {
                override fun <S : Any> create(
                    metadata: StateAggregateMetadata<S>,
                    aggregateId: AggregateId
                ): Mono<StateAggregate<S>> {
                    @Suppress("UNCHECKED_CAST")
                    return Mono.just(verifiedResult.stateAggregate as StateAggregate<S>)
                }
            },
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )
    }

    override fun then(verifyError: Boolean): GivenStage<S> {
        verifyError(verifyError)
        return this
    }

    private fun verifyError(verifyError: Boolean) {
        if (verifyError) {
            require(!verifiedResult.hasError) {
                "An exception[${verifiedResult.error}] occurred in the verified result."
            }
        }
    }

    override fun fork(verifyError: Boolean, handle: GivenStage<S>.(ExpectedResult<S>) -> Unit): VerifiedStage<S> {
        verifyError(verifyError)
        val forkedStateAggregate = verifyStateAggregateSerializable(verifiedResult.stateAggregate)
        val forkedResult = verifiedResult.copy(stateAggregate = forkedStateAggregate)
        require(serviceProvider is SimpleServiceProvider)
        val forkedServiceProvider = serviceProvider.copy()
        val forkedGivenStage = DefaultVerifiedStage(
            verifiedResult = forkedResult,
            metadata = this.metadata,
            commandAggregateFactory = SimpleCommandAggregateFactory(InMemoryEventStore()),
            serviceProvider = forkedServiceProvider,
        )
        handle(forkedGivenStage, forkedResult)
        return this
    }
}

private fun <S : Any> verifyStateAggregateSerializable(stateAggregate: StateAggregate<S>): StateAggregate<S> {
    if (!stateAggregate.initialized) {
        return stateAggregate
    }
    val serialized = stateAggregate.toJsonString()
    val deserialized = serialized.toObject<StateAggregate<S>>()
    assertThat(deserialized, equalTo(stateAggregate))
    return deserialized
}

internal class DefaultExpectStage<C : Any, S : Any>(
    private val metadata: AggregateMetadata<C, S>,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val serviceProvider: ServiceProvider,
    private val expectedResultMono: Mono<ExpectedResult<S>>
) : ExpectStage<S> {

    private val expectStates: MutableList<Consumer<ExpectedResult<S>>> = mutableListOf()

    override fun expect(expected: Consumer<ExpectedResult<S>>): ExpectStage<S> {
        expectStates.add(expected)
        return this
    }

    override fun verify(): VerifiedStage<S> {
        lateinit var expectedResult: ExpectedResult<S>
        expectedResultMono
            .test()
            .consumeNextWith {
                verifyStateAggregateSerializable(it.stateAggregate)
                expectedResult = it
                for (expectState in expectStates) {
                    expectState.accept(it)
                }
            }
            .verifyComplete()
        return DefaultVerifiedStage(
            verifiedResult = expectedResult,
            metadata = metadata,
            commandAggregateFactory = commandAggregateFactory,
            serviceProvider = serviceProvider,
        )
    }
}

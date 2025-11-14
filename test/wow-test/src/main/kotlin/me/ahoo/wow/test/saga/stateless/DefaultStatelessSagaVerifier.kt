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

package me.ahoo.wow.test.saga.stateless

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.SimpleStateDomainEventExchange
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.accessor.constructor.InjectableObjectFactory
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.processor.ProcessorMetadata
import me.ahoo.wow.saga.stateless.CommandStream
import me.ahoo.wow.saga.stateless.DefaultCommandStream
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.test.saga.stateless.GivenReadOnlyStateAggregate.Companion.toReadOnlyStateAggregate
import org.assertj.core.error.MultipleAssertionsError
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.test.test
import java.lang.reflect.Constructor
import java.util.function.Consumer

/**
 * Default implementation of [WhenStage] for stateless saga testing.
 *
 * This class handles the setup and execution of stateless saga processing
 * in response to domain events, providing the foundation for expectation verification.
 *
 * @param T The type of the saga being tested.
 * @property sagaMetadata Metadata about the saga processor.
 * @property serviceProvider Provider for dependency injection.
 * @property commandGateway Gateway for sending commands.
 * @property commandMessageFactory Factory for creating command messages.
 */
internal class DefaultWhenStage<T : Any>(
    private val sagaMetadata: ProcessorMetadata<T, DomainEventExchange<*>>,
    private val serviceProvider: ServiceProvider,
    private val commandGateway: CommandGateway,
    private val commandMessageFactory: CommandMessageFactory
) : WhenStage<T> {
    companion object {
        /**
         * Constant command ID used for stateless saga verification.
         */
        const val STATELESS_SAGA_COMMAND_ID = "__StatelessSagaVerifier__"
    }

    private var functionFilter: (MessageFunction<*, *, *>) -> Boolean = { true }

    /**
     * Converts an event object to a [DomainEvent] if necessary.
     *
     * If the event is already a [DomainEvent], it is returned as-is.
     * Otherwise, it wraps the event in a new [DomainEvent] with generated IDs.
     *
     * @param event The event to convert.
     * @param ownerId The owner ID for the domain event.
     * @return A [DomainEvent] instance.
     */
    private fun toDomainEvent(
        event: Any,
        ownerId: String
    ): DomainEvent<*> {
        if (event is DomainEvent<*>) {
            return event
        }
        return event.toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = TenantId.DEFAULT_TENANT_ID,
            commandId = STATELESS_SAGA_COMMAND_ID,
            ownerId = ownerId,
        )
    }

    /**
     * Injects services into the service provider for testing.
     *
     * @param inject A lambda function that configures services on the [ServiceProvider].
     * @return This stage instance for method chaining.
     */
    override fun inject(inject: ServiceProvider.() -> Unit): WhenStage<T> {
        inject(serviceProvider)
        return this
    }

    /**
     * Sets a filter for message functions to be considered during testing.
     *
     * @param filter A predicate function that determines which message functions to include.
     * @return This stage instance for method chaining.
     */
    override fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean): WhenStage<T> {
        functionFilter = filter
        return this
    }

    /**
     * Processes a domain event through the stateless saga and returns an expectation stage.
     *
     * This method sets up the saga processor, registers it with the command gateway,
     * and executes the appropriate saga function based on the event and optional state.
     * It returns a stage that allows setting expectations on the results.
     *
     * @param event The domain event to process.
     * @param state Optional state to provide to state-aware saga functions.
     * @param ownerId The owner ID for the event processing.
     * @return An expectation stage for verifying the saga results.
     */
    @Suppress("UNCHECKED_CAST")
    override fun whenEvent(
        event: Any,
        state: Any?,
        ownerId: String
    ): ExpectStage<T> {
        val sagaCtor = sagaMetadata.processorType.constructors.first() as Constructor<T>
        val processor: T = InjectableObjectFactory(sagaCtor, serviceProvider).newInstance()
        val handlerRegistrar = StatelessSagaFunctionRegistrar(commandGateway, commandMessageFactory)
        handlerRegistrar.registerProcessor(processor)
        val eventExchange = toEventExchange(event, state, ownerId)
        val expectedResultMono =
            handlerRegistrar.supportedFunctions(eventExchange.message)
                .filter {
                    if (state != null) {
                        it.functionKind == FunctionKind.STATE_EVENT
                    } else {
                        it.functionKind == FunctionKind.EVENT
                    }
                }.filter(functionFilter)
                .single()
                .invoke(eventExchange)
                .ofType(CommandStream::class.java)
                .map {
                    ExpectedResult(
                        exchange = eventExchange,
                        processor = processor,
                        commandStream = it,
                    )
                }.onErrorResume {
                    Mono.just(
                        ExpectedResult(
                            exchange = eventExchange,
                            processor = processor,
                            commandStream = null,
                            error = it,
                        ),
                    )
                }.switchIfEmpty {
                    Mono.just(
                        ExpectedResult(
                            exchange = eventExchange,
                            processor = processor,
                            commandStream = DefaultCommandStream(
                                domainEventId = eventExchange.message.id,
                                commands = listOf(),
                            ),
                        ),
                    )
                }

        return DefaultExpectStage(expectedResultMono)
    }

    /**
     * Creates a [DomainEventExchange] from the event and optional state.
     *
     * @param event The event to wrap in an exchange.
     * @param state Optional state for state-aware exchanges.
     * @param ownerId The owner ID for the event.
     * @return A configured [DomainEventExchange].
     */
    private fun toEventExchange(
        event: Any,
        state: Any?,
        ownerId: String
    ): DomainEventExchange<out Any> {
        val domainEvent = toDomainEvent(event, ownerId)
        return if (state == null) {
            SimpleDomainEventExchange(message = domainEvent)
        } else {
            val stateAggregate = state.toReadOnlyStateAggregate(domainEvent)
            SimpleStateDomainEventExchange(state = stateAggregate, message = domainEvent)
        }.setServiceProvider(serviceProvider)
    }
}

/**
 * Default implementation of [ExpectStage] for collecting and verifying expectations.
 *
 * This class accumulates expectation functions and provides methods to verify
 * them against the actual results of saga processing.
 *
 * @param T The type of the saga being tested.
 * @property expectedResultMono A Mono that provides the expected result when subscribed to.
 */
internal class DefaultExpectStage<T : Any>(
    private val expectedResultMono: Mono<ExpectedResult<T>>
) : ExpectStage<T> {
    /**
     * List of expectation functions to be applied to the result.
     */
    private val expectStates: MutableList<Consumer<ExpectedResult<T>>> = mutableListOf()

    /**
     * The actual expected result, computed lazily from the Mono.
     */
    private val expectedResult: ExpectedResult<T> by lazy(this) {
        lateinit var expectedResult: ExpectedResult<T>
        expectedResultMono.test()
            .consumeNextWith {
                expectedResult = it
            }.verifyComplete()
        expectedResult
    }

    /**
     * Adds an expectation function to be verified against the result.
     *
     * @param expected A lambda function that defines expectations on the [ExpectedResult].
     * @return This stage instance for method chaining.
     */
    override fun expect(expected: ExpectedResult<T>.() -> Unit): ExpectStage<T> {
        expectStates.add(expected)
        return this
    }

    /**
     * Verifies all accumulated expectations against the actual result.
     *
     * @param immediately If true, runs verification immediately and throws on failure.
     *                    If false, returns the result without verification.
     * @return The expected result.
     * @throws MultipleAssertionsError If any expectations fail and immediately is true.
     */
    override fun verify(immediately: Boolean): ExpectedResult<T> {
        if (immediately.not()) {
            return expectedResult
        }
        val assertionErrors = mutableListOf<AssertionError>()
        for (expectState in expectStates) {
            try {
                expectState.accept(expectedResult)
            } catch (e: AssertionError) {
                assertionErrors.add(e)
            }
        }
        if (assertionErrors.isNotEmpty()) {
            throw MultipleAssertionsError(assertionErrors)
        }
        return expectedResult
    }
}

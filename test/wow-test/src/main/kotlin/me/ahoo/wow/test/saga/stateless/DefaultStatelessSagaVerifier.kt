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
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.test.test
import java.lang.reflect.Constructor
import java.util.function.Consumer

internal class DefaultWhenStage<T : Any>(
    private val sagaMetadata: ProcessorMetadata<T, DomainEventExchange<*>>,
    private val serviceProvider: ServiceProvider,
    private val commandGateway: CommandGateway,
    private val commandMessageFactory: CommandMessageFactory
) : WhenStage<T> {
    companion object {
        const val STATELESS_SAGA_COMMAND_ID = "__StatelessSagaVerifier__"
    }

    private var functionFilter: (MessageFunction<*, *, *>) -> Boolean = { true }

    private fun toDomainEvent(event: Any, ownerId: String): DomainEvent<*> {
        if (event is DomainEvent<*>) {
            return event
        }
        return event.toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = TenantId.DEFAULT_TENANT_ID,
            commandId = STATELESS_SAGA_COMMAND_ID,
            ownerId = ownerId
        )
    }

    override fun inject(inject: ServiceProvider.() -> Unit): WhenStage<T> {
        inject(serviceProvider)
        return this
    }

    override fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean): WhenStage<T> {
        functionFilter = filter
        return this
    }

    @Suppress("UNCHECKED_CAST")
    override fun whenEvent(event: Any, state: Any?, ownerId: String): ExpectStage<T> {
        val sagaCtor = sagaMetadata.processorType.constructors.first() as Constructor<T>
        val processor: T = InjectableObjectFactory(sagaCtor, serviceProvider).newInstance()
        val handlerRegistrar = StatelessSagaFunctionRegistrar(commandGateway, commandMessageFactory)
        handlerRegistrar.registerProcessor(processor)
        val eventExchange = toEventExchange(event, state, ownerId)
        val expectedResultMono = handlerRegistrar.supportedFunctions(eventExchange.message)
            .filter {
                if (state != null) {
                    it.functionKind == FunctionKind.STATE_EVENT
                } else {
                    it.functionKind == FunctionKind.EVENT
                }
            }
            .filter(functionFilter)
            .single()
            .invoke(eventExchange)
            .ofType(CommandStream::class.java)
            .map {
                ExpectedResult(
                    exchange = eventExchange,
                    processor = processor,
                    commandStream = it,
                )
            }
            .onErrorResume {
                Mono.just(
                    ExpectedResult(
                        exchange = eventExchange,
                        processor = processor,
                        commandStream = null,
                        error = it
                    )
                )
            }.switchIfEmpty {
                Mono.just(
                    ExpectedResult(
                        exchange = eventExchange,
                        processor = processor,
                        commandStream = DefaultCommandStream(
                            domainEventId = eventExchange.message.id,
                            commands = listOf()
                        )
                    )
                )
            }

        return DefaultExpectStage(expectedResultMono)
    }

    private fun toEventExchange(event: Any, state: Any?, ownerId: String): DomainEventExchange<out Any> {
        val domainEvent = toDomainEvent(event, ownerId)
        return if (state == null) {
            SimpleDomainEventExchange(message = domainEvent)
        } else {
            val stateAggregate = state.toReadOnlyStateAggregate(domainEvent)
            SimpleStateDomainEventExchange(state = stateAggregate, message = domainEvent)
        }.setServiceProvider(serviceProvider)
    }
}

internal class DefaultExpectStage<T : Any>(private val expectedResult: Mono<ExpectedResult<T>>) : ExpectStage<T> {
    private val expectStates: MutableList<Consumer<ExpectedResult<T>>> = mutableListOf()

    override fun expect(expected: ExpectedResult<T>.() -> Unit): ExpectStage<T> {
        expectStates.add(expected)
        return this
    }

    override fun verify() {
        expectedResult
            .test()
            .consumeNextWith {
                for (expectState in expectStates) {
                    expectState.accept(it)
                }
            }
            .verifyComplete()
    }
}

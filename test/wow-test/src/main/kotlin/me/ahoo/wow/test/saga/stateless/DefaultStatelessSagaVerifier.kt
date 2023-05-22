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
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.asDomainEvent
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.accessor.constructor.InjectableObjectFactory
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.processor.ProcessorMetadata
import me.ahoo.wow.saga.stateless.CommandStream
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.test.test
import java.lang.reflect.Constructor

internal class DefaultWhenStage<T : Any>(
    private val sagaMetadata: ProcessorMetadata<T, DomainEventExchange<*>>,
    private val serviceProvider: ServiceProvider,
    private val commandBus: CommandBus,
) : WhenStage<T> {
    companion object {
        const val STATELESS_SAGA_COMMAND_ID = "__StatelessSagaVerifier__"
    }

    private fun asDomainEvent(event: Any): DomainEvent<*> {
        if (event is DomainEvent<*>) {
            return event
        }
        return event.asDomainEvent(
            aggregateId = GlobalIdGenerator.generateAsString(),
            tenantId = TenantId.DEFAULT_TENANT_ID,
            commandId = STATELESS_SAGA_COMMAND_ID,
        )
    }

    override fun <SERVICE : Any> inject(service: SERVICE): WhenStage<T> {
        serviceProvider.register(service)
        return this
    }

    @Suppress("UNCHECKED_CAST")
    override fun `when`(event: Any): ExpectStage<T> {
        val sagaCtor = sagaMetadata.processorType.constructors.first() as Constructor<T>
        val processor: T = InjectableObjectFactory(sagaCtor, serviceProvider).newInstance()
        val handlerRegistrar = StatelessSagaFunctionRegistrar()
        handlerRegistrar.registerStatelessSaga(processor, commandBus)

        val domainEvent = asDomainEvent(event)
        val eventExchange = SimpleDomainEventExchange(message = domainEvent).setServiceProvider(serviceProvider)
        val expectedResultMono = handlerRegistrar.getFunctions(domainEvent.body.javaClass)
            .first()
            .handle(eventExchange)
            .map {
                ExpectedResult(
                    processor = processor,
                    commandStream = it as CommandStream,
                )
            }
            .onErrorResume {
                Mono.just(ExpectedResult(processor = processor, commandStream = null, error = it))
            }.switchIfEmpty {
                Mono.just(ExpectedResult(processor = processor, commandStream = null))
            }

        return DefaultExpectStage(expectedResultMono)
    }
}

internal class DefaultExpectStage<T : Any>(private val expectedResult: Mono<ExpectedResult<T>>) : ExpectStage<T> {
    private val expectStates: MutableList<(ExpectedResult<T>) -> Unit> = mutableListOf()

    override fun expect(expected: (ExpectedResult<T>) -> Unit): ExpectStage<T> {
        expectStates.add(expected)
        return this
    }

    override fun verify() {
        expectedResult
            .test()
            .consumeNextWith {
                for (expectState in expectStates) {
                    expectState(it)
                }
            }
            .verifyComplete()
    }
}

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
package me.ahoo.wow.test

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.annotation.asEventProcessorMetadata
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.processor.ProcessorMetadata
import me.ahoo.wow.test.saga.stateless.DefaultWhenStage
import me.ahoo.wow.test.saga.stateless.WhenStage

/**
 * Stateless Saga Verifier .
 *
 * @author ahoo wang
 */
object StatelessSagaVerifier {
    @JvmStatic
    fun defaultCommandGateway(): CommandGateway {
        return DefaultCommandGateway(
            SimpleCommandWaitEndpoint("__StatelessSagaVerifier__"),
            InMemoryCommandBus(),
            NoOpIdempotencyChecker,
            SimpleWaitStrategyRegistrar,
            NoOpValidator,
        )
    }

    @JvmStatic
    fun <T : Any> Class<T>.asSagaVerifier(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway()
    ): WhenStage<T> {
        val sagaMetadata: ProcessorMetadata<T, DomainEventExchange<*>> = asEventProcessorMetadata()
        return DefaultWhenStage(
            sagaMetadata = sagaMetadata,
            serviceProvider = serviceProvider,
            commandGateway = commandGateway,
        )
    }

    @JvmStatic
    inline fun <reified T : Any> sagaVerifier(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway()
    ): WhenStage<T> {
        return T::class.java.asSagaVerifier(serviceProvider, commandGateway)
    }
}

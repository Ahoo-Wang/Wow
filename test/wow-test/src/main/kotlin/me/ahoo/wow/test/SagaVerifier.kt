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
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.annotation.eventProcessorMetadata
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.processor.ProcessorMetadata
import me.ahoo.wow.test.saga.stateless.DefaultWhenStage
import me.ahoo.wow.test.saga.stateless.WhenStage
import me.ahoo.wow.test.validation.TestValidator

/**
 * Utility object for creating and configuring stateless saga verifiers for testing.
 *
 * This object provides factory methods to create test environments for stateless sagas,
 * including default command gateways and when stages for defining test scenarios.
 * It simplifies the setup of saga testing by providing sensible defaults and
 * convenient extension methods.
 *
 * @author ahoo wang
 */
object SagaVerifier {
    /**
     * Creates a default command gateway configured for stateless saga testing.
     *
     * This method returns a pre-configured [CommandGateway] with in-memory components
     * suitable for testing sagas without external dependencies. It uses a test-specific
     * endpoint name and no-op idempotency checking for simplified test scenarios.
     *
     * @return A [CommandGateway] instance configured for testing.
     */
    @JvmStatic
    fun defaultCommandGateway(): CommandGateway =
        DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint("__StatelessSagaVerifier__"),
            commandBus = InMemoryCommandBus(),
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider { NoOpIdempotencyChecker },
            waitStrategyRegistrar = SimpleWaitStrategyRegistrar,
            commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar),
        )

    /**
     * Creates a when stage for testing a stateless saga of this class type.
     *
     * This extension method on [Class] creates a test environment for the specified
     * saga class, allowing you to define test scenarios using the fluent DSL.
     * It automatically extracts processor metadata from the class annotations.
     *
     * @param T The type of the saga class.
     * @param serviceProvider The service provider for dependency injection (defaults to simple provider).
     * @param commandGateway The command gateway for sending commands (defaults to test gateway).
     * @param commandMessageFactory The factory for creating command messages (defaults to simple factory with test validator).
     * @return A [WhenStage] for defining test scenarios.
     */
    @JvmStatic
    @JvmOverloads
    fun <T : Any> Class<T>.sagaVerifier(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway(),
        commandMessageFactory: CommandMessageFactory =
            SimpleCommandMessageFactory(
                validator = TestValidator,
                commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry(),
            )
    ): WhenStage<T> {
        val sagaMetadata: ProcessorMetadata<T, DomainEventExchange<*>> = eventProcessorMetadata()
        return DefaultWhenStage(
            sagaMetadata = sagaMetadata,
            serviceProvider = serviceProvider,
            commandGateway = commandGateway,
            commandMessageFactory = commandMessageFactory,
        )
    }

    /**
     * Creates a when stage for testing a stateless saga using reified generics.
     *
     * This inline function provides a convenient way to create saga verifiers
     * without explicitly specifying the class type. It uses reified generics
     * to automatically infer the saga class type.
     *
     * @param T The type of the saga (inferred automatically).
     * @param serviceProvider The service provider for dependency injection (defaults to simple provider).
     * @param commandGateway The command gateway for sending commands (defaults to test gateway).
     * @return A [WhenStage] for defining test scenarios.
     */
    @JvmStatic
    inline fun <reified T : Any> sagaVerifier(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway()
    ): WhenStage<T> = T::class.java.sagaVerifier(serviceProvider, commandGateway)
}

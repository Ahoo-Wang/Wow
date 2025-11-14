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
 * SagaVerifier provides a fluent API for testing stateless sagas using the Given/When/Expect pattern.
 * It simplifies saga testing by offering pre-configured components and convenient factory methods
 * that handle the complex setup of command gateways, service providers, and message factories.
 *
 * Key features:
 * - Pre-configured command gateway for isolated testing
 * - Automatic metadata extraction from saga class annotations
 * - Support for dependency injection via ServiceProvider
 * - Fluent DSL for defining test scenarios with domain events
 * - Type-safe testing with generics and reified types
 *
 * Example usage:
 * ```kotlin
 * SagaVerifier.sagaVerifier<OrderSaga>()
 *     .whenEvent(mockOrderCreatedEvent)
 *     .expectNoCommand()
 *     .verify()
 * ```
 *
 * @author ahoo wang
 */
object SagaVerifier {
    /**
     * Creates a default command gateway configured for stateless saga testing.
     *
     * This method returns a pre-configured CommandGateway with in-memory components
     * suitable for testing sagas in isolation without external dependencies. The gateway
     * uses a test-specific endpoint name and no-op idempotency checking to simplify
     * test scenarios and avoid external service interactions.
     *
     * The default configuration includes:
     * - In-memory command bus for local message handling
     * - Test validator for command validation
     * - No-op idempotency checker to prevent duplicate command filtering
     * - Local command wait notifier for synchronous testing
     *
     * Example:
     * ```kotlin
     * val gateway = SagaVerifier.defaultCommandGateway()
     * // Use gateway in custom saga verifier setup
     * ```
     *
     * @return a CommandGateway instance configured for testing stateless sagas
     * @throws Exception if gateway initialization fails
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
     * This extension method on Class<T> creates a complete test environment for the specified
     * saga class, allowing you to define test scenarios using the fluent DSL. It automatically
     * extracts processor metadata from the class annotations and sets up all necessary
     * components for saga testing.
     *
     * The method configures:
     * - Saga processor metadata extraction
     * - Service provider for dependency injection
     * - Command gateway for sending commands triggered by the saga
     * - Command message factory for creating command messages
     *
     * Example:
     * ```kotlin
     * CartSaga::class.java.sagaVerifier()
     *     .whenEvent(
     *         event = mockk<OrderCreated> { ... },
     *         ownerId = "owner123"
     *     )
     *     .expectCommandType(RemoveCartItem::class)
     *     .expectCommand<RemoveCartItem> { body.productIds.assert().hasSize(1) }
     *     .verify()
     * ```
     *
     * @param T the type of the saga class to be tested
     * @param serviceProvider the service provider for dependency injection, defaults to SimpleServiceProvider
     * @param commandGateway the command gateway for sending commands, defaults to defaultCommandGateway()
     * @param commandMessageFactory the factory for creating command messages, defaults to SimpleCommandMessageFactory with TestValidator
     * @return a WhenStage instance for defining test scenarios with domain events
     * @throws IllegalArgumentException if saga metadata cannot be resolved from class annotations
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
     * This inline function provides a convenient way to create saga verifiers without explicitly
     * specifying the Class type. It leverages Kotlin's reified generics to automatically infer
     * the saga class type at compile time, enabling type-safe testing without reflection overhead.
     *
     * This is the recommended method for creating saga verifiers in Kotlin code due to its
     * type safety and conciseness compared to the Class-based extension method.
     *
     * Example:
     * ```kotlin
     * sagaVerifier<OrderSaga>()
     *     .whenEvent(
     *         event = mockk<OrderCreated> {
     *             every { items } returns listOf(orderItem)
     *             every { fromCart } returns true
     *         },
     *         state = mockk<OrderState>()
     *     )
     *     .expectNoCommand()
     *     .verify()
     * ```
     *
     * @param T the reified type of the saga class (inferred automatically)
     * @param serviceProvider the service provider for dependency injection, defaults to SimpleServiceProvider
     * @param commandGateway the command gateway for sending commands, defaults to defaultCommandGateway()
     * @return a WhenStage instance for defining test scenarios with domain events
     * @throws IllegalArgumentException if saga metadata cannot be resolved from class annotations
     */
    @JvmStatic
    inline fun <reified T : Any> sagaVerifier(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway()
    ): WhenStage<T> = T::class.java.sagaVerifier(serviceProvider, commandGateway)
}

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

package me.ahoo.wow.test.saga.stateless.dsl

import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.test.SagaVerifier.defaultCommandGateway
import me.ahoo.wow.test.dsl.InjectServiceCapable
import me.ahoo.wow.test.dsl.NameSpecCapable
import me.ahoo.wow.test.dsl.TestDslMarker
import me.ahoo.wow.test.saga.stateless.StatelessSagaExpecter
import me.ahoo.wow.test.validation.TestValidator

/**
 * DSL interface for defining stateless saga test specifications.
 *
 * This interface provides the entry point for configuring and executing
 * stateless saga tests with customizable service providers, command gateways,
 * and message factories.
 *
 * @param T The type of the saga being tested.
 */
@TestDslMarker
interface StatelessSagaDsl<T : Any> : InjectPublicServiceCapable {
    /**
     * Defines a test scenario with optional custom components.
     *
     * This method sets up the test environment and executes the provided
     * test block that defines the "when" conditions and expectations.
     *
     * @param serviceProvider The service provider for dependency injection (defaults to simple provider).
     * @param commandGateway The command gateway for sending commands (defaults to test gateway).
     * @param commandMessageFactory The factory for creating command messages (defaults to simple factory with test validator).
     * @param block The test specification block that defines the scenario.
     */
    fun on(
        serviceProvider: ServiceProvider = SimpleServiceProvider(),
        commandGateway: CommandGateway = defaultCommandGateway(),
        commandMessageFactory: CommandMessageFactory =
            SimpleCommandMessageFactory(
                validator = TestValidator,
                commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry(),
            ),
        block: WhenDsl<T>.() -> Unit
    )
}

/**
 * DSL interface for defining the "when" conditions in saga testing.
 *
 * This interface allows specifying which events trigger the saga and
 * configuring the test environment.
 *
 * @param T The type of the saga being tested.
 */
@TestDslMarker
interface WhenDsl<T : Any> :
    NameSpecCapable,
    InjectServiceCapable<Unit> {
    /**
     * Sets a filter for message functions to be considered during testing.
     *
     * @param filter A predicate function that determines which message functions to include.
     */
    fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean)

    /**
     * Filters message functions by name.
     *
     * This is a convenience method that filters functions to only include
     * those with the specified name.
     *
     * @param functionName The name of the function to include.
     */
    fun functionName(functionName: String) {
        functionFilter {
            it.name == functionName
        }
    }

    /**
     * Defines an event that triggers the saga and sets expectations on the result.
     *
     * @param event The domain event that triggers the saga.
     * @param state Optional state to provide to the saga processing.
     * @param ownerId The owner ID for the event processing.
     * @param block The expectation block that defines assertions on the saga results.
     */
    fun whenEvent(
        event: Any,
        state: Any? = null,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
        block: ExpectDsl<T>.() -> Unit
    )
}

/**
 * DSL interface for defining expectations on saga test results.
 *
 * This interface extends [StatelessSagaExpecter] to provide a fluent API
 * for asserting expectations on the commands and errors produced by saga processing.
 *
 * @param T The type of the saga being tested.
 */
@TestDslMarker
interface ExpectDsl<T : Any> : StatelessSagaExpecter<T, ExpectDsl<T>>

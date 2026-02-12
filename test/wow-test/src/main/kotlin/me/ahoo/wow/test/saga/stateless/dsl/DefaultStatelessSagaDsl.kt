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

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder

/**
 * Default implementation of [StatelessSagaDsl] for creating dynamic saga tests.
 *
 * This class provides the entry point for configuring and executing stateless saga tests
 * with customizable service providers, command gateways, and message factories.
 * It creates dynamic test nodes that can be executed by JUnit 5.
 *
 * @param T The type of the saga being tested.
 * @property processorType The class of the saga processor.
 * @property publicServiceProvider A service provider for public services accessible during testing.
 */
class DefaultStatelessSagaDsl<T : Any>(
    private val processorType: Class<T>
) : AbstractDynamicTestBuilder(),
    StatelessSagaDsl<T> {
    override val publicServiceProvider: ServiceProvider = SimpleServiceProvider()

    /**
     * Configures and executes a test scenario with the provided components.
     *
     * This method sets up the test environment by copying public services to the
     * provided service provider, creating a saga verifier, and executing the
     * test block that defines the "when" conditions and expectations.
     * The resulting dynamic test nodes are collected for JUnit 5 execution.
     *
     * @param serviceProvider The service provider for dependency injection.
     * @param commandGateway The command gateway for sending commands.
     * @param commandMessageFactory The factory for creating command messages.
     * @param block The test specification block defining the scenario.
     */
    override fun on(
        serviceProvider: ServiceProvider,
        commandGateway: CommandGateway,
        commandMessageFactory: CommandMessageFactory,
        block: WhenDsl<T>.() -> Unit
    ) {
        publicServiceProvider.copyTo(serviceProvider)
        val whenStage = processorType.sagaVerifier(
            serviceProvider = serviceProvider,
            commandGateway = commandGateway,
            commandMessageFactory = commandMessageFactory
        )
        val whenDsl = DefaultWhenDsl(whenStage)
        block(whenDsl)
        dynamicNodes.addAll(whenDsl.dynamicNodes)
    }
}

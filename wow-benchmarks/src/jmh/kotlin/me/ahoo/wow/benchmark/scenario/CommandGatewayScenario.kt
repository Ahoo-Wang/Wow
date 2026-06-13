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

package me.ahoo.wow.benchmark.scenario

import jakarta.validation.Validator
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.test.validation.TestValidator
import reactor.core.Disposable

class CommandGatewayScenario private constructor(
    val commandBus: CommandBus,
    val commandGateway: CommandGateway,
    val commandWaitNotifier: CommandWaitNotifier,
    val waitCoordinator: WaitCoordinator,
    private val subscription: Disposable?,
) : AutoCloseable {

    override fun close() {
        subscription?.dispose()
        commandGateway.close()
    }

    companion object {
        fun inMemory(
            validator: Validator = TestValidator,
            idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
                DefaultAggregateIdempotencyCheckerProvider {
                    NoOpIdempotencyChecker
                },
            waitCoordinator: WaitCoordinator = DefaultWaitCoordinator(),
            commandWaitNotifier: CommandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator),
            commandWaitEndpoint: CommandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            subscribeToCart: Boolean = true,
        ): CommandGatewayScenario {
            return create(
                commandBus = InMemoryCommandBus(),
                validator = validator,
                idempotencyCheckerProvider = idempotencyCheckerProvider,
                waitCoordinator = waitCoordinator,
                commandWaitNotifier = commandWaitNotifier,
                commandWaitEndpoint = commandWaitEndpoint,
                subscribeToCart = subscribeToCart,
            )
        }

        fun create(
            commandBus: CommandBus = InMemoryCommandBus(),
            validator: Validator = TestValidator,
            idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
                DefaultAggregateIdempotencyCheckerProvider {
                    NoOpIdempotencyChecker
                },
            waitCoordinator: WaitCoordinator = DefaultWaitCoordinator(),
            commandWaitNotifier: CommandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator),
            commandWaitEndpoint: CommandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            subscribeToCart: Boolean = true,
        ): CommandGatewayScenario {
            val commandGateway = DefaultCommandGateway(
                commandWaitEndpoint = commandWaitEndpoint,
                commandBus = commandBus,
                validator = validator,
                idempotencyCheckerProvider = idempotencyCheckerProvider,
                waitCoordinator = waitCoordinator,
                commandWaitNotifier = commandWaitNotifier,
            )
            val subscription = if (subscribeToCart) {
                commandGateway
                    .receive(setOf(BenchmarkAggregates.cartMetadata.namedAggregate.materialize()))
                    .subscribe()
            } else {
                null
            }
            return CommandGatewayScenario(
                commandBus = commandBus,
                commandGateway = commandGateway,
                commandWaitNotifier = commandWaitNotifier,
                waitCoordinator = waitCoordinator,
                subscription = subscription,
            )
        }
    }
}

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

package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class StatelessSagaFunctionRequestIdTest {

    @Test
    fun `command builder without request id uses domain event id and iterable index`() {
        val event = fixtureEvent()
        val function = StatelessSagaFunction(
            delegate = StubMessageFunction(
                Mono.just(
                    listOf(
                        MockCreateAggregate("next-id", "create").commandBuilder(),
                        MockCreateAggregate("next-id", "change").commandBuilder(),
                    )
                )
            ),
            commandGateway = recordingCommandGateway(),
            commandMessageFactory = commandMessageFactory(),
        )

        StepVerifier.create(function.invoke(SimpleDomainEventExchange(event)))
            .assertNext { stream ->
                stream.map { it.requestId }.toList().assert().containsExactly(
                    "${event.id}-0",
                    "${event.id}-1",
                )
            }.verifyComplete()
    }

    @Test
    fun `command builder with request id keeps existing request id`() {
        val event = fixtureEvent()
        val function = StatelessSagaFunction(
            delegate = StubMessageFunction(
                Mono.just(
                    MockCreateAggregate("next-id", "create")
                        .commandBuilder()
                        .requestId("explicit-request")
                )
            ),
            commandGateway = recordingCommandGateway(),
            commandMessageFactory = commandMessageFactory(),
        )

        StepVerifier.create(function.invoke(SimpleDomainEventExchange(event)))
            .assertNext { stream ->
                stream.single().requestId.assert().isEqualTo("explicit-request")
            }.verifyComplete()
    }

    private fun recordingCommandGateway(): CommandGateway =
        mockk {
            every { send(any<CommandMessage<*>>()) } returns Mono.empty()
        }
}

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
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class StatelessSagaFunctionRegistrarBehaviorTest {

    @Test
    fun `resolve processor wraps metadata functions as stateless saga functions`() {
        val registrar = StatelessSagaFunctionRegistrar(
            commandGateway = mockk(),
            commandMessageFactory = mockk(),
        )

        val functions = registrar.resolveProcessor(FixtureSaga())

        functions.assert().hasSize(1)
        functions.single().assert().isInstanceOf(StatelessSagaFunction::class.java)
        functions.single().name.assert().isEqualTo("onCreated")
        functions.single().supportedType.assert().isEqualTo(MockAggregateCreated::class.java)
    }

    @Test
    fun `register processor exposes supported stateless saga function`() {
        val registrar = StatelessSagaFunctionRegistrar(
            commandGateway = mockk<CommandGateway> {
                every { send(any<CommandMessage<*>>()) } returns Mono.empty()
            },
            commandMessageFactory = mockk<CommandMessageFactory>(),
        )
        val event = mockDomainEvent(MockAggregateCreated("created"))

        registrar.registerProcessor(FixtureSaga())

        registrar.functions.assert().hasSize(1)
        registrar.supportedFunctions(event).toSet().assert().hasSize(1)
    }
}

@StatelessSaga
private class FixtureSaga {
    @OnEvent
    @Suppress("UNUSED_PARAMETER")
    fun onCreated(created: MockAggregateCreated) = Unit
}

private fun mockDomainEvent(body: MockAggregateCreated): DomainEvent<MockAggregateCreated> {
    val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
    return mockk {
        every { this@mockk.body } returns body
        every { contextName } returns namedAggregate.contextName
        every { aggregateName } returns namedAggregate.aggregateName
    }
}

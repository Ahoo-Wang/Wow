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

package me.ahoo.wow.projection

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class ProjectionFunctionRegistrarTest {

    @Test
    fun `register processor resolves annotated projection functions from metadata`() {
        val registrar = ProjectionFunctionRegistrar()

        registrar.registerProcessor(FixtureProjection())

        registrar.functions.assert().hasSize(2)
        registrar.functions.map { it.functionKind }.toSet().assert().isEqualTo(
            setOf(FunctionKind.EVENT, FunctionKind.STATE_EVENT)
        )
        registrar.functions.map { it.supportedType }.toSet().assert().isEqualTo(
            setOf(MockAggregateCreated::class.java, MockAggregateChanged::class.java)
        )
    }

    @Test
    fun `supported functions invoke projection processor methods`() {
        val registrar = ProjectionFunctionRegistrar()
        val projection = FixtureProjection()
        val created = mockDomainEvent(MockAggregateCreated("created"))
        val changed = mockDomainEvent(MockAggregateChanged("changed"))

        registrar.registerProcessor(projection)

        registrar.supportedFunctions(created).toSet().assert().hasSize(1)
        StepVerifier.create(registrar.supportedFunctions(created).single().invoke(SimpleDomainEventExchange(created)))
            .verifyComplete()
        projection.events.assert().containsExactly("created:created")

        registrar.supportedFunctions(changed).toSet().assert().hasSize(1)
        StepVerifier.create(registrar.supportedFunctions(changed).single().invoke(SimpleDomainEventExchange(changed)))
            .verifyComplete()
        projection.events.assert().containsExactly("created:created", "changed:changed")
    }
}

private class FixtureProjection {
    val events: MutableList<String> = mutableListOf()

    @OnEvent
    fun onCreated(created: MockAggregateCreated) {
        events.add("created:${created.data}")
    }

    @OnStateEvent
    fun onChanged(changed: MockAggregateChanged) {
        events.add("changed:${changed.data}")
    }
}

private inline fun <reified T : Any> mockDomainEvent(body: T): DomainEvent<T> {
    val namedAggregate = requiredNamedAggregate<T>()
    return mockk {
        every { this@mockk.body } returns body
        every { contextName } returns namedAggregate.contextName
        every { aggregateName } returns namedAggregate.aggregateName
    }
}

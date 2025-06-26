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
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test

internal class ProjectionProcessorRegistrarTest {

    @Test
    fun registerProjectionProcessor() {
        val handlerRegistrar = ProjectionFunctionRegistrar()
        val mockProjector = MockProjector()
        handlerRegistrar.registerProcessor(mockProjector)
        handlerRegistrar.functions.assert().hasSize(2)
        val messageMockAggregateCreated = mockk<DomainEvent<MockAggregateCreated>> {
            every { body } returns MockAggregateCreated("data")
            every { contextName } returns requiredNamedAggregate<MockAggregateCreated>().contextName
            every { aggregateName } returns requiredNamedAggregate<MockAggregateCreated>().aggregateName
        }
        handlerRegistrar.supportedFunctions(messageMockAggregateCreated).toSet().assert().hasSize(1)

        val messageMockAggregateChanged = mockk<DomainEvent<MockAggregateChanged>> {
            every { body } returns MockAggregateChanged("data")
            every { contextName } returns requiredNamedAggregate<MockAggregateChanged>().contextName
            every { aggregateName } returns requiredNamedAggregate<MockAggregateChanged>().aggregateName
        }
        handlerRegistrar.supportedFunctions(messageMockAggregateChanged).toSet().assert().hasSize(1)
    }
}

internal class MockProjector {
    lateinit var data: String

    fun onEvent(created: MockAggregateCreated) {
        data = created.data
    }

    fun onEvent(changed: MockAggregateChanged) {
        data = changed.data
    }
}

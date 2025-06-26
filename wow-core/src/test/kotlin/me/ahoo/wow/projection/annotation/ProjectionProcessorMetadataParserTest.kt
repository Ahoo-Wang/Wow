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

package me.ahoo.wow.projection.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

internal class ProjectionProcessorMetadataParserTest {

    @Test
    fun projectorMetadata() {
        val metadata = projectionProcessorMetadata<MockProjector>()
        metadata.processorType.assert().isEqualTo(MockProjector::class.java)
        metadata.contextName.assert().isEqualTo("wow")
        metadata.name.assert().isEqualTo("MockProjector")
        metadata.functionRegistry.size.assert().isEqualTo(3)

        metadata.functionRegistry.map { it.supportedType }.toSet().assert()
            .containsExactly(MockAggregateCreated::class.java, MockAggregateChanged::class.java)
    }

    @Test
    fun asTarget() {
        val aggregateMetadata = MOCK_AGGREGATE_METADATA
        val mockProjector = MockProjector()
        val eventHandlerRegistry = projectionProcessorMetadata<MockProjector>().toMessageFunctionRegistry(mockProjector)
        val createdState = GlobalIdGenerator.generateAsString()
        val created = MockAggregateChanged(createdState).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateMetadata.aggregateId(GlobalIdGenerator.generateAsString())),
            aggregateVersion = 0,
        ).first()
        eventHandlerRegistry.first {
            it.supportedType == created.body.javaClass
        }.invoke(SimpleDomainEventExchange(created)).block()
        mockProjector.data.assert().isEqualTo(createdState)
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

    @OnEvent
    fun onAggregateChanged(changed: MockAggregateChanged) {
        data = changed.data
    }
}

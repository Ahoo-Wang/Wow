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

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.tck.modeling.AggregateChanged
import me.ahoo.wow.tck.modeling.AggregateCreated
import me.ahoo.wow.tck.modeling.MockAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test

internal class ProjectionProcessorMetadataParserTest {

    @Test
    fun projectorMetadata() {
        val metadata = projectionProcessorMetadata<MockProjector>()
        assertThat(metadata.processorType, equalTo(MockProjector::class.java))
        assertThat(metadata.contextName, equalTo("wow-core-test"))
        assertThat(metadata.name, equalTo("mock_projector"))
        assertThat(metadata.functionRegistry.size, equalTo(3))
        assertThat(
            metadata.functionRegistry.map { it.supportedType }.toSet(),
            hasItems(AggregateCreated::class.java, AggregateChanged::class.java),
        )
    }

    @Test
    fun asTarget() {
        val aggregateMetadata = aggregateMetadata<MockAggregate, MockAggregate>()
        val mockProjector = MockProjector()
        val eventHandlerRegistry = projectionProcessorMetadata<MockProjector>().asMessageFunctionRegistry(mockProjector)
        val createdState = GlobalIdGenerator.generateAsString()
        val created = AggregateChanged(createdState).asDomainEventStream(
            command = GivenInitializationCommand(aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())),
            aggregateVersion = 0,
        ).first()
        eventHandlerRegistry.first {
            it.supportedType == created.body.javaClass
        }.handle(SimpleDomainEventExchange(created)).block()
        assertThat(mockProjector.state, equalTo(createdState))
    }
}

internal class MockProjector {
    lateinit var state: String

    fun onEvent(created: AggregateCreated) {
        state = created.state
    }

    fun onEvent(changed: AggregateChanged) {
        state = changed.state
    }

    @OnEvent
    fun onAggregateChanged(changed: AggregateChanged) {
        state = changed.state
    }
}

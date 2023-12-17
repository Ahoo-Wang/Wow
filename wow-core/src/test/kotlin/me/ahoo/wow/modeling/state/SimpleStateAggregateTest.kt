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
package me.ahoo.wow.modeling.state

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.event.IgnoreSourcing
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate.Companion.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class SimpleStateAggregateTest {
    private val aggregateMetadata = MOCK_AGGREGATE_METADATA

    @Test
    fun testMetadataHashCode() {
        val stateAggregateMetadata = aggregateMetadata.state
        assertThat(stateAggregateMetadata.hashCode(), equalTo(stateAggregateMetadata.aggregateType.hashCode()))
    }

    @Test
    fun testMetadataToString() {
        val stateAggregateMetadata = aggregateMetadata.state
        assertThat(
            stateAggregateMetadata.toString(),
            equalTo("StateAggregateMetadata(aggregateType=${stateAggregateMetadata.aggregateType})")
        )
    }

    @Test
    fun testMetadataEq() {
        val stateAggregateMetadata = aggregateMetadata.state
        assertThat(
            stateAggregateMetadata.equals(stateAggregateMetadata),
            equalTo(true)
        )
        assertThat(
            stateAggregateMetadata.equals(Any()),
            equalTo(false)
        )
        assertThat(
            stateAggregateMetadata.equals(
                mockk<StateAggregateMetadata<MockStateAggregate>> {
                    every {
                        aggregateType
                    } returns MockStateAggregate::class.java
                }
            ),
            equalTo(true)
        )
    }

    @Test
    fun id() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        assertThat(stateAggregate.aggregateId.id, equalTo(mockAggregate.id))
        assertThat(stateAggregate.initialized, equalTo(false))
        assertThat(
            stateAggregate.toString(),
            equalTo("SimpleStateAggregate(aggregateId=${stateAggregate.aggregateId}, version=0)")
        )
    }

    @Test
    fun createWhenFullArgs() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate =
            SimpleStateAggregate(
                aggregateId = aggregateMetadata.aggregateId(mockAggregate.id),
                version = 0,
                state = mockAggregate,
                metadata = aggregateMetadata.state,
            )
        assertThat(stateAggregate, notNullValue())
    }

    @Test
    fun version() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 1)
        assertThat(stateAggregate.version, equalTo(1))
    }

    @Test
    fun aggregateRoot() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 1)
        assertThat(stateAggregate.state, equalTo(mockAggregate))
    }

    @Test
    fun aggregateType() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 1)
        assertThat(
            stateAggregate.aggregateType,
            equalTo<Class<out MockStateAggregate>>(mockAggregate.javaClass),
        )
    }

    @Test
    fun sourcing() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("changed")
        val domainEventStream = changed.toDomainEventStream(
            command = GivenInitializationCommand(stateAggregate.aggregateId),
            aggregateVersion = stateAggregate.version,
        )

        stateAggregate.onSourcing(domainEventStream)
        assertThat(mockAggregate.data, equalTo(changed.data))
    }

    @Test
    fun sourcingGivenFailVersion() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("changed")
        val streamHeadVersion = stateAggregate.version + 1
        val domainEventStream = changed.toDomainEventStream(
            command = GivenInitializationCommand(stateAggregate.aggregateId),
            aggregateVersion = streamHeadVersion,
        )

        var thrown = false
        try {
            stateAggregate.onSourcing(domainEventStream)
        } catch (conflictException: SourcingVersionConflictException) {
            thrown = true
            assertThat(conflictException.eventStream, equalTo(domainEventStream))
            assertThat(conflictException.expectVersion, equalTo(1))
        }
        assertThat(thrown, equalTo(true))
    }

    @Test
    fun sourcingGivenWrongAggregateId() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("changed")
        val streamHeadVersion = stateAggregate.version + 1
        val domainEventStream = changed.toDomainEventStream(
            command = GivenInitializationCommand(
                stateAggregate.aggregateId.aggregateId(GlobalIdGenerator.generateAsString()),
            ),
            aggregateVersion = streamHeadVersion,
        )
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            stateAggregate.onSourcing(
                domainEventStream,
            )
        }
    }

    /**
     * 当聚合未找到匹配的 `onSourcing` 方法时，不会认为产生的故障，忽略该事件，但更新聚合版本号为该领域事件的版本号。
     */
    @Test
    fun sourcingGivenWrongEvent() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val wrongEvent = WrongEvent()
        val domainEventStream = wrongEvent.toDomainEventStream(
            command = GivenInitializationCommand(stateAggregate.aggregateId),
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(domainEventStream)
        assertThat(stateAggregate.version, equalTo(1))
    }

    @Test
    fun sourcingGivenErrorIgnoreEvent() {
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val errorIgnoreEvent =
            ErrorIgnoreEvent(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
        val domainEventStream = errorIgnoreEvent.toDomainEventStream(
            command = GivenInitializationCommand(stateAggregate.aggregateId),
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(domainEventStream)
        assertThat(stateAggregate.version, equalTo(0))
    }

    class WrongEvent

    data class ErrorIgnoreEvent(override val errorCode: String, override val errorMsg: String) :
        IgnoreSourcing,
        ErrorInfo
}

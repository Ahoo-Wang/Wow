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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.api.event.AggregateRecovered
import me.ahoo.wow.api.event.IgnoreSourcing
import me.ahoo.wow.api.event.OwnerTransferred
import me.ahoo.wow.api.event.SpaceTransferred
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class SimpleStateAggregateTest {
    private val aggregateMetadata = MOCK_AGGREGATE_METADATA

    @Test
    fun testMetadataHashCode() {
        val stateAggregateMetadata = aggregateMetadata.state
        stateAggregateMetadata.hashCode().assert().isEqualTo(stateAggregateMetadata.aggregateType.hashCode())
    }

    @Test
    fun testMetadataToString() {
        val stateAggregateMetadata = aggregateMetadata.state

        stateAggregateMetadata
            .toString()
            .assert()
            .isEqualTo("StateAggregateMetadata(aggregateType=${stateAggregateMetadata.aggregateType})")
    }

    @Test
    fun testMetadataEq() {
        val stateAggregateMetadata = aggregateMetadata.state

        stateAggregateMetadata.equals(stateAggregateMetadata).assert().isEqualTo(true)

        stateAggregateMetadata.equals(Any()).assert().isEqualTo(false)

        stateAggregateMetadata
            .equals(
                mockk<StateAggregateMetadata<MockStateAggregate>> {
                    every {
                        aggregateType
                    } returns MockStateAggregate::class.java
                },
            ).assert()
            .isEqualTo(true)
    }

    @Test
    fun toStateAggregate() {
        val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val stateAggregate =
            aggregateMetadata.state.toStateAggregate(
                aggregateId = aggregateMetadata.aggregateId(),
                state = MockStateAggregate(generateGlobalId()),
                ownerId = generateGlobalId(),
                eventId = generateGlobalId(),
                firstOperator = generateGlobalId(),
                operator = generateGlobalId(),
                firstEventTime = System.currentTimeMillis(),
                version = Version.INITIAL_VERSION,
                eventTime = System.currentTimeMillis(),
                deleted = true,
            )

        stateAggregate.version.assert().isEqualTo(Version.INITIAL_VERSION)
        stateAggregate.deleted.assert().isEqualTo(true)
        stateAggregate.hashCode().assert().isNotEqualTo(0)
        stateAggregate.assert().isEqualTo(stateAggregate)
    }

    @Test
    fun id() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        stateAggregate.aggregateId.id
            .assert()
            .isEqualTo(mockAggregate.id)
        stateAggregate.initialized.assert().isEqualTo(false)

        stateAggregate
            .toString()
            .assert()
            .isEqualTo("SimpleStateAggregate(aggregateId=${stateAggregate.aggregateId}, version=0)")
    }

    @Test
    fun createWhenFullArgs() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate =
            SimpleStateAggregate(
                aggregateId = aggregateMetadata.aggregateId(mockAggregate.id),
                version = 0,
                state = mockAggregate,
                metadata = aggregateMetadata.state,
            )
        stateAggregate.assert().isNotNull()
    }

    @Test
    fun version() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 1)
        stateAggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun aggregateRoot() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 1)
        stateAggregate.state.assert().isEqualTo(mockAggregate)
    }

    @Test
    fun sourcing() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("changed")
        val domainEventStream =
            changed.toDomainEventStream(
                upstream =
                GivenInitializationCommand(
                    stateAggregate.aggregateId,
                    header = DefaultHeader(mutableMapOf("operator" to "operator")),
                ),
                aggregateVersion = stateAggregate.version,
            )

        stateAggregate.onSourcing(domainEventStream)
        mockAggregate.data.assert().isEqualTo(changed.data)
    }

    @Test
    fun sourcingGivenFailVersion() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("changed")
        val streamHeadVersion = stateAggregate.version + 1
        val domainEventStream =
            changed.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = streamHeadVersion,
            )

        var thrown = false
        try {
            stateAggregate.onSourcing(domainEventStream)
        } catch (conflictException: SourcingVersionConflictException) {
            thrown = true
            conflictException.eventStream.assert().isEqualTo(domainEventStream)
            conflictException.expectVersion.assert().isEqualTo(1)
        }
        thrown.assert().isEqualTo(true)
    }

    @Test
    fun sourcingGivenWrongAggregateId() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("changed")
        val streamHeadVersion = stateAggregate.version + 1
        val domainEventStream =
            changed.toDomainEventStream(
                upstream =
                GivenInitializationCommand(
                    stateAggregate.aggregateId.aggregateId(generateGlobalId()),
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
    fun sourcingGivenErrorIgnoreEvent() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val errorIgnoreEvent =
            ErrorIgnoreEvent(generateGlobalId(), generateGlobalId())
        val domainEventStream =
            errorIgnoreEvent.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )
        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.version.assert().isEqualTo(0)
    }

    @Test
    fun sourcingWithAggregateDeleted() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        stateAggregate.deleted.assert().isEqualTo(false)

        val deletedEvent = TestAggregateDeleted()
        val domainEventStream =
            deletedEvent.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )

        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.deleted.assert().isEqualTo(true)
        stateAggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun sourcingWithAggregateRecovered() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        (stateAggregate as SimpleStateAggregate<*>).deleted = true // Simulate previously deleted

        val recoveredEvent = TestAggregateRecovered()
        val domainEventStream =
            recoveredEvent.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )

        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.deleted.assert().isEqualTo(false)
        stateAggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun sourcingWithOwnerTransferred() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val newOwnerId = generateGlobalId()
        stateAggregate.ownerId.assert().isNotEqualTo(newOwnerId)

        val transferredEvent = TestOwnerTransferred(newOwnerId)
        val domainEventStream =
            transferredEvent.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )

        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.ownerId.assert().isEqualTo(newOwnerId)
        stateAggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun sourcingWithSpaceTransferred() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val newSpaceId = generateGlobalId()
        stateAggregate.ownerId.assert().isNotEqualTo(newSpaceId)

        val transferredEvent = TestSpaceTransferred(newSpaceId)
        val domainEventStream =
            transferredEvent.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )

        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.spaceId.assert().isEqualTo(newSpaceId)
        stateAggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun sourcingWithNormalEventHavingSourcingFunction() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val changed = MockAggregateChanged("updated")
        val domainEventStream =
            changed.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )

        stateAggregate.onSourcing(domainEventStream)
        mockAggregate.data.assert().isEqualTo(changed.data)
        stateAggregate.version.assert().isEqualTo(1)
    }

    @Test
    fun sourcingWithEventMissingSourcingFunction() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 0)
        val normalEvent = TestNormalEvent("test")
        val domainEventStream =
            normalEvent.toDomainEventStream(
                upstream = GivenInitializationCommand(stateAggregate.aggregateId),
                aggregateVersion = stateAggregate.version,
            )

        // Should not throw, just log and ignore
        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.version.assert().isEqualTo(1)
        // State should remain unchanged
        mockAggregate.data.assert().isEqualTo("")
    }

    @Test
    fun onSourcingUpdatesMetadata() {
        val mockAggregate = MockStateAggregate(generateGlobalId())

        val stateAggregate =
            aggregateMetadata.toStateAggregate(state = mockAggregate, 0)
        val eventTime = System.currentTimeMillis()

        val changed = MockAggregateChanged("changed")
        val operator = generateGlobalId()
        val header = DefaultHeader.empty()
            .withOperator(operator)
        val domainEventStream = changed.toDomainEventStream(
            upstream = GivenInitializationCommand(stateAggregate.aggregateId, header = header),
            aggregateVersion = stateAggregate.version,
            createTime = eventTime,
        )

        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.version.assert().isEqualTo(1)
        stateAggregate.eventId.assert().isEqualTo(domainEventStream.id)
        stateAggregate.operator.assert().isEqualTo(operator)
        stateAggregate.eventTime.assert().isEqualTo(eventTime)
        stateAggregate.firstOperator.assert().isEqualTo(operator)
        stateAggregate.firstEventTime.assert().isEqualTo(eventTime)
    }

    @Test
    fun onSourcingWithInitialVersion() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, Version.INITIAL_VERSION)
        val operator = generateGlobalId()
        val eventTime = System.currentTimeMillis()

        val changed = MockAggregateChanged("changed")
        val domainEventStream =
            changed.toDomainEventStream(
                upstream =
                GivenInitializationCommand(
                    stateAggregate.aggregateId,
                    header = DefaultHeader(mutableMapOf("operator" to operator)),
                ),
                aggregateVersion = stateAggregate.version,
                createTime = eventTime,
            )

        stateAggregate.onSourcing(domainEventStream)
        stateAggregate.firstOperator.assert().isEqualTo("") // Should not update since not initial
        stateAggregate.firstEventTime.assert().isEqualTo(0L)
    }

    @Test
    fun equalsAndHashCode() {
        val mockAggregate1 = MockStateAggregate(generateGlobalId())
        val stateAggregate1 = aggregateMetadata.toStateAggregate(mockAggregate1, 1)
        val stateAggregate2 = aggregateMetadata.toStateAggregate(mockAggregate1, 1)
        val mockAggregate3 = MockStateAggregate(generateGlobalId())
        val stateAggregate3 = aggregateMetadata.toStateAggregate(mockAggregate3, 1)
        val stateAggregate4 = aggregateMetadata.toStateAggregate(mockAggregate1, 2)

        stateAggregate1.assert().isEqualTo(stateAggregate2)
        stateAggregate1.hashCode().assert().isEqualTo(stateAggregate2.hashCode())

        stateAggregate1.assert().isNotEqualTo(stateAggregate3)
        stateAggregate1.assert().isNotEqualTo(stateAggregate4)
        stateAggregate1.assert().isNotEqualTo(null)
        stateAggregate1.assert().isNotEqualTo(Any())
    }

    @Test
    fun toStringFormat() {
        val mockAggregate = MockStateAggregate(generateGlobalId())
        val stateAggregate = aggregateMetadata.toStateAggregate(mockAggregate, 5)
        stateAggregate
            .toString()
            .assert()
            .isEqualTo("SimpleStateAggregate(aggregateId=${stateAggregate.aggregateId}, version=5)")
    }

    class WrongEvent

    data class ErrorIgnoreEvent(
        override val errorCode: String,
        override val errorMsg: String
    ) : IgnoreSourcing,
        ErrorInfo

    // Additional test events for comprehensive coverage
    class TestAggregateDeleted : AggregateDeleted

    class TestAggregateRecovered : AggregateRecovered

    data class TestOwnerTransferred(
        override val toOwnerId: String
    ) : OwnerTransferred

    data class TestSpaceTransferred(
        override val toSpaceId: SpaceId
    ) : SpaceTransferred

    data class TestNormalEvent(
        val value: String
    )
}

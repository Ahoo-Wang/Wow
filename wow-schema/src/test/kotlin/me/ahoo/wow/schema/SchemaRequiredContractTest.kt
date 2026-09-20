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

package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventData
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import java.lang.reflect.Type
import java.util.stream.Stream

/**
 * Guards the message envelope schemas against losing `required` entries.
 *
 * [JsonSchemaValidatorTest] only catches schemas that are too strict for a real payload; a `required`
 * entry that goes missing keeps every payload valid and slips through. The envelope serializers write
 * their properties unconditionally, so every property a real payload carries has to be declared required.
 *
 * Only the top level is checked: nested payloads are user types whose properties may carry Kotlin default
 * values, and message headers are free-form, so neither is unconditional.
 */
class SchemaRequiredContractTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().wowModule(
        WowModule(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))
    ).build()

    companion object {
        private val typeResolver = TypeResolver()

        @Suppress("LongMethod")
        @JvmStatic
        fun parametersForRequiredContract(): Stream<Arguments> {
            val mockStateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(
                MockStateAggregate(generateGlobalId()),
                eventId = generateGlobalId(),
                version = 1
            )
            return Stream.of(
                Arguments.of(AggregateId::class.java, MOCK_AGGREGATE_METADATA.aggregateId()),
                Arguments.of(
                    typeResolver.resolve(CommandMessage::class.java, CreateTestAggregate::class.java),
                    SimpleCommandMessage(
                        body = CreateTestAggregate(
                            name = "test",
                            address = TestAddress(country = "CN", city = "SZ", district = "NS"),
                            items = listOf(
                                TestItem(
                                    productId = generateGlobalId(),
                                    quantity = 1,
                                    price = java.math.BigDecimal.TEN,
                                )
                            ),
                            pathId = generateGlobalId(),
                            headerToken = "token",
                        ),
                        aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                    )
                ),
                Arguments.of(
                    typeResolver.resolve(DomainEvent::class.java, TestAggregateCreated::class.java),
                    SimpleDomainEvent(
                        body = TestAggregateCreated(
                            name = "test",
                            address = TestAddress(country = "CN", city = "SZ", district = "NS"),
                        ),
                        aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                        version = 1,
                        commandId = generateGlobalId(),
                    )
                ),
                Arguments.of(
                    DomainEventStream::class.java,
                    MockDomainEventStreams.generateEventStream(MOCK_AGGREGATE_METADATA.aggregateId())
                ),
                Arguments.of(
                    typeResolver.resolve(StateAggregate::class.java, MockStateAggregate::class.java),
                    mockStateAggregate
                ),
                Arguments.of(
                    typeResolver.resolve(Snapshot::class.java, MockStateAggregate::class.java),
                    SimpleSnapshot(mockStateAggregate)
                ),
                Arguments.of(
                    typeResolver.resolve(StateEvent::class.java, MockStateAggregate::class.java),
                    StateEventData(
                        delegate = MockDomainEventStreams.generateEventStream(
                            MOCK_AGGREGATE_METADATA.aggregateId()
                        ),
                        state = mockStateAggregate.state
                    )
                )
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForRequiredContract")
    fun `every serialized envelope property should be declared required`(type: Type, targetObject: Any) {
        val schema = jsonSchemaGenerator.generateSchema(type)
        val required = schema.path("required").toList().map { it.stringValue() }.toSet()
        val declared = schema.path("properties").properties().map { it.key }.toSet()
        val serializedNames = targetObject.toJsonNode<JsonNode>().properties().map { it.key }
        serializedNames.filterNot { declared.contains(it) }.assert().isEmpty()
        serializedNames.filterNot { required.contains(it) }.assert().isEmpty()
    }
}

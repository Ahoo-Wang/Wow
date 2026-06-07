package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
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
import java.math.BigDecimal
import java.util.stream.Stream

class JsonSchemaValidatorTest {

    private val jsonSchemaGenerator = SchemaGeneratorBuilder().wowModule(
        WowModule(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))
    ).build()

    companion object {
        private val typeResolver = TypeResolver()

        @Suppress("LongMethod")
        @JvmStatic
        fun parametersForValidate(): Stream<Arguments> {
            val testAggregateCreated = TestAggregateCreated(
                name = "test",
                address = TestAddress(country = "CN", city = "SZ", district = "NS"),
            )

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
                                    price = BigDecimal.TEN,
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
                        body = testAggregateCreated,
                        aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                        version = 1,
                        commandId = generateGlobalId(),
                    )
                ),
                Arguments.of(
                    DomainEventStream::class.java,
                    MockDomainEventStreams.generateEventStream(
                        MOCK_AGGREGATE_METADATA.aggregateId()
                    )
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
    @MethodSource("parametersForValidate")
    fun `should validate object against schema`(type: Type, targetObject: Any) {
        val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val schemaJsonNode = jsonSchemaGenerator.generateSchema(type)
        val jsonSchema = schemaRegistry.getSchema(schemaJsonNode)
        val input = targetObject.toJsonNode<JsonNode>()
        val assertions = jsonSchema.validate(input)
        assertions.assert().isEmpty()
    }
}

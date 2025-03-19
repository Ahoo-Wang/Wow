package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaId
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion.VersionFlag
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventData
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.serialization.toPrettyJson
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.lang.reflect.Type
import java.math.BigDecimal
import java.util.stream.Stream

class JsonSchemaValidatorTest {

    private val jsonSchemaGenerator = JsonSchemaGenerator(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))

    companion object {
        private val typeResolver = TypeResolver()

        @Suppress("LongMethod")
        @JvmStatic
        fun parametersForValidate(): Stream<Arguments> {
            val orderCreated = OrderCreated(
                orderId = generateGlobalId(),
                items = listOf(
                    OrderItem(
                        generateGlobalId(),
                        generateGlobalId(),
                        BigDecimal.TEN,
                        quantity = 1
                    )
                ),
                address = ShippingAddress(
                    country = "CN",
                    province = "广东省",
                    city = "深圳市",
                    district = "宝安区",
                    detail = "宝安区龙岗大道"
                ),
                fromCart = false
            )

            val stateAggregate = aggregateMetadata<Order, OrderState>().toStateAggregate(
                OrderState(
                    generateGlobalId()
                ).also {
                    it.onSourcing(
                        orderCreated
                    )
                },
                eventId = generateGlobalId(),
                version = 1
            )
            return Stream.of(
                Arguments.of(AggregateId::class.java, MOCK_AGGREGATE_METADATA.aggregateId()),
                Arguments.of(
                    typeResolver.resolve(CommandMessage::class.java, CreateOrder::class.java),
                    CreateOrder(
                        items = listOf(
                            CreateOrder.Item(
                                generateGlobalId(),
                                BigDecimal.TEN,
                                quantity = 1
                            )
                        ),
                        address = ShippingAddress(
                            country = "CN",
                            province = "广东省",
                            city = "深圳市",
                            district = "宝安区",
                            detail = "宝安区龙岗大道"
                        ),
                        fromCart = false
                    ).toCommandMessage()
                ),
                Arguments.of(
                    typeResolver.resolve(DomainEvent::class.java, OrderCreated::class.java),
                    orderCreated.toDomainEvent(MOCK_AGGREGATE_METADATA.aggregateId(), generateGlobalId())
                ),
                Arguments.of(
                    DomainEventStream::class.java,
                    MockDomainEventStreams.generateEventStream(
                        MOCK_AGGREGATE_METADATA.aggregateId()
                    )
                ),
                Arguments.of(
                    typeResolver.resolve(StateAggregate::class.java, OrderState::class.java),
                    stateAggregate
                ),
                Arguments.of(
                    typeResolver.resolve(Snapshot::class.java, OrderState::class.java),
                    SimpleSnapshot(stateAggregate)
                ),
                Arguments.of(
                    typeResolver.resolve(StateEvent::class.java, OrderState::class.java),
                    StateEventData(
                        MockDomainEventStreams.generateEventStream(
                            MOCK_AGGREGATE_METADATA.aggregateId()
                        ),
                        stateAggregate
                    )
                )
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForValidate")
    fun validate(type: Type, targetObject: Any) {
        val jsonSchemaFactory = JsonSchemaFactory.getInstance(VersionFlag.V202012) { builder ->
            builder.schemaLoaders {
                it.schemas {
                    jsonSchemaGenerator.generate(type).toPrettyString()
                }
            }
        }
        val builder = SchemaValidatorsConfig.builder()
        val config = builder.build()
        val input = targetObject.toPrettyJson()
        val schema: JsonSchema = jsonSchemaFactory.getSchema(SchemaLocation.of(SchemaId.V202012), config)
        val assertions = schema.validate(input, InputFormat.JSON) { executionContext ->
            executionContext.executionConfig.formatAssertionsEnabled = true
        }
        assertThat(assertions.firstOrNull()?.error.orEmpty(), assertions.isEmpty(), equalTo(true))
    }
}
